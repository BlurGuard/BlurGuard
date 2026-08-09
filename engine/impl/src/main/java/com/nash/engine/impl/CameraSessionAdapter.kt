package com.nash.engine.impl

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.nash.engine.camera.CameraSessionController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts the engine/camera [CameraSessionController] to the engine/impl
 * [CameraSession] and [CameraPreviewFactory] contracts.
 */
@Singleton
class CameraSessionAdapter @Inject constructor(
    private val cameraSessionController: CameraSessionController
) : CameraSession, CameraPreviewFactory {

    override fun bind(lifecycleOwner: LifecycleOwner) {
        cameraSessionController.bind(lifecycleOwner)
    }

    override fun unbind() {
        cameraSessionController.unbind()
    }

    override fun create(context: Context): View {
        return cameraSessionController.createPreviewView(context)
    }
}
