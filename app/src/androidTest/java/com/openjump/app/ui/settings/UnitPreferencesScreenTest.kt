package com.openjump.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.openjump.app.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.settings.AppLanguage
import com.openjump.app.settings.AppLanguageManager
import com.openjump.app.settings.DisplayPreferencesStore
import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.JumpTimingUnit
import com.openjump.app.settings.UnitPreset
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnitPreferencesScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE).edit().clear().commit()
        AppLanguageManager.apply(AppLanguage.SYSTEM)
    }

    @Test
    fun presetAndIndividualSelectionsPersistImmediately() {
        val store = DisplayPreferencesStore(context)
        composeRule.setContent { MaterialTheme { UnitPreferencesScreen({}, store) } }
        composeRule.onNodeWithText(context.getString(com.openjump.app.R.string.settings_units_us)).performClick()
        composeRule.waitForIdle()
        assertEquals(UnitPreset.UNITED_STATES, store.state.value.unitProfile.preset())
        composeRule.onNodeWithTag("unit-preferences-list").performScrollToNode(matcher = hasText("mm"))
        composeRule.onNodeWithTag("unit-option-mm").performClick()
        composeRule.waitForIdle()
        assertEquals(EncoderDisplacementUnit.MILLIMETER, store.state.value.unitProfile.encoderDisplacement)
        assertEquals(UnitPreset.CUSTOM, store.state.value.unitProfile.preset())
        assertEquals(UnitPreset.CUSTOM, store.state.value.unitProfile.preset())
    }

    @Test
    fun mainActivitySettingsUnitsBackAndRecreatePreserveSelection() {
        AppLanguageManager.apply(AppLanguage.ENGLISH)
        context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText(context.getString(com.openjump.app.R.string.nav_settings)).performClick()
            composeRule.onNodeWithText(context.getString(com.openjump.app.R.string.settings_units_title)).performClick()
            composeRule.onAllNodesWithTag("bottom-navigation").assertCountEquals(1)
            composeRule.onNodeWithTag("bottom-navigation-item-settings").assertIsSelected()
            composeRule.onNodeWithText(context.getString(com.openjump.app.R.string.settings_units_us)).performClick()
            composeRule.onNodeWithTag("unit-preferences-list").performScrollToNode(matcher = hasText("s"))
            composeRule.onNodeWithTag("unit-option-s").performClick()
            scenario.recreate()
            composeRule.waitForIdle()
            val selectedTiming = composeRule.onNodeWithTag("unit-option-s")
            selectedTiming.assertIsSelected()
            assertEquals(
                context.getString(com.openjump.app.R.string.settings_units_selected),
                selectedTiming.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            val expected = UnitProfile.UNITED_STATES.copy(jumpTiming = JumpTimingUnit.SECOND)
            assertEquals(UnitPreset.CUSTOM, DisplayPreferencesStore(context).state.value.unitProfile.preset())
            assertEquals(expected, DisplayPreferencesStore(context).state.value.unitProfile)
            val preview = MeasurementFormatting.format(
                500.0,
                MeasurementQuantity.DURATION_MS,
                expected,
                java.util.Locale.ENGLISH,
            )
            composeRule.onNodeWithText(preview).assertExists()
            composeRule.onAllNodesWithText(context.getString(com.openjump.app.R.string.settings_save_default)).assertCountEquals(0)
            composeRule.onNodeWithContentDescription(context.getString(com.openjump.app.R.string.common_back)).performClick()
            composeRule.onAllNodesWithText(context.getString(com.openjump.app.R.string.settings_title)).assertCountEquals(2)
        }
    }

    @Test
    fun supportedLocalesExposeStableLocalizedUnitLabels() {
        val labels = listOf(
            Triple(AppLanguage.SPANISH, "Unidades", "Altura y diámetro"),
            Triple(AppLanguage.ENGLISH, "Units", "Height and diameter"),
            Triple(AppLanguage.FRENCH, "Unités", "Hauteur et diamètre"),
            Triple(AppLanguage.GERMAN, "Einheiten", "Höhe und Durchmesser"),
        )
        val configurationState = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            val configuration = configurationState.value
            val localizedContext = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                MaterialTheme { UnitPreferencesScreen({}, DisplayPreferencesStore(context)) }
            }
        }
        labels.forEach { (language, title, shortLength) ->
            configurationState.value = Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(language.languageTag))
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(title).assertExists()
            composeRule.onNodeWithText(shortLength).assertExists()
        }
    }

    @Test
    fun largeFontSmallViewportKeepsUnitRowsReachable() {
        val store = DisplayPreferencesStore(context)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    MaterialTheme { UnitPreferencesScreen({}, store) }
                }
            }
        }
        composeRule.onNodeWithTag("unit-preferences-list").performScrollToNode(matcher = hasText("s"))
        composeRule.onNodeWithTag("unit-option-s").assertExists()
    }

    @Test
    fun presetActionIsImmediatelyObservableWithoutSaveStep() {
        val store = DisplayPreferencesStore(context)
        composeRule.setContent { MaterialTheme { UnitPreferencesScreen({}, store) } }
        composeRule.onNodeWithText(context.getString(com.openjump.app.R.string.settings_units_metric)).performClick()
        assertEquals(UnitPreset.METRIC, store.state.value.unitProfile.preset())
        assertEquals("cm", store.state.value.unitProfile.shortLength.id)
    }
}
