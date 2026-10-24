package com.openjump.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sandbox storage coverage for the split recording FPS preferences.
 * Uses an isolated preferences file only; never touches production `openjump_settings`.
 */
@RunWith(AndroidJUnit4::class)
class CameraRecordingSettingsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() =
        context.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)

    @Before
    @After
    fun resetSandbox() {
        prefs.edit().clear().commit()
    }

    @Test
    fun legacyAutoAndProductValuesCopyToBothModesOnceAndPreserveLegacy() {
        listOf(CameraSettings.AUTO, 60, 120, 240).forEach { legacy ->
            prefs.edit().clear().commit()
            prefs.edit().putInt(RecordingFpsPolicy.LEGACY_KEY, legacy).commit()
            assertFalse(prefs.contains(RecordingFpsPolicy.SPLIT_DONE_KEY))

            val fresh = CameraSettings(prefs)
            assertEquals(legacy, fresh.getPreferredFps(RecordingUse.JUMPS))
            assertEquals(legacy, fresh.getPreferredFps(RecordingUse.ENCODER))
            assertEquals(legacy, fresh.jumpPreferredFps)
            assertEquals(legacy, fresh.encoderPreferredFps)

            // Copied BOTH once, legacy preserved, split flagged.
            assertEquals(legacy, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, MISSING))
            assertEquals(legacy, prefs.getInt(RecordingFpsPolicy.ENCODER_KEY, MISSING))
            assertTrue(prefs.getBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, false))
            assertEquals(legacy, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))

            // Reopening with a fresh instance keeps the same values without further writes.
            val reopened = CameraSettings(prefs)
            assertEquals(legacy, reopened.jumpPreferredFps)
            assertEquals(legacy, reopened.encoderPreferredFps)
            assertEquals(legacy, reopened.preferredFps)
        }
    }

    @Test
    fun freshInstallDefaultsToAutoAuto() {
        val fresh = CameraSettings(prefs)
        assertEquals(CameraSettings.AUTO, fresh.getPreferredFps(RecordingUse.JUMPS))
        assertEquals(CameraSettings.AUTO, fresh.getPreferredFps(RecordingUse.ENCODER))
        assertTrue(prefs.getBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, false))
        assertEquals(CameraSettings.AUTO, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, MISSING))
        assertEquals(CameraSettings.AUTO, prefs.getInt(RecordingFpsPolicy.ENCODER_KEY, MISSING))
        val reopened = CameraSettings(prefs)
        assertEquals(CameraSettings.AUTO, reopened.jumpPreferredFps)
        assertEquals(CameraSettings.AUTO, reopened.encoderPreferredFps)
    }

    @Test
    fun firstWriteSnapshotsLegacyToBothBeforeOverwritingOnlySelectedMode() {
        // Jump-first: write before any read.
        prefs.edit().clear().commit()
        prefs.edit().putInt(RecordingFpsPolicy.LEGACY_KEY, 120).commit()
        CameraSettings(prefs).setPreferredFps(RecordingUse.JUMPS, 60)
        assertEquals(60, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(120, CameraSettings(prefs).encoderPreferredFps)
        assertEquals(120, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))
        assertTrue(prefs.getBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, false))

        // Encoder-first: write before any read, opposite direction.
        prefs.edit().clear().commit()
        prefs.edit().putInt(RecordingFpsPolicy.LEGACY_KEY, 120).commit()
        CameraSettings(prefs).setPreferredFps(RecordingUse.ENCODER, 240)
        assertEquals(120, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(240, CameraSettings(prefs).encoderPreferredFps)
        assertEquals(120, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))
    }

    @Test
    fun freshInstancesStayIndependentAfterBothDirectionsAndResetToAuto() {
        val first = CameraSettings(prefs)
        assertEquals(CameraSettings.AUTO, first.jumpPreferredFps)
        assertEquals(CameraSettings.AUTO, first.encoderPreferredFps)

        CameraSettings(prefs).setPreferredFps(RecordingUse.JUMPS, 120)
        assertEquals(120, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(CameraSettings.AUTO, CameraSettings(prefs).encoderPreferredFps)

        CameraSettings(prefs).setPreferredFps(RecordingUse.ENCODER, 60)
        assertEquals(120, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(60, CameraSettings(prefs).encoderPreferredFps)

        // Repeated fresh instances observe the same independent values.
        repeat(3) {
            assertEquals(120, CameraSettings(prefs).jumpPreferredFps)
            assertEquals(60, CameraSettings(prefs).encoderPreferredFps)
        }

        // Reset-to-Auto is per mode and survives a fresh instance.
        CameraSettings(prefs).setPreferredFps(RecordingUse.JUMPS, CameraSettings.AUTO)
        assertEquals(CameraSettings.AUTO, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(60, CameraSettings(prefs).encoderPreferredFps)

        CameraSettings(prefs).setPreferredFps(RecordingUse.ENCODER, 240)
        assertEquals(CameraSettings.AUTO, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(240, CameraSettings(prefs).encoderPreferredFps)

        val reopened = CameraSettings(prefs)
        assertEquals(CameraSettings.AUTO, reopened.jumpPreferredFps)
        assertEquals(240, reopened.encoderPreferredFps)
    }

    @Test
    fun migrationAndWritesPreserveLegacyAndUnrelatedPreferences() {
        prefs.edit().clear().commit()
        prefs.edit()
            .putInt(RecordingFpsPolicy.LEGACY_KEY, 60)
            .putBoolean("jump_recording_guide_seen", true)
            .putString("unrelated_probe", "retain")
            .commit()

        val settings = CameraSettings(prefs)
        assertEquals(60, settings.jumpPreferredFps)
        settings.setPreferredFps(RecordingUse.JUMPS, 240)

        assertEquals(240, CameraSettings(prefs).jumpPreferredFps)
        assertEquals(60, CameraSettings(prefs).encoderPreferredFps)
        // Legacy seed and unrelated values are unchanged.
        assertEquals(60, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))
        assertTrue(prefs.getBoolean("jump_recording_guide_seen", false))
        assertEquals("retain", prefs.getString("unrelated_probe", null))
        assertTrue(prefs.getBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, false))
    }

    @Test
    fun invalid480RawIsPreservedButNormalizesToAutoWithExistingPolicy() {
        prefs.edit().clear().commit()
        prefs.edit().putInt(RecordingFpsPolicy.LEGACY_KEY, 480).commit()

        val settings = CameraSettings(prefs)
        val jumpsRaw = settings.getPreferredFps(RecordingUse.JUMPS)
        val encoderRaw = settings.getPreferredFps(RecordingUse.ENCODER)
        assertEquals(480, jumpsRaw)
        assertEquals(480, encoderRaw)
        assertEquals(480, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, MISSING))
        assertEquals(480, prefs.getInt(RecordingFpsPolicy.ENCODER_KEY, MISSING))
        assertEquals(480, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))

        assertEquals(
            CameraSettings.AUTO,
            CameraSettings.normalizePreferredFps(jumpsRaw, CameraSettings.PRODUCT_FPS),
        )
        assertEquals(
            CameraSettings.AUTO,
            CameraSettings.normalizePreferredFps(encoderRaw, CameraSettings.PRODUCT_FPS),
        )
        assertEquals(
            CameraSettings.AUTO,
            CameraSettings.normalizePreferredFps(encoderRaw, listOf(60, 120, 240)),
        )
    }

    @Test
    fun concurrentFirstWritesFromFreshInstancesPreserveBothSelections() {
        repeat(20) {
            prefs.edit().clear().putInt(RecordingFpsPolicy.LEGACY_KEY, 60).commit()
            val start = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val threads = listOf(RecordingUse.JUMPS to 120, RecordingUse.ENCODER to 240).map { (use, fps) ->
                Thread {
                    try {
                        check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        CameraSettings(prefs).setPreferredFps(use, fps)
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join(5_000); assertFalse("Writer must terminate", it.isAlive) }
            failure.get()?.let { throw AssertionError("Concurrent write failed", it) }
            assertEquals(120, CameraSettings(prefs).jumpPreferredFps)
            assertEquals(240, CameraSettings(prefs).encoderPreferredFps)
            assertEquals(60, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))
        }
    }

    private companion object {
        const val SANDBOX_NAME = "camera-recording-sandbox-storage"
        const val MISSING = -999
    }
}
