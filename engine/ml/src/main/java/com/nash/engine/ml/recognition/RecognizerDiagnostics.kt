package com.nash.engine.ml.recognition

import android.util.Log
import com.nash.engine.ml.DetectorClock
import com.nash.engine.ml.SystemDetectorClock
import java.util.Locale

/**
 * Split-timing probes for the embedding path: EMA-smoothed bitmap / rotate /
 * crop / align / inference times, logged at most once per second.
 *
 * Same contract as DetectorDiagnostics, same clock seam, deliberately the same
 * shape so the two read alike in logcat. The difference is what it counts:
 * recognition is sporadic rather than per-frame, so [skips] matters as much as
 * the timings — it is how you see the quality gates doing their job.
 *
 * ## Release behaviour
 * When [enabled] is false the whole thing is a no-op: [mark] returns 0 without
 * reading the clock, and [record] / [recordSkip] return on their first line.
 * No clock syscall, no arithmetic, and no string formatting off the hot path.
 *
 * ## Thread-safety
 * The EMA state is mutated without synchronization and is only safe because
 * recognition runs on the serialized ml dispatcher. One instance per recognizer.
 *
 * @param enabled master switch, typically `context.isDebugBuild()`.
 * @param tag logcat tag.
 * @param sink log destination; overridable so tests need no Android framework.
 */
internal class RecognizerDiagnostics(
    private val enabled: Boolean,
    private val tag: String = "FaceRecognizer",
    private val clock: DetectorClock = SystemDetectorClock,
    private val logIntervalMs: Long = LOG_INTERVAL_MS,
    private val sink: (String, String) -> Unit = { logTag, message -> Log.d(logTag, message) }
) {

    var bitmapMs: Double = 0.0
        private set

    var rotateMs: Double = 0.0
        private set

    var cropMs: Double = 0.0
        private set

    var alignMs: Double = 0.0
        private set

    var inferenceMs: Double = 0.0
        private set

    /** Passes that produced an embedding. */
    var calls: Long = 0L
        private set

    /** Passes abandoned by a quality gate or a failed inference. */
    var skips: Long = 0L
        private set

    private var lastLogUptimeMs = 0L

    /** Timestamp for a stage boundary, or 0 when diagnostics are off. */
    fun mark(): Long = if (enabled) clock.elapsedNanos() else 0L

    /**
     * Feeds one completed pass's stage boundaries (as returned by [mark]) into
     * the EMAs and emits a throttled summary line.
     *
     * Tensor preprocessing is folded into the inference stage: it is a fixed
     * 112x112 loop with no allocation, and splitting it would add a sixth mark
     * for a constant.
     */
    fun record(
        passStart: Long,
        bitmapEnd: Long,
        rotateEnd: Long,
        cropEnd: Long,
        alignEnd: Long,
        inferenceEnd: Long
    ) {
        if (!enabled) return

        bitmapMs = updateEma(bitmapMs, (bitmapEnd - passStart) / NANOS_PER_MS)
        rotateMs = updateEma(rotateMs, (rotateEnd - bitmapEnd) / NANOS_PER_MS)
        cropMs = updateEma(cropMs, (cropEnd - rotateEnd) / NANOS_PER_MS)
        alignMs = updateEma(alignMs, (alignEnd - cropEnd) / NANOS_PER_MS)
        inferenceMs = updateEma(inferenceMs, (inferenceEnd - alignEnd) / NANOS_PER_MS)
        calls++

        maybeLog()
    }

    /** One pass that cost work but produced nothing. Timings are left alone. */
    fun recordSkip() {
        if (!enabled) return
        skips++
        maybeLog()
    }

    private fun maybeLog() {
        val nowMs = clock.uptimeMillis()
        if (nowMs - lastLogUptimeMs < logIntervalMs) return
        lastLogUptimeMs = nowMs
        sink(tag, summary())
    }

    private fun summary(): String = String.format(
        Locale.US,
        "recognize(ms): bitmap=%.1f rotate=%.1f crop=%.1f align=%.1f infer=%.1f " +
                "total=%.1f (n=%d, skipped=%d)",
        bitmapMs,
        rotateMs,
        cropMs,
        alignMs,
        inferenceMs,
        bitmapMs + rotateMs + cropMs + alignMs + inferenceMs,
        calls,
        skips
    )

    /** First sample seeds the average; afterwards 0.9 old / 0.1 new. */
    private fun updateEma(current: Double, sample: Double): Double =
        if (calls == 0L) sample else current * EMA_OLD + sample * EMA_NEW

    private companion object {
        const val LOG_INTERVAL_MS = 1_000L
        const val NANOS_PER_MS = 1e6
        const val EMA_OLD = 0.9
        const val EMA_NEW = 0.1
    }
}