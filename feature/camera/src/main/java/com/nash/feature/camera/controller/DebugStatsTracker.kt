package com.nash.feature.camera.controller

import com.nash.core.model.TrackedBox
import com.nash.feature.camera.state.CameraDebugStatsUiModel

/**
 * Accumulates unique track IDs for the debug HUD.
 * Not thread-safe by design: call from a single collector coroutine.
 */
class DebugStatsTracker {

    private val seenIds = mutableSetOf<Long>()

    fun onTrackedBoxes(boxes: List<TrackedBox>): CameraDebugStatsUiModel {
        boxes.forEach { seenIds += it.id.value }
        return CameraDebugStatsUiModel(active = boxes.size, totalSeen = seenIds.size)
    }

    fun reset(): CameraDebugStatsUiModel {
        seenIds.clear()
        return CameraDebugStatsUiModel()
    }
}
