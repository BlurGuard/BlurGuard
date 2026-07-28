package com.nash.core.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.nash.core.model.RecognitionConfig
import kotlin.math.abs
import kotlin.math.hypot
import androidx.core.graphics.createBitmap

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
            return null
        }

        val result = detector.detect(BitmapImageBuilder(faceCrop).build())
        // Exactly-one gate: zero faces = nothing to align; several faces in one
        // crop = ambiguous, and ambiguity must never feed an unblur decision.
        val detection = result.detections().singleOrNull() ?: return null
        val keypoints = detection.keypoints().orElse(null) ?: return null
        if (keypoints.size < 3) return null

        // BlazeFace keypoint order — VERIFY ONCE ON DEVICE (log + overlay dots):
        // 0 = left eye, 1 = right eye, 2 = nose tip, 3 = mouth center,
        // 4/5 = tragions. Coordinates are normalized to the crop.
        val leftEye = floatArrayOf(
            keypoints[KP_LEFT_EYE].x() * faceCrop.width,
            keypoints[KP_LEFT_EYE].y() * faceCrop.height
        )
        val rightEye = floatArrayOf(
            keypoints[KP_RIGHT_EYE].x() * faceCrop.width,
            keypoints[KP_RIGHT_EYE].y() * faceCrop.height
        )
        val nose = floatArrayOf(
            keypoints[KP_NOSE].x() * faceCrop.width,
            keypoints[KP_NOSE].y() * faceCrop.height
        )

        // Gate: eyes far enough apart to carry identity information.
        val interEye = hypot(rightEye[0] - leftEye[0], rightEye[1] - leftEye[1])
        if (interEye < MIN_INTER_EYE_PX) return null

        // Gate: roughly frontal — the nose should sit near the eye midpoint
        // horizontally. Profile faces produce unreliable embeddings.
        val midEyeX = (leftEye[0] + rightEye[0]) / 2f
        if (abs(nose[0] - midEyeX) > MAX_NOSE_OFFSET_RATIO * interEye) return null

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
        const val MAX_NOSE_OFFSET_RATIO = 0.35f

        const val KP_LEFT_EYE = 0
        const val KP_RIGHT_EYE = 1
        const val KP_NOSE = 2
    }
}