package com.nash.engine.impl.usecase

import com.nash.core.model.AnonymizationModeEnum
import com.nash.core.model.AnonymizationModeHolder
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

class ObserveAnonymizationModeUseCase @Inject constructor(
    private val holder: AnonymizationModeHolder
) {
    operator fun invoke(): StateFlow<AnonymizationModeEnum> = holder.mode
}

class SetAnonymizationModeUseCase @Inject constructor(
    private val holder: AnonymizationModeHolder
) {
    operator fun invoke(mode: AnonymizationModeEnum) = holder.set(mode)
}
