package com.nash.engine.render.renderer

import com.nash.core.model.AnonymizationModeEnum

/** Maps an anonymization mode to its renderer. Exhaustive: a new enum value fails compilation here, and only here. */
internal class ModeRendererFactory(
    private val blackBox: BlackBoxRenderer,
    private val outline: OutlineRenderer,
    private val pixelate: PixelateRenderer,
    private val blur: BlurRenderer,
) {
    fun forMode(mode: AnonymizationModeEnum): AnonymizationModeRenderer = when (mode) {
        AnonymizationModeEnum.BLACKBOX -> blackBox
        AnonymizationModeEnum.BOUNDING -> outline
        AnonymizationModeEnum.PIXELATE -> pixelate
        AnonymizationModeEnum.BLUR -> blur
    }
}
