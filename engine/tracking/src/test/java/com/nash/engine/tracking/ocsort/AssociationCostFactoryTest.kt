package com.nash.engine.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.OcSortConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationCostFactoryTest {
    private val factory = AssociationCostFactory(OcSortConfig(ocmWeight = 0.25))

    @Test
    fun `iou returns overlap ratio`() {
        val overlap = factory.iou(
            BoundingBox(0f, 0f, 0.5f, 0.5f),
            BoundingBox(0.25f, 0.25f, 0.75f, 0.75f),
        )

        assertEquals(1.0 / 7.0, overlap, 1e-6)
    }

    @Test
    fun `cost is forbidden for mismatched class`() {
        val track = track(detection(DetectionClass.FACE, box(0.1f), 0.9f))
        val detection = detection(DetectionClass.LICENSE_PLATE, box(0.1f), 0.9f)

        val cost = factory.cost(detection, track, AssociationMode(false, 0.1f) { it.predictedBox() })

        assertEquals(HungarianSolver.FORBIDDEN, cost, 0.0)
    }

    @Test
    fun `direction cost is zero before previous observation exists`() {
        val track = track(detection(DetectionClass.FACE, box(0.1f), 0.9f))

        assertEquals(0.0, factory.directionCost(track, detection(DetectionClass.FACE, box(0.2f), 0.9f)), 0.0)
    }

    @Test
    fun `ocm raises cost for reversed observed direction`() {
        val track = track(detection(DetectionClass.FACE, box(0.1f), 0.9f))
        track.updateWith(detection(DetectionClass.FACE, box(0.2f), 0.9f), frameId = 2L)
        val reversed = detection(DetectionClass.FACE, box(0.1f), 0.9f)

        val cost = factory.directionCost(track, reversed)

        assertTrue(cost > 0.9)
    }

    private fun track(detection: DetectionBox) = OcSortTrack(1L, detection, 1L)

    private fun detection(clazz: DetectionClass, box: BoundingBox, confidence: Float) = DetectionBox(box, clazz, confidence)

    private fun box(left: Float) = BoundingBox(left, 0.1f, left + 0.1f, 0.2f)
}