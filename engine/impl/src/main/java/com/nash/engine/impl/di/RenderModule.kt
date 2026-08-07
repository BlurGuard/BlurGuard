package com.nash.engine.impl.di

import androidx.camera.core.CameraEffect
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.RenderBoxFeed
import com.nash.engine.render.AnonymizationCameraEffect
import com.nash.engine.render.AnonymizingSurfaceProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Render-path bindings: the box feed the pipeline publishes into, the
 * anonymization mode holder, and the CameraX effect that draws over every
 * preview/recording frame (split out of the former EngineImplModule —
 * review fix: focused, cohesive DI modules).
 */
@Module
@InstallIn(SingletonComponent::class)
object RenderModule {

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
}