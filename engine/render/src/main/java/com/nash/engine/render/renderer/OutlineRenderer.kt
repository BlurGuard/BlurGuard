package com.nash.engine.render.renderer

import android.opengl.GLES20
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.PixelRect
import com.nash.engine.render.gl.scissorClear
import com.nash.engine.render.gl.toPixelRect
import kotlin.math.max

/** BOUNDING mode: outline only (debug/demo). */
internal class OutlineRenderer : AnonymizationModeRenderer {

    private val rect = PixelRect() // GL-thread-confined scratch

    override fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput) {
        val stroke = max(2, minOf(output.widthPx, output.heightPx) / 200)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(
            RenderTuning.OUTLINE_R,
            RenderTuning.OUTLINE_G,
            RenderTuning.OUTLINE_B,
            RenderTuning.OUTLINE_A,
        )
        boxes.forEach { tracked ->
            val r = toPixelRect(tracked.toRenderBox(frame.rotationDegrees), output.widthPx, output.heightPx, rect)
            if (r.w <= 0 || r.h <= 0) return@forEach
            // bottom, top, left, right strips
            scissorClear(r.x, r.y, r.w, stroke)
            scissorClear(r.x, r.y + r.h - stroke, r.w, stroke)
            scissorClear(r.x, r.y, stroke, r.h)
            scissorClear(r.x + r.w - stroke, r.y, stroke, r.h)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}
