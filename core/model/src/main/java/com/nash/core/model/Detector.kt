package com.nash.core.model

/**
 * Generic contract for on-device object detection.
 *
 * Implementations live in core/ml.
 *
 * @param F The frame type, kept generic to ensure core/model remains a leaf module
 * (e.g., ImageProxy in CameraX implementations).
 */
interface Detector<in F> {
    /**
     * Processes a single frame and returns a list of detected objects.
     *
     * @param frame The raw frame data.
     * @param metadata Timing and geometry information for the frame.
     * @return List of detections with normalized coordinates.
     */
    suspend fun detect(frame: F, metadata: FrameMetadata): List<DetectionBox>

    /**
     * Releases any native or ML resources (e.g., TFLite interpreter, GPU delegates).
     */
    fun close()
}
