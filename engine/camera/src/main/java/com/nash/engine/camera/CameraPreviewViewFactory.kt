package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and validates the CameraX [PreviewView].
 *
 * Callers outside engine/camera only ever see [View]; CameraX types and the
 * raw camera Surface never leave this module (architecture invariant #4).
 */
@Singleton
class CameraPreviewViewFactory @Inject constructor() {

    fun create(context: Context): PreviewView =
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }

    fun requirePreviewView(view: View): PreviewView =
        view as? PreviewView
            ?: error("PreviewTarget.view must be a CameraX PreviewView")
}