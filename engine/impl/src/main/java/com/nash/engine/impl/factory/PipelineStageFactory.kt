package com.nash.engine.impl.factory

import androidx.camera.core.ImageProxy
import com.nash.core.model.DetectorConfig
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.TrackerConfig
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper
import javax.inject.Inject

/**
 * Creates the individual pipeline stages for CameraX frames (review fix:
 * stage creation isolated from pipeline assembly).
 *
 * Owns backend selection and config forwarding: every stage that needs a
 * detector, tracker or config gets it here, so the assembling factory
 * ([ImageProxyAnonymizationPipelineFactory]) never touches sub-factories.
 * Each method returns a FRESH stage — stages are stateful and must never
 * be shared between pipelines.
 */
internal class PipelineStageFactory @Inject constructor(
    private val detectorFactory: @JvmSuppressWildcards DetectorFactory<ImageProxy>,
    private val trackerFactory: TrackerFactory,
    private val detectorConfig: DetectorConfig,
    private val trackerConfig: TrackerConfig,
    private val renderBoxFeed: RenderBoxFeed,
    private val keepVisibleRecognizer: @JvmSuppressWildcards KeepVisibleRecognizer<ImageProxy>,
    private val keepVisibleState: KeepVisibleStateReader,
) {

    fun scheduler(): DetectionScheduler = DetectionScheduler()

    fun detectionRunner(): DetectionRunner<ImageProxy> =
        DetectionRunner(detectorFactory.create(detectorConfig))

    fun trackingStage(): TrackingStage = TrackingStage(trackerFactory.create(trackerConfig))

    fun keepVisibleStage(): KeepVisibleStage<ImageProxy> =
        KeepVisibleStage(keepVisibleRecognizer, keepVisibleState)

    fun boxMapper(): VisibleRegionBoxMapper = VisibleRegionBoxMapper()

    fun publisher(): TrackedBoxPublisher = TrackedBoxPublisher(renderBoxFeed)

    fun statsCollector(clock: () -> Long): PipelineStatsCollector =
        PipelineStatsCollector(clock = clock)
}
