package com.nash.engine.camera

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Releases a bound camera session. Must be called on the main thread.
 *
 * The release order mirrors the legacy facade exactly:
 * 1. stop any active recording quietly,
 * 2. detach the recorder,
 * 3. detach the analyzer from the session's ImageAnalysis,
 * 4. unbind all use cases from the provider,
 * 5. clear the per-session Preview (the last PreviewView is intentionally
 *    kept by [PreviewSurfaceAttacher] for rebinds without a new target).
 *
 * Recorder cleanup (1-2) runs even when [release] is called with `null`
 * (nothing bound yet), matching the legacy null-safe release path.
 */
@Singleton
class CameraSessionReleaser @Inject constructor(
    private val frameSource: AnalysisFrameSource,
    private val videoRecorder: CameraVideoRecorder,
    private val surfaceAttacher: PreviewSurfaceAttacher,
) {

    suspend fun release(session: BoundCameraSession?) {
        videoRecorder.cancelActiveRecordingQuietly()
        videoRecorder.detach()
        session?.let { frameSource.detachFrom(it.imageAnalysis) }
        session?.provider?.unbindAll()
        surfaceAttacher.setPreview(null)
    }
}