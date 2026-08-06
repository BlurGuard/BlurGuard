package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.BoundingBox
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.engine.ml.isDebugBuild
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))
    }

    private val embeddingSize: Int by lazy {
        // Pin the input to a concrete shape and allocate before reading the
        // output shape — some converted models report 0/dynamic dims until then.
        interpreter.resizeInput(0, intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))
        interpreter.allocateTensors()
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        if (debugLogging) {
            Log.i(
                TAG,
                "model spec: input=${inShape.contentToString()} " +
                        "${interpreter.getInputTensor(0).dataType()} " +
                        "output=${outShape.contentToString()} " +
                        "${interpreter.getOutputTensor(0).dataType()}"
            )
        }
        val size = outShape.last()
        require(size > 0) { "Unusable output shape ${outShape.contentToString()}" }
        size
    }

    private val debugLogging: Boolean = context.isDebugBuild()

    private val diagnostics = RecognizerDiagnostics(enabled = debugLogging, tag = TAG)

    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    }
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    /**
     * Serializes model use. Real embeds are already serialized by the ml
     * dispatcher; this exists so a tap arriving during warm-up cannot run a
     * second concurrent inference on the same non-thread-safe interpreter.
     */
    private val modelLock = Mutex()

    private val warmUpScope = CoroutineScope(SupervisorJob())

    init {
        // Both interpreters are `by lazy`, so without this the first tap pays
        // for two model loads (~460 ms measured). Hilt builds this singleton
        // when the camera screen opens, well before any tap, so the load is
        // free real time. Deliberately NOT on dispatcherProvider.ml: stalling
        // the detection thread at camera-open would leave faces unblurred.
        warmUpScope.launch(dispatcherProvider.io) { warmUp() }
    }

    /**
     * Forces model load and one inference through each model. Uses throwaway
     * buffers so the shared [inputBuffer] and [pixels] scratch are never
     * touched from this thread. Failures are logged and swallowed — a failed
     * warm-up must degrade to the old lazy behavior, never crash the camera.
     */
    private suspend fun warmUp() = modelLock.withLock {
        try {
            val blank = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            // Returns null (no face in a blank image) but builds the MediaPipe
            // detector and runs one real BlazeFace pass.
            aligner.align(blank)
            blank.recycle()

            val scratch = ByteBuffer
                .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            scratch.rewind()
            val output = Array(1) { FloatArray(embeddingSize) }
            interpreter.run(scratch, output)

            if (debugLogging) Log.i(TAG, "warm-up complete")
        } catch (e: Exception) {
            Log.w(TAG, "warm-up failed; falling back to lazy init on first use", e)
        }
    }

    override suspend fun embed(
        frame: ImageProxy,
        faceBox: BoundingBox,
        metadata: FrameMetadata
    ): FaceEmbedding? = withContext(dispatcherProvider.ml) {
        modelLock.withLock {
            val t0 = diagnostics.mark()

            // 1) Upright bitmap. faceBox is in upright normalized space, so rotate
            //    the buffer first and crop with no coordinate gymnastics.
            val raw = frame.toBitmap()
            val tBitmap = diagnostics.mark()
            val upright = raw.rotatedToUpright(metadata.rotationDegrees)
            val tRotate = diagnostics.mark()

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
            val tCrop = diagnostics.mark()

            // 3) Landmark alignment. Null = quality gate failed = no decision.
            val aligned = aligner.align(crop) ?: run {
                diagnostics.recordSkip()
                return@withLock null
            }
            val tAlign = diagnostics.mark()

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
                return@withLock null
            }
            val tInfer = diagnostics.mark()

            diagnostics.record(t0, tBitmap, tRotate, tCrop, tAlign, tInfer)

            // 5) L2 normalize; degenerate vectors become null (fail-closed).
            FaceEmbedding.fromRaw(output[0])
        }
    }

    override fun close() {
        warmUpScope.cancel()
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

    private companion object {
        const val TAG = "FaceRecognizer"
        const val MODEL_ASSET = "mobilefacenet.tflite"
        const val INPUT_SIZE = SimilarityTransform.OUTPUT_SIZE // 112
        const val NUM_THREADS = 2
        /** Margin around the tracker box so landmarks get facial context. */
        const val CROP_DILATION = 0.25f
    }
}