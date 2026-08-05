package com.nash.feature.camera.controller

import com.nash.feature.camera.state.CameraEvent
import com.nash.feature.camera.state.CameraUiState

/**
 * Pure reducer for permission-related [com.nash.feature.camera.state.CameraEvent]s.
 * No coroutines, no Android types — trivially unit-testable.
 */
object CameraPermissionReducer {

    const val CAMERA_PERMISSION_REQUIRED_MESSAGE =
        "Camera permission is required to record video."

    fun reduce(state: CameraUiState, event: CameraEvent): CameraUiState = when (event) {
        CameraEvent.OnCameraPermissionGranted -> state.copy(
            cameraPermissionGranted = true,
            showCameraPermissionRationale = false
        )

        CameraEvent.OnCameraPermissionDenied -> state.copy(
            cameraPermissionGranted = false,
            showCameraPermissionRationale = true,
            errorMessage = CAMERA_PERMISSION_REQUIRED_MESSAGE
        )

        CameraEvent.OnAudioPermissionGranted -> state.copy(
            audioPermissionGranted = true,
            showAudioPermissionRationale = false
        )

        CameraEvent.OnAudioPermissionDenied -> state.copy(
            audioPermissionGranted = false,
            showAudioPermissionRationale = true
        )

        else -> state
    }
}