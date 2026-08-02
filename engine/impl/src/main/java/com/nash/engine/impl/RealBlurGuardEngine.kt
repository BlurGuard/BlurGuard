package com.nash.engine.impl

import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import com.nash.engine.api.*
import com.nash.engine.camera.CameraXCameraController
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealBlurGuardEngine @Inject constructor(
    private val controller: CameraXCameraController,
    private val pipeline: DefaultAnonymizationPipeline<ImageProxy>
) : BlurGuardEngine {

    private val _warnings = MutableSharedFlow<EngineWarning>()

    override fun bind(lifecycleOwner: LifecycleOwner, previewTarget: PreviewTarget, config: EngineConfig) {
        controller.bind(lifecycleOwner)
    }

    override fun startRecording(request: RecordingRequest): Flow<RecordingState> {
        return controller.recordingState.map { coreState ->
            when (coreState) {
                is com.nash.core.model.RecordingState.Idle -> RecordingState.Idle
                is com.nash.core.model.RecordingState.Starting -> RecordingState.Starting(request)
                is com.nash.core.model.RecordingState.Recording -> RecordingState.Recording(
                    request = request,
                    durationMillis = System.currentTimeMillis() - coreState.startedAtMillis,
                    sizeBytes = 0L // Core doesn't provide size yet
                )
                is com.nash.core.model.RecordingState.Stopping -> RecordingState.Stopping(request)
                is com.nash.core.model.RecordingState.Saved -> RecordingState.Saved(android.net.Uri.parse(coreState.uri))
                is com.nash.core.model.RecordingState.Error -> RecordingState.Error(coreState.message, coreState.cause)
            }
        }
    }

    override suspend fun stopRecording() {
        controller.stopRecording()
    }

    override suspend fun updateAnonymizationMode(mode: AnonymizationMode) {
        // Implementation note: This will be connected to AnonymizationModeHolder
    }

    override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
        // Implementation note: This will be connected to KeepVisibleOrchestrator
    }

    override fun observeWarnings(): Flow<EngineWarning> = _warnings.asSharedFlow()

    override val trackedBoxes: StateFlow<List<TrackedBox>>
        get() = pipeline.trackedBoxes

    override val stats: StateFlow<PipelineStats>
        get() = pipeline.stats
}
