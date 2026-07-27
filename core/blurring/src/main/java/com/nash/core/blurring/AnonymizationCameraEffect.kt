package com.nash.core.blurring

import android.util.Log
import androidx.camera.core.CameraEffect

/**
 * CameraX effect applying [AnonymizingSurfaceProcessor] to BOTH the preview
 * and the recorded video, so the raw stream is unreachable downstream
 * (FR-04, FR-05, invariant #1).
 */
class AnonymizationCameraEffect(
    processor: AnonymizingSurfaceProcessor,
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    processor.glExecutor,
    processor,
    { t -> Log.e("AnonymizationEffect", "Renderer error", t) },
)