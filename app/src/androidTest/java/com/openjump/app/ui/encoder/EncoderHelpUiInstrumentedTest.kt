package com.openjump.app.ui.encoder

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncoderHelpUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var strings: Context

    private fun render(sheet: Boolean = false) {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(args.getString("encoderSetupLanguage") ?: "en"))
        }
        strings = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides strings, LocalConfiguration provides config) {
                OpenJumpTheme(darkTheme = args.getString("encoderSetupDark") == "true") {
                    if (sheet) EncoderMetricsHelpSheet({}) else EncoderMethodologyScreen({})
                }
            }
        }
    }

    private fun visible(id: Int) = compose.onNodeWithText(strings.getString(id)).performScrollTo().assertIsDisplayed()
    private fun capture(name: String) {
        if (args.getString("encoderSetupEvidence") != "true") return
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val folder = File(context.getExternalFilesDir(null), "encoder-help-visual").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun methodologyKeepsStepsFormulasAndFullLimitsReachable() {
        render()
        visible(R.string.encoder_method_one_line_body)
        capture("01-methodology")
        for (id in listOf(R.string.encoder_method_step_scale_title, R.string.encoder_method_step_time_title,
            R.string.encoder_method_step_track_title, R.string.encoder_method_step_phases_title,
            R.string.encoder_method_conversion_title, R.string.encoder_method_metrics_title,
            R.string.encoder_method_technical_title, R.string.encoder_method_quality_body,
            R.string.encoder_method_limits_body)) visible(id)
        capture("02-methodology-limits")
    }

    @Test fun metricsSheetKeepsDescriptionsQualityAndLossCaveatReachable() {
        render(sheet = true)
        visible(R.string.encoder_help_metrics_title)
        capture("03-metrics-help")
        encoderMetricHelp.forEach { visible(it.shortDescriptionResource) }
        visible(R.string.encoder_help_valid_description)
        visible(R.string.encoder_help_uncertain_description)
        visible(R.string.encoder_help_invalid_description)
        visible(R.string.encoder_help_loss_caveat)
        capture("04-metrics-quality")
    }
}
