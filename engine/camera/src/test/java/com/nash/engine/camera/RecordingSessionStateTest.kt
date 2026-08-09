package com.nash.engine.camera

import com.nash.core.model.RecordingStopResult
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionStateTest {

    private class FakeRecorder
    private class FakeRecording

    private val directExecutor = Executor { it.run() }
    private val state = RecordingSessionState<FakeRecorder, FakeRecording>()

    @Test
    fun `start is rejected when nothing is attached`() {
        runBlocking {
            val outcome = state.start { _, _ -> FakeRecording() }

            assertEquals(RecordingSessionState.StartOutcome.NotAttached, outcome)
        }
    }

    @Test
    fun `start is rejected after detach`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)
            state.detach()

            val outcome = state.start { _, _ -> FakeRecording() }

            assertEquals(RecordingSessionState.StartOutcome.NotAttached, outcome)
        }
    }

    @Test
    fun `start passes the attached recorder and executor to the factory`() {
        runBlocking {
            val recorder = FakeRecorder()
            val recording = FakeRecording()
            state.attach(recorder, directExecutor)

            var seenRecorder: FakeRecorder? = null
            var seenExecutor: Executor? = null
            val outcome = state.start { r, e ->
                seenRecorder = r
                seenExecutor = e
                recording
            }

            assertEquals(RecordingSessionState.StartOutcome.Started(recording), outcome)
            assertSame(recorder, seenRecorder)
            assertSame(directExecutor, seenExecutor)
        }
    }

    @Test
    fun `start is rejected while a recording is active`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)
            state.start { _, _ -> FakeRecording() }

            val outcome = state.start { _, _ -> FakeRecording() }

            assertEquals(RecordingSessionState.StartOutcome.AlreadyRecording, outcome)
        }
    }

    @Test
    fun `failed recording creation leaves the session startable`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)

            var thrown = false
            try {
                state.start { _, _ -> throw IllegalStateException("boom") }
            } catch (_: IllegalStateException) {
                thrown = true
            }

            assertTrue(thrown)
            assertTrue(
                state.start { _, _ -> FakeRecording() }
                        is RecordingSessionState.StartOutcome.Started
            )
        }
    }

    @Test
    fun `claimStop returns null when no recording is active`() {
        runBlocking {
            assertNull(state.claimStop())
        }
    }

    @Test
    fun `finish completes the claimed finalize deferred and clears the active recording`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)
            state.start { _, _ -> FakeRecording() }
            val claim = state.claimStop()
            val result = RecordingStopResult.Saved(uri = "content://media/video/1")

            state.finish(result)

            assertEquals(result, claim?.finalizeResult?.await())
            assertNull(state.claimStop())
            assertTrue(
                state.start { _, _ -> FakeRecording() }
                        is RecordingSessionState.StartOutcome.Started
            )
        }
    }

    @Test
    fun `clearActiveRecording returns the recording and keeps the finalize deferred`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)
            val recording = FakeRecording()
            state.start { _, _ -> recording }
            val claim = state.claimStop()

            assertSame(recording, state.clearActiveRecording())

            val result = RecordingStopResult.Failure(message = "boom", cause = null)
            state.finish(result)
            assertEquals(result, claim?.finalizeResult?.await())
        }
    }

    @Test
    fun `clearFinalize drops the deferred so a later finish completes nothing`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)
            state.start { _, _ -> FakeRecording() }
            val claim = state.claimStop()

            state.clearFinalize()
            state.finish(RecordingStopResult.Failure(message = "boom", cause = null))

            assertFalse(claim?.finalizeResult?.isCompleted ?: true)
        }
    }

    @Test
    fun `only one of many concurrent starts wins`() {
        runBlocking {
            state.attach(FakeRecorder(), directExecutor)

            val outcomes = (1..50).map {
                async(Dispatchers.Default) {
                    state.start { _, _ -> FakeRecording() }
                }
            }.awaitAll()

            assertEquals(
                1,
                outcomes.count { it is RecordingSessionState.StartOutcome.Started }
            )
            assertEquals(
                49,
                outcomes.count { it == RecordingSessionState.StartOutcome.AlreadyRecording }
            )
        }
    }
}