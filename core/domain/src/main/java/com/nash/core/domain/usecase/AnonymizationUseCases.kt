package com.nash.core.domain.usecase

import com.nash.core.model.AnonymizationEngine
import com.nash.core.model.TrackedBox
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Starts the detection & tracking pipeline (frames begin flowing). */
class StartAnonymizationUseCase @Inject constructor(
    private val engine: AnonymizationEngine
) {
    operator fun invoke() = engine.start()
}

/** Stops the pipeline and clears all tracking state. */
class StopAnonymizationUseCase @Inject constructor(
    private val engine: AnonymizationEngine
) {
    operator fun invoke() = engine.stop()
}

/** Observes the latest tracked boxes (normalized, stable IDs). */
class ObserveTrackedBoxesUseCase @Inject constructor(
    private val engine: AnonymizationEngine
) {
    operator fun invoke(): StateFlow<List<TrackedBox>> = engine.trackedBoxes
}