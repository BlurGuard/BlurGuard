package com.nash.engine.impl

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The per-frame orchestrator: schedule -> detect -> track -> keep-visible ->
 * remap -> publish. Each responsibility lives in its own stage under
 * [com.nash.engine.impl.pipeline]; this class only sequences them.
 *
 * Generic over the frame type F, so the whole pipeline is unit-testable on
 * the JVM with fake frames (F = String in tests). No @Inject on purpose —
 * EngineImplModule constructs it, pinning the concrete F exactly once.
 *
 * Concurrency contract: [onFrame] is called serially by the FrameSource on
 * the single-parallelism ml dispatcher, and the frame is only valid until
 * onFrame returns (the source closes it). Nothing here retains the frame.
 *
 * Backpressure: while this method suspends, the camera's KEEP_ONLY_LATEST
 * strategy drops stale frames — latest-wins by construction, never
 * queue-and-lag.
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
) : AnonymizationPipeline<F> {

    /** Latest remapped tracked boxes. Conflated latest-wins state. */
    override val trackedBoxes: StateFlow<List<TrackedBox>> get() = publisher.trackedBoxes

    /** Live pipeline performance counters (debug). */
    override val stats: StateFlow<PipelineStats> get() = statsCollector.stats

    private val _degraded = MutableStateFlow(false)

    /**
     * True while the LAST detection pass was incomplete (a detector threw),
     * meaning objects in frame may be temporarily unprotected. Predict frames
     * never touch this — it describes detection health and stays latched
     * until the next detection pass reports clean.
     */
    override val degraded: StateFlow<Boolean> = _degraded.asStateFlow()

    private val stages: List<PipelineStage> = listOf(
        scheduler,
        trackingStage,
        keepVisibleStage,
        publisher,
        statsCollector,
    )

    override suspend fun onFrame(frame: F, metadata: FrameMetadata) {
        val startNanos = clock()

        if (scheduler.isDetectionDue(metadata)) {
            val detections = detectionRunner.detect(frame, metadata)
            _degraded.value = detections.degraded
            val tracked = trackingStage.update(detections.boxes, metadata)
            val decorated = keepVisibleStage.onDetectionFrame(frame, metadata, tracked)
            publisher.publish(boxMapper.remap(decorated, metadata), metadata.rotationDegrees)
            statsCollector.recordDetectionLatency(clock() - startNanos)
        } else {
            val decorated = keepVisibleStage.decorate(trackingStage.predict(metadata))
            publisher.publish(boxMapper.remap(decorated, metadata), metadata.rotationDegrees)
        }

        statsCollector.onFrameProcessed(startNanos)
    }

    override fun reset() {
        stages.forEach(PipelineStage::reset)
        // Deliberately not folded into a stage: `degraded` belongs to the
        // orchestrator, the only component that knows a detection pass ran.
        _degraded.value = false
    }
}