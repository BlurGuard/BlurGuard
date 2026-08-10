package com.nash.engine.tracking.ocsort

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackId
import com.nash.core.model.TrackedBox

internal class OcSortTrack(
    val id: Long,
    detection: DetectionBox,
    frameId: Long,
) {
    val clazz: DetectionClass = detection.clazz
    val kf = KalmanBoxFilter(detection.box.toMeasurement())
    var confidence = detection.confidence
    var lastUpdatedFrame = frameId
    var predictedFrame = frameId

    /** OC-SORT trusts observations over filter state; keep the real ones. */
    var lastObservation: BoundingBox = detection.box
    var lastObservationFrame: Long = frameId
    var previousObservation: BoundingBox? = null

    /** Filter snapshot at the last real observation — rollback point for ORU. */
    private var frozenState = kf.stateSnapshot()
    private var frozenCovariance = kf.covarianceSnapshot()

    fun predictTo(frameId: Long) {
        val gap = frameId - predictedFrame
        if (gap <= 0) return
        repeat(gap.toInt()) { kf.predict() }
        predictedFrame = frameId
    }

    fun updateWith(detection: DetectionBox, frameId: Long) {
        val gap = frameId - lastObservationFrame
        if (gap > 1) {
            // ORU: rollback to the last observation, then re-run the filter
            // along a virtual linear trajectory to the new observation, so
            // blind-coasting error doesn't poison the velocity estimate.
            kf.restore(frozenState, frozenCovariance)
            val zLast = lastObservation.toMeasurement()
            val zNew = detection.box.toMeasurement()
            for (step in 1..gap) {
                kf.predict()
                val t = step.toDouble() / gap
                kf.update(DoubleArray(4) { zLast[it] + (zNew[it] - zLast[it]) * t })
            }
        } else {
            kf.update(detection.box.toMeasurement())
        }

        previousObservation = lastObservation
        lastObservation = detection.box
        lastObservationFrame = frameId
        frozenState = kf.stateSnapshot()
        frozenCovariance = kf.covarianceSnapshot()

        confidence = detection.confidence
        lastUpdatedFrame = frameId
        predictedFrame = frameId
    }

    /** Filter estimate, clamped to the frame; falls back to the last observation. */
    fun predictedBox(): BoundingBox {
        val state = kf.boxState() ?: return lastObservation
        val (cx, cy, w, h) = state
        val left = (cx - w / 2).toFloat().coerceIn(0f, 1f)
        val top = (cy - h / 2).toFloat().coerceIn(0f, 1f)
        val right = (cx + w / 2).toFloat().coerceIn(0f, 1f)
        val bottom = (cy + h / 2).toFloat().coerceIn(0f, 1f)
        // Fail-closed: a degenerate estimate must not un-blur someone.
        if (right - left < 1e-4f || bottom - top < 1e-4f) return lastObservation
        return BoundingBox(left, top, right, bottom)
    }

    fun toTrackedBox() = TrackedBox(
        id = TrackId(id),
        box = predictedBox(),
        clazz = clazz,
        confidence = confidence,
        lastUpdatedFrame = lastUpdatedFrame,
    )

    private operator fun DoubleArray.component4() = this[3]
}

private fun BoundingBox.toMeasurement(): DoubleArray {
    val w = (right - left).coerceAtLeast(1e-6f).toDouble()
    val h = (bottom - top).coerceAtLeast(1e-6f).toDouble()
    return doubleArrayOf(centerX.toDouble(), centerY.toDouble(), w * h, w / h)
}
