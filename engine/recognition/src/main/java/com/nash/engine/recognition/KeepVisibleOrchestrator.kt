package com.nash.engine.recognition

import android.os.SystemClock
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
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Person-level keep-visible trust. Runs on the ml thread inside the pipeline's
 * detection branch; UI threads only touch the atomics via [KeepVisibleController].
 *
 * Budget: at most ONE recognizer call per detection frame, priority-ordered:
 * pending tap > verify unknown/pending > re-verify trusted > recheck rejected.
 *
 * Every automatic path is additionally gated on cheap, purely arithmetic checks
 * (size, track stability, elapsed wall clock) so that faces which cannot
 * plausibly produce a usable embedding never reach the expensive path. Failing
 * a gate is always fail-closed: no decision is recorded and the face stays
 * blurred.
 *
 * @param nowMs wall-clock source, injectable so cadence is testable on the JVM.
 * @param logger logging seam; production wires an Android-backed
 * implementation in DI, JVM tests default to [RecognitionLogger.None].
 */
class KeepVisibleOrchestrator<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val config: RecognitionConfig,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) : KeepVisibleController, KeepVisibleRecognizer<F> {

    /** Written from UI, drained on ml thread. */
    private val pendingEnrollment = AtomicLong(NO_REQUEST)
    private val revokeAllRequested = AtomicBoolean(false)

    /** ml-thread only. */
    private val enrollAttempts = HashMap<Long, Int>()

    /** ml-thread only. First frame each live track was seen on (age gate). */
    private val firstSeenFrame = HashMap<Long, Long>()

    /** ml-thread only. Wall clock of the last embed attempt per track. */
    private val lastRecognizedAtMs = HashMap<Long, Long>()

    override fun requestKeepVisible(trackId: TrackId) {
        logger.debug { "tap: keep-visible requested for track=${trackId.value}" }
        pendingEnrollment.set(trackId.value)
    }

    override fun revokeAll() {
        logger.debug { "revokeAll requested" }
        state.clearAll() // instant visual re-blur
        revokeAllRequested.set(true) // store wipe drained on ml thread
    }

    override fun onSessionReset() {
        pendingEnrollment.set(NO_REQUEST)
        enrollAttempts.clear()
        firstSeenFrame.clear()
        lastRecognizedAtMs.clear()
        state.clearAll()
        logger.debug { "session reset (trusted persons kept: ${store.trustedPersonCount})" }
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
            firstSeenFrame.clear()
            lastRecognizedAtMs.clear()
            logger.debug { "revokeAll drained: trusted store wiped" }
        }

        val faces = boxes.filter { it.clazz == DetectionClass.FACE }
        val liveIds = faces.map { it.id.value }.toSet()
        state.retainTracks(faces.map { it.id }.toSet())
        enrollAttempts.keys.retainAll(liveIds)
        firstSeenFrame.keys.retainAll(liveIds)
        lastRecognizedAtMs.keys.retainAll(liveIds)
        faces.forEach { firstSeenFrame.putIfAbsent(it.id.value, metadata.frameId) }

        val now = nowMs()

        // Priority 1: pending tap. The user asked explicitly, so the STABILITY gate
        // is skipped — but the size gate still applies: an undersized face cannot
        // produce a usable embedding, so the tap stays pending until the subject is
        // close enough. The interval floor spreads maxEnrollAttempts over time.
        val pendingValue = pendingEnrollment.get()
        if (pendingValue != NO_REQUEST) {
            val target = faces.firstOrNull { it.id.value == pendingValue }
            if (target != null) {
                if (isIntervalElapsed(target, now) && isLargeEnough(target, metadata)) {
                    enroll(frame, target, metadata, now)
                }
                return
            }
            logger.debug { "tap: track=$pendingValue no longer alive, dropping request" }
            pendingEnrollment.compareAndSet(pendingValue, NO_REQUEST)
        }

        // Priority 2: verify unknown/pending faces (re-identification on re-entry).
        if (store.trustedPersonCount > 0) {
            pickDue(faces, metadata, now, config.verifyRetryIntervalFrames) {
                it.state == VerificationState.UNKNOWN || it.state == VerificationState.PENDING
            }?.let {
                verify(frame, it, metadata, now)
                return
            }
        }

        // Priority 3: periodic re-verify of trusted tracks (ID-switch defense
        // AND pipeline self-check: same person should log high similarity here).
        pickDue(faces, metadata, now, config.reVerifyIntervalFrames) {
            it.state == VerificationState.TRUSTED
        }?.let {
            reVerify(frame, it, metadata, now)
            return
        }

        // Priority 4: slow recheck of rejected tracks.
        pickDue(
            faces,
            metadata,
            now,
            config.reVerifyIntervalFrames * config.rejectedRecheckMultiplier
        ) {
            it.state == VerificationState.REJECTED
        }?.let {
            verify(frame, it, metadata, now)
        }
    }

    /** Oldest-checked eligible face matching [predicate] whose [interval] has elapsed. */
    private fun pickDue(
        faces: List<TrackedBox>,
        metadata: FrameMetadata,
        now: Long,
        interval: Long,
        predicate: (TrackVerification) -> Boolean
    ): TrackedBox? = faces
        .filter { predicate(state.of(it.id)) }
        .filter { metadata.frameId - state.of(it.id).lastCheckedFrame >= interval }
        .filter { canAutoCheck(it, metadata, now) }
        .minByOrNull { state.of(it.id).lastCheckedFrame }

    /** All cheap gates for the automatic (non-tap) paths. Pure arithmetic. */
    private fun canAutoCheck(track: TrackedBox, metadata: FrameMetadata, now: Long): Boolean =
        isIntervalElapsed(track, now) &&
                isLargeEnough(track, metadata) &&
                isStableEnough(track, metadata)

    private fun isIntervalElapsed(track: TrackedBox, now: Long): Boolean {
        val last = lastRecognizedAtMs[track.id.value] ?: return true
        return now - last >= config.minRecognitionIntervalMs
    }

    /**
     * Box size in UPRIGHT pixels, on both axes.
     *
     * [FrameMetadata.width] and [FrameMetadata.height] describe the raw buffer,
     * but [TrackedBox.box] is normalized against the upright frame — the same
     * space the recognizer crops in after rotating. At 90/270 those two are
     * transposed, so the dimensions must be swapped before measuring, or the
     * height of a face is compared against the width of the frame.
     */
    private fun isLargeEnough(track: TrackedBox, metadata: FrameMetadata): Boolean {
        val transposed = metadata.rotationDegrees % 180 != 0
        val uprightWidth = if (transposed) metadata.height else metadata.width
        val uprightHeight = if (transposed) metadata.width else metadata.height
        val widthPx = (track.box.right - track.box.left) * uprightWidth
        val heightPx = (track.box.bottom - track.box.top) * uprightHeight
        return widthPx >= config.minFaceBoxPx && heightPx >= config.minFaceBoxPx
    }

    private fun isStableEnough(track: TrackedBox, metadata: FrameMetadata): Boolean {
        if (track.confidence < config.minTrackConfidence) return false
        val firstSeen = firstSeenFrame[track.id.value] ?: return false
        return metadata.frameId - firstSeen >= config.minTrackAgeFrames
    }

    /**
     * Single entry point to the expensive path. Stamps the clock BEFORE the
     * call so a slow or failed pass still counts against the interval budget.
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

    private suspend fun enroll(
        frame: F,
        track: TrackedBox,
        metadata: FrameMetadata,
        now: Long
    ) {
        val embedding = embed(frame, track, metadata, now)
        if (embedding == null) {
            val attempts = (enrollAttempts[track.id.value] ?: 0) + 1
            enrollAttempts[track.id.value] = attempts
            logger.debug {
                "enroll track=${track.id.value}: null embed, " +
                        "attempt $attempts/${config.maxEnrollAttempts}"
            }
            if (attempts >= config.maxEnrollAttempts) {
                enrollAttempts.remove(track.id.value)
                pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
                state.set(
                    track.id,
                    TrackVerification(
                        state = VerificationState.UNKNOWN,
                        lastCheckedFrame = metadata.frameId
                    )
                )
                logger.warn("enroll track=${track.id.value}: GAVE UP — quality gates never passed")
            } else {
                state.set(
                    track.id,
                    TrackVerification(
                        state = VerificationState.PENDING,
                        lastCheckedFrame = metadata.frameId
                    )
                )
            }
            return
        }

        enrollAttempts.remove(track.id.value)
        val match = store.bestMatch(embedding)
        val personId = if (match != null && match.similarity >= config.matchThreshold) {
            logger.debug {
                "enroll track=${track.id.value}: matched existing person=${match.personId.value} " +
                        "sim=${fmt(match.similarity)} -> reusing"
            }
            store.addToGallery(match.personId, embedding)
            match.personId
        } else {
            val newId = store.enroll(embedding)
            logger.debug {
                "enroll track=${track.id.value}: NEW person=${newId.value} " +
                        "(bestExisting=${match?.similarity?.let(::fmt) ?: "none"})"
            }
            newId
        }
        state.set(
            track.id,
            TrackVerification(
                state = VerificationState.TRUSTED,
                personId = personId,
                lastCheckedFrame = metadata.frameId
            )
        )
        pendingEnrollment.compareAndSet(track.id.value, NO_REQUEST)
    }

    private suspend fun verify(
        frame: F,
        track: TrackedBox,
        metadata: FrameMetadata,
        now: Long
    ) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata, now)
        if (embedding == null) {
            logger.debug { "verify track=${track.id.value}: null embed (quality gate) — no decision" }
            state.set(track.id, current.copy(lastCheckedFrame = metadata.frameId))
            return
        }

        val match = store.bestMatch(embedding)
        logger.debug {
            "verify track=${track.id.value}: best=${match?.similarity?.let(::fmt) ?: "none"} " +
                    "person=${match?.personId?.value} threshold=${fmt(config.matchThreshold)}"
        }

        if (match != null && match.similarity >= config.matchThreshold) {
            val matches = current.consecutiveMatches + 1
            if (matches >= config.consecutiveMatchesToTrust) {
                store.addToGallery(match.personId, embedding)
                state.set(
                    track.id,
                    TrackVerification(
                        state = VerificationState.TRUSTED,
                        personId = match.personId,
                        consecutiveMatches = matches,
                        lastCheckedFrame = metadata.frameId
                    )
                )
                logger.debug {
                    "verify track=${track.id.value}: TRUSTED as person=${match.personId.value} " +
                            "sim=${fmt(match.similarity)}"
                }
            } else {
                state.set(
                    track.id,
                    current.copy(
                        state = VerificationState.PENDING,
                        personId = match.personId,
                        consecutiveMatches = matches,
                        consecutiveMismatches = 0,
                        lastCheckedFrame = metadata.frameId
                    )
                )
            }
        } else {
            // F2 fix: hysteresis — never hard-reject on a single noisy embed.
            val mismatches = current.consecutiveMismatches + 1
            val newState = if (mismatches >= config.mismatchesToRevoke) {
                VerificationState.REJECTED
            } else {
                current.state // stay UNKNOWN/PENDING, keep trying
            }
            state.set(
                track.id,
                current.copy(
                    state = newState,
                    consecutiveMatches = 0,
                    consecutiveMismatches = mismatches,
                    lastCheckedFrame = metadata.frameId
                )
            )
            if (newState == VerificationState.REJECTED) {
                logger.warn("verify track=${track.id.value}: REJECTED after $mismatches mismatches")
            }
        }
    }

    private suspend fun reVerify(
        frame: F,
        track: TrackedBox,
        metadata: FrameMetadata,
        now: Long
    ) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata, now)
        if (embedding == null) {
            logger.debug { "reVerify track=${track.id.value}: null embed — no decision" }
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
            logger.debug {
                "reVerify track=${track.id.value}: OK person=${match.personId.value} " +
                        "sim=${fmt(match.similarity)}"
            }
            store.addToGallery(match.personId, embedding)
            state.set(
                track.id,
                current.copy(
                    consecutiveMismatches = 0,
                    lastCheckedFrame = metadata.frameId
                )
            )
        } else {
            val mismatches = current.consecutiveMismatches + 1
            logger.warn(
                "reVerify track=${track.id.value}: MISMATCH " +
                        "best=${match?.similarity?.let(::fmt) ?: "none"} " +
                        "bestPerson=${match?.personId?.value} " +
                        "expected=${current.personId?.value} ($mismatches/${config.mismatchesToRevoke})"
            )
            if (mismatches >= config.mismatchesToRevoke) {
                state.set(
                    track.id,
                    TrackVerification(
                        state = VerificationState.REJECTED,
                        personId = current.personId,
                        lastCheckedFrame = metadata.frameId
                    )
                )
                logger.warn("reVerify track=${track.id.value}: REVOKED (possible ID switch)")
            } else {
                state.set(
                    track.id,
                    current.copy(
                        consecutiveMismatches = mismatches,
                        lastCheckedFrame = metadata.frameId
                    )
                )
            }
        }
    }

    private fun fmt(v: Float) = "%.3f".format(v)

    private companion object {
        /** Sentinel for "no pending enrollment" — real TrackIds start at 1. */
        const val NO_REQUEST = Long.MIN_VALUE
    }
}