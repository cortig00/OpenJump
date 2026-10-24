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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.QualityReason
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Analysis presentation tests use in-memory fixtures only; they never touch preferences or Room. */
@RunWith(AndroidJUnit4::class)
class EncoderResultAnalysisInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var localized: Context

    private fun render(
        repetitionCount: Int = 3,
        width: Int = 360,
        height: Int = 820,
        fontScale: Float = 1f,
        uncertainAt: Set<Int> = emptySet(),
        saving: Boolean = false,
        saved: Boolean = false,
        onRemoveRepetition: ((Int) -> Unit)? = null,
        ptsGapAt: Set<Int> = emptySet(),
        locale: Locale = Locale.US,
    ) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        localized = context.createConfigurationContext(config)
        val fixture = encoderResultUiFixture(repetitionCount = repetitionCount, uncertainAt = uncertainAt)
        val analysis = fixture.copy(repetitions = fixture.repetitions.map { repetition ->
            if (repetition.ordinal in ptsGapAt) repetition.copy(reasons = setOf(QualityReason.PTS_GAP))
            else repetition
        })
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                OpenJumpTheme(darkTheme = false) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        EncoderResultContent(
                            analysis = analysis,
                            modifier = Modifier.size(width.dp, height.dp),
                            saving = saving,
                            saved = saved,
                            noteDraft = null,
                            onRemoveRepetition = onRemoveRepetition,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): String = localized.getString(id, *args)

    private fun metricDescription(labelId: Int): String = compose.onNode(
        hasContentDescription(text(labelId), substring = true),
    ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun openAnalysisAt(ordinal: Int) {
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-$ordinal"))
        compose.onNodeWithTag("encoder-repetition-row-$ordinal").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_repetition, ordinal)).assertIsDisplayed()
    }

    @Test
    fun spanishVideoInterruptionWarningAppearsOnlyForTheAffectedRepetition() {
        render(uncertainAt = setOf(1, 2), ptsGapAt = setOf(1), locale = Locale.forLanguageTag("es"))
        assertEquals(
            "Interrupción en el vídeo: esta repetición puede ser menos precisa.",
            text(R.string.encoder_reason_pts_gap),
        )
        openAnalysisAt(1)
        compose.onNodeWithText(text(R.string.encoder_reason_pts_gap)).assertDoesNotExist()
        compose.onNodeWithTag("encoder-analysis-next").performClick()
        compose.onNode(hasText(text(R.string.encoder_reason_pts_gap)) and
            hasAnyAncestor(hasTestTag("encoder-analysis-header"))).assertIsDisplayed()
        compose.onNodeWithTag("encoder-analysis-next").performClick()
        compose.onNodeWithText(text(R.string.encoder_reason_pts_gap)).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.encoder_reason_low_confidence)) and
            hasAnyAncestor(hasTestTag("encoder-analysis-header"))).assertIsDisplayed()
    }

    @Test
    fun englishVideoInterruptionWarningRemainsReadableAtLargeFont() {
        render(uncertainAt = setOf(1), ptsGapAt = setOf(1), width = 280, height = 760, fontScale = 1.5f)
        assertEquals(
            "Interruption in the video: this repetition may be less accurate.",
            text(R.string.encoder_reason_pts_gap),
        )
        openAnalysisAt(2)
        compose.onNode(hasText(text(R.string.encoder_reason_pts_gap)) and
            hasAnyAncestor(hasTestTag("encoder-analysis-header"))).assertIsDisplayed()
    }

    @Test
    fun selectingR7FromR6KeepsAnalysisOpenAndListReturnsToR7Selection() {
        render(repetitionCount = 18, width = 320)
        openAnalysisAt(6)
        val mcvAtR6 = metricDescription(R.string.encoder_metric_mcv)
        val peakAtR6 = metricDescription(R.string.encoder_metric_peak_velocity)
        assertTrue(mcvAtR6.contains("0.60"))
        assertTrue(peakAtR6.contains("1.10"))
        compose.onNodeWithTag("encoder-analysis-next").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        assertTrue(metricDescription(R.string.encoder_metric_mcv).contains("0.61"))
        assertTrue(metricDescription(R.string.encoder_metric_peak_velocity).contains("1.11"))
        compose.onNodeWithTag("encoder-analysis-chip-7").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_repetition, 7)).assertIsDisplayed()
        compose.onNodeWithTag("encoder-analysis-header").assertExists()

        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-repetition-row-7"))
        val selectedDescription = compose.onNodeWithTag("encoder-repetition-row-7").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        assertTrue(selectedDescription.contains(text(R.string.encoder_result_repetition_selected)))
    }

    @Test
    fun analysisSelectorAndButtonsStayInBoundsAtLargeFontAndNarrowWidth() {
        render(repetitionCount = 18, width = 280, height = 760, fontScale = 1.5f)
        openAnalysisAt(6)
        compose.onNodeWithTag("encoder-analysis-next").performClick()
        val selector = compose.onNodeWithTag("encoder-analysis-selector").fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithTag("encoder-analysis-previous").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("encoder-analysis-next").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("encoder-analysis-chip-7").fetchSemanticsNode().boundsInRoot
        val minTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue("Selector remains within screen bounds", selector.left >= 0f && selector.right <= with(compose.density) { 280.dp.toPx() })
        assertTrue("Previous target is at least 48dp", previous.width >= minTarget && previous.height >= minTarget)
        assertTrue("Next target is at least 48dp", next.width >= minTarget && next.height >= minTarget)
        assertTrue("Repetition chip is at least 48dp", chip.width >= minTarget && chip.height >= minTarget)
        assertTrue("Visible chip remains inside selector viewport", chip.left >= selector.left && chip.right <= selector.right)
    }

    @Test
    fun horizontalCurveDragDoesNotChangeAnalysisViewOrSelectedRepetition() {
        render()
        openAnalysisAt(1)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-time-series-canvas"))
        compose.onNodeWithTag("encoder-time-series-canvas").performTouchInput { swipeRight() }
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_repetition, 1)).assertIsDisplayed()
    }

    @Test
    fun velocityModeIsControlledByResultRootAcrossRepetitionAndTabChanges() {
        render(repetitionCount = 4)
        openAnalysisAt(1)
        compose.onNodeWithTag("encoder-chart-velocity").performClick()
        compose.onNodeWithTag("encoder-chart-velocity").assertIsDisplayed()
        compose.onNodeWithTag("encoder-analysis-next").performClick()
        assertTrue(
            compose.onNodeWithTag("encoder-chart-velocity").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Selected) == true,
        )
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").performClick()
        assertTrue(
            compose.onNodeWithTag("encoder-chart-velocity").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Selected) == true,
        )
        compose.onAllNodesWithText(text(R.string.encoder_chart_velocity)).assertCountEquals(2)
    }

    @Test
    fun analysisHeaderTracksSelectedQualityAndMetricsAndTrajectoryDisclosureStartsClosed() {
        render(repetitionCount = 3, uncertainAt = setOf(1))
        openAnalysisAt(2)
        compose.onNode(hasText(text(R.string.encoder_quality_uncertain)) and
            hasAnyAncestor(hasTestTag("encoder-analysis-header"))).assertIsDisplayed()
        compose.onNodeWithTag("encoder-analysis-header").assertIsDisplayed()
        compose.onNodeWithTag("encoder-analysis-metrics").assertExists()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-disclosure-trajectory"))
        compose.onNodeWithTag("encoder-trajectory-plot").assertDoesNotExist()
        compose.onNodeWithTag("encoder-disclosure-trajectory").performClick()
        compose.onNodeWithTag("encoder-trajectory-plot").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_chart_axis_note)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoder_chart_trajectory_markers)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-show-details"))
        compose.onNodeWithTag("encoder-show-details").performClick()
        EncoderMetricKey.entries.forEach { key ->
            compose.onNodeWithTag("encoder-metric-detail-${key.name.lowercase()}")
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun removalUsesConfirmationAndCancelPreservesTheRequestedFirstMiddleAndLastRepetition() {
        val removed = mutableListOf<Int>()
        render(repetitionCount = 3, onRemoveRepetition = removed::add)
        for (ordinal in 1..3) {
            if (ordinal == 1) openAnalysisAt(ordinal) else {
                compose.onNodeWithTag("encoder-tab-repetitions").performClick()
                compose.onNodeWithTag("encoder-result-content")
                    .performScrollToNode(hasTestTag("encoder-repetition-row-$ordinal"))
                compose.onNodeWithTag("encoder-repetition-row-$ordinal").performClick()
            }
            compose.onNodeWithTag("encoder-result-content")
                .performScrollToNode(hasTestTag("encoder-delete-repetition"))
            compose.onNodeWithTag("encoder-delete-repetition").performClick()
            compose.onNodeWithText(text(R.string.encoder_delete_repetition_title, ordinal)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.common_cancel)).performClick()
            assertEquals("Cancel must not remove R$ordinal", ordinal - 1, removed.size)
            compose.onNodeWithTag("encoder-delete-repetition").performClick()
            compose.onNodeWithText(text(R.string.encoder_delete_repetition_confirm)).performClick()
            assertEquals(ordinal - 1, removed.last())
        }
    }

    @Test
    fun onlyRepetitionCanBeRemovedOnlyAfterExplicitConfirmation() {
        val removed = mutableListOf<Int>()
        render(repetitionCount = 1, onRemoveRepetition = removed::add)
        openAnalysisAt(1)
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(hasTestTag("encoder-delete-repetition"))
        compose.onNodeWithTag("encoder-delete-repetition").performClick()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithTag("encoder-delete-repetition").performClick()
        compose.onNodeWithText(text(R.string.encoder_delete_repetition_confirm)).performClick()
        assertEquals(listOf(0), removed)
    }

    @Test
    fun savingResultCannotOfferRepetitionRemovalConfirmation() {
        render(saving = true, onRemoveRepetition = {})
        openAnalysisAt(1)
        compose.onNodeWithTag("encoder-delete-repetition").assertDoesNotExist()
    }

    @Test
    fun savedResultCannotOfferRepetitionRemovalConfirmation() {
        render(saved = true, onRemoveRepetition = {})
        openAnalysisAt(1)
        compose.onNodeWithTag("encoder-delete-repetition").assertDoesNotExist()
    }
}
