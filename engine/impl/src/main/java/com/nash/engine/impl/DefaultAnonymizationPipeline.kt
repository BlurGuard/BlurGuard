package com.nash.engine.impl

import com.nash.core.model.FrameConsumer
import com.nash.core.model.FrameMetadata
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import com.nash.engine.impl.pipeline.DetectionRunner
import com.nash.engine.impl.pipeline.DetectionScheduler
import com.nash.engine.impl.pipeline.KeepVisibleStage
import com.nash.engine.impl.pipeline.PipelineStage
import com.nash.engine.impl.pipeline.PipelineStatsCollector
import com.nash.engine.impl.pipeline.TrackedBoxPublisher
import com.nash.engine.impl.pipeline.TrackingStage
import com.nash.engine.impl.pipeline.VisibleRegionBoxMapper
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin per-frame orchestrator. Owns sequencing only — every actual
 * responsibility lives in a [PipelineStage] under `pipeline/`:
 *
 *  scheduler -> detectionRunner -> trackingStage -> keepVisibleStage
 *            -> boxMapper -> publisher, with statsCollector observing.
 *
 * Generic over the frame type F so the whole class is unit-testable on the JVM
 * with fake frames (F = String in tests). No @Inject on purpose — the DI
 * module constructs it, pinning the concrete F exactly once.
 *
 * Concurrency contract: [onFrame] is called serially by the FrameSource on the
 * single-parallelism ml dispatcher, and the frame is only valid until onFrame
 * returns (the source closes it). Nothing here retains the frame.
 *
 * Backpressure: while this method suspends, the camera's KEEP_ONLY_LATEST
 * strategy drops stale frames — latest-wins by construction, never
 * queue-and-lag.
 *
 * The constructor is internal because the stages are internal to engine:impl.
 * The observable surface ([trackedBoxes], [stats], [onFrame], [reset]) stays
 * public.
 */
class DefaultAnonymizationPipeline<F> internal constructor(
    private val scheduler: DetectionScheduler,
    private val detectionRunner: DetectionRunner<F>,
    private val trackingStage: TrackingStage,
    private val keepVisibleStage: KeepVisibleStage<F>,
    private val boxMapper: VisibleRegionBoxMapper,
    private val publisher: TrackedBoxPublisher,
    private val statsCollector: PipelineStatsCollector,
    private val clock: () -> Long = System::nanoTime,
) : FrameConsumer<F> {

    private val stages: List<PipelineStage> = listOf(
        scheduler, trackingStage, keepVisibleStage, publisher, statsCollector,
    )

    /** Latest boxes, normalized to the visible region of the upright frame. */
    val trackedBoxes: StateFlow<List<TrackedBox>> get() = publisher.trackedBoxes

    /** Live pipeline performance counters (debug). */
    val stats: StateFlow<PipelineStats> get() = statsCollector.stats

    override suspend fun onFrame(frame: F, metadata: FrameMetadata) {
        val startNanos = clock()
        val detectionDue = scheduler.isDetectionDue(metadata)

        val boxes = if (detectionDue) {
            val detections = detectionRunner.detect(frame, metadata)
            val tracked = trackingStage.update(detections.boxes, metadata)
            keepVisibleStage.onDetectionFrame(frame, metadata, tracked)
        } else {
            keepVisibleStage.decorate(trackingStage.predict(metadata))
        }

        // Single publish site for both paths: the privacy-critical hand-off
        // exists exactly once, so it cannot drift between branches.
        publisher.publish(boxMapper.remap(boxes, metadata), metadata.rotationDegrees)

        if (detectionDue) statsCollector.recordDetectionLatency(clock() - startNanos)
        statsCollector.onFrameProcessed(startNanos)
    }

    /** Clears all per-session state. Safe to call between camera sessions. */
    fun reset() {
        stages.forEach(PipelineStage::reset)
    }
}