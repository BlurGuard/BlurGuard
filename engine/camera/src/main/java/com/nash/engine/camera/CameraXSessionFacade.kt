package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.nash.core.common.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Facade over the CameraX **session**: coordinates bind/unbind/shutdown
 * sequencing and holds the current [BoundCameraSession]. Coordinator ONLY —
 * all real work lives in focused collaborators:
 * - [CameraProviderResolver]  — ProcessCameraProvider resolution (off main)
 * - [CameraSessionBinder]     — use-case construction + lifecycle binding
 *                               (via [CameraUseCaseFactory])
 * - [CameraSessionReleaser]   — ordered session teardown
 * - [PreviewSurfaceAttacher]  — PreviewView/Preview state + surface attachment
 *                               (creates/validates views via [CameraPreviewViewFactory])
 * - [AnalysisFrameSource]     — analyzer, FrameMetadata, frame IDs, frame closing
 * - [CameraVideoRecorder]     — Recorder/Recording lifecycle + RecordingState
 * - [MediaStoreOutputFactory] — output options + safe filenames (via the recorder)
 * - [CameraExecutorProvider]  — camera/recorder callback executor lifecycle
 *
 * VideoCapture is bound inside a UseCaseGroup carrying the anonymization
 * CameraEffect (see [CameraSessionBinder]), so preview AND recording consume
 * processed output only. engine/camera remains the only video-writing
 * module, and raw frames or surfaces never leave it.
 *
 * The facade exposes only the [CameraSessionController] session surface.
 * The public `VideoRecorder` and `FrameSource` contracts are bound directly
 * to [CameraVideoRecorder] and [AnalysisFrameSource] in DI, so no consumer
 * can reach recording or frame APIs through the session controller.
 */
@Singleton
class CameraXSessionFacade @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val frameSource: AnalysisFrameSource,
    private val videoRecorder: CameraVideoRecorder,
    private val surfaceAttacher: PreviewSurfaceAttacher,
    private val executorProvider: CameraExecutorProvider,
    private val providerResolver: CameraProviderResolver,
    private val sessionBinder: CameraSessionBinder,
    private val sessionReleaser: CameraSessionReleaser,
) : CameraSessionController {

    private val facadeScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io
    )

    private var boundSession: BoundCameraSession? = null

    override fun createPreviewView(context: Context): View {
        return surfaceAttacher.createPreviewView(context)
    }

    override fun attachPreviewView(view: View) {
        surfaceAttacher.attachPreviewView(view)
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
        // the main thread; the resolver hops to IO internally for resolution.
        facadeScope.launch(dispatcherProvider.main) {
            try {
                val provider = providerResolver.resolve()

                // Rebind safety: never leave stale use cases or analyzers around.
                releaseSession()

                boundSession = sessionBinder.bind(provider, lifecycleOwner)
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
     * Releases the current session (see [CameraSessionReleaser] for the
     * exact teardown order) and clears the stored [BoundCameraSession].
     * Must run on the main thread.
     */
    private fun releaseSession() {
        sessionReleaser.release(boundSession)
        boundSession = null
    }
}