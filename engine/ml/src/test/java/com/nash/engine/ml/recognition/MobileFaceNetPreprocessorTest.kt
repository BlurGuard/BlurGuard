package com.nash.engine.ml.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileFaceNetPreprocessorTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `channels are written in RGB order`() {
        val out = FloatArray(3)

        MobileFaceNetPreprocessor.fill(intArrayOf(argb(r = 255, g = 128, b = 0)), out)

        assertEquals(1.0f, out[0], 1e-6f)                  // R = 255
        assertEquals((128 - 127.5f) / 127.5f, out[1], 1e-6f) // G = 128
        assertEquals(-1.0f, out[2], 1e-6f)                 // B = 0
    }

    @Test
    fun `the value range is normalized to minus one through one`() {
        val out = FloatArray(6)

        MobileFaceNetPreprocessor.fill(
            intArrayOf(argb(0, 0, 0), argb(255, 255, 255)),
            out,
        )

        out.take(3).forEach { assertEquals(-1.0f, it, 1e-6f) }
        out.drop(3).forEach { assertEquals(1.0f, it, 1e-6f) }
    }

    @Test
    fun `alpha is ignored`() {
        val opaque = FloatArray(3)
        val transparent = FloatArray(3)

        MobileFaceNetPreprocessor.fill(intArrayOf(argb(10, 20, 30, a = 0xFF)), opaque)
        MobileFaceNetPreprocessor.fill(intArrayOf(argb(10, 20, 30, a = 0x00)), transparent)

        assertEquals(opaque.toList(), transparent.toList())
    }

    @Test
    fun `pixels are written row-major with no gaps`() {
        val pixels = IntArray(4) { argb(it, it, it) }
        val out = FloatArray(12)

        MobileFaceNetPreprocessor.fill(pixels, out)

        for (i in 0 until 4) {
            val expected = (i - 127.5f) / 127.5f
            assertEquals("pixel $i", expected, out[i * 3], 1e-6f)
        }
    }

    @Test
    fun `the destination must be exactly three floats per pixel`() {
        assertThrows(IllegalArgumentException::class.java) {
            MobileFaceNetPreprocessor.fill(IntArray(4), FloatArray(11))
        }
    }

    @Test
    fun `a full 112x112 frame is filled completely`() {
        val size = 112 * 112
        val out = FloatArray(size * 3) { Float.NaN }

        MobileFaceNetPreprocessor.fill(IntArray(size) { argb(255, 255, 255) }, out)

        assertEquals("no element left unwritten", 0, out.count { it.isNaN() })
    }
}
