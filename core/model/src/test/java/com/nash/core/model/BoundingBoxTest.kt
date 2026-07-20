package com.nash.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundingBoxTest {

    @Test
    fun `computed properties return correct values`() {
        val box = BoundingBox(
            left = 0.1f,
            top = 0.2f,
            right = 0.5f,
            bottom = 0.8f
        )

        assertEquals(0.4f, box.width, 0.0001f)
        assertEquals(0.6f, box.height, 0.0001f)
        assertEquals(0.3f, box.centerX, 0.0001f)
        assertEquals(0.5f, box.centerY, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid horizontal coordinates throw exception`() {
        BoundingBox(left = 0.6f, top = 0.1f, right = 0.4f, bottom = 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid vertical coordinates throw exception`() {
        BoundingBox(left = 0.1f, top = 0.8f, right = 0.5f, bottom = 0.2f)
    }
}
