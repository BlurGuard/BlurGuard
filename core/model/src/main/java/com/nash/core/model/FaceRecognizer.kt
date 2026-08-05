package com.nash.core.model

/**
 * Contract for on-device face embedding extraction.
 *
 * Implementations live in engine/ml, which owns every TFLite/LiteRT and MediaPipe model
 * wrapper (`MobileFaceNetRecognizer`). Alignment (landmarks, 112x112 warp) and quality
 * gates are implementation details behind this interface — see the FaceAligner /
 * SimilarityTransform seam in that module.
 *
 * Module ownership: engine/ml runs the models (where a face is, what its embedding is);
 * engine/recognition decides identity and trust (who it is, whether the box may be
 * unblurred, how often to re-check) and reaches models only through this interface and
 * [TrustedPersonStore]. engine/impl injects the concrete implementation.
 *
 * @param F The frame type, kept generic so core/model stays a leaf module
 * (e.g. ImageProxy in CameraX implementations), mirroring [Detector].
 */
interface FaceRecognizer<in F> {

    /**
     * Extracts an embedding for one face in the frame.
     *
     * Must be called on the ml dispatcher (single-threaded, serialized with
     * detection). Runs sporadically — enrollment, new-track verification,
     * periodic re-verification — never per frame.
     *
     * @param frame The raw analysis frame (same instance the detector saw).
     * @param faceBox Normalized face box in upright space (from a [TrackedBox]).
     * @param metadata Frame geometry/rotation info.
     * @return The embedding, or null when any quality gate fails (crop too
     * small, landmarks not found, low landmark confidence, degenerate vector).
     * Null means "no decision this frame" — callers must NOT treat it as a
     * mismatch, and must NOT unblur on it (fail-closed).
     */
    suspend fun embed(frame: F, faceBox: BoundingBox, metadata: FrameMetadata): FaceEmbedding?

    /** Releases ML resources (interpreter, delegates). */
    fun close()
}