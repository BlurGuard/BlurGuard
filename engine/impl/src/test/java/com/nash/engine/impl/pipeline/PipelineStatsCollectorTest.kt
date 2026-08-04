package com.nash.engine.impl.pipeline

import com.nash.core.model.PipelineStats
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineStatsCollectorTest {

    private val oneSecond = 1_000_000_000L

    @Test
    fun `no stats published before the window closes`() {
        val clock = FakeClock()
        val collector = PipelineStatsCollector(clock)

        repeat(10) {
            collector.onFrameProcessed(frameStartNanos = clock())
            clock.advance(oneSecond / 100)  // 10 ms apart
        }

        assertEquals(PipelineStats(), collector.stats.value)
    }

    @Test
    fun `window rollover publishes fps and latency`() {
        val clock = FakeClock()
        val collector = PipelineStatsCollector(clock)

        // 30 frames, 10 of them detections, spread over exactly one second.
        repeat(30) { i ->
            val start = clock()
            if (i % 3 == 0) collector.recordDetectionLatency(25 * 1_000_000L) // 25 ms
            collector.onFrameProcessed(start)
            clock.advance(oneSecond / 30)
        }
        // Close the window.
        collector.onFrameProcessed(clock())

        val stats = collector.stats.value
        assertEquals(31f, stats.frameFps, 0.5f)
        assertEquals(10f, stats.fps, 0.5f)
        assertEquals(25L, stats.detectionLatencyMillis)
    }

    @Test
    fun `counters restart after each window`() {
        val clock = FakeClock()
        val collector = PipelineStatsCollector(clock)

        collector.onFrameProcessed(clock())
        clock.advance(oneSecond)
        collector.onFrameProcessed(clock())     // closes window 1
        val first = collector.stats.value

        clock.advance(oneSecond)
        collector.onFrameProcessed(clock())     // closes window 2
        val second = collector.stats.value

        // Window 2 saw a single frame, so its fps must not accumulate window 1.
        assertEquals(0f, second.fps, 0.01f)
        assertEquals(0f, first.fps, 0.01f)
    }

    @Test
    fun `reset clears published stats and counters`() {
        val clock = FakeClock()
        val collector = PipelineStatsCollector(clock)
        collector.recordDetectionLatency(50 * 1_000_000L)
        collector.onFrameProcessed(clock())
        clock.advance(oneSecond)
        collector.onFrameProcessed(clock())

        collector.reset()

        assertEquals(PipelineStats(), collector.stats.value)
    }
}