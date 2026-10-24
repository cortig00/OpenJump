package com.openjump.app.camera

import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder

/**
 * Shared CameraX Recorder choices for the product profiles ("HD"/"FHD").
 *
 * The runtime Recorder keeps the CameraX defaults: background encode/mux work runs on the
 * CameraX IO executor (no [Recorder.Builder.setExecutor] override) and quality resolution
 * consults the [Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE] source (no
 * `setVideoCapabilitiesSource` override). Discovery queries this same shared source so the
 * advertised qualities come from the same manufacturer-declared profile set the Recorder
 * consults at bind time. Those profiles are manufacturer-declared, not a runtime guarantee:
 * whether a device satisfies one while recording is decided by the device at bind time.
 * Recording-event callbacks (`PendingRecording.start`) intentionally stay on the main
 * executor at the call site in [JumpCameraController].
 */
internal object CameraXRecorderConfig {
    const val videoCapabilitiesSource: Int =
        Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE

    /**
     * Runtime factory: the QualitySelector is the sole quality input; executor, bitrate,
     * and capabilities source stay at their CameraX defaults. Unknown product quality
     * names fail fast instead of silently mapping to a different recording quality.
     */
    fun newRecorder(profileQuality: String): Recorder {
        val quality = when (profileQuality) {
            "HD" -> Quality.HD
            "FHD" -> Quality.FHD
            else -> throw IllegalArgumentException("Unsupported product quality: $profileQuality")
        }
        return Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
    }
}
