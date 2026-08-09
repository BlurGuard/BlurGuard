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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust-policy tests for the keep-visible recognizer, driven end to end
 * through the controller/recognizer pair sharing one command queue and one
 * state store — exactly the production wiring. Moved here with the classes
 * they cover (review fix 16); the store and gallery they drive are
 * same-package collaborators rather than core/model classes.
 *
 * The clock advances with the frame id at 30 fps, so the wall-clock floor
 * added for review fix 21 behaves as it does on a healthy device. Cadence
 * itself is covered in KeepVisibleRecognizerImplCadenceTest.
 */
class KeepVisibleRecognizerImplTest {

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
    private var clockMs = 0L
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
    private val recognizer = FakeRecognizer { null }
    private val commands = KeepVisibleCommandQueue()
    private val liveTracks = LiveTrackRegistry(config) { clockMs }
    private val gate = RecognitionGate(config, liveTracks)
    private val controller = KeepVisibleControllerImpl(commands, state)
    private val keepVisible = KeepVisibleRecognizerImpl(
        commands = commands,
        liveTracks = liveTracks,
        selector = RecognitionCandidateSelector(
            commands, liveTracks, gate, state, store, config
        ),
        enrollmentPolicy = EnrollmentPolicy(
            recognizer, store, state, commands, liveTracks, config
        ),
        verificationPolicy = VerificationPolicy(recognizer, store, state, liveTracks, config),
        reVerificationPolicy = ReVerificationPolicy(recognizer, store, state, liveTracks, config),
        state = state,
        store = store,
    )

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

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    /** Advances the clock in step with the frame id: 30 fps. */
    private fun frame(frameId: Long, vararg boxes: TrackedBox) = runBlocking {
        clockMs = frameId * MS_PER_FRAME
        keepVisible.onDetectionFrame(Unit, metadata(frameId), boxes.toList())
    }

    /** Runs the real render gate over a single track, as the pipeline would. */
    private fun isKeptVisible(id: Long): Boolean =
        state.verifications.value.decorate(listOf(face(id))).single().keepVisible

    @Test
    fun `tap enrolls and trusts immediately`() {
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(1, face(1))
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state)
        assertEquals(1, store.trustedPersonCount)
        assertTrue(isKeptVisible(1))
    }

    @Test
    fun `re-entry needs K consecutive matches before unblurring`() {
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        // Track 1 died; same person returns as track 2. The first sighting
        // only registers the track — it is too young to spend a pass on.
        recognizer.next = { aliceAgain }
        frame(10, face(2))
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(2)).state)

        frame(20, face(2)) // first match
        assertEquals(VerificationState.PENDING, state.of(TrackId(2)).state)
        assertFalse(isKeptVisible(2))

        frame(30, face(2)) // second consecutive match
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(2)).state)
        assertTrue(isKeptVisible(2))
    }

    @Test
    fun `a stranger is rejected and stays blurred`(): Unit = runTest {
        // Enroll alice on track 1.
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(0, face(1))

        // A stranger appears on track 2 (first sighting registers the track).
        recognizer.next = { stranger }
        frame(10, face(2))

        // First mismatch: hysteresis — not rejected yet, but still blurred.
        frame(20, face(2))
        assertNotEquals(VerificationState.REJECTED, state.of(TrackId(2)).state)
        assertFalse(isKeptVisible(2))

        // Second consecutive mismatch: now rejected.
        frame(30, face(2))
        assertEquals(VerificationState.REJECTED, state.of(TrackId(2)).state)
        assertFalse(isKeptVisible(2))
    }

    @Test
    fun `null embedding is no decision, not a mismatch`() {
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        recognizer.next = { null }
        frame(10, face(2))
        frame(20, face(2))
        val v = state.of(TrackId(2))
        assertTrue(v.state == VerificationState.UNKNOWN || v.state == VerificationState.PENDING)
        assertEquals(0, v.consecutiveMatches)
        assertEquals(0, v.consecutiveMismatches)
        assertFalse("no decision must never unblur", isKeptVisible(2))
    }

    @Test
    fun `trusted track stolen by an ID switch is revoked after mismatches`() {
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        // Re-verification now sees a different face on the same track id.
        recognizer.next = { stranger }
        frame(12, face(1))  // mismatch 1 of 2 — still trusted (hysteresis)
        assertEquals(VerificationState.TRUSTED, state.of(TrackId(1)).state)
        frame(24, face(1))  // mismatch 2 of 2 — revoked
        assertEquals(VerificationState.REJECTED, state.of(TrackId(1)).state)
        assertFalse(isKeptVisible(1))
    }

    @Test
    fun `revokeAll re-blurs and wipes the store`() {
        recognizer.next = { alice }
        controller.requestKeepVisible(TrackId(1))
        frame(1, face(1))

        controller.revokeAll()
        assertFalse(isKeptVisible(1))
        frame(2, face(1)) // flag drained on ml thread
        assertEquals(0, store.trustedPersonCount)
    }

    private companion object {
        const val MS_PER_FRAME = 33L
    }
}