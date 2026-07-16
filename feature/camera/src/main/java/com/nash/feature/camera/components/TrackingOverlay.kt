package com.nash.feature.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackedBox

/**
 * Debug overlay: draws tracked boxes over the camera preview.
 * Box coordinates are normalized to the upright analysis frame, so we just
 * scale by the overlay's size. Faces = green, plates = yellow.
 */
@Composable
fun TrackingOverlay(
    boxes: List<TrackedBox>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        boxes.forEach { tracked ->
            val color = when (tracked.clazz) {
                DetectionClass.FACE -> Color.Green
                DetectionClass.LICENSE_PLATE -> Color.Yellow
            }
            drawRect(
                color = color,
                topLeft = Offset(
                    x = tracked.box.left * size.width,
                    y = tracked.box.top * size.height
                ),
                size = Size(
                    width = tracked.box.width * size.width,
                    height = tracked.box.height * size.height
                ),
                style = Stroke(width = 4f)
            )
        }
    }
}