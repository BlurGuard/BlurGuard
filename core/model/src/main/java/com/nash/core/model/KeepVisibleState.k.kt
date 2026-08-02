package com.nash.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Verification lifecycle of one track with respect to trusted persons. */
enum class VerificationState {
    /** Never checked (or reset). Blurred. */
    UNKNOWN,

    /** User tapped it (enrollment) or a recognition pass is pending. Blurred. */
    PENDING,

    /** Verified as a trusted person. The ONLY state that unblurs. */
    TRUSTED,

    /** Checked and did not match any trusted person. Blurred; may be re-checked. */
    REJECTED
}

/**
 * Per-track verification snapshot.
 *
 * @property consecutiveMatches Successful consecutive matches so far (a track
 * needs [RecognitionConfig.consecutiveMatchesToTrust] to be promoted).
 * @property consecutiveMismatches Failed re-verifications in a row for a
 * TRUSTED track (revoked at [RecognitionConfig.mismatchesToRevoke]).
 * @property lastCheckedFrame Frame ID of the last recognition pass, for
 * scheduling periodic re-verification.
 */
data class TrackVerification(
    val state: VerificationState = VerificationState.UNKNOWN,
    val personId: PersonId? = null,
    val consecutiveMatches: Int = 0,
    val consecutiveMismatches: Int = 0,
    val lastCheckedFrame: Long = -1L
)

/**
 * Live TrackId -> verification map. Mutated only on the ml dispatcher (by the
 * core/domain orchestrator); observed by the UI for overlay states.
 *
 * Fail-closed by construction: an absent entry behaves as UNKNOWN, and only
 * TRUSTED ever sets keepVisible.
 */
class KeepVisibleState {

    private val _verifications = MutableStateFlow<Map<TrackId, TrackVerification>>(emptyMap())
    val verifications: StateFlow<Map<TrackId, TrackVerification>> = _verifications.asStateFlow()

    fun of(trackId: TrackId): TrackVerification =
        _verifications.value[trackId] ?: TrackVerification()

    fun set(trackId: TrackId, verification: TrackVerification) {
        _verifications.value = _verifications.value + (trackId to verification)
    }

    /** Drop state for tracks the tracker no longer reports (they expired). */
    fun retainTracks(liveTrackIds: Set<TrackId>) {
        val current = _verifications.value
        if (current.keys.all { it in liveTrackIds }) return
        _verifications.value = current.filterKeys { it in liveTrackIds }
    }

    /** Re-blur everything now. Wire to the "Revoke all" chip and panic delete. */
    fun clearAll() {
        _verifications.value = emptyMap()
    }

    /**
     * Decorates tracker output with keepVisible flags. This is THE single
     * gate between recognition state and the renderer — Phase 4 calls it at
     * both pipeline publish sites (update and predict paths), immediately
     * before boxes reach trackedBoxes and the RenderBoxFeed.
     *
     * Only FACE tracks in TRUSTED state are ever unblurred.
     */
    fun decorate(boxes: List<TrackedBox>): List<TrackedBox> {
        val map = _verifications.value
        if (map.isEmpty()) return boxes
        return boxes.map { tracked ->
            val trusted = tracked.clazz == DetectionClass.FACE &&
                    map[tracked.id]?.state == VerificationState.TRUSTED
            if (trusted) tracked.copy(keepVisible = true) else tracked
        }
    }
}