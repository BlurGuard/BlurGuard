package com.nash.feature.camera.components

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoxCoordinateMapperTest {

    private val aspect = 16f / 9f

    private fun box(
        id: Long,
        clazz: DetectionClass = DetectionClass.FACE,
        left: Float = 0.4f,
        top: Float = 0.4f,
        right: Float = 0.6f,
        bottom: Float = 0.6f
    ) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(left = left, top = top, right = right, bottom = bottom),
        clazz = clazz,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )

    // ---- contentRect -------------------------------------------------------

    @Test
    fun `wide landscape canvas letterboxes horizontally`() {
        val rect = BoxCoordinateMapper.contentRect(2000f, 900f, aspect)
        assertEquals(200f, rect.offsetX, 0.01f)
        assertEquals(0f, rect.offsetY, 0.01f)
        assertEquals(1600f, rect.width, 0.01f)
        assertEquals(900f, rect.height, 0.01f)
    }

    @Test
    fun `tall portrait canvas letterboxes vertically`() {
        val rect = BoxCoordinateMapper.contentRect(900f, 2000f, aspect)
        assertEquals(0f, rect.offsetX, 0.01f)
        assertEquals(200f, rect.offsetY, 0.01f)
        assertEquals(900f, rect.width, 0.01f)
        assertEquals(1600f, rect.height, 0.01f)
    }

    @Test
    fun `exact 16 to 9 canvas has no letterbox`() {
        val rect = BoxCoordinateMapper.contentRect(1600f, 900f, aspect)
        assertEquals(0f, rect.offsetX, 0.01f)
        assertEquals(0f, rect.offsetY, 0.01f)
        assertEquals(1600f, rect.width, 0.01f)
        assertEquals(900f, rect.height, 0.01f)
    }

    // ---- faceTrackIdAt -----------------------------------------------------

    @Test
    fun `tap inside a face box returns its track id`() {
        val id = BoxCoordinateMapper.faceTrackIdAt(
            tapX = 800f, tapY = 450f,
            canvasWidth = 1600f, canvasHeight = 900f,
            frameAspectRatio = aspect,
            boxes = listOf(box(7L))
        )
        assertEquals(TrackId(7L), id)
    }

    @Test
    fun `tap missing every box returns null`() {
        assertNull(
            BoxCoordinateMapper.faceTrackIdAt(
                tapX = 80f, tapY = 60f,
                canvasWidth = 1600f, canvasHeight = 900f,
                frameAspectRatio = aspect,
                boxes = listOf(box(1L))
            )
        )
    }

    @Test
    fun `letterbox offset is applied before hit testing`() {
        // Canvas 2000x900 -> content occupies x in [200, 1800].
        val id = BoxCoordinateMapper.faceTrackIdAt(
            tapX = 200f + 0.5f * 1600f, tapY = 450f,
            canvasWidth = 2000f, canvasHeight = 900f,
            frameAspectRatio = aspect,
            boxes = listOf(box(4L))
        )
        assertEquals(TrackId(4L), id)
    }

    @Test
    fun `tap inside the letterbox bars returns null even over a full-frame box`() {
        assertNull(
            BoxCoordinateMapper.faceTrackIdAt(
                tapX = 100f, tapY = 450f, // inside the left black bar
                canvasWidth = 2000f, canvasHeight = 900f,
                frameAspectRatio = aspect,
                boxes = listOf(box(1L, left = 0f, top = 0f, right = 1f, bottom = 1f))
            )
        )
    }

    @Test
    fun `license plates are not tappable`() {
        assertNull(
            BoxCoordinateMapper.faceTrackIdAt(
                tapX = 800f, tapY = 450f,
                canvasWidth = 1600f, canvasHeight = 900f,
                frameAspectRatio = aspect,
                boxes = listOf(box(1L, clazz = DetectionClass.LICENSE_PLATE))
            )
        )
    }

    @Test
    fun `smallest overlapping face wins`() {
        val big = box(1L, left = 0.3f, top = 0.3f, right = 0.7f, bottom = 0.7f)
        val small = box(2L, left = 0.45f, top = 0.45f, right = 0.55f, bottom = 0.55f)
        val id = BoxCoordinateMapper.faceTrackIdAt(
            tapX = 800f, tapY = 450f,
            canvasWidth = 1600f, canvasHeight = 900f,
            frameAspectRatio = aspect,
            boxes = listOf(big, small)
        )
        assertEquals(TrackId(2L), id)
    }

    @Test
    fun `dilated halo around a face is tappable but beyond it is not`() {
        // Box x in [0.4, 0.6], width 0.2, dilation 0.25 -> tappable out to 0.65.
        val boxes = listOf(box(3L))
        assertEquals(
            TrackId(3L),
            BoxCoordinateMapper.faceTrackIdAt(
                tapX = 0.63f * 1600f, tapY = 450f,
                canvasWidth = 1600f, canvasHeight = 900f,
                frameAspectRatio = aspect,
                boxes = boxes
            )
        )
        assertNull(
            BoxCoordinateMapper.faceTrackIdAt(
                tapX = 0.67f * 1600f, tapY = 450f,
                canvasWidth = 1600f, canvasHeight = 900f,
                frameAspectRatio = aspect,
                boxes = boxes
            )
        )
    }
}
