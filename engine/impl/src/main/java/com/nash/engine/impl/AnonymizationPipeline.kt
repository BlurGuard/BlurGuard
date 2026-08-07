package com.nash.engine.impl

import com.nash.core.model.FrameConsumer
import com.nash.core.model.PipelineStats
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.flow.StateFlow

/**
 * Frame-processing pipeline boundary consumed by the engine coordinator.
 *
 * The coordinator only needs to feed frames, observe safe state, and reset
 * session-scoped pipeline state. Concrete stage composition remains hidden
 * behind this abstraction so orchestration depends on policy, not on the
 * default implementation class.
 */
interface AnonymizationPipeline<F> : FrameConsumer<F> {
    /** Latest remapped tracked boxes. Conflated latest-wins state. */
    val trackedBoxes: StateFlow<List<TrackedBox>>

    /** Live pipeline performance counters (debug). */
    val stats: StateFlow<PipelineStats>

    /** True while the latest detection pass reported degraded detector health. */
    val degraded: StateFlow<Boolean>

    /** Clears all session-scoped tracking, stats, keep-visible, and health state. */
    fun reset()
}