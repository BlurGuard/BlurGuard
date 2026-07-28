package com.nash.core.domain.usecase

import com.nash.core.domain.keepvisible.KeepVisibleController
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** User tapped a face box: keep this person visible. */
class RequestKeepVisibleUseCase @Inject constructor(
    private val controller: KeepVisibleController
) {
    operator fun invoke(trackId: TrackId) = controller.requestKeepVisible(trackId)
}

/** Re-blur everyone and forget all trusted persons (also: panic delete). */
class RevokeAllKeepVisibleUseCase @Inject constructor(
    private val controller: KeepVisibleController
) {
    operator fun invoke() = controller.revokeAll()
}

/** Observes per-track verification for overlay states (pending/trusted/rejected). */
class ObserveKeepVisibleStateUseCase @Inject constructor(
    private val state: KeepVisibleState
) {
    operator fun invoke(): StateFlow<Map<TrackId, TrackVerification>> = state.verifications
}