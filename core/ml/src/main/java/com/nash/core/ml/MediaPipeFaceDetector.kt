package com.nash.core.ml

import android.content.Context
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.Detector
import com.nash.core.model.DetectorConfig
import com.nash.core.model.DetectorDelegate
import com.nash.core.model.FaceModelRange
import com.nash.core.model.FrameMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * BlazeFace (short-range) face detector backed by MediaPipe Tasks / TFLite.
 *
 * Implements the core/model [Detector] contract for CameraX frames.
 *
 * - All inference is on-device; the frame is only read, never retained (NFR-01).
 * - Coordinates are returned NORMALIZED to the upright (rotation-applied) frame,
 *   so downstream consumers (Tracker, renderer) never deal with pixels/rotation.
 * - Accelerator preference comes from [DetectorConfig]; default CPU (XNNPACK)
 *   keeps the GPU fully dedicated to the core/blurring render path.
 *
 * Known MVP limitation: the short-range model is tuned for faces within ~2 m.
 * Swap the asset for a full-range/YOLO-face model behind this same interface
 * if far-away faces need coverage.
 */
class MediaPipeFaceDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val config: DetectorConfig
) : Detector<ImageProxy> {

    /** Lazy so the model is loaded on first detect() (on the ml dispatcher), not at DI time. */
    private val faceDetector: FaceDetector by lazy { createDetector() }
    private val modelAsset: String
        get() = when (config.faceModelRange) {
            FaceModelRange.SHORT_RANGE -> "blaze_face_short_range.tflite"
            FaceModelRange.FULL_RANGE -> "blaze_face_full_range.tflite"
        }
    private fun createDetector(): FaceDetector {
        fun options(delegate: Delegate): FaceDetector.FaceDetectorOptions =
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelAsset)
                        .setDelegate(delegate)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(config.minConfidence)
                .build()

        val preferred = when (config.delegate) {
            DetectorDelegate.CPU -> Delegate.CPU
            DetectorDelegate.GPU -> Delegate.GPU
        }

        return try {
            FaceDetector.createFromOptions(context, options(preferred))
        } catch (e: Exception) {
            if (preferred == Delegate.CPU) throw e
            // Preferred accelerator unavailable on this device — fall back to CPU.
            FaceDetector.createFromOptions(context, options(Delegate.CPU))
        }
    }

    override suspend fun detect(
        frame: ImageProxy,
        metadata: FrameMetadata
    ): List<DetectionBox> = withContext(dispatcherProvider.ml) {
        // RGBA_8888 analysis output makes this a cheap copy — no YUV conversion.
        val bitmap = frame.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()

        val processingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(metadata.rotationDegrees)
            .build()

        val result = faceDetector.detect(mpImage, processingOptions)

        // MediaPipe rotates internally for inference, but returns pixel coords
        // in the UNROTATED input bitmap's space. Normalize against the bitmap,
        // then rotate the normalized box into upright (display) space.
        val bufferWidth = bitmap.width.toFloat()
        val bufferHeight = bitmap.height.toFloat()

        result.detections().map { detection ->
            val rect = detection.boundingBox()
            val bufferBox = BoundingBox(
                left = (rect.left / bufferWidth).coerceIn(0f, 1f),
                top = (rect.top / bufferHeight).coerceIn(0f, 1f),
                right = (rect.right / bufferWidth).coerceIn(0f, 1f),
                bottom = (rect.bottom / bufferHeight).coerceIn(0f, 1f)
            )
            DetectionBox(
                box = bufferBox.rotatedToUpright(metadata.rotationDegrees),
                clazz = DetectionClass.FACE,
                confidence = detection.categories().firstOrNull()?.score() ?: 0f
            )
        }
    }

    /**
     * Rotates a normalized box from unrotated-buffer space into upright space.
     * [rotationDegrees] is the clockwise rotation that makes the buffer upright
     * (CameraX convention); always a multiple of 90.
     */
    private fun BoundingBox.rotatedToUpright(rotationDegrees: Int): BoundingBox =
        when ((rotationDegrees % 360 + 360) % 360) {
            90 -> BoundingBox(
                left = 1f - bottom,
                top = left,
                right = 1f - top,
                bottom = right
            )
            180 -> BoundingBox(
                left = 1f - right,
                top = 1f - bottom,
                right = 1f - left,
                bottom = 1f - top
            )
            270 -> BoundingBox(
                left = top,
                top = 1f - right,
                right = bottom,
                bottom = 1f - left
            )
            else -> this
        }
    override fun close() {
        faceDetector.close()
    }


}