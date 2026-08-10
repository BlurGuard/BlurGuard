package com.nash.engine.ml.recognition

import android.util.Log
import com.nash.engine.ml.DetectorClock
import com.nash.engine.ml.SystemDetectorClock
import java.util.Locale

/**
 * Split timings for the embedding path, mirroring DetectorDiagnostics:
 * same EMA, same once-per-second throttle, same fake-able clock, same
 * zero-cost contract when disabled (no clock syscalls, no allocation,
 * no string building).
 *
 * Stages are cumulative marks taken along one embed() pass, so the
 * boundaries are exact rather than reconstructed.
 */
internal class RecognizerDiagnostics(
    private val enabled: Boolean,
    private val tag: String = "FaceRecognizer",
    private val clock: DetectorClock = SystemDetectorClock,
    private val logIntervalMs: Long = LOG_INTERVAL_MS,
    private val sink: (String, String) -> Unit = { t, m -> Log.d(t, m) },
) {

    /** ImageProxy -> Bitmap conversion. */
    var bitmapMs = 0.0
        private set

    /** Rotation to upright; free when rotationDegrees is 0. */
    var rotateMs = 0.0
        private set

    /** Dilated crop out of the upright frame. */
    var cropMs = 0.0
        private set

    /** BlazeFace landmark detection + similarity warp (FaceAligner). */
    var alignMs = 0.0
        private set

    /**
     * Pixel read plus the (x - 127.5) / 127.5 fill of the input tensor.
     * Split out from [inferenceMs] because these are different fixes: a slow
     * preprocess wants a bulk conversion, slow inference wants threads or a
     * delegate.
     */
    var preprocessMs = 0.0
        private set

    /** Interpreter.run only. */
    var inferenceMs = 0.0
        private set

    /** Completed passes folded into the averages. */
    var calls = 0L
        private set

    /** Passes abandoned at a quality gate; these never touch the averages. */
    var skips = 0L
        private set

    private var lastLogUptimeMs = 0L

    /** Cheap timestamp; returns 0 and reads no clock when disabled. */
    fun mark(): Long = if (enabled) clock.elapsedNanos() else 0L

    /**
     * Folds one complete pass into the averages. All arguments are marks
     * from [mark], in pass order.
     */
    fun record(
        passStart: Long,
        bitmapEnd: Long,
        rotateEnd: Long,
        cropEnd: Long,
        alignEnd: Long,
        preprocessEnd: Long,
        inferenceEnd: Long,
    ) {
        if (!enabled) return
        val first = calls == 0L
        bitmapMs = blend(first, bitmapMs, (bitmapEnd - passStart).toMs())
        rotateMs = blend(first, rotateMs, (rotateEnd - bitmapEnd).toMs())
        cropMs = blend(first, cropMs, (cropEnd - rotateEnd).toMs())
        alignMs = blend(first, alignMs, (alignEnd - cropEnd).toMs())
        preprocessMs = blend(first, preprocessMs, (preprocessEnd - alignEnd).toMs())
        inferenceMs = blend(first, inferenceMs, (inferenceEnd - preprocessEnd).toMs())
        calls++
        maybeLog()
    }

    /** A pass that stopped at a quality gate: counted, but not timed. */
    fun recordSkip() {
        if (!enabled) return
        skips++
        maybeLog()
    }

    private fun blend(first: Boolean, old: Double, sample: Double): Double =
        if (first) sample else old * EMA_OLD + sample * EMA_NEW

    private fun Long.toMs(): Double = this / NANOS_PER_MS

    private fun maybeLog() {
        val now = clock.uptimeMillis()
        if (now - lastLogUptimeMs < logIntervalMs) return
        lastLogUptimeMs = now
        sink(tag, summary())
    }

    private fun summary(): String = String.format(
        Locale.US,
        "recognize(ms): bitmap=%.1f rotate=%.1f crop=%.1f align=%.1f prep=%.1f " +
                "infer=%.1f total=%.1f (n=%d, skipped=%d)",
        bitmapMs, rotateMs, cropMs, alignMs, preprocessMs, inferenceMs,
        bitmapMs + rotateMs + cropMs + alignMs + preprocessMs + inferenceMs,
        calls, skips,
    )

    private companion object {
        const val LOG_INTERVAL_MS = 1_000L
        const val NANOS_PER_MS = 1e6
        const val EMA_OLD = 0.9
        const val EMA_NEW = 0.1
    }
}
