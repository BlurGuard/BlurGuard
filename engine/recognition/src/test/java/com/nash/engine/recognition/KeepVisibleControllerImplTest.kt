package com.nash.engine.recognition

import com.nash.core.model.PersonId
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepVisibleControllerImplTest {

    private val commands = KeepVisibleCommandQueue()
    private val state = SessionKeepVisibleStateStore()
    private val controller = KeepVisibleControllerImpl(commands, state)

    @Test
    fun `tap is handed to the frame side through the queue`() {
        controller.requestKeepVisible(TrackId(5))

        assertEquals(TrackId(5), commands.pendingEnrollment())
    }

    @Test
    fun `tap does not touch verification state`() {
        controller.requestKeepVisible(TrackId(5))

        // Promotion to PENDING/TRUSTED is the recognizer's job, on the ml
        // thread; the controller must never write trust state itself.
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(5)).state)
    }

    @Test
    fun `revokeAll re-blurs instantly without waiting for the ml thread`() {
        state.set(
            TrackId(1),
            TrackVerification(
                state = VerificationState.TRUSTED,
                personId = PersonId(9),
                lastCheckedFrame = 10L
            )
        )

        controller.revokeAll()

        // Fail-closed: the visual gate closes immediately...
        assertEquals(VerificationState.UNKNOWN, state.of(TrackId(1)).state)
        assertTrue(state.verifications.value.isEmpty())
        // ...and the gallery wipe is queued for the frame side.
        assertTrue(commands.drainRevokeAll())
    }

    @Test
    fun `revokeAll does not queue an enrollment`() {
        controller.revokeAll()

        assertNull(commands.pendingEnrollment())
    }
}