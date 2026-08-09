package com.nash.engine.impl.di

import com.nash.core.model.TimeProvider
import com.nash.engine.impl.time.SystemTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single source of time for engine orchestration and recording mapping
 * (review fix: no direct System.currentTimeMillis/nanoTime in those paths).
 * Tests never see this binding; they inject fakes directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()
}
