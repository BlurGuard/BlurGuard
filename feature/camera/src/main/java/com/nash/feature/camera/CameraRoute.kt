package com.nash.feature.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

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
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )

    val cameraGranted = permissions.permissions
        .find { it.permission == android.Manifest.permission.CAMERA }
        ?.status?.isGranted == true

    val audioGranted = permissions.permissions
        .find { it.permission == android.Manifest.permission.RECORD_AUDIO }
        ?.status?.isGranted == true

    // Only bind the camera once the permission is granted, and re-bind if the
    // permission state (or lifecycle owner) changes.
    DisposableEffect(lifecycleOwner, cameraGranted) {
        if (cameraGranted) {
            viewModel.bindCamera(lifecycleOwner)
        }
        onDispose {
            viewModel.unbindCamera()
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

    CameraScreen(
        uiState = uiState,
        previewFactory = viewModel.previewFactory,
        onRecordClick = { viewModel.onEvent(CameraEvent.OnRecordClicked) },
        onStopClick = { viewModel.onEvent(CameraEvent.OnStopRecordingClicked) },
        onRequestPermissions = { permissions.launchMultiplePermissionRequest() },
        onDismissError = { viewModel.onEvent(CameraEvent.OnErrorDismissed) },
        trackedBoxes = trackedBoxes,
    )
}
