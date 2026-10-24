package com.openjump.app.ui.fvp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.components.JumpFlowProgress
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FvpSharedFlowProgressInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fvpLabels get() = listOf(R.string.fvp_step_data, R.string.fvp_step_measurement, R.string.fvp_step_saltos)
    private val jumpLabels get() = listOf(R.string.jump_flow_prepare, R.string.jump_flow_video, R.string.jump_flow_mark, R.string.jump_flow_result)

    @Test
    fun saltosRetainsItsFourLabelsAndStatesAtEveryPosition() {
        val current = mutableStateOf(JumpFlowStep.PREPARE)
        compose.setContent { OpenJumpTheme { JumpFlowProgress(current.value) } }
        JumpFlowStep.entries.forEachIndexed { index, step ->
            compose.runOnIdle { current.value = step }
            assertStates(jumpLabels, index)
        }
    }

    @Test
    fun fvpUsesThreeStepsWithoutRenamingRecoveryStatesOrAddingNavigation() {
        val current = mutableStateOf(FvpFlowStep.SETUP)
        compose.setContent { OpenJumpTheme { FvpFlowProgress(current.value) } }
        val expected = listOf(FvpFlowStep.SETUP to 0, FvpFlowStep.MEASUREMENT to 1, FvpFlowStep.CONDITIONS to 2, FvpFlowStep.RESULT to 2)
        assertEquals(listOf("SETUP", "MEASUREMENT", "CONDITIONS", "RESULT"), FvpFlowStep.entries.map { it.name })
        expected.forEach { (step, index) ->
            assertEquals(index, step.progressIndex)
            compose.runOnIdle { current.value = step }
            compose.onNodeWithTag("fvp-flow-progress").assertIsDisplayed()
            assertStates(fvpLabels, index)
        }
    }

    @Test
    fun threeLabelsFitAt150PercentInBothThemesAndEveryPage() {
        val dark = mutableStateOf(false)
        val current = mutableStateOf(FvpFlowStep.SETUP)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                OpenJumpTheme(darkTheme = dark.value) { FvpFlowProgress(current.value) }
            }
        }
        listOf(false, true).forEach { night ->
            listOf(FvpFlowStep.SETUP, FvpFlowStep.MEASUREMENT, FvpFlowStep.CONDITIONS).forEach { step ->
                compose.runOnIdle { dark.value = night; current.value = step }
                assertStates(fvpLabels, step.progressIndex)
                captureGuidedScreen(compose, "fvp_shared_progress", "${if (night) "dark" else "light"}-150-${step.name.lowercase()}")
                val root = compose.onNodeWithTag("fvp-flow-progress").fetchSemanticsNode().boundsInRoot
                fvpLabels.forEach { resource ->
                    val node = compose.onNodeWithText(context.getString(resource)).assertIsDisplayed()
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Text layout must be available", layouts.isNotEmpty())
                    val layout = layouts.single()
                    // A centered paragraph can retain the full column width while the Text node
                    // measures its intrinsic width. Check each actual line span, not paragraph.width.
                    assertFalse("Label must not lose lines", layout.didOverflowHeight)
                    (0 until layout.lineCount).forEach { line ->
                        val span = layout.getLineRight(line) - layout.getLineLeft(line)
                        assertTrue("Line fits its label (one physical pixel rounding tolerance): $span / ${layout.size.width}", span <= layout.size.width + 1f)
                        assertFalse("No ellipsis", layout.isLineEllipsized(line))
                    }
                    assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    assertTrue("Label remains inside timeline", bounds.top >= root.top && bounds.bottom <= root.bottom && bounds.left >= root.left && bounds.right <= root.right)
                }
            }
        }
    }

    private fun assertStates(labels: List<Int>, current: Int) {
        compose.onNodeWithContentDescription(context.getString(R.string.jump_flow_progress, current + 1, labels.size)).assertIsDisplayed()
        labels.forEachIndexed { index, label ->
            val state = when {
                index == current -> R.string.jump_flow_state_active
                index < current -> R.string.jump_flow_state_completed
                else -> R.string.jump_flow_state_pending
            }
            val description = context.getString(R.string.jump_flow_step, index + 1, context.getString(label), context.getString(state))
            val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
            assertFalse("Timeline is an indicator, not a navigation bypass", node.config.contains(SemanticsActions.OnClick))
        }
    }
}
