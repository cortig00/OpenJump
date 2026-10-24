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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.openjump.app.encoder.EncoderMetricKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.encoder.EncoderResultContent
import com.openjump.app.ui.encoder.PersistedEncoderContext
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Presentation contract only: no ViewModels, repositories, preferences, Room, or media are touched. */
@RunWith(AndroidJUnit4::class)
class EncoderResultDisclosureInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var localized: Context
    private var saveCount = 0

    private fun render(
        analysis: com.openjump.app.encoder.EncoderAnalysis = encoderResultUiFixture(),
        width: Int = 360,
        height: Int = 760,
        fontScale: Float = 1f,
        language: String = "en",
        units: UnitProfile = UnitProfile.METRIC,
        note: String? = "",
        persistedContext: PersistedEncoderContext? = null,
        saveAction: Boolean = true,
        saving: Boolean = false,
        saved: Boolean = false,
        saveError: String? = null,
        onCorrect: (() -> Unit)? = null,
        onDiscard: (() -> Unit)? = null,
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
                            persistedContext = persistedContext,
                            saveError = saveError,
                            onCorrect = onCorrect,
                            onDiscard = onDiscard,
                            onSave = if (saveAction) ({ saveCount++ }) else null,
                            saving = saving,
                            saved = saved,
                            noteDraft = note,
                            onNoteChange = {},
                            noteEnabled = true,
                            onNoteEdit = {},
                            onNoteDelete = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg arguments: Any): String = localized.getString(id, *arguments)

    @Test
    fun summaryNamesTheSingleUncertainRepetitionInSpanishWithoutOpeningWarnings() {
        render(analysis = encoderResultUiFixture(uncertainAt = setOf(1)), language = "es", height = 820)
        compose.onNodeWithTag("encoder-uncertain-repetitions-notice")
            .assertIsDisplayed().assertTextEquals("Medición incierta en la repetición 2.")
    }

    @Test
    fun summaryNamesAllUncertainRepetitionsAtLargeFontWithoutIncludingInvalidOrMetricConfidence() {
        render(
            analysis = encoderResultUiFixture(
                repetitionCount = 5,
                uncertainAt = setOf(1, 3),
                invalidQualityAt = setOf(4),
                lowConfidencePvAt = setOf(0),
            ),
            width = 280,
            height = 760,
            fontScale = 1.5f,
        )
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-uncertain-repetitions-notice"))
        compose.onNodeWithTag("encoder-uncertain-repetitions-notice")
            .assertIsDisplayed().assertTextEquals("Uncertain measurements for repetitions 2, 4.")
    }

    @Test
    fun summaryDoesNotCallInvalidRepetitionsUncertain() {
        render(analysis = encoderResultUiFixture(allInvalid = true))
        compose.onNodeWithTag("encoder-result-summary-hero").assertIsDisplayed()
        compose.onNodeWithTag("encoder-uncertain-repetitions-notice").assertDoesNotExist()
    }

    @Test
    fun savedSummaryAlsoNamesItsUncertainRepetitions() {
        render(
            analysis = encoderResultUiFixture(uncertainAt = setOf(0, 2)),
            saved = true,
            saveAction = false,
            height = 820,
            persistedContext = PersistedEncoderContext("Atleta UI", 1_759_320_000_000L, VideoSource.IMPORTED),
        )
        compose.onNodeWithTag("encoder-uncertain-repetitions-notice")
            .assertIsDisplayed().assertTextEquals("Uncertain measurements for repetitions 1, 3.")
    }

    @Test
    fun summaryShowsHeroBeforeCollapsedNotesAndCriticalTimebaseExplainer() {
        render(
            analysis = encoderResultUiFixture(allInvalid = true, unknownTimebase = true),
            height = 820,
            language = "es",
            note = "Nota local de prueba",
            persistedContext = PersistedEncoderContext("Atleta UI", 1_759_320_000_000L, VideoSource.IMPORTED),
        )
        compose.onNodeWithTag("encoder-result-summary-hero").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        // Critical timebase stays prominent before the chart without a duplicated expanded copy.
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-timeline"))
        compose.onNodeWithTag("encoder-critical-timeline").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-critical-no-valid"))
        compose.onNodeWithTag("encoder-critical-no-valid").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.encoder_warning_unreliable_timeline)).assertCountEquals(1)
        compose.onNodeWithTag("encoder-disclosure-warnings").assertDoesNotExist()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-notes"))
        compose.onNodeWithTag("encoder-disclosure-notes").assertIsDisplayed()
        compose.onNodeWithTag("encoder-disclosure-notes").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-context"))
        compose.onNodeWithTag("encoder-disclosure-context").performClick()
        compose.onNodeWithText("Atleta UI", substring = true).assertExists()
    }

    @Test
    fun repetitionRowsAreLazyGroupedReach48dpAndTapDirectlyToSharedAnalysisSelection() {
        render(analysis = encoderResultUiFixture(repetitionCount = 18), width = 312, height = 720, fontScale = 1.5f)
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-18"))
        compose.onNodeWithTag("encoder-repetition-row-18").assertIsDisplayed()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-6"))
        compose.onNodeWithTag("encoder-repetition-row-6").assertIsDisplayed()
        val rowHeight = compose.onNodeWithTag("encoder-repetition-row-6").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Repetition target must be at least 48dp", rowHeight >= with(compose.density) { 48.dp.toPx() })
        compose.onNodeWithTag("encoder-repetition-row-6").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_repetition, 6)).assertIsDisplayed()
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        try {
            compose.onNodeWithTag("encoder-repetition-row-6").assertIsDisplayed()
        } catch (failure: Throwable) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }

    @Test
    fun font150At280dpRowWrapsInsideItsGroupedTouchTarget() = assertResponsiveRow(width = 280)

    @Test
    fun font150At312dpRowWrapsInsideItsGroupedTouchTarget() = assertResponsiveRow(width = 312)

    private fun assertResponsiveRow(width: Int) {
        render(analysis = encoderResultUiFixture(), width = width, height = 720, fontScale = 1.5f)
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-1"))
        val row = compose.onNodeWithTag("encoder-repetition-row-1").fetchSemanticsNode().boundsInRoot
        val metrics = compose.onNodeWithTag(
            "encoder-metric-layout-1",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("Adaptive metrics stay inside their row", metrics.left >= row.left && metrics.right <= row.right)
        assertTrue("Row remains comfortably tappable", row.height >= with(compose.density) { 48.dp.toPx() })
    }

    @Test
    fun expandedAnalysisKeepsAllEightMetricDetailsReachable() {
        render(analysis = encoderResultUiFixture())
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-1"))
        compose.onNodeWithTag("encoder-repetition-row-1").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-show-details"))
        compose.onNodeWithTag("encoder-show-details").performClick()
        EncoderMetricKey.entries.forEach { key ->
            compose.onNodeWithTag("encoder-metric-detail-${key.name.lowercase()}")
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun threeRepetitionChartUsesOrdinalLabelsAndKeepsAccessibleData() = assertChart(3, 312)

    @Test
    fun tenRepetitionChartUsesOrdinalLabelsAndKeepsAccessibleData() = assertChart(10, 312)

    @Test
    fun eighteenRepetitionChartUsesDensityAwareOrdinalLabelsAndKeepsAccessibleData() = assertChart(18, 280, fontScale = 1.5f)

    private fun assertChart(count: Int, width: Int, fontScale: Float = 1f) {
        render(analysis = encoderResultUiFixture(repetitionCount = count), width = width, fontScale = fontScale)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-summary-mcv-chart"))
        compose.onNodeWithTag("encoder-summary-mcv-chart").assertExists()
        compose.onNode(hasContentDescription("Repetition ${count}: ", substring = true)).assertExists()
        compose.onNodeWithText("R1").assertExists()
        compose.onNodeWithText("R$count").assertExists()

        val axis = compose.onNodeWithTag("encoder-mcv-axis").fetchSemanticsNode().boundsInRoot
        val labels = compose.onAllNodes(hasTestTag("encoder-mcv-axis-label")).fetchSemanticsNodes()
        assertTrue("Sparse labels are still displayed", labels.size >= 2)
        val bounds = labels.map { it.boundsInRoot }
        assertTrue("Every label remains inside the axis", bounds.all {
            it.left >= axis.left && it.right <= axis.right && it.top >= axis.top && it.bottom <= axis.bottom
        })
        val ordered = bounds.sortedBy { it.left }
        ordered.zipWithNext().forEach { (left, right) ->
            assertTrue("Axis labels do not overlap", left.right <= right.left)
        }
        val countInBounds = count.toFloat()
        labels.forEach { node ->
            val label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            val ordinal = Regex("R(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: error("Unexpected axis label: $label")
            val labelBounds = node.boundsInRoot
            val barCenter = axis.left + (ordinal - 0.5f) * axis.width / countInBounds
            val expectedLeft = (barCenter - labelBounds.width / 2f).coerceIn(axis.left, axis.right - labelBounds.width)
            assertTrue("$label stays anchored to its bar slot", kotlin.math.abs(labelBounds.left - expectedLeft) <= 2f)
        }
        compose.onAllNodes(hasTestTag("encoder-mcv-axis-label-overflow")).assertCountEquals(0)
    }

    @Test
    fun invalidMetricSentinelIsSuppressedAndQualityBestAndConfidenceStayDistinct() {
        val markerAnalysis = encoderResultUiFixture(
            repetitionCount = 3,
            invalidMcvAt = setOf(2),
            invalidQualityAt = setOf(2),
            lowConfidencePvAt = setOf(0, 1),
            uncertainAt = setOf(1),
            tieUncertainWithBest = true,
        )
        render(analysis = markerAnalysis)
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        val validBest = compose.onNodeWithTag("encoder-repetition-row-1").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        val uncertainTie = compose.onNodeWithTag("encoder-repetition-row-2").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        val invalid = compose.onNodeWithTag("encoder-repetition-row-3").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(validBest.contains(text(R.string.encoder_chart_best_marker)))
        assertTrue(uncertainTie.contains(text(R.string.encoder_quality_uncertain)))
        assertTrue(!uncertainTie.contains(text(R.string.encoder_chart_best_marker)))
        assertTrue(uncertainTie.contains(text(R.string.encoder_low_confidence_suffix).trim(' ', '·')))
        assertTrue(invalid.contains(text(R.string.encoder_unreliable)))
        assertTrue(!invalid.contains("9876"))

        compose.onNodeWithTag("encoder-repetition-row-3").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-show-details"))
        compose.onNodeWithTag("encoder-show-details").performClick()
        compose.onAllNodesWithText("9876", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("encoder-tab-summary").performClick()
        compose.onNode(hasContentDescription("9876", substring = true)).assertDoesNotExist()
    }

    @Test
    fun lowConfidenceAndBestMarkerHaveFullAccessibleMeaningAndImperialLocaleRemainsFormatted() {
        render(
            analysis = encoderResultUiFixture(lowConfidencePvAt = setOf(0), tieUncertainWithBest = true),
            language = "en",
            units = UnitProfile.UNITED_STATES,
        )
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        val row = compose.onNodeWithTag("encoder-repetition-row-1").fetchSemanticsNode()
        val description = row.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(description.contains("Best"))
        assertTrue(description.contains("low confidence", ignoreCase = true))
        assertTrue(description.contains("ft/s"))
        compose.onNodeWithTag("encoder-disclosure-notes").assertDoesNotExist()
        compose.onNodeWithTag("encoder-tab-summary").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-technical"))
        compose.onNodeWithTag("encoder-disclosure-technical").performClick()
        compose.onNodeWithText(text(R.string.encoder_load_fps, "88.2 lb", 60.0)).assertIsDisplayed()
    }

    @Test
    fun saveFooterRemainsAvailableInAllFourViews() {
        render()
        for (tab in listOf("summary", "repetitions", "analysis", "video")) {
            compose.onNodeWithTag("encoder-tab-$tab").performClick()
            compose.onNodeWithText(text(R.string.encoder_save_session)).assertIsDisplayed()
        }
    }

    @Test
    fun savingFooterStaysVisibleAndDisabled() {
        render(saving = true)
        compose.onNodeWithText(text(R.string.common_saving)).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun noValidFooterOffersSameSaveAnywayConfirmationWithoutCorrectionAction() {
        saveCount = 0
        render(analysis = encoderResultUiFixture(allInvalid = true, unknownTimebase = true), onCorrect = null)
        compose.onNodeWithText(text(R.string.encoder_save_anyway)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.encoder_save_anyway_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        assertTrue(saveCount == 0)
        compose.onNodeWithText(text(R.string.encoder_save_anyway)).performClick()
        compose.onNodeWithText(text(R.string.encoder_save_anyway_confirm)).performClick()
        assertTrue(saveCount == 1)
    }

    @Test
    fun saveErrorFooterOpensFullDetail() {
        val longError = "Database is temporarily unavailable: diagnostic detail must remain reachable."
        render(saveError = longError)
        compose.onNodeWithTag("encoder-save-error-details").assertIsDisplayed().performClick()
        compose.onNodeWithText(longError).assertIsDisplayed()
    }

    @Test
    fun savedResultHidesActiveSaveAction() {
        render(saved = true)
        compose.onNodeWithText(text(R.string.encoder_save_session)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.encoder_save_anyway)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.encoder_session_saved)).assertDoesNotExist()
    }
}
