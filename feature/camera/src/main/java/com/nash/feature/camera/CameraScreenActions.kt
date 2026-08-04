package com.nash.feature.camera

import com.nash.core.model.TrackId

/** Every event [CameraScreen] can emit, grouped in one stable object. */
data class CameraScreenActions(
    val onRecordClick: () -> Unit,
    val onStopClick: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onDismissError: () -> Unit,
    val onModeClick: () -> Unit,
    val onFaceTapped: (TrackId) -> Unit,
    val onRevokeAllKeepVisible: () -> Unit,
    val onKeepVisibleMessageShown: () -> Unit
)