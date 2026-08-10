package com.nash.engine.render.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Owns the EGL display, context, config, and the 1x1 pbuffer surface used for
 * offscreen work. All methods must be called on the GL thread.
 */
internal class EglContextManager {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    val isInitialized: Boolean
        get() = eglDisplay != EGL14.EGL_NO_DISPLAY

    /** Idempotent. Leaves the pbuffer surface current so GL setup can run. */
    fun initIfNeeded() {
        if (isInitialized) return
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
    }

    fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)

    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun makeCurrent(eglSurface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

    fun makePbufferCurrent(): Boolean =
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

    fun setPresentationTime(eglSurface: EGLSurface, timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
    }

    fun swapBuffers(eglSurface: EGLSurface) {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (!isInitialized) return
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
        }
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        pbufferSurface = EGL14.EGL_NO_SURFACE
    }
}
