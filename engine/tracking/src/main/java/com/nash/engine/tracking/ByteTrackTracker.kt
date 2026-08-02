package com.nash.engine.tracking

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import com.nash.core.model.TrackerConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ByteTrack-style multi-object tracker (FR-06).
 *
 * Core idea of ByteTrack: don't throw away low-confidence detections. Matching
 * happens in two stages:
 *  1. High-score detections are matched to existing tracks by IoU.
 *  2. Low-score detections (usually occluded / motion-blurred objects) are
 *     matched to the tracks left over from stage 1 — they can KEEP a track
 *     alive but never CREATE one.
 *
 * Simplifications vs. the paper, chosen deliberately for the MVP:
 *  - Greedy IoU matching instead of the Hungarian algorithm (near-identical
 *    results at the few-objects-per-frame scale of this app, much simpler).
 *  - Constant-velocity linear motion model instead of a Kalman filter
 *    (sufficient for interpolation between subsampled detections; swappable
 *    later behind the same [Tracker] interface).
 *
 * Privacy behavior: a track that stops being detected keeps being emitted
 * (coasting on its predicted position) for [TrackerConfig.maxLostFrames], so
 * the blur persists through short misses — a false "keep blurring" is always
 * safer than a false "stop blurring".
 *
 * Tracks are per-class: a FACE detection never matches a LICENSE_PLATE track.
 *
 * Not thread-safe by design — the pipeline calls [update] serially from the
 * single-parallelism ml dispatcher.
 */
@Singleton
class ByteTrackTracker @Inject constructor(
    private val config: TrackerConfig
) : Tracker {

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1L

    override fun update(
        detections: List<DetectionBox>,
        metadata: FrameMetadata
    ): List<TrackedBox> {
        val frameId = metadata.frameId

        // 1. Advance every live track to this frame with the motion model.
        tracks.forEach { it.predictTo(frameId) }

        // 2. ByteTrack split.
        val (high, lowRaw) = detections.partition { it.confidence >= config.highScoreThreshold }
        val low = lowRaw.filter { it.confidence >= config.lowScoreThreshold }

        // 3. Stage 1: high-score detections vs. all tracks.
        val stage1 = associate(tracks, high)

        // 4. Stage 2: low-score detections vs. tracks unmatched in stage 1.
        val stage2 = associate(stage1.unmatchedTracks, low)

        // 5. Apply matches.
        (stage1.matches + stage2.matches).forEach { (track, detection) ->
            track.updateWith(detection, frameId, config.velocitySmoothing)
        }

        // 6. Unmatched HIGH-score detections found a new object.
        //    (Unmatched low-score detections are discarded — likely noise.)
        stage1.unmatchedDetections.forEach { detection ->
            tracks += MutableTrack(
                id = nextId++,
                box = detection.box,
                clazz = detection.clazz,
                confidence = detection.confidence,
                lastUpdatedFrame = frameId
            )
        }

        // 7. Kill tracks that have been lost for too long.
        tracks.removeAll { frameId - it.lastUpdatedFrame > config.maxLostFrames }

        // 8. Emit every live track, including coasting ones (privacy-first:
        //    unmatched tracks keep their blur at the predicted position).
        return tracks.map { it.toTrackedBox() }
    }

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

    override fun predict(metadata: FrameMetadata): List<TrackedBox> {
        val frameId = metadata.frameId
        tracks.forEach { it.predictTo(frameId) }
        // Same expiry rule as update(): a stalled detector must not leave
        // ghost boxes coasting forever.
        tracks.removeAll { frameId - it.lastUpdatedFrame > config.maxLostFrames }
        return tracks.map { it.toTrackedBox() }
    }


    // ------------------------------------------------------------------
    // Association
    // ------------------------------------------------------------------

    private class Association(
        val matches: List<Pair<MutableTrack, DetectionBox>>,
        val unmatchedTracks: List<MutableTrack>,
        val unmatchedDetections: List<DetectionBox>
    )

    /** Greedy IoU matching: best IoU pairs first, each track/detection used once. */
    private fun associate(
        candidates: List<MutableTrack>,
        detections: List<DetectionBox>
    ): Association {
        if (candidates.isEmpty() || detections.isEmpty()) {
            return Association(emptyList(), candidates, detections)
        }

        class ScoredPair(val trackIndex: Int, val detectionIndex: Int, val iou: Float)

        val scoredPairs = ArrayList<ScoredPair>()
        candidates.forEachIndexed { t, track ->
            detections.forEachIndexed { d, detection ->
                if (track.clazz == detection.clazz) {
                    val overlap = iou(track.box, detection.box)
                    if (overlap >= config.iouThreshold) {
                        scoredPairs += ScoredPair(t, d, overlap)
                    }
                }
            }
        }
        scoredPairs.sortByDescending { it.iou }

        val matchedTracks = BooleanArray(candidates.size)
        val matchedDetections = BooleanArray(detections.size)
        val matches = ArrayList<Pair<MutableTrack, DetectionBox>>()

        for (pair in scoredPairs) {
            if (matchedTracks[pair.trackIndex] || matchedDetections[pair.detectionIndex]) continue
            matchedTracks[pair.trackIndex] = true
            matchedDetections[pair.detectionIndex] = true
            matches += candidates[pair.trackIndex] to detections[pair.detectionIndex]
        }

        return Association(
            matches = matches,
            unmatchedTracks = candidates.filterIndexed { i, _ -> !matchedTracks[i] },
            unmatchedDetections = detections.filterIndexed { i, _ -> !matchedDetections[i] }
        )
    }

    // ------------------------------------------------------------------
    // Internal mutable track state
    // ------------------------------------------------------------------

    private class MutableTrack(
        val id: Long,
        var box: BoundingBox,
        val clazz: DetectionClass,
        var confidence: Float,
        var lastUpdatedFrame: Long
    ) {
        /** Frame the box position has been predicted to (avoids double-shifting). */
        private var predictedFrame: Long = lastUpdatedFrame

        /** Normalized units per frame (constant-velocity model). */
        private var velocityX = 0f
        private var velocityY = 0f

        fun predictTo(frameId: Long) {
            val gap = frameId - predictedFrame
            if (gap <= 0) return
            // Clamp the shift so the box never inverts or leaves [0, 1].
            val dx = (velocityX * gap).coerceIn(-box.left, 1f - box.right)
            val dy = (velocityY * gap).coerceIn(-box.top, 1f - box.bottom)
            box = BoundingBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            predictedFrame = frameId
        }

        fun updateWith(detection: DetectionBox, frameId: Long, smoothing: Float) {
            val gap = (frameId - lastUpdatedFrame).coerceAtLeast(1L).toFloat()
            val rawVx = (detection.box.centerX - box.centerX) / gap
            val rawVy = (detection.box.centerY - box.centerY) / gap
            velocityX = smoothing * velocityX + (1f - smoothing) * rawVx
            velocityY = smoothing * velocityY + (1f - smoothing) * rawVy

            box = detection.box
            confidence = detection.confidence
            lastUpdatedFrame = frameId
            predictedFrame = frameId
        }

        fun toTrackedBox() = TrackedBox(
            id = TrackId(id),
            box = box,
            clazz = clazz,
            confidence = confidence,
            lastUpdatedFrame = lastUpdatedFrame,
            keepVisible = false // keep-visible decisions belong to core/domain + core/recognition
        )
    }

    private companion object {
        fun iou(a: BoundingBox, b: BoundingBox): Float {
            val interLeft = maxOf(a.left, b.left)
            val interTop = maxOf(a.top, b.top)
            val interRight = minOf(a.right, b.right)
            val interBottom = minOf(a.bottom, b.bottom)

            val interWidth = (interRight - interLeft).coerceAtLeast(0f)
            val interHeight = (interBottom - interTop).coerceAtLeast(0f)
            val intersection = interWidth * interHeight
            if (intersection <= 0f) return 0f

            val union = a.width * a.height + b.width * b.height - intersection
            return if (union <= 0f) 0f else intersection / union
        }
    }
}
