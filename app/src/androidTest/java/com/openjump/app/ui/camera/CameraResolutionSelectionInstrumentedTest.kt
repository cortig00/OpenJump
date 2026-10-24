package com.openjump.app.ui.camera

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.camera.CameraProductProfile
import com.openjump.app.camera.CameraProfilePolicy
import com.openjump.app.camera.CameraResolutionPreference
import com.openjump.app.camera.CameraSessionKind
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.RecordingFpsPolicy
import com.openjump.app.settings.RecordingUse
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resolution is transient per camera visit (AUTO on entry, never persisted) and the
 * grouped FPS + resolution row exposes requested/active semantics with stable tags.
 * Never touches production `openjump_settings`.
 */
@RunWith(AndroidJUnit4::class)
class CameraResolutionSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = appContext.getSharedPreferences(SANDBOX, Context.MODE_PRIVATE)

    @Before
    @After
    fun resetSandbox() {
        prefs.edit().clear().commit()
    }

    private fun seed() {
        prefs.edit()
            .putInt(RecordingFpsPolicy.JUMPS_KEY, 240)
            .putInt(RecordingFpsPolicy.ENCODER_KEY, 60)
            .putBoolean(RecordingFpsPolicy.SPLIT_DONE_KEY, true)
            .putInt(RecordingFpsPolicy.LEGACY_KEY, 240)
            .commit()
    }

    private fun autoRes(): String = compose.activityRuleSafeString(R.string.camera_resolution_auto)

    @Test
    fun resolutionIsTransientAutoAndLeavesPrefsUntouched() {
        seed()
        val show = mutableStateOf(true)
        val use = mutableStateOf(RecordingUse.JUMPS)
        compose.setContent {
            OpenJumpTheme {
                if (show.value) {
                    var res by rememberCameraResolutionSelection(use.value)
                    CameraQuickResolutionSelector(selected = res, onSelect = { res = it })
                }
            }
        }
        compose.waitForIdle()
        // Default AUTO on entry.
        compose.onNodeWithTag("camera-resolution-selector").assertIsDisplayed()
        compose.onNodeWithText(autoRes()).assertIsDisplayed()
        // Transient pick does not write prefs.
        compose.onNodeWithText(autoRes()).performClick()
        compose.waitForIdle()
        val hd = context.getString(R.string.camera_resolution_720p)
        compose.onNodeWithText(hd).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(hd).assertIsDisplayed()
        assertEquals(240, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, -1))
        assertEquals(60, prefs.getInt(RecordingFpsPolicy.ENCODER_KEY, -1))
        // Next visit resets to AUTO.
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { show.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(autoRes()).assertIsDisplayed()
        assertEquals(240, prefs.getInt(RecordingFpsPolicy.JUMPS_KEY, -1))
    }

    @Test
    fun groupedRowExposesTagsRequestedSemanticsAndActiveCaption() {
        val profile = CameraProductProfile("rear", "HD", 1280, 720, 120, CameraSessionKind.HIGH_SPEED)
        val expectedActive = context.getString(
            R.string.camera_active_profile,
            context.getString(R.string.camera_profile_label, 720, 120),
        )
        compose.setContent {
            OpenJumpTheme {
                Column {
                    CameraQuickFpsSelector(
                        selectedFps = CameraSettings.AUTO,
                        activeFps = 120,
                        availableFps = listOf(240, 120, 60),
                        unavailableFps = emptySet(),
                        onSelect = {},
                    )
                    CameraQuickResolutionSelector(
                        selected = CameraResolutionPreference.HD_720P,
                        onSelect = {},
                    )
                    CameraActiveProfileCaption(profile = profile)
                }
            }
        }
        compose.onNodeWithTag("camera-fps-selector").assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("camera-resolution-selector").assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("camera-active-profile").assertIsDisplayed()
        compose.onNodeWithText(expectedActive).assertIsDisplayed()
        // Requested pills show what was asked; content-descriptions carry requested semantics.
        val auto = context.getString(R.string.camera_fps_auto)
        val expectedFpsLabel = context.getString(R.string.camera_fps_quick_auto, auto, 120)
        val expectedFpsRequested = context.getString(R.string.camera_requested_fps, expectedFpsLabel)
        val expectedResLabel = context.getString(R.string.camera_resolution_720p)
        val expectedResRequested = context.getString(R.string.camera_requested_resolution, expectedResLabel)
        compose.onNodeWithText(expectedFpsLabel).assertIsDisplayed()
        compose.onNodeWithText(expectedResLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(expectedFpsRequested).assertIsDisplayed()
        compose.onNodeWithContentDescription(expectedResRequested).assertIsDisplayed()
    }

    @Test
    fun impossibleResolutionIsDisabledNotSilentlySwitched() {
        var selected = CameraResolutionPreference.AUTO
        compose.setContent {
            OpenJumpTheme {
                CameraQuickResolutionSelector(
                    selected = selected,
                    onSelect = { selected = it },
                    // Simulate 720p impossible at the exact selected FPS: disabled.
                    isEnabled = { it != CameraResolutionPreference.HD_720P },
                )
            }
        }
        compose.onNodeWithText(autoRes()).assertIsDisplayed().performClick()
        compose.waitForIdle()
        val hd = context.getString(R.string.camera_resolution_720p)
        compose.onNodeWithText(hd).assertIsDisplayed().assertIsNotEnabled()
        // Disabled option cannot be picked; selection stays AUTO.
        assertEquals(CameraResolutionPreference.AUTO, selected)
    }

    @Test
    fun impossibleFpsIsDisabledNotSilentlySwitched() {
        // FPS side: globally unavailable FPS stays disabled.
        var fps: Int? = null
        compose.setContent {
            OpenJumpTheme {
                CameraQuickFpsSelector(
                    selectedFps = CameraSettings.AUTO,
                    activeFps = null,
                    availableFps = listOf(120, 60),
                    unavailableFps = setOf(120),
                    onSelect = { fps = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(autoResFps()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.camera_fps_unavailable_chip, "120"))
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.camera_fps_quick_fixed, 60)).performClick()
        compose.waitForIdle()
        assertEquals(60, fps)
    }

    @Test
    fun scopedFpsUnavailabilityUsesGenericLabelNotPreviewCause() {
        compose.setContent {
            OpenJumpTheme {
                CameraQuickFpsSelector(
                    selectedFps = CameraSettings.AUTO,
                    activeFps = null,
                    availableFps = listOf(120, 60),
                    unavailableFps = emptySet(),
                    scopedUnavailableFps = setOf(120),
                    onSelect = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(autoResFps()).performClick()
        compose.waitForIdle()
        // Scoped (resolution) unavailability must not claim the preview failed.
        compose.onNodeWithText(context.getString(R.string.camera_resolution_unavailable_chip, "120"))
            .assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun compactCaptureGroupKeepsAccessibleSelectorsWithoutPermanentLabels() {
        val profile = CameraProductProfile("rear", "HD", 1280, 720, 120, CameraSessionKind.HIGH_SPEED)
        compose.setContent {
            OpenJumpTheme {
                CameraCaptureControlsGroup(
                    selectedFps = CameraSettings.AUTO,
                    activeFps = 120,
                    fpsChoices = listOf(240, 120, 60),
                    previewUnavailableFps = emptySet(),
                    scopedUnavailableFps = emptySet(),
                    selectedResolution = CameraResolutionPreference.AUTO,
                    isResolutionEnabled = { true },
                    activeProfile = profile,
                    onFpsSelect = {},
                    onResolutionSelect = {},
                    onHintClick = {},
                    hintDescriptionRes = R.string.camera_capture_tips,
                )
            }
        }
        compose.onNodeWithTag("camera-fps-selector").assertIsDisplayed()
        compose.onNodeWithTag("camera-resolution-selector").assertIsDisplayed()
        // The bound profile is now on demand, not another permanent viewfinder label.
        compose.onAllNodesWithTag("camera-active-profile").assertCountEquals(0)
        compose.onNodeWithTag("camera-fps-selector").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("camera-resolution-selector").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(
            context.getString(R.string.camera_requested_resolution, autoRes()),
        ).assertHasClickAction()
    }

    /**
     * Component-only ERROR recovery: an impossible manual 720p@120 keeps Auto and
     * FHD 120 enabled via the real [CameraProfilePolicy], with null active profile
     * (no caption, no REC). Real callbacks/state, no fake recorder. Limited to
     * Compose components; not a full hardware/binder proof.
     */
    @Test
    fun errorRecoveryKeepsAutoEnabledAndFhdUsableWhen720Impossible() {
        val available = listOf(
            CameraProductProfile("rear", "FHD", 1920, 1080, 120, CameraSessionKind.NORMAL),
            CameraProductProfile("rear", "HD", 1280, 720, 60, CameraSessionKind.NORMAL),
        )
        val fpsChoices = listOf(240, 120, 60)
        val manualFps = 120
        val manualRes = CameraResolutionPreference.HD_720P
        // Exact 720p@120 is impossible in this catalog.
        assertTrue(CameraProfilePolicy.candidates(available, manualFps, manualRes).isEmpty())
        // Recovery stays usable: Auto always enabled and FHD 120 valid via policy.
        assertTrue(
            CameraProfilePolicy.candidates(
                available,
                manualFps,
                CameraResolutionPreference.FHD_1080P,
            ).isNotEmpty(),
        )
        val resState = mutableStateOf(manualRes)
        var fpsCallback: Int? = null
        compose.setContent {
            OpenJumpTheme {
                val currentScoped = fpsChoices.filter { fps ->
                    CameraProfilePolicy.candidates(available, fps, resState.value).isEmpty()
                }.toSet()
                CameraCaptureControlsGroup(
                    selectedFps = manualFps,
                    activeFps = null,
                    fpsChoices = fpsChoices,
                    previewUnavailableFps = emptySet(),
                    scopedUnavailableFps = currentScoped,
                    selectedResolution = resState.value,
                    isResolutionEnabled = { option ->
                        if (option == CameraResolutionPreference.AUTO) true
                        else CameraProfilePolicy.candidates(available, manualFps, option).isNotEmpty()
                    },
                    activeProfile = null,
                    onFpsSelect = { fpsCallback = it },
                    onResolutionSelect = { resState.value = it },
                    onHintClick = {},
                    hintDescriptionRes = R.string.camera_capture_tips,
                )
            }
        }
        compose.waitForIdle()
        // Group stays visible in ERROR (no REC, no active caption).
        compose.onNodeWithTag("camera-fps-selector").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("camera-resolution-selector").assertIsDisplayed().assertHasClickAction()
        compose.onAllNodesWithTag("camera-active-profile").assertCountEquals(0)
        // Scoped FPS at 720 keeps the exact impossible value disabled with the
        // generic resolution label, not a preview-failure claim.
        compose.onNodeWithTag("camera-fps-selector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.camera_resolution_unavailable_chip, "120"))
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.camera_fps_quick_fixed, 60)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(60, fpsCallback)
        }
        // Manual recovery: Auto is visible and enabled; FHD usable, 720 disabled.
        compose.onNodeWithTag("camera-resolution-selector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(autoRes()).assertIsDisplayed().assertIsEnabled()
        val fhd = context.getString(R.string.camera_resolution_1080p)
        val hd = context.getString(R.string.camera_resolution_720p)
        compose.onNodeWithText(fhd).assertIsDisplayed().assertIsEnabled()
        // Collapsed 720p button and disabled dropdown 720p share the same visual label:
        // select the disabled popup item uniquely via text + disabled matcher.
        compose.onNode(hasText(hd) and !isEnabled()).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(autoRes()).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(CameraResolutionPreference.AUTO, resState.value)
        }
    }

    private fun autoResFps(): String = context.getString(R.string.camera_fps_auto)

    private companion object {
        const val SANDBOX = "camera-resolution-selection-sandbox"
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.activityRuleSafeString(res: Int): String =
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(res)
