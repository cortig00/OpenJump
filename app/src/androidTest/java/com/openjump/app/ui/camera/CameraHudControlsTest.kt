package com.openjump.app.ui.camera

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraHudControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun tipsAreDiscoverableAndHaveAnAccessibleTouchTarget() {
        var clicked = false
        compose.setContent {
            OpenJumpTheme { CameraHintButton(onClick = { clicked = true }) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.camera_capture_tips))
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(clicked)
    }

    @Test fun jumpHelpButtonHasItsOwnAccessibleLabel() {
        var clicked = false
        compose.setContent {
            OpenJumpTheme {
                CameraHintButton(
                    onClick = { clicked = true },
                    descriptionRes = R.string.jump_recording_guide_open,
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.jump_recording_guide_open))
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(clicked)
    }

    @Test fun quickFpsShowsResolvedAutoAndDisablesRuntimeUnavailableProfile() {
        var selection: Int? = null
        compose.setContent {
            OpenJumpTheme {
                CameraQuickFpsSelector(
                    selectedFps = CameraSettings.AUTO,
                    activeFps = 30,
                    availableFps = listOf(120, 60),
                    unavailableFps = setOf(120),
                    onSelect = { selection = it },
                )
            }
        }
        compose.onNodeWithText(
            context.getString(R.string.camera_fps_quick_auto, context.getString(R.string.camera_fps_auto), 30),
        ).performClick()
        compose.onNodeWithText(context.getString(R.string.camera_fps_unavailable_chip, "120"))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.camera_fps_quick_fixed, 60)).performClick()
        assertEquals(60, selection)
    }
}
