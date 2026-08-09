package com.nash.engine.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPermissionPolicyTest {

    private fun policy(permissionGranted: Boolean) =
        AudioPermissionPolicy(hasRecordAudioPermission = { permissionGranted })

    @Test
    fun `canEnableAudio is false when audio is not requested`() {
        assertFalse(policy(permissionGranted = true).canEnableAudio(includeAudio = false))
    }

    @Test
    fun `canEnableAudio is false without the record audio permission`() {
        assertFalse(policy(permissionGranted = false).canEnableAudio(includeAudio = true))
    }

    @Test
    fun `canEnableAudio is true when audio is requested and permitted`() {
        assertTrue(policy(permissionGranted = true).canEnableAudio(includeAudio = true))
    }

    @Test
    fun `withAudioIfPermitted enables audio when requested and permitted`() {
        val result = policy(permissionGranted = true)
            .withAudioIfPermitted("video-only", includeAudio = true) { "$it+audio" }

        assertEquals("video-only+audio", result)
    }

    @Test
    fun `withAudioIfPermitted keeps video-only when audio is not requested`() {
        var enableCalls = 0

        val result = policy(permissionGranted = true)
            .withAudioIfPermitted("video-only", includeAudio = false) {
                enableCalls++
                "$it+audio"
            }

        assertEquals("video-only", result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun `withAudioIfPermitted keeps video-only without the permission`() {
        var enableCalls = 0

        val result = policy(permissionGranted = false)
            .withAudioIfPermitted("video-only", includeAudio = true) {
                enableCalls++
                "$it+audio"
            }

        assertEquals("video-only", result)
        assertEquals(0, enableCalls)
    }

    @Test
    fun `withAudioIfPermitted falls back to video-only when enabling audio throws SecurityException`() {
        val result = policy(permissionGranted = true)
            .withAudioIfPermitted("video-only", includeAudio = true) {
                throw SecurityException("permission revoked after check")
            }

        assertEquals("video-only", result)
    }
}
