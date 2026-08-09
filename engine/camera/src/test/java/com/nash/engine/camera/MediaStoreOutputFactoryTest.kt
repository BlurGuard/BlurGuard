package com.nash.engine.camera

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreOutputFactoryTest {

    @Test
    fun `filename uses prefix, timestamp pattern, and mp4 extension`() {
        val name = MediaStoreOutputFactory.generateFilename("BlurGuard", Date(0))
        assertTrue(Regex("""BlurGuard_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.mp4""").matches(name))
    }

    @Test
    fun `unsafe characters are sanitized`() {
        assertEquals("my_clip_1", MediaStoreOutputFactory.sanitizePrefix("my clip/1"))
    }

    @Test
    fun `blank prefix falls back to default`() {
        assertEquals("BlurGuard", MediaStoreOutputFactory.sanitizePrefix("   "))
    }
}
