package com.nash.engine.recognition

import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-only, in-memory [KeepVisibleStateStore]: the live
 * TrackId -> verification map.
 *
 * Mutated only on the ml dispatcher, by [KeepVisibleOrchestrator]; observed
 * from any thread through [verifications]. Every mutation replaces the map
 * instance, so observers always see an immutable snapshot and never a map
 * being edited underneath them.
 *
 * Lives here rather than in core/model because it is the mutable half of the
 * trust decision (review fix 16); core/model keeps the contract and the
 * immutable snapshot types.
 */
class SessionKeepVisibleStateStore : KeepVisibleStateStore {

    private val _verifications = MutableStateFlow<Map<TrackId, TrackVerification>>(emptyMap())
    override val verifications: StateFlow<Map<TrackId, TrackVerification>> =
        _verifications.asStateFlow()

    /** Absent means never checked, which is blurred — fail-closed default. */
    override fun of(trackId: TrackId): TrackVerification =
        _verifications.value[trackId] ?: TrackVerification()

    override fun set(trackId: TrackId, verification: TrackVerification) {
        _verifications.value = _verifications.value + (trackId to verification)
    }

    override fun retainTracks(liveTrackIds: Set<TrackId>) {
        val current = _verifications.value
        if (current.keys.all { it in liveTrackIds }) return
        _verifications.value = current.filterKeys { it in liveTrackIds }
    }

    override fun clearAll() {
        _verifications.value = emptyMap()
    }
}