package com.nash.engine.impl.usecase

import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.engine.api.keepvisible.KeepVisibleController
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

/**
 * Observes per-track verification for overlay states (pending/trusted/rejected).
 * Read-only by type: the UI cannot mutate trust.
 */
class ObserveKeepVisibleStateUseCase @Inject constructor(
    private val state: KeepVisibleStateReader
) {
    operator fun invoke(): StateFlow<Map<TrackId, TrackVerification>> = state.verifications
}