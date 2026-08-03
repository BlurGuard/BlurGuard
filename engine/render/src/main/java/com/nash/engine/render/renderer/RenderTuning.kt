package com.nash.engine.render.renderer

/** Tuning constants shared by the anonymization renderers. */
internal object RenderTuning {

    /** Blur source resolution divisor: higher = stronger, cheaper blur. */
    const val BLUR_DOWNSCALE = 16

    /** Pixelation blocks across the frame width: lower = chunkier. */
    const val PIXELATE_BLOCKS_X = 64f

    /** Safety margin around tracked boxes. */
    const val BOX_DILATION = 0.25f

    /** Outline (BOUNDING debug mode) color. */
    const val OUTLINE_R = 0f
    const val OUTLINE_G = 1f
    const val OUTLINE_B = 0f
    const val OUTLINE_A = 1f
}