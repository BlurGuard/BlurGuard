package com.nash.feature.camera.components

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingOverlayLabelFormatterTest {

    private val formatter = TrackingOverlayLabelFormatter(
        facePrefix = "F",
        platePrefix = "P",
        visibleLabel = "✓ visible",
        verifyingLabel = "verifying…",
    )

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

    // ---- debug mode --------------------------------------------------------

    @Test
    fun `debug face label is F-hash-id`() {
        assertEquals(
            "F#7",
            formatter.labelFor(box(7L), verification = null, debugIds = true)
        )
    }

    @Test
    fun `debug plate label is P-hash-id`() {
        assertEquals(
            "P#3",
            formatter.labelFor(
                box(3L, clazz = DetectionClass.LICENSE_PLATE),
                verification = null,
                debugIds = true,
            )
        )
    }

    @Test
    fun `debug mode ignores verification state`() {
        val trusted = TrackVerification(state = VerificationState.TRUSTED)
        assertEquals(
            "F#7",
            formatter.labelFor(box(7L), verification = trusted, debugIds = true)
        )
    }

    // ---- normal mode -------------------------------------------------------

    @Test
    fun `trusted face gets visible label`() {
        val trusted = TrackVerification(state = VerificationState.TRUSTED)
        assertEquals(
            "✓ visible",
            formatter.labelFor(box(1L), verification = trusted, debugIds = false)
        )
    }

    @Test
    fun `pending face gets verifying label`() {
        val pending = TrackVerification(state = VerificationState.PENDING)
        assertEquals(
            "verifying…",
            formatter.labelFor(box(1L), verification = pending, debugIds = false)
        )
    }

    @Test
    fun `rejected face gets no label`() {
        val rejected = TrackVerification(state = VerificationState.REJECTED)
        assertNull(formatter.labelFor(box(1L), verification = rejected, debugIds = false))
    }

    @Test
    fun `unknown face gets no label`() {
        val unknown = TrackVerification(state = VerificationState.UNKNOWN)
        assertNull(formatter.labelFor(box(1L), verification = unknown, debugIds = false))
    }

    @Test
    fun `missing verification gets no label`() {
        assertNull(formatter.labelFor(box(1L), verification = null, debugIds = false))
    }
}
