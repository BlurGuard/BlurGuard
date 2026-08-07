package com.nash.engine.camera

import com.nash.core.model.RecordingStartResult
import com.nash.core.model.RecordingStopResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecordingErrorMapperTest {

    private val mapper = RecordingErrorMapper()

    @Test
    fun `startFailure maps SecurityException to permission message`() {
        val error = SecurityException("Permission denied")
        val result = mapper.startFailure(error)
        
        assertTrue(result is RecordingStartResult.Failure)
        assertEquals("Missing permission to start recording", result.message)
        assertEquals(error, result.cause)
    }

    @Test
    fun `startFailure maps IllegalStateException to custom message or fallback`() {
        val error = IllegalStateException("Camera not ready")
        val result = mapper.startFailure(error)
        
        assertTrue(result is RecordingStartResult.Failure)
        assertEquals("Camera not ready", result.message)
        
        val errorNoMsg = IllegalStateException()
        val resultFallback = mapper.startFailure(errorNoMsg)
        assertEquals("Recorder is not ready to start", resultFallback.message)
    }

    @Test
    fun `startFailure maps IllegalArgumentException to custom message or fallback`() {
        val error = IllegalArgumentException("Bad quality")
        val result = mapper.startFailure(error)
        
        assertTrue(result is RecordingStartResult.Failure)
        assertEquals("Bad quality", result.message)
        
        val errorNoMsg = IllegalArgumentException()
        val resultFallback = mapper.startFailure(errorNoMsg)
        assertEquals("Invalid recording request", resultFallback.message)
    }

    @Test
    fun `startFailure maps IOException to custom message or fallback`() {
        val error = IOException("Disk full")
        val result = mapper.startFailure(error)
        
        assertTrue(result is RecordingStartResult.Failure)
        assertEquals("Disk full", result.message)
        
        val errorNoMsg = IOException()
        val resultFallback = mapper.startFailure(errorNoMsg)
        assertEquals("Failed to create recording output", resultFallback.message)
    }

    @Test
    fun `startFailure maps generic Exception to generic message`() {
        val error = RuntimeException("Unknown error")
        val result = mapper.startFailure(error)
        
        assertTrue(result is RecordingStartResult.Failure)
        assertEquals("Unknown error", result.message)
        
        val errorNoMsg = RuntimeException()
        val resultFallback = mapper.startFailure(errorNoMsg)
        assertEquals("Failed to start recording", resultFallback.message)
    }

    @Test
    fun `stopFailure maps IllegalStateException to custom message or fallback`() {
        val error = IllegalStateException("Stop failed")
        val result = mapper.stopFailure(error)
        
        assertTrue(result is RecordingStopResult.Failure)
        assertEquals("Stop failed", result.message)
        
        val errorNoMsg = IllegalStateException()
        val resultFallback = mapper.stopFailure(errorNoMsg)
        assertEquals("Recorder is not ready to stop", resultFallback.message)
    }

    @Test
    fun `stopFailure maps generic Exception to generic message`() {
        val error = RuntimeException("Unknown stop error")
        val result = mapper.stopFailure(error)
        
        assertTrue(result is RecordingStopResult.Failure)
        assertEquals("Unknown stop error", result.message)
        
        val errorNoMsg = RuntimeException()
        val resultFallback = mapper.stopFailure(errorNoMsg)
        assertEquals("Failed to stop recording", resultFallback.message)
    }
}
