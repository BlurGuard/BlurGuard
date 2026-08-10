package com.nash.engine.tracking.ocsort

import com.nash.core.model.DetectionBox
import com.nash.core.model.OcSortConfig

internal class TrackLifecycleManager(
    private val config: OcSortConfig,
) {
    fun predict(tracks: List<OcSortTrack>, frameId: Long) {
        tracks.forEach { it.predictTo(frameId) }
    }

    fun spawn(
        tracks: MutableList<OcSortTrack>,
        detections: List<DetectionBox>,
        frameId: Long,
        nextId: Long,
    ): Long {
        var id = nextId
        detections.forEach { detection ->
            tracks += OcSortTrack(id++, detection, frameId)
        }
        return id
    }

    fun expire(tracks: MutableList<OcSortTrack>, frameId: Long) {
        tracks.removeAll { frameId - it.lastUpdatedFrame > config.maxLostFrames }
    }
}