package com.nash.engine.ml.recognition

import android.graphics.Bitmap
import com.nash.core.model.RecognitionConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal sealed interface QualityResult {
    data object Valid : QualityResult
    data class Invalid(val reason: String) : QualityResult
}

internal data class FaceQualityMetrics(
    val interEye: Float,
    val rollDeg: Float,
    val yaw: Float,
    val yawRatio: Float,
    val perpendicularOffset: Float,
    val maxYaw: Float,
) {
    companion object {
        fun from(keypoints: FaceKeypoints): FaceQualityMetrics {
            val eyeDx = keypoints.rightEye.x - keypoints.leftEye.x
            val eyeDy = keypoints.rightEye.y - keypoints.leftEye.y
            val interEye = hypot(eyeDx, eyeDy)
            val rollDeg = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()
            if (interEye == 0f) {
                return FaceQualityMetrics(
                    interEye = 0f,
                    rollDeg = rollDeg,
                    yaw = 0f,
                    yawRatio = Float.POSITIVE_INFINITY,
                    perpendicularOffset = 0f,
                    maxYaw = 0f,
                )
            }

            val axisX = eyeDx / interEye
            val axisY = eyeDy / interEye
            val midX = (keypoints.leftEye.x + keypoints.rightEye.x) / 2f
            val midY = (keypoints.leftEye.y + keypoints.rightEye.y) / 2f
            val noseDx = keypoints.nose.x - midX
            val noseDy = keypoints.nose.y - midY
            val yaw = abs(noseDx * axisX + noseDy * axisY)
            val perpendicularOffset = abs(noseDx * -axisY + noseDy * axisX)
            val maxYaw = interEye * MAX_NOSE_OFFSET_RATIO

            return FaceQualityMetrics(
                interEye = interEye,
                rollDeg = rollDeg,
                yaw = yaw,
                yawRatio = yaw / interEye,
                perpendicularOffset = perpendicularOffset,
                maxYaw = maxYaw,
            )
        }

        /** Nose offset along the eye axis, as a fraction of inter-eye distance. */
        const val MAX_NOSE_OFFSET_RATIO = 0.45f
    }
}

internal class FaceQualityGate(
    private val config: RecognitionConfig,
) {
    fun validateCropSize(bitmap: Bitmap): QualityResult =
        validateCropSize(width = bitmap.width, height = bitmap.height)

    internal fun validateCropSize(width: Int, height: Int): QualityResult =
        if (width < config.minFaceCropPx || height < config.minFaceCropPx) {
            QualityResult.Invalid(
                "gate: crop too small crop=${width}x${height} (min ${config.minFaceCropPx})"
            )
        } else {
            QualityResult.Valid
        }

    fun validateKeypoints(keypoints: FaceKeypoints): QualityResult {
        val metrics = FaceQualityMetrics.from(keypoints)
        if (metrics.interEye < MIN_INTER_EYE_PX) {
            return QualityResult.Invalid(
                "interEye=${fmt(metrics.interEye)}px < ${fmt(MIN_INTER_EYE_PX)} " +
                        "roll=${fmt(metrics.rollDeg)}deg"
            )
        }

        if (metrics.yaw > metrics.maxYaw) {
            return QualityResult.Invalid(
                "not frontal yaw=${fmt(metrics.yaw)} (ratio ${fmt(metrics.yawRatio)}) " +
                        "perp=${fmt(metrics.perpendicularOffset)} interEye=${fmt(metrics.interEye)} " +
                        "max=${fmt(metrics.maxYaw)} roll=${fmt(metrics.rollDeg)}deg"
            )
        }

        return QualityResult.Valid
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.1f", value)

    private companion object {
        /** Below this the eye landmarks carry too little signal to align on. */
        const val MIN_INTER_EYE_PX = 20f


    }
}