package com.nash.engine.recognition

import com.nash.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepVisibleCommandQueueTest {

    private val queue = KeepVisibleCommandQueue()

    @Test
    fun `starts with no pending commands`() {
        assertNull(queue.pendingEnrollment())
        assertFalse(queue.drainRevokeAll())
    }

    @Test
    fun `a tap stays readable across frames until cleared`() {
        queue.requestEnrollment(TrackId(7))

        // Reading must not consume: gates may fail for many frames.
        assertEquals(TrackId(7), queue.pendingEnrollment())
        assertEquals(TrackId(7), queue.pendingEnrollment())

        queue.clearPendingEnrollment(TrackId(7))
        assertNull(queue.pendingEnrollment())
    }

    @Test
    fun `a newer tap replaces an unserved older tap`() {
        queue.requestEnrollment(TrackId(1))
        queue.requestEnrollment(TrackId(2))

        assertEquals(TrackId(2), queue.pendingEnrollment())
    }

    @Test
    fun `clearing a stale tap never drops a newer tap`() {
        queue.requestEnrollment(TrackId(1))
        queue.requestEnrollment(TrackId(2))

        // Frame side finishes (or abandons) the old attempt after the UI
        // already queued a new tap: the CAS must miss.
        queue.clearPendingEnrollment(TrackId(1))

        assertEquals(TrackId(2), queue.pendingEnrollment())
    }

    @Test
    fun `revoke all drains exactly once`() {
        queue.requestRevokeAll()

        assertTrue(queue.drainRevokeAll())
        assertFalse("a second drain must not re-trigger the wipe", queue.drainRevokeAll())
    }

    @Test
    fun `reset drops a pending tap`() {
        queue.requestEnrollment(TrackId(3))

        queue.reset()

        assertNull(queue.pendingEnrollment())
    }

    @Test
    fun `reset never loses a pending revoke all`() {
        queue.requestRevokeAll()

        queue.reset()

        assertTrue("a panic delete must survive a session reset", queue.drainRevokeAll())
    }
}