package com.nash.engine.render.renderer

import android.opengl.GLES20
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.GlQuadDrawer
import com.nash.engine.render.gl.ShaderPrograms
import kotlin.math.max

/** PIXELATE mode: re-sample the camera texture with grid-snapped coordinates. */
internal class PixelateRenderer(
    private val programs: ShaderPrograms,
    private val quadDrawer: GlQuadDrawer,
    private val cameraFrameRenderer: CameraFrameRenderer,
) : AnonymizationModeRenderer {

    override fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput) {
        val p = programs.pixelate
        val gridY = RenderTuning.PIXELATE_BLOCKS_X * frame.inputHeight.toFloat() / max(1, frame.inputWidth)
        boxes.forEach { tracked ->
            quadDrawer.fillBoxQuad(tracked.toRenderBox(frame.rotationDegrees))
            quadDrawer.drawQuad(
                p, output.cameraMatrix, quadDrawer.boxQuad, cameraFrameRenderer::bindCameraTexture
            ) {
                GLES20.glUniform2f(it.uGrid, RenderTuning.PIXELATE_BLOCKS_X, gridY)
            }
        }
    }
}
