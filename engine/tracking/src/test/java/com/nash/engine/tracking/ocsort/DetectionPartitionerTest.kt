package com.nash.engine.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.OcSortConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionPartitionerTest {
    private val config = OcSortConfig(
        highScoreThreshold = 0.45f,
        lowScoreThreshold = 0.10f,
    )
    private val partitioner = DetectionPartitioner(config)

    @Test
    fun `partitions high and low score detections without mutating input order`() {
        val belowLow = detection(0.09f)
        val low = detection(0.10f)
        val mid = detection(0.44f)
        val high = detection(0.45f)
        val higher = detection(0.90f)
        val detections = listOf(belowLow, low, mid, high, higher)

        val partitions = partitioner.partition(detections)

        assertEquals(listOf(high, higher), partitions.highScore)
        assertEquals(listOf(low, mid), partitions.lowScore)
        assertEquals(listOf(belowLow, low, mid, high, higher), detections)
    }

    private fun detection(confidence: Float) = DetectionBox(
        box = BoundingBox(0.1f, 0.1f, 0.2f, 0.2f),
        clazz = DetectionClass.FACE,
        confidence = confidence,
    )
}
