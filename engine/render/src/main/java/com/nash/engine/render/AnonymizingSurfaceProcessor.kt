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
import com.nash.core.model.TrackedBox
import com.nash.engine.render.gl.EglContextManager
import com.nash.engine.render.gl.SurfaceOutputRegistry
import com.nash.engine.render.renderer.RenderFrame
import com.nash.engine.render.renderer.RenderOutput
import com.nash.engine.render.renderer.RenderPipeline
import java.util.concurrent.Executor

/**
 * Coordinator for the anonymization pipeline (FR-04/FR-05).
 *
 * Only: receives input/output surfaces, reads the latest boxes, chooses the
 * mode renderer, renders each output, and sets the presentation time before
 * swapping buffers. All GL/EGL work is delegated to `gl/` and `renderer/`.
 *
 * Fail-closed: BlurRenderer falls back to BlackBoxRenderer whenever blur
 * preparation cannot be proven valid, so raw pixels are never shown.
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

    @Volatile
    private var isShutdown = false

    // Input
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputWidth = 0
    private var inputHeight = 0

    // Per-frame scratch, reused: no allocation in the hot loop.
    // GL-thread confined; never expose outside the render call.
    private val texMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val renderFrame = RenderFrame()
    private val renderOutput = RenderOutput()
    private val boxesScratch = ArrayList<TrackedBox>()
    private var lastKept = -1

    // ------------------------------------------------------------------
    // SurfaceProcessor
    // ------------------------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            if (isShutdown) {
                request.willNotProvideSurface()
                return@execute
            }
            val pipeline = initGlIfNeeded()
            inputWidth = request.resolution.width
            inputHeight = request.resolution.height
            pipeline.onInputChanged()

            // NOTE: cleanup of a replaced input surface is callback-owned.
            // CameraX signals through provideSurface's result listener when it
            // has stopped writing to the previous surface; releasing it
            // eagerly here could destroy a surface the camera HAL is still
            // producing into. We only re-point our references.
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
                if (isShutdown) return@setOnFrameAvailableListener
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
            if (isShutdown) {
                surfaceOutput.close()
                return@execute
            }
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

        boxesScratch.clear()
        snapshot.boxes.filterTo(boxesScratch) { !it.keepVisible }
        val boxes = boxesScratch

        if (ENABLE_RENDER_DIAGNOSTICS) logKeepVisibleChanges(snapshot)

        renderFrame.update(texMatrix, inputWidth, inputHeight, snapshot.rotationDegrees)
        val renderer = pipeline.rendererFactory.forMode(modeHolder.mode.value)

        // Shared per-frame work (e.g. the blurred frame copy), once for all outputs.
        if (boxes.isNotEmpty()) renderer.prepare(boxes, renderFrame)

        outputs.forEachOutput { output, eglSurface ->
            if (!egl.makeCurrent(eglSurface)) {
                Log.w(TAG, "eglMakeCurrent failed for output")
                return@forEachOutput
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

    private fun logKeepVisibleChanges(snapshot: RenderBoxFeed.Snapshot) {
        val kept = snapshot.boxes.count { it.keepVisible }
        if (kept != lastKept) {
            lastKept = kept
            Log.d(TAG, "keepVisible boxes in feed: $kept")
        }
    }

    private fun initGlIfNeeded(): RenderPipeline {
        egl.initIfNeeded()
        return pipeline ?: RenderPipeline(egl).also { pipeline = it }
    }

    fun shutdown() {
        isShutdown = true
        glExecutor.execute {
            pipeline?.let {
                egl.makePbufferCurrent()
                it.release()
            }
            pipeline = null
            outputs.releaseAll()
            egl.release()
            glThread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "AnonProcessor"

        /** Keep false in production: no logging from the render hot path. */
        const val ENABLE_RENDER_DIAGNOSTICS = false
    }
}