package com.nash.engine.impl.mapper

import android.net.Uri
import com.nash.core.model.TimeProvider
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import javax.inject.Inject
import com.nash.core.model.RecordingState as CoreRecordingState

class RecordingStateMapper @Inject constructor(
    private val timeProvider: TimeProvider,
) {
    fun toApi(
        coreState: CoreRecordingState,
        request: RecordingRequest,
    ): RecordingState = when (coreState) {
        is CoreRecordingState.Idle -> RecordingState.Idle
        is CoreRecordingState.Starting -> RecordingState.Starting(request)
        is CoreRecordingState.Recording -> RecordingState.Recording(
            request = request,
            durationMillis = timeProvider.currentTimeMillis() - coreState.startedAtMillis,
            sizeBytes = 0L,
        )
        is CoreRecordingState.Stopping -> RecordingState.Stopping(request)
        is CoreRecordingState.Saved -> RecordingState.Saved(Uri.parse(coreState.uri))
        is CoreRecordingState.Error -> RecordingState.Error(coreState.message, coreState.cause)
    }
}