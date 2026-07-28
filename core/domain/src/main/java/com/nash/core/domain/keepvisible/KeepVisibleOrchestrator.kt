package com.nash.core.domain.keepvisible

import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.VerificationState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides blur vs keep-visible. Owns the whole trust lifecycle:
 *
 * - Tap -> enroll embedding as a trusted person -> TRUSTED (immediate: the tap
 *   IS the user's explicit intent; K-consecutive applies to re-entry only).
 * - New FACE track while trusted persons exist -> verify: match K consecutive
 *   times -> TRUSTED (same person, new track id); mismatch -> REJECTED.
 * - TRUSTED tracks are re-verified periodically (defense against tracker ID
 *   switches); consecutive mismatches revoke.
 * - REJECTED tracks are re-checked on a slow cadence (appearance may improve).
 *
 * Budget: at most ONE recognizer call per detection frame, chosen by the
 * priority above. Fail-closed everywhere: a null embedding is "no decision",
 * never a match or a mismatch.
 *
 * Threading: [onDetectionFrame] and all state/store mutation run on the ml
 * dispatcher (inside the pipeline). UI-thread calls only touch atomics.
 */
class KeepVisibleOrchestrator<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleState,
    private val config: RecognitionConfig
) : KeepVisibleController {

    private val pendingEnrollment = AtomicLong(NO_REQUEST)
    private val revokeAllRequested = AtomicBoolean(false)

    // ml-thread only:
    private var enrollAttemptsFor: TrackId? = null
    private var enrollAttempts = 0

    override fun requestKeepVisible(trackId: TrackId) {
        pendingEnrollment.set(trackId.value)
    }

    override fun revokeAll() {
        // Instant visual effect (plain assignment, thread-safe)...
        state.clearAll()
        // ...and authoritative cleanup on the ml thread, closing the tiny
        // window where an in-flight verification could re-add an entry.
        revokeAllRequested.set(true)
    }

    /** Called by the pipeline when a recording session starts/resets. */
    fun onSessionReset() {
        pendingEnrollment.set(NO_REQUEST)
        state.clearAll()
        // Trusted persons deliberately survive session resets (trust is
        // per-person, not per-session); revokeAll() is the explicit wipe.
    }

    /**
     * Called from the pipeline's detection branch, after tracker.update and
     * before publishing. [boxes] are upright-normalized tracker output — the
     * same space the recognizer's crop expects.
     */
    suspend fun onDetectionFrame(frame: F, metadata: FrameMetadata, boxes: List<TrackedBox>) {
        if (revokeAllRequested.getAndSet(false)) {
            store.revokeAll()
            state.clearAll()
        }
        state.retainTracks(boxes.mapTo(mutableSetOf()) { it.id })

        val frameId = metadata.frameId
        val faces = boxes.filter { it.clazz == DetectionClass.FACE }

        // Priority 1: pending tap.
        val pendingId = pendingEnrollment.get()
        if (pendingId != NO_REQUEST) {
            val target = faces.firstOrNull { it.id.value == pendingId }
            if (target != null) {
                enroll(frame, target, metadata)
                return
            }
            // Track died before we could enroll — drop the request.
            pendingEnrollment.compareAndSet(pendingId, NO_REQUEST)
        }

        if (store.trustedPersonCount == 0) return

        // Priority 2: unverified new tracks (UNKNOWN/PENDING).
        pickDue(faces, frameId, VERIFY_RETRY_INTERVAL_FRAMES) {
            it.state == VerificationState.UNKNOWN || it.state == VerificationState.PENDING
        }?.let { verify(frame, it, metadata); return }

        // Priority 3: periodic re-verification of TRUSTED tracks.
        pickDue(faces, frameId, config.reVerifyIntervalFrames) {
            it.state == VerificationState.TRUSTED
        }?.let { reVerify(frame, it, metadata); return }

        // Priority 4: slow re-check of REJECTED tracks (pose may have improved).
        pickDue(faces, frameId, config.reVerifyIntervalFrames * REJECTED_RECHECK_MULTIPLIER) {
            it.state == VerificationState.REJECTED
        }?.let { verify(frame, it, metadata) }
    }

    /** Oldest-checked-first among tracks whose check interval has elapsed. */
    private inline fun pickDue(
        faces: List<TrackedBox>,
        frameId: Long,
        intervalFrames: Long,
        crossinline predicate: (TrackVerification) -> Boolean
    ): TrackedBox? = faces
        .filter {
            val v = state.of(it.id)
            predicate(v) && frameId - v.lastCheckedFrame >= intervalFrames
        }
        .minByOrNull { state.of(it.id).lastCheckedFrame }

    private suspend fun enroll(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        if (enrollAttemptsFor != track.id) {
            enrollAttemptsFor = track.id
            enrollAttempts = 0
        }
        val current = state.of(track.id)
        val embedding = recognizer.embed(frame, track.box, metadata)
        if (embedding == null) {
            enrollAttempts++
            if (enrollAttempts >= MAX_ENROLL_ATTEMPTS) {
                // Quality gates never passed (tiny/profile face). Give up;
                // Phase 5 surfaces this as "couldn't verify — try closer".
                pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
                state.set(track.id, TrackVerification(lastCheckedFrame = metadata.frameId))
            } else {
                state.set(
                    track.id,
                    current.copy(state = VerificationState.PENDING, lastCheckedFrame = metadata.frameId)
                )
            }
            return
        }

        pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
        val match = store.bestMatch(embedding)
        val personId = if (match != null && match.similarity >= config.matchThreshold) {
            // Tapped an already-trusted person (e.g. after re-entry): reuse the
            // identity and grow its gallery instead of forking a duplicate.
            store.addToGallery(match.personId, embedding)
            match.personId
        } else {
            store.enroll(embedding)
        }
        state.set(
            track.id,
            TrackVerification(VerificationState.TRUSTED, personId, lastCheckedFrame = metadata.frameId)
        )
    }

    private suspend fun verify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = recognizer.embed(frame, track.box, metadata)
            ?: run {
                // No decision — just reschedule (fail-closed).
                state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
                return
            }

        val match = store.bestMatch(embedding)
        if (match != null && match.similarity >= config.matchThreshold) {
            val matches = current.consecutiveMatches + 1
            if (matches >= config.consecutiveMatchesToTrust) {
                store.addToGallery(match.personId, embedding) // opportunistic variety
                state.set(
                    track.id,
                    TrackVerification(VerificationState.TRUSTED, match.personId, lastCheckedFrame = metadata.frameId)
                )
            } else {
                state.set(
                    track.id,
                    TrackVerification(
                        VerificationState.PENDING,
                        match.personId,
                        consecutiveMatches = matches,
                        lastCheckedFrame = metadata.frameId
                    )
                )
            }
        } else {
            state.set(track.id, TrackVerification(VerificationState.REJECTED, lastCheckedFrame = metadata.frameId))
        }
    }

    private suspend fun reVerify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = recognizer.embed(frame, track.box, metadata)
            ?: run {
                state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
                return
            }

        val match = store.bestMatch(embedding)
        val samePerson = match != null &&
                match.personId == current.personId &&
                match.similarity >= config.matchThreshold
        if (samePerson) {
            store.addToGallery(match!!.personId, embedding)
            state.set(track.id, current.copy(consecutiveMismatches = 0, lastCheckedFrame = metadata.frameId))
        } else {
            val misses = current.consecutiveMismatches + 1
            if (misses >= config.mismatchesToRevoke) {
                // Track was likely stolen by an ID switch: re-blur it.
                state.set(track.id, TrackVerification(VerificationState.REJECTED, lastCheckedFrame = metadata.frameId))
            } else {
                state.set(track.id, current.copy(consecutiveMismatches = misses, lastCheckedFrame = metadata.frameId))
            }
        }
    }

    private companion object {
        const val NO_REQUEST = Long.MIN_VALUE
        /** Retry cadence for verification attempts (in frame IDs, ~6 = 2 detection cycles). */
        const val VERIFY_RETRY_INTERVAL_FRAMES = 6L
        /** Give up enrolling after this many failed quality-gated attempts. */
        const val MAX_ENROLL_ATTEMPTS = 10
        /** REJECTED tracks are re-checked this much slower than re-verification. */
        const val REJECTED_RECHECK_MULTIPLIER = 4
    }
}