package com.nash.engine.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.OcSortConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationStageTest {
    private val stage = AssociationStage(AssociationCostFactory(OcSortConfig()))

    @Test
    fun `matches detections to tracks without mutating input lists`() {
        val matchingDetection = detection(box(0.1f), 0.9f)
        val unmatchedDetection = detection(box(0.7f), 0.9f)
        val matchedTrack = track(id = 1L, detection = detection(box(0.1f), 0.8f))
        val unmatchedTrack = track(id = 2L, detection = detection(box(0.4f), 0.8f))
        val detections = listOf(matchingDetection, unmatchedDetection)
        val tracks = listOf(matchedTrack, unmatchedTrack)

        val result = stage.associate(
            detections = detections,
            tracks = tracks,
            frameId = 2L,
            mode = AssociationMode(useOcm = false, iouThreshold = 0.1f) { it.predictedBox() },
        )

        assertEquals(listOf(matchingDetection), result.matchedDetections)
        assertEquals(listOf(matchedTrack), result.matchedTracks)
        assertEquals(listOf(unmatchedDetection), result.unmatchedDetections)
        assertEquals(listOf(unmatchedTrack), result.unmatchedTracks)
        assertEquals(listOf(matchingDetection, unmatchedDetection), detections)
        assertEquals(listOf(matchedTrack, unmatchedTrack), tracks)
        assertEquals(2L, matchedTrack.lastUpdatedFrame)
        assertEquals(1L, unmatchedTrack.lastUpdatedFrame)
    }

    @Test
    fun `returns all inputs unmatched when either side is empty`() {
        val detections = listOf(detection(box(0.1f), 0.9f))

        val result = stage.associate(
            detections = detections,
            tracks = emptyList(),
            frameId = 2L,
            mode = AssociationMode(useOcm = false, iouThreshold = 0.1f) { it.predictedBox() },
        )

        assertTrue(result.matchedDetections.isEmpty())
        assertTrue(result.matchedTracks.isEmpty())
        assertEquals(detections, result.unmatchedDetections)
        assertTrue(result.unmatchedTracks.isEmpty())
    }

    private fun track(id: Long, detection: DetectionBox) = OcSortTrack(id, detection, 1L)

    private fun detection(box: BoundingBox, confidence: Float) = DetectionBox(
        box = box,
        clazz = DetectionClass.FACE,
        confidence = confidence,
    )

    private fun box(left: Float) = BoundingBox(left, 0.1f, left + 0.1f, 0.2f)
}
