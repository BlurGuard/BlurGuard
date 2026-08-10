package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.FaceEmbedding
import com.nash.core.model.FaceRecognizer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.RecognitionConfig
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The size gate is what stands between a distant face and ~34 ms of wasted
 * bitmap, rotation, crop and BlazeFace work per attempt. It must reject on
 * BOTH axes, and it must measure against upright dimensions.
 */
class KeepVisibleGateTest {

    private val embedding = FaceEmbedding.fromRaw(floatArrayOf(1f, 0f, 0f))!!

    private class CountingRecognizer(
        private val result: FaceEmbedding
    ) : FaceRecognizer<String> {
        var calls = 0
            private set

        override suspend fun embed(
            frame: String,
            faceBox: BoundingBox,
            metadata: FrameMetadata
        ): FaceEmbedding? {
            calls++
            return result
        }

        override fun close() = Unit
    }

    /** Normalized box anchored at (0.1, 0.1), sized [w] x [h] in normalized units. */
    private fun face(w: Float, h: Float) = TrackedBox(
        id = TrackId(1L),
        box = BoundingBox(0.1f, 0.1f, 0.1f + w, 0.1f + h),
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L,
    )

    /**
     * Drives enough frames to clear the track-age gate, with a clock that
     * advances past the interval floor each frame so only the size gate can
     * be responsible for a zero call count.
     */
    private fun runFrames(
        track: TrackedBox,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
    ): Int {
        val recognizer = CountingRecognizer(embedding)
        val store = SessionTrustedPersonStore(maxGallerySize = 5, duplicateSimilarity = 0.95f)
        // One enrolled person, so the automatic verify path is reachable.
        store.enroll(embedding)

        var clockMs = 100_000L
        val config = RecognitionConfig()
        val state = SessionKeepVisibleStateStore()
        val commands = KeepVisibleCommandQueue()
        val liveTracks = LiveTrackRegistry(config) { clockMs }
        val gate = RecognitionGate(config, liveTracks)
        val keepVisible = KeepVisibleRecognizerImpl(
            commands = commands,
            liveTracks = liveTracks,
            selector = RecognitionCandidateSelector(
                commands, liveTracks, gate, state, store, config
            ),
            enrollmentPolicy = EnrollmentPolicy(
                recognizer, store, state, commands, liveTracks, config
            ),
            verificationPolicy = VerificationPolicy(recognizer, store, state, liveTracks, config),
            reVerificationPolicy = ReVerificationPolicy(recognizer, store, state, liveTracks, config),
            state = state,
            store = store,
        )

        runBlocking {
            repeat(FRAMES) { frame ->
                keepVisible.onDetectionFrame(
                    frame = "frame-$frame",
                    metadata = FrameMetadata(
                        frameId = frame.toLong(),
                        timestampNanos = 0L,
                        width = bufferWidth,
                        height = bufferHeight,
                        rotationDegrees = rotationDegrees,
                    ),
                    boxes = listOf(track),
                )
                clockMs += 1_000L
            }
        }
        return recognizer.calls
    }

    @Test
    fun `a short face is rejected even when it is wide enough`() {
        // 640x480 upright: 0.3125 -> 200px wide, 0.0833 -> 40px tall.
        // This is the shape that produced the 78x52 crops on device.
        val calls = runFrames(face(w = 0.3125f, h = 0.0833f), 640, 480, rotationDegrees = 0)

        assertEquals("height below minFaceBoxPx must skip", 0, calls)
    }

    @Test
    fun `a narrow face is rejected even when it is tall enough`() {
        // 0.0625 -> 40px wide, 0.4167 -> 200px tall.
        val calls = runFrames(face(w = 0.0625f, h = 0.4167f), 640, 480, rotationDegrees = 0)

        assertEquals("width below minFaceBoxPx must skip", 0, calls)
    }

    @Test
    fun `a face above the minimum on both axes is recognized`() {
        // 200px x 200px.
        val calls = runFrames(face(w = 0.3125f, h = 0.4167f), 640, 480, rotationDegrees = 0)

        assertEquals(1, calls)
    }

    @Test
    fun `the gate measures upright pixels, not buffer pixels`() {
        // Buffer 640x480 rotated 90 -> upright 480x640.
        // Upright: 0.2 * 480 = 96px wide, 0.12 * 640 = 76.8px tall -> both pass.
        // Against the raw buffer it would read 128 x 57.6 and wrongly skip.
        val calls = runFrames(face(w = 0.2f, h = 0.12f), 640, 480, rotationDegrees = 90)

        assertEquals("rotated frames must use transposed dimensions", 1, calls)
    }

    private companion object {
        /**
         * Enough frames to clear both the track-age gate (minTrackAgeFrames = 4)
         * and the automatic-verify retry interval (6 frames from the initial
         * lastCheckedFrame of -1), so the first eligible frame is 5.
         *
         * Deliberately below 11: after a pass at frame 5 the next one would not
         * be due until frame 11, so the positive tests can assert exactly 1.
         */
        const val FRAMES = 8
    }
}
