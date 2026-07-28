package com.nash.core.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.OcSortConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OcSortTrackerTest {

    private val config = OcSortConfig()


    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0,
        timestampNanos = frameId * 33,

    )

    private fun det(
        cx: Float, cy: Float, w: Float = 0.1f, h: Float = 0.15f,
        conf: Float = 0.9f, clazz: DetectionClass = DetectionClass.FACE
    ) = DetectionBox(
        box = BoundingBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
        clazz = clazz,
        confidence = conf
    )

    @Test
    fun `two crossing targets keep their ids`() {
        val tracker = OcSortTracker(config)
        // A moves right, B moves left, same row; they meet around x = 0.5.
        var frame = 0L
        var lastA = -1L
        var lastB = -1L
        // Warm up so both tracks have observed direction (OCM needs history).
        for (step in 0..20) {
            val xa = 0.2f + step * 0.03f            // A: left -> right
            val xb = 0.8f - step * 0.03f            // B: right -> left
            val result = tracker.update(listOf(det(xa, 0.5f), det(xb, 0.5f)), metadata(frame))
            assertEquals(2, result.size)
            val a = result.minByOrNull { abs(it.box.centerX - xa) }!!
            val b = result.first { it !== a }
            if (step == 0) { lastA = a.id.value; lastB = b.id.value }
            assertEquals("A swapped id at step $step", lastA, a.id.value)
            assertEquals("B swapped id at step $step", lastB, b.id.value)
            frame += 3 // detection cadence: every 3rd frame
        }
        assertNotEquals(lastA, lastB)
    }

    @Test
    fun `track recovered after occlusion keeps id and sane velocity (ORU)`() {
        val tracker = OcSortTracker(config)
        // Constant motion, then 4 missed detection ticks, then reappears on-path.
        var frame = 0L
        var id = -1L
        for (step in 0..4) {
            val result = tracker.update(listOf(det(0.2f + step * 0.02f, 0.5f)), metadata(frame))
            id = result.single().id.value
            frame += 3
        }
        repeat(4) { // lost: detector returns nothing, track coasts (still reported)
            val coasting = tracker.update(emptyList(), metadata(frame))
            assertEquals("must keep blurring while lost", 1, coasting.size)
            frame += 3
        }
        val reappearX = 0.2f + 9 * 0.02f // back on the linear path
        val recovered = tracker.update(listOf(det(reappearX, 0.5f)), metadata(frame)).single()
        assertEquals("recovery must keep the id", id, recovered.id.value)
        // After ORU the estimate should sit on the observation, not drifted state.
        assertTrue(abs(recovered.box.centerX - reappearX) < 0.02f)
    }

    @Test
    fun `lost track expires after maxLostFrames`() {
        val tracker = OcSortTracker(config)
        tracker.update(listOf(det(0.5f, 0.5f)), metadata(0))
        // Within budget: still reported (privacy coasting).
        assertEquals(1, tracker.predict(metadata(config.maxLostFrames)).size)
        // Past budget: dropped.
        assertEquals(0, tracker.predict(metadata(config.maxLostFrames + 1)).size)
    }

    @Test
    fun `low score detection reconfirms but never spawns`() {
        val tracker = OcSortTracker(config)
        tracker.update(listOf(det(0.3f, 0.5f, conf = 0.9f)), metadata(0))
        // Low-score det overlapping the track re-confirms it...
        val result = tracker.update(listOf(det(0.31f, 0.5f, conf = 0.2f)), metadata(3))
        assertEquals(1, result.size)
        assertEquals(3L, result.single().lastUpdatedFrame)
        // ...but a low-score det with no track never creates one.
        val spawned = tracker.update(listOf(det(0.8f, 0.8f, conf = 0.2f)), metadata(6))
        assertEquals(1, spawned.size) // still only the original track
    }

    @Test
    fun `face detection never matches plate track`() {
        val tracker = OcSortTracker(config)
        tracker.update(listOf(det(0.5f, 0.5f, clazz = DetectionClass.LICENSE_PLATE)), metadata(0))
        val result = tracker.update(
            listOf(det(0.5f, 0.5f, clazz = DetectionClass.FACE)), metadata(3)
        )
        // Same spot, different class: plate track coasts, face spawns fresh.
        assertEquals(2, result.size)
    }
}