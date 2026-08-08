package com.nash.engine.camera

import kotlinx.coroutines.flow.Flow

/**
 * Write side of camera session error reporting.
 *
 * A camera bind failure is a camera/session error, not a recording error, so
 * the session facade reports it here instead of routing it through
 * [CameraVideoRecorder]'s recording state.
 */
interface CameraSessionErrorReporter {
    fun reportBindError(cause: Throwable)
}

/**
 * Read side of camera session error reporting: the stream the engine merges
 * into its public warnings.
 */
interface CameraSessionErrorSource {
    val bindErrors: Flow<Throwable>
}