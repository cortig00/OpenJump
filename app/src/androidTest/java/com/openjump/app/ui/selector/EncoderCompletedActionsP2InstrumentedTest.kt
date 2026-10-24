package com.openjump.app.ui.selector

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** P2-remaining: actual EncoderCompletedActions/export-disclosure/LOST composable behavior. */
@RunWith(AndroidJUnit4::class)
class EncoderCompletedActionsP2InstrumentedTest {
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

    private fun wrapSized(width: Int, height: Int, font: Float, language: String = "en", content: @androidx.compose.runtime.Composable () -> Unit) {
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

    private fun sample(status: TrackingStatus = TrackingStatus.TRACKING): TrackingFrameResult {
        val point = ImagePoint(160.0, 120.0)
        val region = ImageRegion(ImagePoint(160.0, 120.0), 40.0, 40.0)
        return TrackingFrameResult(
            frameIndex = 0,
            result = TrackingResult(
                sample = TrackingSample(timestampUs = 0L, point = point.takeUnless { status == TrackingStatus.LOST }, confidence = 0.9, status = status),
                region = region.takeUnless { status == TrackingStatus.LOST },
                featurePoints = emptyList(),
                diagnostics = TrackingDiagnostics.EMPTY,
            ),
        )
    }

    private fun testMapper() = VideoCoordinateMapper(
        VideoPresentationGeometry(320, 240),
        ViewportSize(320.0, 240.0),
    )

    @Test fun idleExportCollapsedByDefaultAndExpandsToRealExportCallback() {
        var exports = 0
        val state = TrackingUiState(phase = TrackingPhase.COMPLETED, results = listOf(sample()), message = "done")
        wrap {
            Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    EncoderCompletedActions(
                        state, TrajectoryExportUiState(phase = TrajectoryExportPhase.IDLE),
                        onExport = { exports++ }, onCancelExport = {}, onSaveExport = {}, onShareExport = {},
                        onRequestTargetReselection = {}, onRequestRecalibration = {},
                    )
                }
            }
        }
        // Review instruction verifying the SAME chosen zone stays visible.
        compose.onNodeWithText(label(R.string.selector_review_instruction)).assertIsDisplayed()
        assertTrue(label(R.string.selector_review_instruction).contains("same chosen zone"))
        // IDLE export is collapsed: disclosure visible with 48dp + tag, real action hidden.
        compose.onNodeWithTag("export-disclosure").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("export-action").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.selector_export_show_options)).assertIsDisplayed()
        // Disclosure carries an explicit stateDescription for screen readers.
        val disclosure = compose.onNodeWithTag("export-disclosure").fetchSemanticsNode()
        assertTrue((disclosure.config.getOrNull(SemanticsProperties.StateDescription) ?: "").isNotBlank())
        // Expanding reveals the real export control with the same callback/gate.
        compose.onNodeWithTag("export-disclosure").performClick()
        compose.onNodeWithTag("export-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(label(R.string.selector_export_hide_options)).assertIsDisplayed()
        compose.onNodeWithTag("export-action").performClick()
        assertEquals(1, exports)
        // COMPLETED without loss shows no partial warning.
        compose.onNodeWithTag("review-lost-partial").assertDoesNotExist()
    }

    @Test fun lostShowsPartialWarningWithSameZoneCheckAndDistinctTargetScaleCallbacks() {
        var targets = 0
        var scales = 0
        var exports = 0
        // strings is not initialized until wrap() runs: resolve the message from the
        // application context first, then wrap once below.
        val lostMessage = context.getString(R.string.selector_tracking_lost, 12)
        val lost = TrackingUiState(
            phase = TrackingPhase.LOST,
            results = listOf(sample(), sample(TrackingStatus.LOST)),
            message = lostMessage,
        )
        wrap {
            Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    EncoderCompletedActions(
                        lost, TrajectoryExportUiState(phase = TrajectoryExportPhase.IDLE),
                        onExport = { exports++ }, onCancelExport = {}, onSaveExport = {}, onShareExport = {},
                        onRequestTargetReselection = { targets++ }, onRequestRecalibration = { scales++ },
                    )
                }
            }
        }
        // Visible LOST partial warning: only the tracked portion is available.
        compose.onNodeWithTag("review-lost-partial").assertIsDisplayed()
        assertTrue(label(R.string.selector_review_lost_partial).contains("tracked portion"))
        // Same-zone verification still required; real failure message kept.
        compose.onNodeWithText(label(R.string.selector_review_instruction)).assertIsDisplayed()
        compose.onNodeWithText(lostMessage).assertIsDisplayed()
        // Target vs scale use the existing confirmed callbacks, not swapped.
        compose.onNodeWithText(label(R.string.selector_change_target)).assertIsDisplayed().performClick()
        assertEquals(1, targets)
        assertEquals(0, scales)
        compose.onNodeWithText(label(R.string.selector_change_scale)).assertIsDisplayed().performClick()
        assertEquals(1, targets)
        assertEquals(1, scales)
        // Export disclosure still collapsed but the LOST warning is not hidden behind it.
        compose.onNodeWithTag("export-disclosure").assertIsDisplayed()
        compose.onNodeWithTag("export-action").assertDoesNotExist()
        compose.onNodeWithTag("export-disclosure").performClick()
        compose.onNodeWithTag("export-action").assertIsDisplayed().performClick()
        assertEquals(1, exports)
        compose.onNodeWithTag("review-lost-partial").assertIsDisplayed()
    }

    @Test fun exportErrorStaysVisibleWithoutDisclosure() {
        var retries = 0
        wrap {
            Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    TrajectoryExportControls(
                        TrajectoryExportUiState(phase = TrajectoryExportPhase.ERROR, message = "boom"),
                        hasTrajectory = true, onExport = { retries++ }, onCancel = {}, onSave = {}, onShare = {},
                    )
                }
            }
        }
        // ERROR: critical message + retry action auto-visible, no expansion needed.
        compose.onNodeWithText("boom").assertIsDisplayed()
        compose.onNodeWithTag("export-disclosure").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.selector_export_trajectory)).assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test fun exportExportingStaysVisibleWithCancelCallback() {
        var cancels = 0
        wrap {
            Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    TrajectoryExportControls(
                        TrajectoryExportUiState(phase = TrajectoryExportPhase.EXPORTING, progressPercent = 40, message = "working"),
                        hasTrajectory = true, onExport = {}, onCancel = { cancels++ }, onSave = {}, onShare = {},
                    )
                }
            }
        }
        // EXPORTING: progress message + cancel auto-visible with the real callback.
        compose.onNodeWithText("working").assertIsDisplayed()
        val cancelBounds = compose.onNodeWithText(label(R.string.selector_cancel_export)).fetchSemanticsNode().boundsInRoot
        assertTrue("Export cancel must be at least 48dp", cancelBounds.height >= with(compose.density) { 48.dp.toPx() })
        compose.onNodeWithText(label(R.string.selector_cancel_export)).assertIsDisplayed().performClick()
        assertEquals(1, cancels)
    }

    @Test fun exportReadyStaysVisibleWithSaveAndRegenerateCallbacks() {
        var saves = 0
        var regens = 0
        wrap {
            Box(Modifier.size(360.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    TrajectoryExportControls(
                        TrajectoryExportUiState(phase = TrajectoryExportPhase.READY, message = "ready"),
                        hasTrajectory = true, onExport = { regens++ }, onCancel = {}, onSave = { saves++ }, onShare = {},
                    )
                }
            }
        }
        // READY: save/share/regenerate auto-visible with distinct callbacks.
        compose.onNodeWithText("ready").assertIsDisplayed()
        val saveBounds = compose.onNodeWithText(label(R.string.selector_save_mp4)).fetchSemanticsNode().boundsInRoot
        assertTrue("Save must be at least 48dp", saveBounds.height >= with(compose.density) { 48.dp.toPx() })
        compose.onNodeWithText(label(R.string.selector_save_mp4)).assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText(label(R.string.common_share)).assertIsDisplayed()
        val regenBounds = compose.onNodeWithText(label(R.string.selector_regenerate)).fetchSemanticsNode().boundsInRoot
        assertTrue("Regenerate must be at least 48dp", regenBounds.height >= with(compose.density) { 48.dp.toPx() })
        compose.onNodeWithText(label(R.string.selector_regenerate)).assertIsDisplayed().performClick()
        assertEquals(1, regens)
    }

    @Test fun completedOverlayKeepsUncertainSampleVisible() {
        wrap {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = sample(TrackingStatus.UNCERTAIN), recent = emptyList(),
                    trackingPhase = TrackingPhase.COMPLETED,
                    plateDetection = null, selectionEnabled = false, onTargetSelected = {},
                )
            }
        }
        // COMPLETED yields to live UNCERTAIN so review is not obscured.
        compose.onNodeWithText(label(R.string.selector_tracking_status_uncertain)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_tracking_status_completed)).assertDoesNotExist()
    }

    @Test fun completedOverlayKeepsLostSampleVisible() {
        wrap {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = sample(TrackingStatus.LOST), recent = emptyList(),
                    trackingPhase = TrackingPhase.COMPLETED,
                    plateDetection = null, selectionEnabled = false, onTargetSelected = {},
                )
            }
        }
        compose.onNodeWithText(label(R.string.selector_tracking_status_lost)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_tracking_status_completed)).assertDoesNotExist()
    }

    @Test fun completedOverlayWithCleanSampleStillReadsCompleted() {
        wrap {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = sample(TrackingStatus.TRACKING), recent = emptyList(),
                    trackingPhase = TrackingPhase.COMPLETED,
                    plateDetection = null, selectionEnabled = false, onTargetSelected = {},
                )
            }
        }
        compose.onNodeWithText(label(R.string.selector_tracking_status_completed)).assertIsDisplayed()
    }

    @Test fun readyOverlayNeverShowsStaleTrackingSample() {
        wrap {
            Box(Modifier.size(320.dp, 240.dp)) {
                TrackingOverlay(
                    mapper = testMapper(),
                    current = sample(TrackingStatus.TRACKING), recent = emptyList(),
                    trackingPhase = TrackingPhase.READY,
                    plateDetection = null, selectionEnabled = false, onTargetSelected = {},
                )
            }
        }
        compose.onNodeWithText(label(R.string.selector_tracking_status_ready)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.selector_tracking_status_tracking)).assertDoesNotExist()
    }

    @Test fun narrow280AtFont150StacksTargetAndScaleWith48dpSingles() {
        var targets = 0
        var scales = 0
        val state = TrackingUiState(phase = TrackingPhase.COMPLETED, results = listOf(sample()), message = "done")
        wrapSized(width = 280, height = 640, font = 1.5f) {
            Box(Modifier.size(280.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    EncoderCompletedActions(
                        state, TrajectoryExportUiState(phase = TrajectoryExportPhase.IDLE),
                        onExport = {}, onCancelExport = {}, onSaveExport = {}, onShareExport = {},
                        onRequestTargetReselection = { targets++ }, onRequestRecalibration = { scales++ },
                    )
                }
            }
        }
        val targetNode = compose.onNodeWithText(label(R.string.selector_change_target)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val scaleNode = compose.onNodeWithText(label(R.string.selector_change_scale)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Target must be at least 48dp", targetNode.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Scale must be at least 48dp", scaleNode.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Narrow actions must stack vertically, not clip side-by-side", scaleNode.top >= targetNode.bottom - 1f)
        compose.onNodeWithText(label(R.string.selector_change_target)).performClick()
        assertEquals(1, targets)
        assertEquals(0, scales)
        compose.onNodeWithText(label(R.string.selector_change_scale)).performClick()
        assertEquals(1, targets)
        assertEquals(1, scales)
    }

    @Test fun narrow312AtFont150StacksTargetAndScaleWith48dpSingles() {
        var targets = 0
        var scales = 0
        val state = TrackingUiState(phase = TrackingPhase.COMPLETED, results = listOf(sample()), message = "done")
        wrapSized(width = 312, height = 640, font = 1.5f) {
            Box(Modifier.size(312.dp, 640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    EncoderCompletedActions(
                        state, TrajectoryExportUiState(phase = TrajectoryExportPhase.IDLE),
                        onExport = {}, onCancelExport = {}, onSaveExport = {}, onShareExport = {},
                        onRequestTargetReselection = { targets++ }, onRequestRecalibration = { scales++ },
                    )
                }
            }
        }
        val targetNode = compose.onNodeWithText(label(R.string.selector_change_target)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val scaleNode = compose.onNodeWithText(label(R.string.selector_change_scale)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Target must be at least 48dp", targetNode.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Scale must be at least 48dp", scaleNode.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Narrow actions must stack vertically, not clip side-by-side", scaleNode.top >= targetNode.bottom - 1f)
        compose.onNodeWithText(label(R.string.selector_change_target)).performClick()
        assertEquals(1, targets)
        compose.onNodeWithText(label(R.string.selector_change_scale)).performClick()
        assertEquals(1, scales)
    }

    @Test fun analyzeActionRenamedToViewResultsKeepingSameGate() {
        wrap(language = "en") {}
        assertEquals("View results", label(R.string.selector_analyze_trajectory))
        // Same gate: partial LOST review stays analyzable, empty LOST stays blocked.
        val partial = EncoderReviewPolicy.resolve(EncoderReviewInput(TrackingPhase.LOST, listOf(sample()), listOf(sample()), false))
        assertTrue(partial.visible && partial.canAnalyze)
        val empty = EncoderReviewPolicy.resolve(EncoderReviewInput(TrackingPhase.LOST, emptyList(), emptyList(), false))
        assertTrue(!empty.visible && !empty.canAnalyze)
    }
}
