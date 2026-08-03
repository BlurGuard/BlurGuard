package com.nash.engine.render.gl

import android.opengl.EGLSurface
import androidx.camera.core.SurfaceOutput
import java.util.concurrent.Executor

/**
 * Owns the mapping from CameraX [SurfaceOutput]s (preview + video encoder) to
 * their EGL window surfaces, and destroys EGL surfaces when outputs close.
 * GL-thread only.
 */
internal class SurfaceOutputRegistry(
    private val egl: EglContextManager,
) {

    /**
     * Not private only because [forEachOutput] is inline (Kotlin forbids
     * inline functions from accessing less-visible members). Never touch
     * this outside SurfaceOutputRegistry. GL-thread only.
     */
    internal val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    fun isEmpty(): Boolean = outputs.isEmpty()

    /** Allocation-free per-frame iteration. GL-thread only. */
    inline fun forEachOutput(block: (SurfaceOutput, EGLSurface) -> Unit) {
        for ((output, surface) in outputs) block(output, surface)
    }

    /** EGL must already be initialized. [glExecutor] receives the close callback. */
    fun register(surfaceOutput: SurfaceOutput, glExecutor: Executor) {
        val surface = surfaceOutput.getSurface(glExecutor) {
            outputs.remove(surfaceOutput)?.let { egl.destroySurface(it) }
            surfaceOutput.close()
        }
        outputs[surfaceOutput] = egl.createWindowSurface(surface)
    }

    fun releaseAll() {
        outputs.values.forEach { egl.destroySurface(it) }
        outputs.clear()
    }
}