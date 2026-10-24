package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focal policy for the F3 stale-error fix: entering BINDING (like READY) drops
 * the obsolete preview failure, while a fresh ERROR keeps its message.
 * The screen's onStateChanged delegates to [shouldClearStaleCameraError]; the
 * production onError callback still lands after onStateChanged(ERROR).
 */
class CameraStaleErrorPolicyTest {

    @Test fun bindingClearsStaleError() {
        assertTrue(shouldClearStaleCameraError(JumpCameraState.BINDING))
    }

    @Test fun readyClearsStaleError() {
        assertTrue(shouldClearStaleCameraError(JumpCameraState.READY))
    }

    @Test fun freshErrorKeepsMessage() {
        assertFalse(shouldClearStaleCameraError(JumpCameraState.ERROR))
    }

    @Test fun otherStatesKeepMessage() {
        assertFalse(shouldClearStaleCameraError(JumpCameraState.BLOCKED))
        assertFalse(shouldClearStaleCameraError(JumpCameraState.STARTING))
        assertFalse(shouldClearStaleCameraError(JumpCameraState.RECORDING))
        assertFalse(shouldClearStaleCameraError(JumpCameraState.FINALIZING))
    }
}
