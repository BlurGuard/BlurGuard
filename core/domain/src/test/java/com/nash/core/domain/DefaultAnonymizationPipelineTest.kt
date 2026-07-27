package com.nash.core.domain

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of [DefaultAnonymizationPipeline]:
 *  1. Cadence — detectors run every [detectionInterval] frames, the tracker
 *     ticks on EVERY frame, boxes are published on every frame.
 *  2. Render feed — every published frame also lands in [RenderBoxFeed],
 *     carrying the frame rotation.
 *  3. Geometry — boxes are remapped from the full analysis buffer into the
 *     ViewPort-visible region (crop rect) before publishing, and clamped to 0..1.
 */
class DefaultAnonymizationPipelineTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Records the exact sequence of update/predict calls per frameId. */
    private class RecordingTracker(
        var updateBox: BoundingBox = BoundingBox(0.1f, 0.1f, 0.3f, 0.3f),
        var predictBox: BoundingBox = BoundingBox(0.1f, 0.1f, 0.3f, 0.3f),
    ) : Tracker {
        val calls = mutableListOf<String>()
        var lastDetections: List<DetectionBox> = emptyList()

        override fun update(
            detections: List<DetectionBox>,
            metadata: FrameMetadata
        ): List<TrackedBox> {
            calls += "update:${metadata.frameId}"
            lastDetections = detections
            return listOf(box(id = UPDATE_ID, box = updateBox, frameId = metadata.frameId))
        }

        override fun predict(metadata: FrameMetadata): List<TrackedBox> {
            calls += "predict:${metadata.frameId}"
            return listOf(box(id = PREDICT_ID, box = predictBox, frameId = metadata.frameId))
        }

        override fun reset() {
            calls += "reset"
        }

        companion object {
            const val UPDATE_ID = 100L
            const val PREDICT_ID = 200L

            fun box(id: Long, box: BoundingBox, frameId: Long) = TrackedBox(
                id = TrackId(id),
                box = box,
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun metadata(
        frameId: Long,
        width: Int = 640,
        height: Int = 360,
        rotationDegrees: Int = 90,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropWidth: Int = 0,
        cropHeight: Int = 0,
    ) = FrameMetadata(
        frameId = frameId,
        timestampNanos = frameId * 33_000_000L,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropWidth = cropWidth,
        cropHeight = cropHeight,
    )

    /** The real-world case: 640x480 (4:3) analysis buffer, 16:9 ViewPort band. */
    private fun croppedMetadata(frameId: Long, rotationDegrees: Int) = metadata(
        frameId = frameId,
        width = 640,
        height = 480,
        rotationDegrees = rotationDegrees,
        cropLeft = 0,
        cropTop = 60,
        cropWidth = 640,
        cropHeight = 360,
    )

    private fun detection(confidence: Float = 0.8f) = DetectionBox(
        box = BoundingBox(0.2f, 0.2f, 0.4f, 0.4f),
        clazz = DetectionClass.FACE,
        confidence = confidence
    )

    private fun pipeline(
        tracker: Tracker,
        detector: Detector<String> = FakeDetector(),
        interval: Long = 3L,
        feed: RenderBoxFeed = RenderBoxFeed(),
    ) = DefaultAnonymizationPipeline(
        detectors = listOf(detector),
        tracker = tracker,
        detectionInterval = interval,
        renderBoxFeed = feed,
    )

    private companion object {
        const val EPS = 1e-4f
    }

    // ------------------------------------------------------------------
    // 1. Cadence contract
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
        val pipeline = pipeline(tracker, interval = 3L)

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
        assertEquals(0.7f, tracker.lastDetections.single().confidence, EPS)
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

    // ------------------------------------------------------------------
    // 2. Render feed contract
    // ------------------------------------------------------------------

    @Test
    fun `update frames publish boxes and rotation to the render feed`() = runTest {
        val feed = RenderBoxFeed()
        val pipeline = pipeline(RecordingTracker(), feed = feed)

        pipeline.onFrame("frame", metadata(0, rotationDegrees = 90))

        val snapshot = feed.latest()
        assertEquals(90, snapshot.rotationDegrees)
        assertEquals(TrackId(RecordingTracker.UPDATE_ID), snapshot.boxes.single().id)
    }

    @Test
    fun `predict frames also publish to the render feed`() = runTest {
        val feed = RenderBoxFeed()
        val pipeline = pipeline(RecordingTracker(), interval = 3L, feed = feed)

        pipeline.onFrame("frame", metadata(0)) // update
        pipeline.onFrame("frame", metadata(1)) // predict

        assertEquals(TrackId(RecordingTracker.PREDICT_ID), feed.latest().boxes.single().id)
    }

    @Test
    fun `render feed and trackedBoxes flow publish identical remapped boxes`() = runTest {
        val feed = RenderBoxFeed()
        val pipeline = pipeline(RecordingTracker(), feed = feed)

        pipeline.onFrame("frame", croppedMetadata(frameId = 0, rotationDegrees = 90))

        // The GPU renderer and the debug overlay must never disagree on geometry.
        assertEquals(pipeline.trackedBoxes.value, feed.latest().boxes)
    }

    @Test
    fun `reset clears the render feed`() = runTest {
        val feed = RenderBoxFeed()
        val pipeline = pipeline(RecordingTracker(), feed = feed)

        pipeline.onFrame("frame", metadata(0))
        assertTrue(feed.latest().boxes.isNotEmpty())

        pipeline.reset()

        // Fail-closed hygiene: stale boxes must not survive a session restart.
        assertTrue(feed.latest().boxes.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. Visible-region remap (the ViewPort crop fix)
    // ------------------------------------------------------------------

    @Test
    fun `no crop metadata means boxes pass through unchanged`() = runTest {
        val original = BoundingBox(0.2f, 0.3f, 0.4f, 0.6f)
        val tracker = RecordingTracker(updateBox = original)
        val pipeline = pipeline(tracker)

        // Default metadata: cropWidth/cropHeight = 0 → full frame visible.
        pipeline.onFrame("frame", metadata(0))

        assertEquals(original, pipeline.trackedBoxes.value.single().box)
    }

    @Test
    fun `full-frame crop rect is treated as no crop`() = runTest {
        val original = BoundingBox(0.2f, 0.3f, 0.4f, 0.6f)
        val tracker = RecordingTracker(updateBox = original)
        val pipeline = pipeline(tracker)

        pipeline.onFrame(
            "frame",
            metadata(0, width = 640, height = 480, cropWidth = 640, cropHeight = 480)
        )

        assertEquals(original, pipeline.trackedBoxes.value.single().box)
    }

    @Test
    fun `rotation 0 - vertical band crop remaps and stretches the y axis only`() = runTest {
        // Buffer 640x480, visible band y = 60..420 (16:9 inside 4:3).
        // Upright crop (rotation 0) = (0, 0.125, 1, 0.875), so y' = (y - 0.125) / 0.75.
        val tracker = RecordingTracker(updateBox = BoundingBox(0.2f, 0.5f, 0.4f, 0.875f))
        val pipeline = pipeline(tracker)

        pipeline.onFrame("frame", croppedMetadata(frameId = 0, rotationDegrees = 0))

        val b = pipeline.trackedBoxes.value.single().box
        assertEquals(0.2f, b.left, EPS)             // x untouched
        assertEquals(0.4f, b.right, EPS)            // x untouched
        assertEquals(0.5f, b.top, EPS)              // (0.5   - 0.125) / 0.75
        assertEquals(1.0f, b.bottom, EPS)           // crop edge maps to screen edge
    }

    @Test
    fun `rotation 90 - crop rect is rotated into upright space before remapping`() = runTest {
        // Same 640x480 buffer + y = 60..420 band, but rotation 90:
        // upright crop = (0.125, 0, 0.875, 1) → x' = (x - 0.125) / 0.75, y untouched.
        val tracker = RecordingTracker(updateBox = BoundingBox(0.5f, 0.5f, 0.65f, 0.7f))
        val pipeline = pipeline(tracker)

        pipeline.onFrame("frame", croppedMetadata(frameId = 0, rotationDegrees = 90))

        val b = pipeline.trackedBoxes.value.single().box
        assertEquals(0.5f, b.left, EPS)             // (0.5  - 0.125) / 0.75
        assertEquals(0.7f, b.right, EPS)            // (0.65 - 0.125) / 0.75
        assertEquals(0.5f, b.top, EPS)              // y untouched
        assertEquals(0.7f, b.bottom, EPS)           // y untouched
    }

    @Test
    fun `boxes outside the visible region are clamped to 0-1`() = runTest {
        // Box partially above the visible band (rotation 0 case).
        val tracker = RecordingTracker(updateBox = BoundingBox(0.1f, 0.0f, 0.3f, 0.2f))
        val pipeline = pipeline(tracker)

        pipeline.onFrame("frame", croppedMetadata(frameId = 0, rotationDegrees = 0))

        val b = pipeline.trackedBoxes.value.single().box
        assertEquals(0.0f, b.top, EPS)              // (0.0 - 0.125)/0.75 = -0.167 → clamped
        assertEquals(0.1f, b.bottom, EPS)           // (0.2 - 0.125)/0.75
        assertTrue(b.left in 0f..1f && b.right in 0f..1f)
    }

    @Test
    fun `remap does not change track identity or keepVisible`() = runTest {
        val tracker = RecordingTracker(updateBox = BoundingBox(0.3f, 0.3f, 0.5f, 0.5f))
        val pipeline = pipeline(tracker)

        pipeline.onFrame("frame", croppedMetadata(frameId = 0, rotationDegrees = 90))

        val tracked = pipeline.trackedBoxes.value.single()
        assertEquals(TrackId(RecordingTracker.UPDATE_ID), tracked.id)
        assertEquals(DetectionClass.FACE, tracked.clazz)
        assertEquals(false, tracked.keepVisible)
    }
}