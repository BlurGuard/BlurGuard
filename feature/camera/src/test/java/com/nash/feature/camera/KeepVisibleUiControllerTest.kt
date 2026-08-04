package com.nash.feature.camera

import androidx.lifecycle.LifecycleOwner
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import com.nash.engine.api.AnonymizationMode
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.EngineConfig
import com.nash.engine.api.EngineWarning
import com.nash.engine.api.PreviewTarget
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import com.nash.engine.api.TrustedFaceRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepVisibleUiControllerTest {

    private class FakeEngine : BlurGuardEngine {
        val keepVisibleFlow = MutableStateFlow<Map<TrackId, TrackVerification>>(emptyMap())
        val trustedFacesCalls = mutableListOf<List<TrustedFaceRef>>()

        override fun bind(
            lifecycleOwner: LifecycleOwner,
            previewTarget: PreviewTarget,
            config: EngineConfig
        ) = Unit

        override fun startRecording(request: RecordingRequest): Flow<RecordingState> = emptyFlow()
        override suspend fun stopRecording() = Unit
        override suspend fun updateAnonymizationMode(mode: AnonymizationMode) = Unit
        override suspend fun updateTrustedFaces(faces: List<TrustedFaceRef>) {
            trustedFacesCalls += faces
        }

        override fun observeWarnings(): Flow<EngineWarning> = emptyFlow()
        override val trackedBoxes: StateFlow<List<TrackedBox>> = MutableStateFlow(emptyList())
        override val keepVisible: StateFlow<Map<TrackId, TrackVerification>> get() = keepVisibleFlow
        override val stats: StateFlow<PipelineStats> = MutableStateFlow(PipelineStats())
    }

    private class TestDispatcherProvider(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
        override val unconfined = dispatcher
        override val ml = dispatcher
    }

    private val track = TrackId(1L)

    private fun TestScope.controller(engine: FakeEngine) = KeepVisibleUiController(
        engine = engine,
        scope = backgroundScope,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `tap then pending then trusted emits trusted message once`() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.onFaceTapped(track)
        runCurrent()
        assertEquals(listOf(listOf(TrustedFaceRef(track))), engine.trustedFacesCalls)

        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.PENDING))
        runCurrent()
        assertNull(controller.message.value)

        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.TRUSTED))
        runCurrent()
        assertEquals(R.string.keep_visible_trusted, controller.message.value)

        controller.onMessageShown()
        assertNull(controller.message.value)

        // Enrollment cleared on terminal state: further updates emit nothing.
        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.TRUSTED, consecutiveMatches = 5))
        runCurrent()
        assertNull(controller.message.value)
    }

    @Test
    fun `tap then pending then rejected emits rejected message`() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.onFaceTapped(track)
        runCurrent()

        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.PENDING))
        runCurrent()
        assertNull(controller.message.value)

        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.REJECTED))
        runCurrent()
        assertEquals(R.string.keep_visible_rejected, controller.message.value)
    }

    @Test
    fun `track death after pending clears enrollment silently`() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.onFaceTapped(track)
        runCurrent()

        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.PENDING))
        runCurrent()

        engine.keepVisibleFlow.value = emptyMap()
        runCurrent()
        assertNull(controller.message.value)

        // A later TRUSTED for the same (recycled) id must not emit — enrollment gone.
        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.TRUSTED))
        runCurrent()
        assertNull(controller.message.value)
    }

    @Test
    fun `absence before first appearance does not clear enrollment`() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.onFaceTapped(track)
        runCurrent()

        // Engine hasn't enrolled yet — map updates without the target.
        engine.keepVisibleFlow.value =
            mapOf(TrackId(99L) to TrackVerification(state = VerificationState.UNKNOWN))
        runCurrent()

        // Target finally appears and gets trusted: message must still arrive.
        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.TRUSTED))
        runCurrent()
        assertEquals(R.string.keep_visible_trusted, controller.message.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `revoke all clears enrollment and requests empty trusted set`() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.onFaceTapped(track)
        runCurrent()
        controller.onRevokeAll()
        runCurrent()
        assertEquals(
            listOf(listOf(TrustedFaceRef(track)), emptyList()),
            engine.trustedFacesCalls
        )

        // Pending → trusted after revoke must not emit.
        engine.keepVisibleFlow.value =
            mapOf(track to TrackVerification(state = VerificationState.TRUSTED))
        runCurrent()
        assertNull(controller.message.value)
    }
}