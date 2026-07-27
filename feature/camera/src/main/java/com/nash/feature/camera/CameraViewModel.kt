package com.nash.feature.camera

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nash.core.common.DispatcherProvider
import com.nash.core.domain.usecase.BindCameraUseCase
import com.nash.core.domain.usecase.GetCameraPreviewFactoryUseCase
import com.nash.core.domain.usecase.ObserveAnonymizationModeUseCase
import com.nash.core.domain.usecase.ObservePipelineStatsUseCase
import com.nash.core.domain.usecase.ObserveRecordingStateUseCase
import com.nash.core.domain.usecase.ObserveTrackedBoxesUseCase
import com.nash.core.domain.usecase.SetAnonymizationModeUseCase
import com.nash.core.domain.usecase.StartAnonymizationUseCase
import com.nash.core.domain.usecase.StartRecordingUseCase
import com.nash.core.domain.usecase.StopAnonymizationUseCase
import com.nash.core.domain.usecase.StopRecordingUseCase
import com.nash.core.domain.usecase.UnbindCameraUseCase
import com.nash.core.model.AnonymizationModeEnum
import com.nash.core.model.PipelineStats
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import com.nash.core.model.TrackedBox
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val bindCameraUseCase: BindCameraUseCase,
    private val unbindCameraUseCase: UnbindCameraUseCase,
    private val getCameraPreviewFactoryUseCase: GetCameraPreviewFactoryUseCase,
    private val startRecordingUseCase: StartRecordingUseCase,
    private val stopRecordingUseCase: StopRecordingUseCase,
    private val observeRecordingStateUseCase: ObserveRecordingStateUseCase,
    private val startAnonymizationUseCase: StartAnonymizationUseCase,
    private val stopAnonymizationUseCase: StopAnonymizationUseCase,
    private val observeTrackedBoxesUseCase: ObserveTrackedBoxesUseCase,
    private val dispatcherProvider: DispatcherProvider,
    private val observePipelineStatsUseCase: ObservePipelineStatsUseCase,
    private val observeAnonymizationModeUseCase: ObserveAnonymizationModeUseCase,
    private val setAnonymizationModeUseCase: SetAnonymizationModeUseCase,

    ) : ViewModel() {
    val anonymizationMode: StateFlow<AnonymizationModeEnum> = observeAnonymizationModeUseCase()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    val pipelineStats: StateFlow<PipelineStats> = observePipelineStatsUseCase()
    /**
     * Latest tracked boxes from the detection & tracking pipeline, normalized
     * to the upright analysis frame. Metadata only — frames never reach the
     * ViewModel (architecture invariant).
     */
    val trackedBoxes: StateFlow<List<TrackedBox>> = observeTrackedBoxesUseCase()

    val previewFactory = getCameraPreviewFactoryUseCase()

    private var durationJob: Job? = null

    init {
        viewModelScope.launch {
            observeRecordingStateUseCase().collect { state ->
                _uiState.update { it.copy(recordingState = state) }
                when (state) {
                    is RecordingState.Recording -> startDurationTimer(state.startedAtMillis)
                    is RecordingState.Saved -> {
                        stopDurationTimer()
                        _uiState.update { it.copy(lastSavedUri = state.uri) }
                    }

                    is RecordingState.Error -> {
                        stopDurationTimer(resetDuration = true)
                        _uiState.update { it.copy(errorMessage = state.message) }
                    }

                    else -> stopDurationTimer(resetDuration = true)
                }
            }
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        bindCameraUseCase(lifecycleOwner)
        // Frames only flow while the camera is bound, so the pipeline's
        // lifecycle is tied to the camera's.
        startAnonymizationUseCase()
    }

    fun unbindCamera() {
        stopAnonymizationUseCase()
        unbindCameraUseCase()
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

    private fun startRecording() {
        viewModelScope.launch {
            val config = RecordingConfig(
                includeAudio = _uiState.value.audioPermissionGranted
            )
            val result = withContext(dispatcherProvider.io) {
                startRecordingUseCase(config)
            }
            if (result is RecordingStartResult.Failure) {
                _uiState.update { it.copy(errorMessage = result.message) }
            }
        }
    }

    private fun stopRecording() {
        viewModelScope.launch {
            val result = withContext(dispatcherProvider.io) {
                stopRecordingUseCase()
            }
            if (result is RecordingStopResult.Failure) {
                _uiState.update { it.copy(errorMessage = result.message) }
            }
        }
    }

    private fun startDurationTimer(startedAtMillis: Long) {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAtMillis
                _uiState.update { it.copy(durationSeconds = (elapsed / 1000).toInt()) }
                kotlinx.coroutines.delay(1000.milliseconds)
            }
        }
    }

    private fun stopDurationTimer(resetDuration: Boolean = false) {
        durationJob?.cancel()
        durationJob = null
        if (resetDuration) {
            _uiState.update { it.copy(durationSeconds = 0) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDurationTimer()
        unbindCamera()
    }



    fun onModeClicked() {
        val entries = AnonymizationModeEnum.entries
        val next = entries[(entries.indexOf(anonymizationMode.value) + 1) % entries.size]
        setAnonymizationModeUseCase(next)
    }
}