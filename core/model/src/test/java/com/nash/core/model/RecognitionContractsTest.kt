package com.nash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts owned by core/model: embedding construction and THE render gate.
 *
 * Deliberately store-free. The trust *decision* and its mutable state live in
 * engine/recognition and are tested there (review fix 16); what stays here is
 * immutable data plus the pure rule that decides which boxes may go unblurred.
 */
class RecognitionContractsTest {

    private fun embedding(vararg v: Float) = FaceEmbedding.fromRaw(floatArrayOf(*v))!!

    @Test
    fun `degenerate raw vectors are rejected`() {
        assertNull(FaceEmbedding.fromRaw(floatArrayOf(0f, 0f, 0f)))
        assertNull(FaceEmbedding.fromRaw(floatArrayOf(1f, Float.NaN, 0f)))
        assertNull(FaceEmbedding.fromRaw(floatArrayOf(1f, Float.POSITIVE_INFINITY, 0f)))
    }

    @Test
    fun `embeddings are L2 normalized so cosine ignores magnitude`() {
        // Same direction, very different magnitudes.
        assertEquals(1f, embedding(2f, 0f, 0f).cosineSimilarity(embedding(500f, 0f, 0f)), 1e-4f)
        assertEquals(0f, embedding(1f, 0f, 0f).cosineSimilarity(embedding(0f, 1f, 0f)), 1e-4f)
        assertEquals(-1f, embedding(1f, 0f, 0f).cosineSimilarity(embedding(-3f, 0f, 0f)), 1e-4f)
    }

    @Test
    fun `default verification is unknown, which means blurred`() {
        val default = TrackVerification()

        assertEquals(VerificationState.UNKNOWN, default.state)
        assertNull(default.personId)
        assertEquals(0, default.consecutiveMatches)
        assertEquals(0, default.consecutiveMismatches)
        assertEquals(-1L, default.lastCheckedFrame)
    }

    @Test
    fun `decorate unblurs only TRUSTED faces`() {
        val trustedFace = box(1, DetectionClass.FACE)
        val pendingFace = box(2, DetectionClass.FACE)
        val rejectedFace = box(3, DetectionClass.FACE)
        val unknownFace = box(4, DetectionClass.FACE)
        val verifications = mapOf(
            TrackId(1) to TrackVerification(VerificationState.TRUSTED, PersonId(1)),
            TrackId(2) to TrackVerification(VerificationState.PENDING, PersonId(1)),
            TrackId(3) to TrackVerification(VerificationState.REJECTED, PersonId(1)),
            // Track 4 deliberately absent: absent must behave as UNKNOWN.
        )

        val out = verifications.decorate(
            listOf(trustedFace, pendingFace, rejectedFace, unknownFace)
        )

        assertEquals(listOf(true, false, false, false), out.map { it.keepVisible })
    }

    @Test
    fun `plates are never keep-visible even when marked TRUSTED`() {
        val plate = box(1, DetectionClass.LICENSE_PLATE)
        // Corrupt state on purpose to prove the class gate holds on its own.
        val verifications = mapOf(
            TrackId(1) to TrackVerification(VerificationState.TRUSTED, PersonId(1))
        )

        assertFalse(
            "plates must never be keep-visible",
            verifications.decorate(listOf(plate)).single().keepVisible
        )
    }

    @Test
    fun `an empty verification map leaves every box blurred`() {
        val boxes = listOf(box(1, DetectionClass.FACE), box(2, DetectionClass.LICENSE_PLATE))

        val out = emptyMap<TrackId, TrackVerification>().decorate(boxes)

        assertTrue("no state means nothing to unblur", out.none { it.keepVisible })
        assertTrue("nothing to do, so no copies", out === boxes)
    }

    @Test
    fun `decorate does not mutate its inputs`() {
        val face = box(1, DetectionClass.FACE)
        val verifications = mapOf(
            TrackId(1) to TrackVerification(VerificationState.TRUSTED, PersonId(1))
        )

        val out = verifications.decorate(listOf(face))

        assertTrue(out.single().keepVisible)
        assertFalse("the input box must be untouched", face.keepVisible)
    }

    private fun box(id: Long, clazz: DetectionClass) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        clazz = clazz,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )
}
