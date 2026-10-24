package com.openjump.app.ui.selector

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.abs
import com.openjump.app.video.AppSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dynamic UI regression for the frozen Encoder video while offline tracking is PROCESSING. */
@RunWith(AndroidJUnit4::class)
class EncoderTrackingPreviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private var fixture: File? = null
    private lateinit var viewModel: FrameSelectorViewModel

    @After fun close() {
        compose.runOnUiThread {
            store.clear()
            AppSession.reset()
        }
        fixture?.delete()
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(context.getExternalFilesDir(null), "encoder-tracking-preview").apply { mkdirs() }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }

    private fun openTrackingFixture() {
        val file = File(context.cacheDir, "encoder-tracking-preview.mp4")
        val longerFixture = InstrumentationRegistry.getArguments().getString("encoderPreviewVideo")
        val input = if (longerFixture == null) InstrumentationRegistry.getInstrumentation().context.assets.open("tracking_translation.mp4")
            else File(longerFixture).inputStream()
        input.use { file.outputStream().use { output -> it.copyTo(output) } }
        fixture = file
        compose.runOnUiThread {
            AppSession.beginEncoder(EncoderSetup(EncoderExercise.SQUAT, 40.0), athleteId = 1L)
            AppSession.attachVideo(Uri.fromFile(file), VideoSource.IMPORTED)
            AppSession.setEncoderTiming(VideoTimingDecision(VideoTimingMode.REAL_TIME))
            viewModel = FrameSelectorViewModel(context)
            store.put("encoder-preview", viewModel)
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier) { FrameSelectorScreen({}, {}, viewModel = viewModel) }
            }
        }
        compose.waitUntil(30_000) { viewModel.displayState.value.canMark && !viewModel.loading.value }
        assertEquals(320, AppSession.frameIndex.value?.width)
        assertEquals(240, AppSession.frameIndex.value?.height)
        compose.runOnUiThread {
            viewModel.startManualPlateCircle()
            viewModel.updatePlateProposal(ImagePoint(60.0, 120.0), 30.0)
            viewModel.confirmPlateProposal()
        }
        compose.waitUntil(30_000) {
            viewModel.plateDetectionState.value.phase == PlateDetectionPhase.DETECTED &&
                viewModel.trackingState.value.phase in setOf(TrackingPhase.READY, TrackingPhase.ERROR)
        }
        assertEquals(TrackingPhase.READY, viewModel.trackingState.value.phase)
        assertNotNull(AppSession.encoderDraft.value?.calibration)
    }

    @Test fun processingShowsDecodedPreviewAndCancelDropsItWithoutChangingCalibration() {
        openTrackingFixture()
        val calibration = AppSession.encoderDraft.value?.calibration
        compose.runOnUiThread { viewModel.startTracking() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("tracking-preview").fetchSemanticsNodes().isNotEmpty()
        }
        val pixels = compose.onNodeWithTag("tracking-preview").captureToImage().toPixelMap()
        var visiblePixels = 0
        for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
            if (maxOf(pixels[x, y].red, pixels[x, y].green, pixels[x, y].blue) > 0.05f) visiblePixels++
        }
        assertTrue("Decoded preview must visibly contain the video, found $visiblePixels non-black samples", visiblePixels > 20)
        assertEquals(TrackingPhase.PROCESSING, viewModel.trackingState.value.phase)
        val first = requireNotNull(viewModel.trackingState.value.processingPreview)
        assertTrue(maxOf(first.bitmap.width, first.bitmap.height) <= MAX_TRACKING_PREVIEW_SIDE)
        captureEvidence("01-processing")
        assertEquals(first.result, viewModel.trackingState.value.results.first { it.frameIndex == first.result.frameIndex })
        // The long local fixture gives screen capture a genuine PROCESSING window; never slow production for tests.
        if (InstrumentationRegistry.getArguments().getString("encoderPreviewVideo") != null) {
            compose.waitUntil(15_000) {
                viewModel.trackingState.value.processingPreview?.let { next ->
                    val startPoint = first.result.result.sample.point
                    val nextPoint = next.result.result.sample.point
                    next.result.frameIndex > first.result.frameIndex && startPoint != null && nextPoint != null &&
                        abs(nextPoint.x - startPoint.x) > 4.0 && !first.bitmap.sameAs(next.bitmap)
                } == true
            }
            val next = requireNotNull(viewModel.trackingState.value.processingPreview)
            assertEquals(AppSession.frameIndex.value!!.timeOf(next.result.frameIndex), next.result.result.sample.timestampUs)
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            val advancedPixels = compose.onNodeWithTag("tracking-preview").captureToImage().toPixelMap()
            captureEvidence("02-processing-advanced")
            assertEquals(pixels.width, advancedPixels.width)
            assertEquals(pixels.height, advancedPixels.height)
            val mapper = VideoCoordinateMapper(VideoPresentationGeometry(320, 240), ViewportSize(pixels.width.toDouble(), pixels.height.toDouble()))
            val centers = listOfNotNull(first.result.result.sample.point, next.result.result.sample.point).map(mapper::videoToView)
            var changedVideoPixels = 0
            for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                // Exclude the moving marker's black/white center and coloured overlay strokes.
                // The fixture's checkerboard video is neutral; a stale Canvas plus a moving marker cannot pass.
                if (centers.any { abs(x - it.x) < 30 && abs(y - it.y) < 30 }) continue
                val a = pixels[x, y]; val b = advancedPixels[x, y]
                val neutralA = maxOf(a.red, a.green, a.blue) - minOf(a.red, a.green, a.blue) < 0.02f
                val neutralB = maxOf(b.red, b.green, b.blue) - minOf(b.red, b.green, b.blue) < 0.02f
                if (neutralA && neutralB && abs(a.red - b.red) > 0.25f) changedVideoPixels++
            }
            assertTrue("Rendered VIDEO pixels must advance, not just preview state/marker: $changedVideoPixels changed samples", changedVideoPixels > 50)
        }

        compose.onNodeWithTag("reference-primary").performClick()
        compose.waitUntil(5_000) { viewModel.trackingState.value.phase == TrackingPhase.IDLE }
        assertEquals(calibration, AppSession.encoderDraft.value?.calibration)
        compose.waitForIdle()
        compose.onNodeWithTag("tracking-preview").assertDoesNotExist()
    }

    @Test fun terminalProcessingDropsPreviewAndKeepsCanonicalReviewResults() {
        openTrackingFixture()
        val calibration = AppSession.encoderDraft.value?.calibration
        compose.runOnUiThread { viewModel.startTracking() }
        compose.waitUntil(60_000) {
            viewModel.trackingState.value.phase in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST)
        }
        val state = viewModel.trackingState.value
        assertEquals(calibration, AppSession.encoderDraft.value?.calibration)
        assertEquals(state.results, AppSession.encoderDraft.value?.tracking)
        assertNull(state.processingPreview)
        compose.waitForIdle()
        compose.onNodeWithTag("tracking-preview").assertDoesNotExist()
        assertTrue(EncoderReviewPolicy.resolve(EncoderReviewInput(
            state.phase, state.results, AppSession.encoderDraft.value?.tracking.orEmpty(), false,
        )).visible)
    }
}
