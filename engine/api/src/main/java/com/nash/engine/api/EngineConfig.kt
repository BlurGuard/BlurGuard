package com.nash.engine.api

/**
 * Configuration for the BlurGuard engine.
 */
data class EngineConfig(
    val initialMode: AnonymizationMode = AnonymizationMode.BLUR,
    val initialTrustedFaces: List<TrustedFaceRef> = emptyList(),
    val detectionIntervalFrames: Int = 2
)
