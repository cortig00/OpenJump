package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.LocalUnitSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** P1 first-use contract: explicit blank A/B distance and neutral READY action. No Room/media. */
@RunWith(AndroidJUnit4::class)
class EncoderReferenceFirstUseInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var strings: Context

    private fun wrap(language: String = "en", content: @androidx.compose.runtime.Composable () -> Unit) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, 1f),
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = strings.getString(id)

    @Test fun manualDistanceStartsBlankAndConfirmsOnlyExplicitValidInput() {
        val draft = EncoderDraft(
            sessionKey = "first-use-ab", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
            calibrationPointA = ImagePoint(10.0, 20.0), calibrationPointB = ImagePoint(10.0, 200.0),
        )
        var length = 0.0
        var calls = 0
        wrap {
            Column(Modifier.size(360.dp, 400.dp)) {
                ManualEncoderCalibration(draft, label(R.string.encoder_reference_other)) { length = it; calls++ }
            }
        }
        // No 0.45 m or diameter-derived preload: explicit blank with visible units.
        // TextField content lives in EditableText, not Text (assertTextEquals would also
        // match the label); assert the EditableText seam directly to preserve blank intent.
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assert(
            SemanticsMatcher("blank EditableText") { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
            },
        )
        compose.onNodeWithText("${label(R.string.selector_length)} (m)").assertIsDisplayed()
        // Empty A/B confirmation stays disabled until valid explicit input.
        compose.onNodeWithText(label(R.string.common_confirm)).assertIsDisplayed().assertIsNotEnabled()
        assertEquals(0, calls)
        // Invalid input never dispatches.
        compose.onNode(hasSetTextAction()).performTextReplacement("abc")
        compose.onNodeWithText(label(R.string.common_confirm)).assertIsNotEnabled()
        assertEquals(0, calls)
        // Explicit valid input enables exactly one correct callback.
        compose.onNode(hasSetTextAction()).performTextReplacement("0.60")
        compose.onNodeWithText(label(R.string.common_confirm)).assertIsEnabled().performClick()
        assertEquals(1, calls)
        assertEquals(0.6, length, 0.000001)
    }

    @Test fun readyPrimaryIsNeutralStartTrackingWithSingleCallback() {
        val calibrated = EncoderDraft(
            sessionKey = "first-use-ready", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
            calibrationPointA = ImagePoint(10.0, 20.0), calibrationPointB = ImagePoint(10.0, 200.0),
            calibrationFrameIndex = 0,
            calibration = MetricCalibration(0, ImagePoint(10.0, 20.0), ImagePoint(10.0, 200.0), 0.5, 0.005),
        )
        var starts = 0
        wrap {
            Box(Modifier.size(360.dp, 420.dp)) {
                EncoderReferencePane(
                    Modifier.size(360.dp, 420.dp), calibrated,
                    PlateDetectionUiState(PlateDetectionPhase.IDLE),
                    TrackingUiState(phase = TrackingPhase.READY),
                    true, true, false,
                    onChoose = {}, onConfirm = {}, onDiameter = {},
                    onAuto = {}, onManual = {}, onRetry = {}, onOtherReference = {},
                    onGeometry = { _, _ -> }, onEnlarge = {},
                    onStart = { starts++ }, onCancel = {}, onMoment = {}, showMoments = false,
                )
            }
        }
        // Separate neutral READY copy: scale ready, no plate-specific verb.
        compose.onNodeWithText(label(R.string.encoder_reference_ready)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_follow)).assertIsDisplayed()
        compose.onNodeWithTag("reference-primary").assertIsDisplayed().assertIsEnabled()
        assertEquals("Start tracking", label(R.string.encoder_reference_follow))
        compose.onNodeWithTag("reference-primary").performClick()
        assertEquals(1, starts)
    }

    @Test fun calibratedTargetInstructionIsSeparateFromScaleCopy() {
        wrap(language = "en") {}
        assertEquals(
            "Tap a textured visible zone you want to track. The zone inside the rectangle will be followed. You can change it without altering the scale.",
            label(R.string.encoder_reference_target_instruction),
        )
    }
}
