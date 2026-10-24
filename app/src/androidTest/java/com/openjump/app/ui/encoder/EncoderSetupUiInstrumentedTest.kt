package com.openjump.app.ui.encoder

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real setup, disposable host and in-memory athlete list; no Room or preference writes. */
@RunWith(AndroidJUnit4::class)
class EncoderSetupUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var strings: Context
    private var recorded: Pair<EncoderSetup, Long>? = null
    private var recordCalls = 0
    private var importCalls = 0
    private var hideKeyboard: () -> Unit = {}
    private var selectedId by mutableStateOf<Long?>(1L)

    private fun render(athletes: List<AthleteEntity> = listOf(
        AthleteEntity(id = 1L, displayName = "Athlete A"),
        AthleteEntity(id = 2L, displayName = "Athlete B"),
    ), loading: Boolean = false, imperial: Boolean = false, defaults: Map<EncoderExercise, Double> = emptyMap()) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(args.getString("encoderSetupLanguage") ?: "en"))
        }
        strings = context.createConfigurationContext(config)
        restoration.setContent {
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { hideKeyboard = { keyboard?.hide() } }
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalActivityResultRegistryOwner provides compose.activity,
                LocalConfiguration provides config,
                LocalUnitSystem provides if (imperial) UnitProfile.UNITED_STATES else UnitProfile.METRIC,
            ) {
                OpenJumpTheme(darkTheme = args.getString("encoderSetupDark") == "true") {
                    EncoderSetupScreen(
                        onBack = {}, onHowItWorks = {}, activeAthletes = athletes,
                        athletesLoading = loading, selectedAthleteId = selectedId,
                        onAthleteSelected = { selectedId = it },
                        onImport = { _, _, _ -> importCalls++ },
                        onRecord = { setup, athlete -> recorded = setup to athlete; recordCalls++ },
                        initialDefaultLoadsKg = defaults,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = strings.getString(id)
    private fun metricDefaultText(kg: Double) = MeasurementFormatting.formatValue(
        kg, MeasurementQuantity.MASS_KG, UnitProfile.METRIC, strings.resources.configuration.locales[0], decimals = 2,
    )
    private fun enterLoad(value: String) {
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        if (args.getString("encoderSetupEvidence") != "true") return
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val folder = File(context.getExternalFilesDir(null), "encoder-setup-visual").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun disclosureAndFixedActionsRemainReachableAtNativeWindowAndFont() {
        render()
        compose.onNodeWithTag("encoder-setup-import").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("encoder-setup-record").assertIsDisplayed().assertIsEnabled()
        capture("01-setup")
        compose.onNodeWithText(label(R.string.labs_live_warning_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.labs_live_warning_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("encoder-setup-scale-reference").performScrollTo().assertIsDisplayed()
        val footer = compose.onNodeWithTag("encoder-setup-footer").fetchSemanticsNode().boundsInRoot
        val scroll = compose.onNodeWithTag("encoder-setup-content").fetchSemanticsNode().boundsInRoot
        assertTrue("Scroll must not be hidden under the footer", scroll.bottom <= footer.top + 1f)
        for (tag in listOf("encoder-setup-import", "encoder-setup-record")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue(bounds.height >= 48 * density - 1)
            assertTrue(bounds.top >= footer.top && bounds.bottom <= footer.bottom)
        }
        val recordBounds = compose.onNodeWithTag("encoder-setup-record").fetchSemanticsNode().boundsInRoot
        val importBounds = compose.onNodeWithTag("encoder-setup-import").fetchSemanticsNode().boundsInRoot
        assertTrue("Record must sit above import", recordBounds.bottom <= importBounds.top + 1f)
    }

    @Test fun loadEditingKeepsActionsAboveTheNativeKeyboardWithoutDispatch() {
        render()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().performClick()
        try {
            compose.waitUntil(5_000) {
                compose.activity.window.decorView.rootWindowInsets
                    ?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            compose.onNodeWithTag("encoder-setup-load").performTextReplacement("40")
            compose.onNodeWithTag("encoder-setup-import").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("encoder-setup-record").assertIsDisplayed().assertIsEnabled()
            val recordWindow = compose.onNodeWithTag("encoder-setup-record").fetchSemanticsNode().boundsInWindow
            val importWindow = compose.onNodeWithTag("encoder-setup-import").fetchSemanticsNode().boundsInWindow
            assertTrue("Record must sit above import", recordWindow.bottom <= importWindow.top + 1f)
            val footer = compose.onNodeWithTag("encoder-setup-footer").fetchSemanticsNode().boundsInWindow
            val screenHeight = compose.activity.window.decorView.height
            val imeHeight = compose.activity.window.decorView.rootWindowInsets
                .getInsets(android.view.WindowInsets.Type.ime()).bottom
            assertTrue("Footer content must remain above the native IME", footer.bottom <= screenHeight - imeHeight + 1f)
            capture("04-load-keyboard")
            assertEquals(0, importCalls)
            assertEquals(0, recordCalls)
        } finally {
            compose.runOnUiThread { hideKeyboard() }
            compose.waitForIdle()
        }
    }

    @Test fun invalidLoadDisablesBothActionsAndZeroRemainsValid() {
        render()
        for (value in listOf("", "-1", "abc")) {
            enterLoad(value)
            compose.onNodeWithTag("encoder-setup-import").assertIsNotEnabled()
            compose.onNodeWithTag("encoder-setup-record").assertIsNotEnabled()
        }
        enterLoad("0")
        compose.onNodeWithTag("encoder-setup-import").assertIsEnabled()
        compose.onNodeWithTag("encoder-setup-record").assertIsEnabled().performClick()
        assertEquals(0.0, recorded!!.first.loadKg, 0.0)
        assertEquals(1L, recorded!!.second)
        compose.onNodeWithTag("encoder-setup-record").assertIsNotEnabled()
        assertEquals(1, recordCalls)
        assertEquals(0, importCalls)
    }

    @Test fun exerciseAndAthletePickersKeepTheExistingRecordedPayload() {
        render()
        compose.onNodeWithTag("encoder-setup-exercise").performScrollTo().performClick()
        EncoderExercise.entries.forEach {
            compose.onNodeWithTag("encoder-exercise-${it.name.lowercase()}").assertExists()
        }
        compose.onNodeWithTag("encoder-exercise-bench_press").assertIsSelected()
        capture("02-exercise-list")
        compose.onNodeWithTag("encoder-exercise-squat").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-setup-athlete").performScrollTo().performClick()
        compose.onNodeWithText("Athlete B").performScrollTo().performClick()
        enterLoad("40")
        compose.onNodeWithTag("encoder-setup-record").assertIsEnabled().performClick()
        assertEquals(EncoderSetup(EncoderExercise.SQUAT, 40.0), recorded!!.first)
        assertEquals(2L, recorded!!.second)
        assertEquals(1, recordCalls)
    }

    @Test fun scaleReferenceActionOnlyRevealsTheFullExplanation() {
        render()
        compose.onNodeWithTag("encoder-setup-scale-reference").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.encoder_scale_reference_body)).assertIsDisplayed()
        capture("03-scale-reference")
        compose.onNode(hasText(label(R.string.common_back)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(label(R.string.encoder_scale_reference_body)).assertDoesNotExist()
        assertEquals(0, importCalls)
        assertEquals(0, recordCalls)
    }

    @Test fun emptyAthletesCannotStartEvenWithValidLoad() {
        selectedId = null
        render(athletes = emptyList())
        enterLoad("40")
        compose.onNodeWithTag("encoder-setup-import").assertIsNotEnabled()
        compose.onNodeWithTag("encoder-setup-record").assertIsNotEnabled()
        compose.onNodeWithTag("encoder-setup-athlete").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.athlete_selector_empty)).assertIsDisplayed()
    }

    @Test fun athleteLoadingKeepsTheCompactSelectorDisabled() {
        render(loading = true, athletes = emptyList())
        compose.onNodeWithTag("encoder-setup-athlete").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("encoder-setup-import").assertIsNotEnabled()
    }

    @Test fun defaultLoadIsUsedWithoutEditingAndConvertedDisplayDoesNotChangePayload() {
        render(imperial = true, defaults = mapOf(EncoderExercise.BENCH_PRESS to 131.54))
        compose.onNodeWithTag("encoder-setup-record").assertIsEnabled().performClick()
        assertEquals(131.54.toRawBits(), recorded!!.first.loadKg.toRawBits())
    }

    @Test fun switchingExercisesUsesOwnDefaultAndReturningKeepsTheManualSessionLoad() {
        render(defaults = mapOf(EncoderExercise.BENCH_PRESS to 40.0, EncoderExercise.SQUAT to 120.0))
        enterLoad("61.25")
        compose.runOnUiThread { hideKeyboard() }
        compose.onNodeWithTag("encoder-setup-exercise").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-exercise-squat").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().assertTextContains(metricDefaultText(120.0))
        compose.onNodeWithTag("encoder-setup-exercise").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-exercise-bench_press").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().assertTextContains("61.25")
        compose.onNodeWithTag("encoder-setup-record").performClick()
        assertEquals(EncoderSetup(EncoderExercise.BENCH_PRESS, 61.25), recorded!!.first)
    }

    @Test fun editedExerciseDraftAndSelectionSurviveSavedStateRestoration() {
        render(defaults = mapOf(EncoderExercise.BENCH_PRESS to 40.0, EncoderExercise.SQUAT to 120.0))
        enterLoad("61.25")
        compose.runOnUiThread { hideKeyboard() }
        compose.onNodeWithTag("encoder-setup-exercise").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-exercise-squat").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().assertTextContains(metricDefaultText(120.0))
        compose.onNodeWithTag("encoder-setup-exercise").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-exercise-bench_press").performScrollTo().performClick()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().assertTextContains("61.25")
        compose.onNodeWithTag("encoder-setup-record").performClick()
        assertEquals(EncoderSetup(EncoderExercise.BENCH_PRESS, 61.25), recorded!!.first)
    }

    @Test fun initialZeroDefaultCanStartWithoutTyping() {
        render()
        compose.onNodeWithTag("encoder-setup-load").performScrollTo().assertTextContains(metricDefaultText(0.0))
        compose.onNodeWithTag("encoder-setup-record").assertIsEnabled().performClick()
        assertEquals(0.0, recorded!!.first.loadKg, 0.0)
    }

    @Test fun imperialLoadSuffixStillDispatchesCanonicalKilograms() {
        render(imperial = true)
        enterLoad("100")
        compose.onNodeWithText("lb").assertExists()
        compose.onNodeWithTag("encoder-setup-record").performClick()
        assertEquals(45.359237, recorded!!.first.loadKg, 0.000001)
    }
}
