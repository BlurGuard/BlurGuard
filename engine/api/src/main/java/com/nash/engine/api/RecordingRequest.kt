package com.nash.engine.api

/**
 * Request to start recording a new video.
 */
data class RecordingRequest(
    val includeAudio: Boolean = true,
    val outputFileName: String? = null
)
