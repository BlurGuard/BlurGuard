package com.nash.engine.impl.factory

import androidx.camera.core.ImageProxy
import com.nash.core.model.Detector
import com.nash.core.model.DetectorBackend
import com.nash.core.model.DetectorConfig
import com.nash.engine.ml.MediaPipeFaceDetector
import com.nash.engine.ml.YoloDetector
import javax.inject.Inject

/**
 * Selects the live detector backend(s) for CameraX frames from
 * [DetectorConfig.backend]. Previously a `when` inside EngineImplModule.
 *
 * All backends are constructed by Hilt (singletons, lazy model load), so
 * injecting both costs nothing until a model is actually opened on first
 * detect().
 */
internal class ImageProxyDetectorFactory @Inject constructor(
    private val yoloDetector: YoloDetector,
    private val mediaPipeFaceDetector: MediaPipeFaceDetector,
) : DetectorFactory<ImageProxy> {

    override fun create(config: DetectorConfig): List<Detector<ImageProxy>> =
        select(config, yoloDetector, mediaPipeFaceDetector)

    companion object {
        /**
         * Pure backend selection. Generic over the frame type so it is
         * JVM-unit-testable with fake detectors (the concrete detectors are
         * Android-bound finals and cannot be faked in plain JUnit).
         */
        fun <F> select(
            config: DetectorConfig,
            yolo: Detector<F>,
            mediaPipe: Detector<F>,
        ): List<Detector<F>> = when (config.backend) {
            DetectorBackend.YOLO -> listOf(yolo)
            DetectorBackend.MEDIAPIPE -> listOf(mediaPipe)
        }
    }
}