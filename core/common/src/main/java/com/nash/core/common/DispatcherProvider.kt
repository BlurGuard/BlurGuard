package com.nash.core.common

import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Abstraction over Kotlin coroutine dispatchers so that modules do not
 * hard-code [Dispatchers] and remain testable.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher

    /**
     * Single-threaded dispatcher for the detection/tracking pipeline.
     * Serialized on purpose: one frame is processed at a time; backpressure
     * comes from dropping stale frames, never from queueing work.
     */
    val ml: CoroutineDispatcher
}

class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    @OptIn(ExperimentalCoroutinesApi::class)
    override val ml: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
