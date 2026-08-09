package com.nash.engine.camera

import org.junit.Assert
import org.junit.Test

class DefaultCameraSessionErrorReporterTest {

    private val reporter = DefaultCameraSessionErrorReporter()

    @Test
    fun `stream is empty before any error is reported`() {
        Assert.assertTrue(reporter.bindErrors.replayCache.isEmpty())
    }

    @Test
    fun `reportBindError publishes the cause to the bind error stream`() {
        val cause = IllegalStateException("bind failed")

        reporter.reportBindError(cause)

        Assert.assertEquals(listOf<Throwable>(cause), reporter.bindErrors.replayCache)
    }

    @Test
    fun `stream replays only the latest error for late subscribers`() {
        reporter.reportBindError(IllegalStateException("first"))
        val latest = IllegalStateException("second")

        reporter.reportBindError(latest)

        Assert.assertSame(latest, reporter.bindErrors.replayCache.single())
    }
}
