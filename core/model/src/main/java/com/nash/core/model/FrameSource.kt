package com.nash.core.model

/**
 * Consumer of analysis frames produced by a [FrameSource].
 *
 * Invoked synchronously on the source's analysis thread. The frame is only
 * valid for the duration of the [onFrame] call: the consumer must not retain,
 * close, or release the frame — the source owns the frame's lifecycle and
 * reclaims it after this method returns. While the consumer is working, the
 * source drops newer frames (latest-wins backpressure), never queues them.
 *
 * @param F The frame type, kept generic so core/model remains framework-free
 * (e.g. ImageProxy in the CameraX implementation, a fake frame in tests).
 */
fun interface FrameConsumer<in F> {
    suspend fun onFrame(frame: F, metadata: FrameMetadata)
}

/**
 * Source of downsampled camera frames for the detection pipeline.
 *
 * Implemented in core/camera (CameraX ImageAnalysis); consumed by the
 * frame-pipeline orchestrator in core/domain. This is the analysis branch
 * only — it is independent of the preview and video-capture surfaces.
 */
interface FrameSource<out F> {
    /**
     * Registers the single active frame consumer, replacing any previous one.
     * Pass null to stop receiving frames.
     */
    fun setFrameConsumer(consumer: FrameConsumer<@UnsafeVariance F>?)
}
