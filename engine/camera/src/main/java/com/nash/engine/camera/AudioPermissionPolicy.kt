package com.nash.engine.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the audio permission decision for recording: whether audio may be
 * enabled, and the [SecurityException] fallback for when the RECORD_AUDIO
 * permission is revoked between the check and enabling audio.
 *
 * Extracted from [CameraVideoRecorder] so the recorder contains no Android
 * permission logic. The permission probe is injectable through the internal
 * constructor so the policy is unit-testable on the plain JVM.
 */
@Singleton
class AudioPermissionPolicy internal constructor(
    private val hasRecordAudioPermission: () -> Boolean,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        hasRecordAudioPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        }
    )

    /** True only when the caller requested audio AND RECORD_AUDIO is granted. */
    fun canEnableAudio(includeAudio: Boolean): Boolean =
        includeAudio && hasRecordAudioPermission()

    /**
     * Applies [enableAudio] to [pending] when audio is requested and
     * permitted; otherwise returns [pending] unchanged (video-only).
     *
     * If the permission is revoked after the check, the resulting
     * [SecurityException] falls back to the unmodified video-only value —
     * identical to the legacy behavior inside [CameraVideoRecorder].
     */
    fun <T> withAudioIfPermitted(
        pending: T,
        includeAudio: Boolean,
        enableAudio: (T) -> T,
    ): T {
        if (!canEnableAudio(includeAudio)) {
            return pending
        }
        return try {
            enableAudio(pending)
        } catch (_: SecurityException) {
            // Audio permission was revoked after the check; record video-only.
            pending
        }
    }
}
