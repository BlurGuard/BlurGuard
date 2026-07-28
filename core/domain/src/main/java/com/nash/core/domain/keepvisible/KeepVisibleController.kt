package com.nash.core.domain.keepvisible

import com.nash.core.model.TrackId

/**
 * UI-facing entry points for the keep-visible feature. Non-generic so use
 * cases can inject it without knowing the pipeline's frame type.
 * Safe to call from any thread; work happens on the ml dispatcher.
 */
interface KeepVisibleController {

    /**
     * User tapped this track's box: enroll the face behind it as a trusted
     * person and keep it visible. Processed on the next detection frame.
     */
    fun requestKeepVisible(trackId: TrackId)

    /** Re-blur everyone and forget all trusted persons. Wire to panic delete. */
    fun revokeAll()
}