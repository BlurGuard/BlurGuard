package com.nash.engine.impl.pipeline

import com.nash.core.model.FrameMetadata
import com.nash.core.model.KeepVisibleState
import com.nash.core.model.TrackedBox
import com.nash.engine.impl.keepvisible.KeepVisibleRecognizer

/**
 * Bridges recognition state to the render path.
 *
 * [KeepVisibleState.decorate] is THE single gate that can set
 * `keepVisible = true` on a box, i.e. the only thing that can leave a face
 * unblurred. Both frame paths must go through it, which is why this stage
 * exposes two explicit entry points rather than one convenience method:
 * recognition is expensive and belongs on detection frames only, while
 * decoration is cheap and mandatory on EVERY frame.
 */
internal class KeepVisibleStage<F>(
    private val recognizer: KeepVisibleRecognizer<F>,
    private val state: KeepVisibleState,
) : PipelineStage {

    /** Detection path: advance recognition, then decorate. Order matters. */
    suspend fun onDetectionFrame(
        frame: F,
        metadata: FrameMetadata,
        boxes: List<TrackedBox>,
    ): List<TrackedBox> {
        recognizer.onDetectionFrame(frame, metadata, boxes)
        return state.decorate(boxes)
    }

    /** Predict path: decorate only. Must never trigger recognition. */
    fun decorate(boxes: List<TrackedBox>): List<TrackedBox> = state.decorate(boxes)

    override fun reset() {
        recognizer.onSessionReset()
    }
}