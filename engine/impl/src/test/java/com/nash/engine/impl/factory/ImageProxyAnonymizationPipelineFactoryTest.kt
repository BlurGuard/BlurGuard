package com.nash.engine.impl.factory

import androidx.camera.core.ImageProxy
import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.Detector
import com.nash.core.model.DetectorConfig
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.PersonId
import com.nash.core.model.PipelineStats
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.Tracker
import com.nash.core.model.TrackerConfig
import com.nash.core.model.TrustedPersonStore
import com.nash.engine.impl.keepvisible.KeepVisibleOrchestrator
import com.nash.engine.tracking.ByteTrackTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Construction-contract tests for [ImageProxyAnonymizationPipelineFactory]:
 * configs flow to the right sub-factories, and the produced pipeline is
 * usable with clean initial state. Real stages are used (they are cheap);
 * only the Android-bound seams are faked.
 */
class ImageProxyAnonymizationPipelineFactoryTest {

    private class FakeDetector : Detector<ImageProxy> {
        override suspend fun detect(
            frame: ImageProxy,
            metadata: FrameMetadata
        ): List<DetectionBox> = emptyList()

        override fun close() = Unit
    }

    private class FakeDetectorFactory(
        private val detector: Detector<ImageProxy> = FakeDetector(),
    ) : DetectorFactory<ImageProxy> {
        val receivedConfigs = mutableListOf<DetectorConfig>()

        override fun create(config: DetectorConfig): List<Detector<ImageProxy>> {
            receivedConfigs += config
            return listOf(detector)
        }
    }

    private class FakeTrackerFactory : TrackerFactory {
        val receivedConfigs = mutableListOf<TrackerConfig>()

        override fun create(config: TrackerConfig): Tracker {
            receivedConfigs += config
            return ByteTrackTracker(config)
        }
    }

    private class FakeRecognizer : FaceRecognizer<ImageProxy> {
        override suspend fun embed(
            frame: ImageProxy,
            faceBox: BoundingBox,
            metadata: FrameMetadata
        ): FaceEmbedding? = null

        override fun close() = Unit
    }

    private class FakeTrustedPersonStore : TrustedPersonStore {
        override fun enroll(embedding: FaceEmbedding): PersonId = error("not used")
        override fun addToGallery(personId: PersonId, embedding: FaceEmbedding): Boolean = false
        override fun bestMatch(embedding: FaceEmbedding): TrustedPersonStore.Match? = null
        override fun revoke(personId: PersonId) = Unit
        override fun revokeAll() = Unit
        override val trustedPersonCount: Int get() = 0
    }

    private val detectorConfig = DetectorConfig()
    private val trackerConfig = TrackerConfig()

    private fun newFactory(
        detectorFactory: FakeDetectorFactory,
        trackerFactory: FakeTrackerFactory,
    ): ImageProxyAnonymizationPipelineFactory {
        val keepVisibleState = KeepVisibleState()
        val orchestrator = KeepVisibleOrchestrator(
            recognizer = FakeRecognizer(),
            store = FakeTrustedPersonStore(),
            state = keepVisibleState,
            config = RecognitionConfig(),
        )
        return ImageProxyAnonymizationPipelineFactory(
            detectorFactory = detectorFactory,
            trackerFactory = trackerFactory,
            detectorConfig = detectorConfig,
            trackerConfig = trackerConfig,
            renderBoxFeed = RenderBoxFeed(),
            keepVisibleOrchestrator = orchestrator,
            keepVisibleState = keepVisibleState,
        )
    }

    @Test
    fun `create passes the injected detector config to the detector factory`() {
        val detectorFactory = FakeDetectorFactory()
        newFactory(detectorFactory, FakeTrackerFactory()).create()
        assertEquals(listOf(detectorConfig), detectorFactory.receivedConfigs)
    }

    @Test
    fun `create passes the injected tracker config to the tracker factory`() {
        val trackerFactory = FakeTrackerFactory()
        newFactory(FakeDetectorFactory(), trackerFactory).create()
        assertEquals(listOf(trackerConfig), trackerFactory.receivedConfigs)
    }

    @Test
    fun `created pipeline starts with clean published state`() {
        val pipeline = newFactory(FakeDetectorFactory(), FakeTrackerFactory()).create()
        assertTrue(pipeline.trackedBoxes.value.isEmpty())
        assertEquals(PipelineStats(), pipeline.stats.value)
        assertFalse(pipeline.degraded.value)
    }

    @Test
    fun `each create builds a fresh pipeline and re-queries the factories`() {
        val detectorFactory = FakeDetectorFactory()
        val trackerFactory = FakeTrackerFactory()
        val factory = newFactory(detectorFactory, trackerFactory)

        val first = factory.create()
        val second = factory.create()

        assertNotSame(first, second)
        assertEquals(2, detectorFactory.receivedConfigs.size)
        assertEquals(2, trackerFactory.receivedConfigs.size)
    }
}