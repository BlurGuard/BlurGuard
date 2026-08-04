package com.nash.engine.impl.pipeline

import android.util.Log
import com.nash.core.model.DetectionBox
import com.nash.core.model.Detector
import com.nash.core.model.FrameMetadata
import kotlinx.coroutines.CancellationException

/**
 * Outcome of one detection pass.
 *
 * @property boxes Raw detections from all detectors, concatenated.
 * @property degraded True when at least one detector threw. The pass is then
 * INCOMPLETE: [boxes] may be missing objects that are really in frame, so
 * consumers must treat this as "possibly unprotected", never as "nothing here".
 */
internal data class DetectionResult(
    val boxes: List<DetectionBox>,
    val degraded: Boolean = false,
)

/**
 * What to do when a detector throws mid-frame.
 *
 * Extracted so the failure path is assertable in tests instead of only
 * observable through logcat.
 */
internal interface DetectorFailurePolicy {
    fun onFailure(detector: Detector<*>, error: Throwable)

    /** Production policy: log loudly, drop that detector's output for this frame. */
    object LogAndDrop : DetectorFailurePolicy {
        override fun onFailure(detector: Detector<*>, error: Throwable) {
            Log.e(
                "DetectionRunner",
                "detector ${detector::class.java.simpleName} threw; " +
                        "frame is DEGRADED (objects may be unprotected)",
                error
            )
        }
    }
}

/**
 * Runs every detector over one frame and applies the failure policy.
 *
 * Generic over F so this is JVM-unit-testable with fake frames.
 */
internal class DetectionRunner<F>(
    private val detectors: List<Detector<F>>,
    private val failurePolicy: DetectorFailurePolicy = DetectorFailurePolicy.LogAndDrop,
) {

    suspend fun detect(frame: F, metadata: FrameMetadata): DetectionResult {
        var degraded = false
        val boxes = ArrayList<DetectionBox>()

        for (detector in detectors) {
            try {
                boxes.addAll(detector.detect(frame, metadata))
            } catch (e: CancellationException) {
                // Structured concurrency: shutdown must unwind onFrame, not be
                // absorbed here. Previously swallowed by `catch (e: Exception)`.
                throw e
            } catch (e: Exception) {
                degraded = true
                failurePolicy.onFailure(detector, e)
            }
        }

        return DetectionResult(boxes, degraded)
    }
}