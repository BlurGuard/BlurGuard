package com.nash.engine.impl.pipeline

import com.nash.core.model.RenderBoxFeed
import com.nash.core.model.TrackedBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fans the final boxes out to both consumers: the GPU renderer (privacy) and
 * the Compose overlay (cosmetic).
 *
 * [trackedBoxes] is conflated latest-wins state — collectors always see the
 * newest result, never a backlog.
 */
internal class TrackedBoxPublisher(
    private val renderBoxFeed: RenderBoxFeed,
) : PipelineStage {

    private val _trackedBoxes = MutableStateFlow<List<TrackedBox>>(emptyList())
    val trackedBoxes: StateFlow<List<TrackedBox>> = _trackedBoxes.asStateFlow()

    /**
     * Renderer first, overlay second — deliberate ordering.
     *
     * The renderer is the privacy-critical consumer (it applies the blur); the
     * overlay only draws debug rectangles. If anything ever slows this method
     * down, the blur must be the side that already landed.
     */
    fun publish(boxes: List<TrackedBox>, rotationDegrees: Int) {
        renderBoxFeed.publish(boxes, rotationDegrees)
        _trackedBoxes.value = boxes
    }

    override fun reset() {
        _trackedBoxes.value = emptyList()
        renderBoxFeed.clear()
    }
}
