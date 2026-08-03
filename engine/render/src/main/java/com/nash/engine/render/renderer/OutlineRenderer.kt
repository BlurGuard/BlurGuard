package com.nash.engine.render.renderer

import android.opengl.GLES20
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.scissorClear
import com.nash.engine.render.gl.toPixelRect
import kotlin.math.max

/** BOUNDING mode: green outline only (debug/demo). */
internal class OutlineRenderer : AnonymizationModeRenderer {

    override fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput) {
        val stroke = max(2, minOf(output.widthPx, output.heightPx) / 200)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 1f, 0f, 1f)
        boxes.forEach { tracked ->
            val r = toPixelRect(
                tracked.box.dilated(RenderTuning.BOX_DILATION)
                    .rotatedFromUpright(frame.rotationDegrees),
                output.widthPx, output.heightPx,
            )
            if (r[2] <= 0 || r[3] <= 0) return@forEach
            // bottom, top, left, right strips
            scissorClear(r[0], r[1], r[2], stroke)
            scissorClear(r[0], r[1] + r[3] - stroke, r[2], stroke)
            scissorClear(r[0], r[1], stroke, r[3])
            scissorClear(r[0] + r[2] - stroke, r[1], stroke, r[3])
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}