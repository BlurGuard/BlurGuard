package com.nash.core.domain.usecase

import com.nash.core.domain.CameraSession
import javax.inject.Inject

class UnbindCameraUseCase @Inject constructor(
    private val cameraSession: CameraSession
) {
    operator fun invoke() {
        cameraSession.unbind()
    }
}
