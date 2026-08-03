package com.nash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionContractsTest {

    private fun embedding(vararg v: Float) = FaceEmbedding.fromRaw(floatArrayOf(*v))!!

    private fun store() = SessionTrustedPersonStore(maxGallerySize = 3, duplicateSimilarity = 0.95f)

    @Test
    fun `degenerate raw vectors are rejected`() {
        assertNull(FaceEmbedding.fromRaw(floatArrayOf(0f, 0f, 0f)))
        assertNull(FaceEmbedding.fromRaw(floatArrayOf(1f, Float.NaN, 0f)))
    }

    @Test
    fun `best match finds the enrolled person with max cosine`() {
        val s = store()
        val alice = s.enroll(embedding(1f, 0f, 0f))
        s.enroll(embedding(0f, 1f, 0f)) // someone else
        val probe = embedding(0.9f, 0.1f, 0f) // close to alice
        val match = s.bestMatch(probe)!!
        assertEquals(alice, match.personId)
        assertTrue(match.similarity > 0.9f)
    }

    @Test
    fun `gallery rejects near duplicates and respects the cap`() {
        val s = store()
        val id = s.enroll(embedding(1f, 0f, 0f))
        assertFalse("near-duplicate must be rejected", s.addToGallery(id, embedding(0.99f, 0.01f, 0f)))
        assertTrue(s.addToGallery(id, embedding(0.6f, 0.8f, 0f)))
        assertTrue(s.addToGallery(id, embedding(0f, 0.6f, 0.8f)))
        assertFalse("cap of 3 reached", s.addToGallery(id, embedding(0.8f, 0f, 0.6f)))
    }

    @Test
    fun `revokeAll empties the store`() {
        val s = store()
        s.enroll(embedding(1f, 0f, 0f))
        s.revokeAll()
        assertEquals(0, s.trustedPersonCount)
        assertNull(s.bestMatch(embedding(1f, 0f, 0f)))
    }

    @Test
    fun `decorate unblurs only TRUSTED faces`() {
        val state = KeepVisibleState()
        val trustedFace = box(1, DetectionClass.FACE)
        val pendingFace = box(2, DetectionClass.FACE)
        val plate = box(3, DetectionClass.LICENSE_PLATE)

        state.set(TrackId(1), TrackVerification(VerificationState.TRUSTED, PersonId(1)))
        state.set(TrackId(2), TrackVerification(VerificationState.PENDING))
        // Plate deliberately marked TRUSTED to prove the class gate holds:
        state.set(TrackId(3), TrackVerification(VerificationState.TRUSTED, PersonId(1)))

        val out = state.decorate(listOf(trustedFace, pendingFace, plate))
        assertTrue(out[0].keepVisible)
        assertFalse(out[1].keepVisible)
        assertFalse("plates must never be keep-visible", out[2].keepVisible)
    }

    @Test
    fun `retainTracks drops dead tracks and clearAll reblurs everything`() {
        val state = KeepVisibleState()
        state.set(TrackId(1), TrackVerification(VerificationState.TRUSTED, PersonId(1)))
        state.set(TrackId(2), TrackVerification(VerificationState.REJECTED))
        state.retainTracks(setOf(TrackId(1)))
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(2)).state)
        assertNotNull(state.of(TrackId(1)).personId)
        state.clearAll()
        assertFalse(state.decorate(listOf(box(1, DetectionClass.FACE))).single().keepVisible)
    }

    private fun box(id: Long, clazz: DetectionClass) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        clazz = clazz,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )
}
