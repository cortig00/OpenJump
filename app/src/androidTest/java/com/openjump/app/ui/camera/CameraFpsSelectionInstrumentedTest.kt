package com.openjump.app.ui.camera

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.RecordingFpsPolicy
import com.openjump.app.settings.RecordingUse
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless regression for the transient camera FPS override.
 *
 * Saved defaults (Jumps 240 / Encoder 60 in an isolated prefs file) seed the real
 * [rememberCameraFpsSelection] + [CameraQuickFpsSelector] host. Camera picks must change only
 * the transient selection and never write back to prefs; hiding/showing the component
 * (next visit) or switching [RecordingUse] re-seeds from the saved default.
 * Never touches production `openjump_settings`.
 */
@RunWith(AndroidJUnit4::class)
class CameraFpsSelectionInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() =
        appContext.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)

    @Before
    @After
    fun resetSandbox() {
        prefs.edit().clear().commit()
    }

    private fun seedJumps240Encoder60() {
        prefs.edit()
            .putInt(RecordingFpsPolicy.JUMPS_KEY, 240)
            .putInt(RecordingFpsPolicy.ENCODER_KEY, 60)
            .putBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, true)
            .putInt(RecordingFpsPolicy.LEGACY_KEY, 240)
            .putString(UNRELATED_KEY, UNRELATED_VALUE)
            .commit()
    }

    private fun fixedLabel(fps: Int): String =
        compose.activity.getString(R.string.camera_fps_quick_fixed, fps)

    private fun autoLabel(): String =
        compose.activity.getString(R.string.camera_fps_auto)

    private fun pickOption(collapsedText: String, optionText: String) {
        compose.onNodeWithText(collapsedText).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(optionText).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun assertStoredDefaultsUnchanged() {
        assertEquals(240, CameraSettings(prefs).getPreferredFps(RecordingUse.JUMPS))
        assertEquals(60, CameraSettings(prefs).getPreferredFps(RecordingUse.ENCODER))
        assertEquals(240, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, MISSING))
        assertEquals(60, prefs.getInt(RecordingFpsPolicy.ENCODER_KEY, MISSING))
        assertEquals(240, prefs.getInt(RecordingFpsPolicy.LEGACY_KEY, MISSING))
        assertEquals(UNRELATED_VALUE, prefs.getString(UNRELATED_KEY, null))
    }

    @Test
    fun jumpsPickIsTransientAndNextVisitRestoresDefault() {
        seedJumps240Encoder60()
        val showState = mutableStateOf(true)
        val useState = mutableStateOf(RecordingUse.JUMPS)
        val sandboxPrefs = prefs
        compose.setContent {
            OpenJumpTheme {
                val settings = remember { CameraSettings(sandboxPrefs) }
                if (showState.value) {
                    var selected by rememberCameraFpsSelection(settings, useState.value)
                    CameraQuickFpsSelector(
                        selectedFps = selected,
                        activeFps = null,
                        availableFps = listOf(240, 120, 60),
                        unavailableFps = emptySet(),
                        onSelect = { selected = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        // Next-visit seed: Jumps default 240.
        compose.onNodeWithText(fixedLabel(240)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Transient override to 120: selection changes, saved defaults do not.
        pickOption(fixedLabel(240), fixedLabel(120))
        compose.onNodeWithText(fixedLabel(120)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Hide/show = next visit: transient 120 is dropped, default 240 returns.
        compose.runOnIdle { showState.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showState.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(fixedLabel(240)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Switching use re-seeds from the Encoder saved default.
        compose.runOnIdle { useState.value = RecordingUse.ENCODER }
        compose.waitForIdle()
        compose.onNodeWithText(fixedLabel(60)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()
    }

    @Test
    fun encoderTransientPicksLeaveSavedDefaultAndReturnRestoresJumps() {
        seedJumps240Encoder60()
        val showState = mutableStateOf(true)
        val useState = mutableStateOf(RecordingUse.JUMPS)
        val sandboxPrefs = prefs
        compose.setContent {
            OpenJumpTheme {
                val settings = remember { CameraSettings(sandboxPrefs) }
                if (showState.value) {
                    var selected by rememberCameraFpsSelection(settings, useState.value)
                    CameraQuickFpsSelector(
                        selectedFps = selected,
                        activeFps = null,
                        availableFps = listOf(240, 120, 60),
                        unavailableFps = emptySet(),
                        onSelect = { selected = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(fixedLabel(240)).assertIsDisplayed()

        // Encoder visit starts from its own saved default 60.
        compose.runOnIdle { useState.value = RecordingUse.ENCODER }
        compose.waitForIdle()
        compose.onNodeWithText(fixedLabel(60)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Temporary Auto leaves saved Encoder 60 untouched.
        pickOption(fixedLabel(60), autoLabel())
        compose.onNodeWithText(autoLabel()).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Temporary 120 also leaves saved Encoder 60 untouched.
        pickOption(autoLabel(), fixedLabel(120))
        compose.onNodeWithText(fixedLabel(120)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()

        // Switching back starts again from the Jumps saved default 240.
        compose.runOnIdle { useState.value = RecordingUse.JUMPS }
        compose.waitForIdle()
        compose.onNodeWithText(fixedLabel(240)).assertIsDisplayed()
        assertStoredDefaultsUnchanged()
    }

    private companion object {
        const val SANDBOX_NAME = "camera-fps-selection-sandbox"
        const val UNRELATED_KEY = "unrelated_probe"
        const val UNRELATED_VALUE = "retain"
        const val MISSING = -999
    }
}
