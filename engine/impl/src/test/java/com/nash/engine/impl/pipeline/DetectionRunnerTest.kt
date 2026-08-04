package com.nash.engine.impl.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionRunnerTest {

    @Test
    fun `concatenates results from all detectors`() = runTest {
        val runner = DetectionRunner(
            detectors = listOf(
                FakeDetector(listOf(detection(left = 0.1f))),
                FakeDetector(listOf(detection(left = 0.2f), detection(left = 0.3f))),
            ),
            failurePolicy = RecordingFailurePolicy(),
        )

        val result = runner.detect("frame", meta(0L))

        assertEquals(3, result.boxes.size)
        assertFalse(result.degraded)
    }

    @Test
    fun `one failing detector does not discard the others`() = runTest {
        val policy = RecordingFailurePolicy()
        val runner = DetectionRunner(
            detectors = listOf(
                FakeDetector(error = IllegalStateException("interpreter died")),
                FakeDetector(listOf(detection())),
            ),
            failurePolicy = policy,
        )

        val result = runner.detect("frame", meta(0L))

        assertEquals(1, result.boxes.size)
        assertTrue(result.degraded)
        assertEquals(1, policy.failures.size)
    }

    @Test
    fun `all detectors failing is reported as degraded, not as an empty scene`() = runTest {
        val policy = RecordingFailurePolicy()
        val runner = DetectionRunner(
            detectors = listOf(
                FakeDetector(error = RuntimeException("boom")),
                FakeDetector(error = RuntimeException("boom")),
            ),
            failurePolicy = policy,
        )

        val result = runner.detect("frame", meta(0L))

        // This is THE fail-open trap: zero boxes must be distinguishable from
        // "nothing to blur".
        assertTrue(result.boxes.isEmpty())
        assertTrue(result.degraded)
        assertEquals(2, policy.failures.size)
    }

    @Test
    fun `cancellation propagates instead of being swallowed`() = runTest {
        val policy = RecordingFailurePolicy()
        val runner = DetectionRunner(
            detectors = listOf(FakeDetector(error = CancellationException("shutdown"))),
            failurePolicy = policy,
        )

        var thrown = false
        try {
            runner.detect("frame", meta(0L))
        } catch (e: CancellationException) {
            thrown = true
        }

        assertTrue("CancellationException must unwind onFrame", thrown)
        assertTrue("cancellation is not a detector failure", policy.failures.isEmpty())
    }

    @Test
    fun `empty detector list yields empty non-degraded result`() = runTest {
        val runner = DetectionRunner<TestFrame>(detectors = emptyList())
        val result = runner.detect("frame", meta(0L))
        assertTrue(result.boxes.isEmpty())
        assertFalse(result.degraded)
    }
}