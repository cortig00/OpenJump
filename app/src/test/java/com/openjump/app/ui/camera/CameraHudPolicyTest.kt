package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHudPolicyTest {
    @Test fun readyKeepsQuickControlsButNoPermanentStatus() {
        val hud = CameraHudPolicy.forState(JumpCameraState.READY)
        assertTrue(hud.showStep)
        assertTrue(hud.showFps)
        assertTrue(hud.showGuidance)
        assertTrue(hud.showRecordControl)
        assertFalse(hud.showStatus)
    }

    @Test fun recordingLeavesOnlyRecordControlAndStatus() {
        val hud = CameraHudPolicy.forState(JumpCameraState.RECORDING)
        assertFalse(hud.showStep)
        assertFalse(hud.showFps)
        assertFalse(hud.showGuidance)
        assertTrue(hud.showRecordControl)
        assertTrue(hud.showStatus)
    }

    @Test fun finalizingHasNoImmediateSpinnerOrIncompatibleActions() {
        val hud = CameraHudPolicy.forState(JumpCameraState.FINALIZING)
        assertFalse(hud.showFps)
        assertFalse(hud.showGuidance)
        assertFalse(hud.showRecordControl)
        assertFalse(hud.showStatus)
    }

    @Test fun bindingBlockedAndCapturingNeverOfferFps() {
        assertFalse(CameraHudPolicy.forState(JumpCameraState.BINDING).showFps)
        assertFalse(CameraHudPolicy.forState(JumpCameraState.BLOCKED).showFps)
        assertFalse(CameraHudPolicy.forState(JumpCameraState.STARTING).showFps)
        assertFalse(CameraHudPolicy.forState(JumpCameraState.RECORDING).showFps)
        assertFalse(CameraHudPolicy.forState(JumpCameraState.FINALIZING).showFps)
    }

    @Test fun errorExposesQuickControlsWithoutRecordAction() {
        // P1 recovery: ERROR keeps FPS/resolution pickers visible so a manual
        // impossible tuple (e.g. 720p rejected while FHD 120 stays valid) can be
        // changed to Auto/supported before retry. No REC and no active caption:
        // the record policy stays canRecord=false/canStop=false in ERROR.
        val hud = CameraHudPolicy.forState(JumpCameraState.ERROR)
        assertTrue(hud.showFps)
        assertTrue(hud.showStep)
        assertFalse(hud.showGuidance)
        assertFalse(hud.showRecordControl)
        assertFalse(hud.showStatus)
        val record = CameraRecordControlPolicy.forState(JumpCameraState.ERROR)
        assertFalse(record.canRecord)
        assertFalse(record.canStop)
    }
}
