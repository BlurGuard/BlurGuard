package com.nash.engine.impl.mapper

import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nash.core.model.RecordingState as CoreRecordingState

class RecordingStateMapperTest {
    private val mapper = RecordingStateMapper()
    private val request = RecordingRequest(includeAudio = true, outputFileName = "Clip")

    @Test
    fun `maps idle state`() {
        assertSame(RecordingState.Idle, mapper.toApi(CoreRecordingState.Idle, request))
    }

    @Test
    fun `maps starting state with request`() {
        assertEquals(RecordingState.Starting(request), mapper.toApi(CoreRecordingState.Starting, request))
    }

    @Test
    fun `maps recording state with elapsed duration`() {
        val api = mapper.toApi(
            CoreRecordingState.Recording(startedAtMillis = System.currentTimeMillis() - 1_000L),
            request,
        )

        assertTrue(api is RecordingState.Recording)
        api as RecordingState.Recording

        assertEquals(request, api.request)
        assertTrue(api.durationMillis >= 1_000L)
        assertEquals(0L, api.sizeBytes)
    }

    @Test
    fun `maps stopping state with request`() {
        assertEquals(RecordingState.Stopping(request), mapper.toApi(CoreRecordingState.Stopping, request))
    }

    @Test
    fun `maps error state`() {
        val cause = IllegalStateException("boom")
        val api = mapper.toApi(CoreRecordingState.Error("failed", cause), request)

        assertEquals(RecordingState.Error("failed", cause), api)
    }
}