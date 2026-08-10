package com.nash.engine.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the two halves of preprocessing that don't need
 * Android: letterbox geometry ([YoloPreprocessor.fit]) and tensor layout
 * ([YoloPreprocessor.writeRgbFloats]).
 *
 * The Bitmap/Canvas glue in `process()` is intentionally not covered here —
 * it would drag in Robolectric for three lines of drawing. Everything that can
 * be numerically wrong lives in the two functions below.
 */
class YoloPreprocessorTest {

    // ---- Letterbox geometry ------------------------------------------------

    @Test
    fun `portrait source scales to height and pads horizontally`() {
        val fit = YoloPreprocessor.fit(sourceWidth = 720, sourceHeight = 1280, inputSize = INPUT)

        assertEquals(0.25f, fit.scale, EPS)
        assertEquals(180, fit.scaledWidth)
        assertEquals(320, fit.scaledHeight)
        assertEquals(70f, fit.padX, EPS)
        assertEquals(0f, fit.padY, EPS)
    }

    @Test
    fun `landscape source scales to width and pads vertically`() {
        val fit = YoloPreprocessor.fit(sourceWidth = 1280, sourceHeight = 720, inputSize = INPUT)

        assertEquals(0.25f, fit.scale, EPS)
        assertEquals(320, fit.scaledWidth)
        assertEquals(180, fit.scaledHeight)
        assertEquals(0f, fit.padX, EPS)
        assertEquals(70f, fit.padY, EPS)
    }

    @Test
    fun `square source fills the input with no padding`() {
        val fit = YoloPreprocessor.fit(sourceWidth = 640, sourceHeight = 640, inputSize = INPUT)

        assertEquals(0.5f, fit.scale, EPS)
        assertEquals(320, fit.scaledWidth)
        assertEquals(320, fit.scaledHeight)
        assertEquals(0f, fit.padX, EPS)
        assertEquals(0f, fit.padY, EPS)
    }

    @Test
    fun `source smaller than the input is upscaled, not left as-is`() {
        val fit = YoloPreprocessor.fit(sourceWidth = 160, sourceHeight = 120, inputSize = INPUT)

        assertEquals(2f, fit.scale, EPS)
        assertEquals(320, fit.scaledWidth)
        assertEquals(240, fit.scaledHeight)
        assertEquals(0f, fit.padX, EPS)
        assertEquals(40f, fit.padY, EPS)
    }

    @Test
    fun `odd padding keeps the drawn rect inside the input square`() {
        // 300x200 -> 320x213, leaving 107 px of vertical slack: a half-pixel pad.
        val fit = YoloPreprocessor.fit(sourceWidth = 300, sourceHeight = 200, inputSize = INPUT)

        assertEquals(320, fit.scaledWidth)
        assertEquals(213, fit.scaledHeight)
        assertEquals(53.5f, fit.padY, EPS)

        // This is exactly how process() builds the destination Rect.
        val top = fit.padY.toInt()
        val bottom = top + fit.scaledHeight
        assertTrue("rect must not overflow the input square", bottom <= INPUT)
        assertEquals(53, top)
        assertEquals(266, bottom)
    }

    @Test
    fun `aspect ratio is preserved for every orientation`() {
        listOf(720 to 1280, 1280 to 720, 640 to 640, 300 to 200).forEach { (w, h) ->
            val fit = YoloPreprocessor.fit(w, h, INPUT)
            val sourceRatio = w.toFloat() / h
            val scaledRatio = fit.scaledWidth.toFloat() / fit.scaledHeight
            assertEquals("aspect changed for ${w}x$h", sourceRatio, scaledRatio, 0.01f)
        }
    }

    // ---- Tensor layout -----------------------------------------------------

    @Test
    fun `NHWC layout writes interleaved RGB per pixel`() {
        val buffer = heapBuffer(PIXELS.size)

        YoloPreprocessor.writeRgbFloats(PIXELS, buffer, channelsFirst = false)

        assertFloats(
            buffer,
            floatArrayOf(
                32 / 255f, 64 / 255f, 128 / 255f, // pixel 0: R, G, B
                0 / 255f, 255 / 255f, 127 / 255f  // pixel 1: R, G, B
            )
        )
    }

    @Test
    fun `NCHW layout writes full R, G, then B planes`() {
        val buffer = heapBuffer(PIXELS.size)

        YoloPreprocessor.writeRgbFloats(PIXELS, buffer, channelsFirst = true)

        assertFloats(
            buffer,
            floatArrayOf(
                32 / 255f, 0 / 255f,     // R plane
                64 / 255f, 255 / 255f,   // G plane
                128 / 255f, 127 / 255f   // B plane
            )
        )
    }

    @Test
    fun `buffer is rewound so the interpreter reads from position zero`() {
        val buffer = heapBuffer(PIXELS.size)

        YoloPreprocessor.writeRgbFloats(PIXELS, buffer, channelsFirst = false)

        assertEquals(0, buffer.position())
    }

    @Test
    fun `values are normalized into 0 to 1`() {
        val extremes = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val buffer = heapBuffer(extremes.size)

        YoloPreprocessor.writeRgbFloats(extremes, buffer, channelsFirst = false)

        assertFloats(buffer, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))
    }

    @Test
    fun `writing twice does not append`() {
        val buffer = heapBuffer(PIXELS.size)

        YoloPreprocessor.writeRgbFloats(PIXELS, buffer, channelsFirst = false)
        YoloPreprocessor.writeRgbFloats(PIXELS, buffer, channelsFirst = false)

        // Frame N+1 must overwrite frame N in the reused buffer.
        assertEquals(0, buffer.position())
        assertEquals(32 / 255f, buffer.getFloat(0), EPS)
    }

    private fun heapBuffer(pixelCount: Int): ByteBuffer =
        ByteBuffer.allocate(pixelCount * 3 * 4).order(ByteOrder.nativeOrder())

    private fun assertFloats(buffer: ByteBuffer, expected: FloatArray) {
        expected.forEachIndexed { index, value ->
            assertEquals("float #$index", value, buffer.getFloat(index * 4), EPS)
        }
    }

    private companion object {
        const val INPUT = 320
        const val EPS = 1e-6f

        /** 0xAARRGGBB: (32, 64, 128) and (0, 255, 127). */
        val PIXELS = intArrayOf(0xFF204080.toInt(), 0xFF00FF7F.toInt())
    }
}
