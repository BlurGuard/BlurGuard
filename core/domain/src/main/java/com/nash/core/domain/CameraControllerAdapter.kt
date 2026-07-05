package com.nash.core.domain

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.nash.core.camera.CameraXCameraController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain-layer adapter that exposes lifecycle binding and preview creation
 * contracts to feature modules while delegating all CameraX work to
 * [CameraXCameraController] in [core.camera].
 *
 * This keeps [core.camera] free of any dependency on [core.domain] and preserves
 * the rule that engine modules depend only on [core.model] / [core.common].
 */
@Singleton
class CameraControllerAdapter @Inject constructor(
    private val cameraController: CameraXCameraController
) : CameraSession, CameraPreviewFactory {

    override fun bind(lifecycleOwner: LifecycleOwner) {
        cameraController.bind(lifecycleOwner)
    }

    override fun unbind() {
        cameraController.unbind()
    }

    override fun create(context: Context): View {
        return cameraController.createPreviewView(context)
    }
}
