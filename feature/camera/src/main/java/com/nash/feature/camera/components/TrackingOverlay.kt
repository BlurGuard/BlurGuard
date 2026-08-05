package com.nash.feature.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState

/**
 * Debug overlay drawing tracked boxes over the camera preview.
 *
 * PURELY VISUAL: rectangles, IDs, and verification badges only. It handles
 * no pointer input — face-tap enrollment is owned by [FaceTapTargets], which
 * stays active in release builds while this overlay is debug-gated.
 *
 * Geometry comes from [BoxCoordinateMapper], the same mapper used for hit
 * testing, so drawing and tapping cannot drift.
 *
 * @param frameAspectRatio camera frame aspect as long-side / short-side
 * (16:9 for the current pipeline). Orientation is inferred from the canvas.
 */
@Composable
fun TrackingOverlay(
    trackedBoxes: List<TrackedBox>,
    verifications: Map<TrackId, TrackVerification>,
    modifier: Modifier = Modifier,
    frameAspectRatio: Float = 16f / 9f,
    debugIds: Boolean = false
) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier) {
        val content = BoxCoordinateMapper.contentRect(size.width, size.height, frameAspectRatio)

        val strokeWidth = 3.dp.toPx()
        trackedBoxes.forEach { tracked ->
            val verification = verifications[tracked.id]
            val color = when {
                debugIds -> colorForId(tracked.id.value)
                tracked.clazz == DetectionClass.LICENSE_PLATE -> Color.Yellow
                verification?.state == VerificationState.TRUSTED -> Color.Green
                verification?.state == VerificationState.PENDING -> Color(0xFFFFB300) // amber
                verification?.state == VerificationState.REJECTED -> Color(0xFFE53935) // red
                else -> Color.White.copy(alpha = 0.7f)
            }

            val box = tracked.box
            val left = content.offsetX + box.left * content.width
            val top = content.offsetY + box.top * content.height
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(
                    width = (box.right - box.left) * content.width,
                    height = (box.bottom - box.top) * content.height
                ),
                style = Stroke(width = strokeWidth)
            )

            val label = when {
                debugIds -> "${if (tracked.clazz == DetectionClass.FACE) "F" else "P"}#${tracked.id.value}"
                verification?.state == VerificationState.TRUSTED -> "✓ visible"
                verification?.state == VerificationState.PENDING -> "verifying…"
                else -> null
            }
            if (label != null) {
                drawIntoCanvas { canvas ->
                    val textY = (top - 10f).coerceAtLeast(textPaint.textSize)
                    canvas.nativeCanvas.drawText(label, left, textY, textPaint)
                }
            }
        }
    }
}

private fun colorForId(id: Long): Color {
    val hue = ((id * 137.508) % 360.0).toFloat()
    return Color.hsv(hue, 0.85f, 1f)
}