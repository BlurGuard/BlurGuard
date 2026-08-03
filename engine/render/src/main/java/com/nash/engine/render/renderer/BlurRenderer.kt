package com.nash.engine.render.renderer

import android.opengl.GLES20
import android.opengl.Matrix
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.EglContextManager
import com.nash.engine.render.gl.GlQuadDrawer
import com.nash.engine.render.gl.ShaderPrograms
import kotlin.math.max

/**
 * BLUR mode: sample a downscaled, two-pass Gaussian-blurred copy of the frame.
 * Owns the offscreen ping-pong FBO targets.
 *
 * Fail-closed: if the blurred copy is not ready, [render] delegates to
 * [failClosedFallback] so boxes are drawn black instead of being skipped.
 */
internal class BlurRenderer(
    private val egl: EglContextManager,
    private val programs: ShaderPrograms,
    private val quadDrawer: GlQuadDrawer,
    private val cameraFrameRenderer: CameraFrameRenderer,
    private val failClosedFallback: BlackBoxRenderer,
) : AnonymizationModeRenderer {

    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboWidth = 0
    private var fboHeight = 0
    private var blurTextureReady = false

    private val texMatrixInv = FloatArray(16)
    private val overlayMatrix = FloatArray(16)

    /** Invalidate the blurred copy, e.g. when the camera input surface changes. */
    fun invalidate() {
        blurTextureReady = false
    }

    /** Downsample the camera frame, then blur horizontally and vertically. Once per frame. */
    override fun prepare(boxes: List<TrackedBox>, frame: RenderFrame) {
        blurTextureReady = false
        if (boxes.isEmpty()) return
        egl.makePbufferCurrent()
        ensureBlurTargets(frame.inputWidth, frame.inputHeight)
        if (fboWidth == 0) return // fail closed: render() falls back to black boxes
        GLES20.glViewport(0, 0, fboWidth, fboHeight)

        // Pass 1: camera -> fbo0 (downsample, camera transform applied)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        quadDrawer.drawQuad(
            programs.camera, frame.texMatrix, quadDrawer.fullQuad,
            cameraFrameRenderer::bindCameraTexture,
        )

        // Pass 2: fbo0 -> fbo1 (horizontal)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
        quadDrawer.drawQuad(programs.blur, GlQuadDrawer.IDENTITY, quadDrawer.fullQuad, {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
        }) { GLES20.glUniform2f(it.uTexOffset, 1f / fboWidth, 0f) }

        // Pass 3: fbo1 -> fbo0 (vertical)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        quadDrawer.drawQuad(programs.blur, GlQuadDrawer.IDENTITY, quadDrawer.fullQuad, {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[1])
        }) { GLES20.glUniform2f(it.uTexOffset, 0f, 1f / fboHeight) }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // Blur texture is in pre-output space: needed to strip the camera
        // matrix back out of the per-output transform during render().
        Matrix.invertM(texMatrixInv, 0, frame.texMatrix, 0)
        blurTextureReady = true
    }

    override fun render(boxes: List<TrackedBox>, frame: RenderFrame, output: RenderOutput) {
        if (!blurTextureReady) {
            // Fail closed: never show raw pixels.
            failClosedFallback.render(boxes, frame, output)
            return
        }
        Matrix.multiplyMM(overlayMatrix, 0, texMatrixInv, 0, output.cameraMatrix, 0)
        val p = programs.overlay
        boxes.forEach { tracked ->
            quadDrawer.fillBoxQuad(
                tracked.box.dilated(RenderTuning.BOX_DILATION).rotatedFromUpright(frame.rotationDegrees)
            )
            quadDrawer.drawQuad(p, overlayMatrix, quadDrawer.boxQuad, ::bindBlurTexture)
        }
    }

    /** GL thread, context current. */
    fun release() {
        releaseBlurTargets()
    }

    private fun bindBlurTexture() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
    }

    private fun ensureBlurTargets(inputWidth: Int, inputHeight: Int) {
        val w = max(1, inputWidth / RenderTuning.BLUR_DOWNSCALE)
        val h = max(1, inputHeight / RenderTuning.BLUR_DOWNSCALE)
        if (w == fboWidth && h == fboHeight && fboIds[0] != 0) return
        releaseBlurTargets()

        GLES20.glGenFramebuffers(2, fboIds, 0)
        GLES20.glGenTextures(2, fboTexIds, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexIds[i], 0
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboWidth = w
        fboHeight = h
    }

    private fun releaseBlurTargets() {
        if (fboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0)
            GLES20.glDeleteTextures(2, fboTexIds, 0)
            fboIds.fill(0)
            fboTexIds.fill(0)
        }
        fboWidth = 0
        fboHeight = 0
        blurTextureReady = false
    }
}