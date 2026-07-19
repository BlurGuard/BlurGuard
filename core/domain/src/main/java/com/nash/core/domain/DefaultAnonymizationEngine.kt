package com.nash.core.domain

import com.nash.core.model.AnonymizationEngine
import com.nash.core.model.FrameSource
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.flow.StateFlow

/**
 * Wires a [FrameSource] to the [DefaultAnonymizationPipeline] and exposes the
 * non-generic [AnonymizationEngine] surface to the rest of the app.
 *
 * F is erased at the interface boundary: this is the LAST place the frame type
 * exists. Everything downstream sees only metadata.
 */
class DefaultAnonymizationEngine<F>(
    private val frameSource: FrameSource<F>,
    private val pipeline: DefaultAnonymizationPipeline<F>
) : AnonymizationEngine {

    override val trackedBoxes: StateFlow<List<TrackedBox>>
        get() = pipeline.trackedBoxes

    override val stats: StateFlow<PipelineStats>
        get() = pipeline.stats
    override fun start() {
        pipeline.reset()
        frameSource.setFrameConsumer(pipeline)
    }

    override fun stop() {
        frameSource.setFrameConsumer(null)
        pipeline.reset()
    }
}