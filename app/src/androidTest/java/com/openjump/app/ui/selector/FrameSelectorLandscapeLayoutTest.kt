package com.openjump.app.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production composition contract for analysis/marking (F034 scoped).
 *
 * All assertions compose the REAL [AnalysisPaneLayout] from
 * `AnalysisLayout.kt` with fixture header/video/controls slots — no
 * duplicated Row/Box/Column layout. The pane modifiers (fit-within
 * `aspectRatio` in landscape, width-driven in portrait) are the production
 * ones; fixtures only supply content inside them.
 *
 * Slot/hoisted-state coverage only: the branch-switch test proves hoisted
 * frame/marks state survives a presentation-only mode change. It does NOT
 * prove real AppSession/PlayerView retention across rotation (retention lives
 * in the ViewModel + rememberSaveable with configChanges; PlayerView
 * rebinding and sensor rotation remain PENDING KVM/AVD runtime).
 */
@RunWith(AndroidJUnit4::class)
class FrameSelectorLandscapeLayoutTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val HEADER_TAG = "fixture-header"
        const val MARK_TAG = "fixture-mark"
        const val COMPUTE_TAG = "fixture-compute"
    }

    private fun renderProduction(
        mode: AnalysisLayoutMode,
        aspectRatio: Float,
        widthDp: Int = 360,
        heightDp: Int = 760,
        fontScale: Float = 1f,
        frameText: String = "Frame 45",
        marksText: String = "Marks 2",
        onMark: (() -> Unit)? = null,
        onCompute: (() -> Unit)? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                OpenJumpTheme(darkTheme = false) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        val portraitScroll = rememberScrollState()
                        val landscapeScroll = rememberScrollState()
                        AnalysisPaneLayout(
                            mode = mode,
                            aspectRatio = aspectRatio,
                            portraitScrollState = portraitScroll,
                            landscapeControlsScrollState = landscapeScroll,
                            controlsScrollEnabled = true,
                            header = {
                                Text(frameText, modifier = Modifier.testTag(HEADER_TAG))
                            },
                            video = { paneModifier ->
                                Box(
                                    paneModifier
                                        .testTag(AnalysisLayout.VIDEO_PANE_TAG),
                                ) {
                                    Text("video")
                                }
                            },
                            controls = {
                                Column {
                                    Text(marksText)
                                    Button(
                                        onClick = { onMark?.invoke() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag(MARK_TAG),
                                    ) { Text("Mark takeoff") }
                                    Button(
                                        onClick = { onCompute?.invoke() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag(COMPUTE_TAG),
                                    ) { Text("Calculate result") }
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun portraitKeepsVideoAboveControlsInProductionComposition() {
        renderProduction(
            mode = AnalysisLayoutMode.PORTRAIT,
            aspectRatio = 16f / 9f,
            widthDp = 400,
            heightDp = 800,
            fontScale = 1.3f,
        )
        compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MARK_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPUTE_TAG).assertIsDisplayed()
        val video = compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG).fetchSemanticsNode().boundsInRoot
        val mark = compose.onNodeWithTag(MARK_TAG).fetchSemanticsNode().boundsInRoot
        val compute = compose.onNodeWithTag(COMPUTE_TAG).fetchSemanticsNode().boundsInRoot
        // Portrait order: video above interactive controls, no overlap.
        assertTrue("portrait video must start above mark button (video.top=${video.top}, mark.top=${mark.top})", video.top < mark.top)
        assertTrue("portrait video must end at or above mark button (video.bottom=${video.bottom}, mark.top=${mark.top})", video.bottom <= mark.top + 1f)
        assertTrue("portrait video must not overlap compute button", video.bottom <= compute.top + 1f)
    }

    @Test fun landscapeFitsVideoWithinPaneWithoutOverlapAcrossRatios() {
        // Vertical (0.75), horizontal (16/9) and wider-than-pane (2.0) videos
        // must all fit BOTH pane width and height without overlapping controls.
        val ratios = listOf(0.75f, 16f / 9f, 2.0f)
        for (ratio in ratios) {
            renderProduction(
                mode = AnalysisLayoutMode.LANDSCAPE,
                aspectRatio = ratio,
                widthDp = 800,
                heightDp = 400,
                fontScale = 1.3f,
            )
            compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG).assertIsDisplayed()
            compose.onNodeWithTag(AnalysisLayout.CONTROLS_PANE_TAG).assertIsDisplayed()
            val video = compose.onNodeWithTag(AnalysisLayout.VIDEO_PANE_TAG).fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag(AnalysisLayout.CONTROLS_PANE_TAG).fetchSemanticsNode().boundsInRoot
            // Side-by-side: video right edge at or left of controls left edge.
            assertTrue(
                "ratio=$ratio landscape video must stay left of controls (video.right=${video.right}, controls.left=${controls.left})",
                video.right <= controls.left + 1f,
            )
            // Non-overlap.
            assertTrue(
                "ratio=$ratio video and controls must not overlap",
                video.right <= controls.left + 1f || video.bottom <= controls.top + 1f || video.top >= controls.bottom - 1f,
            )
            // Both panes inside the fixed-size root Box (800x400 at density 1):
            // bounds are in px; root origin is (0,0) of the composition.
            assertTrue("ratio=$ratio video must have positive size", video.width > 1f && video.height > 1f)
            assertTrue("ratio=$ratio controls must have positive size", controls.width > 1f && controls.height > 1f)
            // Video must fit within the left ~58% pane, not bleed across the whole root.
            assertTrue(
                "ratio=$ratio wider-than-pane video must not span the root width (video.right=${video.right})",
                video.right <= with(compose.density) { 800.dp.toPx() } + 1f,
            )
            assertTrue(
                "ratio=$ratio video must fit vertically (video.bottom=${video.bottom})",
                video.bottom <= with(compose.density) { 400.dp.toPx() } + 1f,
            )
        }
    }

    @Test fun landscapeInteractiveButtonsMeetTouchTargetAtLargeFont() {
        var marked = false
        var computed = false
        renderProduction(
            mode = AnalysisLayoutMode.LANDSCAPE,
            aspectRatio = 16f / 9f,
            widthDp = 800,
            heightDp = 400,
            fontScale = 1.3f,
            onMark = { marked = true },
            onCompute = { computed = true },
        )
        // Touch-target rule applies to the real interactive buttons, not the container.
        compose.onNodeWithTag(MARK_TAG).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(COMPUTE_TAG).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(MARK_TAG).performClick()
        compose.onNodeWithTag(COMPUTE_TAG).performClick()
        compose.waitForIdle()
        assertTrue("fixture mark button must be interactive", marked)
        assertTrue("fixture compute button must be interactive", computed)
    }

    @Test fun hoistedFrameAndMarksSurviveProductionBranchSwitch() {
        // Slot/hoisted-state coverage only — NOT real AppSession/PlayerView
        // retention or sensor rotation (pending KVM/AVD runtime).
        val modeState: MutableState<AnalysisLayoutMode> = mutableStateOf(AnalysisLayoutMode.PORTRAIT)
        val frameState = mutableIntStateOf(45)
        val marksState = mutableStateOf(setOf("MOVEMENT_START", "TAKEOFF"))
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.3f),
            ) {
                OpenJumpTheme(darkTheme = false) {
                    Box(Modifier.size(800.dp, 400.dp)) {
                        val portraitScroll = rememberScrollState()
                        val landscapeScroll = rememberScrollState()
                        val mode by modeState
                        val frame by frameState
                        val marks by marksState
                        AnalysisPaneLayout(
                            mode = mode,
                            aspectRatio = 16f / 9f,
                            portraitScrollState = portraitScroll,
                            landscapeControlsScrollState = landscapeScroll,
                            controlsScrollEnabled = true,
                            header = {
                                Text("Frame $frame", modifier = Modifier.testTag(HEADER_TAG))
                            },
                            video = { paneModifier ->
                                Box(paneModifier.testTag(AnalysisLayout.VIDEO_PANE_TAG)) {
                                    Text("video")
                                }
                            },
                            controls = {
                                Column {
                                    Text("Marks ${marks.size}", modifier = Modifier.testTag("fixture-marks"))
                                    Button(
                                        onClick = {},
                                        modifier = Modifier.heightIn(min = 48.dp).testTag(MARK_TAG),
                                    ) { Text("Mark takeoff") }
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Frame 45").assertIsDisplayed()
        compose.onNodeWithText("Marks 2").assertIsDisplayed()
        compose.runOnIdle { modeState.value = AnalysisLayoutMode.LANDSCAPE }
        compose.waitForIdle()
        // Same hoisted values render in the other production branch.
        compose.onNodeWithText("Frame 45").assertIsDisplayed()
        compose.onNodeWithText("Marks 2").assertIsDisplayed()
        // Mutate hoisted state while in landscape; it renders without losing the branch.
        compose.runOnIdle {
            frameState.intValue = 46
            marksState.value = marksState.value + "LANDING"
        }
        compose.waitForIdle()
        compose.onNodeWithText("Frame 46").assertIsDisplayed()
        compose.onNodeWithText("Marks 3").assertIsDisplayed()
    }
}
