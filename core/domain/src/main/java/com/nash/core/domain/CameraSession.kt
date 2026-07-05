package com.nash.core.domain

import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle-aware camera session contract.
 *
 * Kept in the domain layer because it references Android [LifecycleOwner].
 * Implementations live in [core.camera].
 */
interface CameraSession {

    /**
     * Binds the camera pipeline to the supplied [lifecycleOwner].
     *
     * This should be called when the camera screen enters composition and
     * unbound when it leaves.
     */
    fun bind(lifecycleOwner: LifecycleOwner)

    /**
     * Unbinds the camera pipeline from the current lifecycle owner.
     */
    fun unbind()
}
