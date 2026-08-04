package com.nash.feature.camera

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.PreviewTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin coordinator for the camera screen: combines collaborator flows into
 * [CameraUiState] and routes events. All workflow logic lives in
 * [CameraPermissionReducer], [RecordingController], [KeepVisibleUiController],
 * and [DebugStatsTracker].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val engine: BlurGuardEngine,
    dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val recordingController =
        RecordingController(engine, viewModelScope, dispatcherProvider)
    private val keepVisibleController =
        KeepVisibleUiController(engine, viewModelScope, dispatcherProvider)
    private val debugStatsTracker = DebugStatsTracker()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val pipelineStats: StateFlow<PipelineStats> = engine.stats
    val trackedBoxes: StateFlow<List<TrackedBox>> = engine.trackedBoxes

    /** Keep-visible verification state, exposed by the engine API. */
    val keepVisible: StateFlow<Map<TrackId, TrackVerification>> = engine.keepVisible

    val idStats: StateFlow<CameraDebugStatsUiModel> =
        engine.trackedBoxes
            .map(debugStatsTracker::onTrackedBoxes)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CameraDebugStatsUiModel())

    init {
        observeWarnings()
        observeRecording()
        observeKeepVisibleMessages()
    }

    private fun observeWarnings() {
        viewModelScope.launch {
            engine.observeWarnings().collect { warning ->
                _uiState.update { it.copy(errorMessage = warning.toString()) }
            }
        }
    }

    private fun observeRecording() {
        viewModelScope.launch {
            recordingController.state.collect { recording ->
                _uiState.update {
                    it.copy(
                        recordingState = recording.recordingState,
                        durationSeconds = recording.durationSeconds,
                        lastSavedUri = recording.lastSavedUri ?: it.lastSavedUri,
                        errorMessage = recording.errorMessage ?: it.errorMessage
                    )
                }
            }
        }
    }

    private fun observeKeepVisibleMessages() {
        viewModelScope.launch {
            keepVisibleController.message.collect { messageRes ->
                _uiState.update { it.copy(keepVisibleMessage = messageRes) }
            }
        }
    }

    fun bindEngine(lifecycleOwner: LifecycleOwner, previewTarget: PreviewTarget) {
        engine.bind(lifecycleOwner, previewTarget)
    }

    fun onEvent(event: CameraEvent) {
        when (event) {
            CameraEvent.OnCameraPermissionGranted,
            CameraEvent.OnCameraPermissionDenied,
            CameraEvent.OnAudioPermissionGranted,
            CameraEvent.OnAudioPermissionDenied ->
                _uiState.update { CameraPermissionReducer.reduce(it, event) }

            CameraEvent.OnRecordClicked ->
                recordingController.startRecording(
                    includeAudio = _uiState.value.audioPermissionGranted
                )

            CameraEvent.OnStopRecordingClicked -> recordingController.stopRecording()

            is CameraEvent.OnCameraError ->
                _uiState.update { it.copy(errorMessage = event.message) }

            CameraEvent.OnErrorDismissed -> {
                recordingController.consumeError()
                _uiState.update { it.copy(errorMessage = null) }
            }
        }
    }

    fun onFaceTapped(trackId: TrackId) = keepVisibleController.onFaceTapped(trackId)

    fun onRevokeAllKeepVisible() = keepVisibleController.onRevokeAll()

    fun onKeepVisibleMessageShown() {
        keepVisibleController.onMessageShown()
        _uiState.update { it.copy(keepVisibleMessage = null) }
    }

    fun onModeClicked() {
        val nextMode = when (_uiState.value.anonymizationMode) {
            AnonymizationMode.BLUR -> AnonymizationMode.PIXELATE
            AnonymizationMode.PIXELATE -> AnonymizationMode.BLACK_BOX
            AnonymizationMode.BLACK_BOX -> AnonymizationMode.BOUNDING
            AnonymizationMode.BOUNDING -> AnonymizationMode.BLUR
        }
        _uiState.update { it.copy(anonymizationMode = nextMode) }
        viewModelScope.launch {
            engine.updateAnonymizationMode(nextMode)
        }
    }
}