package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReVerificationPolicyTest {

    private class FakeRecognizer(var next: () -> FaceEmbedding?) : FaceRecognizer<Unit> {
        override suspend fun embed(frame: Unit, faceBox: BoundingBox, metadata: FrameMetadata) = next()
        override fun close() {}
    }

    private val config = RecognitionConfig(
        matchThreshold = 0.6f,
        mismatchesToRevoke = 2
    )
    private val liveTracks = LiveTrackRegistry(config) { 0L }
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = FakeRecognizer { null }
    private val policy = ReVerificationPolicy(
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
    fun `matching re-check keeps trust and clears mismatches`() = runBlocking {
        val person = store.enroll(alice)
        state.set(
            TrackId(1),
            TrackVerification(
                state = VerificationState.TRUSTED,
                personId = person,
                consecutiveMismatches = 1,
                lastCheckedFrame = 0L
            )
        )
        recognizer.next = { alice }

        policy.reVerify(Unit, face(1), metadata(10))

        val verification = state.of(TrackId(1))
        assertEquals(VerificationState.TRUSTED, verification.state)
        assertEquals(0, verification.consecutiveMismatches)
        assertEquals(10L, verification.lastCheckedFrame)
    }

    @Test
    fun `second consecutive mismatch revokes trust`() = runBlocking {
        val person = store.enroll(alice)
        state.set(
            TrackId(1),
            TrackVerification(
                state = VerificationState.TRUSTED,
                personId = person,
                lastCheckedFrame = 0L
            )
        )
        recognizer.next = { stranger }

        policy.reVerify(Unit, face(1), metadata(10))
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state) // hysteresis
        assertEquals(1, state.of(TrackId(1)).consecutiveMismatches)

        policy.reVerify(Unit, face(1), metadata(20))
        val verification = state.of(TrackId(1))
        assertEquals(VerificationState.REJECTED, verification.state)
        assertEquals(person, verification.personId) // identity kept for slow recheck
    }

    @Test
    fun `null embed keeps trust with no decision`() = runBlocking {
        val person = store.enroll(alice)
        state.set(
            TrackId(1),
            TrackVerification(
                state = VerificationState.TRUSTED,
                personId = person,
                lastCheckedFrame = 0L
            )
        )
        recognizer.next = { null }

        policy.reVerify(Unit, face(1), metadata(10))

        val verification = state.of(TrackId(1))
        assertEquals(VerificationState.TRUSTED, verification.state)
        assertEquals(0, verification.consecutiveMismatches)
        assertEquals(10L, verification.lastCheckedFrame)
    }
}
