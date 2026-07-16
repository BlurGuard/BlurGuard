package com.nash.core.domain

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.FrameSource
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAnonymizationPipelineTest {

    private class FakeDetector(var result: List<DetectionBox> = emptyList()) : Detector<String> {
        override suspend fun detect(frame: String, metadata: FrameMetadata) = result
        override fun close() {}
    }

    /** Echoes detections back as tracks with sequential ids. */
    private class FakeTracker : Tracker {
        var resetCount = 0
        override fun update(
            detections: List<DetectionBox>,
            metadata: FrameMetadata
        ): List<TrackedBox> = detections.mapIndexed { index, detection ->
            TrackedBox(
                id = TrackId(index.toLong()),
                box = detection.box,
                clazz = detection.clazz,
                confidence = detection.confidence,
                lastUpdatedFrame = metadata.frameId,
                keepVisible = false
            )
        }
        override fun reset() { resetCount++ }
    }

    private class FakeFrameSource : FrameSource<String> {
        var consumer: FrameConsumer<String>? = null
        override fun setFrameConsumer(consumer: FrameConsumer<String>?) {
            this.consumer = consumer
        }
    }

    private fun meta(frameId: Long) = FrameMetadata(
        frameId = frameId,
        timestampNanos = frameId * 33_000_000L,
        width = 640,
        height = 480,
        rotationDegrees = 0
    )

    private fun det() = DetectionBox(
        BoundingBox(0.1f, 0.1f, 0.3f, 0.3f), DetectionClass.FACE, 0.9f
    )

    @Test
    fun `onFrame runs detectors and publishes tracker output`() = runTest {
        val detector = FakeDetector(result = listOf(det()))
        val pipeline = DefaultAnonymizationPipeline(listOf(detector), FakeTracker())

        pipeline.onFrame("frame-1", meta(1))

        val published = pipeline.trackedBoxes.value
        assertEquals(1, published.size)
        assertEquals(1L, published.first().lastUpdatedFrame)
    }

    @Test
    fun `detections from multiple detectors are merged`() = runTest {
        val faces = FakeDetector(result = listOf(det()))
        val plates = FakeDetector(
            result = listOf(
                DetectionBox(BoundingBox(0.5f, 0.5f, 0.7f, 0.6f), DetectionClass.LICENSE_PLATE, 0.8f)
            )
        )
        val pipeline = DefaultAnonymizationPipeline(listOf(faces, plates), FakeTracker())

        pipeline.onFrame("frame-1", meta(1))

        assertEquals(2, pipeline.trackedBoxes.value.size)
    }

    @Test
    fun `reset clears published boxes and tracker state`() = runTest {
        val tracker = FakeTracker()
        val pipeline = DefaultAnonymizationPipeline(listOf(FakeDetector(listOf(det()))), tracker)
        pipeline.onFrame("frame-1", meta(1))

        pipeline.reset()

        assertTrue(pipeline.trackedBoxes.value.isEmpty())
        assertEquals(1, tracker.resetCount)
    }

    @Test
    fun `engine start attaches pipeline and stop detaches and resets`() {
        val source = FakeFrameSource()
        val tracker = FakeTracker()
        val pipeline = DefaultAnonymizationPipeline(listOf(FakeDetector()), tracker)
        val engine = DefaultAnonymizationEngine(source, pipeline)

        engine.start()
        assertSame(pipeline, source.consumer)

        engine.stop()
        assertNull(source.consumer)
        assertEquals(2, tracker.resetCount) // once on start, once on stop
    }
}