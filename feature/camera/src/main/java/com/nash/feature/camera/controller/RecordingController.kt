package com.nash.feature.camera.controller

import com.nash.core.common.DispatcherProvider
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the recording workflow: start/stop calls against the engine and
 * mapping engine [com.nash.engine.api.RecordingState] emissions into UI-friendly fields.
 *
 * Plain class — no Compose or Android View types.
 */
class RecordingController(
    private val engine: BlurGuardEngine,
    private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider
) {

    data class State(
        val recordingState: RecordingState = RecordingState.Idle,
        val durationSeconds: Int = 0,
        val lastSavedUri: String? = null,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recordingJob: Job? = null

    fun startRecording(includeAudio: Boolean) {
        recordingJob?.cancel()
        recordingJob = scope.launch(dispatcherProvider.default) {
            val request = RecordingRequest(includeAudio = includeAudio)
            engine.startRecording(request).collect { recordingState ->
                reduce(recordingState)
            }
        }
    }

    fun stopRecording() {
        scope.launch(dispatcherProvider.default) {
            engine.stopRecording()
        }
    }

    /** Clears a previously emitted recording error (after the UI showed it). */
    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun reduce(recordingState: RecordingState) {
        _state.update { current ->
            when (recordingState) {
                is RecordingState.Recording -> current.copy(
                    recordingState = recordingState,
                    durationSeconds = (recordingState.durationMillis / 1000).toInt()
                )

                is RecordingState.Saved -> current.copy(
                    recordingState = recordingState,
                    lastSavedUri = recordingState.uri.toString()
                )

                is RecordingState.Error -> current.copy(
                    recordingState = recordingState,
                    errorMessage = recordingState.message
                )

                else -> current.copy(recordingState = recordingState)
            }
        }
    }
}
