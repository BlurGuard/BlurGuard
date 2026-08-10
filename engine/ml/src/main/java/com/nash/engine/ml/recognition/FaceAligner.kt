package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
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
) {
    private val appContext = context.applicationContext
    private val debugLogging = appContext.isDebugBuild()
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
                debug { cropSize.reason }
                return null
            }
        }

        var firstFailure: String? = null
        for (degrees in probeStrategy.order(preferredRotation)) {
            val probe = if (degrees == 0) faceCrop else faceCrop.rotated(degrees)
            val outcome = attempt(probe)
            if (probe !== faceCrop) {
                probe.recycle()
            }
            when (outcome) {
                is Outcome.Success -> {
                    preferredRotation = degrees
                    debug {
                        val suffix = if (degrees == 0) "" else " probe=${degrees}deg"
                        "aligned: ${outcome.summary}$suffix"
                    }
                    return outcome.aligned
                }

                is Outcome.Failure -> if (firstFailure == null) firstFailure = outcome.reason
            }
        }

        debug { "gate: ${firstFailure.orEmpty()} (all probes failed)" }
        return null
    }

    private fun attempt(crop: Bitmap): Outcome {
        val detections = detector.detect(crop)
        if (detections.size != 1) {
            return Outcome.Failure("${detections.size} detections in crop ${dims(crop)}")
        }

        val detection = detections[0]
        val keypoints = detection.keypoints().orElse(null)
        if (keypoints == null || keypoints.size < REQUIRED_KEYPOINTS) {
            return Outcome.Failure(
                "only ${keypoints?.size ?: 0} keypoints ${dims(crop)} ${where(detection, crop)}"
            )
        }

        val faceKeypoints = keypointExtractor.extract(detection, crop)
            ?: return Outcome.Failure(
                "only ${keypoints.size} keypoints ${dims(crop)} ${where(detection, crop)}"
            )
        val metrics = FaceQualityMetrics.from(faceKeypoints)
        when (val quality = qualityGate.validateKeypoints(faceKeypoints)) {
            QualityResult.Valid -> Unit
            is QualityResult.Invalid -> return Outcome.Failure(
                quality.reason + " ${dims(crop)} ${where(detection, crop)}"
            )
        }
        val interEye = metrics.interEye
        val rollDeg = metrics.rollDeg

        val aligned = renderer.render(crop, faceKeypoints)
            ?: return Outcome.Failure(
                "degenerate eye pair interEye=${fmt(interEye)} ${dims(crop)} " +
                        where(detection, crop)
            )

        return Outcome.Success(
            aligned = aligned,
            summary = "interEye=${fmt(interEye)} roll=${fmt(rollDeg)}deg ${dims(crop)}",
        )
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun dims(bitmap: Bitmap): String = "crop=${bitmap.width}x${bitmap.height}"

    private fun where(detection: Detection, crop: Bitmap): String {
        val box = detection.boundingBox()
        val cover = box.width() * box.height() / (crop.width.toFloat() * crop.height.toFloat()) * 100f
        return "det=${box.left.roundToInt()},${box.top.roundToInt()} " +
                "${box.width().roundToInt()}x${box.height().roundToInt()} cover=${fmt(cover)}%"
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.1f", value)

    private inline fun debug(message: () -> String) {
        if (debugLogging) {
            Log.d(TAG, message())
        }
    }

    private sealed interface Outcome {
        class Success(val aligned: Bitmap, val summary: String) : Outcome
        class Failure(val reason: String) : Outcome
    }

    fun close() {
        detector.close()
    }

    private companion object {
        const val TAG = "FaceAligner"
        const val REQUIRED_KEYPOINTS = 3
    }
}