package com.nash.core.model

/**
 * A single raw detection result from a [Detector].
 *
 * @property box The normalized geometry of the detection.
 * @property clazz The type of object detected.
 * @property confidence Detection confidence score, typically between 0.0 and 1.0.
 */
data class DetectionBox(
    val box: BoundingBox,
    val clazz: DetectionClass,
    val confidence: Float
)
