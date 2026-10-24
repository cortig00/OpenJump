package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.LocalUnitSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** No settings, Room, or media are mutated by these layout/interaction tests. */
@RunWith(AndroidJUnit4::class)
class EncoderReferencePaneInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val draft = EncoderDraft(sessionKey = "reference-ui-test", athleteId = 1L,
        setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0)
    private var chosen = mutableStateOf(false)
    private var manualReference = mutableStateOf(false)
    private var proposal = mutableStateOf<PlateCalibrationProposal?>(null)
    private var chooseCount = 0
    private var confirmCount = 0
    private var autoCount = 0
    private var manualCount = 0
    private var otherCount = 0
    private var diameter = 0.0
    private lateinit var strings: Context

    private fun show(width: Int = 360, height: Int = 350, font: Float = 1f,
                     language: String = "en", dark: Boolean = true, units: UnitProfile = UnitProfile.METRIC) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides strings, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, font), LocalUnitSystem provides units) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        val plate = if (proposal.value != null) PlateDetectionUiState(PlateDetectionPhase.REVIEW, proposal = proposal.value)
                            else PlateDetectionUiState(when {
                                manualReference.value -> PlateDetectionPhase.MANUAL
                                chosen.value -> PlateDetectionPhase.ASSISTED_REQUIRED
                                else -> PlateDetectionPhase.IDLE
                            })
                        EncoderReferencePane(Modifier.size(width.dp, height.dp), draft, plate, TrackingUiState(),
                            chosen.value, true, plate.canConfirm,
                            onChoose = { chooseCount++; manualReference.value = false; chosen.value = true }, onConfirm = { confirmCount++ },
                            onDiameter = { diameter = it }, onAuto = { autoCount++ }, onManual = { manualCount++ },
                            onRetry = {}, onOtherReference = { otherCount++; manualReference.value = true; chosen.value = true }, onGeometry = { c, d -> proposal.value = proposal.value?.copy(center = c, diameterPx = d, adjusted = true) },
                            onEnlarge = {}, onStart = {}, onCancel = {}, onMoment = {}, showMoments = false)
                    }
                }
            }
        }
    }
    private fun label(id: Int) = strings.getString(id)
    private fun manualProposal() = PlateCalibrationProposal("reference-ui-test", "fixture", 1, 0, 0,
        640, 480, null, ImagePoint(320.0, 240.0), 120.0, 0.0, null, false, true)

    @Test fun initialOffersTwoPeerMethodsWithoutDiameterPreload() {
        show()
        // No generic choose-in-video primary before a method is chosen.
        compose.onNodeWithTag("reference-primary").assertDoesNotExist()
        // Diameter stays hidden on the initial choice.
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertDoesNotExist()
        compose.onNodeWithTag("reference-method-plate").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("reference-method-distance").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(label(R.string.encoder_reference_method_hint)).assertIsDisplayed()
        // Plate route keeps the same callback/guard and reveals method-specific controls.
        compose.onNodeWithTag("reference-method-plate").performClick()
        assertEquals(1, chooseCount)
        assertEquals(0, otherCount)
        compose.onNodeWithText(label(R.string.encoder_reference_manual)).assertIsEnabled().performClick()
        assertEquals(1, manualCount)
        compose.onNodeWithText(label(R.string.encoder_reference_auto)).assertIsEnabled().performClick()
        assertEquals(1, autoCount)
        assertEquals(0, confirmCount)
        // Diameter appears only after the plate method is chosen.
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertIsDisplayed()
    }

    @Test fun distanceRouteUsesExistingManualCallbackWithoutDiameter() {
        show()
        compose.onNodeWithTag("reference-method-distance").performClick()
        assertEquals(1, otherCount)
        assertEquals(0, chooseCount)
        assertEquals(0, confirmCount)
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertDoesNotExist()
    }

    @Test fun manualRequiresAdjustmentAndNeverConfirmsImplicitly() {
        chosen.value = true; proposal.value = manualProposal()
        show(height = 440)
        compose.onNodeWithTag("reference-primary").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.encoder_reference_adjust)).performClick()
        compose.onNodeWithText(label(R.string.encoder_reference_bigger)).performScrollTo().performClick()
        assertEquals(122.0, requireNotNull(proposal.value).diameterPx, 0.0)
        compose.onNodeWithTag("reference-primary").assertIsEnabled()
        assertEquals(0, confirmCount)
        compose.onNodeWithTag("reference-primary").performClick()
        assertEquals(1, confirmCount)
    }

    @Test fun diameterRemainsEditableDuringReviewWithoutMovingCircle() {
        chosen.value = true; proposal.value = manualProposal().copy(adjusted = true)
        show()
        val before = proposal.value
        compose.onNodeWithText(label(R.string.selector_plate_diameter_change)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("50")
        compose.onNodeWithText(label(R.string.common_apply)).performClick()
        assertEquals(50.0, diameter, 0.0)
        assertEquals(before, proposal.value)
        assertEquals(0, confirmCount)
    }

    @Test fun smallSpanishLightPaneAtLargeFontKeepsPrimaryInsidePane() {
        chosen.value = true; proposal.value = manualProposal().copy(adjusted = true)
        show(width = 320, height = 300, font = 1.5f, language = "es", dark = false)
        compose.onNodeWithTag("reference-primary").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertIsDisplayed()
        val pane = compose.onNodeWithTag("reference-pane").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("reference-primary").fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= pane.top && button.bottom <= pane.bottom)
        compose.onNodeWithTag("reference-primary").performClick()
        assertEquals(1, confirmCount)
    }

    @Test fun narrowSidePaneWrapsSecondaryActionsInsteadOfClippingThem() {
        chosen.value = true; proposal.value = manualProposal().copy(adjusted = true)
        show(width = 180, height = 360, font = 1.3f)
        compose.onNodeWithTag("reference-primary").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_adjust)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.selector_cursor_right)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(321.0, requireNotNull(proposal.value).center.x, 0.0)
        compose.onNodeWithTag("reference-primary").assertIsDisplayed()
    }

    @Test fun imperialDiameterUsesInchesAndConvertsToCanonicalCm() {
        // Diameter only appears after the plate method is chosen (P1/P2); initial choice shows no diameter.
        chosen.value = true
        show(units = UnitProfile.UNITED_STATES)
        compose.onNode(SemanticsMatcher("reference diameter in inches") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.endsWith(" in") } == true
        }).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_plate_diameter_change)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("20")
        compose.onNodeWithText(label(R.string.common_apply)).performClick()
        assertEquals(50.8, diameter, 0.001)
        assertEquals(0, confirmCount)
    }

    @Test fun englishDarkPaneAtFont130KeepsReferenceAndCTAVisible() {
        chosen.value = true; proposal.value = manualProposal().copy(adjusted = true)
        show(width = 320, height = 300, font = 1.3f)
        compose.onNodeWithTag("reference-primary").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertIsDisplayed()
        assertEquals(0, autoCount)
    }
}
