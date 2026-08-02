package com.nash.engine.impl.usecase

import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.VideoRecorder
import javax.inject.Inject

class StartRecordingUseCase @Inject constructor(
    private val videoRecorder: VideoRecorder
) {
    suspend operator fun invoke(config: RecordingConfig): RecordingStartResult {
        return videoRecorder.startRecording(config)
    }
}
