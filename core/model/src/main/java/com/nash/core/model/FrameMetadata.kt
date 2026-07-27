package com.nash.core.model

/**
 * Metadata associated with a camera frame.
 * Required to map normalized [BoundingBox] coordinates back to pixel space and to manage timing.
 *
 * @property frameId Monotonically increasing identifier for the frame.
 * @property timestampNanos System time in nanoseconds when the frame was captured.
 * @property width Pixel width of the frame buffer.
 * @property height Pixel height of the frame buffer.
 * @property rotationDegrees Clockwise rotation required to make the frame upright (0, 90, 180, 270).
 */
data class FrameMetadata(
    val frameId: Long,
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    /** Visible sub-rect of the buffer (ViewPort crop), in buffer pixels. 0 width/height = full frame. */
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
)
