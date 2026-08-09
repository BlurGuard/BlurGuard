package com.nash.engine.camera

import java.util.concurrent.ExecutorService

/**
 * Owns the background executor used by the camera session for recorder
 * callbacks and camera work off the main thread.
 *
 * Extracted from [CameraXSessionFacade] so the facade does not construct
 * executors itself (Single Responsibility / Dependency Inversion), and so
 * tests can substitute a direct or controllable executor.
 */
interface CameraExecutorProvider {

    /** The executor for camera/recorder callbacks. Stable across rebinds. */
    val executor: ExecutorService

    /**
     * Permanently shuts the executor down. Called once during session
     * shutdown; the provider is unusable afterward.
     */
    fun shutdown()
}
