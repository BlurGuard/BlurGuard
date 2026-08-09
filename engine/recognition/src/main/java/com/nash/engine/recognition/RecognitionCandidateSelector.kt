package com.nash.engine.recognition

import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.VerificationState

/** The at-most-one recognition action chosen for a detection frame. */
sealed interface RecognitionCandidate {
    val track: TrackedBox

    /** Tap-driven enrolment of [track] as a trusted person. */
    data class Enroll(override val track: TrackedBox) : RecognitionCandidate

    /** Identity check of an unknown/pending/rejected [track] against the gallery. */
    data class Verify(override val track: TrackedBox) : RecognitionCandidate

    /** Periodic identity re-check of an already trusted [track]. */
    data class ReVerify(override val track: TrackedBox) : RecognitionCandidate
}

/**
 * Owns the keep-visible priority policy (strict follow-up fix 4): decides
 * which single face, if any, spends this frame's one-recognizer-call budget.
 *
 * Priority order: pending tap > verify unknown/pending > re-verify trusted >
 * recheck rejected. Selection is fail-closed: returning null means no
 * decision is recorded anywhere and every non-trusted face stays blurred.
 *
 * ml-thread only. The only mutation this class performs is dropping a
 * pending tap whose track has died — that cleanup is part of the tap
 * priority's semantics, not a recognition side effect.
 */
class RecognitionCandidateSelector(
    private val commands: KeepVisibleCommandQueue,
    private val liveTracks: LiveTrackRegistry,
    private val gate: RecognitionGate,
    private val state: KeepVisibleStateReader,
    private val store: TrustedPersonStore,
    private val config: RecognitionConfig,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) {

    fun select(faces: List<TrackedBox>, metadata: FrameMetadata): RecognitionCandidate? {
        // Priority 1: pending tap. The user asked explicitly, so the STABILITY gate
        // is skipped — but the size gate still applies: an undersized face cannot
        // produce a usable embedding, so the tap stays pending until the subject is
        // close enough. The interval floor spreads maxEnrollAttempts over time.
        val pendingTap = commands.pendingEnrollment()
        if (pendingTap != null) {
            val target = faces.firstOrNull { it.id == pendingTap }
            if (target != null) {
                return if (liveTracks.isIntervalElapsed(target) &&
                    gate.isLargeEnough(target, metadata)
                ) {
                    RecognitionCandidate.Enroll(target)
                } else {
                    // A live tap outranks every automatic path even while its
                    // gates fail: the budget is reserved, nothing else runs.
                    null
                }
            }
            logger.debug { "tap: track=${pendingTap.value} no longer alive, dropping request" }
            commands.clearPendingEnrollment(pendingTap)
        }

        // Priority 2: verify unknown/pending faces (re-identification on re-entry).
        if (store.trustedPersonCount > 0) {
            pickDue(faces, metadata, config.verifyRetryIntervalFrames) {
                it.state == VerificationState.UNKNOWN || it.state == VerificationState.PENDING
            }?.let { return RecognitionCandidate.Verify(it) }
        }

        // Priority 3: periodic re-verify of trusted tracks (ID-switch defense
        // AND pipeline self-check: same person should log high similarity here).
        pickDue(faces, metadata, config.reVerifyIntervalFrames) {
            it.state == VerificationState.TRUSTED
        }?.let { return RecognitionCandidate.ReVerify(it) }

        // Priority 4: slow recheck of rejected tracks.
        return pickDue(
            faces,
            metadata,
            config.reVerifyIntervalFrames * config.rejectedRecheckMultiplier
        ) {
            it.state == VerificationState.REJECTED
        }?.let { RecognitionCandidate.Verify(it) }
    }

    /** Oldest-checked eligible face matching [predicate] whose [interval] has elapsed. */
    private fun pickDue(
        faces: List<TrackedBox>,
        metadata: FrameMetadata,
        interval: Long,
        predicate: (TrackVerification) -> Boolean
    ): TrackedBox? = faces
        .filter { predicate(state.of(it.id)) }
        .filter { metadata.frameId - state.of(it.id).lastCheckedFrame >= interval }
        .filter { gate.canAutoCheck(it, metadata) }
        .minByOrNull { state.of(it.id).lastCheckedFrame }
}