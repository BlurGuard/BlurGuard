package com.nash.engine.api

/**
 * Supported anonymization strategies for detected objects.
 */
enum class AnonymizationMode {
    NONE,
    BLUR,
    PIXELATE,
    BLACK_BOX
}
