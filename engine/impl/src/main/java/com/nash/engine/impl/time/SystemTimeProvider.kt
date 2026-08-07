package com.nash.engine.impl.time

import com.nash.core.model.TimeProvider

/**
 * Production [TimeProvider] backed by the real system clocks. This is the
 * only place in the engine allowed to call [System.currentTimeMillis] /
 * [System.nanoTime] for orchestration and recording mapping.
 */
class SystemTimeProvider : TimeProvider {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun nanoTime(): Long = System.nanoTime()
}