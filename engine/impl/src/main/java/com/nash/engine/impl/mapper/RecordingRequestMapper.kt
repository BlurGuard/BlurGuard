package com.nash.engine.impl.mapper

import com.nash.core.model.RecordingConfig
import com.nash.engine.api.RecordingRequest
import javax.inject.Inject

class RecordingRequestMapper @Inject constructor() {
    fun toCore(request: RecordingRequest): RecordingConfig = RecordingConfig(
        includeAudio = request.includeAudio,
        fileNamePrefix = request.outputFileName ?: DEFAULT_FILE_NAME_PREFIX,
    )

    private companion object {
        const val DEFAULT_FILE_NAME_PREFIX = "BlurGuard"
    }
}
