package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.tasks.components.containers.Detection
import com.nash.core.model.RecognitionConfig
import com.nash.engine.ml.isDebugBuild
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns a loose face crop into the 112x112 canonical view MobileFaceNet expects.
 *
 * BlazeFace is not rotation invariant: it reliably finds a face only within
 * roughly +/-30 degrees of upright. The app is portrait locked, so a phone held
 * in landscape delivers analysis frames whose faces lie on their side, and the
 * detector simply reports no landmarks. That surfaced as a ~80% recognition
 * skip rate rather than as a wrong identity, because everything downstream of
 * detection is internally consistent.
 *
 * The fix is local: probe a small set of orientations and keep the first one
 * that clears every gate. The zero degree probe reuses the caller's bitmap, so
 * an upright stream pays nothing. [SimilarityTransform] maps the eyes onto the
 * template and therefore removes the remaining roll, so an embedding produced
 * from a rotated probe is as valid as one from an upright crop.
 *
 * Fail closed: if no orientation clears the gates this returns null and the
 * caller leaves the face blurred.
 */
internal class FaceAligner(
    context: Context,
    private val config: RecognitionConfig,
    private val detector: FaceLandmarkDetector = MediaPipeFaceLandmarkDetector(context.applicationContext),
    private val logger: FaceAlignmentLogger = AndroidFaceAlignmentLogger(
        enabled = context.applicationContext.isDebugBuild(),
    ),
) {
    private val probeStrategy = RotationProbeStrategy()
    private val keypointExtractor = FaceKeypointExtractor()
    private val qualityGate = FaceQualityGate(config)
    private val renderer = AlignedFaceRenderer()

    /**
     * Orientation that last produced a usable alignment, tried first on the next
     * pass. A device held steadily in landscape therefore stops paying for the
     * wasted upright probe after a single recognition.
     *
     * Written from the single-parallelism ml dispatcher, which does not
     * guarantee thread affinity, hence @Volatile.
     */
    @Volatile
    private var preferredRotation = 0

    /**
     * @return the aligned 112x112 crop, or null if no probed orientation yielded
     * a single frontal face with usable eye landmarks.
     */
    fun align(faceCrop: Bitmap): Bitmap? {
        // Rotation cannot rescue a crop that is too small to carry a face, so
        // this gate runs once instead of once per probe.
        when (val cropSize = qualityGate.validateCropSize(faceCrop)) {
            QualityResult.Valid -> Unit
            is QualityResult.Invalid -> {
                logger.debug { cropSize.reason }
                return null
            }
        }

        var firstFailure: AlignmentFailure? = null
        for (degrees in probeStrategy.order(preferredRotation)) {
            val probe = if (degrees == 0) faceCrop else faceCrop.rotated(degrees)
            val result = alignProbe(probe)
            if (probe !== faceCrop) {
                probe.recycle()
            }
            when (result) {
                is AlignmentResult.Success -> {
                    preferredRotation = degrees
                    logger.debug {
                        val suffix = if (degrees == 0) "" else " probe=${degrees}deg"
                        "aligned: ${result.summary}$suffix"
                    }
                    return result.aligned
                }

                is AlignmentResult.Failure -> if (firstFailure == null) firstFailure = result.failure
            }
        }

        logger.debug { "gate: ${firstFailure?.reason.orEmpty()} (all probes failed)" }
        return null
    }

    private fun alignProbe(crop: Bitmap): AlignmentResult {
        val detectionResult = detectSingleFace(crop)
        val detection = when (detectionResult) {
            is DetectionResult.SingleFace -> detectionResult.detection
            is DetectionResult.Failure -> return AlignmentResult.Failure(detectionResult.failure)
        }

        val keypointResult = extractKeypoints(detection, crop)
        val faceKeypoints = when (keypointResult) {
            is KeypointResult.Success -> keypointResult.keypoints
            is KeypointResult.Failure -> return AlignmentResult.Failure(keypointResult.failure)
        }

        val qualityFailure = validateQuality(faceKeypoints, detection, crop)
        if (qualityFailure != null) {
            return AlignmentResult.Failure(qualityFailure)
        }

        val metrics = FaceQualityMetrics.from(faceKeypoints)
        val aligned = renderer.render(crop, faceKeypoints)
            ?: return AlignmentResult.Failure(
                AlignmentFailure(
                    "degenerate eye pair interEye=${metrics.interEye.format()} ${crop.dims()} " +
                            detection.where(crop)
                )
            )

        return AlignmentResult.Success(
            aligned = aligned,
            summary = "interEye=${metrics.interEye.format()} roll=${metrics.rollDeg.format()}deg ${crop.dims()}",
        )
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun detectSingleFace(crop: Bitmap): DetectionResult {
        val detections = detector.detect(crop)
        return if (detections.size == 1) {
            DetectionResult.SingleFace(detections[0])
        } else {
            DetectionResult.Failure(AlignmentFailure("${detections.size} detections in crop ${crop.dims()}"))
        }
    }

    private fun extractKeypoints(
        detection: Detection,
        crop: Bitmap,
    ): KeypointResult {
        val keypoints = detection.keypoints().orElse(null)
        if (keypoints == null || keypoints.size < REQUIRED_KEYPOINTS) {
            return KeypointResult.Failure(
                AlignmentFailure("only ${keypoints?.size ?: 0} keypoints ${crop.dims()} ${detection.where(crop)}")
            )
        }

        return keypointExtractor.extract(detection, crop)?.let(KeypointResult::Success)
            ?: KeypointResult.Failure(
                AlignmentFailure("only ${keypoints.size} keypoints ${crop.dims()} ${detection.where(crop)}")
            )
    }

    private fun validateQuality(
        keypoints: FaceKeypoints,
        detection: Detection,
        crop: Bitmap,
    ): AlignmentFailure? = when (val quality = qualityGate.validateKeypoints(keypoints)) {
        QualityResult.Valid -> null
        is QualityResult.Invalid -> AlignmentFailure(quality.reason + " ${crop.dims()} ${detection.where(crop)}")
    }

    fun close() {
        detector.close()
    }

    private sealed interface DetectionResult {
        data class SingleFace(val detection: Detection) : DetectionResult
        data class Failure(val failure: AlignmentFailure) : DetectionResult
    }

    private sealed interface KeypointResult {
        data class Success(val keypoints: FaceKeypoints) : KeypointResult
        data class Failure(val failure: AlignmentFailure) : KeypointResult
    }

    private sealed interface AlignmentResult {
        data class Success(val aligned: Bitmap, val summary: String) : AlignmentResult
        data class Failure(val failure: AlignmentFailure) : AlignmentResult
    }

    private data class AlignmentFailure(val reason: String)

    private companion object {
        const val REQUIRED_KEYPOINTS = 3
    }
}

private fun Bitmap.dims(): String = "crop=${width}x${height}"

private fun Detection.where(crop: Bitmap): String {
    val box = boundingBox()
    val cover = box.width() * box.height() / (crop.width.toFloat() * crop.height.toFloat()) * 100f
    return "det=${box.left.roundToInt()},${box.top.roundToInt()} " +
            "${box.width().roundToInt()}x${box.height().roundToInt()} cover=${cover.format()}%"
}

private fun Float.format(): String = String.format(Locale.US, "%.1f", this)
