package com.nash.core.model

/**
 * Hardware acceleration preference for a [Detector] implementation.
 *
 * Framework-free on purpose: concrete mapping to a runtime delegate
 * (e.g. MediaPipe/TFLite CPU or NNAPI delegate) happens inside engine/ml.
 */
enum class DetectorDelegate {
    /**
     * CPU inference (XNNPACK). Always-available fallback: engine/ml drops to CPU
     * automatically when the preferred delegate cannot be created on-device.
     */
    CPU,

    /**
     * GPU inference. Not wired for detection: the GPU stays dedicated to the
     * engine/render anonymization path (NFR-02).
     */
    GPU,

    /** NNAPI: lets the vendor driver place ops on NPU/DSP. Deprecated API, best-effort. */
    NPU
}

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

/**
 * Configuration for a [Detector] implementation.
 *
 * Accelerator policy: NNAPI/NPU first, automatic CPU/XNNPACK fallback, GPU reserved for
 * anonymization rendering — owned by `TfliteInterpreterFactory` in engine/ml (single source of truth).
 *
 * Bound in the engine DI module so the delegate choice stays a config decision,
 * not a code edit — and can later be driven by settings or benchmarks.
 *
 * @property backend Which detection model family to run.
 * @property delegate Preferred accelerator. Implementations must fall back to
 * [DetectorDelegate.CPU] if the preferred delegate is unavailable on-device.
 * @property minConfidence Detections below this score are discarded by the detector.
 * @property faceModelRange Input-range variant used by the MediaPipe face backend.
 */
data class DetectorConfig(
    val backend: DetectorBackend = DetectorBackend.YOLO,
    val delegate: DetectorDelegate = DetectorDelegate.NPU,
    val minConfidence: Float = 0.1f,
    val faceModelRange: FaceModelRange = FaceModelRange.FULL_RANGE
)
