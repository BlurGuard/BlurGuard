package com.nash.engine.impl.factory

import com.nash.core.model.Detector
import com.nash.core.model.DetectorConfig

/**
 * Owns detector backend selection.
 *
 * Construction policy ("which backend for which config") lives here, not in
 * DI modules: adding a backend means extending a factory + a config enum,
 * never editing the composition root (Open/Closed). Generic over the frame
 * type so the interface is JVM-testable with fake detectors.
 */
internal interface DetectorFactory<F> {
    fun create(config: DetectorConfig): List<Detector<F>>
}
