package com.nash.engine.api.keepvisible

import com.nash.core.model.FrameMetadata
import com.nash.core.model.TrackedBox

/**
 * The recognition half of keep-visible, as seen by the pipeline.
 *
 * Exists so the pipeline can be unit-tested without a TFLite recognizer,
 * trusted-person store or config. Implemented by KeepVisibleOrchestrator in
 * engine/recognition — which is why this contract lives in engine/api rather
 * than being internal to engine/impl.
 */
interface KeepVisibleRecognizer<in F> {

    /**
     * Advances recognition for this detection frame: drains revoke requests,
     * retains live tracks, and performs at most one enroll/verify/re-verify.
     *
     * Runs on the ml dispatcher, serialized with detection. Only ever called on
     * detection frames — never on predict frames, where it would cost fps for
     * no benefit.
     */
    suspend fun onDetectionFrame(frame: F, metadata: FrameMetadata, boxes: List<TrackedBox>)

    /** Clears per-session recognition state. */
    fun onSessionReset()
}