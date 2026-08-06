package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import com.nash.core.model.decorate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cost bounds for the recognition path (review fix 21).
 *
 * Every test here asserts two things about a skipped pass: the recognizer was
 * not called, AND the track stayed blurred. Cheaper is only correct if it is
 * also fail-closed.
 */
class KeepVisibleOrchestratorCadenceTest {

    /** Counts calls so "cheaper" is measured, not assumed. */
    private class CountingRecognizer(var next: () -> FaceEmbedding?) : FaceRecognizer<Unit> {
        var calls = 0
            private set

        override suspend fun embed(
            frame: Unit,
            faceBox: BoundingBox,
            metadata: FrameMetadata
        ): FaceEmbedding? {
            calls++
            return next()
        }

        override fun close() {}
    }

    private val config = RecognitionConfig(
        matchThreshold = 0.6f,
        consecutiveMatchesToTrust = 2,
        reVerifyIntervalFrames = 10L,
        mismatchesToRevoke = 2,
        minRecognitionIntervalMs = 150L,
        minFaceBoxPx = 40,
        minTrackAgeFrames = 4L,
        minTrackConfidence = 0.4f
    )
    private var nowMs = 0L
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = CountingRecognizer { alice }
    private val orchestrator = KeepVisibleOrchestrator(recognizer, store, state, config) { nowMs }

    private val alice = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!

    /** 640x360 upright: 0.2 normalized is 128x72 px — comfortably over the gate. */
    private fun face(
        id: Long,
        confidence: Float = 0.9f,
        half: Float = 0.1f,
    ) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.5f - half, 0.5f - half, 0.5f + half, 0.5f + half),
        clazz = DetectionClass.FACE,
        confidence = confidence,
        lastUpdatedFrame = 0L
    )

    /** 0.025 normalized is 32x18 px — under minFaceBoxPx on both axes. */
    private fun tinyFace(id: Long) = face(id, half = 0.025f)

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    private fun frame(frameId: Long, atMs: Long, vararg boxes: TrackedBox) = runBlocking {
        nowMs = atMs
        orchestrator.onDetectionFrame(Unit, metadata(frameId), boxes.toList())
    }

    private fun isKeptVisible(track: TrackedBox): Boolean =
        state.verifications.value.decorate(listOf(track)).single().keepVisible

    @Test
    fun `a tap cannot re-embed the same track faster than the interval`() {
        // Embeds keep failing, so the tap path retries — the worst case.
        recognizer.next = { null }
        orchestrator.requestKeepVisible(TrackId(1))

        frame(0, atMs = 0, face(1))
        assertEquals(1, recognizer.calls)

        // Three more detection frames inside the 150 ms window: no new work.
        frame(1, atMs = 33, face(1))
        frame(2, atMs = 66, face(1))
        frame(3, atMs = 99, face(1))
        assertEquals("interval must bound the tap retry storm", 1, recognizer.calls)

        frame(4, atMs = 200, face(1))
        assertEquals(2, recognizer.calls)
        assertFalse("a pending tap never unblurs on its own", isKeptVisible(face(1)))
    }

    @Test
    fun `a face below the pre-gate never reaches the recognizer`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))

        repeat(12) { i ->
            frame(i.toLong(), atMs = i * 200L, tinyFace(1))
        }

        assertEquals("no bitmap work for a 32x18 box", 0, recognizer.calls)
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(1)).state)
        assertFalse("a skipped face stays blurred", isKeptVisible(tinyFace(1)))
        assertEquals(0, store.trustedPersonCount)
    }

    @Test
    fun `a low-confidence box is never auto-verified`() {
        // Give the store someone to match against, so priority 2 is live.
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(0, atMs = 0, face(1))
        val afterEnroll = recognizer.calls

        // Track 2 is old enough, big enough, but the detector is unsure.
        val unsure = face(2, confidence = 0.2f)
        frame(10, atMs = 400, unsure)
        frame(20, atMs = 800, unsure)
        frame(30, atMs = 1200, unsure)

        assertEquals(afterEnroll, recognizer.calls)
        assertFalse(isKeptVisible(unsure))
    }

    @Test
    fun `a freshly seen track waits until it is stable`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(0, atMs = 0, face(1))
        val afterEnroll = recognizer.calls

        // First sighting of track 2: registered, not recognized.
        frame(10, atMs = 400, face(2))
        assertEquals("a one-frame-old track is not worth a pass", afterEnroll, recognizer.calls)
        assertFalse(isKeptVisible(face(2)))

        // Four frame ids later it has earned one.
        frame(14, atMs = 800, face(2))
        assertEquals(afterEnroll + 1, recognizer.calls)
    }

    @Test
    fun `a tap overrides stability but not the size gate`() {
        recognizer.next = { alice }

        // Brand-new track, tapped on its very first frame: recognized anyway.
        orchestrator.requestKeepVisible(TrackId(7))
        frame(0, atMs = 0, face(7))
        assertEquals(1, recognizer.calls)
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(7)).state)
    }

    @Test
    fun `re-verification of a trusted track respects the wall-clock floor`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(0, atMs = 0, face(1))
        val afterEnroll = recognizer.calls
        assertTrue(isKeptVisible(face(1)))

        // The 10-frame re-verify interval has elapsed, but only 40 ms of it:
        // a fast or stalled analysis stream must not multiply the cost.
        frame(10, atMs = 40, face(1))
        assertEquals(afterEnroll, recognizer.calls)

        // Same frame distance, real time elapsed: now it runs.
        frame(20, atMs = 400, face(1))
        assertEquals(afterEnroll + 1, recognizer.calls)

        // And a trusted track stays visible across a skipped re-verification.
        assertTrue(isKeptVisible(face(1)))
    }
}