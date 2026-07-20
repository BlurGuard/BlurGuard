package com.nash.core.model

/**
 * Axis-aligned bounding box in **normalized** coordinates ([0, 1] relative to
 * the frame dimensions in [FrameMetadata]), independent of resolution and rotation.
 *
 * Used by [DetectionBox] (raw detections from core/ml) and [TrackedBox]
 * (tracked objects from core/tracking). Use [FrameMetadata.width] /
 * [FrameMetadata.height] to map back to pixel space.
 *
 * @property left Normalized left edge (x of the top-left corner).
 * @property top Normalized top edge (y of the top-left corner).
 * @property right Normalized right edge (x of the bottom-right corner).
 * @property bottom Normalized bottom edge (y of the bottom-right corner).
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left <= right) {
            "Invalid horizontal coordinates: left ($left) must be <= right ($right)"
        }
        require(top <= bottom) {
            "Invalid vertical coordinates: top ($top) must be <= bottom ($bottom)"
        }
    }

    /** Normalized width of the box. */
    val width: Float
        get() = right - left

    /** Normalized height of the box. */
    val height: Float
        get() = bottom - top

    /** Normalized x coordinate of the box center. */
    val centerX: Float
        get() = (left + right) / 2f

    /** Normalized y coordinate of the box center. */
    val centerY: Float
        get() = (top + bottom) / 2f
}