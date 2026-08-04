package com.nash.feature.camera

/**
 * Debug HUD counters for tracked IDs.
 *
 * Standalone UI model so composables depend on stable UI types,
 * not on [CameraViewModel] implementation details.
 */
data class CameraDebugStatsUiModel(
    val active: Int = 0,
    val totalSeen: Int = 0
)