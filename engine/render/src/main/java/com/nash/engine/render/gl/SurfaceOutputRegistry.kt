package com.nash.engine.render.gl

import android.opengl.EGLSurface
import androidx.camera.core.SurfaceOutput
import java.util.concurrent.Executor

/**
 * Owns the mapping from CameraX [androidx.camera.core.SurfaceOutput]s (preview + video encoder) to
 * their EGL window surfaces, and destroys EGL surfaces when outputs close.
 * GL-thread only.
 */
internal class SurfaceOutputRegistry(
    private val egl: EglContextManager,
) {

    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    fun isEmpty(): Boolean = outputs.isEmpty()

    /** Live view for per-frame iteration. Do not mutate; GL-thread only. */
    fun asMap(): Map<SurfaceOutput, EGLSurface> = outputs

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