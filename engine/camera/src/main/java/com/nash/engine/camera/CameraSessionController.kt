package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner

/**
 * Narrow contract for the engine camera session.
 *
 * Engine implementation code depends on this interface instead of the
 * concrete CameraX-backed facade (Dependency Inversion). No CameraX types
 * appear in this contract; previews are exposed only as plain [View]s, so
 * raw surfaces never leave engine/camera.
 */
interface CameraSessionController {

    /**
     * Creates the preview view for the anonymized preview stream.
     * Returned as [View] so callers need no CameraX dependency.
     */
    fun createPreviewView(context: Context): View

    /**
     * Attaches an externally created preview view as the preview output.
     * Safe to call before or after [bind].
     */
    fun attachPreviewView(view: View)

    /**
     * Binds the camera pipeline to [lifecycleOwner].
     * Safe to call more than once; a rebind releases the previous session first.
     */
    fun bind(lifecycleOwner: LifecycleOwner)

    /** Unbinds the camera pipeline and releases per-session resources. */
    fun unbind()

    /**
     * Permanently releases the session (scopes, executors, analyzer).
     * The instance is unusable afterward.
     */
    fun shutdown()
}
