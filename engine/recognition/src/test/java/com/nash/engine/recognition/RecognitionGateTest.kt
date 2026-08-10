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

class RecognitionGateTest {

    private val config = RecognitionConfig(
        minFaceBoxPx = 72,
        minTrackAgeFrames = 4L,
        minTrackConfidence = 0.4f,
        minRecognitionIntervalMs = 150L
    )
    private var clockMs = 100_000L
    private val liveTracks = LiveTrackRegistry(config) { clockMs }
    private val gate = RecognitionGate(config, liveTracks)

    private fun face(
        id: Long = 1L,
        box: BoundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        confidence: Float = 0.9f
    ) = TrackedBox(
        id = TrackId(id),
        box = box,
        clazz = DetectionClass.FACE,
        confidence = confidence,
        lastUpdatedFrame = 0L
    )

    private fun metadata(frameId: Long = 0L, rotationDegrees: Int = 0) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = rotationDegrees, timestampNanos = frameId * 33
    )

    // --- size gate ---

    @Test
    fun `size gate passes when both upright axes meet the minimum`() {
        // 0.2 of 640 = 128 px wide, 0.2 of 360 = 72 px high; both >= 72.
        assertTrue(gate.isLargeEnough(face(), metadata()))
    }

    @Test
    fun `size gate fails when one upright axis is under the minimum`() {
        // 128 px wide but only ~68 px high: BOTH axes must clear the bar.
        val short = face(box = BoundingBox(0.4f, 0.4f, 0.6f, 0.59f))
        assertFalse(gate.isLargeEnough(short, metadata()))
    }

    @Test
    fun `size gate swaps buffer axes for 90 and 270 degree rotations`() {
        // 0.15 x 0.2 normalized against the UPRIGHT frame.
        val box = face(box = BoundingBox(0.4f, 0.4f, 0.55f, 0.6f))

        // Upright landscape (0/180): 640x360 -> 96 x 72 px, passes.
        assertTrue(gate.isLargeEnough(box, metadata(rotationDegrees = 0)))
        assertTrue(gate.isLargeEnough(box, metadata(rotationDegrees = 180)))

        // Rotated buffer (90/270): upright frame is 360x640 -> 54 x 128 px,
        // width fails. Without the swap this box would wrongly pass.
        assertFalse(gate.isLargeEnough(box, metadata(rotationDegrees = 90)))
        assertFalse(gate.isLargeEnough(box, metadata(rotationDegrees = 270)))
    }

    // --- composed auto-path gate ---

    @Test
    fun `canAutoCheck passes once a track is stable, large and off cooldown`() {
        liveTracks.onFrame(listOf(face()), metadata(frameId = 0))

        assertTrue(gate.canAutoCheck(face(), metadata(frameId = 4)))
    }

    @Test
    fun `canAutoCheck fails while the attempt cooldown is running`() {
        liveTracks.onFrame(listOf(face()), metadata(frameId = 0))
        liveTracks.markRecognitionAttempt(face())

        clockMs += 149
        liveTracks.onFrame(listOf(face()), metadata(frameId = 4))

        assertFalse(gate.canAutoCheck(face(), metadata(frameId = 4)))
    }

    @Test
    fun `canAutoCheck fails for a track that is too young`() {
        liveTracks.onFrame(listOf(face()), metadata(frameId = 0))

        assertFalse(gate.canAutoCheck(face(), metadata(frameId = 3)))
    }

    @Test
    fun `canAutoCheck fails for an undersized face even when stable`() {
        val tiny = face(box = BoundingBox(0.45f, 0.45f, 0.55f, 0.55f)) // 64 x 36 px
        liveTracks.onFrame(listOf(tiny), metadata(frameId = 0))

        assertFalse(gate.canAutoCheck(tiny, metadata(frameId = 10)))
    }

    @Test
    fun `canAutoCheck fails for a low-confidence track`() {
        liveTracks.onFrame(listOf(face()), metadata(frameId = 0))

        assertFalse(gate.canAutoCheck(face(confidence = 0.39f), metadata(frameId = 10)))
    }
}
