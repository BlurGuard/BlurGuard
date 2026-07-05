package com.nash.core.model

/**
 * Configuration for starting a video recording.
 *
 * @property includeAudio Whether audio should be recorded. The caller is responsible
 *           for verifying the RECORD_AUDIO runtime permission; if unavailable this
 *           should be set to `false` so the pipeline falls back to video-only.
 * @property fileNamePrefix Prefix used when generating the MediaStore file name.
 */
data class RecordingConfig(
    val includeAudio: Boolean = true,
    val fileNamePrefix: String = "BlurGuard"
)
