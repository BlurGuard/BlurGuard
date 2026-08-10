package com.nash.engine.ml.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RotationProbeStrategyTest {
    private val strategy = RotationProbeStrategy()

    @Test
    fun `starts upright when no landscape probe is preferred`() {
        assertArrayEquals(intArrayOf(0, 90, 270), strategy.order(0))
    }

    @Test
    fun `starts with preferred clockwise landscape probe`() {
        assertArrayEquals(intArrayOf(90, 0, 270), strategy.order(90))
    }

    @Test
    fun `starts with preferred counterclockwise landscape probe`() {
        assertArrayEquals(intArrayOf(270, 0, 90), strategy.order(270))
    }

    @Test
    fun `falls back to upright order for unsupported rotations`() {
        assertArrayEquals(intArrayOf(0, 90, 270), strategy.order(180))
    }
}
