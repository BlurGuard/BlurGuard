package com.nash.engine.render.renderer

import com.nash.engine.render.gl.EglContextManager
import com.nash.engine.render.gl.GlQuadDrawer
import com.nash.engine.render.gl.ShaderProgramFactory
import com.nash.engine.render.gl.ShaderPrograms

/**
 * Aggregates the GL-thread render collaborators. Must be constructed on the
 * GL thread with the EGL context current (after [com.nash.engine.render.gl.EglContextManager.initIfNeeded]).
 */
internal class RenderPipeline(egl: EglContextManager) {

    private val programs: ShaderPrograms = ShaderProgramFactory().createPrograms()
    private val quadDrawer = GlQuadDrawer()

    val cameraFrameRenderer = CameraFrameRenderer(programs, quadDrawer)

    private val blackBoxRenderer = BlackBoxRenderer()
    private val blurRenderer =
        BlurRenderer(egl, programs, quadDrawer, cameraFrameRenderer, blackBoxRenderer)

    val rendererFactory = ModeRendererFactory(
        blackBox = blackBoxRenderer,
        outline = OutlineRenderer(),
        pixelate = PixelateRenderer(programs, quadDrawer, cameraFrameRenderer),
        blur = blurRenderer,
    )

    val cameraTextureId: Int
        get() = cameraFrameRenderer.textureId

    /** Called when the camera input surface changes. */
    fun onInputChanged() = blurRenderer.invalidate()

    /** GL thread, context current. */
    fun release() = blurRenderer.release()
}