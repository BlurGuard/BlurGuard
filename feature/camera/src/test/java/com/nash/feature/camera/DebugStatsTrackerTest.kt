package com.nash.feature.camera

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.feature.camera.controller.DebugStatsTracker
import com.nash.feature.camera.state.CameraDebugStatsUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugStatsTrackerTest {

    private fun box(id: Long) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f),
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )

    @Test
    fun `accumulates unique ids across emissions`() {
        val tracker = DebugStatsTracker()

        assertEquals(
            CameraDebugStatsUiModel(active = 2, totalSeen = 2),
            tracker.onTrackedBoxes(listOf(box(1), box(2)))
        )
        // Overlapping + new id: totalSeen grows, active reflects current frame.
        assertEquals(
            CameraDebugStatsUiModel(active = 2, totalSeen = 3),
            tracker.onTrackedBoxes(listOf(box(2), box(3)))
        )
        // Empty frame: active drops, totalSeen persists.
        assertEquals(
            CameraDebugStatsUiModel(active = 0, totalSeen = 3),
            tracker.onTrackedBoxes(emptyList())
        )
    }

    @Test
    fun `reset clears seen ids`() {
        val tracker = DebugStatsTracker()
        tracker.onTrackedBoxes(listOf(box(1), box(2)))

        assertEquals(CameraDebugStatsUiModel(), tracker.reset())
        assertEquals(
            CameraDebugStatsUiModel(active = 1, totalSeen = 1),
            tracker.onTrackedBoxes(listOf(box(7)))
        )
    }
}