package com.nash.engine.ml.recognition

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlignedFaceRendererTest {
    @Test
    fun `similarity transform exists for usable eye pair`() {
        val coefficients = SimilarityTransform.fromEyes(
            floatArrayOf(10f, 10f),
            floatArrayOf(40f, 10f),
        )

        assertNotNull(coefficients)
    }

    @Test
    fun `similarity transform fails closed for degenerate eye pair`() {
        val coefficients = SimilarityTransform.fromEyes(
            floatArrayOf(10f, 10f),
            floatArrayOf(10f, 10f),
        )

        assertNull(coefficients)
    }
}
