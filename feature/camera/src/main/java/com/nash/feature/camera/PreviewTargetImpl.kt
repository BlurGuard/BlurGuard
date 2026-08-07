package com.nash.feature.camera

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import com.nash.engine.api.PreviewTarget

/**
 * Feature-owned preview view. The engine binds the camera to it via [view].
 */
class PreviewTargetImpl(context: Context) : PreviewTarget {

    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    override val view: View get() = previewView
}