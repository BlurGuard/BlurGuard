package com.nash.engine.render.renderer

import com.nash.core.model.BoundingBox

/** Grow the box by [fraction] of its own size on every side (privacy headroom). */
internal fun BoundingBox.dilated(fraction: Float): BoundingBox {
    val dx = (right - left) * fraction
    val dy = (bottom - top) * fraction
    return BoundingBox(
        left = (left - dx).coerceIn(0f, 1f),
        top = (top - dy).coerceIn(0f, 1f),
        right = (right + dx).coerceIn(0f, 1f),
        bottom = (bottom + dy).coerceIn(0f, 1f),
    )
}

private fun BoundingBox.rotated(rotationDegrees: Int): BoundingBox =
    when ((rotationDegrees % 360 + 360) % 360) {
        90 -> BoundingBox(left = 1f - bottom, top = left, right = 1f - top, bottom = right)
        180 -> BoundingBox(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top)
        270 -> BoundingBox(left = top, top = 1f - right, right = bottom, bottom = 1f - left)
        else -> this
    }

/** Inverse of rotatedToUpright: map an upright-space box back into buffer space. */
internal fun BoundingBox.rotatedFromUpright(rotationDegrees: Int): BoundingBox =
    rotated((360 - rotationDegrees) % 360)
