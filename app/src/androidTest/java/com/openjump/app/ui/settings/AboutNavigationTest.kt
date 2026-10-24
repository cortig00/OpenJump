package com.openjump.app.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.MainActivity
import com.openjump.app.R
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.AppLanguageManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun resetLocale() {
        AppLanguageManager.apply(AppLanguage.SYSTEM)
    }

    @Test
    fun settingsAboutPrivacyLicenseAndBackRoutesAreReachable() {
        AppLanguageManager.apply(AppLanguage.ENGLISH)
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.nav_settings))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithTag("settings-screen-list").performScrollToNode(
                matcher = hasText(context.getString(R.string.settings_about_title)),
            )
            composeRule.onNodeWithText(context.getString(R.string.settings_about_title)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.about_title)).assertExists()
            assertSettingsBottomBar()

            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(context.getString(R.string.about_open_privacy)),
            )
            composeRule.onNodeWithText(context.getString(R.string.about_open_privacy)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.settings_privacy_title)).assertExists()
            assertSettingsBottomBar()
            composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.about_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(R.string.about_title)).assertExists()
            assertSettingsBottomBar()
            composeRule.onNodeWithTag("about-screen-list").performScrollToNode(
                matcher = hasText(context.getString(R.string.about_open_license)),
            )
            composeRule.onNodeWithText(context.getString(R.string.about_open_license)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.about_open_license)).performClick()

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("license-document-heading")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("license-document-heading")
                .assertTextEquals(context.getString(R.string.about_license_title))
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Heading)
                .let { assertTrue(it) }
            composeRule.onNodeWithText("END OF TERMS AND CONDITIONS", substring = true).assertExists()
            assertSettingsBottomBar()
            composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.about_title)).assertExists()
            assertSettingsBottomBar()
            composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
            composeRule.onNodeWithTag("settings-screen-list").assertExists()
            assertSettingsBottomBar()
        }
    }

    @Test
    fun privacyDirectlyFromSettingsKeepsBarAndReturnsToSettings() {
        AppLanguageManager.apply(AppLanguage.ENGLISH)
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.nav_settings))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
            composeRule.onNodeWithTag("settings-screen-list").performScrollToNode(
                matcher = hasText(context.getString(R.string.settings_privacy_title)),
            )
            composeRule.onNodeWithText(context.getString(R.string.settings_privacy_title)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.privacy_intro))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.settings_privacy_title)).assertExists()
            assertSettingsBottomBar()
            composeRule.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("settings-screen-list")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("settings-screen-list").assertExists()
            assertSettingsBottomBar()
        }
    }

    private fun assertSettingsBottomBar() {
        composeRule.onAllNodesWithTag("bottom-navigation").assertCountEquals(1)
        composeRule.onNodeWithTag("bottom-navigation-item-settings").assertIsSelected()
    }
}
