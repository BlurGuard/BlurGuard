package com.nash.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.ImageProxy
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
 * LiteRT (CPU/XNNPACK — GPU stays reserved for the render path).
 *
 * Input: 320x320x3 RGB float32 (0..1), letterboxed. Output: [1, 6, 2100]
 * (cx, cy, w, h, face score, plate score). Class count is read from the
 * tensor shape at init, so swapping in a retrained model with more classes
 * only requires updating [CLASSES].
 */
class YoloDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: DetectorConfig
) : Detector<ImageProxy> {

    private val interpreter: Interpreter by lazy {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        buffer.put(model)
        buffer.rewind()
        Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))
    }

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

        val input = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
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
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255f)          // B
        }
        inputBuffer.rewind()

        // --- Inference.
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[1]) { FloatArray(outputShape[2]) }
        interpreter.run(inputBuffer, arrayOf(output))

        // --- Decode in buffer space, then rotate to upright space.
        decoder.decode(
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
    }

    override fun close() {
        interpreter.close()
    }

    private companion object {
        const val MODEL_ASSET = "yolo_face_plate.tflite"
        const val INPUT_SIZE = 320
        const val NUM_THREADS = 4

        /** Class order MUST match the training data.yaml. */
        val CLASSES = listOf(
            DetectionClass.FACE,          // index 0
            DetectionClass.LICENSE_PLATE  // index 1
        )
    }
}