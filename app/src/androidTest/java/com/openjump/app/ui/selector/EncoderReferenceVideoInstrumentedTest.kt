package com.openjump.app.ui.selector

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import com.openjump.app.ui.theme.OpenJumpTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

/** Explicit opt-in fixture; skips on a clean clone. Never writes Room or alters user settings.
 * Run on the authorized AVD with -e encoderUxVideo /data/local/tmp/<fixture>.mp4.
 * This verifies UI/rendering/state wiring, NOT detector accuracy or physical measurement.
 */
@RunWith(AndroidJUnit4::class)
class EncoderReferenceVideoInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private var fixtureCopy: File? = null
    private var ownsSession = false
    private var originalOrientation: Int? = null
    private fun label(id: Int) = context.getString(id)

    @After fun close() {
        compose.runOnUiThread {
            originalOrientation?.let { orientation ->
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.requestedOrientation = orientation
            }
            store.clear(); if (ownsSession) AppSession.reset()
        }
        fixtureCopy?.delete()
    }

    private fun evidence(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        val folder = File(context.getExternalFilesDir(null), "encoder-reference-ux").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }

    private fun openFixture(onCompute: () -> Unit = {}): FrameSelectorViewModel {
        val path = InstrumentationRegistry.getArguments().getString("encoderUxVideo")
        assumeTrue("Requires explicit local video fixture on authorized AVD", path != null)
        val source = File(requireNotNull(path))
        assertTrue("Fixture is not readable: $path", source.canRead())
        val file = File(context.cacheDir, "encoder-ux-instrumented.mp4")
        source.copyTo(file, overwrite = true); fixtureCopy = file
        lateinit var vm: FrameSelectorViewModel
        compose.runOnUiThread {
            ownsSession = true
            AppSession.beginEncoder(EncoderSetup(EncoderExercise.SQUAT, 40.0), athleteId = 1L)
            AppSession.attachVideo(Uri.fromFile(file), VideoSource.IMPORTED)
            AppSession.setEncoderTiming(VideoTimingDecision(VideoTimingMode.REAL_TIME))
            vm = FrameSelectorViewModel(context)
            store.put("encoder-reference", vm)
        }
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("encoderUxOrientation") == "landscape") {
            // System user_rotation alone is not evidence of a wide activity/window. Force only
            // this disposable test host before setContent; never alter the production manifest.
            compose.runOnUiThread {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                originalOrientation = activity.requestedOrientation
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val font = args.getString("encoderUxFontScale")?.toFloat() ?: 1f
        val height = args.getString("encoderUxPaneHeight")?.toInt()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                OpenJumpTheme(darkTheme = args.getString("encoderUxDark") == "true") {
                    Box(if (height != null) Modifier.fillMaxWidth().height(height.dp) else Modifier) {
                        FrameSelectorScreen({}, onCompute, viewModel = vm)
                    }
                }
            }
        }
        compose.waitUntil(30_000) { vm.displayState.value.canMark && !vm.loading.value }
        if (args.getString("encoderUxOrientation") == "landscape") {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Native host must actually be wide, not a portrait screenshot with rotation settings", root.width > root.height)
        }
        return vm
    }

    @Test fun firstSurfaceFrameIsVisibleWithoutStepping() {
        openFixture()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("reference-video").fetchSemanticsNode().boundsInRoot
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            // Central area of the viewport, away from the letterbox and transport overlays.
            val left = (root.left + video.left + video.width * 0.15f).toInt().coerceIn(0, screenshot.width - 1)
            val top = (root.top + video.top + video.height * 0.15f).toInt().coerceIn(0, screenshot.height - 1)
            val right = (root.left + video.right - video.width * 0.15f).toInt().coerceIn(left + 1, screenshot.width)
            val bottom = (root.top + video.bottom - video.height * 0.15f).toInt().coerceIn(top + 1, screenshot.height)
            var visible = 0
            var samples = 0
            for (y in 0..19) for (x in 0..19) {
                val pixel = screenshot.getPixel(left + (right - left) * x / 20, top + (bottom - top) * y / 20)
                samples++
                if (android.graphics.Color.red(pixel) > 12 ||
                    android.graphics.Color.green(pixel) > 12 ||
                    android.graphics.Color.blue(pixel) > 12
                ) visible++
            }
            assertTrue(
                "The first frame stays black after the SurfaceView is created: $visible/$samples visible samples",
                visible > samples / 2,
            )
        } finally {
            screenshot.recycle()
        }
    }

    @Test fun renderedFrameManualReviewSnapshotDiameterAndConfirmation() {
        var computed = false
        val vm = openFixture { computed = true }
        assertEquals(PlateDetectionPhase.IDLE, vm.plateDetectionState.value.phase)
        compose.onNodeWithTag("reference-method-plate").assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.encoder_reference_manual)).performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.plateDetectionState.value.phase == PlateDetectionPhase.REVIEW }
        compose.onNodeWithTag("reference-primary").assertIsNotEnabled()
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        val stableViewport = compose.onNodeWithTag("reference-video").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(label(R.string.encoder_reference_adjust)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.encoder_reference_bigger)).performScrollTo().performClick()
        assertEquals("Fine controls must not resize/reposition the displayed video", stableViewport,
            compose.onNodeWithTag("reference-video").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("reference-primary").assertIsEnabled()
        val follow = InstrumentationRegistry.getArguments().getString("encoderUxFollow") == "true"
        if (follow) {
            // Explicit geometry for the opt-in 540x960 static UI fixture, not a measurement oracle.
            val index = requireNotNull(AppSession.frameIndex.value)
            assertEquals(540, index.width); assertEquals(960, index.height)
            compose.runOnIdle { vm.updatePlateProposal(ImagePoint(270.0, 420.0), 190.0) }
        }
        val original = requireNotNull(vm.plateDetectionState.value.proposal)
        evidence("01-manual-review")

        compose.onNodeWithText(label(R.string.selector_plate_diameter_change)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("50")
        compose.onNodeWithText(label(R.string.common_apply)).performClick()
        compose.waitUntil(5_000) { AppSession.encoderDraft.value?.sessionPlateDiameterCm == 50.0 }
        val changed = requireNotNull(vm.plateDetectionState.value.proposal)
        assertEquals(original.center, changed.center)
        assertEquals(original.diameterPx, changed.diameterPx, 0.0)
        assertEquals(50.0, requireNotNull(changed.requestIdentity).sessionPlateDiameterCm, 0.0)
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        compose.onNodeWithTag("reference-primary").assertIsEnabled()

        compose.onNodeWithText(label(R.string.encoder_reference_enlarge)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reference-precision-video").fetchSemanticsNodes().isNotEmpty() }
        val imageBounds = compose.onNodeWithTag("reference-precision-video").fetchSemanticsNode().boundsInRoot
        val zoomBounds = compose.onNodeWithContentDescription(label(R.string.selector_zoom_in_description)).fetchSemanticsNode().boundsInRoot
        assertTrue("Zoom controls must not obscure the calibration image", zoomBounds.bottom <= imageBounds.top)
        val image = compose.onNodeWithTag("reference-precision-video").captureToImage().toPixelMap()
        // Excludes the control pane; a black PixelCopy with only the small circle overlay fails.
        var visible = 0
        for (y in 1..20) for (x in 1..20) {
            val pixel = image[image.width * x / 21, image.height * y / 21]
            if (maxOf(pixel.red, pixel.green, pixel.blue) > 0.05f) visible++
        }
        assertTrue("Snapshot is mostly black: $visible/400 samples", visible > 100)
        // Check native accessibility bounds, not only Compose's injected/off-screen clicks.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        evidence("02-precision-snapshot")
        if (InstrumentationRegistry.getArguments().getString("encoderUxCompactTargets") == "true") {
            val toolPane = compose.onNodeWithTag("reference-precision-pane").fetchSemanticsNode().boundsInRoot
            val returnTop = compose.onNodeWithTag("reference-precision-return").fetchSemanticsNode().boundsInRoot.top
            for (id in listOf(R.string.selector_cursor_left, R.string.selector_cursor_right,
                R.string.selector_cursor_up, R.string.selector_cursor_down,
                R.string.encoder_reference_smaller, R.string.encoder_reference_bigger)) {
                val target = compose.onNode(hasContentDescription(label(id)) and hasAnyAncestor(isDialog()))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val minimum = 48 * context.resources.displayMetrics.density
                assertTrue("All six native compact targets must fit above the CTA: $target", target.top >= toolPane.top && target.bottom <= returnTop)
                assertTrue("No compact target may shrink or be partially clipped: $target", target.width >= minimum - 1 && target.height >= minimum - 1)
            }
        }
        // Compose virtual nodes don't implement findAccessibilityNodeInfosByText;
        // traverse the native tree just like a UIAutomator hierarchy dump.
        val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        val returnNodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == label(R.string.encoder_reference_precision_return)) returnNodes.add(node)
            repeat(node.childCount) { i -> node.getChild(i)?.let(queue::addLast) }
        }
        assertTrue("Return text must be visible above the system navigation: ${returnNodes.map { node ->
            "${node.text}, visible=${node.isVisibleToUser}, bounds=${android.graphics.Rect().also(node::getBoundsInScreen)}"
        }}", returnNodes.any { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && bounds.height() > 0 && bounds.bottom <= context.resources.displayMetrics.heightPixels
        })
        compose.onNode(hasContentDescription(label(R.string.encoder_reference_bigger)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.common_cancel)).performClick()
        compose.onNodeWithTag("reference-precision-video").assertDoesNotExist()
        assertEquals(changed, vm.plateDetectionState.value.proposal)
        compose.onNodeWithText(label(R.string.encoder_reference_enlarge)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reference-precision-video").fetchSemanticsNodes().isNotEmpty() }
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        compose.onNode(hasContentDescription(label(R.string.encoder_reference_bigger)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        // Editing the local copy must not alter the live proposal until returning.
        assertEquals(changed, vm.plateDetectionState.value.proposal)
        compose.onNodeWithText(label(R.string.encoder_reference_precision_return)).performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.proposal?.diameterPx == changed.diameterPx + 2.0 }
        compose.onNodeWithTag("reference-precision-video").assertDoesNotExist()
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        evidence("03-return-fit")
        compose.onNodeWithTag("reference-primary").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            vm.plateDetectionState.value.phase == PlateDetectionPhase.DETECTED &&
                vm.trackingState.value.phase in setOf(TrackingPhase.READY, TrackingPhase.ERROR)
        }
        assertNotNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        assertEquals(PlateDetectionPhase.DETECTED, vm.plateDetectionState.value.phase)
        evidence("04-confirmed")
        if (follow) {
            assertEquals(TrackingPhase.READY, vm.trackingState.value.phase)
            val calibration = requireNotNull(AppSession.encoderDraft.value).calibration
            val videoNode = compose.onNodeWithTag("reference-video")
            val viewport = videoNode.fetchSemanticsNode().size
            val index = requireNotNull(AppSession.frameIndex.value)
            val mapper = VideoCoordinateMapper(VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees),
                ViewportSize(viewport.width.toDouble(), viewport.height.toDouble()))
            val target = mapper.videoToView(ImagePoint(280.0, 420.0))
            videoNode.performTouchInput { click(Offset(target.x.toFloat(), target.y.toFloat())) }
            compose.waitUntil(15_000) {
                vm.trackingState.value.phase == TrackingPhase.READY &&
                    vm.trackingState.value.results.firstOrNull()?.result?.sample?.point?.x?.let { kotlin.math.abs(it - 280.0) < 1.0 } == true
            }
            assertEquals(calibration, requireNotNull(AppSession.encoderDraft.value).calibration)
            compose.onNodeWithTag("reference-primary").assertIsEnabled().performClick()
            compose.waitUntil(60_000) { EncoderReviewPolicy.resolve(EncoderReviewInput(
                vm.trackingState.value.phase, vm.trackingState.value.results,
                requireNotNull(AppSession.encoderDraft.value).tracking, false)).canAnalyze }
            assertEquals(requireNotNull(AppSession.frameIndex.value).frameCount, vm.trackingState.value.results.size)
            evidence("05-canonical-review")
            compose.onNodeWithText(label(R.string.selector_analyze_trajectory)).assertIsEnabled().performClick()
            compose.waitUntil(5_000) { computed }
            assertTrue(requireNotNull(AppSession.encoderDraft.value).tracking.isNotEmpty())
        }
    }

    @Test fun knownDistanceRouteRequiresExplicitLengthAndKeepsScaleWhenTargetChanges() {
        val vm = openFixture()
        val index = requireNotNull(AppSession.frameIndex.value)
        // Opt-in geometry fixture, not a physical accuracy oracle.
        assumeTrue("Requires the explicit 540x960 reference fixture", index.width == 540 && index.height == 960)
        evidence("ab-01-method-choice")
        compose.onNodeWithTag("reference-method-distance").performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.phase == PlateDetectionPhase.MANUAL }
        compose.onNodeWithText(label(R.string.selector_plate_diameter_summary)).assertDoesNotExist()

        fun tap(point: ImagePoint) {
            val video = compose.onNodeWithTag("reference-video")
            val size = video.fetchSemanticsNode().size
            val mapper = VideoCoordinateMapper(
                VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees),
                ViewportSize(size.width.toDouble(), size.height.toDouble()),
            )
            val viewPoint = mapper.videoToView(point)
            video.performTouchInput { click(Offset(viewPoint.x.toFloat(), viewPoint.y.toFloat())) }
        }
        tap(ImagePoint(180.0, 420.0))
        compose.waitUntil(5_000) { AppSession.encoderDraft.value?.calibrationPointA != null }
        tap(ImagePoint(370.0, 420.0))
        compose.waitUntil(5_000) { AppSession.encoderDraft.value?.calibrationPointB != null }
        compose.onNodeWithText(label(R.string.common_confirm)).performScrollTo().assertIsNotEnabled()
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        evidence("ab-02-explicit-distance")
        compose.onNode(hasSetTextAction()).performTextReplacement("0.60")
        compose.onNodeWithText(label(R.string.common_confirm)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { AppSession.encoderDraft.value?.calibration != null }
        val calibration = requireNotNull(AppSession.encoderDraft.value?.calibration)
        assertEquals(0, calibration.frameIndex)
        tap(ImagePoint(280.0, 420.0))
        compose.waitUntil(15_000) { vm.trackingState.value.phase == TrackingPhase.READY }
        evidence("ab-03-target-ready")
        tap(ImagePoint(286.0, 426.0))
        compose.waitUntil(15_000) {
            vm.trackingState.value.phase == TrackingPhase.READY &&
                vm.trackingState.value.results.firstOrNull()?.result?.sample?.point?.x?.let {
                    kotlin.math.abs(it - 286.0) < 1.0
                } == true
        }
        assertEquals(calibration, requireNotNull(AppSession.encoderDraft.value).calibration)
        compose.onNodeWithTag("reference-primary").assertIsEnabled()
        evidence("ab-04-target-replaced-scale-kept")
    }

    @Test fun changingMomentDiscardsReviewAndBindsNewManualProposalToNewPTS() {
        val vm = openFixture()
        compose.onNodeWithTag("reference-method-plate").performClick()
        compose.onNodeWithText(label(R.string.encoder_reference_manual)).performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.proposal != null }
        val before = requireNotNull(vm.plateDetectionState.value.proposal)
        compose.onNodeWithText(label(R.string.encoder_reference_moment)).performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.proposal == null }
        assertNull(requireNotNull(AppSession.encoderDraft.value).calibration)
        compose.runOnIdle { vm.stepFrames(1) }
        compose.waitUntil(15_000) { vm.displayState.value.canMark && vm.displayState.value.renderedIndex == 1 }
        compose.onNodeWithText(label(R.string.encoder_reference_manual)).performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.plateDetectionState.value.proposal?.frameIndex == 1 }
        val after = requireNotNull(vm.plateDetectionState.value.proposal)
        assertNotEquals(before.requestIdentity, after.requestIdentity)
        assertNotEquals(before.ptsUs, after.ptsUs)
        compose.onNodeWithTag("reference-primary").assertIsNotEnabled()
    }
}
