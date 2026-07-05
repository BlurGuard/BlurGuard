package com.nash.core.domain.usecase

import com.nash.core.model.RecordingState
import com.nash.core.model.VideoRecorder
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class ObserveRecordingStateUseCase @Inject constructor(
    private val videoRecorder: VideoRecorder
) {
    operator fun invoke(): Flow<RecordingState> = videoRecorder.recordingState
}
