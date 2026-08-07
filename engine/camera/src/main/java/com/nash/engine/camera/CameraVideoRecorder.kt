package com.nash.engine.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import com.nash.core.model.VideoRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Owns the CameraX [Recorder]/[Recording] pair, start/stop semantics, and
 * [RecordingState]. Output destinations come exclusively from
 * [MediaStoreOutputFactory].
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
) : VideoRecorder {

    private var recorder: Recorder? = null
    private var callbackExecutor: Executor? = null

    @Volatile
    private var activeRecording: Recording? = null

    private var finalizeResult: CompletableDeferred<RecordingStopResult>? = null

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: Flow<RecordingState> = _recordingState.asStateFlow()

    /** Called by [CameraXSessionFacade] once the CameraX [Recorder] is created during bind. */
    fun attach(recorder: Recorder, callbackExecutor: Executor) {
        this.recorder = recorder
        this.callbackExecutor = callbackExecutor
    }

    /**
     * Clears the session-scoped recorder/executor references. Called by the
     * facade on unbind/shutdown; [attach] is called again on the next bind.
     * Recording attempts while detached fail with "Camera not initialized".
     */
    fun detach() {
        recorder = null
        callbackExecutor = null
    }

    /**
     * Best-effort stop used during unbind/shutdown.
     *
     * Keep [finalizeResult] so any concurrent [stopRecording] caller receives
     * the camera finalize result instead of hanging or getting a synthetic error.
     */
    fun cancelActiveRecordingQuietly() {
        try {
            activeRecording?.stop()
            activeRecording?.close()
        } catch (_: Exception) {
            // Best-effort cleanup during unbind/shutdown; the Finalize event reports
            // real recording errors to any active stopRecording caller.
        } finally {
            activeRecording = null
        }
    }

    /** Surfaces a camera bind failure through the recording state stream. */
    fun onCameraBindError(cause: Exception) {
        _recordingState.value = RecordingState.Error(
            message = cause.message ?: "Failed to bind camera",
            cause = cause
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecording(config: RecordingConfig): RecordingStartResult {
        return withContext(dispatcherProvider.io) {
            val currentRecorder = recorder
            val executor = callbackExecutor
            if (currentRecorder == null || executor == null) {
                return@withContext RecordingStartResult.Failure(
                    "Camera not initialized. Bind the camera before recording."
                )
            }

            if (activeRecording != null) {
                return@withContext RecordingStartResult.Failure("Recording already in progress")
            }

            _recordingState.value = RecordingState.Starting

            try {
                val outputOptions = outputFactory.create(config.fileNamePrefix)

                var pendingRecording = currentRecorder.prepareRecording(context, outputOptions)

                if (config.includeAudio &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRecording = try {
                        pendingRecording.withAudioEnabled()
                    } catch (_: SecurityException) {
                        // Audio permission was revoked after the check; record video-only.
                        pendingRecording
                    }
                }

                finalizeResult = CompletableDeferred()

                activeRecording = pendingRecording.start(executor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            _recordingState.value = RecordingState.Recording(
                                startedAtMillis = System.currentTimeMillis()
                            )
                        }

                        is VideoRecordEvent.Finalize -> {
                            activeRecording = null
                            val result = if (!event.hasError()) {
                                RecordingStopResult.Saved(
                                    uri = event.outputResults.outputUri.toString()
                                )
                            } else {
                                RecordingStopResult.Failure(
                                    message = event.cause?.message ?: "Recording failed",
                                    cause = event.cause
                                )
                            }
                            finalizeResult?.complete(result)
                            _recordingState.value = when (result) {
                                is RecordingStopResult.Saved -> RecordingState.Saved(result.uri)
                                is RecordingStopResult.Failure -> RecordingState.Error(
                                    message = result.message,
                                    cause = result.cause
                                )
                            }
                        }
                    }
                }

                RecordingStartResult.Started
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMapper.startFailure(e).also(::publishStartFailure)
            }
        }
    }

    override suspend fun stopRecording(): RecordingStopResult {
        return withContext(dispatcherProvider.io) {
            val recording = activeRecording
                ?: return@withContext RecordingStopResult.Failure("No active recording")

            _recordingState.value = RecordingState.Stopping

            try {
                recording.stop()
                val result = finalizeResult?.await()
                    ?: RecordingStopResult.Failure("Recording did not finalize")
                finalizeResult = null
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMapper.stopFailure(e).also(::publishStopFailure)
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
