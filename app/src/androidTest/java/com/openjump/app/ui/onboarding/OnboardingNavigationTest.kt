package com.openjump.app.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.MainActivity
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingNavigationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun athleteNameStaysInPlaceWhenKeyboardOpensAndTextChanges() {
        (context as OpenJumpApp).onboardingPreferencesStore.completeOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).performClick()
            val next = context.getString(R.string.onboarding_finish_start)
            composeRule.onNodeWithText(next).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_next)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_athlete_title)).assertExists()

            val nameField = composeRule.onNodeWithTag("onboarding-athlete-name")
            val before = nameField.fetchSemanticsNode().boundsInRoot.top
            nameField.performClick()
            nameField.performTextReplacement("Alex")
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.onboarding_save_continue))
                    .fetchSemanticsNodes().isEmpty()
            }
            val after = nameField.fetchSemanticsNode().boundsInRoot.top
            assertTrue("Name field shifted by ${after - before}px on IME open", kotlin.math.abs(after - before) < 80f)
        }
    }

    @Test
    fun finalStepKeepsPreviousAndContinueAndOffersToolRoutesInTheContent() {
        (context as OpenJumpApp).onboardingPreferencesStore.completeOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).performClick()
            composeRule.onNodeWithTag("onboarding-next").performClick() // Welcome
            composeRule.onNodeWithTag("onboarding-next").performClick() // Mode
            composeRule.onNodeWithText(context.getString(R.string.onboarding_athlete_title)).assertExists()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("onboarding-next").performClick() // Save profile
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.onboarding_basics_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("onboarding-next").performClick() // Basics
            composeRule.onNodeWithText(context.getString(R.string.onboarding_finish_title)).assertExists()
            composeRule.onNodeWithTag("onboarding-previous").assertExists()
            composeRule.onNodeWithTag("onboarding-next").assertExists()
            composeRule.onNodeWithTag("onboarding-jumps").assertExists()
            composeRule.onNodeWithTag("onboarding-encoder").assertExists()
            composeRule.onNodeWithTag("onboarding-previous").performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_basics_title)).assertExists()
            composeRule.onNodeWithTag("onboarding-next").performClick()
            composeRule.onNodeWithTag("onboarding-jumps").performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_finish_title)).assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.jumps_title)).assertExists()
        }
    }

    @Test
    fun replaySkipReturnsToSettingsWithoutChangingCompletion() {
        val app = context as OpenJumpApp
        app.onboardingPreferencesStore.completeOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).performClick()
            composeRule.onNodeWithTag("onboarding-skip").performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_welcome_title)).assertDoesNotExist()
            assertTrue(app.onboardingPreferencesStore.completion.value == com.openjump.app.settings.OnboardingCompletion.COMPLETED)
        }
    }

    @Test
    fun existingUserCanReplayReturnAndRestartAtWelcomeAfterRecreation() {
        (context as OpenJumpApp).onboardingPreferencesStore.completeOnboarding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).performClick()
            val welcome = context.getString(R.string.onboarding_welcome_title)
            val mode = context.getString(R.string.onboarding_mode_prompt)
            composeRule.onNodeWithText(welcome).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_finish_start)).performClick()
            composeRule.onNodeWithText(mode).assertExists()
            composeRule.onNodeWithTag("onboarding-previous").performClick()
            composeRule.onNodeWithText(welcome).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_finish_start)).performClick()
            composeRule.onNodeWithText(mode).assertExists()

            scenario.recreate()
            composeRule.onNodeWithText(welcome).assertExists()
            composeRule.onNodeWithText(mode).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.onboarding_settings_title)).assertExists()
        }
    }
}
