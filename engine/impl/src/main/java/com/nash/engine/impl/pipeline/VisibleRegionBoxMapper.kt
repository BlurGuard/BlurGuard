package com.nash.engine.impl.pipeline

import com.nash.core.model.BoundingBox
import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackedBox

/**
 * Re-normalizes upright-space boxes from full-buffer coordinates into the
 * visible (ViewPort-cropped) region the user actually sees.
 *
 * Privacy-critical: an error here offsets every box, which means blurring the
 * wrong pixels and leaving real faces exposed. Hence, the exhaustive crop x
 * rotation test matrix in VisibleRegionBoxMapperTest.
 */
internal class VisibleRegionBoxMapper {

    fun remap(boxes: List<TrackedBox>, meta: FrameMetadata): List<TrackedBox> {
        val cw = if (meta.cropWidth > 0) meta.cropWidth else meta.width
        val ch = if (meta.cropHeight > 0) meta.cropHeight else meta.height
        if (cw == meta.width && ch == meta.height) return boxes // no crop, nothing to do

        // Crop rect normalized to the buffer, then rotated into the same upright
        // space the boxes live in.
        val crop = BoundingBox(
            left = meta.cropLeft / meta.width.toFloat(),
            top = meta.cropTop / meta.height.toFloat(),
            right = (meta.cropLeft + cw) / meta.width.toFloat(),
            bottom = (meta.cropTop + ch) / meta.height.toFloat(),
        ).rotatedToUpright(meta.rotationDegrees)

        val w = crop.right - crop.left
        val h = crop.bottom - crop.top
        return boxes.map { tracked ->
            tracked.copy(
                box = BoundingBox(
                    left = ((tracked.box.left - crop.left) / w).coerceIn(0f, 1f),
                    top = ((tracked.box.top - crop.top) / h).coerceIn(0f, 1f),
                    right = ((tracked.box.right - crop.left) / w).coerceIn(0f, 1f),
                    bottom = ((tracked.box.bottom - crop.top) / h).coerceIn(0f, 1f),
                )
            )
        }
    }
}