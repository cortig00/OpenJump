package com.openjump.app.ui.selector

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.VideoSource
import com.openjump.app.video.AppSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/**
 * Real-video integration for the A/B enlarge route: drives the actual
 * [FrameSelectorScreen] with existing callbacks ([AppSession] +
 * [FrameSelectorViewModel]), never mocks. Opt-in fixture only; skips on a clean
 * clone. Verifies UI wiring, NOT measurement accuracy.
 *
 * Run on the authorized AVD with `-e encoderUxVideo /data/local/tmp/<fixture>.mp4`.
 */
@RunWith(AndroidJUnit4::class)
class EncoderAbVideoInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private var fixtureCopy: File? = null
    private var ownsSession = false
    private var evidenceIndex = 0

    private fun evidence(name: String) {
        if (InstrumentationRegistry.getArguments().getString("encoderAbEvidence") != "true") return
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "encoder-ab-zoom").apply { mkdirs() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5_000)
        val screenshot = automation.takeScreenshot()
        try {
            File(folder, "${++evidenceIndex}-$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }
    private lateinit var activeViewModel: FrameSelectorViewModel

    @After fun close() {
        compose.runOnUiThread { store.clear(); if (ownsSession) AppSession.reset() }
        fixtureCopy?.delete()
    }

    private fun label(id: Int) = context.getString(id)

    private fun openAbFixture(): FrameSelectorViewModel {
        val path = InstrumentationRegistry.getArguments().getString("encoderUxVideo")
        assumeTrue("Requires explicit local video fixture on authorized AVD", path != null)
        val source = File(requireNotNull(path))
        assertTrue("Fixture is not readable: $path", source.canRead())
        val file = File(context.cacheDir, "encoder-ab-video-instrumented.mp4")
        source.copyTo(file, overwrite = true); fixtureCopy = file
        lateinit var vm: FrameSelectorViewModel
        compose.runOnUiThread {
            ownsSession = true
            AppSession.beginEncoder(EncoderSetup(EncoderExercise.SQUAT, 40.0), athleteId = 1L)
            AppSession.attachVideo(android.net.Uri.fromFile(file), VideoSource.IMPORTED)
            AppSession.setEncoderTiming(VideoTimingDecision(VideoTimingMode.REAL_TIME))
            vm = FrameSelectorViewModel(context)
            store.put("encoder-ab-video", vm)
        }
        val args = InstrumentationRegistry.getArguments()
        val font = args.getString("encoderUxFontScale")?.toFloat() ?: 1f
        val dark = args.getString("encoderUxDark") != "false"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                com.openjump.app.ui.theme.OpenJumpTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxWidth().height(560.dp)) {
                        FrameSelectorScreen({}, {}, viewModel = vm)
                    }
                }
            }
        }
        compose.waitUntil(30_000) { vm.displayState.value.canMark && !vm.loading.value }
        val fixtureIndex = requireNotNull(AppSession.frameIndex.value)
        assertEquals("UI regression fixture width", 540, fixtureIndex.width)
        assertEquals("UI regression fixture height", 960, fixtureIndex.height)
        activeViewModel = vm
        return vm
    }

    private fun enterManual(vm: FrameSelectorViewModel) {
        compose.onNodeWithTag("reference-method-distance").performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.phase == PlateDetectionPhase.MANUAL }
    }

    private fun openEditor() {
        compose.onNodeWithTag("reference-ab-enlarge").performScrollTo().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isNotEmpty()
            }
            assertFixtureSnapshotVisible()
            evidence("editor-open")
        } catch (failure: Throwable) {
            android.util.Log.e("EncoderAbVideoTest", "Editor did not open: display=${activeViewModel.displayState.value}, phase=${activeViewModel.plateDetectionState.value.phase}, draft=${AppSession.encoderDraft.value}, uri=${AppSession.videoUri.value}, frame=${AppSession.frameIndex.value}")
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("EncoderAbVideoTest") }
                .onFailure { android.util.Log.e("EncoderAbVideoTest", "Multiple native roots; original failure retained", failure) }
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                File(context.getExternalFilesDir(null), "ab-open-failure.png").outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { shot.recycle() }
            throw failure
        }
    }

    private fun assertFixtureSnapshotVisible() {
        val image = compose.onNodeWithTag("ab-precision-video").captureToImage().toPixelMap()
        val fitWidth = minOf(image.width.toDouble(), image.height * 540.0 / 960.0)
        val fitHeight = minOf(image.height.toDouble(), image.width * 960.0 / 540.0)
        val left = (image.width - fitWidth) / 2.0
        val top = (image.height - fitHeight) / 2.0
        var visible = 0
        repeat(20) { row -> repeat(20) { col ->
            val x = (left + fitWidth * (0.1 + 0.8 * (col + 0.5) / 20.0)).toInt().coerceIn(0, image.width - 1)
            val y = (top + fitHeight * (0.1 + 0.8 * (row + 0.5) / 20.0)).toInt().coerceIn(0, image.height - 1)
            val pixel = image[x, y]
            if (maxOf(pixel.red, pixel.green, pixel.blue) > 0.08f) visible++
        } }
        assertTrue("Trusted gray fixture snapshot is black: $visible/400 source-content samples", visible > 100)
    }

    private fun awaitEditorClosed() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        evidence("main-reference")
    }

    private fun tapEditorAt(fractionX: Float = 0.5f, fractionY: Float = 0.5f) {
        val node = compose.onNodeWithTag("ab-precision-video").fetchSemanticsNode()
        val bounds = node.boundsInRoot
        // Independent FIT geometry for the opt-in 540x960 fixture. Fractions
        // refer to source content, not the viewport's black letterbox bars;
        // keyboard/window resizing must not turn a valid B tap into a bar tap.
        val fitWidth = minOf(bounds.width, bounds.height * 540f / 960f)
        val fitHeight = minOf(bounds.height, bounds.width * 960f / 540f)
        compose.onNodeWithTag("ab-precision-video").performTouchInput {
            click(Offset((bounds.width - fitWidth) / 2f + fitWidth * fractionX,
                (bounds.height - fitHeight) / 2f + fitHeight * fractionY))
        }
    }

    private fun fieldText(): String? {
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.EditableText)?.text
    }

    @Test fun abEmptyReturnPublishesNothingAndReopens() {
        val vm = openAbFixture()
        enterManual(vm)
        assertNull(AppSession.encoderDraft.value?.calibrationPointA)
        assertNull(AppSession.encoderDraft.value?.calibrationPointB)
        openEditor()
        // Empty Volver only dismisses.
        compose.onNodeWithTag("ab-precision-return").performClick()
        awaitEditorClosed()
        assertNull(AppSession.encoderDraft.value?.calibrationPointA)
        assertNull(AppSession.encoderDraft.value?.calibrationPointB)
        assertNull(AppSession.encoderDraft.value?.calibration)
        // Reopen works after cancel.
        openEditor()
        compose.onNodeWithContentDescription(label(R.string.common_cancel)).performClick()
        awaitEditorClosed()
        assertNull(AppSession.encoderDraft.value?.calibrationPointA)
    }

    @Test fun abAOnlyReturnLeavesConfirmDisabledAndDistanceBlank() {
        val vm = openAbFixture()
        enterManual(vm)
        openEditor()
        tapEditorAt(0.5f, 0.5f)
        compose.onNodeWithTag("ab-precision-return").performClick()
        compose.waitUntil(5_000) {
            AppSession.encoderDraft.value?.calibrationPointA != null &&
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        assertNotNull(AppSession.encoderDraft.value?.calibrationPointA)
        assertNull(AppSession.encoderDraft.value?.calibrationPointB)
        // No autoconfirm: scale stays unconfirmed with a partial pair.
        assertNull(AppSession.encoderDraft.value?.calibration)
        // The actual confirm button must be disabled without B and distance.
        compose.onNodeWithText(label(R.string.common_confirm)).performScrollTo().assertIsNotEnabled()
        // Explicit distance input is preserved blank.
        assertTrue("Distance input must stay explicitly blank, was '${fieldText()}'", fieldText().isNullOrEmpty())
        assertNull(AppSession.encoderDraft.value?.calibration)
    }

    @Test fun abPartialACompletesToABAndPreservesExplicitDistance() {
        val vm = openAbFixture()
        enterManual(vm)
        // A-only live state first.
        openEditor()
        tapEditorAt(0.42f, 0.5f)
        compose.onNodeWithTag("ab-precision-return").performClick()
        compose.waitUntil(5_000) {
            AppSession.encoderDraft.value?.calibrationPointA != null &&
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        val liveA = requireNotNull(AppSession.encoderDraft.value?.calibrationPointA)
        // Enter an explicit distance before completing the pair.
        compose.onNode(hasSetTextAction()).performTextReplacement("0.60")
        compose.waitUntil(5_000) { fieldText() == "0.60" }
        // Completing the partial pair via the editor must keep A and add B
        // (uniform reset-first then plain marks), without confirming scale.
        openEditor()
        tapEditorAt(0.62f, 0.5f)
        compose.onNodeWithTag("ab-precision-return").performClick()
        compose.waitUntil(5_000) {
            val cur = AppSession.encoderDraft.value
            cur?.calibrationPointA != null && cur.calibrationPointB != null &&
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        val completedA = requireNotNull(AppSession.encoderDraft.value?.calibrationPointA)
        val completedB = requireNotNull(AppSession.encoderDraft.value?.calibrationPointB)
        // Independent original-coordinate oracle: FIT fractions map to source
        // pixel extents (539,959), regardless of snapshot/editor dimensions.
        assertEquals(0.42 * 539.0, completedA.x, 1.0)
        assertEquals(0.62 * 539.0, completedB.x, 1.0)
        assertEquals(0.5 * 959.0, completedA.y, 1.0)
        assertEquals(0.5 * 959.0, completedB.y, 1.0)
        // Independently ordered: second tap was right of first, so B.x > A.x.
        assertTrue("Completed B must be right of A (A=$completedA B=$completedB)", completedB.x > completedA.x)
        assertNull(AppSession.encoderDraft.value?.calibration)
        // Explicit distance survives the replace/reopen cycle.
        assertEquals("0.60", fieldText())
        // Confirming with the explicit distance binds canonical scale to this frame.
        compose.onNodeWithText(label(R.string.common_confirm)).performScrollTo().performClick()
        compose.waitUntil(10_000) { AppSession.encoderDraft.value?.calibration != null }
        val calibration = requireNotNull(AppSession.encoderDraft.value?.calibration)
        val rendered = requireNotNull(vm.displayState.value.renderedIndex)
        assertEquals(rendered, calibration.frameIndex)
        val expectedMpp = 0.60 / hypot(completedB.x - completedA.x, completedB.y - completedA.y)
        assertEquals(expectedMpp, calibration.metersPerPixel, 1e-9)
        // Live A survived the completion (same side, not swapped to B).
        assertTrue(
            "Completed A must stay near the original A (liveA=$liveA completedA=$completedA)",
            hypot(completedA.x - liveA.x, completedA.y - liveA.y) <
                hypot(completedB.x - liveA.x, completedB.y - liveA.y),
        )
    }

    @Test fun abFullPairReplacesBothPointsWithoutConfirmingScale() {
        val vm = openAbFixture()
        enterManual(vm)
        // First full pair via the editor: left then right.
        openEditor()
        tapEditorAt(0.40f, 0.5f)
        tapEditorAt(0.60f, 0.5f)
        compose.onNodeWithTag("ab-precision-return").performClick()
        compose.waitUntil(5_000) {
            AppSession.encoderDraft.value?.calibrationPointA != null &&
                AppSession.encoderDraft.value?.calibrationPointB != null &&
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        evidence("main-pair")
        val firstA = requireNotNull(AppSession.encoderDraft.value?.calibrationPointA)
        val firstB = requireNotNull(AppSession.encoderDraft.value?.calibrationPointB)
        assertTrue("First pair must be ordered left-to-right", firstB.x > firstA.x)
        assertNull(AppSession.encoderDraft.value?.calibration)
        val index = requireNotNull(AppSession.frameIndex.value)
        for (p in listOf(firstA, firstB)) {
            assertTrue("Point $p inside video width", p.x in 0.0..(index.width - 1.0))
            assertTrue("Point $p inside video height", p.y in 0.0..(index.height - 1.0))
        }
        // Full replace with wider taps: reset-first must land both new points.
        openEditor()
        tapEditorAt(0.32f, 0.5f)
        tapEditorAt(0.68f, 0.5f)
        compose.onNodeWithTag("ab-precision-return").performClick()
        compose.waitUntil(5_000) {
            val cur = AppSession.encoderDraft.value
            cur?.calibrationPointA != null && cur.calibrationPointB != null &&
                (cur.calibrationPointA != firstA || cur.calibrationPointB != firstB) &&
                compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        evidence("main-replaced-pair")
        val secondA = requireNotNull(AppSession.encoderDraft.value?.calibrationPointA)
        val secondB = requireNotNull(AppSession.encoderDraft.value?.calibrationPointB)
        assertTrue("Replaced pair must stay ordered left-to-right", secondB.x > secondA.x)
        // Wider taps must widen the pair in source coordinates (independent order check).
        val firstDist = hypot(firstB.x - firstA.x, firstB.y - firstA.y)
        val secondDist = hypot(secondB.x - secondA.x, secondB.y - secondA.y)
        assertTrue("Wider taps must widen the pair ($firstDist -> $secondDist)", secondDist > firstDist)
        // Replacing points never confirms scale on its own.
        assertNull(AppSession.encoderDraft.value?.calibration)
    }

    @Test fun abSeekDuringOpenEditorInvalidatesInsteadOfPublishingStale() {
        val vm = openAbFixture()
        enterManual(vm)
        openEditor()
        tapEditorAt(0.5f, 0.5f)
        // Force a drift boundary while the modal is open: step one frame.
        compose.runOnIdle { vm.stepFrames(1) }
        // Drift guard must auto-dismiss the stale dialog; no Volver publish happens.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("ab-precision-video").fetchSemanticsNodes().isEmpty()
        }
        // Live state untouched by the discarded local tap.
        assertNull(AppSession.encoderDraft.value?.calibrationPointA)
        assertNull(AppSession.encoderDraft.value?.calibrationPointB)
        assertNull(AppSession.encoderDraft.value?.calibration)
    }
}
