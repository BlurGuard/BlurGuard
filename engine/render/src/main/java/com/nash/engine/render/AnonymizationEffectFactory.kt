package com.nash.engine.render

import androidx.camera.core.CameraEffect

/**
 * Builds the production [CameraEffect] that anonymizes BOTH the preview and
 * the recorded video (FR-04, FR-05, invariant #1).
 *
 * Construction lives here, next to the render implementation, so the DI
 * module only delegates — mirroring the pipeline-factory pattern in
 * engine/impl. Kept Hilt-free on purpose: engine/render exposes plain
 * classes and the DI wiring stays in engine/impl.
 */
class AnonymizationEffectFactory(
    private val processor: AnonymizingSurfaceProcessor,
) {

    fun create(): CameraEffect = AnonymizationCameraEffect(processor)
}
