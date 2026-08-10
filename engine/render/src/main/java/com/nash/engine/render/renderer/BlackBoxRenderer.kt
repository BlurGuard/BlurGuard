package com.nash.engine.render.renderer

import android.opengl.GLES20
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.PixelRect
import com.nash.engine.render.gl.toPixelRect

/**
 * BLACKBOX mode: scissor-clear each tracked box to solid black.
 * Also, the centralized fail-closed fallback — other renderers delegate here
 * when their resources are unavailable, so raw pixels are never shown.
 */
internal class BlackBoxRenderer : AnonymizationModeRenderer {

    private val rect = PixelRect() // GL-thread-confined scratch

    override fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        boxes.forEach { tracked ->
            val r = toPixelRect(tracked.toRenderBox(frame.rotationDegrees), output.widthPx, output.heightPx, rect)
            if (r.w > 0 && r.h > 0) {
                GLES20.glScissor(r.x, r.y, r.w, r.h)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}
