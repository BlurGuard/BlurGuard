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
 * Owns the verify transition for unknown/pending/rejected tracks (strict
 * follow-up fix 4): one recognizer call, then either build consecutive-match
 * confidence toward trust or count a mismatch toward rejection (F2 fix:
 * hysteresis — never hard-reject on a single noisy embed).
 *
 * Fail-closed: a null embedding records no decision and the track stays at
 * its current (blurred) state.
 *
 * ml-thread only.
 */
class VerificationPolicy<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val liveTracks: LiveTrackRegistry,
    private val config: RecognitionConfig,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) {

    suspend fun verify(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val current = state.of(track.id)
        val embedding = embed(frame, track, metadata)
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