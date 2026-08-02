package com.nash.engine.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.nash.core.model.AnonymizationModeEnum
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.BoundingBox
import com.nash.core.model.RenderBoxFeed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * GPU renderer for the anonymization pipeline (FR-04/FR-05).
 *
 * Draws the camera frame to every output (preview + video encoder) and covers
 * tracked regions according to [AnonymizationModeHolder.mode]:
 *  - BLACKBOX: scissor-clear to black
 *  - BOUNDING: outline only (debug/demo)
 *  - PIXELATE: re-sample the camera texture with grid-snapped coordinates
 *  - BLUR: sample a downscaled, two-pass Gaussian-blurred copy of the frame
 *
 * Fail-closed: if BLUR is selected before the offscreen targets exist,
 * boxes are drawn as black instead of being skipped.
 */
class AnonymizingSurfaceProcessor(
    private val renderBoxFeed: RenderBoxFeed,
    private val modeHolder: AnonymizationModeHolder,
) : SurfaceProcessor {

    private val glThread = HandlerThread("BlurGuardRender").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor: Executor = Executor { glHandler.post(it) }

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Input
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputWidth = 0
    private var inputHeight = 0

    // Outputs
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    // Shader programs
    private class GlProgram(val id: Int) {
        val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
        val uGrid = GLES20.glGetUniformLocation(id, "uGrid")
        val uTexOffset = GLES20.glGetUniformLocation(id, "uTexOffset")
    }

    private var cameraProgram: GlProgram? = null
    private var pixelateProgram: GlProgram? = null
    private var blurProgram: GlProgram? = null
    private var overlayProgram: GlProgram? = null

    // Offscreen targets for the Gaussian blur (ping-pong at 1/BLUR_DOWNSCALE res)
    private val fboIds = IntArray(2)
    private val fboTexIds = IntArray(2)
    private var fboWidth = 0
    private var fboHeight = 0
    private var blurTextureReady = false

    // Matrices
    private val texMatrix = FloatArray(16)
    private val texMatrixInv = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val overlayMatrix = FloatArray(16)

    // Geometry: interleaved x, y, u, v — triangle strip
    private val fullQuad: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    )
    private val boxQuad: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ------------------------------------------------------------------
    // SurfaceProcessor
    // ------------------------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            initEglIfNeeded()
            inputWidth = request.resolution.width
            inputHeight = request.resolution.height
            blurTextureReady = false

            val st = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(inputWidth, inputHeight)
            }
            surfaceTexture = st
            val surface = Surface(st)
            inputSurface = surface

            request.provideSurface(surface, glExecutor) {
                st.setOnFrameAvailableListener(null)
                surface.release()
                st.release()
                if (surfaceTexture === st) {
                    surfaceTexture = null
                    inputSurface = null
                }
            }

            st.setOnFrameAvailableListener({
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(texMatrix)
                    renderToOutputs(st.timestamp)
                } catch (t: Throwable) {
                    Log.e(TAG, "Render failed", t)
                }
            }, glHandler)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glExecutor.execute {
            initEglIfNeeded()
            val surface = surfaceOutput.getSurface(glExecutor) {
                outputs.remove(surfaceOutput)?.let {
                    EGL14.eglDestroySurface(eglDisplay, it)
                }
                surfaceOutput.close()
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            outputs[surfaceOutput] = eglSurface
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun renderToOutputs(timestampNs: Long) {
        if (outputs.isEmpty()) return
        val snapshot = renderBoxFeed.latest()
        val mode = modeHolder.mode.value
        val boxes = snapshot.boxes.filter { !it.keepVisible }

        val kept = snapshot.boxes.count { it.keepVisible }
        if (kept != lastKept) {
            lastKept = kept
            Log.d("Renderer", "keepVisible boxes in feed: $kept")
        }
        // Prepare the blurred copy of the frame once, shared by all outputs.
        if (mode == AnonymizationModeEnum.BLUR && boxes.isNotEmpty()) {
            renderBlurTexture()
            Matrix.invertM(texMatrixInv, 0, texMatrix, 0)
        }

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.w(TAG, "eglMakeCurrent failed for output")
                continue
            }
            val w = output.size.width
            val h = output.size.height
            GLES20.glViewport(0, 0, w, h)

            // 1) Camera frame
            output.updateTransformMatrix(cameraMatrix, texMatrix)
            drawQuad(cameraProgram!!, cameraMatrix, fullQuad, ::bindCameraTexture)

            // 2) Anonymization boxes
            if (boxes.isNotEmpty()) {
                when (mode) {
                    AnonymizationModeEnum.BLACKBOX -> drawBlackBoxes(w, h, boxes, snapshot.rotationDegrees)

                    AnonymizationModeEnum.BOUNDING -> drawOutlines(w, h, boxes, snapshot.rotationDegrees)

                    AnonymizationModeEnum.PIXELATE -> {
                        val p = pixelateProgram!!
                        boxes.forEach { tracked ->
                            fillBoxQuad(tracked.box.dilated(BOX_DILATION).rotatedFromUpright(snapshot.rotationDegrees))
                            drawQuad(p, cameraMatrix, boxQuad, ::bindCameraTexture) {
                                GLES20.glUniform2f(
                                    it.uGrid,
                                    PIXELATE_BLOCKS_X,
                                    PIXELATE_BLOCKS_X * inputHeight.toFloat() / max(1, inputWidth)
                                )
                            }
                        }
                    }

                    AnonymizationModeEnum.BLUR -> {
                        if (blurTextureReady) {
                            // Blur texture is in pre-output space: strip the camera
                            // matrix back out of the per-output transform.
                            Matrix.multiplyMM(overlayMatrix, 0, texMatrixInv, 0, cameraMatrix, 0)
                            val p = overlayProgram!!
                            boxes.forEach { tracked ->
                                fillBoxQuad(tracked.box.dilated(BOX_DILATION).rotatedFromUpright(snapshot.rotationDegrees))
                                drawQuad(p, overlayMatrix, boxQuad) {
                                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
                                }
                            }
                        } else {
                            // Fail closed: never show raw pixels.
                            drawBlackBoxes(w, h, boxes, snapshot.rotationDegrees)
                        }
                    }
                }
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    /** Downsample the camera frame, then blur horizontally and vertically. */
    private fun renderBlurTexture() {
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        ensureBlurTargets()
        if (fboWidth == 0) return
        GLES20.glViewport(0, 0, fboWidth, fboHeight)

        // Pass 1: camera -> fbo0 (downsample, camera transform applied)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        drawQuad(cameraProgram!!, texMatrix, fullQuad, ::bindCameraTexture)

        // Pass 2: fbo0 -> fbo1 (horizontal)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1])
        drawQuad(blurProgram!!, IDENTITY, fullQuad, {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[0])
        }) { GLES20.glUniform2f(it.uTexOffset, 1f / fboWidth, 0f) }

        // Pass 3: fbo1 -> fbo0 (vertical)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        drawQuad(blurProgram!!, IDENTITY, fullQuad, {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[1])
        }) { GLES20.glUniform2f(it.uTexOffset, 0f, 1f / fboHeight) }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        blurTextureReady = true
    }

    private fun ensureBlurTargets() {
        val w = max(1, inputWidth / BLUR_DOWNSCALE)
        val h = max(1, inputHeight / BLUR_DOWNSCALE)
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

    // ------------------------------------------------------------------
    // Draw helpers
    // ------------------------------------------------------------------

    private fun bindCameraTexture() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    private fun drawQuad(
        program: GlProgram,
        matrix: FloatArray,
        vertices: FloatBuffer,
        bindTexture: () -> Unit = ::bindCameraTexture,
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
    private fun fillBoxQuad(b: BoundingBox) {
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

    private fun toPixelRect(b: BoundingBox, widthPx: Int, heightPx: Int): IntArray {
        val x = (b.left * widthPx).toInt()
        val y = ((1f - b.bottom) * heightPx).toInt() // GL origin is bottom-left
        val w = ((b.right - b.left) * widthPx).toInt()
        val h = ((b.bottom - b.top) * heightPx).toInt()
        return intArrayOf(x, y, w, h)
    }

    private fun drawBlackBoxes(widthPx: Int, heightPx: Int, boxes: List<com.nash.core.model.TrackedBox>, rotationDegrees: Int) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        boxes.forEach { tracked ->
            val r = toPixelRect(tracked.box.dilated(BOX_DILATION).rotatedFromUpright(rotationDegrees), widthPx, heightPx)
            if (r[2] > 0 && r[3] > 0) {
                GLES20.glScissor(r[0], r[1], r[2], r[3])
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun drawOutlines(widthPx: Int, heightPx: Int, boxes: List<com.nash.core.model.TrackedBox>, rotationDegrees: Int) {
        val stroke = max(2, minOf(widthPx, heightPx) / 200)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 1f, 0f, 1f)
        boxes.forEach { tracked ->
            val r = toPixelRect(tracked.box.dilated(BOX_DILATION).rotatedFromUpright(rotationDegrees), widthPx, heightPx)
            if (r[2] <= 0 || r[3] <= 0) return@forEach
            // bottom, top, left, right strips
            scissorClear(r[0], r[1], r[2], stroke)
            scissorClear(r[0], r[1] + r[3] - stroke, r[2], stroke)
            scissorClear(r[0], r[1], stroke, r[3])
            scissorClear(r[0] + r[2] - stroke, r[1], stroke, r[3])
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun scissorClear(x: Int, y: Int, w: Int, h: Int) {
        GLES20.glScissor(x, y, w, h)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    // ------------------------------------------------------------------
    // EGL / shader setup
    // ------------------------------------------------------------------

    private fun initEglIfNeeded() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, num, 0) &&
                    num[0] > 0
        ) { "eglChooseConfig failed" }
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

        cameraProgram = GlProgram(linkProgram(VERTEX_SHADER, CAMERA_FRAGMENT))
        pixelateProgram = GlProgram(linkProgram(VERTEX_SHADER, PIXELATE_FRAGMENT))
        blurProgram = GlProgram(linkProgram(VERTEX_SHADER, BLUR_FRAGMENT))
        overlayProgram = GlProgram(linkProgram(VERTEX_SHADER, OVERLAY_FRAGMENT))

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

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

    fun shutdown() {
        glExecutor.execute {
            releaseBlurTargets()
            outputs.values.forEach { EGL14.eglDestroySurface(eglDisplay, it) }
            outputs.clear()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                }
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            glThread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "AnonProcessor"

        /** Blur source resolution divisor: higher = stronger, cheaper blur. */
        const val BLUR_DOWNSCALE = 16

        /** Pixelation blocks across the frame width: lower = chunkier. */
        const val PIXELATE_BLOCKS_X = 64f

        /** Safety margin around tracked boxes. */
        const val BOX_DILATION = 0.25f

        val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var lastKept = -1


        fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .also { it.position(0) }

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
