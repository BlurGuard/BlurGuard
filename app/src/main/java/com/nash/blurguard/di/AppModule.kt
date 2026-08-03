package com.nash.blurguard.di

import com.nash.core.common.DefaultDispatcherProvider
import com.nash.core.common.DispatcherProvider
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.impl.RealBlurGuardEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-wide Hilt module.
 *
 * This module binds the public engine API to its implementation.
 * The app module is the only module that knows about engine:impl.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(
        provider: DefaultDispatcherProvider
    ): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindBlurGuardEngine(
        engine: RealBlurGuardEngine
    ): BlurGuardEngine
}
