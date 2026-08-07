package com.nash.engine.impl

import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.FrameSource
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.PipelineStats
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VideoRecorder
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.EngineConfig
import com.nash.engine.api.EngineWarning
import com.nash.engine.api.PreviewTarget
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import com.nash.engine.api.TrustedFaceRef
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.camera.CameraSessionController
import com.nash.engine.impl.mapper.AnonymizationModeMapper
import com.nash.engine.impl.mapper.RecordingRequestMapper
import com.nash.engine.impl.mapper.RecordingStateMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Singleton
class RealBlurGuardEngine @Inject constructor(
    private val cameraSession: CameraSessionController,
    private val videoRecorder: VideoRecorder,
    private val frameSource: @JvmSuppressWildcards FrameSource<ImageProxy>,
    private val pipeline: AnonymizationPipeline<ImageProxy>,
    private val keepVisibleState: KeepVisibleStateReader,
    private val modeHolder: AnonymizationModeHolder,
    private val keepVisibleController: KeepVisibleController,
    private val recordingRequestMapper: RecordingRequestMapper,
    private val recordingStateMapper: RecordingStateMapper,
    private val anonymizationModeMapper: AnonymizationModeMapper
) : BlurGuardEngine {

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewTarget: PreviewTarget,
        config: EngineConfig
    ) {
        // Apply initial config before frames start flowing.
        modeHolder.set(anonymizationModeMapper.toCore(config.initialMode))
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
        val result = videoRecorder.startRecording(recordingRequestMapper.toCore(request))
        when (result) {
            is RecordingStartResult.Failure -> {
                emit(RecordingState.Error(result.message, result.cause))
            }
            is RecordingStartResult.Started -> {
                emitAll(videoRecorder.recordingState.map { coreState ->
                    recordingStateMapper.toApi(coreState, request)
                })
            }
        }
    }

    override suspend fun stopRecording() {
        videoRecorder.stopRecording()
    }

    override suspend fun updateAnonymizationMode(mode: AnonymizationMode) {
        modeHolder.set(anonymizationModeMapper.toCore(mode))
    }

    override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
        if (faces.isEmpty()) {
            keepVisibleController.revokeAll()
        } else {
            faces.forEach { keepVisibleController.requestKeepVisible(it.trackId) }
        }
    }

    override fun observeWarnings(): Flow<EngineWarning> = pipeline.degraded
        .filter { it }
        .map { EngineWarning.DetectionDegraded }

    override val trackedBoxes: StateFlow<List<TrackedBox>>
        get() = pipeline.trackedBoxes

    override val keepVisible: StateFlow<Map<TrackId, TrackVerification>>
        get() = keepVisibleState.verifications

    override val stats: StateFlow<PipelineStats>
        get() = pipeline.stats
}