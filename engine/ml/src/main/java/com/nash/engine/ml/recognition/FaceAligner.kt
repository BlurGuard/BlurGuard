package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.nash.core.model.RecognitionConfig
import com.nash.engine.ml.isDebugBuild
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Aligns a face crop to the ArcFace 112x112 template using BlazeFace keypoints.
 *
 * Runs a dedicated short-range MediaPipe FaceDetector on the (already upright,
 * already cropped) face region — the crop makes every face a "near" face, so
 * the short-range model limitation from the full-frame days does not apply.
 *
 * Every quality-gate failure returns null. Null means "no embedding this
 * frame"; the caller must stay fail-closed (keep blurring).
 *
 * ml-dispatcher only; not thread-safe.
 */
internal class FaceAligner(
    context: Context,
    private val config: RecognitionConfig
) {

    /**
     * Debug-only gate logging. Same contract as DetectorDiagnostics: when this
     * is false nothing is formatted and nothing is logged.
     */
    private val debugLogging: Boolean = context.isDebugBuild()

    /** Lazy so model load happens on first use (on the ml dispatcher), not DI time. */
    private val detector: FaceDetector by lazy {
        FaceDetector.createFromOptions(
            context,
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(MIN_LANDMARK_DETECTION_CONFIDENCE)
                .build()
        )
    }

    /**
     * @param faceCrop Upright RGB face crop (dilated box already applied).
     * @return 112x112 aligned bitmap, or null if any quality gate failed.
     */
    fun align(faceCrop: Bitmap): Bitmap? {
        if (faceCrop.width < config.minFaceCropPx || faceCrop.height < config.minFaceCropPx) {
            logGate { "crop too small ${faceCrop.width}x${faceCrop.height} (min ${config.minFaceCropPx})" }
            return null
        }

        val result = detector.detect(BitmapImageBuilder(faceCrop).build())
        // Exactly-one gate: zero faces = nothing to align; several faces in one
        // crop = ambiguous, and ambiguity must never feed an unblur decision.
        val detection = result.detections().singleOrNull() ?: run {
            logGate { "${result.detections().size} detections in crop (need exactly 1)" }
            return null
        }
        val keypoints = detection.keypoints().orElse(null) ?: run {
            logGate { "detection carried no keypoints" }
            return null
        }
        if (keypoints.size <= KP_NOSE) {
            logGate { "only ${keypoints.size} keypoints" }
            return null
        }

        fun toPx(kp: NormalizedKeypoint) =
            floatArrayOf(kp.x() * faceCrop.width, kp.y() * faceCrop.height)

        // Robust to keypoint-order differences: image-left eye = smaller x.
        val eyes = listOf(toPx(keypoints[KP_EYE_A]), toPx(keypoints[KP_EYE_B])).sortedBy { it[0] }
        val leftEye = eyes[0]   // image-left  -> template (38.29, 51.69)
        val rightEye = eyes[1]  // image-right -> template (73.53, 51.50)
        val nose = toPx(keypoints[KP_NOSE])

        // Gate: eyes far enough apart to carry identity information.
        val eyeDx = rightEye[0] - leftEye[0]
        val eyeDy = rightEye[1] - leftEye[1]
        val interEye = hypot(eyeDx, eyeDy)
        if (interEye < MIN_INTER_EYE_PX) {
            logGate { "interEye=${fmt(interEye)}px < $MIN_INTER_EYE_PX" }
            return null
        }

        // Gate: roughly frontal — profile faces produce unreliable embeddings.
        // Yaw proxy: nose displacement projected ONTO the eye axis. Head turn
        // moves the nose along this axis; in-plane tilt does not.
        val midX = (leftEye[0] + rightEye[0]) / 2f
        val midY = (leftEye[1] + rightEye[1]) / 2f
        val noseOffset = abs(
            (nose[0] - midX) * (eyeDx / interEye) + (nose[1] - midY) * (eyeDy / interEye)
        )
        if (noseOffset > MAX_NOSE_OFFSET_RATIO * interEye) {
            logGate {
                "not frontal, noseOffset=${fmt(noseOffset)} interEye=${fmt(interEye)} " +
                        "(max ${fmt(MAX_NOSE_OFFSET_RATIO * interEye)})"
            }
            return null
        }

        val coefficients = SimilarityTransform.fromEyes(leftEye, rightEye) ?: return null
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    coefficients[0], coefficients[1], coefficients[2],
                    coefficients[3], coefficients[4], coefficients[5],
                    0f, 0f, 1f
                )
            )
        }

        val aligned = createBitmap(SimilarityTransform.OUTPUT_SIZE, SimilarityTransform.OUTPUT_SIZE)
        Canvas(aligned).drawBitmap(faceCrop, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return aligned
    }

    fun close() {
        detector.close()
    }

    /**
     * Records why a crop was rejected. The message lambda only runs in
     * debuggable builds, so release builds pay nothing on the sporadic
     * recognition path.
     */
    private inline fun logGate(message: () -> String) {
        if (debugLogging) Log.d(TAG, "gate: ${message()}")
    }

    /** Locale-independent so logs stay ASCII-readable on any device locale. */
    private fun fmt(value: Float): String = String.format(Locale.US, "%.1f", value)

    private companion object {
        const val TAG = "FaceAligner"

        /** Reuses the asset already shipped for the MediaPipe detector backend. */
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val MIN_LANDMARK_DETECTION_CONFIDENCE = 0.5f

        /** Below this inter-eye distance the crop carries too little identity signal. */
        const val MIN_INTER_EYE_PX = 20f

        /**
         * Max nose displacement along the eye axis, as a fraction of inter-eye
         * distance. Deliberately loose: tightening it toward 0.35 rejected a
         * large share of usable frames and made enrollment feel broken, and a
         * rejected frame costs nothing but a still-blurred face.
         */
        const val MAX_NOSE_OFFSET_RATIO = 0.45f

        /**
         * BlazeFace keypoint indices. 0 and 1 are the eyes — which one is
         * image-left is NOT guaranteed, hence the sort in [align] — and 2 is
         * the nose tip.
         */
        const val KP_EYE_A = 0
        const val KP_EYE_B = 1
        const val KP_NOSE = 2
    }
}