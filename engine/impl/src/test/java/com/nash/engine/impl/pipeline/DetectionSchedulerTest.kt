package com.nash.engine.impl.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionSchedulerTest {

    @Test
    fun `first frame always runs detection`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        assertTrue(scheduler.isDetectionDue(meta(frameId = 99L)))
    }

    @Test
    fun `interval boundary is inclusive`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        scheduler.isDetectionDue(meta(0L))                      // claims frame 0
        assertFalse(scheduler.isDetectionDue(meta(1L)))         // gap 1 < 2
        assertTrue(scheduler.isDetectionDue(meta(2L)))          // gap 2 >= 2
    }

    @Test
    fun `detect-predict cadence alternates at interval 2`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        val pattern = (0L..5L).map { scheduler.isDetectionDue(meta(it)) }
        assertTrue(pattern == listOf(true, false, true, false, true, false))
    }

    @Test
    fun `interval 1 detects every frame`() {
        val scheduler = DetectionScheduler(detectionInterval = 1L)
        assertTrue((0L..3L).all { scheduler.isDetectionDue(meta(it)) })
    }

    @Test
    fun `calling twice for the same frame does not double-claim`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        assertTrue(scheduler.isDetectionDue(meta(0L)))
        assertFalse(scheduler.isDetectionDue(meta(0L)))
    }

    @Test
    fun `frameId going backwards after camera restart still detects`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        scheduler.isDetectionDue(meta(1_000L))
        // Negative gap: must not stall detection forever.
        assertFalse(scheduler.isDetectionDue(meta(1L)))
        scheduler.reset()
        assertTrue(scheduler.isDetectionDue(meta(1L)))
    }

    @Test
    fun `reset restores first-frame-always-detects`() {
        val scheduler = DetectionScheduler(detectionInterval = 2L)
        scheduler.isDetectionDue(meta(0L))
        scheduler.reset()
        assertTrue(scheduler.isDetectionDue(meta(1L)))
    }
}
