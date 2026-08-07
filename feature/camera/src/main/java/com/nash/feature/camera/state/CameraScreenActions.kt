package com.nash.feature.camera.state

import com.nash.core.model.TrackId

/** Every event [com.nash.feature.camera.CameraScreen] can emit, grouped in one stable object. */
data class CameraScreenActions(
    val onRecordClick: () -> Unit,
    val onStopClick: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onDismissError: () -> Unit,
    val onModeClick: () -> Unit,
    val onFaceTapped: (TrackId) -> Unit,
    val onRevokeAllKeepVisible: () -> Unit,
    val onKeepVisibleMessageShown: () -> Unit,
    val onSettingsClick: () -> Unit
)
