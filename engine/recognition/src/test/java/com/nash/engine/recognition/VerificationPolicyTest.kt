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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationPolicyTest {

    private class FakeRecognizer(var next: () -> FaceEmbedding?) : FaceRecognizer<Unit> {
        override suspend fun embed(frame: Unit, faceBox: BoundingBox, metadata: FrameMetadata) = next()
        override fun close() {}
    }

    private val config = RecognitionConfig(
        matchThreshold = 0.6f,
        consecutiveMatchesToTrust = 2,
        mismatchesToRevoke = 2
    )
    private val liveTracks = LiveTrackRegistry(config) { 0L }
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = FakeRecognizer { null }
    private val policy = VerificationPolicy(
        recognizer, store, state, liveTracks, config
    )

    private val alice = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!
    private val stranger = FaceEmbedding.fromRaw(floatArrayOf(0f, 1f, 0f))!!

    private fun face(id: Long) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    @Test
    fun `first match is pending and the second match trusts`() = runBlocking {
        val person = store.enroll(alice)
        recognizer.next = { alice }

        policy.verify(Unit, face(1), metadata(5))
        val first = state.of(TrackId(1))
        assertEquals(VerificationState.PENDING, first.state)
        assertEquals(1, first.consecutiveMatches)
        assertEquals(person, first.personId)

        policy.verify(Unit, face(1), metadata(11))
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state)
    }

    @Test
    fun `a single mismatch keeps trying before rejecting`() = runBlocking {
        store.enroll(alice)
        recognizer.next = { stranger }

        policy.verify(Unit, face(1), metadata(5))
        val first = state.of(TrackId(1))
        assertEquals(VerificationState.UNKNOWN, first.state) // hysteresis: no hard reject
        assertEquals(1, first.consecutiveMismatches)

        policy.verify(Unit, face(1), metadata(11))
        assertEquals(VerificationState.REJECTED, state.of(TrackId(1)).state)
    }

    @Test
    fun `null embed records no decision`() = runBlocking {
        store.enroll(alice)
        recognizer.next = { null }

        policy.verify(Unit, face(1), metadata(5))

        val verification = state.of(TrackId(1))
        assertEquals(VerificationState.UNKNOWN, verification.state)
        assertEquals(0, verification.consecutiveMismatches)
        assertEquals(5L, verification.lastCheckedFrame) // still counts for cadence
    }
}
