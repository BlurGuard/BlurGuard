package com.nash.core.model

/**
 * Abstraction over system time so engine orchestration and recording
 * mapping stay deterministic in unit tests (review fix: strict DIP for
 * time access).
 *
 * Production code should depend on this interface instead of calling
 * [System.currentTimeMillis] / [System.nanoTime] directly. The production
 * implementation lives in engine/impl (SystemTimeProvider); tests supply
 * fixed or manually advanced fakes.
 */
interface TimeProvider {

    /** Wall-clock time in milliseconds since the Unix epoch. */
    fun currentTimeMillis(): Long

    /** Monotonic high-resolution timestamp in nanoseconds, for measuring elapsed intervals. */
    fun nanoTime(): Long
}