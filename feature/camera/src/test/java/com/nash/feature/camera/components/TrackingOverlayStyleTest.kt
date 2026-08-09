package com.nash.feature.camera.components

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingOverlayStyleTest {

    private val style = TrackingOverlayStyle()

    private fun box(
        id: Long,
        clazz: DetectionClass = DetectionClass.FACE,
    ) = TrackedBox(
        id = TrackId(id),
        box = BoundingBox(left = 0.4f, top = 0.4f, right = 0.6f, bottom = 0.6f),
        clazz = clazz,
        confidence = 0.9f,
        lastUpdatedFrame = 0L,
    )

    private fun tokenOf(
        tracked: TrackedBox,
        verification: TrackVerification?,
    ): BoxStroke {
        val choice = style.strokeFor(tracked, verification, debugIds = false)
        assertTrue("expected a token stroke, got $choice", choice is StrokeChoice.Token)
        return (choice as StrokeChoice.Token).stroke
    }

    // ---- normal mode -------------------------------------------------------

    @Test
    fun `license plate always gets plate stroke`() {
        val plate = box(1L, clazz = DetectionClass.LICENSE_PLATE)
        assertEquals(BoxStroke.LICENSE_PLATE, tokenOf(plate, null))
        // Plates keep their stroke even with verification entries.
        val trusted = TrackVerification(state = VerificationState.TRUSTED)
        assertEquals(BoxStroke.LICENSE_PLATE, tokenOf(plate, trusted))
    }

    @Test
    fun `trusted face gets trusted stroke`() {
        val trusted = TrackVerification(state = VerificationState.TRUSTED)
        assertEquals(BoxStroke.TRUSTED, tokenOf(box(1L), trusted))
    }

    @Test
    fun `pending face gets pending stroke`() {
        val pending = TrackVerification(state = VerificationState.PENDING)
        assertEquals(BoxStroke.PENDING, tokenOf(box(1L), pending))
    }

    @Test
    fun `rejected face gets rejected stroke`() {
        val rejected = TrackVerification(state = VerificationState.REJECTED)
        assertEquals(BoxStroke.REJECTED, tokenOf(box(1L), rejected))
    }

    @Test
    fun `unknown face gets neutral stroke`() {
        val unknown = TrackVerification(state = VerificationState.UNKNOWN)
        assertEquals(BoxStroke.NEUTRAL, tokenOf(box(1L), unknown))
    }

    @Test
    fun `missing verification gets neutral stroke`() {
        assertEquals(BoxStroke.NEUTRAL, tokenOf(box(1L), null))
    }

    // ---- debug mode --------------------------------------------------------

    @Test
    fun `debug mode returns per-id hue, overriding state`() {
        val trusted = TrackVerification(state = VerificationState.TRUSTED)
        val choice = style.strokeFor(box(5L), trusted, debugIds = true)
        assertTrue(choice is StrokeChoice.Debug)
        assertEquals(style.hueForId(5L), (choice as StrokeChoice.Debug).debugStroke.hue, 0.0001f)
    }

    @Test
    fun `debug hue matches golden-angle formula and is deterministic`() {
        val id = 42L
        val expected = ((id * 137.508) % 360.0).toFloat()
        assertEquals(expected, style.hueForId(id), 0.0001f)
        assertEquals(style.hueForId(id), style.hueForId(id), 0.0f)
    }

    @Test
    fun `debug hue stays within 0 to 360`() {
        for (id in listOf(1L, 7L, 100L, 10_000L, Long.MAX_VALUE / 2)) {
            val hue = style.hueForId(id)
            assertTrue("hue $hue for id $id in [0, 360)", hue >= 0f && hue < 360f)
        }
    }
}
