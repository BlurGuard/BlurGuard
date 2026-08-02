package com.nash.engine.impl

import android.content.Context
import android.view.View

/**
 * Factory that produces a camera preview [View] for the supplied [Context].
 *
 * The feature layer displays the returned [View] inside [AndroidView] without
 * needing to know that the underlying implementation is a CameraX [PreviewView].
 */
fun interface CameraPreviewFactory {
    fun create(context: Context): View
}
