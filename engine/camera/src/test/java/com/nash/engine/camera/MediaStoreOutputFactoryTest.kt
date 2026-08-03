package com.nash.engine.camera

import java.util.Date
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreOutputFactoryTest {

    @Test
    fun `filename uses prefix, timestamp pattern, and mp4 extension`() {
        val name = MediaStoreOutputFactory.generateFilename("BlurGuard", Date(0))
        // e.g. BlurGuard_1970-01-01_03-00.mp4 (timezone-dependent)
        assertTrue(name.startsWith("BlurGuard_"))
        assertTrue(name.endsWith(".mp4"))
        assertTrue(Regex("""BlurGuard_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.mp4""").matches(name))
    }
}