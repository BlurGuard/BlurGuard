package com.nash.engine.camera

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Production [CameraSessionErrorReporter]: publishes bind errors to a shared
 * stream that [com.nash.engine.impl] merges into engine warnings.
 *
 * The stream replays the most recent error so a subscriber that starts
 * collecting just after a failed bind still observes it — mirroring the
 * stickiness of the legacy StateFlow-based reporting path.
 */
@Singleton
class DefaultCameraSessionErrorReporter @Inject constructor() :
    CameraSessionErrorReporter, CameraSessionErrorSource {

    private val _bindErrors = MutableSharedFlow<Throwable>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val bindErrors: SharedFlow<Throwable> = _bindErrors.asSharedFlow()

    override fun reportBindError(cause: Throwable) {
        _bindErrors.tryEmit(cause)
    }
}