package com.nash.engine.api

/**
 * Non-fatal warnings from the engine pipeline.
 */
sealed class EngineWarning {
    data class MlInitializationFailed(val message: String) : EngineWarning()
    data class HighLatency(val latencyMillis: Long) : EngineWarning()
    data class StorageLow(val remainingBytes: Long) : EngineWarning()
    object CameraTimedOut : EngineWarning()
}
