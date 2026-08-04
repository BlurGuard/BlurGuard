package com.nash.feature.camera

import android.content.Context
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.PreviewTarget
import com.nash.engine.api.RecordingState
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import com.nash.feature.camera.components.TrackingOverlay

/**
 * Camera recording screen.
 *
 * Displays the camera preview, record/stop controls, status information, and
 * the debug tracking overlay.
 */
@Composable
fun CameraScreen(
    uiState: CameraUiState,
    previewTarget: PreviewTarget,
    trackedBoxes: List<TrackedBox>,
    onRecordClick: () -> Unit,
    onStopClick: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDismissError: () -> Unit,
    stats: PipelineStats,
    mode: AnonymizationMode,
    onModeClick: () -> Unit,
    idStats: CameraDebugStatsUiModel,
    keepVisible: Map<TrackId, TrackVerification>,
    onFaceTapped: (TrackId) -> Unit,
    onRevokeAllKeepVisible: () -> Unit
    ) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onDismissError()
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
            if (!uiState.cameraPermissionGranted) {
                PermissionRationale(
                    onRequestPermissions = onRequestPermissions,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                CameraPreview(
                    previewTarget = previewTarget,
                    modifier = Modifier.fillMaxSize()
                )

                // Debug overlay: must sit directly on top of the preview and
                // share its exact bounds so normalized coords line up.
                TrackingOverlay(
                    trackedBoxes = trackedBoxes,
                    modifier = Modifier.fillMaxSize(),
                    verifications = keepVisible,
                    onFaceTapped = onFaceTapped,
                )
                val anyKeptVisible = keepVisible.values.any {
                    it.state == VerificationState.TRUSTED || it.state == VerificationState.PENDING
                }
                if (anyKeptVisible) {
                    AssistChip(
                        onClick = onRevokeAllKeepVisible,
                        label = { Text("Re-blur all") },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                }
                RecordingOverlay(
                    uiState = uiState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                Text("ids: ${idStats.active} active / ${idStats.totalSeen} seen")
                Text(
                    text = "%.0f fps · det %.1f/s · %d ms".format(
                        stats.frameFps, stats.fps, stats.detectionLatencyMillis
                    ),
                    color = Color.Green,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = mode.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .clickable { onModeClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Controls(
                    isRecording = uiState.isRecording,
                    isBusy = uiState.isStartingOrStopping,
                    onRecordClick = onRecordClick,
                    onStopClick = onStopClick,
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
            text = "Camera and microphone access are needed to record video.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant permissions")
        }
    }
}

@Composable
private fun RecordingOverlay(
    uiState: CameraUiState,
    modifier: Modifier = Modifier
) {
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
                    text = "Saved: ${state.uri}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }

            else -> { /* Idle / Starting / Stopping — no persistent overlay */ }
        }
    }
}

@Composable
private fun RecordingIndicator(durationSeconds: Int) {
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "● REC ${formatDuration(durationSeconds)}",
            color = Color.Red,
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
                    .background(Color.White)
                    .clickable(enabled = !isBusy, onClick = onStopClick)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Red)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
                    .clickable(enabled = !isBusy, onClick = onRecordClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "REC",
                    color = Color.White,
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
