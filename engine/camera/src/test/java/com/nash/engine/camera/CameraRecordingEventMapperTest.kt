package com.nash.engine.camera

import com.nash.core.model.RecordingState
import com.nash.core.model.RecordingStopResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecordingEventMapperTest {

    private val mapper = CameraRecordingEventMapper()

    @Test
    fun `finalize without error maps to Saved with the output uri`() {
        val result = mapper.mapFinalize(
            hasError = false,
            cause = null,
            outputUri = { "content://media/video/1" },
        )

        assertEquals(RecordingStopResult.Saved(uri = "content://media/video/1"), result)
    }

    @Test
    fun `finalize with error maps to Failure with the cause message`() {
        val cause = RuntimeException("disk full")

        val result = mapper.mapFinalize(
            hasError = true,
            cause = cause,
            outputUri = { "content://media/video/1" },
        )

        assertEquals(
            RecordingStopResult.Failure(message = "disk full", cause = cause),
            result
        )
    }

    @Test
    fun `finalize with error and no cause falls back to default message`() {
        val result = mapper.mapFinalize(
            hasError = true,
            cause = null,
            outputUri = { "content://media/video/1" },
        )

        assertEquals(
            RecordingStopResult.Failure(message = "Recording failed", cause = null),
            result
        )
    }

    @Test
    fun `finalize with error and blank-message cause falls back to default message`() {
        val cause = RuntimeException()

        val result = mapper.mapFinalize(
            hasError = true,
            cause = cause,
            outputUri = { "content://media/video/1" },
        )

        assertEquals(
            RecordingStopResult.Failure(message = "Recording failed", cause = cause),
            result
        )
    }

    @Test
    fun `finalize with error never reads the output uri`() {
        var uriReads = 0

        mapper.mapFinalize(
            hasError = true,
            cause = RuntimeException("boom"),
            outputUri = {
                uriReads++
                "content://media/video/1"
            },
        )

        assertEquals(0, uriReads)
    }

    @Test
    fun `saved result maps to Saved state`() {
        val state = mapper.mapState(RecordingStopResult.Saved(uri = "content://media/video/1"))

        assertEquals(RecordingState.Saved(uri = "content://media/video/1"), state)
    }

    @Test
    fun `failure result maps to Error state with message and cause`() {
        val cause = RuntimeException("boom")

        val state = mapper.mapState(
            RecordingStopResult.Failure(message = "boom", cause = cause)
        )

        assertEquals(RecordingState.Error(message = "boom", cause = cause), state)
    }
}
