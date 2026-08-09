package com.nash.engine.impl

import com.nash.core.model.RenderBoxFeed
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.FakeClock
import com.nash.engine.impl.pipeline.FakeDetector
import com.nash.engine.impl.pipeline.FakeKeepVisibleRecognizer
import com.nash.engine.impl.pipeline.FakeKeepVisibleState
import com.nash.engine.impl.pipeline.FakeTracker
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.TestFrame
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper
import com.nash.engine.impl.pipeline.detection
import com.nash.engine.impl.pipeline.meta
import com.nash.engine.impl.pipeline.tracked
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior-parity harness for the staged pipeline.
 *
 * These assertions describe the ORIGINAL monolithic onFrame() semantics and
 * must keep passing unchanged through the whole extraction, with one
 * documented exception: RenderBoxFeed is now published before trackedBoxes.
 */
class DefaultAnonymizationPipelineTest {

    private class Harness(
        detectionInterval: Long = 2L,
        updateBoxes: List<com.nash.core.model.TrackedBox> = listOf(tracked(id = 1L)),
        predictBoxes: List<com.nash.core.model.TrackedBox> = listOf(tracked(id = 1L)),
        detectorError: Throwable? = null,
    ) {
        val clock = FakeClock()
        val feed = RenderBoxFeed()
        val detector = FakeDetector(listOf(detection()), detectorError)
        val tracker = FakeTracker(updateBoxes, predictBoxes)
        val recognizer = FakeKeepVisibleRecognizer()
        val keepVisibleState = FakeKeepVisibleState()
        val publisher = TrackedBoxPublisher(feed)
        val statsCollector = PipelineStatsCollector(clock)

        val pipeline = DefaultAnonymizationPipeline<TestFrame>(
            scheduler = DetectionScheduler(detectionInterval),
            detectionRunner = DetectionRunner(listOf(detector)),
            trackingStage = TrackingStage(tracker),
            keepVisibleStage = KeepVisibleStage(recognizer, keepVisibleState),
            boxMapper = VisibleRegionBoxMapper(),
            publisher = publisher,
            statsCollector = statsCollector,
            clock = clock,
        )
    }

    @Test
    fun `detect-predict-detect cadence hits the right stages`() = runTest {
        val h = Harness(detectionInterval = 2L)

        h.pipeline.onFrame("f0", meta(0L))
        h.pipeline.onFrame("f1", meta(1L))
        h.pipeline.onFrame("f2", meta(2L))

        assertEquals(listOf(0L, 2L), h.tracker.updatedFrameIds)
        assertEquals(listOf(1L), h.tracker.predictedFrameIds)
        // Recognition runs on detection frames only — never on predict frames.
        assertEquals(listOf(0L, 2L), h.recognizer.detectionFrameIds)
    }

    @Test
    fun `both paths publish to renderer and overlay`() = runTest {
        val h = Harness(detectionInterval = 2L)

        h.pipeline.onFrame("f0", meta(0L, rotationDegrees = 90))
        assertEquals(1, h.feed.latest().boxes.size)
        assertEquals(90, h.feed.latest().rotationDegrees)
        assertEquals(1, h.pipeline.trackedBoxes.value.size)

        h.feed.clear()
        h.pipeline.onFrame("f1", meta(1L, rotationDegrees = 90))
        assertEquals("predict path must publish too", 1, h.feed.latest().boxes.size)
        assertEquals(1, h.pipeline.trackedBoxes.value.size)
    }

    @Test
    fun `detector crash still publishes and does not break the cadence`() = runTest {
        val h = Harness(
            detectionInterval = 2L,
            detectorError = IllegalStateException("interpreter died"),
        )

        h.pipeline.onFrame("f0", meta(0L))

        // Tracker still ran, with an empty detection set.
        assertEquals(listOf(0L), h.tracker.updatedFrameIds)
        assertTrue(h.tracker.lastDetections.isEmpty())
        // And the frame was still published (no silent stall).
        assertEquals(1, h.pipeline.trackedBoxes.value.size)
    }

    @Test
    fun `empty keep-visible state leaves every box blurred`() = runTest {
        val h = Harness(detectionInterval = 2L)

        h.pipeline.onFrame("f0", meta(0L))

        assertTrue(h.pipeline.trackedBoxes.value.none { it.keepVisible })
    }

    @Test
    fun `reset clears every stage`() = runTest {
        val h = Harness(detectionInterval = 2L)
        h.pipeline.onFrame("f0", meta(0L))

        h.pipeline.reset()

        assertEquals(1, h.tracker.resetCount)
        assertEquals(1, h.recognizer.sessionResets)
        assertTrue(h.pipeline.trackedBoxes.value.isEmpty())
        assertTrue(h.feed.latest().boxes.isEmpty())
        assertEquals(com.nash.core.model.PipelineStats(), h.pipeline.stats.value)

        // After reset the next frame must detect again, whatever its frameId.
        h.pipeline.onFrame("f9", meta(9L))
        assertEquals(listOf(0L, 9L), h.tracker.updatedFrameIds)
    }

    @Test
    fun `crop metadata is applied to published boxes`() = runTest {
        val h = Harness(
            detectionInterval = 2L,
            updateBoxes = listOf(tracked(left = 0.5f, top = 0.5f, right = 0.75f, bottom = 0.75f)),
        )

        h.pipeline.onFrame(
            "f0",
            meta(0L, width = 400, height = 400, cropLeft = 100, cropTop = 100, cropWidth = 200, cropHeight = 200),
        )

        val box = h.pipeline.trackedBoxes.value.single().box
        assertEquals(0.5f, box.left, 1e-4f)
        assertEquals(1.0f, box.right, 1e-4f)
    }
}
