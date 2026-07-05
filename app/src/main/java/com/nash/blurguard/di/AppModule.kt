package com.nash.blurguard.di

import com.nash.core.camera.CameraXCameraController
import com.nash.core.common.DefaultDispatcherProvider
import com.nash.core.common.DispatcherProvider
import com.nash.core.domain.CameraControllerAdapter
import com.nash.core.domain.CameraPreviewFactory
import com.nash.core.domain.CameraSession
import com.nash.core.model.VideoRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Application-wide Hilt module.
 *
 * All interface-to-implementation bindings live here so the Hilt graph remains
 * centralized in the [app] module as required by BlurGuard architecture.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindDispatcherProvider(
        provider: DefaultDispatcherProvider
    ): DispatcherProvider

    @Binds
    abstract fun bindVideoRecorder(
        controller: CameraXCameraController
    ): VideoRecorder

    @Binds
    abstract fun bindCameraSession(
        adapter: CameraControllerAdapter
    ): CameraSession

    @Binds
    abstract fun bindCameraPreviewFactory(
        adapter: CameraControllerAdapter
    ): CameraPreviewFactory
}
