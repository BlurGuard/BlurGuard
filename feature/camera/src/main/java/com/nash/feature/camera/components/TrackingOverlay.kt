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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nash.core.designsystem.theme.BlurGuardSemanticColors
import com.nash.core.designsystem.theme.LocalBlurGuardSemanticColors
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.feature.camera.R

/**
 * Debug overlay drawing tracked boxes over the camera preview.
 *
 * PURELY VISUAL: rectangles, IDs, and verification badges only. It handles
 * no pointer input — face-tap enrollment is owned by [FaceTapTargets], which
 * stays active in release builds while this overlay is debug-gated.
 *
 * Geometry comes from [BoxCoordinateMapper], the same mapper used for hit
 * testing, so drawing and tapping cannot drift. Label text and stroke
 * selection live in [TrackingOverlayLabelFormatter] and [TrackingOverlayStyle]
 * (both pure Kotlin, unit-tested); this composable only draws.
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
    val colors = LocalBlurGuardSemanticColors.current
    val style = remember { TrackingOverlayStyle() }

    val facePrefix = stringResource(R.string.tracking_overlay_face_prefix)
    val platePrefix = stringResource(R.string.tracking_overlay_plate_prefix)
    val visibleLabel = stringResource(R.string.tracking_overlay_visible_label)
    val verifyingLabel = stringResource(R.string.tracking_overlay_verifying_label)
    val labelFormatter = remember(facePrefix, platePrefix, visibleLabel, verifyingLabel) {
        TrackingOverlayLabelFormatter(
            facePrefix = facePrefix,
            platePrefix = platePrefix,
            visibleLabel = visibleLabel,
            verifyingLabel = verifyingLabel,
        )
    }

    val textPaint = remember(colors) { overlayTextPaint(colors) }

    Canvas(modifier = modifier) {
        val content = BoxCoordinateMapper.contentRect(size.width, size.height, frameAspectRatio)

        val strokeWidth = 3.dp.toPx()
        trackedBoxes.forEach { tracked ->
            val verification = verifications[tracked.id]
            val color = when (val choice = style.strokeFor(tracked, verification, debugIds)) {
                is StrokeChoice.Debug -> Color.hsv(choice.debugStroke.hue, 0.85f, 1f)
                is StrokeChoice.Token -> when (choice.stroke) {
                    BoxStroke.LICENSE_PLATE -> colors.licensePlateYellow
                    BoxStroke.TRUSTED -> colors.trustedGreen
                    BoxStroke.PENDING -> colors.pendingAmber
                    BoxStroke.REJECTED -> colors.rejectedRed
                    BoxStroke.NEUTRAL -> colors.neutralOverlayStroke
                }
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

            val label = labelFormatter.labelFor(tracked, verification, debugIds)
            if (label != null) {
                drawIntoCanvas { canvas ->
                    val textY = (top - 10f).coerceAtLeast(textPaint.textSize)
                    canvas.nativeCanvas.drawText(label, left, textY, textPaint)
                }
            }
        }
    }
}

/**
 * Label paint built from design-system tokens. Identical values to the
 * pre-extraction hardcoded paint (white text, black shadow, 36px).
 */
private fun overlayTextPaint(colors: BlurGuardSemanticColors): android.graphics.Paint =
    android.graphics.Paint().apply {
        color = colors.overlayText.toArgb()
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, colors.overlayTextShadow.toArgb())
    }
