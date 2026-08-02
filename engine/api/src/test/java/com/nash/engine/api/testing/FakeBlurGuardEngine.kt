package com.nash.engine.api.testing

import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.EngineConfig
import com.nash.engine.api.EngineWarning
import com.nash.engine.api.PreviewTarget
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import com.nash.engine.api.TrustedFaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeBlurGuardEngine : BlurGuardEngine {
    var boundLifecycle: LifecycleOwner? = null
    var boundTarget: PreviewTarget? = null
    var currentConfig: EngineConfig? = null

    private val _recordingState = MutableSharedFlow<RecordingState>()
    private val _warnings = MutableSharedFlow<EngineWarning>()
    private val _trackedBoxes = MutableStateFlow<List<TrackedBox>>(emptyList())
    private val _stats = MutableStateFlow(PipelineStats())

    override fun bind(lifecycleOwner: LifecycleOwner, previewTarget: PreviewTarget, config: EngineConfig) {
        boundLifecycle = lifecycleOwner
        boundTarget = previewTarget
        currentConfig = config
    }

    override fun startRecording(request: RecordingRequest): Flow<RecordingState> = _recordingState.asSharedFlow()

    override suspend fun stopRecording() {
        _recordingState.emit(RecordingState.Idle)
    }

    override suspend fun updateAnonymizationMode(mode: AnonymizationMode) {
        currentConfig = currentConfig?.copy(initialMode = mode)
    }

    override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
        currentConfig = currentConfig?.copy(initialTrustedFaces = faces)
    }

    override fun observeWarnings(): Flow<EngineWarning> = _warnings.asSharedFlow()

    override val trackedBoxes: StateFlow<List<TrackedBox>> = _trackedBoxes.asStateFlow()
    override val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    suspend fun emitRecordingState(state: RecordingState) = _recordingState.emit(state)
    suspend fun emitWarning(warning: EngineWarning) = _warnings.emit(warning)
    fun setTrackedBoxes(boxes: List<TrackedBox>) { _trackedBoxes.value = boxes }
    fun setStats(stats: PipelineStats) { _stats.value = stats }
}
