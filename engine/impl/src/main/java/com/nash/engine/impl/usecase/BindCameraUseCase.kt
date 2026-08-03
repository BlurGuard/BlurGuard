package com.nash.engine.impl.usecase

import androidx.lifecycle.LifecycleOwner
import com.nash.engine.impl.CameraSession
import javax.inject.Inject

class BindCameraUseCase @Inject constructor(
    private val cameraSession: CameraSession
) {
    operator fun invoke(lifecycleOwner: LifecycleOwner) {
        cameraSession.bind(lifecycleOwner)
    }
}
