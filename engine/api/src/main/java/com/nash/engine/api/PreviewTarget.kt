package com.nash.engine.api

import android.view.View
import androidx.lifecycle.LifecycleOwner

/**
 * A safe abstraction for where the engine should render its preview.
 * This hides CameraX and Surface details from the feature module.
 */
interface PreviewTarget {
    /**
     * The Android View that displays the preview.
     * The engine attaches its camera output to this view internally.
     */
    val view: View
}
