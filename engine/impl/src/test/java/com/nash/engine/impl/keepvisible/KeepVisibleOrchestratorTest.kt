package com.nash.engine.impl.keepvisible

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.SessionTrustedPersonStore
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepVisibleOrchestratorTest {

    private class FakeRecognizer(var next: () -> FaceEmbedding?) : FaceRecognizer<Unit> {
        override suspend fun embed(frame: Unit, faceBox: BoundingBox, metadata: FrameMetadata) = next()
        override fun close() {}
    }

    private val config = RecognitionConfig(
        matchThreshold = 0.6f,
        consecutiveMatchesToTrust = 2,
        reVerifyIntervalFrames = 10L,
        mismatchesToRevoke = 2
    )
    private val state = KeepVisibleState()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = FakeRecognizer { null }
    private val orchestrator = KeepVisibleOrchestrator(recognizer, store, state, config)

    private val alice = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!
    private val aliceAgain = FaceEmbedding.fromRaw(floatArrayOf(0.95f, 0.05f, 0.05f))!!
    private val stranger = FaceEmbedding.fromRaw(floatArrayOf(0f, 1f, 0f))!!

    private fun face(id: Long) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )

    // NOTE: adjust to your real FrameMetadata constructor (same caveat as the
    // Phase 1 test file).
    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    private fun frame(frameId: Long, vararg boxes: TrackedBox) = runBlocking {
        orchestrator.onDetectionFrame(Unit, metadata(frameId), boxes.toList())
    }

    @Test
    fun `tap enrolls and trusts immediately`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(1, face(1))
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state)
        assertEquals(1, store.trustedPersonCount)
        assertTrue(state.decorate(listOf(face(1))).single().keepVisible)
    }

    @Test
    fun `re-entry needs K consecutive matches before unblurring`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        // Track 1 died; same person returns as track 2.
        recognizer.next = { aliceAgain }
        frame(10, face(2))
        assertEquals(VerificationState.PENDING, state.of(TrackId(2)).state)
        assertFalse(state.decorate(listOf(face(2))).single().keepVisible)

        frame(20, face(2)) // second consecutive match
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(2)).state)
        assertTrue(state.decorate(listOf(face(2))).single().keepVisible)
    }

    @Test
    fun `a stranger is rejected and stays blurred`(): Unit = runTest {
        // Enroll alice on track 1.
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        orchestrator.onDetectionFrame(Unit, metadata(frameId = 0), listOf(face(1)))

        // A stranger appears on track 2.
        recognizer.next = { stranger }
        orchestrator.onDetectionFrame(Unit, metadata(frameId = 10), listOf(face(2)))

        // First mismatch: hysteresis — not rejected yet, but still blurred.
        assertNotEquals(VerificationState.REJECTED, state.of(TrackId(2)).state)
        assertFalse(state.decorate(listOf(face(2))).single().keepVisible)

        // Second consecutive mismatch (retry interval elapsed): now rejected.
        orchestrator.onDetectionFrame(Unit, metadata(frameId = 20), listOf(face(2)))
        assertEquals(VerificationState.REJECTED, state.of(TrackId(2)).state)
        assertFalse(state.decorate(listOf(face(2))).single().keepVisible)
    }

    @Test
    fun `null embedding is no decision, not a mismatch`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        recognizer.next = { null }
        frame(10, face(2))
        val v = state.of(TrackId(2))
        assertTrue(v.state == VerificationState.UNKNOWN || v.state == VerificationState.PENDING)
        assertEquals(0, v.consecutiveMatches)
    }

    @Test
    fun `trusted track stolen by an ID switch is revoked after mismatches`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        // Re-verification now sees a different face on the same track id.
        recognizer.next = { stranger }
        frame(12, face(1))  // mismatch 1 of 2 — still trusted (hysteresis)
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state)
        frame(24, face(1))  // mismatch 2 of 2 — revoked
        assertEquals(VerificationState.REJECTED, state.of(TrackId(1)).state)
        assertFalse(state.decorate(listOf(face(1))).single().keepVisible)
    }

    @Test
    fun `revokeAll re-blurs and wipes the store`() {
        recognizer.next = { alice }
        orchestrator.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        orchestrator.revokeAll()
        assertFalse(state.decorate(listOf(face(1))).single().keepVisible)
        frame(2, face(1)) // flag drained on ml thread
        assertEquals(0, store.trustedPersonCount)
    }
}
