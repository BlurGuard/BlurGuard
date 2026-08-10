package com.nash.core.model

import kotlinx.coroutines.flow.StateFlow

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
 * Read side of the live TrackId -> verification map: observed by the UI for
 * overlay states and read by the pipeline immediately before boxes reach the
 * renderer.
 *
 * Fail-closed by construction: an absent entry behaves as UNKNOWN.
 */
interface KeepVisibleStateReader {

    /** Current verification map; new instance on every change. */
    val verifications: StateFlow<Map<TrackId, TrackVerification>>

    /** Verification for [trackId], or a default UNKNOWN snapshot if absent. */
    fun of(trackId: TrackId): TrackVerification
}

/**
 * Write side, used ONLY by the keep-visible orchestrator on the ml dispatcher.
 * Split from [KeepVisibleStateReader] so that the render gate and the UI hold a
 * handle that physically cannot promote a track to TRUSTED.
 *
 * The implementation lives in engine/recognition with the trust policy that
 * drives it; core/model owns the contract and the immutable snapshots only.
 */
interface KeepVisibleStateStore : KeepVisibleStateReader {

    fun set(trackId: TrackId, verification: TrackVerification)

    /** Drop state for tracks the tracker no longer reports (they expired). */
    fun retainTracks(liveTrackIds: Set<TrackId>)

    /** Re-blur everything now. Wire to the "Revoke all" chip and panic delete. */
    fun clearAll()
}

/**
 * Decorates tracker output with keepVisible flags. This is THE single gate
 * between recognition state and the renderer: it is called at both pipeline
 * publish sites (update and predict paths), immediately before boxes reach
 * trackedBoxes and the RenderBoxFeed.
 *
 * Only FACE tracks in TRUSTED state are ever unblurred. Deliberately a pure
 * extension function over an immutable snapshot rather than a member of
 * [KeepVisibleStateReader]: extensions cannot be overridden, so no
 * implementation can weaken the gate, and the rule is unit-testable with no
 * store, no coroutines and no Android.
 */
fun Map<TrackId, TrackVerification>.decorate(boxes: List<TrackedBox>): List<TrackedBox> {
    if (isEmpty()) return boxes
    return boxes.map { tracked ->
        val trusted = tracked.clazz == DetectionClass.FACE &&
                this[tracked.id]?.state == VerificationState.TRUSTED
        if (trusted) tracked.copy(keepVisible = true) else tracked
    }
}
