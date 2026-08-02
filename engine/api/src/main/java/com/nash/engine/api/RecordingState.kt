package com.nash.engine.api

import android.net.Uri

/**
 * State of the video recording process.
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Starting(val request: RecordingRequest) : RecordingState()
    data class Recording(
        val request: RecordingRequest,
        val durationMillis: Long,
        val sizeBytes: Long
    ) : RecordingState()
    data class Stopping(val request: RecordingRequest) : RecordingState()
    data class Saved(val uri: Uri) : RecordingState()
    data class Error(val message: String, val cause: Throwable? = null) : RecordingState()
}
