package com.nash.feature.camera

import com.nash.core.model.RecordingState

/**
 * Immutable UI state for the camera recording screen.
 *
 * Contains no frame, pixel, or Surface data — only primitives and the opaque
 * [RecordingState] from the domain layer.
 */
data class CameraUiState(
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val showCameraPermissionRationale: Boolean = false,
    val showAudioPermissionRationale: Boolean = false,
    val recordingState: RecordingState = RecordingState.Idle,
    val durationSeconds: Int = 0,
    val lastSavedUri: String? = null,
    val errorMessage: String? = null
) {
    val isRecording: Boolean
        get() = recordingState is RecordingState.Recording

    val isStartingOrStopping: Boolean
        get() = recordingState is RecordingState.Starting || recordingState is RecordingState.Stopping
}
