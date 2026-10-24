package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
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

/** A/B enlarge route: entry point, local edit, cancel vs return. No Room, settings, or media. */
@RunWith(AndroidJUnit4::class)
class EncoderAbPrecisionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val draft = EncoderDraft(sessionKey = "ab-zoom-test", athleteId = 1L,
        setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0)
    private lateinit var strings: Context

    private fun themed(
        width: Int = 360,
        height: Int = 560,
        font: Float = 1f,
        language: String = "en",
        dark: Boolean = true,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides strings, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, font),
                LocalUnitSystem provides UnitProfile.METRIC) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.size(width.dp, height.dp)) { content() }
                }
            }
        }
    }

    @Test fun manualRouteOffersEnlargeBeforeAnyPointsWithA48dpTarget() {
        var enlarged = 0
        themed {
            EncoderReferencePane(Modifier.size(360.dp, 560.dp), draft,
                PlateDetectionUiState(PlateDetectionPhase.MANUAL), TrackingUiState(),
                chosen = true, editable = true, canConfirm = false,
                onChoose = {}, onConfirm = {}, onDiameter = {}, onAuto = {}, onManual = {},
                onRetry = {}, onOtherReference = {}, onGeometry = { _, _ -> },
                onEnlarge = {}, onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                onEnlargeAb = { enlarged++ }, abEnlargeReady = true, abBusy = false)
        }
        compose.onNodeWithTag("reference-ab-enlarge").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reference-ab-enlarge").performClick()
        assertEquals(1, enlarged)
    }

    @Test fun manualEnlargeStaysDisabledWithoutAnEditableFrameAndShowsBusy() {
        themed {
            EncoderReferencePane(Modifier.size(360.dp, 560.dp), draft,
                PlateDetectionUiState(PlateDetectionPhase.MANUAL), TrackingUiState(),
                chosen = true, editable = false, canConfirm = false,
                onChoose = {}, onConfirm = {}, onDiameter = {}, onAuto = {}, onManual = {},
                onRetry = {}, onOtherReference = {}, onGeometry = { _, _ -> },
                onEnlarge = {}, onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                onEnlargeAb = {}, abEnlargeReady = false, abBusy = true)
        }
        compose.onNodeWithTag("reference-ab-enlarge").assertIsNotEnabled()
    }

    @Test fun manualEnlargeSurvivesLargeFonts() {
        themed(font = 1.5f, language = "es") {
            EncoderReferencePane(Modifier.size(360.dp, 560.dp), draft,
                PlateDetectionUiState(PlateDetectionPhase.MANUAL), TrackingUiState(),
                chosen = true, editable = true, canConfirm = false,
                onChoose = {}, onConfirm = {}, onDiameter = {}, onAuto = {}, onManual = {},
                onRetry = {}, onOtherReference = {}, onGeometry = { _, _ -> },
                onEnlarge = {}, onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                onEnlargeAb = {}, abEnlargeReady = true, abBusy = false)
        }
        compose.onNodeWithTag("reference-ab-enlarge").assertIsEnabled().assertHeightIsAtLeast(48.dp)
    }

    private fun anchor(liveA: ImagePoint? = null, liveB: ImagePoint? = null) = AbEditorAnchor(
        sessionKey = "ab-zoom-test", videoUri = null, renderedIndex = 3, renderedPtsUs = 30_000L,
        videoWidth = 200.0, videoHeight = 100.0, liveA = liveA, liveB = liveB,
    )

    private fun bitmap(): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)

    private fun tapCenterThenRight() {
        val node = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset(bounds.size.width / 2f, bounds.size.height / 2f))
        }
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset(bounds.size.width / 2f + 40f, bounds.size.height / 2f))
        }
    }

    @Test fun editorReturnPublishesTheLocallyTappedPairAndCancelPublishesNothing() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        var dismissed = 0
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null,
                onDismiss = { dismissed++ },
                onReturn = { a, b -> returned = a to b })
        }
        tapCenterThenRight()
        compose.onNodeWithTag("ab-precision-return").performClick()
        val pair = requireNotNull(returned)
        assertNotNull(pair.first)
        assertNotNull(pair.second)
        assertNotEquals(pair.first, pair.second)
        // Taps share the same height and run left to right in source coordinates.
        assertTrue(pair.second!!.x > pair.first!!.x)
        assertEquals(1, dismissed)
    }

    @Test fun editorReturnWithEmptyLocalPairPublishesNothing() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        var dismissed = 0
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null,
                onDismiss = { dismissed++ },
                onReturn = { a, b -> returned = a to b })
        }
        // Return with an empty local pair only dismisses: nothing to publish.
        compose.onNodeWithTag("ab-precision-return").performClick()
        assertNull(returned)
        assertEquals(1, dismissed)
    }

    @Test fun editorCancelFromPartialKeepsLiveStateUntouched() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        var dismissed = 0
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null,
                onDismiss = { dismissed++ },
                onReturn = { a, b -> returned = a to b })
        }
        // Single tap creates a local A-only pair, then top-bar cancel discards it.
        val node = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset(bounds.size.width / 2f, bounds.size.height / 2f))
        }
        compose.onNodeWithContentDescription(strings.getString(R.string.common_cancel)).performClick()
        assertNull(returned)
        assertEquals(1, dismissed)
    }

    @Test fun editorCancelViaTopBarDiscardsATappedPoint() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null,
                onDismiss = {},
                onReturn = { a, b -> returned = a to b })
        }
        val node = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset(bounds.size.width / 2f, bounds.size.height / 2f))
        }
        compose.onNodeWithContentDescription(strings.getString(R.string.common_cancel)).performClick()
        assertNull(returned)
    }

    @Test fun editorZoomControlsKeep48dpTargetsAtLargeFonts() {
        var dismissed = 0
        themed(font = 1.5f, language = "es") {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null, onDismiss = { dismissed++ }, onReturn = { _, _ -> })
        }
        compose.onNodeWithContentDescription(strings.getString(R.string.selector_zoom_in_description))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(strings.getString(R.string.selector_zoom_out_description))
            .assertHeightIsAtLeast(48.dp)
        // Narrow + fontScale 1.5 must keep Return displayed and clickable (scrollable body, pinned CTA).
        compose.onNodeWithTag("ab-precision-return").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("ab-precision-return").assertIsDisplayed()
        compose.onNodeWithTag("ab-precision-return").performClick()
        assertEquals(1, dismissed)
        // Title comes from the new localized strings, not a hardcoded literal.
        // Note: title assertion needs a fresh composition (previous click dismissed the dialog).
    }

    @Test fun editorTitleUsesExactESStringsInsideDialog() {
        themed(font = 1.5f, language = "es") {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null, onDismiss = {}, onReturn = { _, _ -> })
        }
        compose.onNodeWithText(strings.getString(R.string.encoder_reference_ab_editor_title)).assertExists()
        compose.onNodeWithText(strings.getString(R.string.encoder_reference_ab_editor_hint)).assertExists()
    }

    @Test fun continuousPanMovesTheReferenceAcrossMultipleMotionEvents() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(), initialA = null, initialB = null,
                onDismiss = {}, onReturn = { a, b -> returned = a to b })
        }
        repeat(2) {
            compose.onNodeWithContentDescription(strings.getString(R.string.selector_zoom_in_description)).performClick()
        }
        val bounds = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode().boundsInRoot
        val center = Offset(bounds.width / 2f, bounds.height / 2f)
        val shift = minOf(240f, bounds.width / 3f)
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            swipe(center, center + Offset(shift, 0f), durationMillis = 480)
        }
        compose.onNodeWithTag("ab-precision-video").performTouchInput { click(center) }
        compose.onNodeWithTag("ab-precision-return").performClick()
        val point = requireNotNull(requireNotNull(returned).first)
        // Independent affine expectation for 200x100 at FIT then 2.25x:
        // positive content-pan moves the source under the viewport center left.
        // Touch slop consumes up to 48 viewport px; never just one tiny move.
        val fitWidth = minOf(bounds.width.toDouble(), bounds.height.toDouble() * 2.0)
        val minimumMovement = (shift - 48.0) * 199.0 / (fitWidth * 2.25)
        val maximumMovement = (shift + 4.0) * 199.0 / (fitWidth * 2.25)
        assertTrue("Continuous pan was lost: center mapped to ${point.x}", point.x <= 99.5 - minimumMovement)
        assertTrue("Pan exceeded finger movement: ${point.x}", point.x >= 99.5 - maximumMovement)
        assertEquals(49.5, point.y, 0.5)
    }

    @Test fun editorZoomOutRecentersToFITAfterPan() {
        var returned: Pair<ImagePoint?, ImagePoint?>? = null
        themed {
            EncoderAbPrecisionEditor(bitmap(), anchor(),
                initialA = null, initialB = null,
                onDismiss = {},
                onReturn = { a, b -> returned = a to b })
        }
        // Zoom in twice (1x -> 1.5x -> 2.25x), pan with a swipe, then zoom out to 1x.
        // Clamp wiring must reset pan so 1x is centered FIT again.
        val zoomIn = compose.onNodeWithContentDescription(strings.getString(R.string.selector_zoom_in_description))
        val zoomOut = compose.onNodeWithContentDescription(strings.getString(R.string.selector_zoom_out_description))
        zoomIn.performClick()
        zoomIn.performClick()
        val node = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val startX = bounds.size.width / 2f
        val startY = bounds.size.height / 2f
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            down(0, Offset(startX, startY))
            moveBy(Offset(120f, 0f))
            up(0)
        }
        // Back to 1x (two outs from 2.25x).
        zoomOut.performClick()
        zoomOut.performClick()
        // Center tap at 1x must map near the video center (200x100 -> 99.5,49.5)
        // when pan was correctly reset; a stale pan would shift it by tens of px.
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset(bounds.size.width / 2f, bounds.size.height / 2f))
        }
        compose.onNodeWithTag("ab-precision-return").performClick()
        val pair = requireNotNull(returned)
        val a = requireNotNull(pair.first)
        assertTrue("Center tap after zoom-out must recenter (x=${a.x})", kotlin.math.abs(a.x - 99.5) < 15.0)
        assertTrue("Center tap after zoom-out must recenter (y=${a.y})", kotlin.math.abs(a.y - 49.5) < 15.0)
    }
}
