package com.nash.engine.impl

import android.content.Context
import android.view.View
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.FrameSource
import com.nash.core.model.KeepVisibleStateReader
import com.nash.core.model.PipelineStats
import com.nash.core.model.RecordingConfig
import com.nash.core.model.RecordingState
import com.nash.core.model.TimeProvider
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VideoRecorder
import com.nash.engine.api.EngineWarning
import com.nash.engine.api.keepvisible.KeepVisibleController
import com.nash.engine.camera.CameraSessionController
import com.nash.engine.camera.CameraSessionErrorSource
import com.nash.engine.impl.mapper.AnonymizationModeMapper
import com.nash.engine.impl.mapper.RecordingRequestMapper
import com.nash.engine.impl.mapper.RecordingStateMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [RealBlurGuardEngine.observeWarnings] merges both warning sources:
 * camera session bind errors (as [EngineWarning.CameraSessionError]) and
 * pipeline degradation (as [EngineWarning.DetectionDegraded]).
 */
class RealBlurGuardEngineWarningsTest {

    private class FakeCameraSession : CameraSessionController {
        override fun createPreviewView(context: Context): View = error("not used")
        override fun attachPreviewView(view: View) = Unit
        override fun bind(lifecycleOwner: LifecycleOwner) = Unit
        override fun unbind() = Unit
        override fun shutdown() = Unit
    }

    private class FakeSessionErrorSource : CameraSessionErrorSource {
        private val errors = MutableSharedFlow<Throwable>(replay = 1)
        override val bindErrors: Flow<Throwable> = errors
        suspend fun report(cause: Throwable) = errors.emit(cause)
    }

    private class FakeVideoRecorder : VideoRecorder {
        override suspend fun startRecording(config: RecordingConfig) = error("not used")
        override suspend fun stopRecording() = error("not used")
        override val recordingState: Flow<RecordingState> get() = error("not used")
    }

    private class FakeFrameSource : FrameSource<ImageProxy> {
        override fun setFrameConsumer(consumer: FrameConsumer<ImageProxy>?) = Unit
    }

    private class FakePipeline : AnonymizationPipeline<ImageProxy> {
        val degradedState = MutableStateFlow(false)
        override val trackedBoxes: StateFlow<List<TrackedBox>> get() = error("not used")
        override val stats: StateFlow<PipelineStats> get() = error("not used")
        override val degraded: StateFlow<Boolean> = degradedState
        override fun reset() = Unit
        override suspend fun onFrame(frame: ImageProxy, metadata: FrameMetadata) = Unit
    }

    private class FakeKeepVisibleState : KeepVisibleStateReader {
        override val verifications: StateFlow<Map<TrackId, TrackVerification>>
            get() = error("not used")
        override fun of(trackId: TrackId) = error("not used")
    }

    private class FakeKeepVisibleController : KeepVisibleController {
        override fun requestKeepVisible(trackId: TrackId) = Unit
        override fun revokeAll() = Unit
    }

    private class FixedTimeProvider : TimeProvider {
        override fun currentTimeMillis(): Long = 0L
        override fun nanoTime(): Long = 0L
    }

    private fun engine(
        pipeline: FakePipeline,
        sessionErrors: FakeSessionErrorSource,
    ) = RealBlurGuardEngine(
        cameraSession = FakeCameraSession(),
        sessionErrors = sessionErrors,
        videoRecorder = FakeVideoRecorder(),
        frameSource = FakeFrameSource(),
        pipeline = pipeline,
        keepVisibleState = FakeKeepVisibleState(),
        modeHolder = AnonymizationModeHolder(),
        keepVisibleController = FakeKeepVisibleController(),
        recordingRequestMapper = RecordingRequestMapper(),
        recordingStateMapper = RecordingStateMapper(FixedTimeProvider()),
        anonymizationModeMapper = AnonymizationModeMapper(),
    )

    @Test
    fun `emits CameraSessionError with the cause message when a bind error is reported`() = runTest {
        val pipeline = FakePipeline()
        val sessionErrors = FakeSessionErrorSource()
        sessionErrors.report(IllegalStateException("Camera HAL died"))

        val warning = engine(pipeline, sessionErrors).observeWarnings().first()

        assertEquals(EngineWarning.CameraSessionError("Camera HAL died"), warning)
    }

    @Test
    fun `falls back to a default message when the bind error has none`() = runTest {
        val pipeline = FakePipeline()
        val sessionErrors = FakeSessionErrorSource()
        sessionErrors.report(RuntimeException())

        val warning = engine(pipeline, sessionErrors).observeWarnings().first()

        assertEquals(EngineWarning.CameraSessionError("Failed to bind camera"), warning)
    }

    @Test
    fun `emits DetectionDegraded when the pipeline degrades`() = runTest {
        val pipeline = FakePipeline()
        val sessionErrors = FakeSessionErrorSource()
        pipeline.degradedState.value = true

        val warning = engine(pipeline, sessionErrors).observeWarnings().first()

        assertEquals(EngineWarning.DetectionDegraded, warning)
    }

    @Test
    fun `merges degradation and bind errors into one stream`() = runTest {
        val pipeline = FakePipeline()
        val sessionErrors = FakeSessionErrorSource()
        pipeline.degradedState.value = true
        sessionErrors.report(RuntimeException("bind failed"))

        val warnings = engine(pipeline, sessionErrors).observeWarnings().take(2).toList()

        assertTrue(warnings.contains(EngineWarning.DetectionDegraded))
        assertTrue(warnings.contains(EngineWarning.CameraSessionError("bind failed")))
    }
}
