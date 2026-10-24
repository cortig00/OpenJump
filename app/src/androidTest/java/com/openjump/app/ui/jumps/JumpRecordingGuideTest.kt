package com.openjump.app.ui.jumps

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.camera.CameraHintOverlay
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F038: the unused [JumpRecordingGuideDialog] popup is gone; the live inline tips
 * inside the camera information panel are the supported path. Preference keys and
 * `jump_recording_guide_*` resources are unchanged.
 */
@RunWith(AndroidJUnit4::class)
class JumpRecordingGuideTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = CameraSettings(context)
    private var previouslySeenJump = false
    private var previouslySeenEncoder = false

    @Before fun savePreferences() {
        previouslySeenJump = settings.hasSeenJumpCameraGuide
        previouslySeenEncoder = settings.hasSeenEncoderCameraGuide
        settings.hasSeenJumpCameraGuide = false
        settings.hasSeenEncoderCameraGuide = false
    }

    @After fun restorePreferences() {
        settings.hasSeenJumpCameraGuide = previouslySeenJump
        settings.hasSeenEncoderCameraGuide = previouslySeenEncoder
    }

    @Test fun selectionDoesNotConsumeFirstCameraHelp() {
        compose.setContent {
            OpenJumpTheme { JumpsScreen(onBack = {}, onProtocolSelected = {}) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.jump_recording_guide_open))
            .assertDoesNotExist()
        assertFalse(settings.hasSeenJumpCameraGuide)
    }

    @Test fun liveInlineTipsShowJumpGuideAndClose() {
        // Mirrors the production CameraScreen inline block: the jump guide body plus
        // its four cues rendered on demand inside CameraHintOverlay, never a popup.
        var dismissed = false
        compose.setContent {
            OpenJumpTheme {
                CameraHintOverlay(
                    guidance = context.getString(R.string.camera_capture_vertical),
                    progress = null,
                    warnings = emptyList(),
                    onDismiss = { dismissed = true },
                ) {
                    Text(
                        context.getString(R.string.jump_recording_guide_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    listOf(
                        R.string.jump_recording_guide_stable,
                        R.string.jump_recording_guide_framing,
                        R.string.jump_recording_guide_headroom,
                        R.string.jump_recording_guide_lighting,
                    ).forEach { cue ->
                        Text(context.getString(cue), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        listOf(
            R.string.jump_recording_guide_body,
            R.string.jump_recording_guide_stable,
            R.string.jump_recording_guide_framing,
            R.string.jump_recording_guide_headroom,
            R.string.jump_recording_guide_lighting,
        ).forEach { res -> compose.onNodeWithText(context.getString(res)).assertIsDisplayed() }
        compose.onNodeWithContentDescription(context.getString(R.string.video_source_close))
            .performClick()
        assert(dismissed)
    }
}
