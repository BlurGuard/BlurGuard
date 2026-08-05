package com.nash.engine.ml

import com.nash.core.model.DetectionClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the seam introduced by the refactor: the letterbox metadata the
 * preprocessor computes must be exactly what the decoder needs to un-letterbox
 * with. A box placed at a known position in source pixels is projected into
 * model-input space by hand, fed through [YoloOutputDecoder], and must come
 * back at the same place.
 *
 * If the box drifts, preprocessing and decoding disagree — which on-device
 * shows up as blur landing next to a face instead of on it.
 */
class LetterboxHandoffTest {

    @Test
    fun `landscape source round-trips through letterbox and decode`() {
        assertRoundTrip(
            sourceWidth = 1280,
            sourceHeight = 720,
            left = 400f, top = 200f, right = 560f, bottom = 360f
        )
    }

    @Test
    fun `portrait source round-trips through letterbox and decode`() {
        assertRoundTrip(
            sourceWidth = 720,
            sourceHeight = 1280,
            left = 100f, top = 400f, right = 260f, bottom = 560f
        )
    }

    @Test
    fun `upscaled small source round-trips through letterbox and decode`() {
        assertRoundTrip(
            sourceWidth = 160,
            sourceHeight = 120,
            left = 20f, top = 30f, right = 100f, bottom = 90f
        )
    }

    @Test
    fun `decoded class and confidence survive the handoff`() {
        val letterbox = letterboxFor(1280, 720)
        val output = tensor(cx = 160f, cy = 160f, w = 40f, h = 40f, faceScore = 0.77f)

        val detection = decoder().decode(output, letterbox).single()

        assertEquals(DetectionClass.FACE, detection.clazz)
        assertEquals(0.77f, detection.confidence, EPS)
    }

    /**
     * Builds the [YoloOutputDecoder.Letterbox] exactly the way
     * `YoloPreprocessor.process()` does. Keep the two in sync.
     */
    private fun letterboxFor(width: Int, height: Int): YoloOutputDecoder.Letterbox {
        val fit = YoloPreprocessor.fit(width, height, INPUT)
        return YoloOutputDecoder.Letterbox(
            scale = fit.scale,
            padX = fit.padX,
            padY = fit.padY,
            bufferWidth = width,
            bufferHeight = height
        )
    }

    private fun assertRoundTrip(
        sourceWidth: Int,
        sourceHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val fit = YoloPreprocessor.fit(sourceWidth, sourceHeight, INPUT)
        val letterbox = letterboxFor(sourceWidth, sourceHeight)

        // Source pixels -> model-input pixels, the forward direction the
        // preprocessor's scale-and-pad performs on the actual image data.
        val cx = ((left + right) / 2f) * fit.scale + fit.padX
        val cy = ((top + bottom) / 2f) * fit.scale + fit.padY
        val w = (right - left) * fit.scale
        val h = (bottom - top) * fit.scale

        val detection = decoder()
            .decode(tensor(cx, cy, w, h, faceScore = 0.9f), letterbox)
            .single()

        assertEquals(left / sourceWidth, detection.box.left, EPS)
        assertEquals(top / sourceHeight, detection.box.top, EPS)
        assertEquals(right / sourceWidth, detection.box.right, EPS)
        assertEquals(bottom / sourceHeight, detection.box.bottom, EPS)
    }

    private fun decoder() = YoloOutputDecoder(
        classes = listOf(DetectionClass.LICENSE_PLATE, DetectionClass.FACE),
        inputSize = INPUT,
        confidenceThreshold = 0.1f
    )

    /** One candidate, pixel-space coords, `[4 + numClasses][1]`. */
    private fun tensor(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        faceScore: Float
    ): Array<FloatArray> = arrayOf(
        floatArrayOf(cx),
        floatArrayOf(cy),
        floatArrayOf(w),
        floatArrayOf(h),
        floatArrayOf(0f),        // license plate
        floatArrayOf(faceScore)  // face
    )

    private companion object {
        const val INPUT = 320
        const val EPS = 1e-4f
    }
}