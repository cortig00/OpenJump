package com.openjump.app.ui.encoder

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.openjump.app.R
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable Scaffold host, real native density/font and insets. Never touches Room or preferences. */
@RunWith(AndroidJUnit4::class)
class EncoderResultVisualInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var originalOrientation: Int? = null
    private val args get() = InstrumentationRegistry.getArguments()

    @After fun restoreOrientation() {
        originalOrientation?.let { orientation ->
            compose.runOnUiThread {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull()?.requestedOrientation = orientation
            }
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun render(saved: Boolean = false, empty: Boolean = false) {
        if (args.getString("encoderResultsOrientation") == "landscape") {
            compose.runOnUiThread {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                originalOrientation = activity.requestedOrientation
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) {
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
        }
        val locale = Locale.forLanguageTag(args.getString("encoderResultsLanguage") ?: "en")
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        val analysis = encoderResultUiFixture(
            repetitionCount = if (empty) 0 else 18,
            uncertainAt = setOf(1), invalidQualityAt = setOf(3),
            lowConfidencePvAt = setOf(0), allInvalid = empty,
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                OpenJumpTheme(darkTheme = args.getString("encoderResultsDark") == "true") {
                    Scaffold(topBar = {
                        OpenJumpTopAppBar(title = localized.getString(R.string.encoder_result_title))
                    }) { padding ->
                        EncoderResultContent(
                            analysis = analysis,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            saved = saved,
                            onSave = if (saved) null else ({}),
                            noteDraft = if (empty) null else "Optional note; details stay available without dominating the result.",
                            onNoteChange = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        if (args.getString("encoderResultsOrientation") == "landscape") {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Host must be genuinely wide, not an orientation label", root.width > root.height)
        }
    }

    private fun capture(name: String) {
        if (args.getString("encoderResultsEvidence") != "true") return
        compose.waitForIdle()
        if (name != "06-saved-empty") compose.onNodeWithTag("encoder-save-footer").assertIsDisplayed()
        else compose.onNodeWithTag("encoder-save-footer").assertDoesNotExist()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val folder = File(context.getExternalFilesDir(null), "encoder-results-visual").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun fourViewsKeepSaveVisibleAndDetailsReachableAtNativeFontAndWindow() {
        render()
        compose.onNodeWithTag("encoder-result-summary-hero").assertIsDisplayed()
        capture("01-summary")
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content").performScrollToIndex(18)
        compose.onNodeWithTag("encoder-repetition-row-18").assertIsDisplayed()
        capture("02-repetitions")
        compose.onNodeWithTag("encoder-repetition-row-18").performClick()
        compose.onNodeWithTag("encoder-analysis-header").assertIsDisplayed()
        capture("03-analysis")
        compose.onNodeWithTag("encoder-result-content").performScrollToNode(hasTestTag("encoder-show-details"))
        compose.onNodeWithTag("encoder-show-details").performClick()
        EncoderMetricKey.entries.forEach { key ->
            compose.onNodeWithTag("encoder-metric-detail-${key.name.lowercase()}")
                .performScrollTo().assertIsDisplayed()
        }
        capture("04-analysis-details")
        compose.onNodeWithTag("encoder-tab-video").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("encoder-video-placeholder").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("encoder-video-placeholder").assertIsDisplayed()
        capture("05-no-video")
        compose.onNodeWithTag("encoder-tab-analysis").performClick()
        compose.onNodeWithTag("encoder-analysis-chip-18").assertIsSelected()
    }

    @Test fun savedAndEmptyResultsKeepRecoveryViewsAvailableWithoutSaveAction() {
        render(saved = true, empty = true)
        capture("06-saved-empty")
        for (view in listOf("repetitions", "analysis", "video", "summary")) {
            compose.onNodeWithTag("encoder-tab-$view").performClick()
            compose.onNodeWithTag("encoder-result-content").assertIsDisplayed()
        }
    }
}
