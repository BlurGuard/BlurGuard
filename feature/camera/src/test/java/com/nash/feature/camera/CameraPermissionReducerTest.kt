package com.nash.feature.camera

import com.nash.feature.camera.controller.CameraPermissionReducer
import com.nash.feature.camera.state.CameraEvent
import com.nash.feature.camera.state.CameraUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionReducerTest {

    @Test
    fun `camera granted sets flag and clears rationale`() {
        val result = CameraPermissionReducer.reduce(
            CameraUiState(showCameraPermissionRationale = true),
            CameraEvent.OnCameraPermissionGranted
        )
        assertTrue(result.cameraPermissionGranted)
        assertFalse(result.showCameraPermissionRationale)
    }

    @Test
    fun `camera denied clears flag, shows rationale, sets error resource`() {
        val result = CameraPermissionReducer.reduce(
            CameraUiState(cameraPermissionGranted = true),
            CameraEvent.OnCameraPermissionDenied
        )
        assertFalse(result.cameraPermissionGranted)
        assertTrue(result.showCameraPermissionRationale)
        // Fixed app strings travel as @StringRes ids, not hardcoded literals.
        assertNull(result.errorMessage)
        assertEquals(R.string.camera_permission_required, result.errorMessageRes)
    }

    @Test
    fun `audio granted sets flag and clears rationale`() {
        val result = CameraPermissionReducer.reduce(
            CameraUiState(showAudioPermissionRationale = true),
            CameraEvent.OnAudioPermissionGranted
        )
        assertTrue(result.audioPermissionGranted)
        assertFalse(result.showAudioPermissionRationale)
    }

    @Test
    fun `audio denied clears flag and shows rationale without error`() {
        val result = CameraPermissionReducer.reduce(
            CameraUiState(audioPermissionGranted = true),
            CameraEvent.OnAudioPermissionDenied
        )
        assertFalse(result.audioPermissionGranted)
        assertTrue(result.showAudioPermissionRationale)
        assertNull(result.errorMessage)
        assertNull(result.errorMessageRes)
    }

    @Test
    fun `non-permission events return state unchanged`() {
        val state = CameraUiState(cameraPermissionGranted = true, audioPermissionGranted = true)
        assertEquals(state, CameraPermissionReducer.reduce(state, CameraEvent.OnRecordClicked))
        assertEquals(state, CameraPermissionReducer.reduce(state, CameraEvent.OnStopRecordingClicked))
        assertEquals(state, CameraPermissionReducer.reduce(state, CameraEvent.OnErrorDismissed))
    }
}