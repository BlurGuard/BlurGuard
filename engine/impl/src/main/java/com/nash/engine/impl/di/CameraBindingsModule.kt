package com.nash.engine.impl.di

import androidx.camera.core.ImageProxy
import com.nash.core.model.FrameSource
import com.nash.core.model.VideoRecorder
import com.nash.engine.camera.AnalysisFrameSource
import com.nash.engine.camera.CameraExecutorProvider
import com.nash.engine.camera.CameraSessionController
import com.nash.engine.camera.CameraVideoRecorder
import com.nash.engine.camera.CameraXSessionFacade
import com.nash.engine.camera.SingleThreadCameraExecutorProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the engine camera contracts to their single implementations.
 *
 * VideoRecorder and FrameSource are bound to the leaf classes that actually
 * own the behavior, and the session surface is bound to the facade, which
 * implements only CameraSessionController. Exactly one binding exists for
 * each contract, so no consumer can reach recording or frame APIs through
 * the session controller.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraBindingsModule {

    @Binds
    @Singleton
    abstract fun bindCameraSessionController(
        facade: CameraXSessionFacade
    ): CameraSessionController

    @Binds
    @Singleton
    abstract fun bindVideoRecorder(
        recorder: CameraVideoRecorder
    ): VideoRecorder

    @Binds
    @Singleton
    abstract fun bindFrameSource(
        source: AnalysisFrameSource
    ): @JvmSuppressWildcards FrameSource<ImageProxy>

    @Binds
    @Singleton
    abstract fun bindCameraExecutorProvider(
        provider: SingleThreadCameraExecutorProvider
    ): CameraExecutorProvider
}