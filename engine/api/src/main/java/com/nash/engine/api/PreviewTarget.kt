package com.nash.engine.api

import androidx.lifecycle.LifecycleOwner

/**
 * A safe abstraction for where the engine should render its preview.
 * This hides CameraX and Surface details from the feature module.
 */
interface PreviewTarget {
    /**
     * Called by the engine to bind its output to this target.
     * The engine owns the lifecycle of the binding.
     */
    fun bind(lifecycleOwner: LifecycleOwner)
}
