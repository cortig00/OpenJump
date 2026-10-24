package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F033: production information gate ([shouldShowCameraInformation], used by CameraScreen).
 * The on-demand tips panel is available in READY and ERROR with permissions granted,
 * and hidden during any active take.
 */
class CameraInformationGateTest {
    @Test fun `ready and error offer information with permissions granted`() {
        assertTrue(shouldShowCameraInformation(true, true, JumpCameraState.READY))
        assertTrue(shouldShowCameraInformation(true, true, JumpCameraState.ERROR))
    }

    @Test fun `active take hides information even when requested`() {
        assertFalse(shouldShowCameraInformation(true, true, JumpCameraState.STARTING))
        assertFalse(shouldShowCameraInformation(true, true, JumpCameraState.RECORDING))
        assertFalse(shouldShowCameraInformation(true, true, JumpCameraState.FINALIZING))
    }

    @Test fun `blocked and binding never offer information`() {
        assertFalse(shouldShowCameraInformation(true, true, JumpCameraState.BLOCKED))
        assertFalse(shouldShowCameraInformation(true, true, JumpCameraState.BINDING))
    }

    @Test fun `dismissed hint or missing permission hides information`() {
        assertFalse(shouldShowCameraInformation(false, true, JumpCameraState.READY))
        assertFalse(shouldShowCameraInformation(false, true, JumpCameraState.ERROR))
        assertFalse(shouldShowCameraInformation(true, false, JumpCameraState.READY))
        assertFalse(shouldShowCameraInformation(true, false, JumpCameraState.ERROR))
    }
}
