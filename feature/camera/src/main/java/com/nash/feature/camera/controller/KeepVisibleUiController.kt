package com.nash.feature.camera.controller

import androidx.annotation.StringRes
import com.nash.core.common.DispatcherProvider
import com.nash.core.model.TrackId
import com.nash.core.model.TrackVerification
import com.nash.core.model.VerificationState
import com.nash.engine.api.BlurGuardEngine
import com.nash.engine.api.TrustedFaceRef
import com.nash.feature.camera.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the keep-visible enrollment workflow: tracks the enroll target, calls
 * the engine, observes verification transitions, and emits one-shot UI
 * messages as string resource ids.
 *
 * Plain class — no Compose or Android View types.
 */
class KeepVisibleUiController(
    private val engine: BlurGuardEngine,
    private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider
) {

    /** One-shot keep-visible message; null = no message pending. */
    @StringRes
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /**
     * The engine enrolls on the next detection frame, so the target may not be
     * in the verification map immediately after a tap. [seenInMap] tracks
     * whether the target has appeared at least once, so absence can be
     * disambiguated: not-yet-enrolled vs track died.
     */
    private data class Enrollment(val trackId: TrackId, val seenInMap: Boolean)

    private var enrollment: Enrollment? = null

    init {
        scope.launch(dispatcherProvider.default) {
            engine.keepVisible.collect { verifications ->
                onVerifications(verifications)
            }
        }
    }

    fun onFaceTapped(trackId: TrackId) {
        enrollment = Enrollment(trackId, seenInMap = false)
        scope.launch(dispatcherProvider.default) {
            engine.updateTrustedFaces(listOf(TrustedFaceRef(trackId)))
        }
    }

    fun onRevokeAll() {
        enrollment = null
        scope.launch(dispatcherProvider.default) {
            engine.updateTrustedFaces(emptyList())
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private fun onVerifications(verifications: Map<TrackId, TrackVerification>) {
        val current = enrollment ?: return
        val verification = verifications[current.trackId]

        if (verification == null) {
            // Track died after enrollment started: clear silently — no leaked
            // pending state. Before first appearance, keep waiting.
            if (current.seenInMap) {
                enrollment = null
            }
            return
        }

        if (!current.seenInMap) {
            enrollment = current.copy(seenInMap = true)
        }

        when (verification.state) {
            VerificationState.TRUSTED -> {
                enrollment = null
                _message.value = R.string.keep_visible_trusted
            }

            VerificationState.REJECTED -> {
                enrollment = null
                _message.value = R.string.keep_visible_rejected
            }

            VerificationState.PENDING,
            VerificationState.UNKNOWN -> {
                // Verification in flight — no message.
            }
        }
    }
}