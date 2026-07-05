package com.nash.core.model

sealed interface RecordingStopResult {
    data class Saved(val uri: String) : RecordingStopResult
    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : RecordingStopResult
}
