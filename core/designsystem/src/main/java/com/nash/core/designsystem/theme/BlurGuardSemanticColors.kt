package com.nash.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * BlurGuard semantic colors: fixed-purpose tokens layered on top of the
 * Material color scheme.
 *
 * These are camera/overlay colors whose values are dictated by function
 * (recording = red, trusted = green, ...), not by the theme palette. They are
 * intentionally identical in light and dark mode: the overlay sits on the
 * camera preview, not on themed surfaces, and its colors must stay constant
 * for privacy-critical readability.
 *
 * Values mirror the colors previously hardcoded in feature/camera composables
 * exactly (visual parity) — change a value here only with a deliberate
 * visual review.
 */
@Immutable
data class BlurGuardSemanticColors(
    /** Record button, stop square, and "● REC" indicator. */
    val recordingRed: Color,
    /** Translucent black scrim behind overlay chips/badges. */
    val overlayScrim: Color,
    /** Text/icons drawn on top of [overlayScrim]. */
    val overlayOnScrim: Color,
    /** Tracking overlay stroke for TRUSTED (kept-visible) faces. */
    val trustedGreen: Color,
    /** Tracking overlay stroke for PENDING verification. */
    val pendingAmber: Color,
    /** Tracking overlay stroke for REJECTED verification. */
    val rejectedRed: Color,
    /** Tracking overlay stroke for license plates. */
    val licensePlateYellow: Color,
    /** Tracking overlay stroke for unverified/unknown boxes. */
    val neutralOverlayStroke: Color,
    /** Tracking overlay label text. */
    val overlayText: Color,
    /** Tracking overlay label shadow. */
    val overlayTextShadow: Color,
)

val BlurGuardSemanticColorDefaults = BlurGuardSemanticColors(
    recordingRed = Color(0xFFE53935),
    overlayScrim = Color.Black.copy(alpha = 0.5f),
    overlayOnScrim = Color.White,
    trustedGreen = Color.Green,
    pendingAmber = Color(0xFFFFB300),
    rejectedRed = Color(0xFFE53935),
    licensePlateYellow = Color.Yellow,
    neutralOverlayStroke = Color.White.copy(alpha = 0.7f),
    overlayText = Color.White,
    overlayTextShadow = Color.Black,
)

val LocalBlurGuardSemanticColors = staticCompositionLocalOf {
    BlurGuardSemanticColorDefaults
}