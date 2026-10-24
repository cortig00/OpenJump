package com.openjump.app.camera

import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime proof of the shared Recorder choices: no main-thread executor override (CameraX
 * keeps its IO executor), the CAMCORDER_PROFILE capabilities default shared with discovery,
 * no target bitrate override, and exactly the requested quality with no fallback strategy.
 *
 * The requested quality is observed through the public [Recorder.getQualitySelector]
 * representation compared against the equivalent expected selector. Limit: this pins the
 * requested selector, not the runtime-resolved resolution, which depends on the
 * manufacturer-declared profiles the device reports at bind time.
 */
@RunWith(AndroidJUnit4::class)
class CameraXRecorderConfigInstrumentedTest {
    @Test
    fun recorderKeepsDefaultsAndExactQuality() {
        mapOf("HD" to Quality.HD, "FHD" to Quality.FHD).forEach { (name, quality) ->
            val recorder: Recorder = CameraXRecorderConfig.newRecorder(name)
            assertNull(recorder.executor)
            assertEquals(
                Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE,
                recorder.videoCapabilitiesSource,
            )
            assertEquals(
                CameraXRecorderConfig.videoCapabilitiesSource,
                recorder.videoCapabilitiesSource,
            )
            // No setTargetVideoEncodingBitRate override: 0 is VideoSpec.BITRATE_UNSPECIFIED
            // in CameraX 1.6.2 (VideoSpec itself is library-restricted, so compare to 0).
            assertEquals(0, recorder.targetVideoEncodingBitRate)
            assertEquals(
                QualitySelector.from(quality).toString(),
                recorder.qualitySelector.toString(),
            )
        }
    }

    @Test
    fun invalidQualityFailsFastBeforeConstruction() {
        assertThrows(IllegalArgumentException::class.java) { CameraXRecorderConfig.newRecorder("UHD") }
        assertThrows(IllegalArgumentException::class.java) { CameraXRecorderConfig.newRecorder("") }
        assertThrows(IllegalArgumentException::class.java) { CameraXRecorderConfig.newRecorder("FHD ") }
        assertThrows(IllegalArgumentException::class.java) { CameraXRecorderConfig.newRecorder("hd") }
    }
}
