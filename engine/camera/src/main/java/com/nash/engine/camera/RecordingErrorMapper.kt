package com.nash.engine.camera

import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingStopResult
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Maps recorder exceptions into stable domain failures. */
@Singleton
class RecordingErrorMapper @Inject constructor() {

    fun startFailure(error: Throwable): RecordingStartResult.Failure {
        return when (error) {
            is SecurityException -> RecordingStartResult.Failure(
                message = "Missing permission to start recording",
                cause = error
            )

            is IllegalStateException -> RecordingStartResult.Failure(
                message = error.message ?: "Recorder is not ready to start",
                cause = error
            )

            is IllegalArgumentException -> RecordingStartResult.Failure(
                message = error.message ?: "Invalid recording request",
                cause = error
            )

            is IOException -> RecordingStartResult.Failure(
                message = error.message ?: "Failed to create recording output",
                cause = error
            )

            else -> RecordingStartResult.Failure(
                message = error.message ?: "Failed to start recording",
                cause = error
            )
        }
    }

    fun stopFailure(error: Throwable): RecordingStopResult.Failure {
        return when (error) {
            is IllegalStateException -> RecordingStopResult.Failure(
                message = error.message ?: "Recorder is not ready to stop",
                cause = error
            )

            else -> RecordingStopResult.Failure(
                message = error.message ?: "Failed to stop recording",
                cause = error
            )
        }
    }
}