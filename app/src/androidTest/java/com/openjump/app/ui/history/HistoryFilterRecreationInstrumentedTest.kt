package com.openjump.app.ui.history

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.MainActivity
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFilterRecreationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var applied: HistoryFilters? = null

    private fun setFilterContent() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                OpenJumpTheme {
                    CompositionLocalProvider(LocalUnitSystem provides UnitProfile.METRIC) {
                        HistoryFilterSheet(
                            initial = HistoryFilters(family = HistoryFamily.JUMP),
                            athletes = emptyList(),
                            groups = emptyList(),
                            onDismiss = {},
                            onApply = { applied = it },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun encoderDraftRestoresCoherentlyAcrossActivityRecreationAndAppliesCanonicalSpeed() {
        val context = composeRule.activity
        setFilterContent()
        composeRule.onNodeWithText(context.getString(R.string.history_family_jump)).assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.history_family_encoder)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.history_protocol_exercise)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.exercise_bench_press)).performClick()
        composeRule.onAllNodes(hasSetTextAction()).get(0).performTextInput("2.5")
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()
        setFilterContent()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.history_family_encoder)).assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.exercise_bench_press)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_metric_min) + " (m/s)").assertExists()
        composeRule.onNodeWithText("2.5").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.history_show_results)).performClick()
        composeRule.runOnIdle {
            val filters = requireNotNull(applied)
            assertEquals(HistoryFamily.ENCODER, filters.family)
            assertEquals(EncoderExercise.BENCH_PRESS.name, filters.protocolOrExercise)
            assertEquals(MeasurementQuantity.SPEED_MPS, historyFilterQuantity(filters.protocolOrExercise))
            assertEquals(2.5, filters.metricMin!!, 0.0)
            assertNull(filters.metricMax)
        }
    }
}
