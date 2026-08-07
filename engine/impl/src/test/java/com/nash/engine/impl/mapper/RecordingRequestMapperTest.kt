package com.nash.engine.impl.mapper

import com.nash.engine.api.RecordingRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRequestMapperTest {
    private val mapper = RecordingRequestMapper()

    @Test
    fun `maps request fields to core config`() {
        val config = mapper.toCore(
            RecordingRequest(includeAudio = false, outputFileName = "CustomName"),
        )

        assertEquals(false, config.includeAudio)
        assertEquals("CustomName", config.fileNamePrefix)
    }

    @Test
    fun `uses BlurGuard as default file prefix`() {
        val config = mapper.toCore(RecordingRequest(outputFileName = null))

        assertEquals("BlurGuard", config.fileNamePrefix)
    }
}