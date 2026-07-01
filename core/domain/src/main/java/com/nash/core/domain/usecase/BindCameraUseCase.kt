package com.nash.core.domain.usecase

import androidx.lifecycle.LifecycleOwner
import com.nash.core.domain.CameraSession
import javax.inject.Inject

class BindCameraUseCase @Inject constructor(
    private val cameraSession: CameraSession
) {
    operator fun invoke(lifecycleOwner: LifecycleOwner) {
        cameraSession.bind(lifecycleOwner)
    }
}
