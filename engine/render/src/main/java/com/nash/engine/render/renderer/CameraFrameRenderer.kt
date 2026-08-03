package com.nash.engine.render.renderer

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.nash.engine.render.gl.GlQuadDrawer
import com.nash.engine.render.gl.ShaderPrograms

/**
 * Owns the external OES camera texture and draws the camera frame as a
 * full-screen quad. Construct on the GL thread with EGL context current.
 */
internal class CameraFrameRenderer(
    private val programs: ShaderPrograms,
    private val quadDrawer: GlQuadDrawer,
) {

    /** OES texture the camera SurfaceTexture feeds into. */
    val textureId: Int = createInputTexture()

    fun bindCameraTexture() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    /** Draws the camera frame full-screen using [matrix] as the texture transform. */
    fun render(matrix: FloatArray) {
        quadDrawer.drawQuad(programs.camera, matrix, quadDrawer.fullQuad, ::bindCameraTexture)
    }

    /** GL thread, context current. Deletes the OES texture this renderer owns. */
    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun createInputTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }
}