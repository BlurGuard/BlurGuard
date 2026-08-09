package com.nash.engine.camera

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [CameraExecutorProvider]: a single background thread, matching
 * the previous facade-owned `Executors.newSingleThreadExecutor()` so recorder
 * callbacks stay serialized on one camera thread.
 */
@Singleton
class SingleThreadCameraExecutorProvider @Inject constructor() : CameraExecutorProvider {

    override val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun shutdown() {
        executor.shutdown()
    }
}
