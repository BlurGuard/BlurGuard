package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackRegistryTest {

    private val config = RecognitionConfig(
        minTrackAgeFrames = 4L,
        minTrackConfidence = 0.4f,
        minRecognitionIntervalMs = 150L
    )
    private var clockMs = 100_000L
    private val registry = LiveTrackRegistry(config) { clockMs }

    private fun face(id: Long, confidence: Float = 0.9f) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        clazz = DetectionClass.FACE,
        confidence = confidence,
        lastUpdatedFrame = 0L
    )

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    @Test
    fun `interval gate passes for a never-attempted track`() {
        registry.onFrame(listOf(face(1)), metadata(0))

        assertTrue(registry.isIntervalElapsed(face(1)))
    }

    @Test
    fun `interval gate blocks until minRecognitionIntervalMs has passed`() {
        registry.onFrame(listOf(face(1)), metadata(0))
        registry.markRecognitionAttempt(face(1))

        clockMs += 149
        registry.onFrame(listOf(face(1)), metadata(1))
        assertFalse(registry.isIntervalElapsed(face(1)))

        clockMs += 1 // exactly 150 ms since the attempt
        registry.onFrame(listOf(face(1)), metadata(2))
        assertTrue(registry.isIntervalElapsed(face(1)))
    }

    @Test
    fun `attempt clock is stamped even when the pass will fail`() {
        // markRecognitionAttempt happens BEFORE the embed, so a slow or
        // failed pass still consumes the interval budget.
        registry.onFrame(listOf(face(1)), metadata(0))
        registry.markRecognitionAttempt(face(1))

        registry.onFrame(listOf(face(1)), metadata(1)) // same clock
        assertFalse(registry.isIntervalElapsed(face(1)))
    }

    @Test
    fun `stability gate requires minTrackAgeFrames on screen`() {
        registry.onFrame(listOf(face(1)), metadata(10))

        assertFalse(registry.isStableEnough(face(1), metadata(10)))
        assertFalse(registry.isStableEnough(face(1), metadata(13)))
        assertTrue(registry.isStableEnough(face(1), metadata(14)))
    }

    @Test
    fun `stability gate rejects low-confidence tracks regardless of age`() {
        registry.onFrame(listOf(face(1)), metadata(0))

        assertFalse(registry.isStableEnough(face(1, confidence = 0.39f), metadata(100)))
    }

    @Test
    fun `a track that disappears loses its age on re-entry`() {
        registry.onFrame(listOf(face(1)), metadata(0))
        assertTrue(registry.isStableEnough(face(1), metadata(4)))

        registry.onFrame(emptyList(), metadata(5)) // track 1 died
        registry.onFrame(listOf(face(1)), metadata(6)) // id reused later

        // Age restarts from re-entry: 6..9 are too young, 10 is old enough.
        assertFalse(registry.isStableEnough(face(1), metadata(9)))
        assertTrue(registry.isStableEnough(face(1), metadata(10)))
    }

    @Test
    fun `reset forgets attempt clocks`() {
        registry.onFrame(listOf(face(1)), metadata(0))
        registry.markRecognitionAttempt(face(1))
        assertFalse(registry.isIntervalElapsed(face(1)))

        registry.reset()
        registry.onFrame(listOf(face(1)), metadata(1)) // same clock

        assertTrue(registry.isIntervalElapsed(face(1)))
    }
}