package com.openjump.app.ui.fvp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.fvp.FvpQualityState
import com.openjump.app.fvp.FvpSensitivityAvailability
import com.openjump.app.fvp.FvpTimingQuality
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.fvp.FvpProtocol
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FvpResultNoticesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val language = InstrumentationRegistry.getArguments().getString("fvpUiLocale") ?: "en"
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    @Test fun criticalLimitsAreExplicitWithoutOpeningQualityOrADisclosure() {
        val flags = listOf(FvpUiWarning.NON_PHYSICAL, FvpUiWarning.UNCLASSIFIED_WARNING,
            FvpUiWarning.SNAPSHOT_UNAVAILABLE, FvpUiWarning.FPS_BLOCKED, FvpUiWarning.R2_UNDEFINED)
        setNoticeContent {
            OpenJumpTheme { Column(Modifier.fillMaxSize()) {
                FvpResultInterpretation(model(flags), false, false)
                Column(Modifier.weight(1f)) { }
            } }
        }
        compose.onNodeWithTag("fvp-context-quality").assertTextEquals(context.getString(R.string.fvp_cannot_compute))
        flags.forEach { flag ->
            val tag = if (flag == FvpUiWarning.NON_PHYSICAL) "fvp-context-non-physical" else "fvp-critical-${flag.name}"
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(flag.messageRes()))
        }
    }

    @Test fun originalStalenessIsQualifiedAndDisappearsForTheOtherExecution() {
        val original = mutableStateOf(true)
        setNoticeContent {
            OpenJumpTheme { Column(Modifier.fillMaxSize()) {
                FvpResultInterpretation(
                    model(listOf(FvpUiWarning.SNAPSHOT_RECALCULATED)).copy(view = if (original.value) FvpResultView.ORIGINAL else FvpResultView.RECALCULATION),
                    true, original.value,
                )
                Column(Modifier.weight(1f)) { }
            } }
        }
        compose.onNodeWithTag("fvp-context-quality").assertTextContains(context.getString(R.string.fvp_notice_stored_quality, ""), substring = true)
        compose.onNodeWithTag("fvp-context-stale").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { original.value = false }
        compose.onNodeWithTag("fvp-context-stale").assertDoesNotExist()
        compose.onNodeWithTag("fvp-critical-SNAPSHOT_RECALCULATED").performScrollTo().assertIsDisplayed()
    }

    @Test fun ordinaryWarningsHaveConciseTitlesAndRetainTheirFullExplanationOnRequest() {
        val flags = listOf(FvpUiWarning.TIMING_UNKNOWN, FvpUiWarning.BODY_MASS_UNKNOWN,
            FvpUiWarning.ADDED_LOAD_UNKNOWN, FvpUiWarning.ACQUISITION_CONTEXT_UNKNOWN)
        setNoticeContent { OpenJumpTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { FvpResultNoticeGroups(model(flags)) } } }
        flags.forEach { flag ->
            compose.onNodeWithTag("fvp-notice-toggle-${flag.name}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("fvp-notice-detail-${flag.name}").assertDoesNotExist()
            compose.onNodeWithTag("fvp-notice-toggle-${flag.name}").performClick()
            compose.onNodeWithTag("fvp-notice-detail-${flag.name}").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(flag.messageRes()))
        }
    }

    @Test fun criticalDetailIsNeverCollapsed() {
        setNoticeContent { OpenJumpTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FvpResultNoticeGroups(model(listOf(FvpUiWarning.UNCLASSIFIED_WARNING, FvpUiWarning.NON_PHYSICAL)))
        } } }
        listOf(FvpUiWarning.UNCLASSIFIED_WARNING, FvpUiWarning.NON_PHYSICAL).forEach {
            compose.onNodeWithTag("fvp-notice-detail-${it.name}").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(it.messageRes()))
        }
    }

    @Test fun ordinaryCausesBelongToQualityAndShowingThemDoesNotMutateTheModel() {
        val showQuality = mutableStateOf(false)
        val sample = model(listOf(FvpUiWarning.TIMING_UNKNOWN, FvpUiWarning.HIGH_EXTRAPOLATION))
        val before = sample.copy()
        setNoticeContent { OpenJumpTheme { Column(Modifier.fillMaxSize()) {
            FvpResultInterpretation(sample, true, false)
            Column(Modifier.weight(1f)) { if (showQuality.value) FvpResultNoticeCauses(sample) }
        } } }
        compose.onNodeWithTag("fvp-context-quality").assertTextEquals(context.getString(R.string.fvp_notice_diagnostic))
        compose.onNodeWithTag("fvp-interpretation-causes").assertDoesNotExist()
        compose.onNodeWithTag("fvp-open-quality").assertDoesNotExist()
        compose.runOnIdle { showQuality.value = true }
        compose.onNodeWithTag("fvp-interpretation-causes").assertTextContains(context.getString(R.string.fvp_notice_timing_unknown), substring = true)
        compose.onNodeWithText(context.getString(R.string.fvp_notice_few_points)).assertDoesNotExist()
        assertEquals("displaying reasons changes no values/verdict/source", before, sample)
    }

    @Test fun noWarningsDoesNotInventAnExplanationForADiagnosticVerdict() {
        setNoticeContent { OpenJumpTheme { Column(Modifier.fillMaxSize()) {
            FvpResultInterpretation(model(emptyList()), true, false)
            Column(Modifier.weight(1f)) { }
        } } }
        compose.onNodeWithTag("fvp-context-quality").assertTextEquals(context.getString(R.string.fvp_notice_diagnostic))
        compose.onNodeWithTag("fvp-interpretation-causes").assertTextEquals(context.getString(R.string.fvp_notice_reason_unavailable))
        compose.onNodeWithText(context.getString(R.string.fvp_notice_no_extra)).assertDoesNotExist()
    }

    @Test fun temporalAvailabilityStaysVisibleWithoutInventingSensitivityRanges() {
        val availability = mutableStateOf(FvpSensitivityAvailability.MISSING_FLIGHT_TIME)
        setNoticeContent { OpenJumpTheme { Column(Modifier.fillMaxSize()) {
            FvpResultInterpretation(model(emptyList()).copy(sensitivityAvailability = availability.value), true, false)
            Column(Modifier.weight(1f)) { }
        } } }
        compose.onNodeWithTag("fvp-sensitivity").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(R.string.fvp_notice_temporal_unavailable))
        compose.runOnIdle { availability.value = FvpSensitivityAvailability.AVAILABLE_GATED }
        compose.onNodeWithTag("fvp-sensitivity").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(R.string.fvp_notice_temporal_gated))
        assertTrue(compose.onAllNodesWithText("±", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun enlargedTextKeepsCriticalContentAndAllGroupsReachableInBothThemes() {
        val dark = mutableStateOf(false)
        setNoticeContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                OpenJumpTheme(darkTheme = dark.value) { Column(Modifier.fillMaxSize()) {
                    FvpResultInterpretation(model(FvpUiWarning.entries.toList()), true, false)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { FvpResultNoticeGroups(model(FvpUiWarning.entries.toList())) }
                } }
            }
        }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            compose.onNodeWithTag("fvp-context-non-physical").performScrollTo().assertIsDisplayed()
            FvpNoticeGroup.entries.forEach { group ->
                compose.onNodeWithTag("fvp-notice-group-${group.name}").performScrollTo().assertIsDisplayed()
            }
            captureGuidedScreen(compose, "fvp_result_notices", if (night) "all-flags-dark150" else "all-flags-light150")
        }
    }

    private fun setNoticeContent(content: @Composable () -> Unit) {
        // Explicit host locale, as in Encoder's UI fixtures. Per-app shell locale alone does not
        // localize ComponentActivity's Compose resources consistently under instrumentation.
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                content = content,
            )
        }
    }

    private fun model(warnings: List<FvpUiWarning>) = FvpResultUiModel(
        protocolLabelKey = FvpProtocol.SJ, bodyMassKg = 80.0, conditionCount = 4,
        diagnosticOnly = false, profileEligible = false, timingQuality = FvpTimingQuality.UNKNOWN,
        quality = FvpQualityState.DIAGNOSTIC, f0N = 2500.0, sfvNsPerM = 500.0, slopeFv = -500.0,
        v0Mps = 5.0, pmaxW = 3125.0, r2 = 0.99, vMinMps = 1.0, vMaxMps = 2.0,
        fMinN = 1500.0, fMaxN = 2000.0, f0RelNPerKg = 31.25, pmaxRelWPerKg = 39.0625,
        sensitivityAvailability = FvpSensitivityAvailability.MISSING_FLIGHT_TIME,
        warnings = warnings, points = emptyList(), pointsUnavailableFromSource = false,
        algorithmVersion = "ui-test-only", storedSnapshotMatches = true, view = FvpResultView.RECALCULATION,
    )
}
