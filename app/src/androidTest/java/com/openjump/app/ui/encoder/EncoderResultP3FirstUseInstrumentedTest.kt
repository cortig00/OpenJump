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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.encoder.EncoderResultContent
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** P3 first-use presentation: help entry points, critical order, technical disclosure, visible confidence. */
@RunWith(AndroidJUnit4::class)
class EncoderResultP3FirstUseInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var localized: Context

    private fun render(
        analysis: com.openjump.app.encoder.EncoderAnalysis = encoderResultUiFixture(),
        width: Int = 360,
        height: Int = 900,
        fontScale: Float = 1f,
        language: String = "en",
        units: UnitProfile = UnitProfile.METRIC,
        onCorrect: (() -> Unit)? = null,
    ) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalUnitSystem provides units,
            ) {
                OpenJumpTheme(darkTheme = false) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        EncoderResultContent(
                            analysis = analysis,
                            modifier = Modifier.size(width.dp, height.dp),
                            onCorrect = onCorrect,
                            onDiscard = {},
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

    private fun text(id: Int, vararg args: Any): String = localized.getString(id, *args)

    private fun assertHelpSheetVisible() {
        // The sheet's show coroutine can start after the first idle boundary.
        try {
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithText(text(R.string.encoder_help_metrics_title)).assertIsDisplayed()
                }.isSuccess
            }
        } catch (failure: Throwable) {
            println("HELP NODES: " + compose.onAllNodesWithText("", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().joinToString("\n") { "${it.boundsInRoot}: ${it.config}" })
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                java.io.File(context.getExternalFilesDir(null), "encoder-help-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
            throw failure
        }
    }

    @Test
    fun summaryHelpIsDiscoverableAndOpensMetricsSheet() {
        render(language = "es")
        compose.onNodeWithTag("encoder-summary-help").assertIsDisplayed()
        val helpBounds = compose.onNodeWithTag("encoder-summary-help").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Summary help must be at least 48dp",
            helpBounds.height >= with(compose.density) { 48.dp.toPx() } &&
                helpBounds.width >= with(compose.density) { 48.dp.toPx() },
        )
        compose.onNodeWithText(text(R.string.encoder_help_metrics_intro), substring = true).assertIsDisplayed()
        compose.onNodeWithTag("encoder-summary-help").performClick()
        assertHelpSheetVisible()
    }

    @Test
    fun analysisHelpOpensFromSelectedRepetition() {
        render()
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-1"))
        compose.onNodeWithTag("encoder-repetition-row-1").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-analysis-help"))
        val bounds = compose.onNodeWithTag("encoder-analysis-help").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Analysis help must be at least 48dp",
            bounds.height >= with(compose.density) { 48.dp.toPx() } &&
                bounds.width >= with(compose.density) { 48.dp.toPx() },
        )
        compose.onNodeWithTag("encoder-analysis-help").performClick()
        compose.onNodeWithText(text(R.string.encoder_help_metrics_title)).assertIsDisplayed()
    }

    @Test
    fun analysisHelpReachableWhenEmpty() {
        render(analysis = encoderResultUiFixture(repetitionCount = 0))
        compose.onNodeWithTag("encoder-tab-analysis").performClick()
        compose.onNodeWithTag("encoder-analysis-help").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("encoder-analysis-help").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Empty analysis help must be at least 48dp",
            bounds.height >= with(compose.density) { 48.dp.toPx() },
        )
        compose.onNodeWithTag("encoder-analysis-help").performClick()
        compose.onNodeWithText(text(R.string.encoder_help_metrics_title)).assertIsDisplayed()
    }

    @Test
    fun criticalWarningsAndCorrectionAppearBeforeChartWithoutDuplicate() {
        var corrected = 0
        render(
            analysis = encoderResultUiFixture(allInvalid = true, unknownTimebase = true),
            height = 820,
            language = "es",
            onCorrect = { corrected++ },
        )
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-timeline"))
        compose.onNodeWithTag("encoder-critical-timeline").assertIsDisplayed()
        // Count while the critical item is composed: scrolling to the chart disposes
        // Lazy items above, so a later count would read 0 for the wrong reason.
        compose.onAllNodesWithText(text(R.string.encoder_warning_unreliable_timeline)).assertCountEquals(1)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-no-valid"))
        compose.onNodeWithTag("encoder-critical-no-valid").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-correct-before-chart"))
        compose.onNodeWithTag("encoder-correct-before-chart").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-summary-mcv-chart"))
        compose.onNodeWithTag("encoder-summary-mcv-chart").assertExists()
        // No duplicated expanded copy: disclosure hides already-prominent critical warnings.
        // The duplicate would sit after technical, so scroll there before asserting absence.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").assertIsDisplayed()
        compose.onNodeWithTag("encoder-disclosure-warnings").assertDoesNotExist()
        // Same-scroll-position recheck: return to the critical item and confirm singularity.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-timeline"))
        compose.onNodeWithTag("encoder-critical-timeline").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.encoder_warning_unreliable_timeline)).assertCountEquals(1)
        // Correction stays reachable before the chart.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-correct-before-chart"))
        compose.onNodeWithText(text(R.string.encoder_correct_results)).performClick()
        assertTrue(corrected == 1)
    }

    @Test
    fun technicalDetailsKeepsFpsAvailableWhileWarningStaysOutside() {
        render(analysis = encoderResultUiFixture(unknownTimebase = true), language = "en")
        // Warning stays visible outside technical details when timing is unreliable.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-timeline"))
        compose.onNodeWithTag("encoder-critical-timeline").assertIsDisplayed()
        // FPS detail lives inside the same-screen technical disclosure, not the header.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").assertIsDisplayed()
        val disclosureBounds = compose.onNodeWithTag("encoder-disclosure-technical")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Technical disclosure must be at least 48dp",
            disclosureBounds.height >= with(compose.density) { 48.dp.toPx() },
        )
        compose.onNodeWithTag("encoder-technical-fps").assertDoesNotExist()
        compose.onNodeWithTag("encoder-disclosure-technical").performClick()
        compose.onNodeWithTag("encoder-technical-fps").assertIsDisplayed()
    }

    @Test
    fun lowConfidenceShowsVisibleTextInRowsAndDetails() {
        render(analysis = encoderResultUiFixture(lowConfidencePvAt = setOf(0)))
        // localized is only initialized by render(); resolve the suffix after composition.
        val lowText = text(R.string.encoder_low_confidence_suffix).trim(' ', '·')
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-1"))
        // CompactMetric clears merged semantics for TalkBack, so visible text lives
        // in the unmerged tree; existing row tags stay the meaningful anchor.
        compose.onNodeWithText(lowText, substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("encoder-repetition-row-1").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-show-details"))
        compose.onNodeWithTag("encoder-show-details").performClick()
        compose.onNodeWithTag("encoder-metric-detail-peak_velocity", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        // MetricDetailRow merges descendants for accessibility; verify visible text unmerged.
        compose.onAllNodesWithText(lowText, substring = true, useUnmergedTree = true).apply {
            assertTrue(fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun defaultFixtureDoesNotInjectFalseTrackingLost() {
        val analysis = encoderResultUiFixture()
        assertTrue(analysis.warnings.none { it == com.openjump.app.encoder.EncoderAnalysisWarnings.TRACKING_LOST })
        render(analysis = analysis)
        compose.onNodeWithTag("encoder-critical-tracking-lost").assertDoesNotExist()
    }

    @Test
    fun explicitTrackingLostShowsCriticalBannerWithLostSample() {
        val analysis = encoderResultUiFixture(trackingLost = true)
        assertTrue(analysis.warnings.any { it == com.openjump.app.encoder.EncoderAnalysisWarnings.TRACKING_LOST })
        assertTrue(analysis.samples.any { it.trackingStatus == com.openjump.app.tracking.TrackingStatus.LOST })
        render(analysis = analysis)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-tracking-lost"))
        compose.onNodeWithTag("encoder-critical-tracking-lost").assertIsDisplayed()
    }

    @Test
    fun sharedSelectionAndSaveFooterPreserved() {
        render(analysis = encoderResultUiFixture(repetitionCount = 3))
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-2"))
        compose.onNodeWithTag("encoder-repetition-row-2").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_repetition, 2)).assertIsDisplayed()
        for (tab in listOf("summary", "repetitions", "analysis", "video")) {
            compose.onNodeWithTag("encoder-tab-$tab").performClick()
            compose.onNodeWithText(text(R.string.encoder_save_session)).assertIsDisplayed()
        }
    }
}
