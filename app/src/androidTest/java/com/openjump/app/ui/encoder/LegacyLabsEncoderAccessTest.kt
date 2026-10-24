package com.openjump.app.ui.encoder

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.MainActivity
import com.openjump.app.R
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.AppLanguageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val Context.legacyOpenJumpLabsDataStore by preferencesDataStore(name = "openjump_labs")

/**
 * The retired OpenJump Labs consent gate must never block Encoder access.
 *
 * Seeds only the legacy `openjump_labs` DataStore `enabled=false` flag before launch and proves
 * Home Labs still reaches Encoder setup with its contextual notice. No other user data is touched.
 */
@RunWith(AndroidJUnit4::class)
class LegacyLabsEncoderAccessTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seedLegacyDisabledFlag() {
        AppLanguageManager.apply(AppLanguage.ENGLISH)
        runBlocking {
            context.legacyOpenJumpLabsDataStore.edit { preferences ->
                preferences[booleanPreferencesKey("enabled")] = false
            }
        }
    }

    @After
    fun resetLocale() {
        AppLanguageManager.apply(AppLanguage.SYSTEM)
    }

    @Test
    fun legacyDisabledLabsFlagCannotBlockEncoderSetup() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.labs_name))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.labs_name)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.encoder_setup_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.encoder_setup_title)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.labs_live_warning_title)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.labs_live_warning_body)).assertExists()
        }
    }
}
