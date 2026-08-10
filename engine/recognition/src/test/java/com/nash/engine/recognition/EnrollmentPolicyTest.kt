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
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentPolicyTest {

    private class FakeRecognizer(var next: () -> FaceEmbedding?) : FaceRecognizer<Unit> {
        override suspend fun embed(frame: Unit, faceBox: BoundingBox, metadata: FrameMetadata) = next()
        override fun close() {}
    }

    private val config = RecognitionConfig(
        matchThreshold = 0.6f,
        maxEnrollAttempts = 2
    )
    private val commands = KeepVisibleCommandQueue()
    private val liveTracks = LiveTrackRegistry(config) { 0L }
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = FakeRecognizer { null }
    private val policy = EnrollmentPolicy(
        recognizer, store, state, commands, liveTracks, config
    )

    private val alice = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!

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
    fun `successful enroll trusts the track and clears the tap`() = runBlocking {
        commands.requestEnrollment(TrackId(1))
        recognizer.next = { alice }

        policy.enroll(Unit, face(1), metadata(5))

        val verification = state.of(TrackId(1))
        assertEquals(VerificationState.TRUSTED, verification.state)
        assertEquals(5L, verification.lastCheckedFrame)
        assertEquals(1, store.trustedPersonCount)
        assertNull(commands.pendingEnrollment())
    }

    @Test
    fun `enrolling a known face reuses the existing person`() = runBlocking {
        val existing = store.enroll(alice)
        recognizer.next = { alice }

        policy.enroll(Unit, face(1), metadata(5))

        assertEquals(existing, state.of(TrackId(1)).personId)
        assertEquals(1, store.trustedPersonCount) // reused, not duplicated
    }

    @Test
    fun `null embeds stay pending then give up as unknown`() = runBlocking {
        commands.requestEnrollment(TrackId(1))
        recognizer.next = { null }

        policy.enroll(Unit, face(1), metadata(5)) // attempt 1 of 2
        assertEquals(VerificationState.PENDING, state.of(TrackId(1)).state)
        assertEquals(TrackId(1), commands.pendingEnrollment()) // still trying

        policy.enroll(Unit, face(1), metadata(11)) // attempt 2 of 2: give up
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(1)).state)
        assertNull(commands.pendingEnrollment())
        assertEquals(0, store.trustedPersonCount)
    }

    @Test
    fun `attempt counter dies with its track`() = runBlocking {
        recognizer.next = { null }

        policy.enroll(Unit, face(1), metadata(5)) // attempt 1 of 2
        policy.onFrame(emptyList()) // track died: counter forgotten

        policy.enroll(Unit, face(1), metadata(20)) // fresh attempt 1: no give-up
        assertEquals(VerificationState.PENDING, state.of(TrackId(1)).state)
    }
}
