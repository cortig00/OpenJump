package com.openjump.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.JumpSettings
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Renders the real SettingsScreen with sandboxed [JumpSettings] (and sandboxed camera settings),
 * so the production `openjump_settings` file is only ever read to prove it was not modified.
 */
@RunWith(AndroidJUnit4::class)
class JumpMarkingSettingsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = appContext.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)
    private val cameraPrefs get() = appContext.getSharedPreferences(CAMERA_SANDBOX_NAME, Context.MODE_PRIVATE)
    private lateinit var strings: Context
    private data class RenderSpec(
        val settings: JumpSettings,
        val context: Context,
        val config: Configuration,
        val dark: Boolean,
        val fontScale: Float,
        val generation: Int,
    )
    private val rendered = mutableStateOf<RenderSpec?>(null)
    private var contentInstalled = false
    private var generation = 0

    @Before
    @After
    fun resetSandbox() {
        prefs.edit().clear().commit()
        cameraPrefs.edit().clear().commit()
    }

    private fun productionSettingsSnapshot(): Map<String, *> =
        appContext.getSharedPreferences(JumpSettings.PREFERENCES_NAME, Context.MODE_PRIVATE).all.toMap()

    private fun render(
        settings: JumpSettings,
        language: String = "en",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val config = Configuration(appContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        strings = appContext.createConfigurationContext(config)
        val spec = RenderSpec(settings, strings, config, dark, fontScale, ++generation)
        compose.runOnIdle { rendered.value = spec }
        if (!contentInstalled) {
            compose.setContent {
                rendered.value?.let { current ->
                    key(current.generation) {
                        CompositionLocalProvider(
                            LocalContext provides current.context,
                            LocalActivityResultRegistryOwner provides compose.activity,
                            LocalConfiguration provides current.config,
                            LocalDensity provides Density(LocalDensity.current.density, current.fontScale),
                        ) {
                            OpenJumpTheme(darkTheme = current.dark) {
                                SettingsScreen(
                                    onPrivacySelected = {},
                                    onAboutSelected = {},
                                    onUnitsSelected = {},
                                    cameraSettingsOverride = CameraSettings(cameraPrefs),
                                    jumpSettingsOverride = current.settings,
                                )
                            }
                        }
                    }
                }
            }
            contentInstalled = true
        }
        compose.waitForIdle()
    }

    private fun scrollToToggle() {
        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag(TOGGLE_TAG))
    }

    @Test
    fun oneTapTogglesOnceAndTheValueSurvivesAFreshComposition() {
        val productionBefore = productionSettingsSnapshot()
        render(JumpSettings(prefs))

        scrollToToggle()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsDisplayed().assertIsOff()
        // A single tap on the merged row must flip the value exactly once.
        compose.onNodeWithTag(TOGGLE_TAG).performClick()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOn()
        assertTrue(JumpSettings(prefs).markMovementStart)

        // Fresh preferences instance plus fresh composition: the stored value is restored.
        render(JumpSettings(prefs))
        scrollToToggle()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOn()

        compose.onNodeWithTag(TOGGLE_TAG).performClick()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOff()
        assertFalse(JumpSettings(prefs).markMovementStart)

        assertEquals(productionBefore, productionSettingsSnapshot())
    }

    @Test
    fun largeFontDarkAndLightKeepTheExactTitleAccessibleTargetAndToggle() {
        render(JumpSettings(prefs), language = "es", dark = true, fontScale = 1.5f)
        val title = strings.getString(R.string.settings_jump_mark_start_title)
        assertEquals("Marcar inicio del movimiento", title)

        scrollToToggle()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.settings_jump_mark_start_description))
            .assertIsDisplayed()
        // The whole row is the toggle target and stays above the 48 dp minimum at 150 % font.
        compose.onNodeWithTag(TOGGLE_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TOGGLE_TAG).performClick()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOn()
        assertTrue(JumpSettings(prefs).markMovementStart)

        render(JumpSettings(prefs), language = "es", dark = false, fontScale = 1.5f)
        scrollToToggle()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithTag(TOGGLE_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOn()
        compose.onNodeWithTag(TOGGLE_TAG).performClick()
        compose.onNodeWithTag(TOGGLE_TAG).assertIsOff()
        assertFalse(JumpSettings(prefs).markMovementStart)
    }

    private companion object {
        const val SANDBOX_NAME = "jump-marking-sandbox-ui"
        const val CAMERA_SANDBOX_NAME = "jump-marking-camera-sandbox-ui"
        const val TOGGLE_TAG = "settings-jump-mark-start"
    }
}
