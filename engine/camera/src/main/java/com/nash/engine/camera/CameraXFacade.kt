package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.FrameSource
import com.nash.core.model.VideoRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Facade over the engine camera session (formerly CameraXCameraController).
 *
 * Coordinates focused collaborators instead of doing everything itself:
 * - [CameraUseCaseFactory]  — builds Preview / VideoCapture / ImageAnalysis + ViewPort
 * - [AnalysisFrameSource]   — analyzer, FrameMetadata, frame IDs, frame closing
 * - [CameraVideoRecorder]   — Recorder/Recording lifecycle + RecordingState
 * - [MediaStoreOutputFactory] — output options + safe filenames (via the recorder)
 * - [CameraPreviewViewFactory] — PreviewView creation/validation
 *
 * The facade's own responsibilities are reduced to: owning the single CameraX
 * session (provider + lifecycle binding), the camera executor, and wiring the
 * processed UseCaseGroup together. engine/camera remains the ONLY module that
 * writes video (architecture invariant #2), and it binds only the use-case
 * group carrying the anonymization effect.
 *
 * Public contracts are preserved: [VideoRecorder] and [FrameSource] are
 * implemented by delegation, so consumers in engine/impl are unaffected.
 */
@Singleton
class CameraXFacade @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val anonymizationEffect: CameraEffect,
    private val useCaseFactory: CameraUseCaseFactory,
    private val frameSource: AnalysisFrameSource,
    private val videoRecorder: CameraVideoRecorder,
    private val previewViewFactory: CameraPreviewViewFactory,
) : VideoRecorder by videoRecorder, FrameSource<ImageProxy> by frameSource {

    private val facadeScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io
    )

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(context)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null

    @Volatile
    private var previewView: PreviewView? = null

    /**
     * Creates a CameraX PreviewView that the feature layer can display inside
     * an [androidx.compose.ui.viewinterop.AndroidView]. The raw camera Surface
     * never leaves this module. Returned as [View] so callers outside this
     * module need no CameraX dependency.
     */
    fun createPreviewView(context: Context): View {
        return previewViewFactory.create(context).also { view ->
            previewView = view
            attachSurfaceProviderIfReady()
        }
    }

    /**
     * Attaches an externally created [PreviewView] as the preview output.
     * Safe to call before or after [bind].
     */
    fun attachPreviewView(view: View) {
        previewView = previewViewFactory.requirePreviewView(view)
        attachSurfaceProviderIfReady()
    }

    /**
     * Binds the camera pipeline to the supplied [lifecycleOwner].
     * Must be called after [createPreviewView]/[attachPreviewView] when the
     * screen enters composition.
     */
    fun bind(lifecycleOwner: LifecycleOwner) {
        // CameraX lifecycle binding and surface-provider attachment must run on
        // the main thread; only the provider future resolution happens on IO.
        facadeScope.launch(dispatcherProvider.main) {
            try {
                val provider = withContext(dispatcherProvider.io) {
                    cameraProviderFuture.await()
                }
                cameraProvider = provider

                val preview = useCaseFactory.createPreview()
                    .also { previewUseCase = it }
                attachSurfaceProviderIfReady()

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
    fun unbind() {
        facadeScope.launch(dispatcherProvider.main) {
            videoRecorder.cancelActiveRecordingQuietly()
            imageAnalysisUseCase?.let { frameSource.detachFrom(it) }
            cameraProvider?.unbindAll()
        }
    }

    private fun attachSurfaceProviderIfReady() {
        val view = previewView ?: return
        val preview = previewUseCase ?: return
        preview.surfaceProvider = view.surfaceProvider
    }

    fun shutdown() {
        facadeScope.cancel()
        frameSource.shutdown()
        cameraExecutor.shutdown()
    }
}