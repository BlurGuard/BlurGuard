package com.nash.core.model

/** Debug/perf counters for the anonymization pipeline. */
data class PipelineStats(

    val frameFps: Float = 0f,
    /** Frames processed per second (post-backpressure, i.e. real detection cadence). */
    val fps: Float = 0f,
    /** Wall time of the last detection pass (all detectors), in ms. */
    val detectionLatencyMillis: Long = 0L
)
