package com.nash.blurguard.di

import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageProxy
import com.nash.core.blurring.AnonymizationCameraEffect
import com.nash.core.blurring.AnonymizingSurfaceProcessor
import com.nash.core.camera.CameraXCameraController
import com.nash.core.common.DefaultDispatcherProvider
import com.nash.core.common.DispatcherProvider
import com.nash.core.common.di.FaceDetection
import com.nash.core.domain.CameraControllerAdapter
import com.nash.core.domain.CameraPreviewFactory
import com.nash.core.domain.CameraSession
import com.nash.core.domain.DefaultAnonymizationEngine
import com.nash.core.domain.DefaultAnonymizationPipeline
import com.nash.core.ml.MediaPipeFaceDetector
import com.nash.core.ml.YoloDetector
import com.nash.core.model.AnonymizationEngine
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.Detector
import com.nash.core.model.DetectorBackend
import com.nash.core.model.DetectorConfig
import com.nash.core.model.DetectorDelegate
import com.nash.core.model.FaceModelRange
import com.nash.core.model.FrameSource
import com.nash.core.model.OcSortConfig
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.Tracker
import com.nash.core.model.TrackerBackend
import com.nash.core.model.TrackerConfig
import com.nash.core.model.VideoRecorder
import com.nash.core.tracking.ByteTrackTracker
import com.nash.core.tracking.ocsort.OcSortTracker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

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

    @Binds
    abstract fun bindFrameSource(
        controller: CameraXCameraController
    ): FrameSource<ImageProxy>



    companion object {
        /**
         * Detection accelerator policy: CPU by default so the GPU stays
         * dedicated to the anonymization renderer (NFR-02). Revisit only
         * with benchmark evidence.
         */
        @Provides
        @Singleton
        fun provideDetectorConfig(): DetectorConfig = DetectorConfig(
            delegate = DetectorDelegate.NPU,
            minConfidence = 0.25f,
            faceModelRange = FaceModelRange.SHORT_RANGE
        )

        @Provides
        @Singleton
        fun provideTrackerConfig(): TrackerConfig = TrackerConfig()

        @Provides
        @Singleton
        fun provideAnonymizationEngine(
            frameSource: @JvmSuppressWildcards FrameSource<ImageProxy>,
            yoloDetector: YoloDetector,
            mediaPipeFaceDetector: MediaPipeFaceDetector,
            config: DetectorConfig,
            tracker: Tracker,
            renderBoxFeed: RenderBoxFeed,
        ): AnonymizationEngine {
            val detectors: List<Detector<ImageProxy>> = when (config.backend) {
                DetectorBackend.YOLO -> listOf(yoloDetector)
                DetectorBackend.MEDIAPIPE -> listOf(mediaPipeFaceDetector)
            }
            return DefaultAnonymizationEngine(
                frameSource = frameSource,
                pipeline = DefaultAnonymizationPipeline(detectors, tracker, renderBoxFeed = renderBoxFeed)
            )
        }

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


        /** Flip to BYTE_TRACK to A/B both trackers on the same footage. */
        private val TRACKER_BACKEND = TrackerBackend.BYTE_TRACK

        @Provides
        @Singleton
        fun provideOcSortConfig(): OcSortConfig = OcSortConfig()

        @Provides
        @Singleton
        fun provideTracker(
            byteTrack: Provider<ByteTrackTracker>,   // javax.inject.Provider — only the
            ocSort: Provider<OcSortTracker>          // selected impl is instantiated
        ): Tracker = when (TRACKER_BACKEND) {
            TrackerBackend.BYTE_TRACK -> byteTrack.get()
            TrackerBackend.OC_SORT -> ocSort.get()
        }
    }

//    @Binds
//    abstract fun bindTracker(
//        tracker: ByteTrackTracker
//    ): Tracker


}
