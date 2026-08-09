package com.nash.engine.camera

import androidx.camera.video.VideoRecordEvent
import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps CameraX recording events into stable domain results, and domain
 * results into [RecordingState] emissions.
 *
 * Extracted from [CameraVideoRecorder]'s Finalize handler so the
 * event-to-domain mapping is independently testable. The CameraX-typed
 * entry point delegates to an internal overload on primitives so the
 * mapping logic runs in plain-JVM unit tests (CameraX event classes are
 * final and cannot be instantiated off-device).
 */
@Singleton
class CameraRecordingEventMapper @Inject constructor() {

    /** Maps a CameraX [VideoRecordEvent.Finalize] into a domain stop result. */
    fun mapFinalize(event: VideoRecordEvent.Finalize): RecordingStopResult =
        mapFinalize(
            hasError = event.hasError(),
            cause = event.cause,
            outputUri = { event.outputResults.outputUri.toString() },
        )

    /**
     * Mapping logic on plain values. [outputUri] is a function so the output
     * results are only read on the success path, matching the legacy access
     * pattern.
     */
    internal fun mapFinalize(
        hasError: Boolean,
        cause: Throwable?,
        outputUri: () -> String,
    ): RecordingStopResult {
        return if (!hasError) {
            RecordingStopResult.Saved(uri = outputUri())
        } else {
            RecordingStopResult.Failure(
                message = cause?.message ?: "Recording failed",
                cause = cause
            )
        }
    }

    /** Maps a domain stop result into the [RecordingState] to publish. */
    fun mapState(result: RecordingStopResult): RecordingState {
        return when (result) {
            is RecordingStopResult.Saved -> RecordingState.Saved(result.uri)
            is RecordingStopResult.Failure -> RecordingState.Error(
                message = result.message,
                cause = result.cause
            )
        }
    }
}
