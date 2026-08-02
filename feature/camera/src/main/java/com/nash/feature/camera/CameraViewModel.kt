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
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import com.nash.engine.api.TrustedFaceRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val engine: BlurGuardEngine,
    private val dispatcherProvider: DispatcherProvider
) : ViewModel() {

    private val seenIds = mutableSetOf<Long>()
    private val _idStats = MutableStateFlow(IdStats())
    val idStats: StateFlow<IdStats> = _idStats.asStateFlow()

    data class IdStats(val active: Int = 0, val totalSeen: Int = 0)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val pipelineStats: StateFlow<PipelineStats> = engine.stats
    val trackedBoxes: StateFlow<List<TrackedBox>> = engine.trackedBoxes

    /** Keep-visible verification state, exposed by the engine API. */
    val keepVisible: StateFlow<Map<TrackId, TrackVerification>> = engine.keepVisible

    private var enrollTarget: TrackId? = null
    private var enrollSeenPending = false

    init {
        viewModelScope.launch {
            engine.observeWarnings().collect { warning ->
                // Handle warnings in UI
                _uiState.update { it.copy(errorMessage = warning.toString()) }
            }
        }

        viewModelScope.launch {
            trackedBoxes.collect { boxes ->
                boxes.forEach { seenIds += it.id.value }
                _idStats.value = IdStats(active = boxes.size, totalSeen = seenIds.size)
            }
        }
    }

    fun onFaceTapped(trackId: TrackId) {
        enrollTarget = trackId
        enrollSeenPending = false
        viewModelScope.launch {
            engine.updateTrustedFaces(listOf(TrustedFaceRef(trackId)))
        }
    }

    fun onRevokeAllKeepVisible() {
        enrollTarget = null
        viewModelScope.launch {
            engine.updateTrustedFaces(emptyList())
        }
    }

    fun onKeepVisibleMessageShown() {
        _uiState.update { it.copy(keepVisibleMessage = null) }
    }

    fun bindEngine(lifecycleOwner: LifecycleOwner, previewTarget: PreviewTarget) {
        engine.bind(lifecycleOwner, previewTarget)
    }

    fun onEvent(event: CameraEvent) {
        when (event) {
            CameraEvent.OnCameraPermissionGranted -> {
                _uiState.update { it.copy(cameraPermissionGranted = true, showCameraPermissionRationale = false) }
            }
            CameraEvent.OnCameraPermissionDenied -> {
                _uiState.update {
                    it.copy(
                        cameraPermissionGranted = false,
                        showCameraPermissionRationale = true,
                        errorMessage = "Camera permission is required to record video."
                    )
                }
            }
            CameraEvent.OnAudioPermissionGranted -> {
                _uiState.update { it.copy(audioPermissionGranted = true, showAudioPermissionRationale = false) }
            }
            CameraEvent.OnAudioPermissionDenied -> {
                _uiState.update {
                    it.copy(
                        audioPermissionGranted = false,
                        showAudioPermissionRationale = true
                    )
                }
            }
            CameraEvent.OnRecordClicked -> startRecording()
            CameraEvent.OnStopRecordingClicked -> stopRecording()
            is CameraEvent.OnCameraError -> {
                _uiState.update { it.copy(errorMessage = event.message) }
            }
            CameraEvent.OnErrorDismissed -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
        }
    }

    private var recordingJob: Job? = null

    private fun startRecording() {
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            val request = RecordingRequest(includeAudio = _uiState.value.audioPermissionGranted)
            engine.startRecording(request).collect { state ->
                updateRecordingState(state)
            }
        }
    }

    private fun updateRecordingState(state: RecordingState) {
        _uiState.update { it.copy(recordingState = state) }
        when (state) {
            is RecordingState.Recording -> {
                _uiState.update { it.copy(durationSeconds = (state.durationMillis / 1000).toInt()) }
            }
            is RecordingState.Saved -> {
                _uiState.update { it.copy(lastSavedUri = state.uri.toString()) }
            }
            is RecordingState.Error -> {
                _uiState.update { it.copy(errorMessage = state.message) }
            }
            else -> {}
        }
    }

    private fun stopRecording() {
        viewModelScope.launch {
            engine.stopRecording()
        }
    }

    fun onModeClicked() {
        val currentMode = _uiState.value.anonymizationMode
        val nextMode = when (currentMode) {
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