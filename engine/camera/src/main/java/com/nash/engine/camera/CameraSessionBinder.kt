package com.nash.engine.camera

import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds one camera session and binds it to a lifecycle.
 *
 * Extracted from [CameraXSessionFacade]: owns use-case construction
 * sequencing, recorder attachment, frame-source attachment, preview
 * attachment, and the lifecycle bind itself. Must be called on the main
 * thread with an already-released previous session.
 *
 * Privacy invariant: every use case is bound inside a UseCaseGroup carrying
 * the anonymization [CameraEffect], so preview AND recording consume
 * processed output only.
 */
@Singleton
class CameraSessionBinder @Inject constructor(
    private val anonymizationEffect: CameraEffect,
    private val useCaseFactory: CameraUseCaseFactory,
    private val frameSource: AnalysisFrameSource,
    private val videoRecorder: CameraVideoRecorder,
    private val surfaceAttacher: PreviewSurfaceAttacher,
    private val executorProvider: CameraExecutorProvider,
) {

    /**
     * Creates the use cases, attaches recorder and frame source, and binds
     * everything to [lifecycleOwner] behind the anonymization effect.
     *
     * The construction sequence mirrors the legacy facade exactly:
     * preview -> recorder/videoCapture -> imageAnalysis -> group -> bind.
     */
    fun bind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
    ): BoundCameraSession {
        val preview = useCaseFactory.createPreview()
        surfaceAttacher.setPreview(preview)

        val cameraExecutor = executorProvider.executor
        val recorder = useCaseFactory.createRecorder(cameraExecutor)
        videoRecorder.attach(recorder, cameraExecutor)
        val videoCapture = useCaseFactory.createVideoCapture(recorder)

        val imageAnalysis = useCaseFactory.createImageAnalysis()
        frameSource.attachTo(imageAnalysis)

        val useCaseGroup = useCaseFactory.createUseCaseGroup(
            preview = preview,
            videoCapture = videoCapture,
            imageAnalysis = imageAnalysis,
            anonymizationEffect = anonymizationEffect,
        )

        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            useCaseGroup,
        )

        return BoundCameraSession(
            provider = provider,
            preview = preview,
            videoCapture = videoCapture,
            imageAnalysis = imageAnalysis,
        )
    }
}