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
 * Input: 320x320x3 RGB float32 (0..1), letterboxed. Output: [1, 4+nc, candidates]
 * (cx, cy, w, h, then per-class scores). The class count is read from the tensor
 * shape once at init, so swapping in a retrained model with more classes only
 * requires updating [CLASSES].
 *
 * This class is orchestration only. Its collaborators own the work:
 * - [TfliteInterpreterFactory] / [TfliteRuntime] — model loading, delegate
 *   policy, NNAPI -> CPU fallback, delegate lifecycle.
 * - [YoloPreprocessor] — `ImageProxy` -> letterboxed input tensor.
 * - [YoloOutputDecoder] — raw tensor -> normalized [DetectionBox]es.
 * - [DetectorDiagnostics] — split timings and throttled debug logging.
 *
 * ## Per-frame allocation policy
 * The input buffer (in the preprocessor) and the output buffer (here) are
 * allocated once and reused. As with the preprocessor, this is only sound
 * because detection runs on the *serialized* ml dispatcher — one frame in
 * flight at a time. Do not call [detect] concurrently, and do not retain
 * anything reachable from the output buffer past the end of a call; the
 * decoded [DetectionBox] list is a fresh, safe-to-keep value.
 */
class YoloDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: DetectorConfig
) : Detector<ImageProxy> {

    /** Lazy so the model load happens on first use (ml dispatcher), not at DI time. */
    private val runtimeDelegate = lazy {
        TfliteInterpreterFactory(context).create(
            modelAsset = MODEL_ASSET,
            delegate = config.delegate,
            modelToken = MODEL_TOKEN
        )
    }

    private val runtime: TfliteRuntime by runtimeDelegate

    private val interpreter: Interpreter get() = runtime.interpreter

    private val diagnostics = DetectorDiagnostics(
        enabled = context.isDebugBuild(),
        tag = TAG,
        // Only evaluated inside the once-per-second log branch, so touching
        // the lazy runtime here costs nothing on the hot path.
        backendLabel = { if (runtime.isAccelerated) "nnapi" else "cpu" }
    )

    /** Model output geometry, inspected exactly once. */
    private class OutputSpec(val channels: Int, val candidates: Int)

    /**
     * Reads `[1, 4+nc, candidates]` from the interpreter once and validates the
     * class mapping. Never called from [detect].
     */
    private val outputSpec: OutputSpec by lazy {
        val shape = interpreter.getOutputTensor(0).shape()
        val numClasses = shape[1] - 4
        check(numClasses == CLASSES.size) {
            "Model has $numClasses classes but CLASSES maps ${CLASSES.size}"
        }
        OutputSpec(channels = shape[1], candidates = shape[2])
    }

    /** Reused across frames; fully overwritten by every `Interpreter.run`. */
    private val outputBuffer: Array<FloatArray> by lazy {
        Array(outputSpec.channels) { FloatArray(outputSpec.candidates) }
    }

    /**
     * The batch wrapper LiteRT expects as the output argument. Hoisted out of
     * [detect] so the frame path does not allocate a one-element array per
     * inference.
     */
    private val outputContainer: Array<Array<FloatArray>> by lazy { arrayOf(outputBuffer) }

    /** Lazy: the layout flag can only be read once the interpreter exists. */
    private val preprocessor: YoloPreprocessor by lazy {
        YoloPreprocessor(inputSize = INPUT_SIZE, channelsFirst = inputIsChannelsFirst)
    }

    private val decoder: YoloOutputDecoder by lazy {
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

        interpreter.run(preprocessor.inputBuffer, outputContainer)
        val inferenceEnd = diagnostics.mark()

        // Decode in buffer space, then rotate into upright space.
        val result = decoder.decode(output = outputBuffer, letterbox = letterbox)
            .map { detection ->
                detection.copy(box = detection.box.rotatedToUpright(metadata.rotationDegrees))
            }
        val decodeEnd = diagnostics.mark()

        diagnostics.record(frameStart, preprocessEnd, inferenceEnd, decodeEnd)
        result
    }

    override fun close() {
        // Guarded: closing a detector that never ran must not trigger the lazy
        // and load the model just to close it.
        if (runtimeDelegate.isInitialized()) runtime.close()
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