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
        JumpCameraState.BLOCKED, JumpCameraState.ERROR ->
            CameraHudVisibility(true, false, false, false, false)
    }
}
