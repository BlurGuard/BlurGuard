package com.nash.engine.impl.di

import android.content.Context
import androidx.camera.core.ImageProxy
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TimeProvider
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.api.keepvisible.KeepVisibleRecognizer
import com.nash.engine.impl.logging.AndroidRecognitionLogger
import com.nash.engine.recognition.EnrollmentPolicy
import com.nash.engine.recognition.KeepVisibleCommandQueue
import com.nash.engine.recognition.KeepVisibleControllerImpl
import com.nash.engine.recognition.KeepVisibleRecognizerImpl
import com.nash.engine.recognition.LiveTrackRegistry
import com.nash.engine.recognition.ReVerificationPolicy
import com.nash.engine.recognition.RecognitionCandidateSelector
import com.nash.engine.recognition.RecognitionGate
import com.nash.engine.recognition.RecognitionLogger
import com.nash.engine.recognition.SessionKeepVisibleStateStore
import com.nash.engine.recognition.VerificationPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Keep-visible policy bindings (split out of the former EngineImplModule —
 * review fix: focused, cohesive DI modules).
 *
 * The UI-facing controller and the pipeline-facing recognizer are separate
 * classes (strict follow-up fix 1): no object implements both interfaces.
 * They meet only through the shared singletons below — the command queue,
 * the verification state store, the trusted-person store, and the live-track
 * registry. Bind any of those twice and the UI would drive a different
 * gallery from the one the pipeline reads, and tapped faces would never
 * unblur.
 */
@Module
@InstallIn(SingletonComponent::class)
object KeepVisibleModule {

    /** UI → ml-thread mailbox; must be the same instance on both sides. */
    @Provides
    @Singleton
    fun provideKeepVisibleCommandQueue(): KeepVisibleCommandQueue = KeepVisibleCommandQueue()

    /** Per-track age/attempt bookkeeping shared by the gates and policies. */
    @Provides
    @Singleton
    fun provideLiveTrackRegistry(
        config: RecognitionConfig,
        time: TimeProvider,
    ): LiveTrackRegistry = LiveTrackRegistry(
        config = config,
        // Monotonic like the SystemClock.uptimeMillis it replaces: cadence
        // compares deltas only, and a wall clock could jump under NTP sync.
        nowMs = { time.nanoTime() / NANOS_PER_MILLI },
    )

    /** Cheap arithmetic gates guarding the expensive recognizer path. */
    @Provides
    @Singleton
    fun provideRecognitionGate(
        config: RecognitionConfig,
        liveTracks: LiveTrackRegistry,
    ): RecognitionGate = RecognitionGate(config, liveTracks)

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
        commands: KeepVisibleCommandQueue,
        state: KeepVisibleStateStore,
        logger: RecognitionLogger,
    ): KeepVisibleController = KeepVisibleControllerImpl(commands, state, logger)

    /**
     * Pipeline-facing half: consumed by KeepVisibleStage each detection
     * frame. The selector and the three policies are recognizer-internal —
     * nothing else may reach them, so they are built here rather than
     * published as bindings.
     */
    @Provides
    @Singleton
    fun provideKeepVisibleRecognizer(
        recognizer: @JvmSuppressWildcards FaceRecognizer<ImageProxy>,
        store: TrustedPersonStore,
        state: KeepVisibleStateStore,
        config: RecognitionConfig,
        commands: KeepVisibleCommandQueue,
        liveTracks: LiveTrackRegistry,
        gate: RecognitionGate,
        logger: RecognitionLogger,
    ): @JvmSuppressWildcards KeepVisibleRecognizer<ImageProxy> = KeepVisibleRecognizerImpl(
        commands = commands,
        liveTracks = liveTracks,
        selector = RecognitionCandidateSelector(
            commands, liveTracks, gate, state, store, config, logger
        ),
        enrollmentPolicy = EnrollmentPolicy(
            recognizer, store, state, commands, liveTracks, config, logger
        ),
        verificationPolicy = VerificationPolicy(
            recognizer, store, state, liveTracks, config, logger
        ),
        reVerificationPolicy = ReVerificationPolicy(
            recognizer, store, state, liveTracks, config, logger
        ),
        state = state,
        store = store,
        logger = logger,
    )

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

    private const val NANOS_PER_MILLI = 1_000_000L
}
