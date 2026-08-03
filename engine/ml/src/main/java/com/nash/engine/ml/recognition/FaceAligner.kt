package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.nash.core.model.RecognitionConfig
import kotlin.math.abs
import kotlin.math.hypot
import androidx.core.graphics.createBitmap
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint

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

            Log.d("FaceAligner", "gate: crop too small ${faceCrop.width}x${faceCrop.height} (min ${config.minFaceCropPx})")
            return null
        }

        val result = detector.detect(BitmapImageBuilder(faceCrop).build())
        // Exactly-one gate: zero faces = nothing to align; several faces in one
        // crop = ambiguous, and ambiguity must never feed an unblur decision.
        val detection = result.detections().singleOrNull() ?: run {
            Log.d("FaceAligner", "gate: ${result.detections().size} detections in crop (need exactly 1)")
            return null
        }
        val keypoints = detection.keypoints().orElse(null) ?: return null
        if (keypoints.size < 3) {
            Log.d("FaceAligner", "gate: only ${keypoints.size} keypoints")
            return null
        }

        // BlazeFace keypoint order — VERIFY ONCE ON DEVICE (log + overlay dots):
        // 0 = left eye, 1 = right eye, 2 = nose tip, 3 = mouth center,
        // 4/5 = tragions. Coordinates are normalized to the crop.
        fun toPx(kp: NormalizedKeypoint) =
            floatArrayOf(kp.x() * faceCrop.width, kp.y() * faceCrop.height)
        // Robust to keypoint-order differences: image-left eye = smaller x.
        val eyes = listOf(toPx(keypoints[0]), toPx(keypoints[1])).sortedBy { it[0] }
        val leftEye = eyes[0]   // image-left -> template (38.29, 51.69)
        val rightEye = eyes[1]  // image-right -> template (73.53, 51.50)
        val nose = toPx(keypoints[2]) // nose tip (correct in MediaPipe's order)


        // Gate: eyes far enough apart to carry identity information.
        val eyeDx = rightEye[0] - leftEye[0]
        val eyeDy = rightEye[1] - leftEye[1]
        val interEye = hypot(eyeDx, eyeDy)
        if (interEye < MIN_INTER_EYE_PX) {
            Log.d("faceAligner", "gate: interEye=${"%.1f".format(interEye)}px < $MIN_INTER_EYE_PX")
            return null
        }

        // Gate: roughly frontal — the nose should sit near the eye midpoint
        // horizontally. Profile faces produce unreliable embeddings.
        val midX = (leftEye[0] + rightEye[0]) / 2f
        val midY = (leftEye[1] + rightEye[1]) / 2f
// Yaw proxy: nose displacement projected ONTO the eye axis.
// Head turn (yaw) moves the nose along this axis; in-plane tilt does not.
        val noseOffset = kotlin.math.abs(
            (nose[0] - midX) * (eyeDx / interEye) + (nose[1] - midY) * (eyeDy / interEye)
        )
        if (noseOffset > MAX_NOSE_OFFSET_RATIO * interEye) {
            Log.d("faceAligner", "gate: not frontal, noseOffset=${"%.1f".format(noseOffset)} " +
                    "interEye=${"%.1f".format(interEye)} " +
                    "(max ${"%.1f".format(MAX_NOSE_OFFSET_RATIO * interEye)})")
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

    private companion object {
        /** Reuses the asset already shipped for the MediaPipe detector backend. */
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val MIN_LANDMARK_DETECTION_CONFIDENCE = 0.5f
        const val MIN_INTER_EYE_PX = 20f
        const val MAX_NOSE_OFFSET_RATIO = 0.45f // was 0.35f
        const val KP_LEFT_EYE = 0
        const val KP_RIGHT_EYE = 1
        const val KP_NOSE = 2
    }
}
