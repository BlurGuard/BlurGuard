package com.nash.engine.impl.logging

import android.util.Log
import com.nash.engine.recognition.RecognitionLogger

/**
 * Production [RecognitionLogger] backed by [android.util.Log]. The only place
 * keep-visible logging touches the Android framework; policy code sees the
 * interface alone.
 *
 * @param debugEnabled when false, debug lambdas are never evaluated — the
 * same "compiles away to a branch on a val" contract the orchestrator's
 * debugLogging flag used to provide. Warnings always survive.
 */
internal class AndroidRecognitionLogger(
    private val debugEnabled: Boolean,
) : RecognitionLogger {

    override fun debug(message: () -> String) {
        if (debugEnabled) Log.d(TAG, message())
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun warn(message: String, throwable: Throwable?) {
        Log.w(TAG, message, throwable)
    }

    private companion object {
        const val TAG = "KeepVisible"
    }
}
