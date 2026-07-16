package com.nash.core.tracking

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackTrackerTest {

    private fun tracker(config: TrackerConfig = TrackerConfig()) = ByteTrackTracker(config)

    private fun meta(frameId: Long) = FrameMetadata(
        frameId = frameId,
        timestampNanos = frameId * 33_000_000L,
        width = 640,
        height = 480,
        rotationDegrees = 0
    )

    private fun det(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.9f,
        clazz: DetectionClass = DetectionClass.FACE
    ) = DetectionBox(BoundingBox(left, top, right, bottom), clazz, confidence)

    @Test
    fun `same object keeps the same id across frames`() {
        val tracker = tracker()

        val frame1 = tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f)), meta(1))
        val frame2 = tracker.update(listOf(det(0.12f, 0.11f, 0.32f, 0.31f)), meta(2))

        assertEquals(1, frame1.size)
        assertEquals(1, frame2.size)
        assertEquals(frame1.first().id, frame2.first().id)
    }

    @Test
    fun `two separate objects get distinct ids`() {
        val tracker = tracker()

        val result = tracker.update(
            listOf(
                det(0.1f, 0.1f, 0.3f, 0.3f),
                det(0.6f, 0.6f, 0.8f, 0.8f)
            ),
            meta(1)
        )

        assertEquals(2, result.size)
        assertNotEquals(result[0].id, result[1].id)
    }

    @Test
    fun `low score detection keeps an existing track alive`() {
        val tracker = tracker()

        val frame1 = tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f, confidence = 0.9f)), meta(1))
        // Occluded/blurred: confidence drops below highScoreThreshold (0.6).
        val frame2 = tracker.update(listOf(det(0.11f, 0.1f, 0.31f, 0.3f, confidence = 0.3f)), meta(2))

        assertEquals(frame1.first().id, frame2.first().id)
        assertEquals(2L, frame2.first().lastUpdatedFrame)
    }

    @Test
    fun `low score detection alone never creates a track`() {
        val tracker = tracker()

        val result = tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f, confidence = 0.3f)), meta(1))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `lost track keeps coasting then dies after maxLostFrames`() {
        val config = TrackerConfig(maxLostFrames = 5)
        val tracker = tracker(config)

        tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f)), meta(1))

        // No detections for a few frames: track must still be emitted (blur persists).
        val coasting = tracker.update(emptyList(), meta(4))
        assertEquals(1, coasting.size)
        assertEquals(1L, coasting.first().lastUpdatedFrame)

        // Past maxLostFrames: track must be gone.
        val dead = tracker.update(emptyList(), meta(8))
        assertTrue(dead.isEmpty())
    }

    @Test
    fun `face detection never matches a plate track`() {
        val tracker = tracker()

        val frame1 = tracker.update(
            listOf(det(0.1f, 0.1f, 0.3f, 0.3f, clazz = DetectionClass.LICENSE_PLATE)),
            meta(1)
        )
        // Same location, different class: must spawn a NEW track, not steal the plate's id.
        val frame2 = tracker.update(
            listOf(det(0.1f, 0.1f, 0.3f, 0.3f, clazz = DetectionClass.FACE)),
            meta(2)
        )

        val faceTrack = frame2.first { it.clazz == DetectionClass.FACE }
        assertNotEquals(frame1.first().id, faceTrack.id)
    }

    @Test
    fun `moving object is followed by the motion model while undetected`() {
        val tracker = tracker(TrackerConfig(velocitySmoothing = 0f)) // raw velocity for a deterministic test
        // Move right by 0.05 per frame.
        tracker.update(listOf(det(0.10f, 0.4f, 0.20f, 0.5f)), meta(1))
        tracker.update(listOf(det(0.15f, 0.4f, 0.25f, 0.5f)), meta(2))

        // No detection on frame 3: predicted box should have moved right again.
        val coasted = tracker.update(emptyList(), meta(3)).first()
        assertEquals(0.20f, coasted.box.left, 0.01f)
        assertEquals(0.30f, coasted.box.right, 0.01f)
    }

    @Test
    fun `reset clears all state`() {
        val tracker = tracker()

        tracker.update(listOf(det(0.1f, 0.1f, 0.3f, 0.3f)), meta(1))
        tracker.reset()

        val afterReset = tracker.update(emptyList(), meta(2))
        assertTrue(afterReset.isEmpty())
    }
}