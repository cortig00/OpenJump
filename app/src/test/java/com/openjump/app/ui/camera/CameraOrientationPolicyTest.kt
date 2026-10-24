package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationPolicyTest {
    @Test
    fun `camera stays flexible until a take starts`() {
        assertEquals(
            CameraOrientationMode.FULL_SENSOR,
            CameraOrientationPolicy.forState(JumpCameraState.BLOCKED),
        )
        assertEquals(
            CameraOrientationMode.FULL_SENSOR,
            CameraOrientationPolicy.forState(JumpCameraState.BINDING),
        )
        assertEquals(
            CameraOrientationMode.FULL_SENSOR,
            CameraOrientationPolicy.forState(JumpCameraState.READY),
        )
        assertEquals(
            CameraOrientationMode.FULL_SENSOR,
            CameraOrientationPolicy.forState(JumpCameraState.ERROR),
        )
    }

    @Test
    fun `orientation is locked for every active take state`() {
        assertEquals(
            CameraOrientationMode.LOCKED,
            CameraOrientationPolicy.forState(JumpCameraState.STARTING),
        )
        assertEquals(
            CameraOrientationMode.LOCKED,
            CameraOrientationPolicy.forState(JumpCameraState.RECORDING),
        )
        assertEquals(
            CameraOrientationMode.LOCKED,
            CameraOrientationPolicy.forState(JumpCameraState.FINALIZING),
        )
    }

    @Test
    fun `disposal restores portrait`() {
        assertEquals(CameraOrientationMode.PORTRAIT, CameraOrientationPolicy.afterDispose())
    }
}
