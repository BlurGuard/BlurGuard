package com.nash.engine.impl.di

import android.content.Context
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
import com.nash.engine.impl.AnonymizationPipeline
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineImplModule {

    /**
     * The orchestrator is the single owner of keep-visible policy: the
     * enrolment gallery, the per-track verification counters and the cadence
     * timestamps all live in its fields. It is provided as its concrete type
     * exactly once, and the two interfaces below republish that same
     * instance. Binding them separately would give the UI a different
     * gallery from the one the pipeline reads, and tapped faces would never
     * unblur.
     */
    @Provides
    @Singleton
    fun provideKeepVisibleOrchestrator(
        @ApplicationContext context: Context,
        recognizer: @JvmSuppressWildcards FaceRecognizer<ImageProxy>,
        store: TrustedPersonStore,
        state: KeepVisibleStateStore,
        config: RecognitionConfig,
    ): KeepVisibleOrchestrator<ImageProxy> = KeepVisibleOrchestrator(
        recognizer = recognizer,
        store = store,
        state = state,
        config = config,
        debugLogging = context.isDebugBuild(),
    )

    /** UI-facing half: tap to keep visible, revoke everything. */
    @Provides
    @Singleton
    fun provideKeepVisibleController(
        orchestrator: KeepVisibleOrchestrator<ImageProxy>,
    ): KeepVisibleController = orchestrator

    /** Pipeline-facing half: consumed by KeepVisibleStage each detection frame. */
    @Provides
    @Singleton
    fun provideKeepVisibleRecognizer(
        orchestrator: KeepVisibleOrchestrator<ImageProxy>,
    ): @JvmSuppressWildcards KeepVisibleRecognizer<ImageProxy> = orchestrator

    /**
     * The mutable store lives in engine/recognition (review fix 16);
     * core/model owns only the interfaces and the immutable types.
     */
    @Provides
    @Singleton
    fun provideKeepVisibleStateStore(): KeepVisibleStateStore = SessionKeepVisibleStateStore()

    /** Read-only view for consumers that must not mutate verification state. */
    @Provides
    @Singleton
    fun provideKeepVisibleStateReader(
        store: KeepVisibleStateStore,
    ): KeepVisibleStateReader = store

    /**
     * Construction and backend selection live in the factory (review fixes
     * 12/13) — this provider only delegates. No `when` branching here.
     */
    @Provides
    @Singleton
    internal fun provideAnonymizationPipeline(
        factory: ImageProxyAnonymizationPipelineFactory,
    ): AnonymizationPipeline<ImageProxy> = factory.create()

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
    fun provideFaceRecognizer(
        recognizer: MobileFaceNetRecognizer,
    ): FaceRecognizer<ImageProxy> = recognizer

    @Provides
    @Singleton
    fun provideTrustedPersonStore(config: RecognitionConfig): TrustedPersonStore =
        SessionTrustedPersonStore(
            maxGallerySize = config.maxGallerySize,
            duplicateSimilarity = config.duplicateSimilarity,
        )

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