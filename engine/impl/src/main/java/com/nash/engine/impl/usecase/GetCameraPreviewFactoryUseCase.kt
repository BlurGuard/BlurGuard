package com.nash.engine.impl.usecase

import com.nash.engine.impl.CameraPreviewFactory
import javax.inject.Inject

class GetCameraPreviewFactoryUseCase @Inject constructor(
    private val previewFactory: CameraPreviewFactory
) {
    operator fun invoke(): CameraPreviewFactory = previewFactory
}
