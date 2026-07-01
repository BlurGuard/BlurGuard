package com.nash.core.model

import kotlinx.coroutines.flow.Flow

/**
 * Cross-module contract for video recording.
 *
 * Implementations live in [core.camera] and are injected via Hilt. The contract
 * is intentionally free of Android lifecycle types so it can reside in the JVM
 * leaf module [core.model]. Lifecycle binding is handled separately by the
 * domain layer.
 */
interface VideoRecorder {

    /**
     * Starts a new video recording using the supplied [config].
     *
     * If a recording is already active this returns a [RecordingStartResult.Failure].
     */
    suspend fun startRecording(config: RecordingConfig): RecordingStartResult

    /**
     * Stops the active recording and finalizes it to the configured output.
     *
     * If no recording is active this returns a [RecordingStopResult.Failure].
     */
    suspend fun stopRecording(): RecordingStopResult

    /**
     * Hot observable of the current recording state. Consumers should collect this
     * on a single collector and never route frame data through it.
     */
    val recordingState: Flow<RecordingState>
}
