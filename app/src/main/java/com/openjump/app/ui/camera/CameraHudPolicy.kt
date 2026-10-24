package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState

/** Presentation only. Capture actions continue to be governed by CameraRecordControlPolicy. */
internal data class CameraHudVisibility(
    val showStep: Boolean,
    val showFps: Boolean,
    val showGuidance: Boolean,
    val showRecordControl: Boolean,
    val showStatus: Boolean,
)

internal object CameraHudPolicy {
    fun forState(state: JumpCameraState): CameraHudVisibility = when (state) {
        JumpCameraState.READY -> CameraHudVisibility(true, true, true, true, false)
        JumpCameraState.RECORDING -> CameraHudVisibility(false, false, false, true, true)
        // The shared preparation overlay appears only if FINALIZING persists beyond the grace period.
        JumpCameraState.FINALIZING -> CameraHudVisibility(false, false, false, false, false)
        JumpCameraState.STARTING -> CameraHudVisibility(false, false, false, true, true)
        JumpCameraState.BINDING -> CameraHudVisibility(true, false, false, true, true)
        // BLOCKED without camera permission keeps quick controls hidden; the retry
        // path re-requests permission instead of offering FPS/resolution.
        JumpCameraState.BLOCKED ->
            CameraHudVisibility(true, false, false, false, false)
        // ERROR keeps quick FPS/resolution controls visible (no REC, no active caption)
        // so a manual impossible tuple (e.g. 720p rejected by the
        // incompatibility cache while FHD 120 stays valid) can be changed to Auto or
        // another supported tuple before retry. BINDING/STARTING/RECORDING/FINALIZING
        // stay hidden per baseline. No silent FPS/resolution switch and no cache reset here.
        JumpCameraState.ERROR ->
            CameraHudVisibility(true, true, false, false, false)
    }
}
