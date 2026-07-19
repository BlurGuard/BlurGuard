package com.nash.core.domain

import com.nash.core.model.Detector
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import com.nash.core.model.Tracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The per-frame orchestrator: detectors -> tracker -> published tracked boxes.
 *
 * Generic over the frame type F, so this whole class is unit-testable on the
 * JVM with fake frames (F = String in tests). No @Inject on purpose — the app
 * DI module constructs it, pinning the concrete F exactly once.
 *
 * Concurrency contract: [onFrame] is called serially by the FrameSource on the
 * single-parallelism ml dispatcher, and the frame is only valid until onFrame
 * returns (the source closes it). Nothing here retains the frame.
 *
 * Backpressure: while this method suspends, the camera's KEEP_ONLY_LATEST
 * strategy drops stale frames — latest-wins by construction, never queue-and-lag.
 */
class DefaultAnonymizationPipeline<F>(
    private val detectors: List<Detector<F>>,
    private val tracker: Tracker
) : FrameConsumer<F> {

    private val _trackedBoxes = MutableStateFlow<List<TrackedBox>>(emptyList())
    val trackedBoxes: StateFlow<List<TrackedBox>> = _trackedBoxes.asStateFlow()


    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private var windowStartNanos = 0L
    private var windowFrameCount = 0
    override suspend fun onFrame(frame: F, metadata: FrameMetadata) {
        val startNanos = System.nanoTime()

        val detections = detectors.flatMap { detector ->
            try {
                detector.detect(frame, metadata)
            } catch (e: Exception) {
                emptyList()
            }
        }
        _trackedBoxes.value = tracker.update(detections, metadata)

        // --- Perf counters: 1-second window over processed frames.
        val latencyMillis = (System.nanoTime() - startNanos) / 1_000_000
        if (windowStartNanos == 0L) windowStartNanos = startNanos
        windowFrameCount++
        val windowNanos = System.nanoTime() - windowStartNanos
        if (windowNanos >= 1_000_000_000L) {
            _stats.value = PipelineStats(
                fps = windowFrameCount * 1_000_000_000f / windowNanos,
                detectionLatencyMillis = latencyMillis
            )
            windowStartNanos = System.nanoTime()
            windowFrameCount = 0
        }
    }

    fun reset() {
        tracker.reset()
        _trackedBoxes.value = emptyList()
        _stats.value = PipelineStats()
        windowStartNanos = 0L
        windowFrameCount = 0
    }
}