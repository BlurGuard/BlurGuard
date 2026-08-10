package com.nash.engine.ml.recognition

/**
 * Chooses the orientation probes for landmark detection.
 *
 * Upright plus both landscape orientations are probed. 180 is deliberately
 * absent: an upside-down phone is not a supported hold and each extra probe
 * costs a full detector pass on the miss path.
 */
internal class RotationProbeStrategy {
    fun order(preferredRotation: Int): IntArray = when (preferredRotation) {
        90 -> PROBE_ORDER_90
        270 -> PROBE_ORDER_270
        else -> PROBE_ORDER_0
    }

    private companion object {
        val PROBE_ORDER_0 = intArrayOf(0, 90, 270)
        val PROBE_ORDER_90 = intArrayOf(90, 0, 270)
        val PROBE_ORDER_270 = intArrayOf(270, 0, 90)
    }
}
