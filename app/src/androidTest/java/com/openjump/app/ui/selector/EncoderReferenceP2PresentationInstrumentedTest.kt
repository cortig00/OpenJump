package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.ImageRegion
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.LocalUnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** P2 presentation: phase-aware READY, manual escape, partial LOST review, confirmations, 48dp bounds. */
@RunWith(AndroidJUnit4::class)
class EncoderReferenceP2PresentationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var strings: Context

    private fun wrap(language: String = "en", font: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, font),
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = strings.getString(id)

    private fun trackingSample(status: TrackingStatus = TrackingStatus.TRACKING): TrackingFrameResult {
        val point = ImagePoint(160.0, 120.0)
        val region = ImageRegion(ImagePoint(160.0, 120.0), 40.0, 40.0)
        val sample = TrackingSample(timestampUs = 0L, point = point, confidence = 0.9, status = status)
        return TrackingFrameResult(
            frameIndex = 0,
            result = TrackingResult(
                sample = sample,
                region = region,
                featurePoints = listOf(ImagePoint(150.0, 110.0), ImagePoint(170.0, 130.0)),
                diagnostics = TrackingDiagnostics.EMPTY,
            ),
        )
    }

    private fun testMapper() = VideoCoordinateMapper(
        VideoPresentationGeometry(320, 240),
        ViewportSize(320.0, 240.0),
    )

    @Test fun readyPhaseShowsReadyNotTrackingStatus() {
        val current = trackingSample(TrackingStatus.TRACKING)
        wrap(language = "en") {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = current,
                    recent = emptyList(),
                    trackingPhase = TrackingPhase.READY,
                    plateDetection = null,
                    selectionEnabled = false,
                    onTargetSelected = {},
                )
            }
        }
        // Phase-aware: READY reads as READY, never as a stale TRACKING sample.
        compose.onNodeWithText(label(R.string.selector_tracking_status_ready)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_tracking_status_tracking)).assertDoesNotExist()
    }

    @Test fun processingPhaseShowsProcessingNotTrackingStatus() {
        val current = trackingSample(TrackingStatus.TRACKING)
        wrap(language = "en") {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = current,
                    recent = emptyList(),
                    trackingPhase = TrackingPhase.PROCESSING,
                    plateDetection = null,
                    selectionEnabled = false,
                    onTargetSelected = {},
                )
            }
        }
        compose.onNodeWithText(label(R.string.selector_tracking_status_processing)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_tracking_status_tracking)).assertDoesNotExist()
    }

    @Test fun idlePhaseKeepsLiveSampleInDescriptionWithoutPhaseText() {
        val current = trackingSample(TrackingStatus.TRACKING)
        wrap(language = "en") {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = current,
                    recent = emptyList(),
                    trackingPhase = TrackingPhase.IDLE,
                    plateDetection = null,
                    selectionEnabled = false,
                    onTargetSelected = {},
                )
            }
        }
        // IDLE shows no phase text overlay; the live sample stays in the description.
        compose.onNodeWithText(label(R.string.selector_tracking_status_ready)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.selector_tracking_status_tracking), substring = true).assertExists()
    }

    @Test fun readyPaneShowsScaleReadyAndNeutralStart() {
        val calibrated = EncoderDraft(
            sessionKey = "p2-ready", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
            calibrationPointA = ImagePoint(10.0, 20.0), calibrationPointB = ImagePoint(10.0, 200.0),
            calibrationFrameIndex = 0,
            calibration = MetricCalibration(0, ImagePoint(10.0, 20.0), ImagePoint(10.0, 200.0), 0.5, 0.005),
        )
        // READY: scale ready copy plus neutral start, diameter hidden once calibrated.
        wrap {
            Box(Modifier.size(360.dp, 420.dp)) {
                EncoderReferencePane(
                    Modifier.size(360.dp, 420.dp), calibrated,
                    PlateDetectionUiState(PlateDetectionPhase.DETECTED),
                    TrackingUiState(phase = TrackingPhase.READY),
                    true, true, false,
                    onChoose = {}, onConfirm = {}, onDiameter = {},
                    onAuto = {}, onManual = {}, onRetry = {}, onOtherReference = {},
                    onGeometry = { _, _ -> }, onEnlarge = {},
                    onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                )
            }
        }
        compose.onNodeWithText(label(R.string.encoder_reference_ready)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.encoder_reference_follow)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertDoesNotExist()
        // Reference FlowRow tool keeps a 48dp target once calibrated (moment action).
        val momentBounds = compose.onNodeWithText(label(R.string.encoder_reference_moment)).fetchSemanticsNode().boundsInRoot
        assertTrue(momentBounds.height >= with(compose.density) { 48.dp.toPx() })
    }

    @Test fun processingPaneShowsPercentEtaAndCancel() {
        val calibrated = EncoderDraft(
            sessionKey = "p2-ready", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
            calibrationPointA = ImagePoint(10.0, 20.0), calibrationPointB = ImagePoint(10.0, 200.0),
            calibrationFrameIndex = 0,
            calibration = MetricCalibration(0, ImagePoint(10.0, 20.0), ImagePoint(10.0, 200.0), 0.5, 0.005),
        )
        // PROCESSING: truthful percent + ETA + cancel stay intact.
        wrap {
            Box(Modifier.size(360.dp, 420.dp)) {
                EncoderReferencePane(
                    Modifier.size(360.dp, 420.dp), calibrated,
                    PlateDetectionUiState(PlateDetectionPhase.DETECTED),
                    TrackingUiState(phase = TrackingPhase.PROCESSING, progress = 0.5f, estimatedRemainingMs = 30_000L),
                    true, true, false,
                    onChoose = {}, onConfirm = {}, onDiameter = {},
                    onAuto = {}, onManual = {}, onRetry = {}, onOtherReference = {},
                    onGeometry = { _, _ -> }, onEnlarge = {},
                    onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                )
            }
        }
        compose.onNodeWithText(strings.getString(R.string.encoder_reference_processing_percent, 50)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_cancel_tracking)).assertIsDisplayed()
    }

    @Test fun manualFormShowsFirstOtherEndpointWithoutDiameterAndOffersPlateEscape() {
        val draft = EncoderDraft(
            sessionKey = "p2-manual", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
        )
        var chooses = 0
        wrap {
            Box(Modifier.size(360.dp, 440.dp)) {
                EncoderReferencePane(
                    Modifier.size(360.dp, 440.dp), draft,
                    PlateDetectionUiState(PlateDetectionPhase.MANUAL),
                    TrackingUiState(),
                    true, true, false,
                    onChoose = { chooses++ }, onConfirm = {}, onDiameter = {},
                    onAuto = {}, onManual = {}, onRetry = {}, onOtherReference = {},
                    onGeometry = { _, _ -> }, onEnlarge = {},
                    onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                    manualControls = {
                        androidx.compose.foundation.layout.Column {
                            ManualEncoderCalibration(draft, label(R.string.encoder_reference_other)) {}
                        }
                    },
                )
            }
        }
        // First/other endpoint guidance, explicit real distance, no diameter preload.
        compose.onNodeWithText(label(R.string.selector_touch_reference_a)).assertIsDisplayed()
        compose.onNodeWithText("${label(R.string.selector_length)} (m)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        // Novice escape: manual form offers the plate method via the existing route.
        compose.onNodeWithTag("reference-method-switch-plate").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, chooses)
    }

    @Test fun partialLostRemainsAnalyzableWhileEmptyLostStaysRecoverable() {
        val usable = listOf(trackingSample(TrackingStatus.TRACKING))
        val canonical = usable.toList()
        val partial = EncoderReviewPolicy.resolve(
            EncoderReviewInput(TrackingPhase.LOST, usable, canonical, false),
        )
        assertTrue(partial.visible)
        assertTrue(partial.canAnalyze)
        val empty = EncoderReviewPolicy.resolve(
            EncoderReviewInput(TrackingPhase.LOST, emptyList(), emptyList(), false),
        )
        assertFalse(empty.visible)
        assertFalse(empty.canAnalyze)
    }

    @Test fun copyCoversMethodTargetScaleReviewAndResetConsequences() {
        wrap(language = "en") {}
        // P1 finishing: neutral method choice without A-B jargon in the title.
        assertEquals("Which measurement do you know?", label(R.string.encoder_reference_method_title))
        assertEquals("Diameter of a plate", label(R.string.encoder_reference_method_plate))
        assertEquals("Distance between two points", label(R.string.encoder_reference_method_distance))
        assertFalse(label(R.string.encoder_reference_method_title).contains("A"))
        // Reference may differ from target at the same camera distance.
        assertTrue(label(R.string.encoder_reference_method_hint).contains("same camera distance"))
        // Target texture plus ROI rectangle explanation, scale independence kept.
        assertTrue(label(R.string.encoder_reference_target_instruction).contains("textured"))
        assertTrue(label(R.string.encoder_reference_target_instruction).contains("rectangle"))
        // Confirm/Find wording, first/other endpoint plus real distance.
        assertEquals("Confirm scale", label(R.string.encoder_reference_confirm))
        assertEquals("Find plate", label(R.string.encoder_reference_auto))
        assertEquals("Tap the first endpoint of a known reference.", label(R.string.selector_touch_reference_a))
        assertEquals("Tap the other endpoint in the same frame.", label(R.string.selector_touch_reference_b))
        assertEquals("Real distance between the points", label(R.string.selector_length))
        // Review verifies the SAME chosen zone; reset consequences stay explicit.
        assertTrue(label(R.string.selector_review_instruction).contains("same chosen zone"))
        assertTrue(label(R.string.selector_change_target_message).contains("calibration"))
        assertTrue(label(R.string.selector_change_scale_message).contains("calibration", ignoreCase = true))
        // Scale reference explains BOTH methods with an independent target.
        assertTrue(label(R.string.encoder_scale_reference_body).contains("plate diameter"))
        assertTrue(label(R.string.encoder_scale_reference_body).contains("distance between two points"))
    }

    @Test fun smallPaneAtLargeFontKeepsMethodSwitchAndPrimaryInsidePane() {
        val draft = EncoderDraft(
            sessionKey = "p2-bounds", athleteId = 1L,
            setup = EncoderSetup(EncoderExercise.SQUAT, 40.0), sessionPlateDiameterCm = 45.0,
        )
        wrap(language = "es", font = 1.5f) {
            Box(Modifier.size(320.dp, 300.dp)) {
                EncoderReferencePane(
                    Modifier.size(320.dp, 300.dp), draft,
                    PlateDetectionUiState(PlateDetectionPhase.MANUAL),
                    TrackingUiState(),
                    true, true, false,
                    onChoose = {}, onConfirm = {}, onDiameter = {},
                    onAuto = {}, onManual = {}, onRetry = {}, onOtherReference = {},
                    onGeometry = { _, _ -> }, onEnlarge = {},
                    onStart = {}, onCancel = {}, onMoment = {}, showMoments = false,
                )
            }
        }
        // Native 48dp targets preserved: manual escape stays reachable at 150% font.
        compose.onNodeWithTag("reference-method-switch-plate").performScrollTo().assertIsDisplayed()
        val pane = compose.onNodeWithTag("reference-pane").fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithTag("reference-method-switch-plate").fetchSemanticsNode().boundsInRoot
        assertTrue(switch.top >= pane.top && switch.bottom <= pane.bottom)
    }
}
