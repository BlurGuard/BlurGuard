package com.nash.engine.impl.mapper

import com.nash.core.model.AnonymizationModeEnum
import com.nash.engine.api.AnonymizationMode
import javax.inject.Inject

class AnonymizationModeMapper @Inject constructor() {
    fun toCore(mode: AnonymizationMode): AnonymizationModeEnum = when (mode) {
        AnonymizationMode.BOUNDING -> AnonymizationModeEnum.BOUNDING
        AnonymizationMode.BLUR -> AnonymizationModeEnum.BLUR
        AnonymizationMode.PIXELATE -> AnonymizationModeEnum.PIXELATE
        AnonymizationMode.BLACK_BOX -> AnonymizationModeEnum.BLACKBOX
    }
}