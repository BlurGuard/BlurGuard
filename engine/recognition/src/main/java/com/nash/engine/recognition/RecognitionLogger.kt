package com.nash.engine.recognition

/**
 * Logging seam for keep-visible policy code.
 *
 * Domain policy must not import android.util.Log directly (strict follow-up
 * fix 4): recognition classes depend on this interface only, and production
 * binds an Android-backed implementation in DI (engine/impl). Debug messages
 * are lazy, so disabled debug logging costs one branch and never builds the
 * string; warnings always survive.
 *
 * Public rather than internal because the production implementation and its
 * DI binding live in engine/impl, outside this module.
 */
interface RecognitionLogger {

    /** Per-decision trace. [message] is evaluated only when debug logging is enabled. */
    fun debug(message: () -> String)

    fun warn(message: String)

    fun warn(message: String, throwable: Throwable?)

    /** No-op logger: the default for JVM tests and a safe fallback. */
    object None : RecognitionLogger {
        override fun debug(message: () -> String) = Unit
        override fun warn(message: String) = Unit
        override fun warn(message: String, throwable: Throwable?) = Unit
    }
}