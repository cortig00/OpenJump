package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState

internal data class CameraRecordControlAvailability(
    val canRecord: Boolean,
    val canStop: Boolean,
)

internal enum class CameraRecordGuidancePhase {
    BEFORE_RECORDING,
    RECORDING,
}

/** Keeps the dominant camera action aligned with operations supported by the controller. */
internal object CameraRecordControlPolicy {
    fun guidancePhase(state: JumpCameraState): CameraRecordGuidancePhase = when (state) {
        JumpCameraState.BLOCKED,
        JumpCameraState.BINDING,
        JumpCameraState.READY,
        JumpCameraState.ERROR,
        -> CameraRecordGuidancePhase.BEFORE_RECORDING
        JumpCameraState.STARTING,
        JumpCameraState.RECORDING,
        JumpCameraState.FINALIZING,
        -> CameraRecordGuidancePhase.RECORDING
    }

    fun forState(state: JumpCameraState): CameraRecordControlAvailability = when (state) {
        JumpCameraState.READY -> CameraRecordControlAvailability(canRecord = true, canStop = false)
        JumpCameraState.STARTING,
        JumpCameraState.RECORDING,
        -> CameraRecordControlAvailability(canRecord = false, canStop = true)
        JumpCameraState.BLOCKED,
        JumpCameraState.BINDING,
        JumpCameraState.FINALIZING,
        JumpCameraState.ERROR,
        -> CameraRecordControlAvailability(canRecord = false, canStop = false)
    }
}
