package com.nash.engine.recognition

import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackedBox

/**
 * Cheap, purely arithmetic gates standing between a detected face and the
 * expensive recognizer call (strict follow-up fix 3: gating is its own
 * responsibility, not recognizer internals).
 *
 * Failing a gate is always fail-closed: no decision is recorded and the
 * face stays blurred. ml-thread only.
 *
 * @param liveTracks supplies the stability and attempt-interval gates that
 * depend on per-track bookkeeping; this class adds the geometry gate and
 * the composed auto-path check.
 */
class RecognitionGate(
    private val config: RecognitionConfig,
    private val liveTracks: LiveTrackRegistry,
) {

    /** All cheap gates for the automatic (non-tap) paths. Pure arithmetic. */
    fun canAutoCheck(track: TrackedBox, metadata: FrameMetadata): Boolean =
        liveTracks.isIntervalElapsed(track) &&
                isLargeEnough(track, metadata) &&
                liveTracks.isStableEnough(track, metadata)

    /**
     * Box size in UPRIGHT pixels, on both axes.
     *
     * [FrameMetadata.width] and [FrameMetadata.height] describe the raw buffer,
     * but [TrackedBox.box] is normalized against the upright frame — the same
     * space the recognizer crops in after rotating. At 90/270 those two are
     * transposed, so the dimensions must be swapped before measuring, or the
     * height of a face is compared against the width of the frame.
     */
    fun isLargeEnough(track: TrackedBox, metadata: FrameMetadata): Boolean {
        val transposed = metadata.rotationDegrees % 180 != 0
        val uprightWidth = if (transposed) metadata.height else metadata.width
        val uprightHeight = if (transposed) metadata.width else metadata.height
        val widthPx = (track.box.right - track.box.left) * uprightWidth
        val heightPx = (track.box.bottom - track.box.top) * uprightHeight
        return widthPx >= config.minFaceBoxPx && heightPx >= config.minFaceBoxPx
    }
}