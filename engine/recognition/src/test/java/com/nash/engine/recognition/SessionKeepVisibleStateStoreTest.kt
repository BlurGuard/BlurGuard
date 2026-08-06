package com.nash.engine.recognition

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import com.nash.core.model.decorate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the keep-visible store and the render gate it feeds.
 *
 * Pure JVM: no Android, no Robolectric, no coroutine dispatcher — the store is
 * a StateFlow holder and decorate() is a pure function over an immutable map.
 */
class SessionKeepVisibleStateStoreTest {

    private val store = SessionKeepVisibleStateStore()

    @Test
    fun `absent track reads as unknown`() {
        val verification = store.of(TrackId(42L))

        assertEquals(VerificationState.UNKNOWN, verification.state)
        assertEquals(null, verification.personId)
        assertEquals(-1L, verification.lastCheckedFrame)
        assertTrue(store.verifications.value.isEmpty())
    }

    @Test
    fun `set then of round trips`() {
        store.set(TrackId(1L), trusted())

        assertEquals(VerificationState.TRUSTED, store.of(TrackId(1L)).state)
        assertEquals(1, store.verifications.value.size)
    }

    @Test
    fun `only trusted face tracks become keep-visible`() {
        val unknown = face(1L)
        val pending = face(2L)
        val trusted = face(3L)
        val rejected = face(4L)
        store.set(pending.id, TrackVerification(state = VerificationState.PENDING))
        store.set(trusted.id, trusted())
        store.set(rejected.id, TrackVerification(state = VerificationState.REJECTED))

        val decorated = store.verifications.value
            .decorate(listOf(unknown, pending, trusted, rejected))

        assertEquals(
            listOf(false, false, true, false),
            decorated.map { it.keepVisible }
        )
    }

    @Test
    fun `plates never become keep-visible even when marked trusted`() {
        val plate = TrackedBox(
            id = TrackId(7L),
            box = BoundingBox(0.1f, 0.1f, 0.3f, 0.2f),
            clazz = DetectionClass.LICENSE_PLATE,
            confidence = 0.9f,
            lastUpdatedFrame = 0L
        )
        // Deliberately corrupt state: a plate track flagged TRUSTED.
        store.set(plate.id, trusted())

        val decorated = store.verifications.value.decorate(listOf(plate))

        assertFalse(decorated.single().keepVisible)
    }

    @Test
    fun `clearAll re-blurs everything`() {
        val a = face(1L)
        val b = face(2L)
        store.set(a.id, trusted())
        store.set(b.id, trusted())

        store.clearAll()

        assertTrue(store.verifications.value.isEmpty())
        assertEquals(VerificationState.UNKNOWN, store.of(a.id).state)
        assertTrue(store.verifications.value.decorate(listOf(a, b)).none { it.keepVisible })
    }

    @Test
    fun `retainTracks drops dead tracks and keeps live ones`() {
        val alive = face(1L)
        val dead = face(2L)
        store.set(alive.id, trusted())
        store.set(dead.id, trusted())

        store.retainTracks(setOf(alive.id))

        assertEquals(setOf(alive.id), store.verifications.value.keys)
        assertEquals(VerificationState.TRUSTED, store.of(alive.id).state)
        assertEquals(VerificationState.UNKNOWN, store.of(dead.id).state)
        assertFalse(store.verifications.value.decorate(listOf(dead)).single().keepVisible)
    }

    @Test
    fun `retainTracks with all tracks live keeps the same map instance`() {
        store.set(TrackId(1L), trusted())
        val before = store.verifications.value

        store.retainTracks(setOf(TrackId(1L), TrackId(2L)))

        // No churn for observers when nothing expired.
        assertTrue(before === store.verifications.value)
    }

    @Test
    fun `every mutation publishes a new immutable snapshot`() {
        val before = store.verifications.value
        store.set(TrackId(1L), trusted())

        val after = store.verifications.value

        assertFalse(before === after)
        assertTrue(before.isEmpty())
        assertEquals(1, after.size)
    }

    @Test
    fun `empty state leaves boxes untouched`() {
        val boxes = listOf(face(1L), face(2L))

        assertTrue(store.verifications.value.decorate(boxes) === boxes)
    }

    private fun trusted() = TrackVerification(state = VerificationState.TRUSTED)

    private fun face(id: Long) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(0.1f, 0.1f, 0.2f, 0.3f),
        clazz = DetectionClass.FACE,
        confidence = 0.9f,
        lastUpdatedFrame = 0L
    )
}