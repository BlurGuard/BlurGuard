package com.nash.feature.camera

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Route entry point for the camera recording screen.
 *
 * Handles runtime permissions, lifecycle-aware camera binding, and delegates all
 * user events to [CameraViewModel]. No frame or pixel data enters this composable.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraRoute(
    viewModel: CameraViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    val cameraGranted = permissions.permissions
        .find { it.permission == Manifest.permission.CAMERA }
        ?.status?.isGranted == true

    val audioGranted = permissions.permissions
        .find { it.permission == Manifest.permission.RECORD_AUDIO }
        ?.status?.isGranted == true

    // Only bind the engine once the permission is granted, and re-bind if the
    // permission state (or lifecycle owner) changes.
    val context = LocalContext.current
    val previewTarget = remember(lifecycleOwner, context) {
        PreviewTargetImpl(context)
    }

    DisposableEffect(lifecycleOwner, cameraGranted) {
        if (cameraGranted) {
            viewModel.bindEngine(lifecycleOwner, previewTarget)
        }
        onDispose {
            // Engine unbinding should be handled by lifecycle, but we can call
            // unbind if BlurGuardEngine supports it.
        }
    }

    LaunchedEffect(cameraGranted) {
        if (cameraGranted) {
            viewModel.onEvent(CameraEvent.OnCameraPermissionGranted)
        } else {
            viewModel.onEvent(CameraEvent.OnCameraPermissionDenied)
        }
    }

    LaunchedEffect(audioGranted) {
        if (audioGranted) {
            viewModel.onEvent(CameraEvent.OnAudioPermissionGranted)
        } else {
            viewModel.onEvent(CameraEvent.OnAudioPermissionDenied)
        }
    }

    val trackedBoxes by viewModel.trackedBoxes.collectAsStateWithLifecycle()
    val pipelineStats by viewModel.pipelineStats.collectAsStateWithLifecycle()
    val idStats by viewModel.idStats.collectAsStateWithLifecycle()
    val keepVisible by viewModel.keepVisible.collectAsStateWithLifecycle()

    // Stable across recompositions: only rebuilt if the VM or permission
    // controller instance changes.
    val actions = remember(viewModel, permissions) {
        CameraScreenActions(
            onRecordClick = { viewModel.onEvent(CameraEvent.OnRecordClicked) },
            onStopClick = { viewModel.onEvent(CameraEvent.OnStopRecordingClicked) },
            onRequestPermissions = { permissions.launchMultiplePermissionRequest() },
            onDismissError = { viewModel.onEvent(CameraEvent.OnErrorDismissed) },
            onModeClick = { viewModel.onModeClicked() },
            onFaceTapped = { viewModel.onFaceTapped(it) },
            onRevokeAllKeepVisible = { viewModel.onRevokeAllKeepVisible() }
        )
    }

    CameraScreen(
        state = CameraScreenState(
            uiState = uiState,
            trackedBoxes = trackedBoxes,
            stats = pipelineStats,
            debugStats = idStats,
            mode = uiState.anonymizationMode,
            keepVisible = keepVisible
        ),
        previewTarget = previewTarget,
        actions = actions
    )
}