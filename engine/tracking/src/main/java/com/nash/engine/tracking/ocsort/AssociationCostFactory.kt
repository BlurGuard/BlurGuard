package com.nash.engine.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.OcSortConfig
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class AssociationCostFactory(
    private val config: OcSortConfig,
) {
    fun costMatrix(
        detections: List<DetectionBox>,
        tracks: List<OcSortTrack>,
        mode: AssociationMode,
    ): Array<DoubleArray> = Array(detections.size) { detectionIndex ->
        DoubleArray(tracks.size) { trackIndex ->
            cost(
                detection = detections[detectionIndex],
                track = tracks[trackIndex],
                mode = mode,
            )
        }
    }

    internal fun cost(
        detection: DetectionBox,
        track: OcSortTrack,
        mode: AssociationMode,
    ): Double {
        val overlap = iou(detection.box, mode.boxOf(track))
        return if (detection.clazz != track.clazz || overlap < mode.iouThreshold) {
            HungarianSolver.FORBIDDEN
        } else {
            (1.0 - overlap) + if (mode.useOcm) config.ocmWeight * directionCost(track, detection) else 0.0
        }
    }

    internal fun directionCost(track: OcSortTrack, detection: DetectionBox): Double {
        val previous = track.previousObservation ?: return 0.0
        val v1x = (track.lastObservation.centerX - previous.centerX).toDouble()
        val v1y = (track.lastObservation.centerY - previous.centerY).toDouble()
        val v2x = (detection.box.centerX - track.lastObservation.centerX).toDouble()
        val v2y = (detection.box.centerY - track.lastObservation.centerY).toDouble()
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        // A (near-)static track has no meaningful direction; don't penalize.
        if (n1 < 1e-6 || n2 < 1e-6) return 0.0
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return acos(cos) / PI
    }

    internal fun iou(a: BoundingBox, b: BoundingBox): Double {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        if (intersection <= 0f) return 0.0
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return intersection.toDouble() / (areaA + areaB - intersection).toDouble()
    }
}

internal data class AssociationMode(
    val useOcm: Boolean,
    val iouThreshold: Float,
    val boxOf: (OcSortTrack) -> BoundingBox,
)
