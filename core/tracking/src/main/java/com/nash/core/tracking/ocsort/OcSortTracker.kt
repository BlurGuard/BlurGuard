package com.nash.core.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.OcSortConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * OC-SORT tracker: Kalman motion model + Hungarian assignment, with the three
 * observation-centric mechanisms from the paper:
 *
 *  - OCM (momentum): the association cost adds a direction-consistency term so
 *    a detection that continues a track's *observed* motion beats one that
 *    contradicts it — the main defense against ID switches when people cross.
 *  - ORU (re-update): when a lost track is recovered, the filter is rolled back
 *    to its last observation and re-run along a virtual linear trajectory to
 *    the new one, so the velocity estimate doesn't carry error accumulated
 *    while coasting blind.
 *  - OCR (recovery): a last-resort association of unmatched detections against
 *    a lost track's last *observed* box (predictions drift; observations don't).
 *
 * BlurGuard invariants kept from ByteTrackTracker:
 *  - BYTE second stage: low-score detections may re-confirm tracks, never spawn.
 *  - Privacy coasting: lost tracks keep reporting (and being blurred) until
 *    maxLostFrames, in both update() and predict().
 *  - keepVisible is NOT decided here; core/domain decorates it downstream.
 *
 * Single-threaded by design: only ever touched from the ml dispatcher.
 */
@Singleton
class OcSortTracker @Inject constructor(
    private val config: OcSortConfig
) : Tracker {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1L

    override fun update(
        detections: List<DetectionBox>,
        metadata: FrameMetadata
    ): List<TrackedBox> {
        val frameId = metadata.frameId
        tracks.forEach { it.predictTo(frameId) }

        val unmatchedHigh = detections
            .filter { it.confidence >= config.highScoreThreshold }
            .toMutableList()
        val unmatchedLow = detections
            .filter { it.confidence >= config.lowScoreThreshold && it.confidence < config.highScoreThreshold }
            .toMutableList()
        val unmatchedTracks = tracks.toMutableList()

        // Stage 1 — high-score detections vs all tracks: IoU + OCM, Hungarian.
        associate(unmatchedHigh, unmatchedTracks, frameId, useOcm = true, config.iouThreshold) {
            it.predictedBox()
        }
        // Stage 2 — BYTE: low-score detections re-confirm leftover tracks (IoU only).
        associate(unmatchedLow, unmatchedTracks, frameId, useOcm = false, config.iouThreshold) {
            it.predictedBox()
        }
        // Stage 3 — OCR: leftover high detections vs still-lost tracks, on the
        // last OBSERVED box. Rescues tracks whose prediction drifted while lost.
        associate(unmatchedHigh, unmatchedTracks, frameId, useOcm = false, config.ocrIouThreshold) {
            it.lastObservation
        }

        // Leftover high-score detections spawn new tracks (low-score never do).
        unmatchedHigh.forEach { detection ->
            tracks += Track(nextId++, detection, frameId)
        }

        // Same expiry rule as ByteTrackTracker: coast, then drop.
        tracks.removeAll { frameId - it.lastUpdatedFrame > config.maxLostFrames }

        return tracks.map { it.toTrackedBox() }
    }

    override fun predict(metadata: FrameMetadata): List<TrackedBox> {
        val frameId = metadata.frameId
        tracks.forEach { it.predictTo(frameId) }
        // A stalled detector must not leave ghost boxes coasting forever.
        tracks.removeAll { frameId - it.lastUpdatedFrame > config.maxLostFrames }
        return tracks.map { it.toTrackedBox() }
    }

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

    // ------------------------------------------------------------------ //

    private fun associate(
        detections: MutableList<DetectionBox>,
        candidates: MutableList<Track>,
        frameId: Long,
        useOcm: Boolean,
        iouThreshold: Float,
        boxOf: (Track) -> BoundingBox
    ) {
        if (detections.isEmpty() || candidates.isEmpty()) return

        val cost = Array(detections.size) { d ->
            DoubleArray(candidates.size) { t ->
                val det = detections[d]
                val track = candidates[t]
                val overlap = iou(det.box, boxOf(track))
                if (det.clazz != track.clazz || overlap < iouThreshold) {
                    HungarianSolver.FORBIDDEN
                } else {
                    (1.0 - overlap) +
                            if (useOcm) config.ocmWeight * directionCost(track, det) else 0.0
                }
            }
        }

        val assignment = HungarianSolver.solve(cost)
        val matchedDetections = mutableListOf<Int>()
        val matchedTracks = mutableListOf<Track>()
        for (d in assignment.indices) {
            val t = assignment[d]
            if (t < 0 || cost[d][t] >= HungarianSolver.FORBIDDEN / 2) continue
            candidates[t].updateWith(detections[d], frameId)
            matchedDetections += d
            matchedTracks += candidates[t]
        }
        matchedDetections.sortedDescending().forEach { detections.removeAt(it) }
        candidates.removeAll(matchedTracks)
    }

    /**
     * OCM term: angle between the track's observed motion direction and the
     * direction implied by accepting this detection, normalized to [0, 1].
     */
    private fun directionCost(track: Track, detection: DetectionBox): Double {
        val previous = track.previousObservation ?: return 0.0
        val v1x = (track.lastObservation.centerX - previous.centerX).toDouble()
        val v1y = (track.lastObservation.centerY - previous.centerY).toDouble()
        val v2x = (detection.box.centerX - track.lastObservation.centerX).toDouble()
        val v2y = (detection.box.centerY - track.lastObservation.centerY).toDouble()
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        // A (near-)static track has no meaningful direction; don't penalize.
        if (n1 < 1e-6 || n2 < 1e-6) return 0.0
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return acos(cos) / PI
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        if (intersection <= 0f) return 0.0
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return intersection.toDouble() / (areaA + areaB - intersection).toDouble()
    }

    // ------------------------------------------------------------------ //

    private class Track(
        val id: Long,
        detection: DetectionBox,
        frameId: Long
    ) {
        val clazz: DetectionClass = detection.clazz
        val kf = KalmanBoxFilter(detection.box.toMeasurement())
        var confidence = detection.confidence
        var lastUpdatedFrame = frameId
        var predictedFrame = frameId

        /** OC-SORT trusts observations over filter state; keep the real ones. */
        var lastObservation: BoundingBox = detection.box
        var lastObservationFrame: Long = frameId
        var previousObservation: BoundingBox? = null

        /** Filter snapshot at the last real observation — rollback point for ORU. */
        private var frozenState = kf.stateSnapshot()
        private var frozenCovariance = kf.covarianceSnapshot()

        fun predictTo(frameId: Long) {
            val gap = frameId - predictedFrame
            if (gap <= 0) return
            repeat(gap.toInt()) { kf.predict() }
            predictedFrame = frameId
        }

        fun updateWith(detection: DetectionBox, frameId: Long) {
            val gap = frameId - lastObservationFrame
            if (gap > 1) {
                // ORU: rollback to the last observation, then re-run the filter
                // along a virtual linear trajectory to the new observation, so
                // blind-coasting error doesn't poison the velocity estimate.
                kf.restore(frozenState, frozenCovariance)
                val zLast = lastObservation.toMeasurement()
                val zNew = detection.box.toMeasurement()
                for (step in 1..gap) {
                    kf.predict()
                    val t = step.toDouble() / gap
                    kf.update(DoubleArray(4) { zLast[it] + (zNew[it] - zLast[it]) * t })
                }
            } else {
                kf.update(detection.box.toMeasurement())
            }

            previousObservation = lastObservation
            lastObservation = detection.box
            lastObservationFrame = frameId
            frozenState = kf.stateSnapshot()
            frozenCovariance = kf.covarianceSnapshot()

            confidence = detection.confidence
            lastUpdatedFrame = frameId
            predictedFrame = frameId
        }

        /** Filter estimate, clamped to the frame; falls back to the last observation. */
        fun predictedBox(): BoundingBox {
            val state = kf.boxState() ?: return lastObservation
            val (cx, cy, w, h) = state
            val left = (cx - w / 2).toFloat().coerceIn(0f, 1f)
            val top = (cy - h / 2).toFloat().coerceIn(0f, 1f)
            val right = (cx + w / 2).toFloat().coerceIn(0f, 1f)
            val bottom = (cy + h / 2).toFloat().coerceIn(0f, 1f)
            // Fail-closed: a degenerate estimate must not un-blur someone.
            if (right - left < 1e-4f || bottom - top < 1e-4f) return lastObservation
            return BoundingBox(left, top, right, bottom)
        }

        fun toTrackedBox() = TrackedBox(
            id = TrackId(id),
            box = predictedBox(),
            clazz = clazz,
            confidence = confidence,
            lastUpdatedFrame = lastUpdatedFrame
        )

        private operator fun DoubleArray.component4() = this[3]
    }

    private companion object {
        fun BoundingBox.toMeasurement(): DoubleArray {
            val w = (right - left).coerceAtLeast(1e-6f).toDouble()
            val h = (bottom - top).coerceAtLeast(1e-6f).toDouble()
            return doubleArrayOf(centerX.toDouble(), centerY.toDouble(), w * h, w / h)
        }
    }
}