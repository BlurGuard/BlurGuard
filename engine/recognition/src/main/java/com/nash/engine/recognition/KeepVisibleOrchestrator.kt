package com.nash.engine.recognition

import android.util.Log
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.VerificationState
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Person-level keep-visible trust. Runs on the ml thread inside the pipeline's
 * detection branch; UI threads only touch the atomics via [KeepVisibleController].
 *
 * Owns identity policy and nothing else: it reaches the embedding model only
 * through [FaceRecognizer], so engine/recognition needs no TFLite or MediaPipe
 * dependency and no dependency on engine/ml (review fix 15).
 *
 * Budget: at most ONE recognizer call per detection frame, priority-ordered:
 * pending tap > verify unknown/pending > re-verify trusted > recheck rejected.
 */
class KeepVisibleOrchestrator<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val config: RecognitionConfig,
) : KeepVisibleController, KeepVisibleRecognizer<F> {

    /** Written from UI, drained on ml thread. */
    private val pendingEnrollment = AtomicLong(NO_REQUEST)
    private val revokeAllRequested = AtomicBoolean(false)

    /** ml-thread only. */
    private val enrollAttempts = HashMap<Long, Int>()

    override fun requestKeepVisible(trackId: TrackId) {
        Log.d(TAG, "tap: keep-visible requested for track=${trackId.value}")
        pendingEnrollment.set(trackId.value)
    }

    override fun revokeAll() {
        Log.d(TAG, "revokeAll requested")
        state.clearAll() // instant visual re-blur
        revokeAllRequested.set(true) // store wipe drained on ml thread
    }

    override fun onSessionReset() {
        pendingEnrollment.set(NO_REQUEST)
        enrollAttempts.clear()
        state.clearAll()
        Log.d(TAG, "session reset (trusted persons kept: ${store.trustedPersonCount})")
    }

    override suspend fun onDetectionFrame(
        frame: F,
        metadata: FrameMetadata,
        boxes: List<TrackedBox>
    ) {
        if (revokeAllRequested.compareAndSet(true, false)) {
            store.revokeAll()
            state.clearAll()
            enrollAttempts.clear()
            Log.d(TAG, "revokeAll drained: trusted store wiped")
        }

        val faces = boxes.filter { it.clazz == DetectionClass.FACE }
        state.retainTracks(faces.map { it.id }.toSet())
        enrollAttempts.keys.retainAll(faces.map { it.id.value }.toSet())

        // Priority 1: pending tap.
        val pendingValue = pendingEnrollment.get()
        if (pendingValue != NO_REQUEST) {
            val target = faces.firstOrNull { it.id.value == pendingValue }
            if (target != null) {
                enroll(frame, target, metadata)
                return
            }
            Log.d(TAG, "tap: track=$pendingValue no longer alive, dropping request")
            pendingEnrollment.compareAndSet(pendingValue, NO_REQUEST)
        }

        // Priority 2: verify unknown/pending faces (re-identification on re-entry).
        if (store.trustedPersonCount > 0) {
            pickDue(faces, metadata.frameId, VERIFY_RETRY_INTERVAL_FRAMES) {
                it.state == VerificationState.UNKNOWN || it.state == VerificationState.PENDING
            }?.let {
                verify(frame, it, metadata)
                return
            }
        }

        // Priority 3: periodic re-verify of trusted tracks (ID-switch defense
        // AND pipeline self-check: same person should log high similarity here).
        pickDue(faces, metadata.frameId, config.reVerifyIntervalFrames) {
            it.state == VerificationState.TRUSTED
        }?.let {
            reVerify(frame, it, metadata)
            return
        }

        // Priority 4: slow recheck of rejected tracks.
        pickDue(faces, metadata.frameId, config.reVerifyIntervalFrames * REJECTED_RECHECK_MULTIPLIER) {
            it.state == VerificationState.REJECTED
        }?.let {
            verify(frame, it, metadata)
        }
    }

    /** Oldest-checked face matching [predicate] whose [interval] has elapsed. */
    private fun pickDue(
        faces: List<TrackedBox>,
        frameId: Long,
        interval: Long,
        predicate: (TrackVerification) -> Boolean
    ): TrackedBox? = faces
        .filter { predicate(state.of(it.id)) }
        .filter { frameId - state.of(it.id).lastCheckedFrame >= interval }
        .minByOrNull { state.of(it.id).lastCheckedFrame }

    private suspend fun enroll(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val embedding = recognizer.embed(frame, track.box, metadata)
        if (embedding == null) {
            val attempts = (enrollAttempts[track.id.value] ?: 0) + 1
            enrollAttempts[track.id.value] = attempts
            Log.d(TAG, "enroll track=${track.id.value}: null embed, attempt $attempts/$MAX_ENROLL_ATTEMPTS")
            if (attempts >= MAX_ENROLL_ATTEMPTS) {
                enrollAttempts.remove(track.id.value)
                pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
                state.set(track.id, TrackVerification(
                    state = VerificationState.UNKNOWN,
                    lastCheckedFrame = metadata.frameId
                ))
                Log.w(TAG, "enroll track=${track.id.value}: GAVE UP — quality gates never passed")
            } else {
                state.set(track.id, TrackVerification(
                    state = VerificationState.PENDING,
                    lastCheckedFrame = metadata.frameId
                ))
            }
            return
        }

        enrollAttempts.remove(track.id.value)
        val match = store.bestMatch(embedding)
        val personId = if (match != null && match.similarity >= config.matchThreshold) {
            Log.i(TAG, "enroll track=${track.id.value}: matched existing person=${match.personId.value} " +
                    "sim=${fmt(match.similarity)} -> reusing")
            store.addToGallery(match.personId, embedding)
            match.personId
        } else {
            val newId = store.enroll(embedding)
            Log.i(TAG, "enroll track=${track.id.value}: NEW person=${newId.value} " +
                    "(bestExisting=${match?.similarity?.let(::fmt) ?: "none"})")
            newId
        }
        state.set(track.id, TrackVerification(
            state = VerificationState.TRUSTED,
            personId = personId,
            lastCheckedFrame = metadata.frameId
        ))
        pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
    }

    private suspend fun verify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = recognizer.embed(frame, track.box, metadata)
        if (embedding == null) {
            Log.d(TAG, "verify track=${track.id.value}: null embed (quality gate) — no decision")
            state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
            return
        }

        val match = store.bestMatch(embedding)
        Log.d(TAG, "verify track=${track.id.value}: best=${match?.similarity?.let(::fmt) ?: "none"} " +
                "person=${match?.personId?.value} threshold=${fmt(config.matchThreshold)}")

        if (match != null && match.similarity >= config.matchThreshold) {
            val matches = current.consecutiveMatches + 1
            if (matches >= config.consecutiveMatchesToTrust) {
                store.addToGallery(match.personId, embedding)
                state.set(track.id, TrackVerification(
                    state = VerificationState.TRUSTED,
                    personId = match.personId,
                    consecutiveMatches = matches,
                    lastCheckedFrame = metadata.frameId
                ))
                Log.i(TAG, "verify track=${track.id.value}: TRUSTED as person=${match.personId.value} " +
                        "sim=${fmt(match.similarity)}")
            } else {
                state.set(track.id, current.copy(
                    state = VerificationState.PENDING,
                    personId = match.personId,
                    consecutiveMatches = matches,
                    consecutiveMismatches = 0,
                    lastCheckedFrame = metadata.frameId
                ))
            }
        } else {
            // F2 fix: hysteresis — never hard-reject on a single noisy embed.
            val mismatches = current.consecutiveMismatches + 1
            val newState = if (mismatches >= config.mismatchesToRevoke) {
                VerificationState.REJECTED
            } else {
                current.state // stay UNKNOWN/PENDING, keep trying
            }
            state.set(track.id, current.copy(
                state = newState,
                consecutiveMatches = 0,
                consecutiveMismatches = mismatches,
                lastCheckedFrame = metadata.frameId
            ))
            if (newState == VerificationState.REJECTED) {
                Log.w(TAG, "verify track=${track.id.value}: REJECTED after $mismatches mismatches")
            }
        }
    }

    private suspend fun reVerify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = recognizer.embed(frame, track.box, metadata)
        if (embedding == null) {
            Log.d(TAG, "reVerify track=${track.id.value}: null embed — no decision")
            state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
            return
        }

        val match = store.bestMatch(embedding)
        val samePerson = match != null &&
                match.personId == current.personId &&
                match.similarity >= config.matchThreshold

        if (samePerson) {
            // SELF-CHECK: person hasn't moved -> this similarity is your
            // pipeline health metric. Should be comfortably above threshold.
            Log.i(TAG, "reVerify track=${track.id.value}: OK person=${match!!.personId.value} " +
                    "sim=${fmt(match.similarity)}")
            store.addToGallery(match.personId, embedding)
            state.set(track.id, current.copy(
                consecutiveMismatches = 0,
                lastCheckedFrame = metadata.frameId
            ))
        } else {
            val mismatches = current.consecutiveMismatches + 1
            Log.w(TAG, "reVerify track=${track.id.value}: MISMATCH " +
                    "best=${match?.similarity?.let(::fmt) ?: "none"} bestPerson=${match?.personId?.value} " +
                    "expected=${current.personId?.value} ($mismatches/${config.mismatchesToRevoke})")
            if (mismatches >= config.mismatchesToRevoke) {
                state.set(track.id, TrackVerification(
                    state = VerificationState.REJECTED,
                    personId = current.personId,
                    lastCheckedFrame = metadata.frameId
                ))
                Log.w(TAG, "reVerify track=${track.id.value}: REVOKED (possible ID switch)")
            } else {
                state.set(track.id, current.copy(
                    consecutiveMismatches = mismatches,
                    lastCheckedFrame = metadata.frameId
                ))
            }
        }
    }

    private fun fmt(v: Float) = "%.3f".format(v)

    private companion object {
        const val TAG = "KeepVisible"
        /** Sentinel for "no pending enrollment" — real TrackIds start at 1. */
        const val NO_REQUEST = Long.MIN_VALUE
        const val VERIFY_RETRY_INTERVAL_FRAMES = 6L
        const val MAX_ENROLL_ATTEMPTS = 10
        /** Was 4 — shortened: hysteresis (F2) makes rejection safe to retry sooner. */
        const val REJECTED_RECHECK_MULTIPLIER = 2
    }
}