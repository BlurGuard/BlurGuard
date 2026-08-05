package com.nash.engine.ml

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/** Time source, abstracted so throttling and EMA math are testable off-device. */
internal interface DetectorClock {
    /** Monotonic nanos for measuring durations. */
    fun elapsedNanos(): Long

    /** Monotonic millis for throttling. */
    fun uptimeMillis(): Long
}

internal object SystemDetectorClock : DetectorClock {
    override fun elapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}

/** True when the host app is a debuggable build; the engine's debug-logging switch. */
internal fun Context.isDebugBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * Split-timing probes for a detector: EMA-smoothed preprocess / inference /
 * decode times, logged at most once per second.
 *
 * ## Release behaviour
 * When [enabled] is false the whole thing is a no-op: [mark] returns 0 without
 * reading the clock and [record] returns on its first line, so there is no
 * clock syscall, no arithmetic and — critically — no string formatting on the
 * hot path. The log message is only built inside the throttled branch.
 *
 * ## Thread-safety
 * Same invariant as [YoloPreprocessor]: the EMA state is mutated without
 * synchronization and is only safe because detection runs on the serialized ml
 * dispatcher. One instance per detector.
 *
 * @param enabled master switch, typically `context.isDebugBuild()`.
 * @param tag logcat tag.
 * @param backendLabel evaluated only when a line is actually logged, so it can
 * touch lazily-created state (e.g. whether NNAPI won) without cost per frame.
 * @param sink log destination; overridable so tests need no Android framework.
 */
internal class DetectorDiagnostics(
    private val enabled: Boolean,
    private val tag: String,
    private val backendLabel: () -> String = { "unknown" },
    private val clock: DetectorClock = SystemDetectorClock,
    private val logIntervalMs: Long = LOG_INTERVAL_MS,
    private val sink: (String, String) -> Unit = { logTag, message -> Log.d(logTag, message) }
) {

    var preprocessMs: Double = 0.0
        private set

    var inferenceMs: Double = 0.0
        private set

    var decodeMs: Double = 0.0
        private set

    var frames: Long = 0L
        private set

    private var lastLogUptimeMs = 0L

    /** Timestamp for a stage boundary, or 0 when diagnostics are off. */
    fun mark(): Long = if (enabled) clock.elapsedNanos() else 0L

    /**
     * Feeds one frame's stage boundaries (as returned by [mark]) into the EMAs
     * and emits a throttled summary line.
     */
    fun record(
        frameStart: Long,
        preprocessEnd: Long,
        inferenceEnd: Long,
        decodeEnd: Long
    ) {
        if (!enabled) return

        preprocessMs = updateEma(preprocessMs, (preprocessEnd - frameStart) / NANOS_PER_MS)
        inferenceMs = updateEma(inferenceMs, (inferenceEnd - preprocessEnd) / NANOS_PER_MS)
        decodeMs = updateEma(decodeMs, (decodeEnd - inferenceEnd) / NANOS_PER_MS)
        frames++

        val nowMs = clock.uptimeMillis()
        if (nowMs - lastLogUptimeMs < logIntervalMs) return
        lastLogUptimeMs = nowMs
        sink(tag, summary())
    }

    private fun summary(): String = String.format(
        Locale.US,
        "split(ms): pre=%.1f infer=%.1f decode=%.1f total=%.1f (n=%d, backend=%s)",
        preprocessMs,
        inferenceMs,
        decodeMs,
        preprocessMs + inferenceMs + decodeMs,
        frames,
        backendLabel()
    )

    /** First sample seeds the average; afterwards 0.9 old / 0.1 new. */
    private fun updateEma(current: Double, sample: Double): Double =
        if (frames == 0L) sample else current * EMA_OLD + sample * EMA_NEW

    private companion object {
        const val LOG_INTERVAL_MS = 1_000L
        const val NANOS_PER_MS = 1e6
        const val EMA_OLD = 0.9
        const val EMA_NEW = 0.1
    }
}