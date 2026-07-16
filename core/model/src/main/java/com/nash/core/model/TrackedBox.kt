package com.nash.core.model

/**
 * An object tracked across multiple frames.
 *
 * @property id Unique tracking identifier assigned by the tracker.
 * @property box The normalized geometry of the object in the current frame.
 * @property clazz The type of object tracked.
 * @property confidence Tracking/Detection confidence score.
 * @property lastUpdatedFrame The frame ID when this object was last explicitly detected or updated.
 * Used by the renderer to handle interpolation or occlusion.
 * @property keepVisible If true, this object should NOT be anonymized (e.g., a "trusted face").
 */
data class TrackedBox(
    val id: TrackId,
    val box: BoundingBox,
    val clazz: DetectionClass,
    val confidence: Float,
    val lastUpdatedFrame: Long,
    val keepVisible: Boolean = false
)
