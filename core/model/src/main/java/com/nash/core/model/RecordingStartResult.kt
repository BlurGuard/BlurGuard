package com.nash.core.model

sealed interface RecordingStartResult {
    data object Started : RecordingStartResult
    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : RecordingStartResult
}
