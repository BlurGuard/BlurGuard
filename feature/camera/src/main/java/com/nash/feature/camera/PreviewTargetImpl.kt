package com.nash.feature.camera

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.nash.engine.api.PreviewTarget

/**
 * A safe implementation of PreviewTarget that provides a View to the engine.
 * The feature layer owns the View creation, but the engine binds the camera to it.
 */
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
