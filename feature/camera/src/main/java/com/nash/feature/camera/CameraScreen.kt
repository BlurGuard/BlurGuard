package com.nash.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nash.core.designsystem.theme.LocalBlurGuardSemanticColors
import com.nash.engine.api.PreviewTarget
import com.nash.engine.api.RecordingState
import com.nash.feature.camera.components.FaceTapTargets
import com.nash.feature.camera.components.KeepVisibleControls
import com.nash.feature.camera.components.ModeChip
import com.nash.feature.camera.components.PipelineDebugHud
import com.nash.feature.camera.components.TrackingOverlay
import com.nash.feature.camera.state.CameraScreenActions
import com.nash.feature.camera.state.CameraScreenState
import com.nash.feature.camera.state.CameraUiState

/**
 * Camera recording screen.
 *
 * Pure layout coordinator: all state comes in via [com.nash.feature.camera.state.CameraScreenState], all
 * events go out via [com.nash.feature.camera.state.CameraScreenActions]. Debug-only surfaces (tracking
 * overlay + pipeline HUD) are gated by [showDebugOverlays].
 */
@Composable
fun CameraScreen(
    state: CameraScreenState,
    previewTarget: PreviewTarget,
    actions: CameraScreenActions,
    showDebugOverlays: Boolean = BuildConfig.DEBUG
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.uiState.errorMessage) {
        state.uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            actions.onDismissError()
        }
    }

    LaunchedEffect(state.uiState.errorMessageRes) {
        state.uiState.errorMessageRes?.let { messageRes ->
            snackbarHostState.showSnackbar(context.getString(messageRes))
            actions.onDismissError()
        }
    }

    LaunchedEffect(state.uiState.keepVisibleMessage) {
        state.uiState.keepVisibleMessage?.let { messageRes ->
            snackbarHostState.showSnackbar(context.getString(messageRes))
            actions.onKeepVisibleMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!state.uiState.cameraPermissionGranted) {
                PermissionRationale(
                    onRequestPermissions = actions.onRequestPermissions,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                CameraPreview(
                    previewTarget = previewTarget,
                    modifier = Modifier.fillMaxSize()
                )

                // Single tap owner: always active (keep-visible enrollment is a
                // release feature) and renders nothing. Shares the preview's
                // exact bounds so tap coordinates line up. Controls and chips
                // are placed later in this Box, so they hit-test above it and
                // stay clickable.
                FaceTapTargets(
                    trackedBoxes = state.trackedBoxes,
                    onFaceTapped = actions.onFaceTapped,
                    modifier = Modifier.fillMaxSize()
                )

                if (showDebugOverlays) {
                    // Purely visual debug overlay — no pointer handling, so taps
                    // pass through it to FaceTapTargets underneath.
                    TrackingOverlay(
                        trackedBoxes = state.trackedBoxes,
                        verifications = state.keepVisible,
                        modifier = Modifier.fillMaxSize()
                    )
                    PipelineDebugHud(
                        stats = state.stats,
                        debugStats = state.debugStats,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(8.dp)
                    )
                }
                KeepVisibleControls(
                    keepVisible = state.keepVisible,
                    onRevokeAll = actions.onRevokeAllKeepVisible,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )
                RecordingOverlay(
                    uiState = state.uiState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                ModeChip(
                    mode = state.mode,
                    onClick = actions.onModeClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp)
                )
                Controls(
                    isRecording = state.uiState.isRecording,
                    isBusy = state.uiState.isStartingOrStopping,
                    onRecordClick = actions.onRecordClick,
                    onStopClick = actions.onStopClick,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    previewTarget: PreviewTarget,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = {
            (previewTarget as PreviewTargetImpl).view
        },
        modifier = modifier
    )
}

@Composable
private fun PermissionRationale(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.camera_permission_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text(stringResource(R.string.camera_grant_permissions))
        }
    }
}

@Composable
private fun RecordingOverlay(
    uiState: CameraUiState,
    modifier: Modifier = Modifier
) {
    val overlayColors = LocalBlurGuardSemanticColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        when (val state = uiState.recordingState) {
            is RecordingState.Recording -> {
                RecordingIndicator(durationSeconds = uiState.durationSeconds)
            }

            is RecordingState.Saved -> {
                Text(
                    text = stringResource(R.string.camera_saved_video, state.uri),
                    color = overlayColors.overlayOnScrim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(overlayColors.overlayScrim)
                        .padding(8.dp)
                )
            }

            else -> { /* Idle / Starting / Stopping — no persistent overlay */ }
        }
    }
}

@Composable
private fun RecordingIndicator(durationSeconds: Int) {
    val overlayColors = LocalBlurGuardSemanticColors.current
    Box(
        modifier = Modifier
            .background(overlayColors.overlayScrim)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(
                R.string.camera_recording_indicator,
                formatDuration(durationSeconds)
            ),
            color = overlayColors.recordingRed,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun Controls(
    isRecording: Boolean,
    isBusy: Boolean,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overlayColors = LocalBlurGuardSemanticColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(overlayColors.overlayOnScrim)
                    .clickable(enabled = !isBusy, onClick = onStopClick)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(overlayColors.recordingRed)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(overlayColors.recordingRed)
                    .clickable(enabled = !isBusy, onClick = onRecordClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.camera_record_button_label),
                    color = overlayColors.overlayOnScrim,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}