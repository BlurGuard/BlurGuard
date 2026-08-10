package com.nash.engine.ml.recognition

import com.nash.engine.ml.DetectorClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RecognizerDiagnosticsTest {

    private class FakeClock(
        var nanos: Long = 0L,
        var millis: Long = 0L,
    ) : DetectorClock {
        var nanoReads = 0
            private set

        override fun elapsedNanos(): Long {
            nanoReads++
            return nanos
        }

        override fun uptimeMillis(): Long = millis
    }

    private val logs = mutableListOf<String>()

    /**
     * The clock starts past one full interval, because throttling measures
     * against a lastLog field seeded to 0: at uptime 0 the very first summary
     * would be suppressed. On a device uptimeMillis() is already minutes in by
     * the time a face is embedded, so START models reality — starting at 0
     * would be testing an unreachable state.
     */
    private val clock = FakeClock(millis = START)

    private fun diagnostics(enabled: Boolean = true) = RecognizerDiagnostics(
        enabled = enabled,
        tag = "T",
        clock = clock,
        sink = { _, message -> logs += message },
    )

    /** One pass with [stageMs] per stage, expressed in nanos. */
    private fun RecognizerDiagnostics.recordPass(stageMs: Long) {
        val ns = stageMs * 1_000_000L
        record(0L, ns, 2 * ns, 3 * ns, 4 * ns, 5 * ns, 6 * ns)
    }

    @Test
    fun `the first pass seeds the averages`() {
        val d = diagnostics()

        d.recordPass(stageMs = 4)

        assertEquals(4.0, d.bitmapMs, 1e-6)
        assertEquals(4.0, d.rotateMs, 1e-6)
        assertEquals(4.0, d.cropMs, 1e-6)
        assertEquals(4.0, d.alignMs, 1e-6)
        assertEquals(4.0, d.preprocessMs, 1e-6)
        assertEquals(4.0, d.inferenceMs, 1e-6)
        assertEquals(1L, d.calls)
    }

    @Test
    fun `later passes blend 0_9 old with 0_1 new`() {
        val d = diagnostics()

        d.recordPass(stageMs = 10)
        d.recordPass(stageMs = 20)

        assertEquals(10 * 0.9 + 20 * 0.1, d.inferenceMs, 1e-6)
        assertEquals(10 * 0.9 + 20 * 0.1, d.preprocessMs, 1e-6)
        assertEquals(2L, d.calls)
    }

    @Test
    fun `preprocess and inference are separate buckets`() {
        val d = diagnostics()
        val ms = 1_000_000L

        // align ends at 10ms, preprocess at 70ms, inference at 100ms.
        d.record(0L, ms, 2 * ms, 3 * ms, 10 * ms, 70 * ms, 100 * ms)

        assertEquals("preprocess is the align->fill span", 60.0, d.preprocessMs, 1e-6)
        assertEquals("inference is Interpreter.run only", 30.0, d.inferenceMs, 1e-6)
    }

    @Test
    fun `summaries are throttled to once per interval`() {
        val d = diagnostics()

        d.recordPass(stageMs = 1)
        assertEquals("the first pass emits a line", 1, logs.size)

        clock.millis = START + 999
        d.recordPass(stageMs = 1)
        assertEquals("still inside the interval", 1, logs.size)

        clock.millis = START + 1_000
        d.recordPass(stageMs = 1)
        assertEquals(2, logs.size)
    }

    @Test
    fun `skips are counted and reported without disturbing the timings`() {
        val d = diagnostics()

        d.recordPass(stageMs = 8)
        val inferenceAfterPass = d.inferenceMs

        clock.millis = START + 5_000
        d.recordSkip()
        d.recordSkip() // same millisecond: counted, but throttled out of the log

        assertEquals(1L, d.calls)
        assertEquals(2L, d.skips)
        assertEquals("a skip must not touch the stage averages", inferenceAfterPass, d.inferenceMs, 1e-6)

        assertEquals(2, logs.size)
        assertTrue(logs.last(), logs.last().contains("n=1"))
        assertTrue(logs.last(), logs.last().contains("skipped=1"))
    }

    @Test
    fun `disabled diagnostics touch nothing`() {
        val d = diagnostics(enabled = false)

        assertEquals(0L, d.mark())
        d.recordPass(stageMs = 50)
        d.recordSkip()

        assertEquals("no clock syscalls when disabled", 0, clock.nanoReads)
        assertEquals(0L, d.calls)
        assertEquals(0L, d.skips)
        assertEquals(0.0, d.preprocessMs, 1e-6)
        assertEquals(0.0, d.inferenceMs, 1e-6)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `output stays ASCII under a non-latin default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val d = diagnostics()
            d.recordPass(stageMs = 3)

            val line = logs.single()
            assertTrue(line, line.contains("prep=3.0"))
            assertTrue(line, line.contains("infer=3.0"))
            assertTrue(line, line.all { it.code < 128 })
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        /** Any value past one log interval; 10 s reads like a plausible uptime. */
        const val START = 10_000L
    }
}
