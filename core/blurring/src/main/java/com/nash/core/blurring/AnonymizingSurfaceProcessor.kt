package com.nash.core.blurring

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.nash.core.model.RenderBoxFeed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * GPU anonymization renderer — step 1: BLACKBOX mode.
 *
 * Receives raw camera frames as an external texture, redraws them to every
 * output surface (Preview + VideoCapture), and scissor-clears each tracked
 * box to solid black. The raw frame never leaves the GPU.
 */
class AnonymizingSurfaceProcessor(
    private val renderBoxFeed: RenderBoxFeed,
) : SurfaceProcessor {

    private val glThread = HandlerThread("BlurGuardRender").apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** All processor callbacks and GL work run on this single-threaded executor. */
    val glExecutor: Executor = Executor { glHandler.post(it) }

    // --- EGL/GL state: touched ONLY from the GL thread. ---
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var cameraTexId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    /** Extra margin around each box — covers detection latency + box jitter. */
    private val dilation = 0.25f

    // Full-screen quad: x, y | u, v (triangle strip).
    private val quad: FloatBuffer = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    ).toBuffer()

    override fun onInputSurface(request: SurfaceRequest) {
        initEglIfNeeded()
        cameraTexId = createExternalTexture()
        val texture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(texture)
        cameraTexture = texture
        inputSurface = surface
        request.provideSurface(surface, glExecutor) {
            surface.release()
            texture.release()
        }
        // Render is driven by camera frames: one render pass per frame.
        texture.setOnFrameAvailableListener({ st ->
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            renderToOutputs(st.timestamp)
        }, glHandler)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        initEglIfNeeded()
        val surface = surfaceOutput.getSurface(glExecutor) {
            outputs.remove(surfaceOutput)?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            surfaceOutput.close()
        }
        outputs[surfaceOutput] = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
    }

    private fun renderToOutputs(timestampNs: Long) {
        val snapshot = renderBoxFeed.latest()
        Log.d("AnonProcessor", "boxes=${snapshot.boxes.size} rot=${snapshot.rotationDegrees}")
        outputs.forEach { (output, eglSurface) ->
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            val w = output.size.width
            val h = output.size.height
            GLES20.glViewport(0, 0, w, h)

            // 1. Draw the camera frame (CameraX adjusts the matrix per output).
            output.updateTransformMatrix(outputMatrix, texMatrix)
            drawCameraQuad(outputMatrix)

            // 2. Black out every tracked box (upright coords -> buffer space).
            drawBlackBoxes(w, h, snapshot)

            // 3. Required for VideoCapture: encoder needs valid timestamps.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun drawCameraQuad(matrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, matrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(positionLoc)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawBlackBoxes(
        widthPx: Int,
        heightPx: Int,
        snapshot: RenderBoxFeed.Snapshot,
    ) {
        if (snapshot.boxes.isEmpty()) return
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        snapshot.boxes.forEach { tracked ->
            if (tracked.keepVisible) return@forEach
            val b = tracked.box.dilated(dilation).rotatedFromUpright(snapshot.rotationDegrees)
            val x = (b.left * widthPx).toInt()
            val y = ((1f - b.bottom) * heightPx).toInt() // GL origin is bottom-left
            val bw = ((b.right - b.left) * widthPx).toInt()
            val bh = ((b.bottom - b.top) * heightPx).toInt()
            if (bw > 0 && bh > 0) {
                GLES20.glScissor(x, y, bw, bh)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    // ---------------------------------------------------------------- EGL/GL setup

    private fun initEglIfNeeded() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, num, 0)
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        // A 1x1 pbuffer so we can compile shaders before the first window surface.
        val pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        program = buildProgram()
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return ids[0]
    }

    private companion object {
        fun FloatArray.toBuffer(): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(this).apply { position(0) }
    }
}