package com.nash.core.model

/**
 * Configuration for the OC-SORT tracker (core/tracking).
 *
 * Framework-free; bound in the app DI module.
 *
 * @property highScoreThreshold Detections at/above this score drive matching and
 * may spawn new tracks (first association).
 * @property lowScoreThreshold Detections between this and [highScoreThreshold]
 * may only re-confirm existing tracks (BYTE second association).
 * @property iouThreshold Minimum IoU for a detection-to-track match.
 * @property maxLostFrames How many frames a track survives (and keeps being
 * reported for blurring) without any matching detection. Privacy-first: while
 * lost, the object keeps its blur; do not set this low.
 * @property ocmWeight Weight of the observation-centric momentum (direction
 * consistency) term added to the IoU cost. OC-SORT default is 0.2.
 * @property ocrIouThreshold IoU threshold for the last-resort recovery stage
 * (OCR), which matches unmatched detections against a lost track's last
 * *observed* box instead of its Kalman prediction.
 */
data class OcSortConfig(
    val highScoreThreshold: Float = 0.45f,
    val lowScoreThreshold: Float = 0.1f,
    val iouThreshold: Float = 0.1f,
    val maxLostFrames: Long = 12L,
    val ocmWeight: Double = 0.25,
    val ocrIouThreshold: Float = 0.3f
)

/** Selectable tracker implementation, for A/B on the same footage. */
enum class TrackerBackend { BYTE_TRACK, OC_SORT }