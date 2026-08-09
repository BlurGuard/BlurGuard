package com.nash.engine.impl

import com.nash.core.model.DetectionBox
import com.nash.core.model.Detector
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RenderBoxFeed
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.FakeClock
import com.nash.engine.impl.pipeline.FakeKeepVisibleRecognizer
import com.nash.engine.impl.pipeline.FakeKeepVisibleState
import com.nash.engine.impl.pipeline.FakeTracker
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.RecordingFailurePolicy
import com.nash.engine.impl.pipeline.TestFrame
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper
import com.nash.engine.impl.pipeline.meta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of [DefaultAnonymizationPipeline.degraded]: latches true when a
 * detection pass is incomplete, clears on the next clean pass, and resets
 * with the session. This is the signal RealBlurGuardEngine surfaces as
 * EngineWarning.DetectionDegraded.
 */
class DefaultAnonymizationPipelineDegradedTest {

    /** Detector whose failure mode can be flipped mid-test. */
    private class FlakyDetector : Detector<TestFrame> {
        var shouldThrow = false

        override suspend fun detect(
            frame: TestFrame,
            metadata: FrameMetadata,
        ): List<DetectionBox> {
            if (shouldThrow) throw IllegalStateException("model exploded")
            return emptyList()
        }

        override fun close() = Unit
    }

    /** detectionInterval = 1: every frame runs detection. */
    private fun pipeline(detector: FlakyDetector) = DefaultAnonymizationPipeline(
        scheduler = DetectionScheduler(detectionInterval = 1L),
        detectionRunner = DetectionRunner(listOf(detector), RecordingFailurePolicy()),
        trackingStage = TrackingStage(FakeTracker()),
        keepVisibleStage = KeepVisibleStage(FakeKeepVisibleRecognizer(), FakeKeepVisibleState()),
        boxMapper = VisibleRegionBoxMapper(),
        publisher = TrackedBoxPublisher(RenderBoxFeed()),
        statsCollector = PipelineStatsCollector(clock = FakeClock()),
        clock = FakeClock(),
    )

    @Test
    fun `throwing detector sets degraded and a clean pass clears it`() = runTest {
        val detector = FlakyDetector()
        val pipeline = pipeline(detector)

        assertFalse(pipeline.degraded.value)

        detector.shouldThrow = true
        pipeline.onFrame("frame", meta(frameId = 0))
        assertTrue(pipeline.degraded.value)

        detector.shouldThrow = false
        pipeline.onFrame("frame", meta(frameId = 1))
        assertFalse(pipeline.degraded.value)
    }

    @Test
    fun `reset clears a latched degraded flag`() = runTest {
        val detector = FlakyDetector().apply { shouldThrow = true }
        val pipeline = pipeline(detector)

        pipeline.onFrame("frame", meta(frameId = 0))
        assertTrue(pipeline.degraded.value)

        pipeline.reset()
        assertFalse(pipeline.degraded.value)
    }
}
