package com.nash.engine.ml.recognition

import android.util.Log

internal interface FaceAlignmentLogger {
    fun debug(message: () -> String)
}

internal class AndroidFaceAlignmentLogger(
    private val enabled: Boolean,
    private val tag: String = TAG,
) : FaceAlignmentLogger {
    override fun debug(message: () -> String) {
        if (enabled) {
            Log.d(tag, message())
        }
    }

    private companion object {
        const val TAG = "FaceAligner"
    }
}