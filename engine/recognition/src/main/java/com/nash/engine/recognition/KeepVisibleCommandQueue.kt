package com.nash.engine.recognition

import com.nash.core.model.TrackId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free UI-to-ml-thread hand-off for keep-visible commands.
 *
 * Single owner of the pending-tap and revoke-all flags (strict follow-up
 * fix 1: command state must not live inside the recognizer). Written from
 * any thread via [com.nash.engine.api.keepvisible.KeepVisibleController];
 * read and drained on the ml thread once per detection frame.
 *
 * Public rather than internal because the DI wiring that shares one queue
 * between the controller and the recognizer lives in engine/impl.
 */
class KeepVisibleCommandQueue {

    private val pendingEnrollment = AtomicLong(NO_REQUEST)
    private val revokeAllRequested = AtomicBoolean(false)

    /** User tapped [trackId]. Replaces any earlier, not-yet-served tap. */
    fun requestEnrollment(trackId: TrackId) {
        pendingEnrollment.set(trackId.value)
    }

    fun requestRevokeAll() {
        revokeAllRequested.set(true)
    }

    /**
     * The pending tap, or null. Deliberately a read, not a removal: a tap
     * must survive failed gate/quality attempts across frames, so it is
     * removed only through [clearPendingEnrollment] once enrollment
     * succeeds, gives up, or the track dies.
     */
    fun pendingEnrollment(): TrackId? {
        val value = pendingEnrollment.get()
        return if (value == NO_REQUEST) null else TrackId(value)
    }

    /**
     * Clears the pending tap only if it is still [trackId] (CAS), so a
     * finished or abandoned attempt can never clobber a newer tap racing
     * in from the UI.
     */
    fun clearPendingEnrollment(trackId: TrackId) {
        pendingEnrollment.compareAndSet(trackId.value, NO_REQUEST)
    }

    /** Returns true at most once per [requestRevokeAll]. */
    fun drainRevokeAll(): Boolean = revokeAllRequested.compareAndSet(true, false)

    /**
     * Session reset: drops any pending tap (its track is gone). A pending
     * revoke-all deliberately survives — a panic delete must never be lost
     * to a session restart (fail-closed).
     */
    fun reset() {
        pendingEnrollment.set(NO_REQUEST)
    }

    private companion object {
        /** Sentinel for "no pending enrollment" — real TrackIds start at 1. */
        const val NO_REQUEST = Long.MIN_VALUE
    }
}