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
import com.nash.core.model.TrackedBox

/**
 * Debug overlay drawing tracked boxes over the camera preview.
 *
 * The preview is displayed FIT_CENTER, so the camera content occupies a
 * centered sub-rect of the screen with black bars around it. Boxes are
 * normalized to the camera frame, so they must be mapped into that content
 * rect — not the full canvas.
 *
 * @param frameAspectRatio camera frame aspect as long-side / short-side
 * (16:9 for the current pipeline). Orientation is inferred from the canvas.
 */
@Composable
fun TrackingOverlay(
    trackedBoxes: List<TrackedBox>,
    modifier: Modifier = Modifier,
    frameAspectRatio: Float = 16f / 9f,
    debugIds: Boolean = true // flip off for normal demo mode
) {
    // Reuse one Paint across draws; native text drawing needs android.graphics.Paint.
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier) {
        // --- existing FIT_CENTER math, unchanged ---
        val contentAspect =
            if (size.width >= size.height) frameAspectRatio else 1f / frameAspectRatio
        val canvasAspect = size.width / size.height
        val contentWidth: Float
        val contentHeight: Float
        if (canvasAspect > contentAspect) {
            contentHeight = size.height
            contentWidth = size.height * contentAspect
        } else {
            contentWidth = size.width
            contentHeight = size.width / contentAspect
        }
        val offsetX = (size.width - contentWidth) / 2f
        val offsetY = (size.height - contentHeight) / 2f

        val strokeWidth = 3.dp.toPx()
        trackedBoxes.forEach { tracked ->
            // Debug mode: color is a function of the TRACK ID, so an ID switch
            // is an instant, unmissable color flip. Class moves into the label.
            val color = if (debugIds) {
                colorForId(tracked.id.value)
            } else when (tracked.clazz) {
                DetectionClass.FACE -> Color.Green
                DetectionClass.LICENSE_PLATE -> Color.Yellow
            }

            val box = tracked.box
            val left = offsetX + box.left * contentWidth
            val top = offsetY + box.top * contentHeight
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(
                    width = (box.right - box.left) * contentWidth,
                    height = (box.bottom - box.top) * contentHeight
                ),
                style = Stroke(width = strokeWidth)
            )

            if (debugIds) {
                drawIntoCanvas { canvas ->
                    val label = when (tracked.clazz) {
                        DetectionClass.FACE -> "F#${tracked.id.value}"
                        DetectionClass.LICENSE_PLATE -> "P#${tracked.id.value}"
                    }
                    // Keep the label on-screen when the box touches the top edge.
                    val textY = (top - 10f).coerceAtLeast(textPaint.textSize)
                    canvas.nativeCanvas.drawText(label, left, textY, textPaint)
                }
            }
        }
    }
}

/**
 * Deterministic, well-separated color per track ID (golden-angle hue spacing:
 * consecutive IDs land ~137° apart on the hue wheel, so a switch from #4 to #5
 * is a hard color jump, never a subtle shade change).
 */
private fun colorForId(id: Long): Color {
    val hue = ((id * 137.508) % 360.0).toFloat()
    return Color.hsv(hue, 0.85f, 1f)
}