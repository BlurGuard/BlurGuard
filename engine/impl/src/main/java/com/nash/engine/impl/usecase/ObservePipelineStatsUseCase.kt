package com.nash.engine.impl.usecase

import com.nash.core.model.AnonymizationEngine
import com.nash.core.model.PipelineStats
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

class ObservePipelineStatsUseCase @Inject constructor(
    private val engine: AnonymizationEngine
) {
    operator fun invoke(): StateFlow<PipelineStats> = engine.stats
}
