package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mediapipe.tasks.components.containers.Detection
import com.nash.core.model.RecognitionConfig
import com.nash.engine.ml.isDebugBuild
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap

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
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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
        if (faceCrop.width < config.minFaceCropPx || faceCrop.height < config.minFaceCropPx) {
            debug { "gate: crop too small ${dims(faceCrop)} (min ${config.minFaceCropPx})" }
            return null
        }

        var firstFailure: String? = null
        for (degrees in probeOrder()) {
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

        val width = crop.width.toFloat()
        val height = crop.height.toFloat()
        val first = floatArrayOf(
            keypoints[KP_LEFT_EYE].x() * width,
            keypoints[KP_LEFT_EYE].y() * height,
        )
        val second = floatArrayOf(
            keypoints[KP_RIGHT_EYE].x() * width,
            keypoints[KP_RIGHT_EYE].y() * height,
        )
        // BlazeFace reports the subject's own left and right eye. Order by image
        // x so the transform always receives the image-left eye first.
        val leftIsFirst = first[0] <= second[0]
        val leftEye = if (leftIsFirst) first else second
        val rightEye = if (leftIsFirst) second else first
        val nose = floatArrayOf(
            keypoints[KP_NOSE].x() * width,
            keypoints[KP_NOSE].y() * height,
        )

        val eyeDx = rightEye[0] - leftEye[0]
        val eyeDy = rightEye[1] - leftEye[1]
        val interEye = hypot(eyeDx, eyeDy)
        val rollDeg = roll(eyeDx, eyeDy)

        if (interEye < MIN_INTER_EYE_PX) {
            return Outcome.Failure(
                "interEye=${fmt(interEye)}px < ${fmt(MIN_INTER_EYE_PX)} roll=${fmt(rollDeg)}deg " +
                        "${dims(crop)} ${where(detection, crop)}"
            )
        }

        // Unit vector along the eye axis, so the frontality test is independent
        // of head roll.
        val axisX = eyeDx / interEye
        val axisY = eyeDy / interEye
        val midX = (leftEye[0] + rightEye[0]) / 2f
        val midY = (leftEye[1] + rightEye[1]) / 2f
        val noseDx = nose[0] - midX
        val noseDy = nose[1] - midY
        // Along the eye axis this is yaw. Perpendicular to it this is just the
        // nose sitting below the eyes, which every face has; it is logged for
        // context but never gated on.
        val yaw = abs(noseDx * axisX + noseDy * axisY)
        val perp = abs(noseDx * -axisY + noseDy * axisX)
        val maxYaw = interEye * MAX_NOSE_OFFSET_RATIO

        if (yaw > maxYaw) {
            return Outcome.Failure(
                "not frontal yaw=${fmt(yaw)} (ratio ${fmt(yaw / interEye)}) perp=${fmt(perp)} " +
                        "interEye=${fmt(interEye)} max=${fmt(maxYaw)} roll=${fmt(rollDeg)}deg " +
                        "${dims(crop)} ${where(detection, crop)}"
            )
        }

        // fromEyes returns the six affine coefficients [a, b, tx, c, d, ty] in
        // Matrix.setValues() row-major order, not a Matrix. It only returns null
        // for a near-coincident eye pair, which MIN_INTER_EYE_PX already rules
        // out, but the branch stays fail-closed rather than asserting.
        val coefficients = SimilarityTransform.fromEyes(leftEye, rightEye)
            ?: return Outcome.Failure(
                "degenerate eye pair interEye=${fmt(interEye)} ${dims(crop)} " +
                        where(detection, crop)
            )
        val transform = Matrix().apply {
            setValues(
                floatArrayOf(
                    coefficients[0], coefficients[1], coefficients[2],
                    coefficients[3], coefficients[4], coefficients[5],
                    0f, 0f, 1f,
                )
            )
        }

        val aligned =
            createBitmap(SimilarityTransform.OUTPUT_SIZE, SimilarityTransform.OUTPUT_SIZE)
        Canvas(aligned).drawBitmap(crop, transform, filterPaint)

        return Outcome.Success(
            aligned = aligned,
            summary = "interEye=${fmt(interEye)} roll=${fmt(rollDeg)}deg ${dims(crop)}",
        )
    }

    private fun probeOrder(): IntArray = when (preferredRotation) {
        90 -> PROBE_ORDER_90
        270 -> PROBE_ORDER_270
        else -> PROBE_ORDER_0
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

    private fun roll(eyeDx: Float, eyeDy: Float): Float =
        Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

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
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val MIN_LANDMARK_DETECTION_CONFIDENCE = 0.5f

        /** Below this the eye landmarks carry too little signal to align on. */
        const val MIN_INTER_EYE_PX = 20f

        /** Nose offset along the eye axis, as a fraction of inter-eye distance. */
        const val MAX_NOSE_OFFSET_RATIO = 0.45f

        const val REQUIRED_KEYPOINTS = 3
        const val KP_LEFT_EYE = 0
        const val KP_RIGHT_EYE = 1
        const val KP_NOSE = 2

        /**
         * Upright plus both landscape orientations. 180 is deliberately absent:
         * an upside-down phone is not a supported hold and each extra probe
         * costs a full detector pass on the miss path.
         */
        val PROBE_ORDER_0 = intArrayOf(0, 90, 270)
        val PROBE_ORDER_90 = intArrayOf(90, 0, 270)
        val PROBE_ORDER_270 = intArrayOf(270, 0, 90)
    }
}
