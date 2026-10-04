package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState

enum class CameraOrientationMode {
    PORTRAIT,
    FULL_SENSOR,
    LOCKED,
}

/** Maps camera lifecycle state to the orientation allowed by the camera UI. */
object CameraOrientationPolicy {
    fun forState(state: JumpCameraState): CameraOrientationMode = when (state) {
        JumpCameraState.STARTING,
        JumpCameraState.RECORDING,
        JumpCameraState.FINALIZING,
        -> CameraOrientationMode.LOCKED
        JumpCameraState.BLOCKED,
        JumpCameraState.BINDING,
        JumpCameraState.READY,
        JumpCameraState.ERROR,
        -> CameraOrientationMode.FULL_SENSOR
    }

    fun afterDispose(): CameraOrientationMode = CameraOrientationMode.PORTRAIT
}
