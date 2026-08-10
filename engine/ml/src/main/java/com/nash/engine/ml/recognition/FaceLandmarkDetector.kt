package com.nash.engine.ml.recognition

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

internal interface FaceLandmarkDetector : AutoCloseable {
    fun detect(bitmap: Bitmap): List<Detection>

    override fun close() = Unit
}

internal class MediaPipeFaceLandmarkDetector(
    private val context: Context,
) : FaceLandmarkDetector {
    private val detector: FaceDetector by lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinDetectionConfidence(MIN_LANDMARK_DETECTION_CONFIDENCE)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        FaceDetector.createFromOptions(context, options)
    }

    override fun detect(bitmap: Bitmap): List<Detection> =
        detector.detect(BitmapImageBuilder(bitmap).build()).detections()

    override fun close() {
        detector.close()
    }

    private companion object {
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val MIN_LANDMARK_DETECTION_CONFIDENCE = 0.5f
    }
}
