package com.nash.engine.impl.factory

import com.nash.core.model.Tracker
import com.nash.core.model.TrackerConfig

/**
 * Owns tracker backend selection (review fix 13): which [Tracker] for which
 * [TrackerConfig.backend]. Selection lives here, not in DI modules, so adding
 * a backend means extending the factory + config, never the composition root.
 */
internal interface TrackerFactory {
    fun create(config: TrackerConfig): Tracker
}
