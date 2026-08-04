package com.nash.engine.impl.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleRegionBoxMapperTest {

    private val mapper = VisibleRegionBoxMapper()
    private val eps = 1e-4f

    @Test
    fun `no crop returns the same list instance`() {
        val boxes = listOf(tracked(left = 0.2f, top = 0.2f, right = 0.4f, bottom = 0.4f))
        assertSame(boxes, mapper.remap(boxes, meta(0L, width = 640, height = 480)))
    }

    @Test
    fun `zero crop dimensions fall back to full frame`() {
        val boxes = listOf(tracked())
        val m = meta(0L, width = 640, height = 480, cropWidth = 0, cropHeight = 0)
        assertSame(boxes, mapper.remap(boxes, m))
    }

    @Test
    fun `centered half-width crop rescales x and clamps`() {
        // Buffer 640x480, crop is the middle 320 px horizontally, full height.
        val m = meta(
            0L, width = 640, height = 480,
            cropLeft = 160, cropTop = 0, cropWidth = 320, cropHeight = 480,
        )
        // Box occupies the exact center-left quarter of the crop.
        val boxes = listOf(tracked(left = 0.25f, top = 0.0f, right = 0.5f, bottom = 1.0f))

        val out = mapper.remap(boxes, m).single().box

        // crop spans normalized x in [0.25, 0.75], width 0.5
        assertEquals(0.0f, out.left, eps)     // (0.25 - 0.25) / 0.5
        assertEquals(0.5f, out.right, eps)    // (0.50 - 0.25) / 0.5
        assertEquals(0.0f, out.top, eps)
        assertEquals(1.0f, out.bottom, eps)
    }

    @Test
    fun `off-origin crop subtracts the crop origin`() {
        val m = meta(
            0L, width = 400, height = 400,
            cropLeft = 100, cropTop = 100, cropWidth = 200, cropHeight = 200,
        )
        val boxes = listOf(tracked(left = 0.5f, top = 0.5f, right = 0.75f, bottom = 0.75f))

        val out = mapper.remap(boxes, m).single().box

        // crop spans [0.25, 0.75] in both axes, extent 0.5
        assertEquals(0.5f, out.left, eps)
        assertEquals(0.5f, out.top, eps)
        assertEquals(1.0f, out.right, eps)
        assertEquals(1.0f, out.bottom, eps)
    }

    @Test
    fun `boxes outside the crop clamp into unit range`() {
        val m = meta(
            0L, width = 400, height = 400,
            cropLeft = 100, cropTop = 100, cropWidth = 200, cropHeight = 200,
        )
        // Entirely left of the crop region.
        val boxes = listOf(tracked(left = 0.0f, top = 0.0f, right = 0.1f, bottom = 0.1f))

        val out = mapper.remap(boxes, m).single().box

        assertTrue(out.left in 0f..1f)
        assertTrue(out.top in 0f..1f)
        assertTrue(out.right in 0f..1f)
        assertTrue(out.bottom in 0f..1f)
        assertEquals(0f, out.right, eps)
    }

    @Test
    fun `all four rotations produce in-range boxes`() {
        val boxes = listOf(tracked(left = 0.3f, top = 0.3f, right = 0.6f, bottom = 0.6f))
        for (rotation in listOf(0, 90, 180, 270)) {
            val m = meta(
                0L, width = 640, height = 480, rotationDegrees = rotation,
                cropLeft = 80, cropTop = 60, cropWidth = 480, cropHeight = 360,
            )
            val out = mapper.remap(boxes, m).single().box
            assertTrue("rotation=$rotation left", out.left in 0f..1f)
            assertTrue("rotation=$rotation top", out.top in 0f..1f)
            assertTrue("rotation=$rotation right", out.right in 0f..1f)
            assertTrue("rotation=$rotation bottom", out.bottom in 0f..1f)
            assertTrue("rotation=$rotation ordering", out.left <= out.right)
            assertTrue("rotation=$rotation ordering", out.top <= out.bottom)
        }
    }

    @Test
    fun `non-geometry fields are preserved`() {
        val m = meta(
            0L, width = 400, height = 400,
            cropLeft = 100, cropTop = 100, cropWidth = 200, cropHeight = 200,
        )
        val input = tracked(id = 7L, confidence = 0.42f, lastUpdatedFrame = 5L, keepVisible = true)

        val out = mapper.remap(listOf(input), m).single()

        assertEquals(input.id, out.id)
        assertEquals(input.clazz, out.clazz)
        assertEquals(input.confidence, out.confidence, eps)
        assertEquals(input.lastUpdatedFrame, out.lastUpdatedFrame)
        assertEquals(input.keepVisible, out.keepVisible)
    }
}