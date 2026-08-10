package com.nash.engine.ml.recognition

import com.nash.core.model.RecognitionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceQualityGateTest {
    private val gate = FaceQualityGate(RecognitionConfig(minFaceCropPx = MIN_FACE_CROP_PX))

    @Test
    fun `accepts crop size at configured minimum`() {
        val result = gate.validateCropSize(width = MIN_FACE_CROP_PX, height = MIN_FACE_CROP_PX)

        assertEquals(QualityResult.Valid, result)
    }

    @Test
    fun `rejects crop size below configured minimum`() {
        val result = gate.validateCropSize(width = MIN_FACE_CROP_PX - 1, height = MIN_FACE_CROP_PX)

        assertEquals(
            QualityResult.Invalid("gate: crop too small crop=31x32 (min 32)"),
            result,
        )
    }

    @Test
    fun `rejects keypoints when inter-eye distance is below minimum`() {
        val result = gate.validateKeypoints(
            FaceKeypoints(
                leftEye = FacePoint(10f, 10f),
                rightEye = FacePoint(29.9f, 10f),
                nose = FacePoint(19.95f, 25f),
            )
        )

        assertTrue((result as QualityResult.Invalid).reason.startsWith("interEye=19.9px < 20.0"))
    }

    @Test
    fun `accepts keypoints when inter-eye distance is at minimum`() {
        val result = gate.validateKeypoints(
            FaceKeypoints(
                leftEye = FacePoint(10f, 10f),
                rightEye = FacePoint(30f, 10f),
                nose = FacePoint(20f, 25f),
            )
        )

        assertEquals(QualityResult.Valid, result)
    }

    @Test
    fun `accepts frontal face within yaw gate`() {
        val result = gate.validateKeypoints(
            FaceKeypoints(
                leftEye = FacePoint(10f, 10f),
                rightEye = FacePoint(40f, 10f),
                nose = FacePoint(25f, 30f),
            )
        )

        assertEquals(QualityResult.Valid, result)
    }

    @Test
    fun `rejects turned face outside yaw gate`() {
        val result = gate.validateKeypoints(
            FaceKeypoints(
                leftEye = FacePoint(10f, 10f),
                rightEye = FacePoint(40f, 10f),
                nose = FacePoint(45f, 30f),
            )
        )

        assertTrue((result as QualityResult.Invalid).reason.startsWith("not frontal yaw=20.0"))
    }

    @Test
    fun `rejects degenerate zero inter-eye case fail closed`() {
        val result = gate.validateKeypoints(
            FaceKeypoints(
                leftEye = FacePoint(10f, 10f),
                rightEye = FacePoint(10f, 10f),
                nose = FacePoint(10f, 20f),
            )
        )

        assertTrue((result as QualityResult.Invalid).reason.startsWith("interEye=0.0px < 20.0"))
    }

    private companion object {
        const val MIN_FACE_CROP_PX = 32
    }
}
