package com.nash.feature.camera.state

sealed interface CameraEvent {
    data object OnCameraPermissionGranted : CameraEvent
    data object OnCameraPermissionDenied : CameraEvent
    data object OnAudioPermissionGranted : CameraEvent
    data object OnAudioPermissionDenied : CameraEvent
    data object OnRecordClicked : CameraEvent
    data object OnStopRecordingClicked : CameraEvent
    data class OnCameraError(val message: String) : CameraEvent
    data object OnErrorDismissed : CameraEvent
}