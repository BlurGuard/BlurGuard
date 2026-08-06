package com.nash.engine.recognition

import android.os.SystemClock
import android.util.Log
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.VerificationState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer

/**
 * Person-level keep-visible trust. Runs on the ml thread inside the pipeline's
 * detection branch; UI threads only touch the atomics via [KeepVisibleController].
 *
 * Budget: at most ONE recognizer call per detection frame, priority-ordered:
 * pending tap > verify unknown/pending > re-verify trusted > recheck rejected.
 *
 * ## Cost bounds (review fix 21)
 * Three gates run before the expensive path, all pure arithmetic on data the
 * caller already has:
 * 1. [isDue] — a wall-clock floor per track, so cadence cannot scale with fps.
 * 2. [isLargeEnough] — the aligner's size check, moved ahead of the bitmaps.
 * 3. [isStableEnough] — track age and detector confidence, automatic paths only.
 *
 * Every gate is fail-closed: a skipped check leaves the track's verification
 * state untouched, and untouched is never TRUSTED, so the face stays blurred.
 * Skips are deliberately silent — a log line here would fire per frame.
 *
 * ## Logging (review fix 29)
 * The play-by-play of the trust state machine is debug-build only, via
 * [debugLogging], and every message is built inside a lambda so release builds
 * pay nothing for the string concatenation. Only three genuine anomalies —
 * enrollment giving up, a rejection, and a suspected ID switch — log
 * unconditionally at warn level, because those are worth having in a bug
 * report from a release build.
 *
 * @param nowMs monotonic wall clock in milliseconds, injectable for tests.
 * @param debugLogging typically `context.isDebugBuild()`, supplied by DI.
 */
class KeepVisibleOrchestrator<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val config: RecognitionConfig,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
    private val debugLogging: Boolean = false,
) : KeepVisibleController, KeepVisibleRecognizer<F> {

    /** Written from UI, drained on ml thread. */
    private val pendingEnrollment = AtomicLong(NO_REQUEST)
    private val revokeAllRequested = AtomicBoolean(false)

    /** ml-thread only. */
    private val enrollAttempts = HashMap<Long, Int>()

    /** ml-thread only. Track id -> first frame it was seen on. */
    private val firstSeenFrame = HashMap<Long, Long>()

    /** ml-thread only. Track id -> uptime of the last REAL recognizer call. */
    private val lastRecognizedAtMs = HashMap<Long, Long>()

    override fun requestKeepVisible(trackId: TrackId) {
        debug { "tap: keep-visible requested for track=${trackId.value}" }
        pendingEnrollment.set(trackId.value)
    }

    override fun revokeAll() {
        debug { "revokeAll requested" }
        state.clearAll() // instant visual re-blur
        revokeAllRequested.set(true) // store wipe drained on ml thread
    }

    override fun onSessionReset() {
        pendingEnrollment.set(NO_REQUEST)
        enrollAttempts.clear()
        firstSeenFrame.clear()
        lastRecognizedAtMs.clear()
        state.clearAll()
        debug { "session reset (trusted persons kept: ${store.trustedPersonCount})" }
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
            lastRecognizedAtMs.clear()
            debug { "revokeAll drained: trusted store wiped" }
        }

        val faces = boxes.filter { it.clazz == DetectionClass.FACE }
        state.retainTracks(faces.map { it.id }.toSet())

        val liveIds = faces.map { it.id.value }.toSet()
        enrollAttempts.keys.retainAll(liveIds)
        firstSeenFrame.keys.retainAll(liveIds)
        lastRecognizedAtMs.keys.retainAll(liveIds)
        faces.forEach { firstSeenFrame.putIfAbsent(it.id.value, metadata.frameId) }

        val now = nowMs()

        // Priority 1: pending tap. Explicit user intent, so it skips the
        // stability heuristics — but not the clock, and not the size gate.
        val pendingValue = pendingEnrollment.get()
        if (pendingValue != NO_REQUEST) {
            val target = faces.firstOrNull { it.id.value == pendingValue }
            if (target != null) {
                // Not "no" — just "not yet". No attempt is burned.
                if (!isDue(target.id, now)) return
                enroll(frame, target, metadata, now)
                return
            }
            debug { "tap: track=$pendingValue no longer alive, dropping request" }
            pendingEnrollment.compareAndSet(pendingValue, NO_REQUEST)
        }

        val eligible = faces.filter { canAutoCheck(it, metadata, now) }

        // Priority 2: verify unknown/pending faces (re-identification on re-entry).
        if (store.trustedPersonCount > 0) {
            pickDue(eligible, metadata.frameId, VERIFY_RETRY_INTERVAL_FRAMES) {
                it.state == VerificationState.UNKNOWN || it.state == VerificationState.PENDING
            }?.let {
                verify(frame, it, metadata, now)
                return
            }
        }

        // Priority 3: periodic re-verify of trusted tracks (ID-switch defense
        // AND pipeline self-check: same person should log high similarity here).
        pickDue(eligible, metadata.frameId, config.reVerifyIntervalFrames) {
            it.state == VerificationState.TRUSTED
        }?.let {
            reVerify(frame, it, metadata, now)
            return
        }

        // Priority 4: slow recheck of rejected tracks.
        pickDue(eligible, metadata.frameId, config.reVerifyIntervalFrames * REJECTED_RECHECK_MULTIPLIER) {
            it.state == VerificationState.REJECTED
        }?.let {
            verify(frame, it, metadata, now)
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

    // ---------------------------------------------------------------- //
    // Gates. All O(1), no allocation, no Android calls.
    // ---------------------------------------------------------------- //

    /** Wall-clock floor. True for a track that has never been recognized. */
    private fun isDue(trackId: TrackId, now: Long): Boolean =
        lastRecognizedAtMs[trackId.value]
            ?.let { now - it >= config.minRecognitionIntervalMs }
            ?: true

    /**
     * The aligner's size check, hoisted ahead of the three bitmaps it used to
     * sit behind. Measured on the upright frame, because the box is in upright
     * normalized space and a 90/270 rotation swaps the analysis dimensions.
     */
    private fun isLargeEnough(track: TrackedBox, metadata: FrameMetadata): Boolean {
        val sideways = metadata.rotationDegrees % 180 != 0
        val uprightWidth = if (sideways) metadata.height else metadata.width
        val uprightHeight = if (sideways) metadata.width else metadata.height
        val widthPx = (track.box.right - track.box.left) * uprightWidth
        val heightPx = (track.box.bottom - track.box.top) * uprightHeight
        return minOf(widthPx, heightPx) >= config.minFaceBoxPx
    }

    /** Heuristics that a tap is allowed to override. */
    private fun isStableEnough(track: TrackedBox, frameId: Long): Boolean {
        if (track.confidence < config.minTrackConfidence) return false
        val firstSeen = firstSeenFrame[track.id.value] ?: frameId
        return frameId - firstSeen >= config.minTrackAgeFrames
    }

    private fun canAutoCheck(track: TrackedBox, metadata: FrameMetadata, now: Long): Boolean =
        isDue(track.id, now) &&
                isLargeEnough(track, metadata) &&
                isStableEnough(track, metadata.frameId)

    /**
     * The one place a recognizer call is made, so the clock stamp cannot drift
     * out of sync with the calls it is meant to bound.
     */
    private suspend fun embed(
        frame: F,
        track: TrackedBox,
        metadata: FrameMetadata,
        now: Long
    ): FaceEmbedding? {
        lastRecognizedAtMs[track.id.value] = now
        return recognizer.embed(frame, track.box, metadata)
    }

    // ---------------------------------------------------------------- //

    private suspend fun enroll(frame: F, track: TrackedBox, metadata: FrameMetadata, now: Long) {
        // A too-small face used to reach the aligner and come back null after
        // three bitmap allocations. Same outcome, same attempt cost, no work.
        val embedding =
            if (isLargeEnough(track, metadata)) embed(frame, track, metadata, now) else null

        if (embedding == null) {
            val attempts = (enrollAttempts[track.id.value] ?: 0) + 1
            enrollAttempts[track.id.value] = attempts
            debug { "enroll track=${track.id.value}: null embed, attempt $attempts/$MAX_ENROLL_ATTEMPTS" }
            if (attempts >= MAX_ENROLL_ATTEMPTS) {
                enrollAttempts.remove(track.id.value)
                pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
                state.set(track.id, TrackVerification(
                    state = VerificationState.UNKNOWN,
                    lastCheckedFrame = metadata.frameId
                ))
                Log.w(TAG, "enroll track=${track.id.value}: gave up, quality gates never passed")
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
            debug {
                "enroll track=${track.id.value}: matched existing person=${match.personId.value} " +
                        "sim=${fmt(match.similarity)} -> reusing"
            }
            store.addToGallery(match.personId, embedding)
            match.personId
        } else {
            val newId = store.enroll(embedding)
            debug {
                "enroll track=${track.id.value}: NEW person=${newId.value} " +
                        "(bestExisting=${match?.similarity?.let(::fmt) ?: "none"})"
            }
            newId
        }
        state.set(track.id, TrackVerification(
            state = VerificationState.TRUSTED,
            personId = personId,
            lastCheckedFrame = metadata.frameId
        ))
        pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
    }

    private suspend fun verify(frame: F, track: TrackedBox, metadata: FrameMetadata, now: Long) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata, now)
        if (embedding == null) {
            debug { "verify track=${track.id.value}: null embed (quality gate) — no decision" }
            state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
            return
        }

        val match = store.bestMatch(embedding)
        debug {
            "verify track=${track.id.value}: best=${match?.similarity?.let(::fmt) ?: "none"} " +
                    "person=${match?.personId?.value} threshold=${fmt(config.matchThreshold)}"
        }

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
                debug {
                    "verify track=${track.id.value}: TRUSTED as person=${match.personId.value} " +
                            "sim=${fmt(match.similarity)}"
                }
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
                Log.w(TAG, "verify track=${track.id.value}: rejected after $mismatches mismatches")
            }
        }
    }

    private suspend fun reVerify(frame: F, track: TrackedBox, metadata: FrameMetadata, now: Long) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata, now)
        if (embedding == null) {
            debug { "reVerify track=${track.id.value}: null embed — no decision" }
            state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
            return
        }

        val match = store.bestMatch(embedding)
        val samePerson = match != null &&
                match.personId == current.personId &&
                match.similarity >= config.matchThreshold

        if (samePerson) {
            // SELF-CHECK: person hasn't moved -> this similarity is the
            // pipeline health metric. Should be comfortably above threshold.
            debug {
                "reVerify track=${track.id.value}: OK person=${match!!.personId.value} " +
                        "sim=${fmt(match.similarity)}"
            }
            store.addToGallery(match!!.personId, embedding)
            state.set(track.id, current.copy(
                consecutiveMismatches = 0,
                lastCheckedFrame = metadata.frameId
            ))
        } else {
            val mismatches = current.consecutiveMismatches + 1
            debug {
                "reVerify track=${track.id.value}: mismatch " +
                        "best=${match?.similarity?.let(::fmt) ?: "none"} " +
                        "bestPerson=${match?.personId?.value} " +
                        "expected=${current.personId?.value} ($mismatches/${config.mismatchesToRevoke})"
            }
            if (mismatches >= config.mismatchesToRevoke) {
                state.set(track.id, TrackVerification(
                    state = VerificationState.REJECTED,
                    personId = current.personId,
                    lastCheckedFrame = metadata.frameId
                ))
                Log.w(TAG, "reVerify track=${track.id.value}: revoked, possible ID switch")
            } else {
                state.set(track.id, current.copy(
                    consecutiveMismatches = mismatches,
                    lastCheckedFrame = metadata.frameId
                ))
            }
        }
    }

    /** Message is built only when debug logging is on. */
    private inline fun debug(message: () -> String) {
        if (debugLogging) Log.d(TAG, message())
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