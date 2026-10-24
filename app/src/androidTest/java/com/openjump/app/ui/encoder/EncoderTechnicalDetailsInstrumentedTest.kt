package com.openjump.app.ui.encoder

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.encoder.EncoderResultContent
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** F010/F037: collapsed technical disclosure keeps version + measured reasons hidden until expanded. */
@RunWith(AndroidJUnit4::class)
class EncoderTechnicalDetailsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var localized: Context

    private fun render(
        analysis: com.openjump.app.encoder.EncoderAnalysis = encoderResultUiFixture(),
        language: String = "en",
    ) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, 1f),
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                OpenJumpTheme(darkTheme = false) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        EncoderResultContent(
                            analysis = analysis,
                            modifier = Modifier.size(360.dp, 760.dp),
                            onSave = {},
                            noteDraft = "",
                            onNoteChange = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg arguments: Any): String = localized.getString(id, *arguments)

    @Test
    fun technicalDisclosureIsCollapsedByDefault() {
        render(analysis = encoderResultUiFixture())
        compose.onNodeWithTag("encoder-disclosure-technical").assertIsDisplayed()
        compose.onNodeWithTag("encoder-technical-fps").assertDoesNotExist()
        compose.onNodeWithTag("encoder-technical-version").assertDoesNotExist()
        compose.onNodeWithTag("encoder-technical-no-reasons").assertDoesNotExist()
    }

    @Test
    fun expandedDisclosureShowsRealVersionAndMeasuredReasonsForInvalidReps() {
        render(analysis = encoderResultUiFixture(repetitionCount = 3, allInvalid = true))
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").performClick()
        // Real version (default fixture config.version == 1).
        compose.onNodeWithText(text(R.string.encoder_technical_version, 1)).assertIsDisplayed()
        compose.onNodeWithTag("encoder-technical-version").assertIsDisplayed()
        // All three invalid fixture reps carry LOW_CONFIDENCE once each.
        compose.onNodeWithTag("encoder-technical-reason-low_confidence").assertIsDisplayed()
        // Overlap explainer stays inside the collapsed disclosure, never in the hero.
        compose.onNodeWithText(text(R.string.encoder_technical_reasons_overlap)).assertIsDisplayed()
        compose.onNodeWithTag("encoder-technical-no-reasons").assertDoesNotExist()
        // FPS/load line is preserved alongside the new rows.
        compose.onNodeWithTag("encoder-technical-fps").assertIsDisplayed()
    }

    @Test
    fun expandedDisclosureWithNoRepsStatesNoClassifiedReasons() {
        render(analysis = encoderResultUiFixture(repetitionCount = 0))
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").performClick()
        compose.onNodeWithText(text(R.string.encoder_technical_version, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_technical_no_reasons)).assertIsDisplayed()
    }
}
