package com.nash.engine.impl.factory

import com.nash.engine.impl.DefaultAnonymizationPipeline

/**
 * Owns construction of the per-frame orchestrator: stage wiring, detector
 * selection (via [DetectorFactory]), tracker selection (via [TrackerFactory]),
 * and the shared clock. DI modules only bind and delegate.
 *
 * Generic over the frame type, matching [DefaultAnonymizationPipeline].
 */
internal interface AnonymizationPipelineFactory<F> {
    fun create(): DefaultAnonymizationPipeline<F>
}