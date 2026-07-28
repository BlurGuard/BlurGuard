package com.nash.core.domain

import com.nash.core.domain.keepvisible.KeepVisibleOrchestrator
import com.nash.core.model.BoundingBox
import com.nash.core.model.Detector
import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.PipelineStats
import com.nash.core.model.RenderBoxFeed
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
    private val tracker: Tracker,
    private val keepVisibleState: KeepVisibleState,
    private val keepVisible: KeepVisibleOrchestrator<F>,
    private val renderBoxFeed: RenderBoxFeed,
    private val detectionInterval: Long = 2L
) : FrameConsumer<F> {

    private var lastDetectionFrameId = -1L
    private val _trackedBoxes = MutableStateFlow<List<TrackedBox>>(emptyList())
    val trackedBoxes: StateFlow<List<TrackedBox>> = _trackedBoxes.asStateFlow()


    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private var windowStartNanos = 0L
    private var windowFrameCount = 0
    private var windowDetectionCount = 0
    private var lastDetectionLatencyMillis = 0L
    override suspend fun onFrame(frame: F, metadata: FrameMetadata) {
        val startNanos = System.nanoTime()
        val detectionDue = lastDetectionFrameId < 0 ||
                metadata.frameId - lastDetectionFrameId >= detectionInterval

        if (detectionDue) {
            lastDetectionFrameId = metadata.frameId
            val detections = detectors.flatMap { detector ->
                try {
                    detector.detect(frame, metadata)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val boxes = tracker.update(detections,metadata)
            keepVisible.onDetectionFrame(frame, metadata, boxes)
            val visibleBoxes = keepVisibleState.decorate(boxes).remappedToVisibleRegion(metadata)
            _trackedBoxes.value = visibleBoxes
            renderBoxFeed.publish(visibleBoxes, metadata.rotationDegrees)
            lastDetectionLatencyMillis = (System.nanoTime() - startNanos) / 1_000_000
            windowDetectionCount++
        } else {
            val boxes = keepVisibleState.decorate(tracker.predict(metadata))
            val visibleBoxes = boxes.remappedToVisibleRegion(metadata)
            _trackedBoxes.value = visibleBoxes
            renderBoxFeed.publish(visibleBoxes, metadata.rotationDegrees)
        }

        // --- Perf counters: 1-second window over ALL processed frames.
        if (windowStartNanos == 0L) windowStartNanos = startNanos
        windowFrameCount++
        val windowNanos = System.nanoTime() - windowStartNanos
        if (windowNanos >= 1_000_000_000L) {
            _stats.value = PipelineStats(
                frameFps = windowFrameCount * 1_000_000_000f / windowNanos,
                fps = windowDetectionCount * 1_000_000_000f / windowNanos,
                detectionLatencyMillis = lastDetectionLatencyMillis
            )
            windowStartNanos = System.nanoTime()
            windowFrameCount = 0
            windowDetectionCount = 0
        }
    }

    private fun List<TrackedBox>.remappedToVisibleRegion(meta: FrameMetadata): List<TrackedBox> {
        val cw = if (meta.cropWidth > 0) meta.cropWidth else meta.width
        val ch = if (meta.cropHeight > 0) meta.cropHeight else meta.height
        if (cw == meta.width && ch == meta.height) return this // no crop, nothing to do

        // Crop rect normalized to the buffer, then rotated into the same upright
        // space the boxes live in.
        val crop = BoundingBox(
            left = meta.cropLeft / meta.width.toFloat(),
            top = meta.cropTop / meta.height.toFloat(),
            right = (meta.cropLeft + cw) / meta.width.toFloat(),
            bottom = (meta.cropTop + ch) / meta.height.toFloat(),
        ).rotatedToUpright(meta.rotationDegrees)

        val w = crop.right - crop.left
        val h = crop.bottom - crop.top
        return map { tracked ->
            tracked.copy(
                box = BoundingBox(
                    left = ((tracked.box.left - crop.left) / w).coerceIn(0f, 1f),
                    top = ((tracked.box.top - crop.top) / h).coerceIn(0f, 1f),
                    right = ((tracked.box.right - crop.left) / w).coerceIn(0f, 1f),
                    bottom = ((tracked.box.bottom - crop.top) / h).coerceIn(0f, 1f),
                )
            )
        }
    }

    fun reset() {
        tracker.reset()
        keepVisible.onSessionReset()
        lastDetectionFrameId = -1L
        _trackedBoxes.value = emptyList()
        _stats.value = PipelineStats()
        renderBoxFeed.clear()
        windowStartNanos = 0L
        windowFrameCount = 0
        windowDetectionCount = 0
        lastDetectionLatencyMillis = 0L
    }
}