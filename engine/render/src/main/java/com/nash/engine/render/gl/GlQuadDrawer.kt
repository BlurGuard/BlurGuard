package com.nash.engine.render.gl

import android.opengl.GLES20
import android.opengl.Matrix
import com.nash.core.model.BoundingBox
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared vertex buffers and quad drawing used by all renderers. GL-thread only.
 */
internal class GlQuadDrawer {

    /** Full-screen triangle strip: interleaved x, y, u, v. */
    val fullQuad: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    )

    /** Scratch quad reused for per-box draws. */
    val boxQuad: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun drawQuad(
        program: GlProgram,
        matrix: FloatArray,
        vertices: FloatBuffer,
        bindTexture: () -> Unit,
        extraUniforms: (GlProgram) -> Unit = {},
    ) {
        GLES20.glUseProgram(program.id)
        bindTexture()
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, matrix, 0)
        extraUniforms(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(program.aPosition)
        GLES20.glVertexAttribPointer(program.aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(program.aTexCoord)
        GLES20.glVertexAttribPointer(program.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(program.aPosition)
        GLES20.glDisableVertexAttribArray(program.aTexCoord)
    }

    /** Positions + texcoords for a box, consistent with the full-screen quad mapping. */
    fun fillBoxQuad(b: BoundingBox) {
        val x0 = b.left * 2f - 1f
        val x1 = b.right * 2f - 1f
        val yBot = 1f - b.bottom * 2f
        val yTop = 1f - b.top * 2f
        val vBot = 1f - b.bottom
        val vTop = 1f - b.top
        boxQuad.clear()
        boxQuad.put(
            floatArrayOf(
                x0, yBot, b.left, vBot,
                x1, yBot, b.right, vBot,
                x0, yTop, b.left, vTop,
                x1, yTop, b.right, vTop,
            )
        )
        boxQuad.position(0)
    }

    companion object {
        val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .also { it.position(0) }
    }
}

/** BoundingBox (normalized, top-left origin) -> GL pixel rect (bottom-left origin). */
internal fun toPixelRect(b: BoundingBox, widthPx: Int, heightPx: Int): IntArray {
    val x = (b.left * widthPx).toInt()
    val y = ((1f - b.bottom) * heightPx).toInt()
    val w = ((b.right - b.left) * widthPx).toInt()
    val h = ((b.bottom - b.top) * heightPx).toInt()
    return intArrayOf(x, y, w, h)
}

internal fun scissorClear(x: Int, y: Int, w: Int, h: Int) {
    GLES20.glScissor(x, y, w, h)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
}