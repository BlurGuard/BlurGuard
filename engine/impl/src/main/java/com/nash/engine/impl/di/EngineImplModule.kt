package com.nash.engine.impl.di

import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageProxy
import com.nash.engine.render.AnonymizationCameraEffect
import com.nash.engine.render.AnonymizingSurfaceProcessor
import com.nash.engine.ml.MediaPipeFaceDetector
import com.nash.engine.ml.YoloDetector
import com.nash.engine.recognition.MobileFaceNetRecognizer
import com.nash.engine.impl.keepvisible.KeepVisibleOrchestrator
import com.nash.engine.impl.DefaultAnonymizationPipeline
import com.nash.core.model.Detector
import com.nash.core.model.DetectorBackend
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.Tracker
import com.nash.core.model.TrackerBackend
import com.nash.core.model.TrustedPersonStore
import com.nash.core.model.SessionTrustedPersonStore
import com.nash.engine.tracking.ByteTrackTracker
import com.nash.engine.tracking.ocsort.OcSortTracker
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.OcSortConfig
import com.nash.core.model.TrackerConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import com.nash.engine.impl.keepvisible.KeepVisibleController
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper

@Module
@InstallIn(SingletonComponent::class)
object EngineImplModule {

    @Provides
    @Singleton
    fun provideKeepVisibleOrchestrator(
        recognizer: @JvmSuppressWildcards FaceRecognizer<ImageProxy>,
        store: TrustedPersonStore,
        state: KeepVisibleState,
        config: RecognitionConfig
    ): KeepVisibleOrchestrator<ImageProxy> =
        KeepVisibleOrchestrator(recognizer, store, state, config)
    @Provides
    @Singleton
    fun provideKeepVisibleController(
        orchestrator: KeepVisibleOrchestrator<ImageProxy>
    ): KeepVisibleController = orchestrator
    /**
     * Assembles the pipeline and its stages, pinning F = ImageProxy exactly
     * once. Stages are internal to engine:impl, so they are built here rather
     * than exposed as individual @Provides bindings.
     */
    @Provides
    @Singleton
    fun provideAnonymizationPipeline(
        yoloDetector: YoloDetector,
        mediaPipeFaceDetector: MediaPipeFaceDetector,
        config: DetectorConfig,
        tracker: Tracker,
        renderBoxFeed: RenderBoxFeed,
        keepVisibleOrchestrator: KeepVisibleOrchestrator<ImageProxy>,
        keepVisibleState: KeepVisibleState,
    ): DefaultAnonymizationPipeline<ImageProxy> {
        val detectors: List<Detector<ImageProxy>> = when (config.backend) {
            DetectorBackend.YOLO -> listOf(yoloDetector)
            DetectorBackend.MEDIAPIPE -> listOf(mediaPipeFaceDetector)
        }
        return DefaultAnonymizationPipeline(
            scheduler = DetectionScheduler(DetectionScheduler.DEFAULT_DETECTION_INTERVAL),
            detectionRunner = DetectionRunner(detectors),
            trackingStage = TrackingStage(tracker),
            keepVisibleStage = KeepVisibleStage(keepVisibleOrchestrator, keepVisibleState),
            boxMapper = VisibleRegionBoxMapper(),
            publisher = TrackedBoxPublisher(renderBoxFeed),
            statsCollector = PipelineStatsCollector(),
        )
    }

    @Provides
    @Singleton
    fun provideRenderBoxFeed(): RenderBoxFeed = RenderBoxFeed()

    @Provides
    @Singleton
    fun provideAnonymizationModeHolder(): AnonymizationModeHolder = AnonymizationModeHolder()

    @Provides
    @Singleton
    fun provideAnonymizingSurfaceProcessor(
        renderBoxFeed: RenderBoxFeed,
        modeHolder: AnonymizationModeHolder,
    ): AnonymizingSurfaceProcessor = AnonymizingSurfaceProcessor(renderBoxFeed, modeHolder)

    @Provides
    @Singleton
    fun provideAnonymizationEffect(
        processor: AnonymizingSurfaceProcessor,
    ): CameraEffect = AnonymizationCameraEffect(processor)

    @Provides
    @Singleton
    fun provideOcSortConfig(): OcSortConfig = OcSortConfig()

    @Provides
    @Singleton
    fun provideTracker(
        byteTrack: Provider<ByteTrackTracker>,
        ocSort: Provider<OcSortTracker>,
    ): Tracker = byteTrack.get()

    @Provides
    @Singleton
    fun provideFaceRecognizer(
        recognizer: MobileFaceNetRecognizer
    ): FaceRecognizer<ImageProxy> = recognizer

    @Provides
    @Singleton
    fun provideTrustedPersonStore(config: RecognitionConfig): TrustedPersonStore =
        SessionTrustedPersonStore(
            maxGallerySize = config.maxGallerySize,
            duplicateSimilarity = config.duplicateSimilarity
        )
        
    @Provides
    @Singleton
    fun provideKeepVisibleState(): KeepVisibleState = KeepVisibleState()
    
    @Provides
    @Singleton
    fun provideRecognitionConfig(): RecognitionConfig = RecognitionConfig()
    
    @Provides
    @Singleton
    fun provideDetectorConfig(): DetectorConfig = DetectorConfig()

    @Provides
    @Singleton
    fun provideTrackerConfig(): TrackerConfig = TrackerConfig()
}
