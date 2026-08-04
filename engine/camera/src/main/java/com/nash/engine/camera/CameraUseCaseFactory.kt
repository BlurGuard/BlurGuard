package com.nash.engine.camera

import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds CameraX use cases for the engine camera session.
 *
 * Owns resolution selectors and ViewPort configuration only. This factory
 * never binds lifecycles, never owns surfaces, and never writes files.
 */
@Singleton
class CameraUseCaseFactory @Inject constructor() {

    fun createPreview(): Preview =
        Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    )
                    .build()
            )
            .build()

    fun createRecorder(executor: Executor): Recorder =
        Recorder.Builder()
            .setExecutor(executor)
            .build()

    fun createVideoCapture(recorder: Recorder): VideoCapture<Recorder> =
        VideoCapture.withOutput(recorder)

    fun createImageAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

    /**
     * Groups all use cases behind a shared ViewPort and attaches the
     * anonymization effect, so preview AND recording consume processed
     * output only (FR-04, FR-05).
     */
    fun createUseCaseGroup(
        preview: Preview,
        videoCapture: VideoCapture<Recorder>,
        imageAnalysis: ImageAnalysis,
        anonymizationEffect: CameraEffect,
    ): UseCaseGroup {
        val viewPort = ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0).build()
        return UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .addUseCase(imageAnalysis)
            .addEffect(anonymizationEffect)// Privacy invariant: the same anonymization effect is applied to preview and video capture.
            .build()
    }

    private companion object {
        /** Target analysis resolution — detection models downscale further anyway. */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480
    }
}