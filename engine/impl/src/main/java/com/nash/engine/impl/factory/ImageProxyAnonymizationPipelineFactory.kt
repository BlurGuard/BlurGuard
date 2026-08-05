package com.nash.engine.impl.factory

import androidx.camera.core.ImageProxy
import com.nash.core.model.DetectorConfig
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.TrackerConfig
import com.nash.engine.impl.DefaultAnonymizationPipeline
import com.nash.engine.impl.keepvisible.KeepVisibleOrchestrator
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper
import javax.inject.Inject

/**
 * Builds the production [DefaultAnonymizationPipeline] for CameraX frames.
 *
 * Moved out of EngineImplModule (review fix 12): backend selection and stage
 * wiring are owned here and unit-testable without Hilt. The pipeline's
 * internal constructor is reachable because this factory lives in the same
 * module (engine/impl) — visibility intentionally unchanged.
 */
internal class ImageProxyAnonymizationPipelineFactory @Inject constructor(
    private val detectorFactory: @JvmSuppressWildcards DetectorFactory<ImageProxy>,
    private val trackerFactory: TrackerFactory,
    private val detectorConfig: DetectorConfig,
    private val trackerConfig: TrackerConfig,
    private val renderBoxFeed: RenderBoxFeed,
    private val keepVisibleOrchestrator: KeepVisibleOrchestrator<ImageProxy>,
    private val keepVisibleState: KeepVisibleState,
) : AnonymizationPipelineFactory<ImageProxy> {

    override fun create(): DefaultAnonymizationPipeline<ImageProxy> {
        // One shared clock for the pipeline AND the stats collector, so tests
        // can drive both with a single fake clock.
        val clock: () -> Long = System::nanoTime
        return DefaultAnonymizationPipeline(
            scheduler = DetectionScheduler(),
            detectionRunner = DetectionRunner(detectorFactory.create(detectorConfig)),
            trackingStage = TrackingStage(trackerFactory.create(trackerConfig)),
            keepVisibleStage = KeepVisibleStage(keepVisibleOrchestrator, keepVisibleState),
            boxMapper = VisibleRegionBoxMapper(),
            publisher = TrackedBoxPublisher(renderBoxFeed),
            statsCollector = PipelineStatsCollector(clock = clock),
            clock = clock,
        )
    }
}