package com.nash.engine.render

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.nash.core.model.AnonymizationModeHolder
import com.nash.core.model.RenderBoxFeed
import com.nash.engine.render.gl.EglContextManager
import com.nash.engine.render.gl.SurfaceOutputRegistry
import com.nash.engine.render.renderer.RenderFrame
import com.nash.engine.render.renderer.RenderOutput
import com.nash.engine.render.renderer.RenderPipeline
import java.util.concurrent.Executor

/**
 * Coordinator for the anonymization pipeline (FR-04/FR-05).
 *
 * Draws the camera frame to every output (preview + video encoder) and covers
 * tracked regions according to [AnonymizationModeHolder.mode]. The actual work
 * is delegated to small collaborators:
 *  - [com.nash.engine.render.gl.EglContextManager]: EGL display/context/pbuffer lifecycle
 *  - [com.nash.engine.render.gl.SurfaceOutputRegistry]: output surface <-> EGL surface mapping
 *  - [com.nash.engine.render.gl.ShaderProgramFactory]: shader compilation and linking
 *  - [com.nash.engine.render.renderer.CameraFrameRenderer]: camera texture + full-frame draw
 *  - [com.nash.engine.render.renderer.AnonymizationModeRenderer]s via [com.nash.engine.render.renderer.ModeRendererFactory]: per-mode box rendering
 *
 * Fail-closed: [com.nash.engine.render.renderer.BlurRenderer] falls back to [com.nash.engine.render.renderer.BlackBoxRenderer] whenever the
 * blurred frame copy is unavailable, so raw pixels are never shown.
 *
 * This class only: receives input/output surfaces, reads the latest boxes,
 * chooses the mode renderer, renders each output, and sets the presentation
 * time before swapping buffers.
 */
class AnonymizingSurfaceProcessor(
    private val renderBoxFeed: RenderBoxFeed,
    private val modeHolder: AnonymizationModeHolder,
) : SurfaceProcessor {

    private val glThread = HandlerThread("BlurGuardRender").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor: Executor = Executor { glHandler.post(it) }

    private val egl = EglContextManager()
    private val outputs = SurfaceOutputRegistry(egl)

    /** Created lazily on the GL thread once the EGL context exists. */
    private var pipeline: RenderPipeline? = null

    // Input
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputWidth = 0
    private var inputHeight = 0

    // Per-frame scratch, reused: no allocation in the hot loop.
    private val texMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val renderFrame = RenderFrame()
    private val renderOutput = RenderOutput()
    private var lastKept = -1

    // ------------------------------------------------------------------
    // SurfaceProcessor
    // ------------------------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            val pipeline = initGlIfNeeded()
            inputWidth = request.resolution.width
            inputHeight = request.resolution.height
            pipeline.onInputChanged()

            val st = SurfaceTexture(pipeline.cameraTextureId).apply {
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
            initGlIfNeeded()
            outputs.register(surfaceOutput, glExecutor)
        }
    }

    // ------------------------------------------------------------------
    // Render coordination
    // ------------------------------------------------------------------

    private fun renderToOutputs(timestampNs: Long) {
        val pipeline = pipeline ?: return
        if (outputs.isEmpty()) return
        val snapshot = renderBoxFeed.latest()
        val boxes = snapshot.boxes.filter { !it.keepVisible }
        logKeepVisibleChanges(snapshot.boxes.count { it.keepVisible })

        renderFrame.update(texMatrix, inputWidth, inputHeight, snapshot.rotationDegrees)
        val renderer = pipeline.rendererFactory.forMode(modeHolder.mode.value)

        // Shared per-frame work (e.g. the blurred frame copy), once for all outputs.
        if (boxes.isNotEmpty()) renderer.prepare(boxes, renderFrame)

        for ((output, eglSurface) in outputs.asMap()) {
            if (!egl.makeCurrent(eglSurface)) {
                Log.w(TAG, "eglMakeCurrent failed for output")
                continue
            }
            val w = output.size.width
            val h = output.size.height
            GLES20.glViewport(0, 0, w, h)

            // 1) Camera frame
            output.updateTransformMatrix(cameraMatrix, texMatrix)
            pipeline.cameraFrameRenderer.render(cameraMatrix)

            // 2) Anonymization boxes
            if (boxes.isNotEmpty()) {
                renderOutput.update(w, h, cameraMatrix)
                renderer.render(boxes, renderFrame, renderOutput)
            }

            egl.setPresentationTime(eglSurface, timestampNs)
            egl.swapBuffers(eglSurface)
        }
    }

    private fun logKeepVisibleChanges(kept: Int) {
        if (kept != lastKept) {
            lastKept = kept
            Log.d("Renderer", "keepVisible boxes in feed: $kept")
        }
    }

    private fun initGlIfNeeded(): RenderPipeline {
        egl.initIfNeeded()
        return pipeline ?: RenderPipeline(egl).also { pipeline = it }
    }

    fun shutdown() {
        glExecutor.execute {
            pipeline?.let {
                egl.makePbufferCurrent()
                it.release()
            }
            outputs.releaseAll()
            egl.release()
            glThread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "AnonProcessor"
    }
}