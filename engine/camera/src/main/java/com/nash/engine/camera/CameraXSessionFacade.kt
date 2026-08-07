package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.nash.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Facade over the CameraX **session**: provider resolution, lifecycle binding,
 * processed-UseCaseGroup wiring, preview surface attachment, and
 * unbind/shutdown coordination. Coordinator ONLY — all real work lives in
 * focused collaborators:
 * - [CameraUseCaseFactory]    — Preview / VideoCapture / ImageAnalysis + ViewPort
 * - [AnalysisFrameSource]     — analyzer, FrameMetadata, frame IDs, frame closing
 * - [CameraVideoRecorder]     — Recorder/Recording lifecycle + RecordingState
 * - [MediaStoreOutputFactory] — output options + safe filenames (via the recorder)
 * - [CameraPreviewViewFactory] — PreviewView creation/validation
 * - [CameraExecutorProvider]  — camera/recorder callback executor lifecycle
 *
 * VideoCapture is bound inside a UseCaseGroup carrying the anonymization
 * CameraEffect, so preview AND recording consume processed output only.
 * engine/camera remains the only video-writing module, and raw frames or
 * surfaces never leave it.
 *
 * The facade exposes only the [CameraSessionController] session surface.
 * The public `VideoRecorder` and `FrameSource` contracts are bound directly
 * to [CameraVideoRecorder] and [AnalysisFrameSource] in DI, so no consumer
 * can reach recording or frame APIs through the session controller.
 */
@Singleton
class CameraXSessionFacade @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val anonymizationEffect: CameraEffect,
    private val useCaseFactory: CameraUseCaseFactory,
    private val frameSource: AnalysisFrameSource,
    private val videoRecorder: CameraVideoRecorder,
    private val previewViewFactory: CameraPreviewViewFactory,
    private val executorProvider: CameraExecutorProvider,
) : CameraSessionController {

    private val facadeScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io
    )

    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(context)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null

    @Volatile
    private var previewView: PreviewView? = null

    override fun createPreviewView(context: Context): View {
        return previewViewFactory.create(context).also { view ->
            previewView = view
            attachSurfaceProviderIfReady()
        }
    }

    override fun attachPreviewView(view: View) {
        previewView = previewViewFactory.requirePreviewView(view)
        attachSurfaceProviderIfReady()
    }

    /**
     * Binds the camera pipeline to the supplied [lifecycleOwner].
     *
     * Safe for rebind: permission/lifecycle changes may call this again, so
     * any previous session (use cases, analyzer, recorder attachment) is
     * released before new use cases are created.
     */
    override fun bind(lifecycleOwner: LifecycleOwner) {
        // CameraX lifecycle binding and surface-provider attachment must run on
        // the main thread; only the provider future resolution happens on IO.
        facadeScope.launch(dispatcherProvider.main) {
            try {
                val provider = withContext(dispatcherProvider.io) {
                    cameraProviderFuture.await()
                }
                cameraProvider = provider

                // Rebind safety: never leave stale use cases or analyzers around.
                releaseSession()

                val preview = useCaseFactory.createPreview()
                    .also { previewUseCase = it }
                attachSurfaceProviderIfReady()

                val cameraExecutor = executorProvider.executor
                val recorder = useCaseFactory.createRecorder(cameraExecutor)
                videoRecorder.attach(recorder, cameraExecutor)
                val videoCapture = useCaseFactory.createVideoCapture(recorder)

                val imageAnalysis = useCaseFactory.createImageAnalysis()
                    .also { imageAnalysisUseCase = it }
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
            } catch (e: Exception) {
                videoRecorder.onCameraBindError(e)
            }
        }
    }

    /** Unbinds the camera pipeline from the current lifecycle owner. */
    override fun unbind() {
        facadeScope.launch(dispatcherProvider.main) {
            releaseSession()
        }
    }

    /**
     * Permanently releases the session.
     *
     * Cleanup is scheduled BEFORE the scope is cancelled: CameraX unbinding
     * runs first on the main thread, then the analyzer scope and camera
     * executor are shut down, and the facade scope is cancelled last (as the
     * final statement of its own last coroutine, which is safe).
     */
    override fun shutdown() {
        facadeScope.launch(dispatcherProvider.main) {
            releaseSession()
            frameSource.shutdown()
            executorProvider.shutdown()
            facadeScope.cancel()
        }
    }

    /**
     * Stops any active recording, detaches the analyzer and recorder, and
     * unbinds all use cases. Must run on the main thread.
     *
     * [cameraProvider] is intentionally kept: ProcessCameraProvider is a
     * process-wide singleton that stays valid across rebinds.
     * [previewView] is intentionally kept: the engine re-attaches the
     * feature's PreviewTarget on every bind, and keeping the last view lets
     * an unbind/rebind cycle without a new target still show a preview.
     */
    private fun releaseSession() {
        videoRecorder.cancelActiveRecordingQuietly()
        videoRecorder.detach()
        imageAnalysisUseCase?.let { frameSource.detachFrom(it) }
        cameraProvider?.unbindAll()
        imageAnalysisUseCase = null
        previewUseCase = null
    }

    private fun attachSurfaceProviderIfReady() {
        val view = previewView ?: return
        val preview = previewUseCase ?: return
        preview.surfaceProvider = view.surfaceProvider
    }
}