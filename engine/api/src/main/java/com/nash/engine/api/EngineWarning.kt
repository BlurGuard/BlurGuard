package com.nash.engine.api

/**
 * Non-fatal warnings from the engine pipeline.
 */
sealed class EngineWarning {
    data class MlInitializationFailed(val message: String) : EngineWarning()
    data class HighLatency(val latencyMillis: Long) : EngineWarning()
    data class StorageLow(val remainingBytes: Long) : EngineWarning()
    data object CameraTimedOut : EngineWarning()

    /**
     * At least one detector failed on the last detection pass; objects in
     * frame may be temporarily unprotected.
     */
    data object DetectionDegraded : EngineWarning()
}