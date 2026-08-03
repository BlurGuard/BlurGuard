package com.nash.engine.impl.usecase

import com.nash.engine.impl.CameraSession
import javax.inject.Inject

class UnbindCameraUseCase @Inject constructor(
    private val cameraSession: CameraSession
) {
    operator fun invoke() {
        cameraSession.unbind()
    }
}
