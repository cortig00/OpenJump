package com.openjump.app.ui.camera

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.camera.CameraProductProfile
import com.openjump.app.camera.CameraResolutionPreference
import com.openjump.app.camera.CameraSessionKind
import com.openjump.app.camera.JumpCameraState
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Real Compose controls/policy in an oriented host, not a fake CameraX recorder. */
@RunWith(AndroidJUnit4::class)
class CameraViewfinderHudInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = CameraProductProfile("rear", "HD", 1280, 720, 30, CameraSessionKind.NORMAL)
    private fun text(id: Int) = compose.activity.getString(id)

    private fun content(landscape: Boolean = false, fontScale: Float = 1f, dark: Boolean = true) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation ==
                if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OpenJumpTheme(darkTheme = dark) {
                    var information by rememberCameraInformationVisibility()
                    var state by remember { mutableStateOf(JumpCameraState.READY) }
                    val hud = CameraHudPolicy.forState(state)
                    Box(Modifier.fillMaxSize().testTag("test-viewfinder")) {
                        CameraViewfinderHud(
                            showControls = hud.showFps,
                            onBack = {},
                            onInformation = { information = !information },
                            informationDescriptionRes = R.string.camera_capture_tips,
                            informationExpanded = information,
                            controls = { includeInformation ->
                                CameraCaptureControlsGroup(
                                    selectedFps = CameraSettings.AUTO, activeFps = 30,
                                    fpsChoices = listOf(240, 120, 60),
                                    previewUnavailableFps = emptySet(), scopedUnavailableFps = setOf(240, 120, 60),
                                    selectedResolution = CameraResolutionPreference.AUTO,
                                    isResolutionEnabled = { it != CameraResolutionPreference.FHD_1080P },
                                    activeProfile = profile,
                                    onFpsSelect = {}, onResolutionSelect = {},
                                    onHintClick = { information = !information },
                                    hintDescriptionRes = R.string.camera_capture_tips,
                                    includeInformationButton = includeInformation,
                                    informationExpanded = information,
                                )
                            },
                            information = if (information && hud.showFps) ({
                                CameraHintOverlay(
                                    guidance = text(R.string.camera_capture_vertical), progress = null,
                                    warnings = emptyList(), profile = profile,
                                    onDismiss = { information = false },
                                )
                            }) else null,
                            record = ({
                                CameraRecordButton(
                                    canRecord = state == JumpCameraState.READY,
                                    canStop = state == JumpCameraState.RECORDING,
                                    onClick = {
                                        information = false
                                        state = if (state == JumpCameraState.READY) JumpCameraState.RECORDING
                                            else JumpCameraState.READY
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }),
                            status = null, blockingError = null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun checkLayout(landscape: Boolean) {
        val root = bounds("test-viewfinder")
        assertEquals(landscape, root.width > root.height)
        val record = bounds("camera-record-position")
        val top = bounds("camera-top-toolbar")
        assertTrue("Toolbar stays above the recording control", top.bottom <= record.top)
        if (landscape) {
            assertTrue("REC on right edge", record.center.x > root.left + root.width * 0.75f)
            assertTrue("REC centered vertically", abs(record.center.y - root.center.y) < root.height * 0.1f)
        } else {
            assertTrue("REC centered horizontally", abs(record.center.x - root.center.x) < root.width * 0.05f)
            assertTrue("REC at bottom", record.bottom > root.top + root.height * 0.85f)
        }
        listOf("camera-back-control", "camera-fps-selector", "camera-resolution-selector").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val b = bounds(it)
            assertTrue("No horizontal clipping: $it", b.left >= root.left && b.right <= root.right)
            assertTrue("Controls at top: $it", b.bottom < root.top + root.height * 0.4f)
        }
        val label = compose.activity.getString(R.string.camera_fps_quick_auto, text(R.string.camera_fps_auto), 30)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        assertFalse("Current FPS must not be ellipsized", results.single().isLineEllipsized(0))
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }

    @Test fun portraitDark100() { content(); checkLayout(false) }
    @Test fun portraitLight150() { content(fontScale = 1.5f, dark = false); checkLayout(false) }
    @Test fun landscapeLight100() { content(landscape = true, dark = false); checkLayout(true) }
    @Test fun landscapeDark150() { content(landscape = true, fontScale = 1.5f); checkLayout(true) }

    @Test fun instructionsStartHiddenAndToggleOrCloseWithoutCoveringControlsOrCenter() {
        content()
        val toggle = compose.onNodeWithContentDescription(text(R.string.camera_capture_tips))
        toggle.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("camera-information-panel").assertIsDisplayed()
        val panel = bounds("camera-information-panel")
        assertTrue(bounds("camera-top-toolbar").top < panel.top)
        assertTrue(bounds("camera-fps-selector").bottom <= panel.top)
        assertFalse("Center of viewfinder remains free", panel.contains(bounds("test-viewfinder").center))
        compose.onNodeWithTag("camera-active-profile").assertIsDisplayed()
        toggle.performClick().assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
        toggle.performClick()
        compose.onNodeWithContentDescription(text(R.string.video_source_close)).performClick()
        toggle.assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }

    @Test fun recordingHidesConfigurationAndInformationAndLeavesOnlyStopAction() {
        content()
        compose.onNodeWithContentDescription(text(R.string.camera_capture_tips)).performClick()
        compose.onNodeWithContentDescription(text(R.string.camera_start_recording)).performClick()
        compose.onAllNodesWithTag("camera-fps-selector").assertCountEquals(0)
        compose.onAllNodesWithTag("camera-resolution-selector").assertCountEquals(0)
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.camera_stop_recording))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("camera-fps-selector").assertIsDisplayed()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }

    @Test fun disabledRecordCannotInvokeCaptureCallback() {
        var clicks = 0
        compose.setContent {
            OpenJumpTheme { CameraRecordButton(false, false, { clicks++ }) }
        }
        compose.onNodeWithContentDescription(text(R.string.camera_preparing))
            .assertIsNotEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(0, clicks)
    }
}
