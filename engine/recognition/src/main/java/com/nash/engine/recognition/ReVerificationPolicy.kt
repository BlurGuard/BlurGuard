package com.nash.engine.recognition

import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.VerificationState

/**
 * Owns the periodic re-check transition for trusted tracks (strict follow-up
 * fix 4): ID-switch defense AND pipeline self-check — the same person should
 * log comfortably above-threshold similarity here. Revocation is hysteretic
 * and fail-closed, mirroring the verify path.
 *
 * ml-thread only.
 */
class ReVerificationPolicy<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val liveTracks: LiveTrackRegistry,
    private val config: RecognitionConfig,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) {

    suspend fun reVerify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata)
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
                "reVerify track=${track.id.value}: OK person=${match!!.personId.value} " +
                        "sim=${fmt(match.similarity)}"
            }
            store.addToGallery(match!!.personId, embedding)
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

    /**
     * Stamps the attempt clock BEFORE the recognizer call so a slow or failed
     * pass still counts against the interval budget.
     */
    private suspend fun embed(
        frame: F,
        track: TrackedBox,
        metadata: FrameMetadata
    ): FaceEmbedding? {
        liveTracks.markRecognitionAttempt(track)
        return recognizer.embed(frame, track.box, metadata)
    }

    private fun fmt(v: Float) = "%.3f".format(v)
}