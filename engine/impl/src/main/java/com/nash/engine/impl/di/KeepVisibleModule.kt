package com.nash.engine.impl.di

import android.content.Context
import androidx.camera.core.ImageProxy
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import com.nash.engine.impl.logging.AndroidRecognitionLogger
import com.nash.engine.recognition.KeepVisibleOrchestrator
import com.nash.engine.recognition.RecognitionLogger
import com.nash.engine.recognition.SessionKeepVisibleStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Keep-visible policy bindings: the orchestrator, its two interface facets,
 * and the session-scoped verification state store (split out of the former
 * EngineImplModule — review fix: focused, cohesive DI modules).
 */
@Module
@InstallIn(SingletonComponent::class)
object KeepVisibleModule {

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
        recognizer: @JvmSuppressWildcards FaceRecognizer<ImageProxy>,
        store: TrustedPersonStore,
        state: KeepVisibleStateStore,
        config: RecognitionConfig,
        logger: RecognitionLogger,
    ): KeepVisibleOrchestrator<ImageProxy> = KeepVisibleOrchestrator(
        recognizer = recognizer,
        store = store,
        state = state,
        config = config,
        logger = logger,
    )

    /**
     * The single place keep-visible logging meets android.util.Log. Debug
     * tracing only in debug builds; warnings always survive (review fix:
     * domain policy no longer imports the Android framework logger).
     */
    @Provides
    @Singleton
    fun provideRecognitionLogger(
        @ApplicationContext context: Context,
    ): RecognitionLogger = AndroidRecognitionLogger(
        debugEnabled = context.isDebugBuild(),
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
}