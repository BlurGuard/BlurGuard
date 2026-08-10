package com.nash.engine.ml.recognition

import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.Detection

internal data class FacePoint(
    val x: Float,
    val y: Float,
)

internal data class FaceKeypoints(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val nose: FacePoint,
) {
    fun leftEyeArray(): FloatArray = floatArrayOf(leftEye.x, leftEye.y)

    fun rightEyeArray(): FloatArray = floatArrayOf(rightEye.x, rightEye.y)

    fun noseArray(): FloatArray = floatArrayOf(nose.x, nose.y)
}

internal class FaceKeypointExtractor {
    fun extract(detection: Detection, crop: Bitmap): FaceKeypoints? {
        val keypoints = detection.keypoints().orElse(null)
        if (keypoints == null || keypoints.size < REQUIRED_KEYPOINTS) {
            return null
        }

        val width = crop.width.toFloat()
        val height = crop.height.toFloat()
        val first = FacePoint(
            keypoints[KP_LEFT_EYE].x() * width,
            keypoints[KP_LEFT_EYE].y() * height,
        )
        val second = FacePoint(
            keypoints[KP_RIGHT_EYE].x() * width,
            keypoints[KP_RIGHT_EYE].y() * height,
        )
        val (leftEye, rightEye) = orderEyes(first, second)
        val nose = FacePoint(
            keypoints[KP_NOSE].x() * width,
            keypoints[KP_NOSE].y() * height,
        )

        return FaceKeypoints(
            leftEye = leftEye,
            rightEye = rightEye,
            nose = nose,
        )
    }

    internal fun orderEyes(first: FacePoint, second: FacePoint): Pair<FacePoint, FacePoint> =
        if (first.x <= second.x) {
            first to second
        } else {
            second to first
        }

    private companion object {
        const val REQUIRED_KEYPOINTS = 3
        const val KP_LEFT_EYE = 0
        const val KP_RIGHT_EYE = 1
        const val KP_NOSE = 2
    }
}