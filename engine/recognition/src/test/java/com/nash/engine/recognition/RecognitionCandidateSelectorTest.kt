package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionCandidateSelectorTest {

    private val config = RecognitionConfig(
        minFaceBoxPx = 72,
        minTrackAgeFrames = 4L,
        minTrackConfidence = 0.4f,
        minRecognitionIntervalMs = 150L,
        verifyRetryIntervalFrames = 6L,
        reVerifyIntervalFrames = 10L,
        rejectedRecheckMultiplier = 2L
    )
    private var clockMs = 100_000L
    private val commands = KeepVisibleCommandQueue()
    private val liveTracks = LiveTrackRegistry(config) { clockMs }
    private val gate = RecognitionGate(config, liveTracks)
    private val state = SessionKeepVisibleStateStore()
    private val store = SessionTrustedPersonStore(
        maxGallerySize = 5,
        duplicateSimilarity = 0.95f
    )
    private val selector = RecognitionCandidateSelector(
        commands, liveTracks, gate, state, store, config
    )

    private val alice = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!

    private fun face(
        id: Long,
        box: BoundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f)
    ) = TrackedBox(
        id = TrackId(id),
        box = box,
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )

    private fun metadata(frameId: Long) = FrameMetadata(
        frameId = frameId, width = 640, height = 360,
        rotationDegrees = 0, timestampNanos = frameId * 33
    )

    /** Registers [faces] with the registry, as the recognizer does each frame. */
    private fun onFrame(frameId: Long, vararg faces: TrackedBox) {
        liveTracks.onFrame(faces.toList(), metadata(frameId))
    }

    /** Frame bookkeeping + selection, as the recognizer does each frame. */
    private fun select(frameId: Long, vararg faces: TrackedBox): RecognitionCandidate? {
        onFrame(frameId, *faces)
        return selector.select(faces.toList(), metadata(frameId))
    }

    @Test
    fun `no faces yields no candidate`() {
        assertNull(select(0))
    }

    @Test
    fun `pending tap outranks an overdue verify`() {
        store.enroll(alice) // makes track 2 an overdue UNKNOWN verify target
        onFrame(0, face(1), face(2))
        commands.requestEnrollment(TrackId(1))

        assertEquals(
            RecognitionCandidate.Enroll(face(1)),
            select(10, face(1), face(2))
        )
    }

    @Test
    fun `gated tap blocks every automatic path`() {
        store.enroll(alice)
        val tiny = face(1, box = BoundingBox(0.45f, 0.45f, 0.55f, 0.55f)) // 64 x 36 px
        onFrame(0, tiny, face(2))
        commands.requestEnrollment(TrackId(1))

        // Track 2 is an overdue verify target, but the live tap reserves the
        // frame budget even while its size gate fails.
        assertNull(select(10, tiny, face(2)))
    }

    @Test
    fun `dead tap is dropped and selection falls through`() {
        store.enroll(alice)
        onFrame(0, face(1))
        commands.requestEnrollment(TrackId(99)) // never appears in a frame

        assertEquals(
            RecognitionCandidate.Verify(face(1)),
            select(10, face(1))
        )
        assertNull(commands.pendingEnrollment())
    }

    @Test
    fun `unknown faces are not verified while the gallery is empty`() {
        onFrame(0, face(1))

        assertNull(select(10, face(1)))
    }

    @Test
    fun `verify outranks re-verify`() {
        store.enroll(alice)
        onFrame(0, face(1), face(2))
        state.set(
            TrackId(2),
            TrackVerification(state = VerificationState.TRUSTED, lastCheckedFrame = 0L)
        )

        // Both are due at frame 15; the unknown face wins the budget.
        assertEquals(
            RecognitionCandidate.Verify(face(1)),
            select(15, face(1), face(2))
        )
    }

    @Test
    fun `trusted tracks re-verify on their cadence`() {
        onFrame(0, face(1))
        state.set(
            TrackId(1),
            TrackVerification(state = VerificationState.TRUSTED, lastCheckedFrame = 0L)
        )

        assertNull(select(9, face(1))) // one frame early
        assertEquals(
            RecognitionCandidate.ReVerify(face(1)),
            select(10, face(1))
        )
    }

    @Test
    fun `rejected tracks recheck at the slow cadence`() {
        onFrame(0, face(1))
        state.set(
            TrackId(1),
            TrackVerification(state = VerificationState.REJECTED, lastCheckedFrame = 0L)
        )

        assertNull(select(10, face(1))) // reVerify interval alone is not enough
        assertEquals(
            RecognitionCandidate.Verify(face(1)),
            select(20, face(1)) // reVerifyIntervalFrames * rejectedRecheckMultiplier
        )
    }
}