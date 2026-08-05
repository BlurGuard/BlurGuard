package com.nash.feature.camera.components

import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState

/**
 * Turns track/verification state into overlay label text.
 *
 * Pure Kotlin — no Compose or Android types. String values (localized at the
 * composable via stringResource) are injected through the constructor, so
 * this class is JVM-unit-testable without a resource framework.
 */
internal class TrackingOverlayLabelFormatter(
    private val facePrefix: String,
    private val platePrefix: String,
    private val visibleLabel: String,
    private val verifyingLabel: String,
) {

    /**
     * Label to draw above [tracked]'s box, or null for no label.
     *
     * Debug mode labels every box with class prefix + track id. Normal mode
     * only surfaces keep-visible enrollment state (trusted/pending).
     */
    fun labelFor(
        tracked: TrackedBox,
        verification: TrackVerification?,
        debugIds: Boolean,
    ): String? {
        return when {
            debugIds -> "${prefixFor(tracked.clazz)}#${tracked.id.value}"
            verification?.state == VerificationState.TRUSTED -> visibleLabel
            verification?.state == VerificationState.PENDING -> verifyingLabel
            else -> null
        }
    }

    private fun prefixFor(clazz: DetectionClass): String =
        if (clazz == DetectionClass.FACE) facePrefix else platePrefix
}