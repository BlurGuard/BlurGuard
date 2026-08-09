package com.nash.feature.camera.components

import com.nash.core.model.DetectionClass
import com.nash.core.model.TrackVerification
import com.nash.core.model.TrackedBox
import com.nash.core.model.VerificationState

/**
 * Which design-system stroke a tracked box gets. Pure decision logic — the
 * composable resolves the returned [BoxStroke] to actual colors, so this
 * class stays free of Compose types and JVM-unit-testable.
 */
internal enum class BoxStroke {
    LICENSE_PLATE,
    TRUSTED,
    PENDING,
    REJECTED,
    NEUTRAL,
}

/** Debug mode: deterministic per-id hue for [strokeHue]. */
internal data class DebugStroke(val hue: Float)

/** Result of [TrackingOverlayStyle.strokeFor]: a token, or a debug hue. */
internal sealed interface StrokeChoice {
    data class Token(val stroke: BoxStroke) : StrokeChoice
    data class Debug(val debugStroke: DebugStroke) : StrokeChoice
}

/**
 * Maps detection/verification state to a stroke choice.
 *
 * Pure Kotlin — no Compose or Android types. Colors arrive resolved in the
 * composable from design-system tokens; this class only decides WHICH one.
 */
internal class TrackingOverlayStyle {

    /**
     * Stroke for [tracked]: per-id debug hue when [debugIds] is on, otherwise
     * verification/class state. Faces without verification state get NEUTRAL.
     */
    fun strokeFor(
        tracked: TrackedBox,
        verification: TrackVerification?,
        debugIds: Boolean,
    ): StrokeChoice {
        if (debugIds) return StrokeChoice.Debug(DebugStroke(hueForId(tracked.id.value)))
        val stroke = when {
            tracked.clazz == DetectionClass.LICENSE_PLATE -> BoxStroke.LICENSE_PLATE
            verification?.state == VerificationState.TRUSTED -> BoxStroke.TRUSTED
            verification?.state == VerificationState.PENDING -> BoxStroke.PENDING
            verification?.state == VerificationState.REJECTED -> BoxStroke.REJECTED
            else -> BoxStroke.NEUTRAL
        }
        return StrokeChoice.Token(stroke)
    }

    /**
     * Deterministic golden-angle hue per track id, matching the pre-extraction
     * debug palette exactly.
     */
    fun hueForId(id: Long): Float = ((id * GOLDEN_ANGLE) % FULL_TURN).toFloat()

    private companion object {
        const val GOLDEN_ANGLE = 137.508
        const val FULL_TURN = 360.0
    }
}
