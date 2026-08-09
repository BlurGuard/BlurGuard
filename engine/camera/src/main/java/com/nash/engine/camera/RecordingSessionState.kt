package com.nash.engine.camera

import com.nash.core.model.RecordingStopResult
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutex-guarded holder for the recorder's mutable session state: the
 * attached recorder/callback executor pair, the active recording, and the
 * finalize deferred.
 *
 * Every read-modify-write happens inside a single mutex-guarded method, so
 * startRecording, stopRecording, detach, and cancelActiveRecordingQuietly
 * cannot interleave unsafely, and no split volatile/non-volatile state
 * remains. The mutex is never held across an await: [claimStop] hands the
 * finalize deferred to the caller, which awaits it outside the lock.
 *
 * Generic over the recorder/recording types so the coordination logic is
 * unit-testable on the plain JVM (the CameraX types are final and cannot be
 * instantiated off-device).
 */
internal class RecordingSessionState<RecorderT : Any, RecordingT : Any> {

    sealed interface StartOutcome<out RecordingT> {
        /** No recorder/executor is attached: the camera is not bound. */
        object NotAttached : StartOutcome<Nothing>

        /** A recording is already active. */
        object AlreadyRecording : StartOutcome<Nothing>

        /** The recording was created and registered as the active recording. */
        data class Started<RecordingT>(val recording: RecordingT) : StartOutcome<RecordingT>
    }

    /** The active recording plus the deferred to await for its finalize result. */
    data class StopClaim<RecordingT>(
        val recording: RecordingT,
        val finalizeResult: CompletableDeferred<RecordingStopResult>?,
    )

    private val mutex = Mutex()

    private var recorder: RecorderT? = null
    private var callbackExecutor: Executor? = null
    private var activeRecording: RecordingT? = null
    private var finalizeResult: CompletableDeferred<RecordingStopResult>? = null

    /** Stores the session-scoped recorder/executor pair (bind). */
    suspend fun attach(recorder: RecorderT, callbackExecutor: Executor) {
        mutex.withLock {
            this.recorder = recorder
            this.callbackExecutor = callbackExecutor
        }
    }

    /** Clears the session-scoped recorder/executor pair (unbind/shutdown). */
    suspend fun detach() {
        mutex.withLock {
            recorder = null
            callbackExecutor = null
        }
    }

    /**
     * Atomically checks the start preconditions, runs [createRecording], and
     * registers its result as the active recording with a fresh finalize
     * deferred. If [createRecording] throws, nothing is registered and the
     * previous finalize deferred is left untouched.
     */
    suspend fun start(
        createRecording: (recorder: RecorderT, callbackExecutor: Executor) -> RecordingT,
    ): StartOutcome<RecordingT> = mutex.withLock {
        val currentRecorder = recorder ?: return@withLock StartOutcome.NotAttached
        val executor = callbackExecutor ?: return@withLock StartOutcome.NotAttached
        if (activeRecording != null) {
            return@withLock StartOutcome.AlreadyRecording
        }
        val recording = createRecording(currentRecorder, executor)
        finalizeResult = CompletableDeferred()
        activeRecording = recording
        StartOutcome.Started(recording)
    }

    /**
     * Claims the active recording for stopping, or null when none is active.
     * The active recording is cleared by [finish] (normal finalize) or by
     * [clearActiveRecording] (quiet cancel), never by claiming.
     *
     * Double-stop is safe: two concurrent stop callers may both claim the
     * same recording and both call stop() on it — CameraX makes the second
     * stop a no-op, and both callers await the same finalize deferred, so
     * both observe the single finalize result.
     */
    suspend fun claimStop(): StopClaim<RecordingT>? = mutex.withLock {
        val recording = activeRecording ?: return@withLock null
        StopClaim(recording, finalizeResult)
    }

    /**
     * Records the finalize outcome: clears the active recording, completes
     * the finalize deferred for any awaiting stop caller (who holds its own
     * reference via [StopClaim]), and clears the stored deferred here so it
     * can never be confused with a later recording's fresh deferred.
     */
    suspend fun finish(result: RecordingStopResult) {
        mutex.withLock {
            activeRecording = null
            finalizeResult?.complete(result)
            finalizeResult = null
        }
    }

    /**
     * Removes and returns the active recording (best-effort cancel during
     * unbind/shutdown). Keeps the finalize deferred so a concurrent stop
     * caller still receives the camera's finalize result.
     */
    suspend fun clearActiveRecording(): RecordingT? = mutex.withLock {
        val recording = activeRecording
        activeRecording = null
        recording
    }
}