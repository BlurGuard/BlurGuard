package com.nash.core.model

/**
 * Configuration for the keep-visible recognition flow.
 *
 * Framework-free; bound in the app DI module.
 *
 * @property matchThreshold Minimum cosine similarity to count as "same person".
 * PLACEHOLDER — must be calibrated on the target device with the real model
 * and alignment (Phase 3 calibration task). Deliberately strict: a false
 * accept unblurs a stranger, which is the one unacceptable failure.
 * @property consecutiveMatchesToTrust A new track must match a trusted person
 * on this many consecutive recognition passes before it is unblurred.
 * @property maxGallerySize Max embeddings kept per person (pose/lighting variety).
 * @property duplicateSimilarity Gallery entries more similar than this to an
 * existing entry are not added (no value in near-duplicates).
 * @property minFaceCropPx Faces whose crop is smaller than this (in analysis-frame
 * pixels) are not embedded — too small to verify reliably.
 * @property reVerifyIntervalFrames How often (in frame IDs) a TRUSTED track is
 * re-verified. Defense against tracker ID switches: if the track was stolen by
 * a different face, re-verification revokes it. ~2 s at 30 fps.
 * @property mismatchesToRevoke Consecutive re-verification mismatches before a
 * TRUSTED track is demoted and re-blurred.
 */
data class RecognitionConfig(
    val matchThreshold: Float = 0.45f,
    val consecutiveMatchesToTrust: Int = 2,
    val maxGallerySize: Int = 5,
    val duplicateSimilarity: Float = 0.95f,
    val minFaceCropPx: Int = 64,
    val reVerifyIntervalFrames: Long = 60L,
    val mismatchesToRevoke: Int = 2
)
