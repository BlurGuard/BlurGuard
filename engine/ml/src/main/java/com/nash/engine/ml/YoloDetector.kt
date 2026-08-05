package com.nash.engine.ml

import android.content.Context
import androidx.camera.core.ImageProxy
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FrameMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
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
 * Collaborators: [TfliteInterpreterFactory] owns runtime creation and delegate
 * lifecycle, [YoloPreprocessor] owns frame -> tensor conversion,
 * [YoloOutputDecoder] owns raw-tensor decoding, [DetectorDiagnostics] owns
 * split timings and throttled debug logging.
 */
class YoloDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: DetectorConfig
) : Detector<ImageProxy> {

    /** Lazy so the model load happens on first use (ml dispatcher), not at DI time. */
    private val runtime: TfliteRuntime by lazy {
        TfliteInterpreterFactory(context).create(
            modelAsset = MODEL_ASSET,
            delegate = config.delegate,
            modelToken = MODEL_TOKEN
        )
    }

    private val interpreter: Interpreter get() = runtime.interpreter

    private val diagnostics = DetectorDiagnostics(
        enabled = context.isDebugBuild(),
        tag = TAG,
        // Only evaluated inside the once-per-second log branch, so touching
        // the lazy runtime here costs nothing on the hot path.
        backendLabel = { if (runtime.isAccelerated) "nnapi" else "cpu" }
    )

    /** Lazy: the layout flag can only be read once the interpreter exists. */
    private val preprocessor: YoloPreprocessor by lazy {
        YoloPreprocessor(inputSize = INPUT_SIZE, channelsFirst = inputIsChannelsFirst)
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

    override suspend fun detect(
        frame: ImageProxy,
        metadata: FrameMetadata
    ): List<DetectionBox> = withContext(dispatcherProvider.ml) {

        val frameStart = diagnostics.mark()
        val letterbox = preprocessor.process(frame)
        val preprocessEnd = diagnostics.mark()

        // --- Inference.
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[1]) { FloatArray(outputShape[2]) }
        interpreter.run(preprocessor.inputBuffer, arrayOf(output))
        val inferenceEnd = diagnostics.mark()

        // --- Decode in buffer space, then rotate to upright space.
        val result = decoder.decode(
            output = output,
            letterbox = letterbox
        ).map { detection ->
            detection.copy(box = detection.box.rotatedToUpright(metadata.rotationDegrees))
        }
        val decodeEnd = diagnostics.mark()

        diagnostics.record(frameStart, preprocessEnd, inferenceEnd, decodeEnd)
        result
    }

    override fun close() {
        runtime.close()
    }

    private companion object {
        const val TAG = "YoloDetector"
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