package com.nash.core.ml

import com.nash.core.model.DetectionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloOutputDecoderTest {

    private val classes = listOf(DetectionClass.FACE, DetectionClass.LICENSE_PLATE)

    /** Builds an empty [6][n] output tensor. */
    private fun emptyOutput(n: Int = 2100) = Array(6) { FloatArray(n) }

    /** Writes one candidate (pixel coords) at index [i]. */
    private fun Array<FloatArray>.withCandidate(
        i: Int, cx: Float, cy: Float, w: Float, h: Float,
        faceScore: Float = 0f, plateScore: Float = 0f
    ) = apply {
        this[0][i] = cx; this[1][i] = cy; this[2][i] = w; this[3][i] = h
        this[4][i] = faceScore; this[5][i] = plateScore
    }

    private fun decoder(threshold: Float = 0.5f) =
        YoloOutputDecoder(classes, inputSize = 320, confidenceThreshold = threshold)

    // Identity letterbox: 320x320 buffer, no scaling/padding.
    private val identity = YoloOutputDecoder.Letterbox(1f, 0f, 0f, 320, 320)

    @Test
    fun `decodes a single face box to normalized coords`() {
        val output = emptyOutput().withCandidate(0, cx = 160f, cy = 160f, w = 64f, h = 64f, faceScore = 0.9f)

        val result = decoder().decode(output, identity)

        assertEquals(1, result.size)
        val box = result.first().box
        assertEquals(0.4f, box.left, 1e-3f)
        assertEquals(0.6f, box.right, 1e-3f)
        assertEquals(DetectionClass.FACE, result.first().clazz)
    }

    @Test
    fun `handles normalized-coordinate exports`() {
        // Same box but coords in 0..1 (the magnitude sniffer must scale them).
        val output = emptyOutput().withCandidate(0, cx = 0.5f, cy = 0.5f, w = 0.2f, h = 0.2f, faceScore = 0.9f)

        val result = decoder().decode(output, identity)

        assertEquals(1, result.size)
        assertEquals(0.4f, result.first().box.left, 1e-3f)
    }

    @Test
    fun `below-threshold candidates are dropped`() {
        val output = emptyOutput().withCandidate(0, 160f, 160f, 64f, 64f, faceScore = 0.3f)

        assertTrue(decoder(threshold = 0.5f).decode(output, identity).isEmpty())
    }

    @Test
    fun `nms keeps only the strongest of overlapping boxes`() {
        val output = emptyOutput()
            .withCandidate(0, 160f, 160f, 64f, 64f, faceScore = 0.9f)
            .withCandidate(1, 164f, 162f, 64f, 64f, faceScore = 0.7f) // heavy overlap

        val result = decoder().decode(output, identity)

        assertEquals(1, result.size)
        assertEquals(0.9f, result.first().confidence, 1e-3f)
    }

    @Test
    fun `overlapping boxes of different classes both survive`() {
        val output = emptyOutput()
            .withCandidate(0, 160f, 160f, 64f, 64f, faceScore = 0.9f)
            .withCandidate(1, 160f, 160f, 64f, 64f, plateScore = 0.8f)

        assertEquals(2, decoder().decode(output, identity).size)
    }

    @Test
    fun `letterbox padding is removed`() {
        // 640x360 buffer -> scale 0.5, padY = (320-180)/2 = 70.
        val letterbox = YoloOutputDecoder.Letterbox(0.5f, 0f, 70f, 640, 360)
        // Box centered in the buffer: input px center = (160, 160).
        val output = emptyOutput().withCandidate(0, 160f, 160f, 32f, 32f, faceScore = 0.9f)

        val box = decoder().decode(output, letterbox).first().box

        assertEquals(0.5f, (box.left + box.right) / 2f, 1e-2f)
        assertEquals(0.5f, (box.top + box.bottom) / 2f, 1e-2f)
    }
}