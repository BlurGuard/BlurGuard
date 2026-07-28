package com.nash.core.ml.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarityTransformTest {

    private fun apply(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[1] * p[1] + m[2],
        m[3] * p[0] + m[4] * p[1] + m[5]
    )

    @Test
    fun `eyes land exactly on the template`() {
        // A rotated, scaled, offset eye pair.
        val srcLeft = floatArrayOf(100f, 120f)
        val srcRight = floatArrayOf(180f, 100f)
        val m = SimilarityTransform.fromEyes(srcLeft, srcRight)!!
        val outLeft = apply(m, srcLeft)
        val outRight = apply(m, srcRight)
        assertEquals(SimilarityTransform.TEMPLATE_LEFT_EYE[0], outLeft[0], 1e-3f)
        assertEquals(SimilarityTransform.TEMPLATE_LEFT_EYE[1], outLeft[1], 1e-3f)
        assertEquals(SimilarityTransform.TEMPLATE_RIGHT_EYE[0], outRight[0], 1e-3f)
        assertEquals(SimilarityTransform.TEMPLATE_RIGHT_EYE[1], outRight[1], 1e-3f)
    }

    @Test
    fun `no shear - a point off the eye line keeps its relative geometry`() {
        val srcLeft = floatArrayOf(0f, 0f)
        val srcRight = floatArrayOf(100f, 0f)
        val srcBelowMid = floatArrayOf(50f, 50f) // nose-ish, on the perpendicular
        val m = SimilarityTransform.fromEyes(srcLeft, srcRight)!!
        val out = apply(m, srcBelowMid)
        // Must stay on the perpendicular bisector of the template eyes (~x=55.9)
        val midX = (SimilarityTransform.TEMPLATE_LEFT_EYE[0] +
                SimilarityTransform.TEMPLATE_RIGHT_EYE[0]) / 2f
        assertEquals(midX, out[0], 0.5f)
    }

    @Test
    fun `coincident eyes are rejected`() {
        assertNull(SimilarityTransform.fromEyes(floatArrayOf(50f, 50f), floatArrayOf(50f, 50f)))
    }
}