package com.nash.engine.impl

import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.AnonymizationModeEnum
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.FrameSource
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.PipelineStats
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VideoRecorder
import com.nash.engine.api.*
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.camera.CameraSessionController
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealBlurGuardEngine @Inject constructor(
    private val cameraSession: CameraSessionController,
    private val videoRecorder: VideoRecorder,
    private val frameSource: @JvmSuppressWildcards FrameSource<ImageProxy>,
    private val pipeline: DefaultAnonymizationPipeline<ImageProxy>,
    private val keepVisibleState: KeepVisibleStateReader,
    private val modeHolder: AnonymizationModeHolder,
    private val keepVisibleController: KeepVisibleController
) : BlurGuardEngine {

    /**
     * Direct emit channel for warnings that don't originate from a pipeline
     * flow (e.g. storage, camera timeouts). Merged into [observeWarnings];
     * future emit sites land here.
     */
    private val _warnings = MutableSharedFlow<EngineWarning>()

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewTarget: PreviewTarget,
        config: EngineConfig
    ) {
        // Apply initial config before frames start flowing.
        modeHolder.set(config.initialMode.toCore())
        config.initialTrustedFaces.forEach { keepVisibleController.requestKeepVisible(it.trackId) }

        // Every session starts from clean tracking/stats/keep-visible state.
        // (This reset was previously owned by the deleted DefaultAnonymizationEngine.)
        pipeline.reset()
        // Feed analysis frames into the detection/tracking pipeline.
        frameSource.setFrameConsumer(pipeline)
        // Connect the feature's preview view, then bind the camera.
        cameraSession.attachPreviewView(previewTarget.view)
        cameraSession.bind(lifecycleOwner)
    }

    override fun startRecording(request: RecordingRequest): Flow<RecordingState> = flow {
        val result = videoRecorder.startRecording(
            RecordingConfig(
                includeAudio = request.includeAudio,
                fileNamePrefix = request.outputFileName ?: "BlurGuard"
            )
        )
        when (result) {
            is RecordingStartResult.Failure -> {
                emit(RecordingState.Error(result.message, result.cause))
            }
            is RecordingStartResult.Started -> {
                emitAll(videoRecorder.recordingState.map { coreState ->
                    when (coreState) {
                        is com.nash.core.model.RecordingState.Idle -> RecordingState.Idle
                        is com.nash.core.model.RecordingState.Starting -> RecordingState.Starting(request)
                        is com.nash.core.model.RecordingState.Recording -> RecordingState.Recording(
                            request = request,
                            durationMillis = System.currentTimeMillis() - coreState.startedAtMillis,
                            sizeBytes = 0L // Core doesn't provide size yet
                        )
                        is com.nash.core.model.RecordingState.Stopping -> RecordingState.Stopping(request)
                        is com.nash.core.model.RecordingState.Saved ->
                            RecordingState.Saved(android.net.Uri.parse(coreState.uri))
                        is com.nash.core.model.RecordingState.Error ->
                            RecordingState.Error(coreState.message, coreState.cause)
                    }
                })
            }
        }
    }

    override suspend fun stopRecording() {
        videoRecorder.stopRecording()
    }

    override suspend fun updateAnonymizationMode(mode: AnonymizationMode) {
        modeHolder.set(mode.toCore())
    }

    override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
        if (faces.isEmpty()) {
            keepVisibleController.revokeAll()
        } else {
            faces.forEach { keepVisibleController.requestKeepVisible(it.trackId) }
        }
    }

    override fun observeWarnings(): Flow<EngineWarning> = merge(
        _warnings.asSharedFlow(),
        pipeline.degraded
            .filter { it }
            .map { EngineWarning.DetectionDegraded },
    )

    override val trackedBoxes: StateFlow<List<TrackedBox>>
        get() = pipeline.trackedBoxes

    override val keepVisible: StateFlow<Map<TrackId, TrackVerification>>
        get() = keepVisibleState.verifications

    override val stats: StateFlow<PipelineStats>
        get() = pipeline.stats
}

/** Maps the public engine-api mode to the core renderer mode. */
private fun AnonymizationMode.toCore(): AnonymizationModeEnum = when (this) {
    AnonymizationMode.BOUNDING -> AnonymizationModeEnum.BOUNDING
    AnonymizationMode.BLUR -> AnonymizationModeEnum.BLUR
    AnonymizationMode.PIXELATE -> AnonymizationModeEnum.PIXELATE
    AnonymizationMode.BLACK_BOX -> AnonymizationModeEnum.BLACKBOX
}