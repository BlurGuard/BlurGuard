package com.nash.engine.impl.pipeline

import com.nash.core.model.PipelineStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rolling one-second window of pipeline performance counters.
 *
 * [clock] is injected so window roll-over is testable deterministically
 * instead of requiring a real one-second sleep.
 */
internal class PipelineStatsCollector(
    private val clock: () -> Long = System::nanoTime,
) : PipelineStage {

    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private var windowStartNanos = 0L
    private var windowFrameCount = 0
    private var windowDetectionCount = 0
    private var lastDetectionLatencyMillis = 0L

    /**
     * Records one completed detection pass.
     *
     * NOTE: [elapsedNanos] currently spans detection + recognition + publish,
     * matching the pre-refactor definition of `detectionLatencyMillis` so
     * historical benchmark numbers stay comparable. Narrowing this to the
     * detection stage alone is a deliberate follow-up.
     */
    fun recordDetectionLatency(elapsedNanos: Long) {
        lastDetectionLatencyMillis = elapsedNanos / 1_000_000
        windowDetectionCount++
    }

    /** Called once per processed frame, detection or predict. */
    fun onFrameProcessed(frameStartNanos: Long) {
        if (windowStartNanos == 0L) windowStartNanos = frameStartNanos
        windowFrameCount++

        val windowNanos = clock() - windowStartNanos
        if (windowNanos >= ONE_SECOND_NANOS) {
            _stats.value = PipelineStats(
                frameFps = windowFrameCount * ONE_SECOND_NANOS_F / windowNanos,
                fps = windowDetectionCount * ONE_SECOND_NANOS_F / windowNanos,
                detectionLatencyMillis = lastDetectionLatencyMillis,
            )
            windowStartNanos = clock()
            windowFrameCount = 0
            windowDetectionCount = 0
        }
    }

    override fun reset() {
        _stats.value = PipelineStats()
        windowStartNanos = 0L
        windowFrameCount = 0
        windowDetectionCount = 0
        lastDetectionLatencyMillis = 0L
    }

    private companion object {
        const val ONE_SECOND_NANOS = 1_000_000_000L
        const val ONE_SECOND_NANOS_F = 1_000_000_000f
    }
}