package com.nash.core.model

/**
 * Hardware acceleration preference for a [Detector] implementation.
 *
 * Framework-free on purpose: concrete mapping to a runtime delegate
 * (e.g. MediaPipe/TFLite CPU or GPU delegate) happens inside core/ml.
 */
enum class DetectorDelegate {
    /**
     * CPU inference (XNNPACK). Default: keeps the GPU fully dedicated to the
     * blur/render path (NFR-02). BlazeFace-class models run in a few ms on CPU.
     */
    CPU,

    /**
     * GPU inference. Only use if benchmarks show CPU is too slow AND the
     * measured contention with the core/blurring renderer is acceptable.
     */
    GPU,


    /** NNAPI: lets the vendor driver place ops on NPU/DSP. Deprecated API, best-effort. */
    NPU
}

/**
 * Configuration for a [Detector] implementation.
 *
 * Bound in the app DI module so the delegate choice stays a config decision,
 * not a code edit — and can later be driven by settings or benchmarks.
 *
 * @property delegate Preferred accelerator. Implementations must fall back to
 * [DetectorDelegate.CPU] if the preferred delegate is unavailable on-device.
 * @property minConfidence Detections below this score are discarded by the detector.
 */
enum class FaceModelRange {
    /** ~2 m, 128px input — fastest; selfie-style scenes. */
    SHORT_RANGE,

    /** ~5 m, 192px input — better for street/scene recording; slightly heavier. */
    FULL_RANGE
}
enum class DetectorBackend {
    /** Fine-tuned YOLO (faces + plates, one model, long-range). */
    YOLO,

    /** MediaPipe BlazeFace short-range (faces only). Kept as fallback. */
    MEDIAPIPE
}

data class DetectorConfig(
    val backend: DetectorBackend = DetectorBackend.YOLO,
    val delegate: DetectorDelegate = DetectorDelegate.NPU,
    val minConfidence: Float = 0.1f,
    val faceModelRange: FaceModelRange = FaceModelRange.FULL_RANGE
)
