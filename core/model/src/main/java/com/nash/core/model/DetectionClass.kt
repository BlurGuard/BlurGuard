package com.nash.core.model

/**
 * Supported object classes for detection and anonymization.
 *
 * Implemented by detectors in core/ml.
 */
enum class DetectionClass {
    /**
     * Human faces.
     */
    FACE,

    /**
     * Vehicle license plates.
     */
    LICENSE_PLATE
}
