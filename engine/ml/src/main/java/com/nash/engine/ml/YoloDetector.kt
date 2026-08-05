package com.nash.engine.ml

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FrameMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * Fine-tuned YOLO detector (faces + license plates, single model) running on
 * LiteRT (NNAPI when available, otherwise CPU/XNNPACK — GPU stays reserved for
 * the render path).
 *
 * Input: 320x320x3 RGB float32 (0..1), letterboxed. Output: [1, 6, 2100]
 * (cx, cy, w, h, face score, plate score). Class count is read from the
 * tensor shape at init, so swapping in a retrained model with more classes
 * only requires updating [CLASSES].
 *
 * Runtime creation (model loading, delegate policy, delegate lifecycle) is
 * owned by [TfliteInterpreterFactory]; this class only orchestrates a frame.
 */
class YoloDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: DetectorConfig
) : Detector<ImageProxy> {

    // --- Split-timing probes (debug). EMA-smoothed, logged once per second.
    private var emaPreprocessMs = 0.0
    private var emaInferenceMs = 0.0
    private var emaDecodeMs = 0.0
    private var timedFrames = 0L
    private var lastLogUptimeMs = 0L

    private fun updateEma(current: Double, sample: Double): Double =
        if (timedFrames == 0L) sample else current * 0.9 + sample * 0.1

    /** Lazy so the model load happens on first use (ml dispatcher), not at DI time. */
    private val runtime: TfliteRuntime by lazy {
        TfliteInterpreterFactory(context).create(
            modelAsset = MODEL_ASSET,
            delegate = config.delegate,
            modelToken = MODEL_TOKEN
        )
    }

    private val interpreter: Interpreter get() = runtime.interpreter

    private val decoder: YoloOutputDecoder by lazy {
        val outputShape = interpreter.getOutputTensor(0).shape() // [1, 4+nc, candidates]
        val numClasses = outputShape[1] - 4
        check(numClasses == CLASSES.size) {
            "Model has $numClasses classes but CLASSES maps ${CLASSES.size}"
        }
        YoloOutputDecoder(
            classes = CLASSES,
            inputSize = INPUT_SIZE,
            confidenceThreshold = config.minConfidence
        )
    }

    // Reused across frames; only touched from the serialized ml dispatcher.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    override suspend fun detect(
        frame: ImageProxy,
        metadata: FrameMetadata
    ): List<DetectionBox> = withContext(dispatcherProvider.ml) {

        val t0 = SystemClock.elapsedRealtimeNanos()
        val bitmap = frame.toBitmap()
        // --- Letterbox into the model input, gray padding (Ultralytics convention).
        val scale = min(
            INPUT_SIZE / bitmap.width.toFloat(),
            INPUT_SIZE / bitmap.height.toFloat()
        )
        val scaledWidth = round(bitmap.width * scale).toInt()
        val scaledHeight = round(bitmap.height * scale).toInt()
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f

        val input = createBitmap(INPUT_SIZE, INPUT_SIZE)
        Canvas(input).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                bitmap,
                null,
                Rect(
                    padX.toInt(),
                    padY.toInt(),
                    padX.toInt() + scaledWidth,
                    padY.toInt() + scaledHeight
                ),
                null
            )
        }

        // --- Bitmap -> float32 RGB buffer, 0..1.
        input.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputBuffer.rewind()
        if (inputIsChannelsFirst) {
            // NCHW: full R plane, then G, then B.
            for (pixel in pixels) inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            for (pixel in pixels) inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            for (pixel in pixels) inputBuffer.putFloat((pixel and 0xFF) / 255f)
        } else {
            // NHWC: interleaved RGB per pixel.
            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()
        val t1 = SystemClock.elapsedRealtimeNanos()

        // --- Inference.
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[1]) { FloatArray(outputShape[2]) }
        interpreter.run(inputBuffer, arrayOf(output))

        // TODO(step 3): dead scan, removed together with DetectorDiagnostics.
        var maxScore = 0f
        var maxCoord = 0f
        for (c in output.indices) {
            val row = output[c]
            for (v in row) {
                if (c < 4) { if (v > maxCoord) maxCoord = v }
                else { if (v > maxScore) maxScore = v }
            }
        }
        val t2 = SystemClock.elapsedRealtimeNanos()

        // --- Decode in buffer space, then rotate to upright space.
        val result = decoder.decode(
            output = output,
            letterbox = YoloOutputDecoder.Letterbox(
                scale = scale,
                padX = padX,
                padY = padY,
                bufferWidth = bitmap.width,
                bufferHeight = bitmap.height
            )
        ).map { detection ->
            detection.copy(box = detection.box.rotatedToUpright(metadata.rotationDegrees))
        }
        val t3 = SystemClock.elapsedRealtimeNanos()
        emaPreprocessMs = updateEma(emaPreprocessMs, (t1 - t0) / 1e6)
        emaInferenceMs = updateEma(emaInferenceMs, (t2 - t1) / 1e6)
        emaDecodeMs = updateEma(emaDecodeMs, (t3 - t2) / 1e6)
        timedFrames++

        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastLogUptimeMs >= 1_000L) {
            lastLogUptimeMs = nowMs
        }
        result
    }

    override fun close() {
        runtime.close()
    }

    private companion object {
        const val MODEL_ASSET = "best_int8_320.tflite"
        const val INPUT_SIZE = 320

        /** Compilation-cache token; bump whenever [MODEL_ASSET] changes. */
        const val MODEL_TOKEN = "yolo_face_plate_v1"

        /** Class order MUST match the training data.yaml. */
        val CLASSES = listOf(
            DetectionClass.LICENSE_PLATE,          // index 0
            DetectionClass.FACE  // index 1
        )
    }

    private val inputIsChannelsFirst: Boolean by lazy {
        val shape = interpreter.getInputTensor(0).shape()
        shape[1] == 3 && shape[3] != 3
    }
}