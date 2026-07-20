package com.nash.core.domain

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadence contract of [DefaultAnonymizationPipeline]: detectors run every
 * [detectionInterval] frames, the tracker ticks on EVERY frame, and boxes
 * are published on every frame.
 */
class DefaultAnonymizationPipelineCadenceTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Records the exact sequence of update/predict calls per frameId. */
    private class RecordingTracker : Tracker {
        val calls = mutableListOf<String>()
        var lastDetections: List<DetectionBox> = emptyList()

        override fun update(
            detections: List<DetectionBox>,
            metadata: FrameMetadata
        ): List<TrackedBox> {
            calls += "update:${metadata.frameId}"
            lastDetections = detections
            return listOf(box(id = UPDATE_ID, frameId = metadata.frameId))
        }

        override fun predict(metadata: FrameMetadata): List<TrackedBox> {
            calls += "predict:${metadata.frameId}"
            return listOf(box(id = PREDICT_ID, frameId = metadata.frameId))
        }

        override fun reset() {
            calls += "reset"
        }

        companion object {
            const val UPDATE_ID = 100L
            const val PREDICT_ID = 200L

            fun box(id: Long, frameId: Long) = TrackedBox(
                id = TrackId(id),
                box = BoundingBox(0.1f, 0.1f, 0.3f, 0.3f),
                clazz = DetectionClass.FACE,
                confidence = 0.9f,
                lastUpdatedFrame = frameId,
                keepVisible = false
            )
        }
    }

    private class FakeDetector(
        private val onDetect: (String, FrameMetadata) -> List<DetectionBox> = { _, _ -> emptyList() }
    ) : Detector<String> {
        var detectCount = 0

        override suspend fun detect(
            frame: String,
            metadata: FrameMetadata
        ): List<DetectionBox> {
            detectCount++
            return onDetect(frame, metadata)
        }

        override fun close() = Unit
    }

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId,
        timestampNanos = frameId * 33_000_000L,
        width = 640,
        height = 360,
        rotationDegrees = 90
    )

    private fun detection(confidence: Float = 0.8f) = DetectionBox(
        box = BoundingBox(0.2f, 0.2f, 0.4f, 0.4f),
        clazz = DetectionClass.FACE,
        confidence = confidence
    )

    private fun pipeline(
        tracker: Tracker,
        detector: Detector<String>,
        interval: Long = 3L
    ) = DefaultAnonymizationPipeline(
        detectors = listOf(detector),
        tracker = tracker,
        detectionInterval = interval
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `first frame always runs detection`() = runTest {
        val tracker = RecordingTracker()
        val detector = FakeDetector()
        val pipeline = pipeline(tracker, detector)

        pipeline.onFrame("frame", metadata(frameId = 0))

        assertEquals(listOf("update:0"), tracker.calls)
        assertEquals(1, detector.detectCount)
    }

    @Test
    fun `detection runs every Nth frame and tracker ticks on all frames`() = runTest {
        val tracker = RecordingTracker()
        val detector = FakeDetector()
        val pipeline = pipeline(tracker, detector, interval = 3L)

        for (frameId in 0L..5L) {
            pipeline.onFrame("frame", metadata(frameId))
        }

        assertEquals(
            listOf("update:0", "predict:1", "predict:2", "update:3", "predict:4", "predict:5"),
            tracker.calls
        )
        assertEquals(2, detector.detectCount)
    }

    @Test
    fun `boxes are published on every frame including predict frames`() = runTest {
        val tracker = RecordingTracker()
        val pipeline = pipeline(tracker, FakeDetector(), interval = 3L)

        pipeline.onFrame("frame", metadata(0)) // update
        assertEquals(TrackId(RecordingTracker.UPDATE_ID), pipeline.trackedBoxes.value.single().id)

        pipeline.onFrame("frame", metadata(1)) // predict
        assertEquals(TrackId(RecordingTracker.PREDICT_ID), pipeline.trackedBoxes.value.single().id)
    }

    @Test
    fun `detections reach the tracker on detection frames`() = runTest {
        val tracker = RecordingTracker()
        val detector = FakeDetector { _, _ -> listOf(detection(confidence = 0.7f)) }
        val pipeline = pipeline(tracker, detector)

        pipeline.onFrame("frame", metadata(0))

        assertEquals(1, tracker.lastDetections.size)
        assertEquals(0.7f, tracker.lastDetections.single().confidence)
    }

    @Test
    fun `detector failure degrades to empty detections instead of crashing`() = runTest {
        val tracker = RecordingTracker()
        val detector = FakeDetector { _, _ -> error("model exploded") }
        val pipeline = pipeline(tracker, detector)

        pipeline.onFrame("frame", metadata(0))

        assertEquals(listOf("update:0"), tracker.calls)
        assertTrue(tracker.lastDetections.isEmpty())
    }

    @Test
    fun `reset restores first-frame-detects behavior`() = runTest {
        val tracker = RecordingTracker()
        val detector = FakeDetector()
        val pipeline = pipeline(tracker, detector, interval = 3L)

        pipeline.onFrame("frame", metadata(0))
        pipeline.onFrame("frame", metadata(1))
        pipeline.reset()

        // Frame 2 would be a predict frame if state survived reset — it must detect.
        pipeline.onFrame("frame", metadata(2))

        assertTrue(tracker.calls.contains("reset"))
        assertEquals("update:2", tracker.calls.last())
        assertEquals(2, detector.detectCount)
    }
}