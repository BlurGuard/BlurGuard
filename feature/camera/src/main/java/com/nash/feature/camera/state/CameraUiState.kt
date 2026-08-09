package com.nash.feature.camera.state

import androidx.annotation.StringRes
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.RecordingState

data class CameraUiState(
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val showCameraPermissionRationale: Boolean = false,
    val showAudioPermissionRationale: Boolean = false,
    val recordingState: RecordingState = RecordingState.Idle,
    val durationSeconds: Int = 0,
    val lastSavedUri: String? = null,
    /** One-shot error text that is dynamic at runtime (e.g. recording/camera failures). */
    val errorMessage: String? = null,
    /**
     * One-shot error text that is a fixed app string (permission denial,
     * engine warnings), as a string resource id — same pattern as
     * [keepVisibleMessage]. Only one of [errorMessage]/[errorMessageRes]
     * is expected to be set at a time.
     */
    @StringRes val errorMessageRes: Int? = null,
    /** One-shot keep-visible verification message, as a string resource id. */
    @StringRes val keepVisibleMessage: Int? = null,
    val anonymizationMode: AnonymizationMode = AnonymizationMode.BLUR
) {
    val isRecording: Boolean
        get() = recordingState is RecordingState.Recording

    val isStartingOrStopping: Boolean
        get() = recordingState is RecordingState.Starting || recordingState is RecordingState.Stopping
}
