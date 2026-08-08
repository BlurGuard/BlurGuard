package com.nash.engine.camera

import android.content.Context
import android.view.View
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the preview output wiring: the current [PreviewView], the current
 * [Preview] use case, and surface-provider attachment.
 *
 * Extracted from [CameraXSessionFacade] so the facade no longer stores
 * preview state. The last [PreviewView] is intentionally kept across
 * rebinds: the engine re-attaches the feature's PreviewTarget on every bind,
 * and keeping the last view lets an unbind/rebind cycle without a new
 * target still show a preview.
 *
 * The attached view only ever renders the processed (anonymized) stream:
 * the [Preview] use case set here is bound inside the UseCaseGroup carrying
 * the anonymization CameraEffect, and raw surfaces never leave this module.
 */
@Singleton
class PreviewSurfaceAttacher @Inject constructor(
    private val previewViewFactory: CameraPreviewViewFactory,
) {

    @Volatile
    private var previewView: PreviewView? = null

    private var preview: Preview? = null

    /** Creates, stores, and (if a preview is set) attaches a new [PreviewView]. */
    fun createPreviewView(context: Context): PreviewView =
        previewViewFactory.create(context).also { view ->
            previewView = view
            attachSurfaceProviderIfReady()
        }

    /** Validates, stores, and (if a preview is set) attaches an external view. */
    fun attachPreviewView(view: View) {
        previewView = previewViewFactory.requirePreviewView(view)
        attachSurfaceProviderIfReady()
    }

    /**
     * Sets (or clears with `null`) the current [Preview] use case and
     * attaches the stored view's surface provider when both are present.
     */
    fun setPreview(preview: Preview?) {
        this.preview = preview
        attachSurfaceProviderIfReady()
    }

    private fun attachSurfaceProviderIfReady() {
        val view = previewView ?: return
        val preview = preview ?: return
        preview.surfaceProvider = view.surfaceProvider
    }
}