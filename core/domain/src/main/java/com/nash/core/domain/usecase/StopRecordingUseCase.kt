package com.nash.core.domain.usecase

import com.nash.core.model.RecordingStopResult
import com.nash.core.model.VideoRecorder
import javax.inject.Inject

class StopRecordingUseCase @Inject constructor(
    private val videoRecorder: VideoRecorder
) {
    suspend operator fun invoke(): RecordingStopResult {
        return videoRecorder.stopRecording()
    }
}
