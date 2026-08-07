package com.nash.engine.impl.factory

import androidx.camera.core.ImageProxy
import com.nash.core.model.TimeProvider
import com.nash.engine.impl.DefaultAnonymizationPipeline
import javax.inject.Inject

/**
 * Assembles the production [DefaultAnonymizationPipeline] for CameraX frames.
 *
 * Single responsibility (review fix: reduced from stage creation +
 * assembly): wire fresh stages from [PipelineStageFactory] into a pipeline
 * that shares ONE clock between frame timing and the stats collector, so
 * tests can drive both with a single fake. The pipeline's internal
 * constructor is reachable because this factory lives in the same module
 * (engine/impl) — visibility intentionally unchanged.
 */
internal class ImageProxyAnonymizationPipelineFactory @Inject constructor(
    private val stageFactory: PipelineStageFactory,
    private val timeProvider: TimeProvider,
) : AnonymizationPipelineFactory<ImageProxy> {

    override fun create(): DefaultAnonymizationPipeline<ImageProxy> {
        // One shared clock for the pipeline AND the stats collector. Time
        // access goes through the injected TimeProvider (review fix: no
        // direct System.nanoTime).
        val clock: () -> Long = timeProvider::nanoTime
        return DefaultAnonymizationPipeline(
            scheduler = stageFactory.scheduler(),
            detectionRunner = stageFactory.detectionRunner(),
            trackingStage = stageFactory.trackingStage(),
            keepVisibleStage = stageFactory.keepVisibleStage(),
            boxMapper = stageFactory.boxMapper(),
            publisher = stageFactory.publisher(),
            statsCollector = stageFactory.statsCollector(clock),
            clock = clock,
        )
    }
}