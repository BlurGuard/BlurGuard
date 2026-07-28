package com.nash.feature.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState

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
    verifications: Map<TrackId, TrackVerification>,
    onFaceTapped: (TrackId) -> Unit,
    modifier: Modifier = Modifier,
    frameAspectRatio: Float = 16f / 9f,
    debugIds: Boolean = false
) {
    // Latest boxes readable from the tap handler without restarting pointerInput.
    val currentBoxes by rememberUpdatedState(trackedBoxes)

    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(
        modifier = modifier.pointerInput(frameAspectRatio) {
            detectTapGestures { tap ->
                val content = contentRect(
                    size.width.toFloat(), size.height.toFloat(), frameAspectRatio
                )
                // Canvas -> normalized upright-frame coordinates (inverse of draw).
                val nx = (tap.x - content[0]) / content[2]
                val ny = (tap.y - content[1]) / content[3]
                if (nx !in 0f..1f || ny < 0f || ny > 1f) return@detectTapGestures
                hitTestFace(currentBoxes, nx, ny)?.let { onFaceTapped(it.id) }
            }
        }
    ) {
        val content = contentRect(size.width, size.height, frameAspectRatio)
        val (offsetX, offsetY, contentWidth, contentHeight) = content

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

/** FIT_CENTER content rect: [offsetX, offsetY, width, height]. Shared by draw + tap. */
private fun contentRect(
    canvasWidth: Float,
    canvasHeight: Float,
    frameAspectRatio: Float
): FloatArray {
    val contentAspect =
        if (canvasWidth >= canvasHeight) frameAspectRatio else 1f / frameAspectRatio
    val canvasAspect = canvasWidth / canvasHeight
    val contentWidth: Float
    val contentHeight: Float
    if (canvasAspect > contentAspect) {
        contentHeight = canvasHeight
        contentWidth = canvasHeight * contentAspect
    } else {
        contentWidth = canvasWidth
        contentHeight = canvasWidth / contentAspect
    }
    return floatArrayOf(
        (canvasWidth - contentWidth) / 2f,
        (canvasHeight - contentHeight) / 2f,
        contentWidth,
        contentHeight
    )
}

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]

/**
 * Face whose (dilated) box contains the tap. Dilation matches the renderer's
 * BOX_DILATION so tapping the blurred halo works. Smallest match wins — the
 * most specific target when boxes overlap. Plates are not tappable.
 */
private fun hitTestFace(boxes: List<TrackedBox>, nx: Float, ny: Float): TrackedBox? =
    boxes
        .filter { it.clazz == DetectionClass.FACE }
        .filter { tracked ->
            val b = tracked.box
            val dx = (b.right - b.left) * HIT_DILATION
            val dy = (b.bottom - b.top) * HIT_DILATION
            nx >= b.left - dx && nx <= b.right + dx && ny >= b.top - dy && ny <= b.bottom + dy
        }
        .minByOrNull { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }

private const val HIT_DILATION = 0.25f

private fun colorForId(id: Long): Color {
    val hue = ((id * 137.508) % 360.0).toFloat()
    return Color.hsv(hue, 0.85f, 1f)
}