package com.openjump.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.RecordingUse
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Sandbox UI coverage for the split Saltos/Encoder recording rows.
 * Renders the real SettingsScreen with an isolated [CameraSettings] override;
 * existing app context/ViewModels are used read-only (no Room writes).
 * Never clears production `openjump_settings`.
 */
@RunWith(AndroidJUnit4::class)
class RecordingModeSettingsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() =
        appContext.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)
    private lateinit var strings: Context
    private data class RenderSpec(
        val settings: CameraSettings,
        val context: Context,
        val config: Configuration,
        val dark: Boolean,
        val fontScale: Float,
        val generation: Int,
    )
    private val rendered = mutableStateOf<RenderSpec?>(null)
    private var contentInstalled = false
    private var generation = 0
    private val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Before
    @After
    fun resetSandbox() {
        appContext.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun render(
        cameraSettings: CameraSettings,
        language: String = "en",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val config = Configuration(appContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        strings = appContext.createConfigurationContext(config)
        val spec = RenderSpec(cameraSettings, strings, config, dark, fontScale, ++generation)
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
                                    cameraSettingsOverride = current.settings,
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

    private fun openPicker(tag: String) {
        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("settings-fps-picker-title")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun chooseFpsOption(fps: Int) {
        val resolved = if (fps == CameraSettings.AUTO) {
            strings.getString(R.string.settings_fps_auto)
        } else {
            strings.getString(R.string.settings_fps_value, fps)
        }
        // Sheet options carry Role.RadioButton; background summaries are Role.Button.
        compose.onNode(hasText(resolved) and radioRole).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("settings-fps-picker-title")
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun dismissPicker() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("settings-fps-picker-title")
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun assertSheetOptionSelected(fps: Int) {
        val label = strings.getString(R.string.settings_fps_value, fps)
        compose.onNode(hasText(label) and radioRole).assertIsSelected()
    }

    @Test
    fun bothRowsPickIndependentlyWithContextualTitleSelectedStateAndFreshRender() {
        render(CameraSettings(prefs))

        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag("settings-fps-jumps"))
        compose.onNodeWithTag("settings-fps-jumps").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag("settings-fps-encoder"))
        compose.onNodeWithTag("settings-fps-encoder").assertIsDisplayed()

        // Jump row -> contextual jumps title -> choose 120 fps.
        openPicker("settings-fps-jumps")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertIsDisplayed()
            .assertTextEquals(strings.getString(R.string.settings_fps_title_jumps))
        chooseFpsOption(120)
        assertEquals(120, CameraSettings(prefs).getPreferredFps(RecordingUse.JUMPS))
        assertEquals(CameraSettings.AUTO, CameraSettings(prefs).getPreferredFps(RecordingUse.ENCODER))

        // Encoder row -> contextual encoder title -> choose 60 fps.
        openPicker("settings-fps-encoder")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertIsDisplayed()
            .assertTextEquals(strings.getString(R.string.settings_fps_title_encoder))
        chooseFpsOption(60)
        assertEquals(120, CameraSettings(prefs).getPreferredFps(RecordingUse.JUMPS))
        assertEquals(60, CameraSettings(prefs).getPreferredFps(RecordingUse.ENCODER))

        // Reopening shows the selected radio state for each mode.
        openPicker("settings-fps-jumps")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertTextEquals(strings.getString(R.string.settings_fps_title_jumps))
        assertSheetOptionSelected(120)
        dismissPicker()

        openPicker("settings-fps-encoder")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertTextEquals(strings.getString(R.string.settings_fps_title_encoder))
        assertSheetOptionSelected(60)
        dismissPicker()

        // Fresh render with a fresh instance persists both selections.
        render(CameraSettings(prefs))
        assertEquals(120, CameraSettings(prefs).getPreferredFps(RecordingUse.JUMPS))
        assertEquals(60, CameraSettings(prefs).getPreferredFps(RecordingUse.ENCODER))

        openPicker("settings-fps-jumps")
        assertSheetOptionSelected(120)
        dismissPicker()

        openPicker("settings-fps-encoder")
        assertSheetOptionSelected(60)
        dismissPicker()

        // Fresh keyed composition resets local remember state; this is not a process-death test.
    }

    @Test
    fun largeFontDarkSpanishKeepsBothRowsAndContextualTitlesReachable() {
        render(CameraSettings(prefs), language = "es", dark = true, fontScale = 1.5f)

        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag("settings-fps-jumps"))
        compose.onNodeWithTag("settings-fps-jumps").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasTestTag("settings-fps-encoder"))
        compose.onNodeWithTag("settings-fps-encoder").assertIsDisplayed()

        // Row summaries mention the shared description copy without crashing at large scale.
        compose.onNodeWithTag("settings-screen-list")
            .performScrollToNode(hasText(strings.getString(R.string.settings_fps_row_description)))

        openPicker("settings-fps-jumps")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertIsDisplayed()
            .assertTextEquals(strings.getString(R.string.settings_fps_title_jumps))
        dismissPicker()

        openPicker("settings-fps-encoder")
        compose.onNodeWithTag("settings-fps-picker-title")
            .assertIsDisplayed()
            .assertTextEquals(strings.getString(R.string.settings_fps_title_encoder))
        dismissPicker()
    }

    private companion object {
        const val SANDBOX_NAME = "camera-recording-sandbox-ui"
    }
}
