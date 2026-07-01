package com.nash.core.domain.usecase

import com.nash.core.domain.CameraPreviewFactory
import javax.inject.Inject

class GetCameraPreviewFactoryUseCase @Inject constructor(
    private val previewFactory: CameraPreviewFactory
) {
    operator fun invoke(): CameraPreviewFactory = previewFactory
}
