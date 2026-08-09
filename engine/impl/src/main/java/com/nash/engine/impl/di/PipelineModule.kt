package com.nash.engine.impl.di

import androidx.camera.core.ImageProxy
import com.nash.engine.impl.AnonymizationPipeline
import com.nash.engine.impl.factory.ImageProxyAnonymizationPipelineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the anonymization pipeline behind its abstraction (split out of
 * the former EngineImplModule — review fix: focused, cohesive DI modules).
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    /**
     * Construction and backend selection live in the factory (review fixes
     * 12/13) — this provider only delegates. No `when` branching here.
     */
    @Provides
    @Singleton
    internal fun provideAnonymizationPipeline(
        factory: ImageProxyAnonymizationPipelineFactory,
    ): AnonymizationPipeline<ImageProxy> = factory.create()
}
