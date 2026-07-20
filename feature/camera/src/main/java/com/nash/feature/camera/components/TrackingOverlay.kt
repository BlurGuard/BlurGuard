package com.nash.feature.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    frameAspectRatio: Float = 16f / 9f
) {
    Canvas(modifier = modifier) {
        // Upright frame aspect: landscape screen -> 16:9, portrait -> 9:16.
        val contentAspect =
            if (size.width >= size.height) frameAspectRatio else 1f / frameAspectRatio

        // Largest rect of that aspect fitting the canvas, centered (FIT_CENTER).
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
            val color = when (tracked.clazz) {
                DetectionClass.FACE -> Color.Green
                DetectionClass.LICENSE_PLATE -> Color.Yellow
            }
            val box = tracked.box
            drawRect(
                color = color,
                topLeft = Offset(
                    x = offsetX + box.left * contentWidth,
                    y = offsetY + box.top * contentHeight
                ),
                size = Size(
                    width = (box.right - box.left) * contentWidth,
                    height = (box.bottom - box.top) * contentHeight
                ),
                style = Stroke(width = strokeWidth)
            )
        }
    }
}