package com.nash.core.model

/**
 * Configuration for the [Tracker] implementation (ByteTrack-style).
 *
 * Framework-free; bound in the app DI module.
 *
 * @property highScoreThreshold Detections at/above this score drive matching and
 * may spawn new tracks (ByteTrack "first association").
 * @property lowScoreThreshold Detections between this and [highScoreThreshold]
 * may only re-confirm existing tracks (ByteTrack "second association") — they
 * recover occluded/motion-blurred objects but never create tracks.
 * @property iouThreshold Minimum IoU for a detection-to-track match.
 * @property maxLostFrames How many frames a track survives (and keeps being
 * reported for blurring) without any matching detection. Privacy-first: while
 * lost, the object keeps its blur; do not set this low.
 * @property velocitySmoothing Exponential smoothing factor for the linear
 * motion model (0 = use only the newest velocity, closer to 1 = smoother).
 */
data class TrackerConfig(
    val highScoreThreshold: Float = 0.45f,
    val lowScoreThreshold: Float = 0.1f,
    val iouThreshold: Float = 0.1f,
    val maxLostFrames: Long = 3L,
    val velocitySmoothing: Float = 0.9f
)