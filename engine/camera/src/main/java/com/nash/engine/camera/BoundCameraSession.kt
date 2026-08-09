package com.nash.engine.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

/**
 * Immutable holder for one bound camera session: the provider it was bound
 * with and the use cases created for it.
 *
 * Created by [CameraSessionBinder] and consumed by [CameraSessionReleaser];
 * never exposed outside engine/camera, so CameraX types and raw surfaces
 * stay inside this module.
 */
class BoundCameraSession(
    val provider: ProcessCameraProvider,
    val preview: Preview,
    val videoCapture: VideoCapture<Recorder>,
    val imageAnalysis: ImageAnalysis,
)
