package com.nash.feature.camera.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox

/**
 * Invisible tap layer for keep-visible face enrollment.
 *
 * This is the ONLY owner of face-tap hit testing — [TrackingOverlay] is
 * purely visual. Always active, including release builds, because
 * keep-visible enrollment is a user-facing feature, not a debug tool.
 *
 * Renders nothing. Must be laid out with the exact same bounds as the
 * camera preview (fillMaxSize over the preview box) so tap coordinates
 * map onto the frame via [BoxCoordinateMapper].
 */
@Composable
fun FaceTapTargets(
    trackedBoxes: List<TrackedBox>,
    onFaceTapped: (TrackId) -> Unit,
    modifier: Modifier = Modifier,
    frameAspectRatio: Float = 16f / 9f
) {
    // Latest values readable from the tap handler without restarting pointerInput.
    val currentBoxes by rememberUpdatedState(trackedBoxes)
    val currentOnFaceTapped by rememberUpdatedState(onFaceTapped)

    Box(
        modifier = modifier.pointerInput(frameAspectRatio) {
            detectTapGestures { tap ->
                BoxCoordinateMapper.faceTrackIdAt(
                    tapX = tap.x,
                    tapY = tap.y,
                    canvasWidth = size.width.toFloat(),
                    canvasHeight = size.height.toFloat(),
                    frameAspectRatio = frameAspectRatio,
                    boxes = currentBoxes
                )?.let { currentOnFaceTapped(it) }
            }
        }
    )
}