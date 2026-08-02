package com.nash.core.model

/**
 * Store of trusted-person embedding galleries.
 *
 * The store is threshold-agnostic: it reports the best raw similarity and the
 * orchestrator (core/domain) decides what counts as a match via
 * [RecognitionConfig.matchThreshold].
 *
 * MVP implementation is session-only and in-memory ([SessionTrustedPersonStore]);
 * a persistent implementation must use the encrypted on-device store and hook
 * into panic delete (architecture invariant #6) — flagged as a mentor decision.
 */
interface TrustedPersonStore {

    data class Match(val personId: PersonId, val similarity: Float)

    /** Creates a new trusted person seeded with one embedding. */
    fun enroll(embedding: FaceEmbedding): PersonId

    /**
     * Opportunistically grows a person's gallery (pose/lighting variety).
     * @return true if stored; false if the gallery is full, the person is
     * unknown, or the embedding is a near-duplicate of an existing entry.
     */
    fun addToGallery(personId: PersonId, embedding: FaceEmbedding): Boolean

    /**
     * Best match across all trusted persons, using max cosine similarity over
     * each person's gallery. Null when no persons are enrolled.
     */
    fun bestMatch(embedding: FaceEmbedding): Match?

    fun revoke(personId: PersonId)

    /** Wipes everything. Wire to "Revoke all" UI and panic delete. */
    fun revokeAll()

    val trustedPersonCount: Int
}