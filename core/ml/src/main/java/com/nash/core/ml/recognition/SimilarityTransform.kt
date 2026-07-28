package com.nash.core.ml.recognition

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Closed-form 2-point similarity transform (rotation + uniform scale +
 * translation): maps the detected eye pair onto the ArcFace template eye
 * positions. Pure Kotlin so the warp math is unit-testable without Android.
 */
object SimilarityTransform {

    /** ArcFace 112x112 canonical template. */
    const val OUTPUT_SIZE = 112
    val TEMPLATE_LEFT_EYE = floatArrayOf(38.2946f, 51.6963f)
    val TEMPLATE_RIGHT_EYE = floatArrayOf(73.5318f, 51.5014f)

    /**
     * Affine coefficients [a, b, tx, c, d, ty] for:
     *   x' = a*x + b*y + tx
     *   y' = c*x + d*y + ty
     * mapping (srcLeft, srcRight) onto (dstLeft, dstRight) with a similarity
     * (no shear, no aspect change). Returns null if the source points are
     * (near-)coincident.
     *
     * Row-major order matches android.graphics.Matrix.setValues() rows 0-1.
     */
    fun fromEyes(
        srcLeft: FloatArray,
        srcRight: FloatArray,
        dstLeft: FloatArray = TEMPLATE_LEFT_EYE,
        dstRight: FloatArray = TEMPLATE_RIGHT_EYE
    ): FloatArray? {
        val srcDx = srcRight[0] - srcLeft[0]
        val srcDy = srcRight[1] - srcLeft[1]
        val srcLen = hypot(srcDx, srcDy)
        if (srcLen < 1e-3f) return null

        val dstDx = dstRight[0] - dstLeft[0]
        val dstDy = dstRight[1] - dstLeft[1]
        val scale = hypot(dstDx, dstDy) / srcLen
        val angle = atan2(dstDy, dstDx) - atan2(srcDy, srcDx)

        val a = (scale * cos(angle))
        val b = (-scale * sin(angle))
        val c = (scale * sin(angle))
        val d = (scale * cos(angle))
        // Anchor: source left eye must land exactly on the template left eye.
        val tx = dstLeft[0] - (a * srcLeft[0] + b * srcLeft[1])
        val ty = dstLeft[1] - (c * srcLeft[0] + d * srcLeft[1])
        return floatArrayOf(a, b, tx, c, d, ty)
    }
}