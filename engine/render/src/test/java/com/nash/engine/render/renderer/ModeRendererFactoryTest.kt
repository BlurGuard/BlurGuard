package com.nash.engine.render.renderer

import com.nash.core.model.AnonymizationModeEnum
import com.nash.engine.render.gl.GlProgram
import com.nash.engine.render.gl.GlQuadDrawer
import com.nash.engine.render.gl.ShaderPrograms
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

class ModeRendererFactoryTest {

    @Test
    fun `each mode routes to its renderer`() {
        val programs = ShaderPrograms(
            camera = GlProgram(0),
            pixelate = GlProgram(0),
            blur = GlProgram(0),
            overlay = GlProgram(0),
        )
        val quadDrawer = GlQuadDrawer()
        val camera = CameraFrameRenderer(programs, quadDrawer)
        val blackBox = BlackBoxRenderer()
        val outline = OutlineRenderer()
        val pixelate = PixelateRenderer(programs, quadDrawer, camera)
        val blur = BlurRenderer(mock(), programs, quadDrawer, camera, blackBox)
        val factory = ModeRendererFactory(blackBox, outline, pixelate, blur)

        assertSame(blackBox, factory.forMode(AnonymizationModeEnum.BLACKBOX))
        assertSame(outline, factory.forMode(AnonymizationModeEnum.BOUNDING))
        assertSame(pixelate, factory.forMode(AnonymizationModeEnum.PIXELATE))
        assertSame(blur, factory.forMode(AnonymizationModeEnum.BLUR))
    }
}