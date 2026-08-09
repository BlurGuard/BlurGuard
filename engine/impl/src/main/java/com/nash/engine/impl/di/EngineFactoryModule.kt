package com.nash.engine.impl.di

import androidx.camera.core.ImageProxy
import com.nash.engine.impl.factory.DefaultTrackerFactory
import com.nash.engine.impl.factory.DetectorFactory
import com.nash.engine.impl.factory.ImageProxyDetectorFactory
import com.nash.engine.impl.factory.TrackerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bindings for the engine factories.
 *
 * Only the generic seams that are injected AS interfaces are bound here:
 * [DetectorFactory]<ImageProxy> is consumed through the interface by the
 * pipeline factory, and [TrackerFactory] likewise. The pipeline factory is
 * injected by its concrete class (ImageProxyAnonymizationPipelineFactory)
 * from PipelineModule, so its interface stays Hilt-free — the
 * [com.nash.engine.impl.factory.AnonymizationPipelineFactory] interface
 * exists for tests only.
 *
 * [JvmSuppressWildcards] mirrors the FrameSource<ImageProxy> pattern in
 * CameraBindingsModule: without it Dagger sees the Kotlin wildcard
 * `DetectorFactory<? extends ImageProxy>` and fails to match injection sites.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class EngineFactoryModule {

    @Binds
    abstract fun bindImageProxyDetectorFactory(
        factory: ImageProxyDetectorFactory,
    ): @JvmSuppressWildcards DetectorFactory<ImageProxy>

    @Binds
    abstract fun bindTrackerFactory(
        factory: DefaultTrackerFactory,
    ): TrackerFactory
}
