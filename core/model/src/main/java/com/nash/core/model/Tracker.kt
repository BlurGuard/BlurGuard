package com.nash.core.model

/**
 * Contract for multi-object tracking across frames.
 *
 * Implementation lives in core/tracking.
 */
interface Tracker {
    /**
     * Updates tracked objects with new detections from the current frame.
     *
     * @param detections Raw results from a [Detector].
     * @param metadata Current frame metadata (for interpolation and ID assignment).
     * @return List of tracked objects with persistent IDs.
     */
    fun update(
        detections: List<DetectionBox>,
        metadata: FrameMetadata
    ): List<TrackedBox>

    /**
     * Resets internal tracking state (e.g., when a recording session ends or camera restarts).
     */
    fun reset()
}
