package com.nash.engine.impl.di

import com.nash.core.model.DetectorConfig
import com.nash.core.model.OcSortConfig
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackerConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine tuning configs, each provided at its defaults exactly once (split
 * out of the former EngineImplModule — review fix: focused, cohesive DI
 * modules). Override here, not at usage sites, to retune the engine.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineConfigModule {

    @Provides
    @Singleton
    fun provideOcSortConfig(): OcSortConfig = OcSortConfig()

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