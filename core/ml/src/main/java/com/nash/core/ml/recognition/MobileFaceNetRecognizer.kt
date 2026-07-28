package com.nash.core.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.BoundingBox
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MobileFaceNet embedding extractor: upright frame -> dilated crop -> BlazeFace
 * landmark alignment (FaceAligner) -> 112x112 -> embedding -> L2 normalize.
 *
 * CPU/XNNPACK, 2 threads — same accelerator policy as YoloDetector (GPU stays
 * dedicated to the anonymization renderer). Runs sporadically on the ml
 * dispatcher, never per frame.
 */
@Singleton
class MobileFaceNetRecognizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: RecognitionConfig
) : FaceRecognizer<ImageProxy> {

    private val aligner by lazy { FaceAligner(context, config) }

    private val interpreter: Interpreter by lazy {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        buffer.put(model)
        buffer.rewind()
        val tempInterperter: Interpreter =Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))

        Log.i(TAG, "model spec: input=${tempInterperter.getInputTensor(0).shape().contentToString()} " +
                "${tempInterperter.getInputTensor(0).dataType()} " +
                "output=${tempInterperter.getOutputTensor(0).shape().contentToString()} " +
                "${tempInterperter.getOutputTensor(0).dataType()}")

        tempInterperter
    }


    private val embeddingSize: Int by lazy {
        // Pin the input to a concrete shape and allocate before reading the
        // output shape — some converted models report 0/dynamic dims until then.
        interpreter.resizeInput(0, intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))
        interpreter.allocateTensors()
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        Log.i(TAG, "model spec (allocated): input=${inShape.contentToString()} " +
                "${interpreter.getInputTensor(0).dataType()} " +
                "output=${outShape.contentToString()} ${interpreter.getOutputTensor(0).dataType()}")
        val size = outShape.last()
        require(size > 0) { "Unusable output shape ${outShape.contentToString()}" }
        size
    }

    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    }
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    // Split-timing probes (debug), same EMA pattern as YoloDetector.
    private var emaAlignMs = 0.0
    private var emaInferMs = 0.0
    private var timedCalls = 0L
    private var lastLogUptimeMs = 0L

    override suspend fun embed(
        frame: ImageProxy,
        faceBox: BoundingBox,
        metadata: FrameMetadata
    ): FaceEmbedding? = withContext(dispatcherProvider.ml) {
//        Log.d(TAG, "input=${interpreter.getInputTensor(0).shape().contentToString()} " +
//                "${interpreter.getInputTensor(0).dataType()} " +
//                "output=${interpreter.getOutputTensor(0).shape().contentToString()}")
        val t0 = SystemClock.elapsedRealtimeNanos()

        // 1) Upright bitmap. faceBox is in upright normalized space, so rotate
        //    the buffer first and crop with no coordinate gymnastics.
        val upright = frame.toBitmap().rotatedToUpright(metadata.rotationDegrees)

        // 2) Dilated crop (margin gives the landmark model context), clamped.
        val margin = CROP_DILATION
        val left = ((faceBox.left - margin * width(faceBox)) * upright.width).toInt()
            .coerceIn(0, upright.width - 1)
        val top = ((faceBox.top - margin * height(faceBox)) * upright.height).toInt()
            .coerceIn(0, upright.height - 1)
        val right = ((faceBox.right + margin * width(faceBox)) * upright.width).toInt()
            .coerceIn(left + 1, upright.width)
        val bottom = ((faceBox.bottom + margin * height(faceBox)) * upright.height).toInt()
            .coerceIn(top + 1, upright.height)
        val crop = Bitmap.createBitmap(upright, left, top, right - left, bottom - top)

        // 3) Landmark alignment. Null = quality gate failed = no decision.
        val aligned = aligner.align(crop) ?: return@withContext null
        val t1 = SystemClock.elapsedRealtimeNanos()

        // 4) Preprocess: MobileFaceNet convention (x - 127.5) / 127.5, NHWC RGB.
        aligned.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 127.5f)
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 127.5f)
            inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 127.5f)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(embeddingSize) }
        try {
            interpreter.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
            return@withContext null
        }
        val t2 = SystemClock.elapsedRealtimeNanos()

        logTimings((t1 - t0) / 1e6, (t2 - t1) / 1e6)

        // 5) L2 normalize; degenerate vectors become null (fail-closed).
        FaceEmbedding.fromRaw(output[0])
    }

    override fun close() {
        aligner.close()
        interpreter.close()
    }

    private fun Bitmap.rotatedToUpright(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return this
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun width(box: BoundingBox) = box.right - box.left
    private fun height(box: BoundingBox) = box.bottom - box.top

    private fun logTimings(alignMs: Double, inferMs: Double) {
        emaAlignMs = if (timedCalls == 0L) alignMs else emaAlignMs * 0.9 + alignMs * 0.1
        emaInferMs = if (timedCalls == 0L) inferMs else emaInferMs * 0.9 + inferMs * 0.1
        timedCalls++
        val now = SystemClock.uptimeMillis()
        if (now - lastLogUptimeMs >= 1000) {
            lastLogUptimeMs = now
//            Log.d(TAG, "align=%.1fms infer=%.1fms calls=%d"
//                .format(emaAlignMs, emaInferMs, timedCalls))
        }
    }

    private companion object {
        const val TAG = "FaceRecognizer"
        const val MODEL_ASSET = "mobilefacenet.tflite"
        const val INPUT_SIZE = SimilarityTransform.OUTPUT_SIZE // 112
        const val NUM_THREADS = 2
        /** Margin around the tracker box so landmarks get facial context. */
        const val CROP_DILATION = 0.25f
    }
}