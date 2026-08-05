package com.nash.engine.impl.di

import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageProxy
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.OcSortConfig
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.TrackerConfig
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import com.nash.engine.impl.DefaultAnonymizationPipeline
import com.nash.engine.impl.factory.ImageProxyAnonymizationPipelineFactory
import com.nash.engine.ml.recognition.MobileFaceNetRecognizer
import com.nash.engine.recognition.KeepVisibleOrchestrator
import com.nash.engine.recognition.SessionKeepVisibleStateStore
import com.nash.engine.recognition.SessionTrustedPersonStore
import com.nash.engine.render.AnonymizationCameraEffect
import com.nash.engine.render.AnonymizingSurfaceProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineImplModule {

    /**
     * The single instance behind all three keep-visible seams. It is the only
     * place in engine/impl that names the concrete orchestrator; everything
     * else injects [KeepVisibleController] or [KeepVisibleRecognizer].
     */
    @Provides
    @Singleton
    fun provideKeepVisibleOrchestrator(
        recognizer: @JvmSuppressWildcards FaceRecognizer<ImageProxy>,
        store: TrustedPersonStore,
        state: KeepVisibleStateStore,
        config: RecognitionConfig
    ): KeepVisibleOrchestrator<ImageProxy> =
        KeepVisibleOrchestrator(recognizer, store, state, config)

    @Provides
    @Singleton
    fun provideKeepVisibleController(
        orchestrator: KeepVisibleOrchestrator<ImageProxy>
    ): KeepVisibleController = orchestrator

    /**
     * [JvmSuppressWildcards] for the same reason as DetectorFactory: without it
     * Dagger looks for `KeepVisibleRecognizer<? extends ImageProxy>`.
     */
    @Provides
    @Singleton
    fun provideKeepVisibleRecognizer(
        orchestrator: KeepVisibleOrchestrator<ImageProxy>
    ): @JvmSuppressWildcards KeepVisibleRecognizer<ImageProxy> = orchestrator

    /**
     * Construction and backend selection live in the factory (review fixes
     * 12/13) — this provider only delegates. No `when` branching here.
     */
    @Provides
    @Singleton
    internal fun provideAnonymizationPipeline(
        factory: ImageProxyAnonymizationPipelineFactory,
    ): DefaultAnonymizationPipeline<ImageProxy> = factory.create()

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

    /**
     * Embedding extraction is an engine/ml concern; identity policy consumes it
     * only through [FaceRecognizer] (review fix 15).
     */
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

    /** Write side: injected only into the orchestrator. */
    @Provides
    @Singleton
    fun provideKeepVisibleStateStore(): KeepVisibleStateStore = SessionKeepVisibleStateStore()

    /**
     * Read side: the same instance, exposed as a handle that cannot promote a
     * track to TRUSTED. This is what the render gate and the UI get.
     */
    @Provides
    @Singleton
    fun provideKeepVisibleStateReader(store: KeepVisibleStateStore): KeepVisibleStateReader = store

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