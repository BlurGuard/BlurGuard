package com.nash.engine.impl

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.nash.engine.camera.CameraXFacade
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine-side adapter that exposes lifecycle binding and preview creation
 * contracts while delegating all CameraX work to [CameraXFacade] in
 * [engine.camera].
 *
 * This keeps [engine.camera] free of any dependency on [engine.impl] and
 * preserves the rule that engine modules depend only on [core.model] /
 * [core.common].
 */
@Singleton
class CameraXFacadeAdapter @Inject constructor(
    private val cameraFacade: CameraXFacade
) : CameraSession, CameraPreviewFactory {

    override fun bind(lifecycleOwner: LifecycleOwner) {
        cameraFacade.bind(lifecycleOwner)
    }

    override fun unbind() {
        cameraFacade.unbind()
    }

    override fun create(context: Context): View {
        return cameraFacade.createPreviewView(context)
    }
}