package com.nash.engine.recognition

import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackedBox

/**
 * Per-track bookkeeping for the keep-visible recognizer: which face tracks
 * are alive, how long each has been on screen, and when each last consumed
 * a recognition attempt (strict follow-up fix 2: no raw mutable maps inside
 * the recognizer).
 *
 * Also owns the two cheap, purely arithmetic gates derived from that
 * bookkeeping: track stability ([isStableEnough]) and the per-track attempt
 * interval ([isIntervalElapsed]). ml-thread only, like the maps it absorbed.
 *
 * @param nowMs monotonic millisecond clock (TimeProvider-backed in
 * production, a fake in tests). Captured ONCE per frame in [onFrame] so
 * every gate decision within one frame sees the same instant.
 */
class LiveTrackRegistry(
    private val config: RecognitionConfig,
    private val nowMs: () -> Long,
) {

    /** First frame each live track was seen on (age gate). */
    private val firstSeenFrame = HashMap<Long, Long>()

    /** Clock of the last embed attempt per track. */
    private val lastRecognizedAtMs = HashMap<Long, Long>()

    /** Clock captured at the top of the current frame. */
    private var frameNowMs = 0L

    /**
     * Per-frame bookkeeping: forget tracks the tracker no longer reports,
     * stamp first-seen for new ones, capture the frame clock. Call exactly
     * once per detection frame, before any gate is consulted.
     */
    fun onFrame(faces: List<TrackedBox>, metadata: FrameMetadata) {
        val liveIds = faces.map { it.id.value }.toSet()
        firstSeenFrame.keys.retainAll(liveIds)
        lastRecognizedAtMs.keys.retainAll(liveIds)
        faces.forEach { firstSeenFrame.putIfAbsent(it.id.value, metadata.frameId) }
        frameNowMs = nowMs()
    }

    /**
     * Has [RecognitionConfig.minRecognitionIntervalMs] elapsed since
     * [track]'s last attempt? Never-attempted tracks pass immediately.
     */
    fun isIntervalElapsed(track: TrackedBox): Boolean {
        val last = lastRecognizedAtMs[track.id.value] ?: return true
        return frameNowMs - last >= config.minRecognitionIntervalMs
    }

    /** Confident and on screen long enough to be worth an embedding. */
    fun isStableEnough(track: TrackedBox, metadata: FrameMetadata): Boolean {
        if (track.confidence < config.minTrackConfidence) return false
        val firstSeen = firstSeenFrame[track.id.value] ?: return false
        return metadata.frameId - firstSeen >= config.minTrackAgeFrames
    }

    /**
     * Stamps [track]'s attempt clock. Called BEFORE the expensive embed so
     * a slow or failed pass still counts against the interval budget.
     */
    fun markRecognitionAttempt(track: TrackedBox) {
        lastRecognizedAtMs[track.id.value] = frameNowMs
    }

    /** Session reset / revoke-all: forget every live track. */
    fun reset() {
        firstSeenFrame.clear()
        lastRecognizedAtMs.clear()
    }
}
