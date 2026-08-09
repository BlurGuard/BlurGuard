package com.nash.engine.recognition

import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer

/**
 * Person-level keep-visible trust. Runs on the ml thread inside the pipeline's
 * detection branch; UI threads only touch the lock-free [KeepVisibleCommandQueue]
 * via [KeepVisibleController].
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
 * @param nowMs monotonic millisecond clock, injectable so cadence is testable
 * on the JVM; production passes a TimeProvider-backed source from DI.
 * @param liveTracks per-track bookkeeping (age, attempt clock) and the
 * stability/interval gates derived from it.
 * @param gate cheap arithmetic gates (geometry plus the composed auto-path
 * check) guarding the expensive recognizer path.
 * @param logger logging seam; production wires an Android-backed
 * implementation in DI, JVM tests default to [RecognitionLogger.None].
 * @param selector priority policy: picks the at-most-one recognition
 * candidate for each detection frame.
 * @param enrollmentPolicy tap-driven enrolment transition, including the
 * give-up attempt counter.
 * @param verificationPolicy unknown/pending/rejected verify transition.
 * @param reVerificationPolicy periodic trusted re-check transition.
 */
class KeepVisibleOrchestrator<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val config: RecognitionConfig,
    private val commands: KeepVisibleCommandQueue = KeepVisibleCommandQueue(),
    private val nowMs: () -> Long,
    private val liveTracks: LiveTrackRegistry = LiveTrackRegistry(config, nowMs),
    private val gate: RecognitionGate = RecognitionGate(config, liveTracks),
    private val logger: RecognitionLogger = RecognitionLogger.None,
    private val selector: RecognitionCandidateSelector = RecognitionCandidateSelector(
        commands, liveTracks, gate, state, store, config, logger
    ),
    private val enrollmentPolicy: EnrollmentPolicy<F> = EnrollmentPolicy(
        recognizer, store, state, commands, liveTracks, config, logger
    ),
    private val verificationPolicy: VerificationPolicy<F> = VerificationPolicy(
        recognizer, store, state, liveTracks, config, logger
    ),
    private val reVerificationPolicy: ReVerificationPolicy<F> = ReVerificationPolicy(
        recognizer, store, state, liveTracks, config, logger
    ),
) : KeepVisibleController, KeepVisibleRecognizer<F> {

    override fun requestKeepVisible(trackId: TrackId) {
        logger.debug { "tap: keep-visible requested for track=${trackId.value}" }
        commands.requestEnrollment(trackId)
    }

    override fun revokeAll() {
        logger.debug { "revokeAll requested" }
        state.clearAll() // instant visual re-blur
        commands.requestRevokeAll() // store wipe drained on ml thread
    }

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