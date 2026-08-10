package com.nash.engine.ml.recognition

import org.junit.Assert.assertSame
import org.junit.Test

class FaceKeypointExtractorTest {
    private val extractor = FaceKeypointExtractor()

    @Test
    fun `keeps eye order when first eye is image-left`() {
        val first = FacePoint(10f, 20f)
        val second = FacePoint(40f, 22f)

        val (leftEye, rightEye) = extractor.orderEyes(first, second)

        assertSame(first, leftEye)
        assertSame(second, rightEye)
    }

    @Test
    fun `swaps eye order when second eye is image-left`() {
        val first = FacePoint(40f, 22f)
        val second = FacePoint(10f, 20f)

        val (leftEye, rightEye) = extractor.orderEyes(first, second)

        assertSame(second, leftEye)
        assertSame(first, rightEye)
    }

    @Test
    fun `keeps stable order when eyes share the same x coordinate`() {
        val first = FacePoint(10f, 20f)
        val second = FacePoint(10f, 22f)

        val (leftEye, rightEye) = extractor.orderEyes(first, second)

        assertSame(first, leftEye)
        assertSame(second, rightEye)
    }
}