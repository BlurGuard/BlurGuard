package com.nash.feature.camera.controller

import com.nash.feature.camera.R
import com.nash.feature.camera.state.CameraEvent
import com.nash.feature.camera.state.CameraUiState

/**
 * Pure reducer for permission-related [com.nash.feature.camera.state.CameraEvent]s.
 * No coroutines, no Android types — trivially unit-testable.
 *
 * The denied-camera error is a fixed app string, so it travels as a
 * @StringRes id on [CameraUiState.errorMessageRes] (localizable), not as a
 * hardcoded English literal.
 */
object CameraPermissionReducer {

    fun reduce(state: CameraUiState, event: CameraEvent): CameraUiState = when (event) {
        CameraEvent.OnCameraPermissionGranted -> state.copy(
            cameraPermissionGranted = true,
            showCameraPermissionRationale = false
        )

        CameraEvent.OnCameraPermissionDenied -> state.copy(
            cameraPermissionGranted = false,
            showCameraPermissionRationale = true,
            errorMessageRes = R.string.camera_permission_required
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
