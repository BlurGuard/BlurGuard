package com.nash.engine.recognition

import com.nash.core.model.FaceEmbedding
import com.nash.core.model.PersonId
import com.nash.core.model.TrustedPersonStore

/**
 * Session-only, in-memory [TrustedPersonStore]. Nothing ever touches disk, so
 * trust dies with the process — the strictest reading of invariant #8
 * (embedding safety).
 *
 * Single-threaded by design: only ever touched from the ml dispatcher, matching
 * the tracker's threading model, and reached from the UI only through the
 * keep-visible controller. No internal locking. That contract is only
 * reviewable now that this class sits next to the orchestrator that owns the ml
 * thread, instead of in a leaf module anything could inject (review fix 16).
 */
class SessionTrustedPersonStore(
    private val maxGallerySize: Int,
    private val duplicateSimilarity: Float
) : TrustedPersonStore {

    private val galleries = LinkedHashMap<PersonId, MutableList<FaceEmbedding>>()
    private var nextId = 1L

    override val trustedPersonCount: Int get() = galleries.size

    override fun enroll(embedding: FaceEmbedding): PersonId {
        val id = PersonId(nextId++)
        galleries[id] = mutableListOf(embedding)
        return id
    }

    override fun addToGallery(personId: PersonId, embedding: FaceEmbedding): Boolean {
        val gallery = galleries[personId] ?: return false
        if (gallery.size >= maxGallerySize) return false
        // Near-duplicates add storage and compare cost but no verification value.
        if (gallery.any { it.cosineSimilarity(embedding) >= duplicateSimilarity }) return false
        gallery += embedding
        return true
    }

    override fun bestMatch(embedding: FaceEmbedding): TrustedPersonStore.Match? {
        var best: TrustedPersonStore.Match? = null
        for ((personId, gallery) in galleries) {
            for (stored in gallery) {
                val similarity = stored.cosineSimilarity(embedding)
                if (best == null || similarity > best.similarity) {
                    best = TrustedPersonStore.Match(personId, similarity)
                }
            }
        }
        return best
    }

    override fun revoke(personId: PersonId) {
        galleries.remove(personId)
    }

    override fun revokeAll() {
        galleries.clear()
    }
}