package com.nash.engine.render.renderer

import com.nash.core.model.TrackedBox

/**
 * Renders one anonymization mode over the already-drawn camera frame.
 * Implementations run on the GL thread only.
 *
 * Adding a new mode = new implementation + one line in [ModeRendererFactory];
 * the coordinator is untouched (Open/Closed).
 */
internal interface AnonymizationModeRenderer {

    /**
     * Optional shared per-frame work executed once before per-output rendering
     * (e.g. producing the blurred frame copy). Implementations may make the
     * pbuffer context current; the coordinator re-binds each output afterwards.
     */
    fun prepare(boxes: List<TrackedBox>, frame: RenderFrame) {}

    /** Draw anonymization for [boxes] onto the currently bound output surface. */
    fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput)
}

/** Camera-input state for the current frame. Mutable and reused: no hot-loop allocations. */
internal class RenderFrame {
    var texMatrix: FloatArray = FloatArray(16)
        private set
    var inputWidth = 0
        private set
    var inputHeight = 0
        private set
    var rotationDegrees = 0
        private set

    fun update(texMatrix: FloatArray, inputWidth: Int, inputHeight: Int, rotationDegrees: Int) {
        this.texMatrix = texMatrix
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        this.rotationDegrees = rotationDegrees
    }
}

/** Output-surface state for the current draw. Mutable and reused: no hot-loop allocations. */
internal class RenderOutput {
    var widthPx = 0
        private set
    var heightPx = 0
        private set
    var cameraMatrix: FloatArray = FloatArray(16)
        private set

    fun update(widthPx: Int, heightPx: Int, cameraMatrix: FloatArray) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.cameraMatrix = cameraMatrix
    }
}
