package com.nash.core.model

import kotlin.math.sqrt

/**
 * An L2-normalized face embedding produced by a [FaceRecognizer].
 *
 * Biometric data: instances must never be logged, serialized, or leave the
 * process. Session-only by default; any persistence must go through the
 * encrypted store (architecture invariant #6).
 */
class FaceEmbedding private constructor(private val values: FloatArray) {

    val dimension: Int get() = values.size

    /**
     * Cosine similarity in [-1, 1]. Both vectors are unit-length by
     * construction, so this is a plain dot product.
     */
    fun cosineSimilarity(other: FaceEmbedding): Float {
        require(other.values.size == values.size) {
            "Embedding dimension mismatch: ${values.size} vs ${other.values.size}"
        }
        var dot = 0f
        for (i in values.indices) dot += values[i] * other.values[i]
        return dot
    }

    /** Never expose vector contents (no biometrics in logs/crash reports). */
    override fun toString(): String = "FaceEmbedding(dim=$dimension)"

    companion object {
        /**
         * Wraps raw model output, L2-normalizing it.
         * Returns null for degenerate (near-zero or non-finite) vectors —
         * callers must treat that as "no embedding this frame" (fail-closed).
         */
        fun fromRaw(raw: FloatArray): FaceEmbedding? {
            var sumSq = 0.0
            for (v in raw) {
                if (!v.isFinite()) return null
                sumSq += v.toDouble() * v.toDouble()
            }
            val norm = sqrt(sumSq)
            if (norm < 1e-6) return null
            return FaceEmbedding(FloatArray(raw.size) { (raw[it] / norm).toFloat() })
        }
    }
}
