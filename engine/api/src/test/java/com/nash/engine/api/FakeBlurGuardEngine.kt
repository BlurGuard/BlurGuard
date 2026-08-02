package com.nash.engine.api

import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for BlurGuardEngine. Exposes mutable flows so tests can
 * drive engine state, and records calls so tests can assert on them.
 */
class FakeBlurGuardEngine : BlurGuardEngine {

    val recordingStates = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val warnings = MutableSharedFlow<EngineWarning>()

    var bindCount = 0
        private set
    var lastConfig: EngineConfig? = null
        private set
    var lastRecordingRequest: RecordingRequest? = null
        private set
    var stopRecordingCount = 0
        private set
    var lastMode: AnonymizationMode? = null
        private set
    var lastTrustedFaces: List<TrustedFaceRef>? = null
        private set

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewTarget: PreviewTarget,
        config: EngineConfig
    ) {
        bindCount++
        lastConfig = config
    }

    override fun startRecording(request: RecordingRequest): Flow<RecordingState> {
        lastRecordingRequest = request
        return recordingStates
    }

    override suspend fun stopRecording() {
        stopRecordingCount++
    }

    override suspend fun updateAnonymizationMode(mode: AnonymizationMode) {
        lastMode = mode
    }

    override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
        lastTrustedFaces = faces
    }

    override fun observeWarnings(): Flow<EngineWarning> = warnings.asSharedFlow()

    override val trackedBoxes: MutableStateFlow<List<TrackedBox>> =
        MutableStateFlow(emptyList())

    override val keepVisible: MutableStateFlow<Map<TrackId, TrackVerification>> =
        MutableStateFlow(emptyMap())

    override val stats: MutableStateFlow<PipelineStats> =
        MutableStateFlow(PipelineStats())
}