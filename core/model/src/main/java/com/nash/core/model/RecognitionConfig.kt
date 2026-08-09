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
 * pixels) are not embedded — too small to verify reliably. Enforced inside the
 * aligner, after dilation.
 * @property minFaceBoxPx Minimum tracker-box size, in upright analysis-frame
 * pixels, on BOTH axes, before a face is worth the expensive recognition path.
 *
 * Derived from measured device behavior, not from [minFaceCropPx]. The binding
 * constraint downstream is the aligner's 20 px minimum inter-eye distance, and
 * dilation cannot help it — widening the crop adds background, it does not
 * magnify the face. Observed on-device: a ~35 px box yields ~15 px inter-eye,
 * a ~52 px box yields ~19 px, i.e. roughly `interEye ~= 0.4 * boxHeight`.
 * Clearing 20 px with headroom therefore needs ~65 px; 72 adds margin for
 * pose and lighting. Anything smaller is rejected here for free rather than
 * after ~34 ms of bitmap, rotation, crop and BlazeFace work.
 *
 * Raising this makes the app blur MORE (fail-closed): distant faces simply
 * stay anonymized until they are close enough to identify reliably.
 * @property minTrackAgeFrames A track must survive this many frames before it
 * is eligible for automatic recognition, so tracker noise does not trigger
 * inference.
 * @property minTrackConfidence Minimum detector confidence for the automatic
 * recognition path.
 * @property minRecognitionIntervalMs Wall-clock floor between two recognition
 * passes on the same track. Bounds worst-case cost independently of frame rate.
 * @property reVerifyIntervalFrames How often (in frame IDs) a TRUSTED track is
 * re-verified. Defense against tracker ID switches: if the track was stolen by
 * a different face, re-verification revokes it. ~2 s at 30 fps.
 * @property mismatchesToRevoke Consecutive re-verification mismatches before a
 * TRUSTED track is demoted and re-blurred.
 * @property verifyRetryIntervalFrames Frame-ID interval between automatic
 * verification passes on the same UNKNOWN/PENDING track. Short, because an
 * unverified face that should be trusted stays blurred until it passes.
 * @property maxEnrollAttempts How many failed (null-embed) passes a tapped
 * enrollment may burn before giving up fail-closed: the request is dropped
 * and the track reverts to UNKNOWN, i.e. blurred.
 * @property rejectedRecheckMultiplier Multiplier over [reVerifyIntervalFrames]
 * for the slow recheck of REJECTED tracks. Was 4 — shortened: mismatch
 * hysteresis (F2) makes rejection safe to retry sooner.
 */
data class RecognitionConfig(
    val matchThreshold: Float = 0.45f,
    val consecutiveMatchesToTrust: Int = 2,
    val maxGallerySize: Int = 5,
    val duplicateSimilarity: Float = 0.95f,
    val minFaceCropPx: Int = 64,
    val minFaceBoxPx: Int = 72,
    val minTrackAgeFrames: Long = 4L,
    val minTrackConfidence: Float = 0.4f,
    val minRecognitionIntervalMs: Long = 150L,
    val reVerifyIntervalFrames: Long = 60L,
    val mismatchesToRevoke: Int = 2,
    val verifyRetryIntervalFrames: Long = 6L,
    val maxEnrollAttempts: Int = 10,
    val rejectedRecheckMultiplier: Long = 2L
)