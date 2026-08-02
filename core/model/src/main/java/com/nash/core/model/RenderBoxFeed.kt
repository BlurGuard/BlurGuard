package com.nash.core.model

import java.util.concurrent.atomic.AtomicReference

/**
 * Latest-value handoff from the detection/tracking pipeline to the GPU
 * renderer. Deliberately NOT a StateFlow: the render thread polls the newest
 * snapshot once per frame — latest-wins, no backlog, no suspension.
 */
class RenderBoxFeed {

    data class Snapshot(
        /** Boxes normalized to the upright analysis frame. */
        val boxes: List<TrackedBox> = emptyList(),
        /** Clockwise rotation that makes the camera buffer upright (0/90/180/270). */
        val rotationDegrees: Int = 0,
        val publishedAtNanos: Long = 0L,
    )

    private val latest = AtomicReference(Snapshot())

    /** Called by core/domain after every tracker update/predict. */
    fun publish(boxes: List<TrackedBox>,rotationDegrees: Int) {
        latest.set(Snapshot(boxes, rotationDegrees,System.nanoTime()))
    }

    /** Called by the renderer once per render frame. Never blocks. */
    fun latest(): Snapshot = latest.get()

    fun clear() {
        latest.set(Snapshot())
    }
}
