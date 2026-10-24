package com.openjump.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPreparationOverlayTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val title get() = context.getString(R.string.selector_video_loading)

    @Test fun fastCompletionNeverDisplaysFeedback() {
        val active = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenJumpTheme {
                Box(Modifier.fillMaxSize()) { VideoPreparationOverlay(active.value) }
            }
        }
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun handoffAfterThresholdShowsFeedbackWithoutAnotherDelay() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenJumpTheme {
                Box(Modifier.fillMaxSize()) {
                    VideoPreparationOverlay(active = true, delayMillis = 0L)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun longPreparationAnnouncesFeedbackAndHidesWhenStateEndsInBothThemes() {
        val active = mutableStateOf(true)
        val dark = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenJumpTheme(darkTheme = dark.value) {
                Box(Modifier.fillMaxSize()) { VideoPreparationOverlay(active.value) }
            }
        }
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithText(title).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(context.getString(R.string.video_preparing_long_hint)).assertIsDisplayed()
        compose.runOnIdle { dark.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(title).assertDoesNotExist()
    }
}
