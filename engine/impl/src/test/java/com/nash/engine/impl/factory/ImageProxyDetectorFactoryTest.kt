package com.nash.engine.impl.factory

import com.nash.core.model.DetectionBox
import com.nash.core.model.Detector
import com.nash.core.model.DetectorBackend
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The factory class itself needs the Android-bound concrete detectors, so the
 * selection contract is tested through [ImageProxyDetectorFactory.select]
 * with fake JVM detectors.
 */
class ImageProxyDetectorFactoryTest {

    private class FakeDetector : Detector<String> {
        override suspend fun detect(frame: String, metadata: FrameMetadata): List<DetectionBox> =
            emptyList()

        override fun close() = Unit
    }

    private val yolo = FakeDetector()
    private val mediaPipe = FakeDetector()

    @Test
    fun `YOLO backend selects only the yolo detector`() {
        val selected = ImageProxyDetectorFactory.select(
            DetectorConfig(backend = DetectorBackend.YOLO),
            yolo,
            mediaPipe,
        )
        assertEquals(1, selected.size)
        assertSame(yolo, selected[0])
    }

    @Test
    fun `MEDIAPIPE backend selects only the mediapipe detector`() {
        val selected = ImageProxyDetectorFactory.select(
            DetectorConfig(backend = DetectorBackend.MEDIAPIPE),
            yolo,
            mediaPipe,
        )
        assertEquals(1, selected.size)
        assertSame(mediaPipe, selected[0])
    }

    @Test
    fun `default config selects YOLO`() {
        val selected = ImageProxyDetectorFactory.select(DetectorConfig(), yolo, mediaPipe)
        assertEquals(listOf(yolo), selected)
    }

    @Test
    fun `selection is stable across calls`() {
        val config = DetectorConfig(backend = DetectorBackend.YOLO)
        val first = ImageProxyDetectorFactory.select(config, yolo, mediaPipe)
        val second = ImageProxyDetectorFactory.select(config, yolo, mediaPipe)
        assertEquals(first, second)
    }
}