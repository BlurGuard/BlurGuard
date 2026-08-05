package com.nash.engine.ml

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorDiagnosticsTest {

    private class FakeClock(
        var nanos: Long = 0L,
        var uptime: Long = 0L
    ) : DetectorClock {
        override fun elapsedNanos(): Long = nanos
        override fun uptimeMillis(): Long = uptime
    }

    private val clock = FakeClock()
    private val logs = mutableListOf<String>()

    private fun diagnostics(enabled: Boolean = true) = DetectorDiagnostics(
        enabled = enabled,
        tag = "Test",
        backendLabel = { "nnapi" },
        clock = clock,
        sink = { _, message -> logs += message }
    )

    /** pre = 10 ms, infer = 20 ms, decode = 5 ms unless overridden. */
    private fun DetectorDiagnostics.recordFrame(
        preMs: Double = 10.0,
        inferMs: Double = 20.0,
        decodeMs: Double = 5.0
    ) {
        val start = 0L
        val preEnd = ms(preMs)
        val inferEnd = preEnd + ms(inferMs)
        record(start, preEnd, inferEnd, inferEnd + ms(decodeMs))
    }

    private fun ms(value: Double): Long = (value * 1e6).toLong()

    // ---- EMA ---------------------------------------------------------------

    @Test
    fun `first frame seeds the averages instead of decaying from zero`() {
        val diagnostics = diagnostics()

        diagnostics.recordFrame()

        assertEquals(10.0, diagnostics.preprocessMs, EPS)
        assertEquals(20.0, diagnostics.inferenceMs, EPS)
        assertEquals(5.0, diagnostics.decodeMs, EPS)
        assertEquals(1L, diagnostics.frames)
    }

    @Test
    fun `later frames blend nine to one`() {
        val diagnostics = diagnostics()

        diagnostics.recordFrame(preMs = 10.0, inferMs = 20.0, decodeMs = 5.0)
        diagnostics.recordFrame(preMs = 20.0, inferMs = 30.0, decodeMs = 15.0)

        assertEquals(10 * 0.9 + 20 * 0.1, diagnostics.preprocessMs, EPS)
        assertEquals(20 * 0.9 + 30 * 0.1, diagnostics.inferenceMs, EPS)
        assertEquals(5 * 0.9 + 15 * 0.1, diagnostics.decodeMs, EPS)
        assertEquals(2L, diagnostics.frames)
    }

    @Test
    fun `a single slow frame cannot dominate the average`() {
        val diagnostics = diagnostics()

        repeat(10) { diagnostics.recordFrame(preMs = 10.0) }
        diagnostics.recordFrame(preMs = 500.0)

        assertTrue("EMA overreacted: ${diagnostics.preprocessMs}", diagnostics.preprocessMs < 60.0)
    }

    // ---- Throttling --------------------------------------------------------

    @Test
    fun `logs at most once per second`() {
        val diagnostics = diagnostics()

        clock.uptime = 10_000
        diagnostics.recordFrame()   // first line

        clock.uptime = 10_400
        diagnostics.recordFrame()   // throttled
        clock.uptime = 10_999
        diagnostics.recordFrame()   // throttled

        clock.uptime = 11_000
        diagnostics.recordFrame()   // second line

        assertEquals(2, logs.size)
    }

    @Test
    fun `throttling never drops samples from the averages`() {
        val diagnostics = diagnostics()

        clock.uptime = 10_000
        repeat(5) { diagnostics.recordFrame() }

        assertEquals(5L, diagnostics.frames)
        assertEquals(1, logs.size)
    }

    // ---- Disabled path -----------------------------------------------------

    @Test
    fun `disabled diagnostics do nothing at all`() {
        val diagnostics = diagnostics(enabled = false)
        clock.nanos = 12_345
        clock.uptime = 10_000

        assertEquals(0L, diagnostics.mark())

        diagnostics.recordFrame()

        assertEquals(0L, diagnostics.frames)
        assertEquals(0.0, diagnostics.preprocessMs, EPS)
        assertEquals(0.0, diagnostics.inferenceMs, EPS)
        assertEquals(0.0, diagnostics.decodeMs, EPS)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `enabled mark reads the clock`() {
        clock.nanos = 12_345

        assertEquals(12_345L, diagnostics().mark())
    }

    // ---- Log line ----------------------------------------------------------

    @Test
    fun `log line reports stages, frame count and backend`() {
        val diagnostics = diagnostics()
        clock.uptime = 10_000

        diagnostics.recordFrame()

        val line = logs.single()
        assertTrue(line, line.contains("pre=10.0"))
        assertTrue(line, line.contains("infer=20.0"))
        assertTrue(line, line.contains("decode=5.0"))
        assertTrue(line, line.contains("total=35.0"))
        assertTrue(line, line.contains("n=1"))
        assertTrue(line, line.contains("backend=nnapi"))
    }

    @Test
    fun `log line stays ASCII under a non-latin default locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
        try {
            val diagnostics = diagnostics()
            clock.uptime = 10_000

            diagnostics.recordFrame()

            assertTrue(logs.single(), logs.single().contains("pre=10.0"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        const val EPS = 1e-9
    }
}