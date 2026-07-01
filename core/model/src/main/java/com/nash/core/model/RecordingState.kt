package com.nash.core.model

/**
 * Observable recording state emitted by [VideoRecorder].
 *
 * This is a UI-state friendly model; it intentionally contains no frame or
 * pixel data and only primitive identifiers (e.g. [Saved.uri] is a String).
 */
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data class Recording(val startedAtMillis: Long) : RecordingState
    data object Stopping : RecordingState
    data class Saved(val uri: String) : RecordingState
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : RecordingState
}
