package com.nash.engine.impl.pipeline

/**
 * A single stage of the per-frame anonymization pipeline.
 *
 * Threading contract: every stage is driven exclusively from
 * [com.nash.engine.impl.DefaultAnonymizationPipeline.onFrame], which the
 * FrameSource calls serially on the single-parallelism ml dispatcher. Stage
 * state is therefore plain non-synchronized `var` on purpose — do NOT add
 * locks, atomics or @Synchronized, and do NOT call a stage from any other
 * thread. The only exception is RenderBoxFeed, which is internally atomic
 * because the GL thread polls it.
 *
 * Lifecycle: [reset] is invoked on session start/stop via the pipeline and
 * must return the stage to its exact construction-time state.
 */
internal interface PipelineStage {
    /** Restores construction-time state. Default no-op for stateless stages. */
    fun reset() {}
}