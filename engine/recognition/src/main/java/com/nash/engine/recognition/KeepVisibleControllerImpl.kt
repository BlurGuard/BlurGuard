package com.nash.engine.recognition

import com.nash.core.model.KeepVisibleStateStore
import com.nash.core.model.TrackId
import com.nash.engine.api.keepvisible.KeepVisibleController

/**
 * UI-facing half of the keep-visible feature, and nothing else: it turns UI
 * intents into [KeepVisibleCommandQueue] commands and, for revocation, an
 * immediate visual re-blur. It knows nothing about frames, the recognizer,
 * or quality gates — those live behind [com.nash.engine.api.keepvisible.KeepVisibleRecognizer]
 * (strict follow-up fix 1: one class per interface).
 *
 * Safe to call from any thread: the queue is lock-free and the state store
 * publishes immutable snapshots.
 *
 * Correctness depends on sharing ONE [commands] and ONE [state] instance
 * with the frame-side recognizer; DI in engine/impl provides both as
 * singletons.
 */
class KeepVisibleControllerImpl(
    private val commands: KeepVisibleCommandQueue,
    private val state: KeepVisibleStateStore,
    private val logger: RecognitionLogger = RecognitionLogger.None,
) : KeepVisibleController {

    override fun requestKeepVisible(trackId: TrackId) {
        logger.debug { "tap: keep-visible requested for track=${trackId.value}" }
        commands.requestEnrollment(trackId)
    }

    override fun revokeAll() {
        logger.debug { "revokeAll requested" }
        state.clearAll() // instant visual re-blur, before the ml thread runs
        commands.requestRevokeAll() // store wipe drained on ml thread
    }
}
