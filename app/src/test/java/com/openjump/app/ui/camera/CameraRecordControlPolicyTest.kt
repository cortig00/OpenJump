package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecordControlPolicyTest {
    @Test
    fun `every camera state maps to its recording guidance phase`() {
        val expected = mapOf(
            JumpCameraState.BLOCKED to CameraRecordGuidancePhase.BEFORE_RECORDING,
            JumpCameraState.BINDING to CameraRecordGuidancePhase.BEFORE_RECORDING,
            JumpCameraState.READY to CameraRecordGuidancePhase.BEFORE_RECORDING,
            JumpCameraState.STARTING to CameraRecordGuidancePhase.RECORDING,
            JumpCameraState.RECORDING to CameraRecordGuidancePhase.RECORDING,
            JumpCameraState.FINALIZING to CameraRecordGuidancePhase.RECORDING,
            JumpCameraState.ERROR to CameraRecordGuidancePhase.BEFORE_RECORDING,
        )

        expected.forEach { (state, phase) ->
            assertEquals(phase, CameraRecordControlPolicy.guidancePhase(state))
        }
    }

    @Test
    fun `every camera state exposes only its supported record action`() {
        val expected = mapOf(
            JumpCameraState.BLOCKED to CameraRecordControlAvailability(canRecord = false, canStop = false),
            JumpCameraState.BINDING to CameraRecordControlAvailability(canRecord = false, canStop = false),
            JumpCameraState.READY to CameraRecordControlAvailability(canRecord = true, canStop = false),
            JumpCameraState.STARTING to CameraRecordControlAvailability(canRecord = false, canStop = true),
            JumpCameraState.RECORDING to CameraRecordControlAvailability(canRecord = false, canStop = true),
            JumpCameraState.FINALIZING to CameraRecordControlAvailability(canRecord = false, canStop = false),
            JumpCameraState.ERROR to CameraRecordControlAvailability(canRecord = false, canStop = false),
        )

        expected.forEach { (state, availability) ->
            assertEquals(availability, CameraRecordControlPolicy.forState(state))
        }
    }
}
