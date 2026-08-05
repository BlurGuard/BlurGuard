package com.nash.feature.camera.state

import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.engine.api.AnonymizationMode

/**
 * Everything [com.nash.feature.camera.CameraScreen] renders, grouped in one immutable model.
 * Must not reference [androidx.lifecycle.ViewModel] types.
 */
data class CameraScreenState(
    val uiState: CameraUiState = CameraUiState(),
    val trackedBoxes: List<TrackedBox> = emptyList(),
    val stats: PipelineStats = PipelineStats(),
    val debugStats: CameraDebugStatsUiModel = CameraDebugStatsUiModel(),
    val mode: AnonymizationMode = AnonymizationMode.BLUR,
    val keepVisible: Map<TrackId, TrackVerification> = emptyMap()
)