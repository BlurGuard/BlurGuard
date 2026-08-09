package com.nash.engine.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import com.nash.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * Owns [ProcessCameraProvider] resolution for the camera session.
 *
 * Extracted from [CameraXSessionFacade] so the facade no longer holds the
 * provider future or the IO-dispatch detail of resolving it. The future is
 * process-wide and stays valid across rebinds, so it is created lazily once
 * and awaiting it repeatedly is safe.
 */
@Singleton
class CameraProviderResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(context)
    }

    /**
     * Resolves the process-wide camera provider on the IO dispatcher.
     * Safe to call on the main thread and safe to call repeatedly.
     */
    suspend fun resolve(): ProcessCameraProvider =
        withContext(dispatcherProvider.io) {
            cameraProviderFuture.await()
        }
}
