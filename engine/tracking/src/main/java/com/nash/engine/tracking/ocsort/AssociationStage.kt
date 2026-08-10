package com.nash.engine.tracking.ocsort

import com.nash.core.model.DetectionBox

internal data class AssociationResult(
    val matchedDetections: List<DetectionBox>,
    val matchedTracks: List<OcSortTrack>,
    val unmatchedDetections: List<DetectionBox>,
    val unmatchedTracks: List<OcSortTrack>,
)

internal class AssociationStage(
    private val costFactory: AssociationCostFactory,
) {
    fun associate(
        detections: List<DetectionBox>,
        tracks: List<OcSortTrack>,
        frameId: Long,
        mode: AssociationMode,
    ): AssociationResult {
        if (detections.isEmpty() || tracks.isEmpty()) {
            return AssociationResult(
                matchedDetections = emptyList(),
                matchedTracks = emptyList(),
                unmatchedDetections = detections,
                unmatchedTracks = tracks,
            )
        }

        val cost = costFactory.costMatrix(detections, tracks, mode)
        val assignment = HungarianSolver.solve(cost)
        val matchedDetectionIndices = mutableSetOf<Int>()
        val matchedTrackIndices = mutableSetOf<Int>()
        for (detectionIndex in assignment.indices) {
            val trackIndex = assignment[detectionIndex]
            if (trackIndex < 0 || cost[detectionIndex][trackIndex] >= HungarianSolver.FORBIDDEN / 2) continue
            tracks[trackIndex].updateWith(detections[detectionIndex], frameId)
            matchedDetectionIndices += detectionIndex
            matchedTrackIndices += trackIndex
        }

        return AssociationResult(
            matchedDetections = matchedDetectionIndices.map { detections[it] },
            matchedTracks = matchedTrackIndices.map { tracks[it] },
            unmatchedDetections = detections.filterIndexed { index, _ -> index !in matchedDetectionIndices },
            unmatchedTracks = tracks.filterIndexed { index, _ -> index !in matchedTrackIndices },
        )
    }
}