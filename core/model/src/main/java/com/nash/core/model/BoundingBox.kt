package com.nash.core.model

/**
 * Normalized coordinates for a detected object.
 * Values are in the range [0.0, 1.0], relative to the frame dimensions.
 *
 * This type is used across all detection and tracking modules to represent geometry
 * without dependencies on specific image resolutions.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    /**
     * Width of the box in normalized coordinates.
     */
    val width: Float get() = (right - left).coerceAtLeast(0f)

    /**
     * Height of the box in normalized coordinates.
     */
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /**
     * Horizontal center of the box.
     */
    val centerX: Float get() = left + width / 2f

    /**
     * Vertical center of the box.
     */
    val centerY: Float get() = top + height / 2f

    init {
        require(left <= right) { "left ($left) must be <= right ($right)" }
        require(top <= bottom) { "top ($top) must be <= bottom ($bottom)" }
    }
}
