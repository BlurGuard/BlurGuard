package com.nash.engine.ml.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap

internal class AlignedFaceRenderer {
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun render(crop: Bitmap, keypoints: FaceKeypoints): Bitmap? {
        val coefficients = SimilarityTransform.fromEyes(
            keypoints.leftEyeArray(),
            keypoints.rightEyeArray(),
        ) ?: return null
        val transform = Matrix().apply {
            setValues(
                floatArrayOf(
                    coefficients[0], coefficients[1], coefficients[2],
                    coefficients[3], coefficients[4], coefficients[5],
                    0f, 0f, 1f,
                )
            )
        }

        val aligned = createBitmap(SimilarityTransform.OUTPUT_SIZE, SimilarityTransform.OUTPUT_SIZE)
        Canvas(aligned).drawBitmap(crop, transform, filterPaint)
        return aligned
    }
}
