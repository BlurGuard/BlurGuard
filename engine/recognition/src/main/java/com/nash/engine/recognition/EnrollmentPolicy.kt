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
 * Owns the tap-driven enrolment transition (strict follow-up fix 4): one
 * recognizer call, then either trust the track (reusing an existing person
 * when the embedding already matches the gallery) or count a failed attempt
 * toward [RecognitionConfig.maxEnrollAttempts].
 *
 * Attempt counting lives HERE rather than in [LiveTrackRegistry] because the
 * give-up threshold is an enrolment rule, not track-lifecycle bookkeeping:
 * the registry stays transition-agnostic. The caller must invoke [onFrame]
 * every detection frame and [reset] on session reset / revoke-all so attempt
 * counters die with their tracks.
 *
 * ml-thread only.
 */
class EnrollmentPolicy<F>(
    private val recognizer: FaceRecognizer<F>,
    private val store: TrustedPersonStore,
    private val state: KeepVisibleStateStore,
    private val commands: KeepVisibleCommandQueue,
    private val liveTracks: LiveTrackRegistry,
    private val config: RecognitionConfig,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) {

    /** ml-thread only. Failed-attempt counts per live track. */
    private val enrollAttempts = HashMap<Long, Int>()

    /** Frame bookkeeping: forget attempt counts for tracks that died. */
    fun onFrame(faces: List<TrackedBox>) {
        enrollAttempts.keys.retainAll(faces.map { it.id.value }.toSet())
    }

    /** Session reset / revoke-all: forget every attempt count. */
    fun reset() {
        enrollAttempts.clear()
    }

    suspend fun enroll(frame: F, track: TrackedBox, metadata: FrameMetadata) {
        val embedding = embed(frame, track, metadata)
        if (embedding == null) {
            val attempts = (enrollAttempts[track.id.value] ?: 0) + 1
            enrollAttempts[track.id.value] = attempts
            logger.debug {
                "enroll track=${track.id.value}: null embed, " +
                        "attempt $attempts/${config.maxEnrollAttempts}"
            }
            if (attempts >= config.maxEnrollAttempts) {
                enrollAttempts.remove(track.id.value)
                commands.clearPendingEnrollment(track.id)
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
        commands.clearPendingEnrollment(track.id)
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
