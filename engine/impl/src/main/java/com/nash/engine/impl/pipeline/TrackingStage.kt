package com.nash.engine.impl.pipeline

import com.nash.core.model.DetectionBox
import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker

/**
 * Wraps the [Tracker] and owns its reset.
 *
 * Intentionally thin: its value is (a) making tracker reset part of the
 * uniform [PipelineStage] lifecycle and (b) giving the orchestrator a single
 * seam to fake in tests.
 */
internal class TrackingStage(
    private val tracker: Tracker,
) : PipelineStage {

    /** Detection frame: fold new detections into persistent tracks. */
    fun update(detections: List<DetectionBox>, metadata: FrameMetadata): List<TrackedBox> =
        tracker.update(detections, metadata)

    /**
     * Non-detection frame: coast on motion estimates. Implementations already
     * prune tracks past `maxLostFrames`, so a stalled detector cannot leave
     * ghost boxes coasting forever.
     */
    fun predict(metadata: FrameMetadata): List<TrackedBox> =
        tracker.predict(metadata)

    override fun reset() {
        tracker.reset()
    }
}