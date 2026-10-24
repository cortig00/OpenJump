package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.settings.UnitProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** UI-only constraints/semantics/callback contract. No Room, device settings, decoder or algorithm mutation. */
@RunWith(AndroidJUnit4::class)
class EncoderReferenceProductUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var proposal = mutableStateOf(PlateCalibrationProposal("fine-ui", "fixture", 1, 0, 0,
        640, 480, null, ImagePoint(320.0, 240.0), 120.0, 0.0, null, true, false))
    private var calls = 0
    private var density = 1f
    private lateinit var strings: Context
    private val directions = listOf(R.string.selector_cursor_left, R.string.selector_cursor_up,
        R.string.selector_cursor_down, R.string.selector_cursor_right)

    private fun showFine(width: Int = 360, height: Int = 500, font: Float = 1f,
                         language: String = "en", enabled: Boolean = true) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides strings, LocalConfiguration provides config,
                LocalDensity provides Density(density, font)) {
                MaterialTheme(colorScheme = if (language == "es") lightColorScheme() else darkColorScheme()) {
                    Box(Modifier.size(width.dp, height.dp).testTag("fine-host")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            PlateReferenceFineControls(proposal.value, enabled, { center, diameter ->
                                calls++; proposal.value = proposal.value.copy(center = center, diameterPx = diameter)
                            })
                        }
                    }
                }
            }
        }
    }
    private fun label(id: Int) = strings.getString(id)
    private fun direction(id: Int) = compose.onNodeWithContentDescription(label(id))

    @Test fun positionPadIsSpatialAndCentredWithFourFull48dpTargets() {
        showFine()
        val left = direction(R.string.selector_cursor_left).fetchSemanticsNode().boundsInRoot
        val up = direction(R.string.selector_cursor_up).fetchSemanticsNode().boundsInRoot
        val down = direction(R.string.selector_cursor_down).fetchSemanticsNode().boundsInRoot
        val right = direction(R.string.selector_cursor_right).fetchSemanticsNode().boundsInRoot
        assertTrue(up.center.y < left.center.y && down.center.y > left.center.y)
        assertEquals(left.center.y, right.center.y, 1f)
        assertEquals(up.center.x, down.center.x, 1f)
        assertTrue(left.center.x < up.center.x && right.center.x > up.center.x)
        val host = compose.onNodeWithTag("fine-host").fetchSemanticsNode().boundsInRoot
        val pad = compose.onNodeWithTag("reference-position-pad").fetchSemanticsNode().boundsInRoot
        assertEquals(host.center.x, pad.center.x, 1f)
        for (bounds in listOf(left, up, down, right)) {
            assertTrue(bounds.width >= 48 * density - 1 && bounds.height >= 48 * density - 1)
            assertTrue(bounds.left >= host.left + 16 * density && bounds.right <= host.right - 16 * density)
        }
        compose.onAllNodes(hasAnyAncestor(hasTestTag("reference-position-pad")) and hasClickAction()).assertCountEquals(4)
    }

    @Test fun everyNudgeAndSizeTapKeepsExactExistingParametersAndNoImplicitCommit() {
        showFine()
        direction(R.string.selector_cursor_up).performClick()
        assertEquals(ImagePoint(320.0, 239.0), proposal.value.center)
        direction(R.string.selector_cursor_left).performClick()
        assertEquals(ImagePoint(319.0, 239.0), proposal.value.center)
        direction(R.string.selector_cursor_down).performClick()
        direction(R.string.selector_cursor_right).performClick()
        assertEquals(ImagePoint(320.0, 240.0), proposal.value.center)
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_smaller)).performScrollTo().performClick()
        assertEquals(118.0, proposal.value.diameterPx, 0.0)
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performScrollTo().performClick()
        assertEquals(120.0, proposal.value.diameterPx, 0.0)
        assertEquals(6, calls)
        assertEquals("fine-ui", proposal.value.sessionKey)
    }

    @Test fun sizeMinimumIsStillEightPixels() {
        proposal.value = proposal.value.copy(diameterPx = 8.0)
        showFine()
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_smaller)).performScrollTo().performClick()
        assertEquals(8.0, proposal.value.diameterPx, 0.0)
        assertEquals(1, calls)
    }

    @Test fun disabledFineControlsDispatchNothing() {
        showFine(enabled = false)
        for (id in directions) direction(id).assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_smaller)).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, calls)
    }

    @Test fun narrowSidePaneDoesNotShrinkOrClipPositionTargets() {
        showFine(width = 180, height = 420, font = 1.3f)
        val fine = compose.onNodeWithTag("reference-fine-controls").fetchSemanticsNode().boundsInRoot
        for (id in directions) {
            val node = direction(id).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(node.width >= 48 * density - 1 && node.height >= 48 * density - 1)
            assertTrue(node.left >= fine.left && node.right <= fine.right)
        }
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(122.0, proposal.value.diameterPx, 0.0)
    }

    @Test fun spanishLargeFontShowsDistinctPositionAndSizeSections() {
        showFine(width = 320, height = 420, font = 1.5f, language = "es")
        compose.onNodeWithText(label(R.string.encoder_reference_position)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_position_step)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_size)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_size_step)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performScrollTo().performClick()
        assertEquals(122.0, proposal.value.diameterPx, 0.0)
    }

    @Test fun compactLargeFontHasAllSixFullTargetsVisibleTogether() {
        showFine(width = 312, height = 195, font = 1.5f)
        val host = compose.onNodeWithTag("fine-host").fetchSemanticsNode().boundsInRoot
        for (id in directions + listOf(R.string.encoder_reference_smaller, R.string.encoder_reference_bigger)) {
            val node = compose.onNodeWithContentDescription(label(id)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Entire target must fit in compact tool viewport: $node within $host", node.top >= host.top && node.bottom <= host.bottom)
            assertTrue(node.width >= 48 * density - 1 && node.height >= 48 * density - 1)
        }
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performClick()
        assertEquals(122.0, proposal.value.diameterPx, 0.0)
    }

    @Test fun manualABLargeFontStacksTheFullWidthInputAndKeepsCanonicalLength() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        strings = context.createConfigurationContext(config)
        val draft = EncoderDraft(sessionKey = "manual-ui", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0).copy(
            calibrationPointA = ImagePoint(10.0, 20.0), calibrationPointB = ImagePoint(10.0, 200.0))
        var length = 0.0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides strings, LocalConfiguration provides config,
                LocalUnitSystem provides UnitProfile.METRIC,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme {
                    Column(Modifier.size(320.dp, 340.dp).testTag("manual-host")) {
                        ManualEncoderCalibration(draft, label(R.string.encoder_reference_other), { length = it })
                    }
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val host = compose.onNodeWithTag("manual-host").fetchSemanticsNode().boundsInRoot
        assertTrue(field.fetchSemanticsNode().boundsInRoot.width >= host.width * 0.95)
        field.performTextReplacement("0.60")
        compose.onNodeWithText(label(R.string.common_confirm)).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(0.6, length, 0.000001)
    }
}
