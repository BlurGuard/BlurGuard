package com.nash.engine.impl.pipeline

import com.nash.core.model.FrameMetadata

/**
 * Decides whether the current frame should run full detection or coast on
 * tracker prediction.
 *
 * Owns [lastDetectionFrameId] and the detection interval — previously fields
 * on the pipeline itself.
 */
internal class DetectionScheduler(
    private val detectionInterval: Long = DEFAULT_DETECTION_INTERVAL
) : PipelineStage {

    private var lastDetectionFrameId = -1L

    /**
     * Returns true when [metadata] should run detection, and claims that frame.
     *
     * NOT idempotent: a `true` result advances the schedule, so this must be
     * called exactly once per frame. Calling it twice for the same frame makes
     * the second call return false and silently skips the next detection.
     */
    fun isDetectionDue(metadata: FrameMetadata): Boolean {
        val due = lastDetectionFrameId < 0 ||
                metadata.frameId - lastDetectionFrameId >= detectionInterval
        if (due) lastDetectionFrameId = metadata.frameId
        return due
    }

    override fun reset() {
        lastDetectionFrameId = -1L
    }

    companion object {
        /** Detect every Nth frame; predict in between. */
        const val DEFAULT_DETECTION_INTERVAL = 2L
    }
}
