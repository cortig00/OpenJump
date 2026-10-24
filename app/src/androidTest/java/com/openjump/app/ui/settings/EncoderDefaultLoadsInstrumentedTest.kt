package com.openjump.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Real list/editor with isolated preferences. No production preferences or Room writes. */
@RunWith(AndroidJUnit4::class)
class EncoderDefaultLoadsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("encoder-default-loads-ui-test", Context.MODE_PRIVATE)
    private val settings get() = EncoderSettings(prefs)
    private var defaults by mutableStateOf<Map<EncoderExercise, Double>>(emptyMap())
    private var profile by mutableStateOf(UnitProfile.METRIC)
    private lateinit var strings: Context

    @Before @After fun resetSandbox() { prefs.edit().clear().commit() }

    private fun render(language: String = "en", imperial: Boolean = false, dark: Boolean = false, fontScale: Float = 1f) {
        profile = if (imperial) UnitProfile.UNITED_STATES else UnitProfile.METRIC
        defaults = EncoderExercise.entries.associateWith(settings::defaultLoadKg)
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        restoration.setContent {
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                OpenJumpTheme(darkTheme = dark) {
                    EncoderDefaultLoadsSheet(
                        defaultsKg = defaults, unitProfile = profile, onDismiss = {},
                        onSave = { exercise, kg -> settings.setDefaultLoadKg(exercise, kg); defaults = defaults + (exercise to kg) },
                        onReset = { settings.resetDefaultLoads(); defaults = EncoderExercise.entries.associateWith(settings::defaultLoadKg) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun open(exercise: EncoderExercise = EncoderExercise.BENCH_PRESS) {
        compose.onNodeWithTag("default-loads-list").performScrollToNode(hasTestTag("default-load-row-${exercise.name}"))
        compose.onNodeWithTag("default-load-row-${exercise.name}").performClick()
        compose.onNodeWithTag("default-load-input").assertIsDisplayed()
    }

    private fun replace(value: String) {
        compose.onNodeWithTag("default-load-input").performTextReplacement(value)
        compose.onNodeWithTag("default-load-input").assertTextContains(value)
    }

    @Test fun zerosHaveTwoDecimalsAndPullUpsAreClearlyAddedLoad() {
        render()
        compose.onAllNodesWithText("0.00 kg").assertCountEquals(4)
        compose.onNodeWithText(strings.getString(R.string.settings_default_load_pull_up)).assertIsDisplayed()
    }

    @Test fun commaInputSavesExactlyAndCancelDoesNotChangeAnExercise() {
        render(language = "es")
        open()
        replace("80,25")
        compose.onNodeWithTag("default-load-save").assertIsEnabled().performClick()
        assertEquals(80.25, EncoderSettings(prefs).defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
        open()
        compose.onNodeWithTag("default-load-input").assertTextContains("80,25")
        replace("120,50")
        compose.onNodeWithTag("default-load-cancel").performClick()
        open()
        compose.onNodeWithTag("default-load-input").assertTextContains("80,25")
        assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.SQUAT), 0.0)
    }

    @Test fun invalidOrIncompleteInputCannotSaveAndZeroIsAccepted() {
        render()
        open()
        for (value in listOf("", "-1", "abc", "1.", "1,", "NaN")) {
            replace(value)
            compose.onNodeWithTag("default-load-save").assertIsNotEnabled()
            assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
        }
        replace("0.00")
        compose.onNodeWithTag("default-load-save").assertIsEnabled().performClick()
        assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
    }

    @Test fun reopeningAndSavingAnUneditedConvertedWeightNeverIncreasesIt() {
        val exact = 131.54
        settings.setDefaultLoadKg(EncoderExercise.BENCH_PRESS, exact)
        render(imperial = true)
        repeat(5) {
            open()
            compose.onNodeWithTag("default-load-save").assertIsEnabled().performClick()
            assertEquals(exact.toRawBits(), EncoderSettings(prefs).defaultLoadKg(EncoderExercise.BENCH_PRESS).toRawBits())
        }
    }

    @Test fun explicitPoundInputConvertsOnceAndLiveUnitChangesRetainTheExactAnchor() {
        render(imperial = true)
        open()
        replace("100.00")
        compose.runOnIdle { profile = UnitProfile.METRIC }
        compose.waitForIdle()
        compose.onNodeWithTag("default-load-input").assertTextContains("45.3592")
        compose.runOnIdle { profile = UnitProfile.UNITED_STATES }
        compose.waitForIdle()
        compose.onNodeWithTag("default-load-input").assertTextContains("100")
        compose.onNodeWithTag("default-load-save").performClick()
        assertEquals(45.359237, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
    }

    @Test fun resetNeedsConfirmationAndOnlyResetsLoads() {
        settings.defaultPlateDiameterCm = 40.0
        EncoderExercise.entries.forEach { settings.setDefaultLoadKg(it, 80.25) }
        render()
        compose.onNodeWithTag("default-loads-reset").performClick()
        compose.onNodeWithText(strings.getString(R.string.common_cancel)).performClick()
        assertEquals(80.25, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
        compose.onNodeWithTag("default-loads-reset").performClick()
        compose.onNodeWithTag("default-load-reset-confirm").performClick()
        EncoderExercise.entries.forEach { assertEquals(0.0, settings.defaultLoadKg(it), 0.0) }
        assertEquals(40.0, settings.defaultPlateDiameterCm, 0.0)
    }

    @Test fun unfinishedEditorRestoresItsTextWithoutResurrectingOrSavingTheOldWeight() {
        settings.setDefaultLoadKg(EncoderExercise.BENCH_PRESS, 80.25)
        render()
        open()
        replace("1.")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("default-load-input").assertTextContains("1.")
        compose.onNodeWithTag("default-load-save").assertIsNotEnabled()
        assertEquals(80.25, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
        replace("0.50")
        compose.onNodeWithTag("default-load-save").performClick()
        assertEquals(0.5, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
    }

    @Test fun largeFontDarkListKeepsTheLastExerciseAndEditorActionsReachable() {
        render(dark = true, fontScale = 1.5f)
        open(EncoderExercise.PULL_UP)
        replace("10.50")
        compose.onNodeWithTag("default-load-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("default-load-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("default-loads-reset").assertIsDisplayed()
    }
}
