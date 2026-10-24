package com.openjump.app.ui.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.camera.JumpCameraState
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F033: live production information toggle/close in READY and ERROR with permissions
 * granted, hidden during an active take. Uses the production [CameraViewfinderHud] and
 * the real production gate ([shouldShowCameraInformation] over [CameraHudPolicy]).
 */
@RunWith(AndroidJUnit4::class)
class CameraInformationGateInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(state: JumpCameraState, permissionsGranted: Boolean = true) {
        compose.setContent {
            OpenJumpTheme {
                var information by rememberCameraInformationVisibility()
                var cameraState by remember(state) { mutableStateOf(state) }
                // Production parity: an active take force-closes the panel.
                if (cameraState == JumpCameraState.RECORDING ||
                    cameraState == JumpCameraState.STARTING ||
                    cameraState == JumpCameraState.FINALIZING
                ) {
                    information = false
                }
                val hud = CameraHudPolicy.forState(cameraState)
                CameraViewfinderHud(
                    showControls = hud.showFps && permissionsGranted,
                    onBack = {},
                    onInformation = { information = !information },
                    informationDescriptionRes = R.string.camera_capture_tips,
                    informationExpanded = information,
                    controls = { includeInformation ->
                        CameraCaptureControlsGroup(
                            selectedFps = com.openjump.app.settings.CameraSettings.AUTO,
                            activeFps = 30,
                            fpsChoices = listOf(120, 60),
                            previewUnavailableFps = emptySet(),
                            scopedUnavailableFps = emptySet(),
                            selectedResolution = com.openjump.app.camera.CameraResolutionPreference.AUTO,
                            isResolutionEnabled = { true },
                            activeProfile = null,
                            onFpsSelect = {},
                            onResolutionSelect = {},
                            onHintClick = { information = !information },
                            hintDescriptionRes = R.string.camera_capture_tips,
                            includeInformationButton = includeInformation,
                            informationExpanded = information,
                        )
                    },
                    information = if (
                        shouldShowCameraInformation(information, permissionsGranted, cameraState)
                    ) ({
                        CameraHintOverlay(
                            guidance = context.getString(R.string.camera_capture_vertical),
                            progress = null,
                            warnings = emptyList(),
                            onDismiss = { information = false },
                        )
                    }) else null,
                    record = null,
                    status = null,
                    blockingError = null,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun readyOffersToggleAndClose() {
        render(JumpCameraState.READY)
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.camera_capture_tips))
        toggle.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("camera-information-panel").assertExists()
        toggle.performClick().assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
        toggle.performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.video_source_close)).performClick()
        toggle.assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }

    @Test fun errorOffersToggleAndClose() {
        render(JumpCameraState.ERROR)
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.camera_capture_tips))
        toggle.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("camera-information-panel").assertExists()
        toggle.performClick().assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
        toggle.performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.video_source_close)).performClick()
        toggle.assertIsOff()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }

    @Test fun activeTakeHidesInformationAndToggle() {
        render(JumpCameraState.RECORDING)
        compose.onNodeWithContentDescription(context.getString(R.string.camera_capture_tips))
            .assertDoesNotExist()
        compose.onAllNodesWithTag("camera-information-panel").assertCountEquals(0)
    }
}
