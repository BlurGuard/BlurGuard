package com.nash.engine.api

/**
 * Supported anonymization strategies for detected objects.
 */
enum class AnonymizationMode {
    BOUNDING,
    BLUR,
    PIXELATE,
    BLACK_BOX
}
