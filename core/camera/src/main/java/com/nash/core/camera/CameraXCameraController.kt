package com.nash.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.View
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.FrameSource
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import com.nash.core.model.VideoRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraX-backed video recorder and analysis-frame source.
 *
 * This is the **only** class in the project that writes video. It currently records the
 * direct CameraX [VideoCapture] output; the future anonymized SurfaceProcessor pipeline
 * will be spliced between the camera and the encoder while keeping this class as the
 * single video writer.
 *
 * It also owns the parallel analysis branch ([ImageAnalysis]): downsampled frames are
 * delivered to a single [FrameConsumer] (the frame-pipeline orchestrator in core/domain)
 * on the serialized ml dispatcher. Frame ownership stays in this class — the [ImageProxy]
 * is closed here after the consumer returns, which is also what drives CameraX's
 * latest-wins backpressure (STRATEGY_KEEP_ONLY_LATEST).
 *
 * Lifecycle binding and preview creation are exposed as plain methods so a domain-layer
 * adapter can implement the cross-module [CameraSession] / [CameraPreviewFactory] contracts
 * without forcing [core.camera] to depend on [core.domain].
 *
 * TODO: Replace the direct [VideoCapture] recording path with a processed SurfaceProcessor
 *       output when the anonymization renderer is connected. Until then, keep all recording
 *       output flowing through this class and never persist raw frames elsewhere.
 */
@Singleton
class CameraXCameraController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : VideoRecorder, FrameSource<ImageProxy> {

    private val controllerScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io
    )

    /**
     * Scope for per-frame analysis work. The ml dispatcher has parallelism 1, so
     * frames are processed strictly one at a time, in order, without blocking a thread.
     */
    private val analysisScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.ml
    )

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(context)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private val frameIdGenerator = AtomicLong(0L)

    @Volatile
    private var frameConsumer: FrameConsumer<ImageProxy>? = null

    @Volatile
    private var previewView: PreviewView? = null

    @Volatile
    private var activeRecording: Recording? = null

    private var finalizeResult: CompletableDeferred<RecordingStopResult>? = null

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: Flow<RecordingState> = _recordingState.asStateFlow()

    override fun setFrameConsumer(consumer: FrameConsumer<ImageProxy>?) {
        frameConsumer = consumer
    }

    /**
     * Creates a CameraX [PreviewView] that the feature layer can display inside an
     * [androidx.compose.ui.viewinterop.AndroidView]. The raw camera Surface never leaves
     * this module.
     *
     * The return type is [View] so callers outside this module do not need a CameraX
     * dependency to consume the preview.
     */
    fun createPreviewView(context: Context): View {
        return PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }.also { view ->
            previewView = view
            attachSurfaceProviderIfReady()
        }
    }

    /**
     * Binds the camera pipeline to the supplied [lifecycleOwner].
     *
     * Must be called after [createPreviewView] when the screen enters composition.
     */
    fun bind(lifecycleOwner: LifecycleOwner) {
        // CameraX lifecycle binding and surface-provider attachment must run on the
        // main thread; only the provider future resolution can safely happen on IO.
        controllerScope.launch(dispatcherProvider.main) {
            try {
                val provider = withContext(dispatcherProvider.io) {
                    cameraProviderFuture.await()
                }
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    previewUseCase = it
                }
                attachSurfaceProviderIfReady()

                val recorderInstance = Recorder.Builder()
                    .setExecutor(cameraExecutor)
                    .build()
                    .also { recorder = it }

                val videoCaptureInstance = VideoCapture.withOutput(recorderInstance)
                    .also { videoCapture = it }

                val imageAnalysisInstance = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { imageAnalysisUseCase = it }

                // Runnable::run is a direct executor: the callback only builds metadata
                // and hands the frame to the analysis coroutine, so it is cheap enough
                // to run on CameraX's own thread.
                imageAnalysisInstance.setAnalyzer(Runnable::run) { imageProxy ->
                    val consumer = frameConsumer
                    if (consumer == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val metadata = FrameMetadata(
                        frameId = frameIdGenerator.incrementAndGet(),
                        timestampNanos = imageProxy.imageInfo.timestamp,
                        width = imageProxy.width,
                        height = imageProxy.height,
                        rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    )
                    analysisScope.launch {
                        try {
                            consumer.onFrame(imageProxy, metadata)
                        } finally {
                            // Closing the frame is what lets CameraX deliver the next
                            // (latest) one — this IS the backpressure/subsampling signal.
                            imageProxy.close()
                        }
                    }
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCaptureInstance,
                    imageAnalysisInstance
                )
            } catch (e: Exception) {
                _recordingState.value = RecordingState.Error(
                    message = e.message ?: "Failed to bind camera",
                    cause = e
                )
            }
        }
    }

    /**
     * Unbinds the camera pipeline from the current lifecycle owner.
     */
    fun unbind() {
        controllerScope.launch(dispatcherProvider.main) {
            try {
                activeRecording?.stop()
                activeRecording?.close()
            } catch (_: Exception) {
                // Best-effort cleanup; the finalize event will report any real error.
            }
            imageAnalysisUseCase?.clearAnalyzer()
            cameraProvider?.unbindAll()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecording(config: RecordingConfig): RecordingStartResult {
        return withContext(dispatcherProvider.io) {
            val currentRecorder = recorder
                ?: return@withContext RecordingStartResult.Failure(
                    "Camera not initialized. Bind the camera before recording."
                )

            if (activeRecording != null) {
                return@withContext RecordingStartResult.Failure("Recording already in progress")
            }

            _recordingState.value = RecordingState.Starting

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, generateFilename(config.fileNamePrefix))
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIRECTORY"
                        )
                    }
                }

                val outputOptions = MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                var pendingRecording = currentRecorder.prepareRecording(context, outputOptions)

                if (config.includeAudio &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRecording = try {
                        pendingRecording.withAudioEnabled()
                    } catch (_: SecurityException) {
                        // Audio permission was revoked between check and record; fall back
                        // to video-only rather than crashing.
                        pendingRecording
                    }
                }

                finalizeResult = CompletableDeferred()

                activeRecording = pendingRecording.start(cameraExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            _recordingState.value = RecordingState.Recording(
                                startedAtMillis = System.currentTimeMillis()
                            )
                        }

                        is VideoRecordEvent.Finalize -> {
                            activeRecording = null
                            val result = if (!event.hasError()) {
                                RecordingStopResult.Saved(
                                    uri = event.outputResults.outputUri.toString()
                                )
                            } else {
                                RecordingStopResult.Failure(
                                    message = event.cause?.message ?: "Recording failed",
                                    cause = event.cause
                                )
                            }
                            finalizeResult?.complete(result)
                            _recordingState.value = when (result) {
                                is RecordingStopResult.Saved -> RecordingState.Saved(result.uri)
                                is RecordingStopResult.Failure -> RecordingState.Error(
                                    message = result.message,
                                    cause = result.cause
                                )
                            }
                        }
                    }
                }

                RecordingStartResult.Started
            } catch (e: Exception) {
                _recordingState.value = RecordingState.Error(
                    message = e.message ?: "Failed to start recording",
                    cause = e
                )
                RecordingStartResult.Failure(e.message ?: "Failed to start recording", e)
            }
        }
    }

    override suspend fun stopRecording(): RecordingStopResult {
        return withContext(dispatcherProvider.io) {
            val recording = activeRecording
                ?: return@withContext RecordingStopResult.Failure("No active recording")

            _recordingState.value = RecordingState.Stopping

            try {
                recording.stop()
                val result = finalizeResult?.await()
                    ?: RecordingStopResult.Failure("Recording did not finalize")
                finalizeResult = null
                result
            } catch (e: Exception) {
                RecordingStopResult.Failure(e.message ?: "Failed to stop recording", e)
            }
        }
    }

    private fun attachSurfaceProviderIfReady() {
        val view = previewView ?: return
        val preview = previewUseCase ?: return
        preview.setSurfaceProvider(view.surfaceProvider)
    }

    private fun generateFilename(prefix: String): String {
        val timestamp = SimpleDateFormat(FILENAME_TIMESTAMP, Locale.getDefault()).format(Date())
        return "${prefix}_$timestamp.mp4"
    }

    fun shutdown() {
        controllerScope.cancel()
        analysisScope.cancel()
        cameraExecutor.shutdown()
    }

    private companion object {
        const val MIME_TYPE = "video/mp4"
        const val OUTPUT_DIRECTORY = "BlurGuard"
        const val FILENAME_TIMESTAMP = "yyyy-MM-dd_HH-mm"

        /** Target analysis resolution — detection models downscale further anyway. */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480
    }
}