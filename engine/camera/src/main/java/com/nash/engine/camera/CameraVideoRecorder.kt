package com.nash.engine.camera

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import com.nash.core.model.TimeProvider
import com.nash.core.model.VideoRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Owns the CameraX [Recorder]/[Recording] pair, start/stop semantics, and
 * [RecordingState]. Output destinations come exclusively from
 * [MediaStoreOutputFactory].
 *
 * All mutable session state (attached recorder/executor, active recording,
 * finalize deferred) lives in the mutex-guarded [RecordingSessionState], so
 * [startRecording], [stopRecording], [detach], and
 * [cancelActiveRecordingQuietly] cannot interleave unsafely.
 *
 * Together with the facade, this remains part of the ONLY video-writing
 * path in the project (architecture invariant #2). It records the CameraX
 * VideoCapture output that already has the anonymization effect attached.
 */
@Singleton
class CameraVideoRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val outputFactory: MediaStoreOutputFactory,
    private val errorMapper: RecordingErrorMapper,
    private val eventMapper: CameraRecordingEventMapper,
    private val timeProvider: TimeProvider,
    private val audioPermissionPolicy: AudioPermissionPolicy,
) : VideoRecorder {

    private val sessionState = RecordingSessionState<Recorder, Recording>()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: Flow<RecordingState> = _recordingState.asStateFlow()

    /** Called by [CameraSessionBinder] once the CameraX [Recorder] is created during bind. */
    suspend fun attach(recorder: Recorder, callbackExecutor: Executor) {
        sessionState.attach(recorder, callbackExecutor)
    }

    /**
     * Clears the session-scoped recorder/executor references. Called by
     * [CameraSessionReleaser] on unbind/shutdown; [attach] is called again
     * on the next bind. Recording attempts while detached fail with
     * "Camera not initialized".
     */
    suspend fun detach() {
        sessionState.detach()
    }

    /**
     * Best-effort stop used during unbind/shutdown.
     *
     * The finalize deferred is kept so any concurrent [stopRecording] caller
     * receives the camera finalize result instead of hanging or getting a
     * synthetic error.
     */
    suspend fun cancelActiveRecordingQuietly() {
        val recording = sessionState.clearActiveRecording() ?: return
        try {
            recording.stop()
            recording.close()
        } catch (_: Exception) {
            // Best-effort cleanup during unbind/shutdown; the Finalize event reports
            // real recording errors to any active stopRecording caller.
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecording(config: RecordingConfig): RecordingStartResult {
        return withContext(dispatcherProvider.io) {
            try {
                val outcome = sessionState.start { recorder, executor ->
                    _recordingState.value = RecordingState.Starting

                    val outputOptions = outputFactory.create(config.fileNamePrefix)

                    val pendingRecording = audioPermissionPolicy.withAudioIfPermitted(
                        pending = recorder.prepareRecording(context, outputOptions),
                        includeAudio = config.includeAudio,
                    ) { it.withAudioEnabled() }

                    pendingRecording.start(executor) { event -> onRecordEvent(event) }
                }

                when (outcome) {
                    is RecordingSessionState.StartOutcome.NotAttached ->
                        RecordingStartResult.Failure(
                            "Camera not initialized. Bind the camera before recording."
                        )

                    is RecordingSessionState.StartOutcome.AlreadyRecording ->
                        RecordingStartResult.Failure("Recording already in progress")

                    is RecordingSessionState.StartOutcome.Started -> RecordingStartResult.Started
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMapper.startFailure(e).also(::publishStartFailure)
            }
        }
    }

    override suspend fun stopRecording(): RecordingStopResult {
        return withContext(dispatcherProvider.io) {
            val claim = sessionState.claimStop()
                ?: return@withContext RecordingStopResult.Failure("No active recording")

            _recordingState.value = RecordingState.Stopping

            try {
                claim.recording.stop()
                // The stored finalize deferred is cleared by the Finalize event
                // via RecordingSessionState.finish(), never by this caller, so
                // a recording started while we await can never lose its own
                // fresh deferred to this stop's cleanup.
                claim.finalizeResult?.await()
                    ?: RecordingStopResult.Failure("Recording did not finalize")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMapper.stopFailure(e).also(::publishStopFailure)
            }
        }
    }

    /**
     * Handles recording events on the camera callback executor. The Finalize
     * branch briefly blocks that dedicated single thread to update the
     * session state; the session mutex is never held across an await, so the
     * wait is bounded and cannot deadlock.
     */
    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                _recordingState.value = RecordingState.Recording(
                    startedAtMillis = timeProvider.currentTimeMillis()
                )
            }

            is VideoRecordEvent.Finalize -> {
                val result = eventMapper.mapFinalize(event)
                runBlocking { sessionState.finish(result) }
                _recordingState.value = eventMapper.mapState(result)
            }
        }
    }

    private fun publishStartFailure(failure: RecordingStartResult.Failure): RecordingStartResult.Failure {
        _recordingState.value = RecordingState.Error(
            message = failure.message,
            cause = failure.cause
        )
        return failure
    }

    private fun publishStopFailure(failure: RecordingStopResult.Failure): RecordingStopResult.Failure {
        _recordingState.value = RecordingState.Error(
            message = failure.message,
            cause = failure.cause
        )
        return failure
    }
}