package com.openjump.app.camera

import kotlin.math.abs

/** Cadence is measured from real PTS deltas; nominal container FPS is not used. */
object RecordingCadencePolicy {
    fun shouldClearWarning(state: JumpCameraState): Boolean = state in setOf(
        JumpCameraState.BINDING,
        JumpCameraState.STARTING,
        JumpCameraState.RECORDING,
    )

    fun observedFps(medianDeltaUs: Long): Double =
        if (medianDeltaUs > 0L) 1_000_000.0 / medianDeltaUs else 0.0

    fun isDegraded(
        requestedFps: Int,
        medianDeltaUs: Long,
        gapCount: Int,
        tolerance: Double = 0.05,
    ): Boolean {
        val observed = observedFps(medianDeltaUs)
        return gapCount > 0 || observed <= 0.0 ||
            abs(observed - requestedFps) / requestedFps > tolerance
    }
}
