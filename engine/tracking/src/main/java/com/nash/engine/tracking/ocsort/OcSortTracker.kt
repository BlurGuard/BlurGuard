package com.nash.engine.tracking.ocsort

import com.nash.core.model.DetectionBox
import com.nash.core.model.FrameMetadata
import com.nash.core.model.OcSortConfig
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import javax.inject.Inject
import javax.inject.Singleton

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

    private val tracks = mutableListOf<OcSortTrack>()
    private val partitioner = DetectionPartitioner(config)
    private val associationStage = AssociationStage(AssociationCostFactory(config))
    private val lifecycle = TrackLifecycleManager(config)
    private var nextId = 1L

    override fun update(
        detections: List<DetectionBox>,
        metadata: FrameMetadata
    ): List<TrackedBox> {
        val frameId = metadata.frameId
        lifecycle.predict(tracks, frameId)

        val partitions = partitioner.partition(detections)

        val association = associateDetections(partitions, frameId)

        // Leftover high-score detections spawn new tracks (low-score never do).
        nextId = lifecycle.spawn(tracks, association.unmatchedDetections, frameId, nextId)

        // Same expiry rule as ByteTrackTracker: coast, then drop.
        lifecycle.expire(tracks, frameId)

        return tracks.map { it.toTrackedBox() }
    }

    private fun associateDetections(
        partitions: DetectionPartitions,
        frameId: Long,
    ): AssociationResult {
        // Stage 1 — high-score detections vs all tracks: IoU + OCM, Hungarian.
        val firstStage = associationStage.associate(
            detections = partitions.highScore,
            tracks = tracks,
            frameId = frameId,
            mode = AssociationMode(useOcm = true, iouThreshold = config.iouThreshold) { it.predictedBox() },
        )
        // Stage 2 — BYTE: low-score detections re-confirm leftover tracks (IoU only).
        val secondStage = associationStage.associate(
            detections = partitions.lowScore,
            tracks = firstStage.unmatchedTracks,
            frameId = frameId,
            mode = AssociationMode(useOcm = false, iouThreshold = config.iouThreshold) { it.predictedBox() },
        )
        // Stage 3 — OCR: leftover high detections vs still-lost tracks, on the
        // last OBSERVED box. Rescues tracks whose prediction drifted while lost.
        return associationStage.associate(
            detections = firstStage.unmatchedDetections,
            tracks = secondStage.unmatchedTracks,
            frameId = frameId,
            mode = AssociationMode(useOcm = false, iouThreshold = config.ocrIouThreshold) { it.lastObservation },
        )
    }

    override fun predict(metadata: FrameMetadata): List<TrackedBox> {
        val frameId = metadata.frameId
        lifecycle.predict(tracks, frameId)
        // A stalled detector must not leave ghost boxes coasting forever.
        lifecycle.expire(tracks, frameId)
        return tracks.map { it.toTrackedBox() }
    }

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

}
