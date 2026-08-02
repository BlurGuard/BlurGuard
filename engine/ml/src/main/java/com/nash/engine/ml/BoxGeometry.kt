package com.nash.engine.ml

import com.nash.core.model.BoundingBox

/**
 * Rotates a normalized box from unrotated-buffer space into upright space.
 * [rotationDegrees] is the clockwise rotation that makes the buffer upright
 * (CameraX convention); always a multiple of 90.
 */
internal fun BoundingBox.rotatedToUpright(rotationDegrees: Int): BoundingBox =
    when ((rotationDegrees % 360 + 360) % 360) {
        90 -> BoundingBox(left = 1f - bottom, top = left, right = 1f - top, bottom = right)
        180 -> BoundingBox(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top)
        270 -> BoundingBox(left = top, top = 1f - right, right = bottom, bottom = 1f - left)
        else -> this
    }
