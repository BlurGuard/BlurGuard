package com.nash.engine.api

import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The main internal SDK entry point for BlurGuard.
 * Features consume this API only.
 */
interface BlurGuardEngine {
    /**
     * Binds the engine to a lifecycle and a preview target.
     * The engine starts its pipeline when the lifecycle is STARTED.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewTarget: PreviewTarget,
        config: EngineConfig = EngineConfig()
    )

    /**
     * Starts recording the anonymized output.
     * Returns a Flow of recording states.
     */
    fun startRecording(request: RecordingRequest): Flow<RecordingState>

    /**
     * Stops the current recording.
     */
    suspend fun stopRecording()

    /**
     * Updates the anonymization strategy in real-time.
     */
    suspend fun updateAnonymizationMode(mode: AnonymizationMode)

    /**
     * Updates the list of faces to keep visible.
     */
    suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>)

    /**
     * Observes warnings from the engine pipeline.
     */
    fun observeWarnings(): Flow<EngineWarning>

    /**
     * Safe metadata-only tracked boxes for UI overlays.
     * No pixel data is exposed here.
     */
    val trackedBoxes: StateFlow<List<TrackedBox>>

    /**
     * Performance metrics for the pipeline.
     */
    val stats: StateFlow<PipelineStats>
}
