package com.nash.engine.render.gl

import android.opengl.GLES20

/** Attribute/uniform handles for a linked GL program. Create with EGL context current. */
internal class GlProgram(val id: Int) {
    val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
    val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
    val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
    val uGrid = GLES20.glGetUniformLocation(id, "uGrid")
    val uTexOffset = GLES20.glGetUniformLocation(id, "uTexOffset")
}

/** All shader programs used by the anonymization renderers. */
internal class ShaderPrograms(
    val camera: GlProgram,
    val pixelate: GlProgram,
    val blur: GlProgram,
    val overlay: GlProgram,
)

/**
 * Compiles and links the anonymization shaders. Must be used on the GL thread
 * with the EGL context current.
 */
internal class ShaderProgramFactory {

    fun createPrograms(): ShaderPrograms = ShaderPrograms(
        camera = GlProgram(linkProgram(VERTEX_SHADER, CAMERA_FRAGMENT)),
        pixelate = GlProgram(linkProgram(VERTEX_SHADER, PIXELATE_FRAGMENT)),
        blur = GlProgram(linkProgram(VERTEX_SHADER, BLUR_FRAGMENT)),
        overlay = GlProgram(linkProgram(VERTEX_SHADER, OVERLAY_FRAGMENT)),
    )

    private fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private companion object {
        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            varying vec2 vRawCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                vRawCoord = aTexCoord.xy;
            }
        """.trimIndent()

        val CAMERA_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        val PIXELATE_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vRawCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec2 uGrid;
            void main() {
                vec2 snapped = (floor(vRawCoord * uGrid) + 0.5) / uGrid;
                vec2 uv = (uTexMatrix * vec4(snapped, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(sTexture, uv);
            }
        """.trimIndent()

        val BLUR_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform vec2 uTexOffset;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord) * 0.227027;
                c += (texture2D(sTexture, vTexCoord + uTexOffset)
                    + texture2D(sTexture, vTexCoord - uTexOffset)) * 0.316216;
                c += (texture2D(sTexture, vTexCoord + 2.0 * uTexOffset)
                    + texture2D(sTexture, vTexCoord - 2.0 * uTexOffset)) * 0.070270;
                gl_FragColor = c;
            }
        """.trimIndent()

        val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
    }
}