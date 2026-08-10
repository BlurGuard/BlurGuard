package com.nash.engine.recognition

import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer

/**
 * Pipeline-facing half of keep-visible (strict follow-up fix 1): implements
 * ONLY [KeepVisibleRecognizer]. The UI-facing half is
 * [KeepVisibleControllerImpl]; the two meet exclusively through the shared
 * [KeepVisibleCommandQueue] and [KeepVisibleStateStore].
 *
 * Budget: at most ONE recognizer call per detection frame —
 * [RecognitionCandidateSelector] owns the priority ordering and the cheap
 * gates, the three policies own the trust transitions. Per frame this class
 * only drains pending commands, retains per-track state for live tracks, and
 * dispatches the selected candidate. No candidate is fail-closed: nothing is
 * recorded and every non-trusted face stays blurred.
 *
 * No clock is injected here: time is owned by [LiveTrackRegistry].
 *
 * Runs on the ml thread inside the pipeline's detection branch.
 */
class KeepVisibleRecognizerImpl<F>(
    private val commands: KeepVisibleCommandQueue,
    private val liveTracks: LiveTrackRegistry,
    private val selector: RecognitionCandidateSelector,
    private val enrollmentPolicy: EnrollmentPolicy<F>,
    private val verificationPolicy: VerificationPolicy<F>,
    private val reVerificationPolicy: ReVerificationPolicy<F>,
    private val state: KeepVisibleStateStore,
    private val store: TrustedPersonStore,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) : KeepVisibleRecognizer<F> {

    override fun onSessionReset() {
        commands.reset()
        enrollmentPolicy.reset()
        liveTracks.reset()
        state.clearAll()
        logger.debug { "session reset (trusted persons kept: ${store.trustedPersonCount})" }
    }

    override suspend fun onDetectionFrame(
        frame: F,
        metadata: FrameMetadata,
        boxes: List<TrackedBox>
    ) {
        if (commands.drainRevokeAll()) {
            store.revokeAll()
            state.clearAll()
            enrollmentPolicy.reset()
            liveTracks.reset()
            logger.debug { "revokeAll drained: trusted store wiped" }
        }

        val faces = boxes.filter { it.clazz == DetectionClass.FACE }
        state.retainTracks(faces.map { it.id }.toSet())
        enrollmentPolicy.onFrame(faces)
        liveTracks.onFrame(faces, metadata)

        when (val candidate = selector.select(faces, metadata)) {
            is RecognitionCandidate.Enroll ->
                enrollmentPolicy.enroll(frame, candidate.track, metadata)
            is RecognitionCandidate.Verify ->
                verificationPolicy.verify(frame, candidate.track, metadata)
            is RecognitionCandidate.ReVerify ->
                reVerificationPolicy.reVerify(frame, candidate.track, metadata)
            null -> Unit // nothing due or everything gated: fail-closed, stay blurred
        }
    }
}
