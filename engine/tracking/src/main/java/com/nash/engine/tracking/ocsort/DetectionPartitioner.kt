package com.nash.engine.tracking.ocsort

import com.nash.core.model.DetectionBox
import com.nash.core.model.OcSortConfig

internal data class DetectionPartitions(
    val highScore: List<DetectionBox>,
    val lowScore: List<DetectionBox>,
)

internal class DetectionPartitioner(
    private val config: OcSortConfig,
) {
    fun partition(detections: List<DetectionBox>): DetectionPartitions = DetectionPartitions(
        highScore = detections.filter { it.confidence >= config.highScoreThreshold },
        lowScore = detections.filter {
            it.confidence >= config.lowScoreThreshold && it.confidence < config.highScoreThreshold
        },
    )
}