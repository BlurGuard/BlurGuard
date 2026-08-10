package com.nash.engine.recognition

import com.nash.core.model.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the trusted-person gallery, now that it lives beside the
 * policy that uses it. Threshold-agnostic by design: this store reports raw
 * similarity and never decides what counts as a match.
 */
class SessionTrustedPersonStoreTest {

    private val store = SessionTrustedPersonStore(
        maxGallerySize = 3,
        duplicateSimilarity = 0.95f
    )

    @Test
    fun `empty store matches nothing`() {
        assertNull(store.bestMatch(embedding(1f, 0f, 0f)))
        assertEquals(0, store.trustedPersonCount)
    }

    @Test
    fun `enroll creates distinct persons`() {
        val first = store.enroll(embedding(1f, 0f, 0f))
        val second = store.enroll(embedding(0f, 1f, 0f))

        assertNotEquals(first, second)
        assertEquals(2, store.trustedPersonCount)
    }

    @Test
    fun `bestMatch returns the closest person with raw similarity`() {
        val a = store.enroll(embedding(1f, 0f, 0f))
        store.enroll(embedding(0f, 1f, 0f))

        val match = store.bestMatch(embedding(1f, 0.05f, 0f))!!

        assertEquals(a, match.personId)
        assertTrue("similarity was ${match.similarity}", match.similarity > 0.99f)
    }

    @Test
    fun `gallery rejects near-duplicates`() {
        val person = store.enroll(embedding(1f, 0f, 0f))

        // Cosine similarity ~0.9999, above duplicateSimilarity.
        assertFalse(store.addToGallery(person, embedding(1f, 0.01f, 0f)))
        // A genuinely different pose is accepted.
        assertTrue(store.addToGallery(person, embedding(0.6f, 0.8f, 0f)))
    }

    @Test
    fun `gallery stops growing at maxGallerySize`() {
        val person = store.enroll(embedding(1f, 0f, 0f))

        assertTrue(store.addToGallery(person, embedding(0f, 1f, 0f)))
        assertTrue(store.addToGallery(person, embedding(0f, 0f, 1f)))
        // Fourth entry exceeds maxGallerySize = 3.
        assertFalse(store.addToGallery(person, embedding(1f, 1f, 1f)))
    }

    @Test
    fun `addToGallery ignores unknown persons`() {
        val person = store.enroll(embedding(1f, 0f, 0f))
        store.revoke(person)

        assertFalse(store.addToGallery(person, embedding(0f, 1f, 0f)))
    }

    @Test
    fun `revoke removes one person and revokeAll wipes the store`() {
        val a = store.enroll(embedding(1f, 0f, 0f))
        val b = store.enroll(embedding(0f, 1f, 0f))

        store.revoke(a)
        assertEquals(1, store.trustedPersonCount)
        assertEquals(b, store.bestMatch(embedding(0f, 1f, 0f))!!.personId)

        store.revokeAll()
        assertEquals(0, store.trustedPersonCount)
        assertNull(store.bestMatch(embedding(0f, 1f, 0f)))
    }

    private fun embedding(vararg values: Float): FaceEmbedding =
        FaceEmbedding.fromRaw(values)!!
}
