package com.nash.feature.camera.components

import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox

/**
 * Shared FIT_CENTER coordinate mapping between the camera frame and the
 * on-screen canvas.
 *
 * Single source of truth used by both [FaceTapTargets] (hit testing) and
 * [TrackingOverlay] (drawing) so the two layers cannot drift.
 *
 * Pure Kotlin — no Compose or Android types — so it is unit-testable.
 */
object BoxCoordinateMapper {

    /**
     * Hit-test dilation matching the renderer's BOX_DILATION so tapping the
     * blurred halo around a face still works.
     */
    const val HIT_DILATION = 0.25f

    data class ContentRect(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float
    )

    /**
     * FIT_CENTER content rect: the centered sub-rect of the canvas that the
     * camera content occupies (black bars around it). Boxes are normalized to
     * the camera frame, so they must be mapped into this rect — not the full
     * canvas. Orientation is inferred from the canvas dimensions.
     *
     * @param frameAspectRatio camera frame aspect as long-side / short-side
     * (16:9 for the current pipeline).
     */
    fun contentRect(
        canvasWidth: Float,
        canvasHeight: Float,
        frameAspectRatio: Float
    ): ContentRect {
        val contentAspect =
            if (canvasWidth >= canvasHeight) frameAspectRatio else 1f / frameAspectRatio
        val canvasAspect = canvasWidth / canvasHeight
        val contentWidth: Float
        val contentHeight: Float
        if (canvasAspect > contentAspect) {
            contentHeight = canvasHeight
            contentWidth = canvasHeight * contentAspect
        } else {
            contentWidth = canvasWidth
            contentHeight = canvasWidth / contentAspect
        }
        return ContentRect(
            offsetX = (canvasWidth - contentWidth) / 2f,
            offsetY = (canvasHeight - contentHeight) / 2f,
            width = contentWidth,
            height = contentHeight
        )
    }

    /**
     * Maps a canvas tap to the tapped FACE track, or null on a miss (tap in
     * the letterbox bars, on a license plate, or on no box at all).
     */
    fun faceTrackIdAt(
        tapX: Float,
        tapY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        frameAspectRatio: Float,
        boxes: List<TrackedBox>
    ): TrackId? {
        val content = contentRect(canvasWidth, canvasHeight, frameAspectRatio)
        // Canvas -> normalized upright-frame coordinates (inverse of draw).
        val nx = (tapX - content.offsetX) / content.width
        val ny = (tapY - content.offsetY) / content.height
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        return hitTestFace(boxes, nx, ny)?.id
    }

    /**
     * Face whose (dilated) box contains the point. Smallest match wins — the
     * most specific target when boxes overlap. Plates are not tappable.
     */
    fun hitTestFace(boxes: List<TrackedBox>, nx: Float, ny: Float): TrackedBox? =
        boxes
            .filter { it.clazz == DetectionClass.FACE }
            .filter { tracked ->
                val b = tracked.box
                val dx = (b.right - b.left) * HIT_DILATION
                val dy = (b.bottom - b.top) * HIT_DILATION
                nx >= b.left - dx && nx <= b.right + dx && ny >= b.top - dy && ny <= b.bottom + dy
            }
            .minByOrNull { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }
}
