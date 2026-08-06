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
 * pixels) are not embedded — too small to verify reliably. Enforced by the
 * aligner on the DILATED crop, i.e. after the bitmap work; see [minFaceBoxPx]
 * for the cheap gate that runs first.
 * @property reVerifyIntervalFrames How often (in frame IDs) a TRUSTED track is
 * re-verified. Defense against tracker ID switches: if the track was stolen by
 * a different face, re-verification revokes it. ~2 s at 30 fps.
 * @property mismatchesToRevoke Consecutive re-verification mismatches before a
 * TRUSTED track is demoted and re-blurred.
 * @property minRecognitionIntervalMs Wall-clock floor between two recognizer
 * calls ON THE SAME TRACK. The frame-count intervals above stay the policy for
 * *when* a check is due; this is the cost bound that survives an fps change,
 * and it is what stops a tap from firing ten full passes back to back. At the
 * default detection cadence (every 2nd frame, 30 fps) the frame intervals are
 * already wider than this, so it changes nothing in the normal case.
 * @property minFaceBoxPx Cheap pre-gate, in analysis-frame pixels, on the RAW
 * tracker box — evaluated before any bitmap is allocated. Deliberately looser
 * than [minFaceCropPx]: the aligner sees a crop dilated by 25% on each side,
 * so a raw box of 64/1.5 ≈ 43 px already passes it. 40 stays below that, which
 * makes this gate strictly more permissive than the aligner's — it exists to
 * skip obviously-tiny boxes for free, never to change which faces verify.
 * @property minTrackAgeFrames How long (in frame IDs, not detection passes) a
 * track must have been observed before AUTOMATIC recognition will spend a pass
 * on it. Brand-new tracks are disproportionately motion-blurred or spurious.
 * An explicit user tap ignores this.
 * @property minTrackConfidence Detector confidence below which a box is not
 * worth embedding. Applies to automatic recognition only; a tap is explicit
 * user intent and is never blocked by a heuristic.
 */
data class RecognitionConfig(
    val matchThreshold: Float = 0.45f,
    val consecutiveMatchesToTrust: Int = 2,
    val maxGallerySize: Int = 5,
    val duplicateSimilarity: Float = 0.95f,
    val minFaceCropPx: Int = 64,
    val reVerifyIntervalFrames: Long = 60L,
    val mismatchesToRevoke: Int = 2,
    val minRecognitionIntervalMs: Long = 150L,
    val minFaceBoxPx: Int = 40,
    val minTrackAgeFrames: Long = 4L,
    val minTrackConfidence: Float = 0.4f
)