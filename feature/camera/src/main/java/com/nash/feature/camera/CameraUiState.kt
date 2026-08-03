package com.nash.feature.camera

import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.RecordingState

/**
 * Immutable UI state for the camera recording screen.
 *
 * Contains no frame, pixel, or Surface data.
 */
data class CameraUiState(
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val showCameraPermissionRationale: Boolean = false,
    val showAudioPermissionRationale: Boolean = false,
    val recordingState: RecordingState = RecordingState.Idle,
    val durationSeconds: Int = 0,
    val lastSavedUri: String? = null,
    val errorMessage: String? = null,
    val keepVisibleMessage: String? = null,
    val anonymizationMode: AnonymizationMode = AnonymizationMode.BLUR
) {
    val isRecording: Boolean
        get() = recordingState is RecordingState.Recording

    val isStartingOrStopping: Boolean
        get() = recordingState is RecordingState.Starting || recordingState is RecordingState.Stopping
}
