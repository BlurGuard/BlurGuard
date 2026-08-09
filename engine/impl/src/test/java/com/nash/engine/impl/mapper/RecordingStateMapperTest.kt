package com.nash.engine.impl.mapper

import com.nash.core.model.TimeProvider
import com.nash.engine.api.RecordingRequest
import com.nash.engine.api.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nash.core.model.RecordingState as CoreRecordingState

class RecordingStateMapperTest {
    private class FixedTimeProvider(private val nowMillis: Long) : TimeProvider {
        override fun currentTimeMillis(): Long = nowMillis
        override fun nanoTime(): Long = nowMillis * 1_000_000L
    }

    private val mapper = RecordingStateMapper(FixedTimeProvider(nowMillis = 10_000L))
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
    fun `maps recording state with exact elapsed duration from fake time`() {
        val api = mapper.toApi(
            CoreRecordingState.Recording(startedAtMillis = 4_000L),
            request,
        )

        assertTrue(api is RecordingState.Recording)
        api as RecordingState.Recording

        assertEquals(request, api.request)
        assertEquals(6_000L, api.durationMillis)
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
