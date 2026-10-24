package com.openjump.app.ui.selector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.benchmark.EncoderPerfSampler
import com.openjump.app.benchmark.EncoderPerformanceSupport
import com.openjump.app.benchmark.PreviewMmrPrefetchFrameSource
import com.openjump.app.benchmark.PreviewMmrDiagnostics
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.PhysicalTimeline
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.ui.OpenJumpNavHost
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.video.AppSession
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.encoder.VideoTimingDecision
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Approved UI/preview MMR prefetch canary (real route only, opt-in).
 *
 * Hosts the actual production [OpenJumpNavHost] inside the instrumented Activity (never a fake
 * two-screen host, never fake tracking states), navigates the real selector, drives the actual
 * route [FrameSelectorViewModel] via `ViewModelProvider(backStackEntry, Factory)`, and measures
 * the real automated flow on the exact `new240.mp4` fixture. OFF (null factory) is the exact
 * usual route; ON injects the AndroidTest-only [PreviewMmrPrefetchFrameSource] via the DEBUG-only
 * ephemeral [FrameSelectorViewModel.debugTrackingSourceFactory] override (cleared in finally).
 *
 * Live preview proof happens DURING PROCESSING (never after COMPLETED): the `tracking-preview`
 * tag is asserted displayed, the live `processingPreview` bitmap pair is matched to the exact
 * frame/PTS result, pixel advancement is proven between two live previews >= 240 frames apart,
 * and two screenshots (same overhead both arms, inside the timed window) plus PNG evidence are
 * saved under a unique run-labeled folder.
 *
 * No Room edits, no save/result writes, no MainActivity/AppNav production edits. AppNav bootstrap
 * writes only when the installation is empty; an already-existing profile is preserved.
 */
@RunWith(AndroidJUnit4::class)
class EncoderPrefetchUiCanaryTest {

    // Fixture parameters: defaults keep the frozen new240 canary byte-for-byte equivalent.
    private var expectedFixtureSha = DEFAULT_FIXTURE_SHA256
    private var expectedSnapshotSha = DEFAULT_SNAPSHOT_SHA256
    private var assertSnapshotSha = true
    private var expectedFrames = DEFAULT_FRAME_COUNT
    private var expectedForEachFrames = DEFAULT_FOREACH_FRAMES
    private var expectedPreviewPacketsCount = DEFAULT_PREVIEW_PACKETS
    private var exercise = EncoderExercise.BENCH_PRESS
    private var calibrationA = ImagePoint(162.0, 834.0)
    private var calibrationB = ImagePoint(508.0, 834.0)
    private var trackingSeed = ImagePoint(328.0, 834.0)
    private var referenceLengthM = 0.4

    private fun configureFixture(arguments: android.os.Bundle) {
        expectedFixtureSha = arguments.getString("encoder_ui_fixture_sha") ?: DEFAULT_FIXTURE_SHA256
        val snapshotArg = arguments.getString("encoder_ui_expected_snapshot_sha")
        if (snapshotArg == "ANY") {
            // Probe run only: the fixture has no prior snapshot. The value is still recorded and
            // every subsequent measured arm must pass the exact learned SHA.
            assertSnapshotSha = false
            expectedSnapshotSha = DEFAULT_SNAPSHOT_SHA256
        } else {
            assertSnapshotSha = true
            expectedSnapshotSha = snapshotArg ?: DEFAULT_SNAPSHOT_SHA256
        }
        expectedFrames = (arguments.getString("encoder_ui_expected_frames")?.toIntOrNull()
            ?: DEFAULT_FRAME_COUNT).also { require(it > 1) }
        expectedForEachFrames = expectedFrames - 1
        expectedPreviewPacketsCount =
            PreviewMmrPrefetchFrameSource.expectedPreviewPackets(0, expectedFrames, 1)
        arguments.getString("encoder_ui_exercise")?.let { exercise = EncoderExercise.valueOf(it) }
        fun argPoint(prefix: String, default: ImagePoint): ImagePoint {
            val x = arguments.getString("${prefix}_x")?.toDoubleOrNull()
            val y = arguments.getString("${prefix}_y")?.toDoubleOrNull()
            require(x != null && y != null) { "incomplete $prefix coordinates" }
            return ImagePoint(x, y)
        }
        if (arguments.containsKey("encoder_ui_seed_x")) trackingSeed = argPoint("encoder_ui_seed", trackingSeed)
        if (arguments.containsKey("encoder_ui_cal_a_x")) calibrationA = argPoint("encoder_ui_cal_a", calibrationA)
        if (arguments.containsKey("encoder_ui_cal_b_x")) calibrationB = argPoint("encoder_ui_cal_b", calibrationB)
        arguments.getString("encoder_ui_ref_m")?.toDoubleOrNull()?.let { referenceLengthM = it }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun realUiRunWhenRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.containsKey(UI_PREFETCH_KEY)) {
            assumeTrue("no $UI_PREFETCH_KEY requested: UI canary NOT_RUN", false)
        }
        val target = instrumentation.targetContext
        val flag = arguments.getString(UI_PREFETCH_KEY)
        val runId = arguments.getString(UI_RUN_ID_KEY)
        if (flag != UI_PREFETCH_ON && flag != UI_PREFETCH_OFF) {
            writeDurableReport(
                target = target,
                runId = runId?.takeIf { EncoderPerformanceSupport.isSafeRunId(it) } ?: "invalid-flag",
                status = EncoderPerformanceSupport.STATUS_FAILED,
                error = "invalid value at '$UI_PREFETCH_KEY' (expected ON or OFF, was '$flag')",
                provenance = provenance(target, flag, runId, arguments),
                measurements = JsonObject(emptyMap()),
                output = null,
            )
            throw AssertionError("invalid $UI_PREFETCH_KEY='$flag' (durable report written)")
        }
        if (runId == null || !EncoderPerformanceSupport.isSafeRunId(runId)) {
            throw AssertionError("missing or unsafe '$UI_RUN_ID_KEY' (expected ${"[A-Za-z0-9][A-Za-z0-9._-]{0,63}"})")
        }
        runRealUiCanary(target = target, prefetchFlag = flag, runId = runId, arguments = arguments)
    }

    @Test
    fun cancelAndReturnWhenRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(UI_CANCEL_KEY) != "TRUE") {
            assumeTrue("no $UI_CANCEL_KEY=TRUE requested: cancel canary NOT_RUN", false)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        // Cancel proving run reuses the same real240 fixture and actual route; never 60/120 trials.
        runCancelReturn(target = target, arguments = arguments)
    }

    private fun runRealUiCanary(
        target: Context,
        prefetchFlag: String?,
        runId: String,
        arguments: android.os.Bundle,
    ) {
        configureFixture(arguments)
        val reportDir = File(target.filesDir, "encoder-ui-prefetch").apply { mkdirs() }
        val reportFile = try {
            EncoderPerformanceSupport.distinctReportFile(reportDir, "encoder_ui_prefetch_$runId")
        } catch (error: Throwable) {
            // distinctReportFile already enforces safe run ids and no-overwrite; surface durably.
            throw AssertionError("cannot allocate durable UI report for run '$runId': ${error.message}")
        }
        val evidenceDir = File(target.cacheDir, "encoder-ui-prefetch-evidence-$runId").apply { mkdirs() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var sampler: EncoderPerfSampler? = null
        var createdPreview: PreviewMmrPrefetchFrameSource? = null
        var previewDiagnostics: PreviewMmrDiagnostics? = null
        var lastPhase: String? = null
        var lastProgress: Float? = null
        var lastResults: Int? = null
        // Hoisted for safe teardown: finally drives the normal VM cancel path (resetTracking)
        // and awaits callback close/join/termination before Activity destruction.
        var routeVmRef: FrameSelectorViewModel? = null
        var bodyError: Throwable? = null
        try {
            // Exact real240 fixture, app-owned; never a plate-circle substitution.
            val fixtureArg = arguments.getString(UI_FIXTURE_KEY)
            val fixture = if (fixtureArg.isNullOrBlank()) {
                File(target.filesDir, "encoder-perf-fixtures/new240.mp4")
            } else {
                File(fixtureArg).also {
                    require(it.isAbsolute) { "optional $UI_FIXTURE_KEY must be an absolute app-owned path" }
                    require(it.exists()) { "optional $UI_FIXTURE_KEY does not exist: $fixtureArg" }
                }
            }
            assertTrue("missing real240 fixture at ${fixture.path}", fixture.isFile)
            assertFixtureSha256(fixture, expectedFixtureSha)
            val index = VideoFrameIndexer.indexFromPath(fixture.absolutePath)
            assertEquals("fixture must keep ${expectedFrames} unaltered source frames", expectedFrames, index.frameCount)
            val seedFrameIndex = 0

            // Controlled AppSession fixture: BENCH_PRESS load 0 unknown, automatic imported timing.
            val setupDecision = PhysicalTimeline.automaticTimingDecision(VideoSource.IMPORTED, index)
            val arm = if (prefetchFlag == UI_PREFETCH_ON) "on" else "off"
            compose.runOnUiThread {
                AppSession.reset()
                AppSession.beginEncoder(EncoderSetup(exercise, 0.0), athleteId = 1L)
                AppSession.attachVideo(Uri.fromFile(fixture), VideoSource.IMPORTED)
                AppSession.setEncoderTiming(setupDecision)
                AppSession.setFrameIndex(0)
            }

            // DEBUG-only ephemeral factory override; OFF stays null = usual route.
            val wantPrefetch = prefetchFlag == UI_PREFETCH_ON
            val factory: SelectorTrackingSourceFactory? = if (!wantPrefetch) {
                null
            } else {
                SelectorTrackingSourceFactory { context, uri, videoIndex, profiler, seedFrame, originalObserver ->
                    PreviewMmrPrefetchFrameSource(
                        context = context,
                        uri = uri,
                        index = videoIndex,
                        profiler = profiler,
                        seedFrameIndex = seedFrame,
                        originalPreviewObserver = originalObserver,
                    ).also { createdPreview = it }
                }
            }
            compose.runOnUiThread {
                FrameSelectorViewModel.debugTrackingSourceFactory = factory
            }

            // Actual production NavHost in the instrumented Activity host.
            var navController: androidx.navigation.NavHostController? = null
            compose.setContent {
                OpenJumpTheme {
                    val controller = rememberNavController()
                    androidx.compose.runtime.SideEffect { navController = controller }
                    OpenJumpNavHost(navController = controller)
                }
            }
            compose.runOnUiThread { requireNotNull(navController).navigate("selector") }
            compose.waitForIdle()
            val controller = requireNotNull(navController)
            val routeVm: FrameSelectorViewModel = compose.runOnUiThreadWithReturn {
                val entry = controller.getBackStackEntry("selector")
                ViewModelProvider(entry, FrameSelectorViewModel.Factory)[FrameSelectorViewModel::class.java]
            }
            routeVmRef = routeVm

            // After actual player canMark frame 0 / rendered PTS wait, drive the real VM.
            compose.waitUntil(120_000) {
                routeVm.displayState.value.canMark && routeVm.displayState.value.renderedIndex == 0
            }
            assertEquals(0, routeVm.displayState.value.renderedIndex)
            compose.runOnUiThread {
                routeVm.useManualEncoderSelection()
                check(routeVm.markCalibrationPoint(calibrationA))
                check(routeVm.markCalibrationPoint(calibrationB))
                routeVm.confirmManualEncoderCalibration(referenceLengthM)
                routeVm.selectTrackingTarget(trackingSeed)
            }
            compose.waitUntil(120_000) { routeVm.trackingState.value.phase == TrackingPhase.READY }
            lastPhase = routeVm.trackingState.value.phase.name
            // Seed/import/setup are excluded from every timed interval below.

            sampler = EncoderPerfSampler(target, samplingEnabled = true, intervalMs = 1000).also {
                it.captureStart()
                it.start()
            }
            // First tap is the existing reference-primary READY action; start the monotonic timer
            // immediately before performClick. Compose automatic clock is identical on both arms;
            // no animation-wait hangs: progress is polled from real VM state below.
            val trackingClickNs = System.nanoTime()
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()

            // LIVE preview proof during PROCESSING (never after COMPLETED): wait for the real
            // composed tracking-preview canvas plus a paired live bitmap.
            compose.waitUntil(600_000) {
                val state = routeVm.trackingState.value
                lastPhase = state.phase.name
                lastProgress = state.progress
                lastResults = state.results.size
                state.phase == TrackingPhase.PROCESSING &&
                    state.processingPreview != null &&
                    compose.onAllNodesWithTag("tracking-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            val firstPreview = requireNotNull(routeVm.trackingState.value.processingPreview) {
                "live processingPreview missing during PROCESSING"
            }
            assertLivePreviewPair(routeVm, index, firstPreview.result.frameIndex)
            assertPreviewBitmapBounded(firstPreview.bitmap)
            val firstNonBlack = countNonBlackPixels(firstPreview.bitmap)
            assertTrue("live preview must carry real video pixels (nonBlack=$firstNonBlack)", firstNonBlack > 20)
            val firstPixels = firstPreview.bitmap.pixelMap()
            val firstCanvas = compose.onNodeWithTag("tracking-preview").captureToImage().toPixelMap()
            val firstFrame = firstPreview.result.frameIndex
            saveEvidenceBitmap(firstPreview.bitmap, evidenceDir, "${runId}_${arm}_live_preview_f${firstFrame}.png")
            // First live screenshot: timed overhead included equally on both arms.
            saveScreenshot(instrumentation.uiAutomation.takeScreenshot(), evidenceDir, "${runId}_${arm}_live_shot1.png")

            // Second live preview >= 240 frames later with a distinct bitmap: proves the preview
            // advances with the video instead of freezing on one frame.
            compose.waitUntil(600_000) {
                val preview = routeVm.trackingState.value.processingPreview
                lastPhase = routeVm.trackingState.value.phase.name
                lastProgress = routeVm.trackingState.value.progress
                lastResults = routeVm.trackingState.value.results.size
                preview != null && preview.result.frameIndex >= firstFrame + 240 && !preview.bitmap.sameAs(firstPreview.bitmap)
            }
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            val secondPreview = requireNotNull(routeVm.trackingState.value.processingPreview) {
                "second live processingPreview missing during PROCESSING"
            }
            assertLivePreviewPair(routeVm, index, secondPreview.result.frameIndex)
            assertPreviewBitmapBounded(secondPreview.bitmap)
            val secondNonBlack = countNonBlackPixels(secondPreview.bitmap)
            assertTrue("second live preview must carry real video pixels (nonBlack=$secondNonBlack)", secondNonBlack > 20)
            val advanced = countAdvancedPixels(firstPixels, secondPreview.bitmap)
            assertTrue(
                "live video pixels must advance outside any marker/overlay neighborhood " +
                    "(advanced=$advanced frame ${firstFrame}->${secondPreview.result.frameIndex})",
                advanced > 50,
            )
            val secondCanvas = compose.onNodeWithTag("tracking-preview").captureToImage().toPixelMap()
            assertEquals(firstCanvas.width, secondCanvas.width)
            assertEquals(firstCanvas.height, secondCanvas.height)
            val mapper = VideoCoordinateMapper(
                VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees),
                ViewportSize(firstCanvas.width.toDouble(), firstCanvas.height.toDouble()),
            )
            val markerCenters = listOfNotNull(
                firstPreview.result.result.sample.point,
                secondPreview.result.result.sample.point,
            ).map(mapper::videoToView)
            var renderedVideoChanges = 0
            for (y in 0 until firstCanvas.height step 4) for (x in 0 until firstCanvas.width step 4) {
                // Exclude both marker/ROI neighborhoods: changing only overlay cannot pass.
                if (markerCenters.any { kotlin.math.abs(x - it.x) < 80 && kotlin.math.abs(y - it.y) < 80 }) continue
                val a = firstCanvas[x, y]
                val b = secondCanvas[x, y]
                if (maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) > 0.01f) {
                    renderedVideoChanges++
                }
            }
            assertTrue("Rendered video must advance outside marker: $renderedVideoChanges", renderedVideoChanges > 50)
            saveEvidenceBitmap(secondPreview.bitmap, evidenceDir, "${runId}_${arm}_live_preview_f${secondPreview.result.frameIndex}.png")
            // Second live screenshot: same timed overhead on both arms.
            saveScreenshot(instrumentation.uiAutomation.takeScreenshot(), evidenceDir, "${runId}_${arm}_live_shot2.png")

            // Then wait for the canonical COMPLETED terminal with real committed results and the
            // actual review action displayed.
            compose.waitUntil(600_000) {
                val state = routeVm.trackingState.value
                val canonical = AppSession.encoderDraft.value?.tracking
                lastPhase = state.phase.name
                lastProgress = state.progress
                lastResults = state.results.size
                state.phase == TrackingPhase.COMPLETED &&
                    canonical != null && canonical == state.results && canonical.isNotEmpty()
            }
            val trackingToReviewNs = System.nanoTime() - trackingClickNs

            // Deterministic automated transition: actual Analizar trayectoria button, then the real
            // encoder/result screen. No fake Analyzer invocation; production EncoderResultVM computes.
            val analyzeText = target.getString(R.string.selector_analyze_trajectory)
            compose.onNodeWithText(analyzeText).assertIsDisplayed()
            val analyzeClickNs = System.nanoTime()
            compose.onNodeWithText(analyzeText).performClick()
            compose.waitUntil(120_000) {
                compose.onAllNodesWithTag("encoder-result-content").fetchSemanticsNodes().isNotEmpty() &&
                    AppSession.encoderAnalysis.value != null
            }
            compose.onNodeWithTag("encoder-result-content").assertIsDisplayed()
            val analyzeToVisibleNs = System.nanoTime() - analyzeClickNs
            val trackingClickToResultNs = System.nanoTime() - trackingClickNs
            val automatedTransitionNs = trackingClickToResultNs - trackingToReviewNs

            // Endpoint sampler capture BEFORE the heavy snapshot allocation/JSON dump, so ART and
            // memory metadata are not inflated by report serialization on either arm.
            runBlocking { sampler?.stop() }
            sampler?.captureEnd()

            val tracking = AppSession.encoderDraft.value?.tracking
                ?: error("real AppSession tracking missing after actual route computation")
            val analysis = AppSession.encoderAnalysis.value
                ?: error("real AppSession analysis missing after actual encoder/result computation")
            assertEquals(expectedFrames, tracking.size)
            assertTrue("no LOST samples allowed in UI canary", tracking.none { it.result.sample.status == TrackingStatus.LOST })
            tracking.forEachIndexed { position, result ->
                assertEquals(position, result.frameIndex)
                assertEquals(index.timeOf(position), result.result.sample.timestampUs)
            }
            val output = EncoderPerformanceSupport.outputSnapshot(tracking, analysis)
            val snapshotSha = EncoderPerformanceSupport.snapshotSha256(output)
            if (assertSnapshotSha) {
                assertEquals(
                    "output snapshot must equal the frozen full-SHA fixture",
                    expectedSnapshotSha,
                    snapshotSha,
                )
            } else {
                assertEquals("probe snapshot must be a full SHA", 64, snapshotSha.length)
            }
            previewDiagnostics = createdPreview?.previewDiagnostics()

            val measurements = obj(
                "ui_flow_wall_ns" to JsonPrimitive(trackingClickToResultNs),
                "tracking_to_review_ns" to JsonPrimitive(trackingToReviewNs),
                "analyze_to_visible_ns" to JsonPrimitive(analyzeToVisibleNs),
                "automated_transition_ns" to JsonPrimitive(automatedTransitionNs),
                "expired_scope_note" to JsonPrimitive(
                    "deterministic test wait/auto-transition included; no human think time; seed/import/setup excluded",
                ),
                "live_preview_assert" to JsonPrimitive(
                    "tracking-preview displayed during PROCESSING; exact frame/PTS pair; " +
                        "nonBlack1=$firstNonBlack nonBlack2=$secondNonBlack advanced=$advanced renderedVideoChanges=$renderedVideoChanges " +
                        "frames $firstFrame->${secondPreview.result.frameIndex}; 2 live screenshots same-arm overhead included",
                ),
                "hidden_content_rule" to JsonPrimitive("no assert on hidden content counts as visible; result tag displayed asserted"),
                "sampler" to (sampler?.sampledSummary() ?: JsonNull),
                "sampler_endpoint" to (sampler?.endpointSnapshot() ?: JsonNull),
                "sampler_claim" to JsonPrimitive("ART/GC traffic and endpoints are sampled, not true peaks"),
                "denominators" to denominatorsElement(index, tracking.size, analysis.samples.size, createdPreview, previewDiagnostics),
                "index" to indexElement(index, setupDecision),
                "source" to obj(
                    "route" to JsonPrimitive(if (wantPrefetch) PreviewMmrPrefetchFrameSource.SOURCE_ROUTE else "DEFAULT_DISPATCHERS_IO"),
                    "prefetch" to prefetchElement(wantPrefetch, previewDiagnostics),
                ),
                "envelope" to obj(
                    "combined_image_envelope_bytes" to JsonPrimitive(PreviewMmrPrefetchFrameSource.COMBINED_IMAGE_ENVELOPE_BYTES),
                    "claim" to JsonPrimitive(PreviewMmrPrefetchFrameSource.ENVELOPE_NOTE),
                ),
                "output_snapshot_sha256" to JsonPrimitive(snapshotSha),
            )
            // All gates must hold BEFORE any PASSED is written.
            assertUiGates(
                trackingSize = tracking.size,
                analysisSamples = analysis.samples.size,
                wantPrefetch = wantPrefetch,
                createdPreviewPresent = createdPreview != null,
                diagnostics = previewDiagnostics,
            )
            val report = EncoderPerformanceSupport.buildRunReport(
                runId = "encoder_ui_prefetch_$runId",
                status = EncoderPerformanceSupport.STATUS_PASSED,
                provenance = provenance(target, prefetchFlag, runId, arguments),
                measurements = measurements,
                output = output,
            )
            // Streamed durable write; never readText the huge Android JSON.
            EncoderPerformanceSupport.writeReport(reportFile, report)
            assertTrue(reportFile.isFile)
        } catch (error: Throwable) {
            bodyError = error
            try {
                runBlocking { sampler?.stop() }
            } catch (_: Throwable) {
            }
            val status = if (error is kotlinx.coroutines.CancellationException) {
                EncoderPerformanceSupport.STATUS_CANCELLED
            } else {
                EncoderPerformanceSupport.STATUS_FAILED
            }
            try {
                val partial = obj(
                    "last_phase" to (lastPhase?.let { JsonPrimitive(it) } ?: JsonNull),
                    "last_progress" to (lastProgress?.let { JsonPrimitive(it) } ?: JsonNull),
                    "last_results" to (lastResults?.let { JsonPrimitive(it) } ?: JsonNull),
                    "arm" to JsonPrimitive(prefetchFlag ?: "NOT_SUPPLIED"),
                )
                writeDurableReport(
                    target = target,
                    runId = runId,
                    status = status,
                    error = "${error.javaClass.simpleName}: ${error.message}",
                    provenance = provenance(target, prefetchFlag, runId, arguments),
                    measurements = partial,
                    output = null,
                )
            } catch (_: Throwable) {
            }
            throw error
        } finally {
            // Safe teardown ONLY through the normal VM path: resetTracking cancels the job and the
            // runner closes via the job-completion callback. Await callback close/join/termination
            // before Activity destruction; the fallback source close below is then an idle close.
            // Never masks the body failure: cleanup errors are suppressed onto it.
            var cleanupError: Throwable? = null
            try {
                compose.runOnUiThread {
                    try { routeVmRef?.resetTracking() } catch (_: Throwable) { }
                }
            } catch (error: Throwable) {
                cleanupError = error
            }
            try {
                val preview = createdPreview
                if (preview != null) {
                    compose.waitUntil(30_000) {
                        val diagnostics = try {
                            preview.previewDiagnostics()
                        } catch (_: Throwable) {
                            null
                        }
                        diagnostics != null && diagnostics.closed &&
                            diagnostics.prefetchDiagnostics.joined &&
                            diagnostics.prefetchDiagnostics.ownerShutdown &&
                            diagnostics.prefetchDiagnostics.ownerTerminated
                    }
                }
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
            }
            try {
                createdPreview?.close()
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
            }
            try {
                previewDiagnostics = try {
                    createdPreview?.previewDiagnostics()
                } catch (_: Throwable) {
                    previewDiagnostics
                }
            } catch (_: Throwable) {
            }
            try {
                compose.runOnUiThread {
                    FrameSelectorViewModel.debugTrackingSourceFactory = null
                    AppSession.reset()
                }
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
            }
            // Never swallow a cleanup failure to mask a failed report: surface it when the body passed.
            // When the body already failed, preserve its original error and suppress cleanup onto it.
            if (bodyError == null && cleanupError != null) throw cleanupError
            if (bodyError != null && cleanupError != null) bodyError.addSuppressed(cleanupError)
        }
    }

    private fun assertLivePreviewPair(
        routeVm: FrameSelectorViewModel,
        index: VideoFrameIndex,
        frameIndex: Int,
    ) {
        val state = routeVm.trackingState.value
        val preview = requireNotNull(state.processingPreview) { "live processingPreview cleared mid-PROCESSING" }
        assertEquals(frameIndex, preview.result.frameIndex)
        // Exact index/PTS pairing: the preview result must exist verbatim in results and match
        // the canonical index PTS (same-points pair, never a future/off-frame point).
        val match = state.results.lastOrNull {
            it.frameIndex == preview.result.frameIndex &&
                it.result.sample.timestampUs == preview.result.result.sample.timestampUs
        }
        assertNotNull("live preview bitmap pair must match an exact frame/PTS result", match)
        assertEquals(index.timeOf(preview.result.frameIndex), preview.result.result.sample.timestampUs)
    }

    private fun assertPreviewBitmapBounded(bitmap: Bitmap) {
        assertTrue("preview width ${bitmap.width} exceeds 384", bitmap.width <= 384)
        assertTrue("preview height ${bitmap.height} exceeds 384", bitmap.height <= 384)
        assertTrue(
            "private preview bytes ${bitmap.allocationByteCount} exceed 589824",
            bitmap.allocationByteCount <= PreviewMmrPrefetchFrameSource.TINY_BYTES,
        )
    }

    private fun Bitmap.pixelMap(): IntArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    private fun countNonBlackPixels(bitmap: Bitmap): Int {
        var count = 0
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.BLACK) count++
                x += stepX
            }
            y += stepY
        }
        return count
    }

    /**
     * Real color-video advancement between two live preview bitmaps. The preview bitmaps carry no
     * overlay by construction (raw decoded copy before any Canvas draw), so any counted difference
     * is video motion outside any marker neighborhood; the >50 threshold additionally exceeds what
     * any marker-sized region could produce. Per-pixel normalized mean RGB distance > 0.01 counts
     * as advanced (real RGB video, no neutral-only requirement).
     */
    private fun countAdvancedPixels(firstPixels: IntArray, second: Bitmap): Int {
        // Dimension mismatch must fail loudly: silently returning a huge count would fake success.
        assertEquals(
            "preview bitmap dimensions must match for pixel-advance comparison",
            firstPixels.size,
            second.width * second.height,
        )
        val secondPixels = second.pixelMap()
        var advanced = 0
        var i = 0
        val stride = 8
        while (i < firstPixels.size) {
            val a = firstPixels[i]
            val b = secondPixels[i]
            val dr = (Color.red(a) - Color.red(b)).toFloat() / 255f
            val dg = (Color.green(a) - Color.green(b)).toFloat() / 255f
            val db = (Color.blue(a) - Color.blue(b)).toFloat() / 255f
            val distance = (kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)) / 3f
            if (distance > 0.01f) advanced++
            i += stride
        }
        return advanced
    }

    private fun saveEvidenceBitmap(bitmap: Bitmap, directory: File, name: String) {
        try {
            val file = File(directory, name)
            file.outputStream().buffered().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveScreenshot(bitmap: Bitmap?, directory: File, name: String) {
        try {
            if (bitmap == null) return
            try {
                val file = File(directory, name)
                file.outputStream().buffered().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
        }
    }

    private fun denominatorsElement(
        index: VideoFrameIndex,
        tracked: Int,
        analysisSamples: Int?,
        createdPreview: PreviewMmrPrefetchFrameSource?,
        diagnostics: PreviewMmrDiagnostics?,
    ): JsonObject = obj(
        "index_frame_count" to JsonPrimitive(index.frameCount.toLong()),
        "expected_host_frames" to JsonPrimitive(expectedFrames.toLong()),
        "host_equals_android" to JsonPrimitive(index.frameCount == expectedFrames),
        "seed_frames" to JsonPrimitive(1L),
        "wrapper_frame_at_delivered" to JsonPrimitive(1L),
        "wrapper_foreach_delivered" to JsonPrimitive(
            (diagnostics?.prefetchDiagnostics?.consumed ?: (tracked - 1)).toLong(),
        ),
        "wrapper_consumed_total" to JsonPrimitive(
            (1 + (diagnostics?.prefetchDiagnostics?.consumed ?: (tracked - 1))).toLong(),
        ),
        "n_tracked_including_seed" to JsonPrimitive(tracked.toLong()),
        "n_analysis_samples" to (analysisSamples?.let { JsonPrimitive(it.toLong()) } ?: JsonPrimitive("NOT_AVAILABLE")),
        "eligible" to JsonPrimitive(index.frameCount == expectedFrames),
        "rule" to JsonPrimitive(
            "host and Android frame counts must match before any timing is comparable;" +
                " foreach delivers index-1 frames after the seed; analysis samples live in the output snapshot",
        ),
    )

    private fun indexElement(index: VideoFrameIndex, decision: VideoTimingDecision): JsonObject {
        val presentation = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)
        return obj(
            "encoded_width" to JsonPrimitive(index.width),
            "encoded_height" to JsonPrimitive(index.height),
            "presentation_width" to JsonPrimitive(presentation.width),
            "presentation_height" to JsonPrimitive(presentation.height),
            "rotation_degrees" to JsonPrimitive(index.rotationDegrees),
            "mime_type" to (index.mimeType?.let { JsonPrimitive(it) } ?: JsonNull),
            "duration_us" to JsonPrimitive(index.durationUs),
            "pts_authority" to JsonPrimitive("index.frameTimesUs (MediaExtractor sample times, strictly increasing)"),
            "pts_index_us" to JsonArray(index.frameTimesUs.map { JsonPrimitive(it) }),
            "timing_decision" to obj(
                "mode" to JsonPrimitive(decision.mode.name),
                "slow_motion_factor_bits" to EncoderPerformanceSupport.doubleBits(decision.slowMotionFactor),
                "time_scale_bits" to EncoderPerformanceSupport.doubleBits(decision.timeScale),
                "is_reliable" to JsonPrimitive(decision.isReliable),
            ),
        )
    }

    private fun prefetchElement(wantPrefetch: Boolean, diagnostics: PreviewMmrDiagnostics?): JsonObject {
        if (!wantPrefetch || diagnostics == null) {
            return obj(
                "status" to JsonPrimitive("DISABLED"),
                "mode" to JsonPrimitive(UI_PREFETCH_OFF),
                "source_route" to JsonPrimitive("DEFAULT_DISPATCHERS_IO"),
                "note" to JsonPrimitive("usual route; no preview wrapper; factory stayed null"),
            )
        }
        val core = diagnostics.prefetchDiagnostics
        return obj(
            "status" to JsonPrimitive("ENABLED"),
            "mode" to JsonPrimitive(UI_PREFETCH_ON),
            "source_route" to JsonPrimitive(diagnostics.sourceRoute),
            "produced_preview_packets" to JsonPrimitive(diagnostics.producedPreviewPackets),
            "delivered_preview_packets" to JsonPrimitive(diagnostics.deliveredPreviewPackets),
            "expected_preview_packets" to JsonPrimitive(expectedPreviewPacketsCount),
            "preview_cadence" to JsonPrimitive(diagnostics.previewCadence),
            "max_preview_bytes" to JsonPrimitive(diagnostics.maxPreviewBytes),
            "max_live_preview_copies" to JsonPrimitive(diagnostics.maxLivePreviewCopies),
            "max_live_preview_bytes" to JsonPrimitive(diagnostics.maxLivePreviewBytes),
            "dropped_oldest" to JsonPrimitive(diagnostics.droppedOldest),
            "pending_preview_packets" to JsonPrimitive(diagnostics.pendingPreviewPackets),
            "max_pending_preview_packets" to JsonPrimitive(diagnostics.maxPendingPreviewPackets),
            "preview_pts_matches" to JsonPrimitive(diagnostics.previewPtsMatches),
            "preview_pts_mismatches" to JsonPrimitive(diagnostics.previewPtsMismatches),
            "prefetch_produced" to JsonPrimitive(core.produced),
            "prefetch_consumed" to JsonPrimitive(core.consumed),
            "prefetch_joined" to JsonPrimitive(core.joined),
            "prefetch_closed" to JsonPrimitive(core.closed),
            "owner_shutdown" to JsonPrimitive(core.ownerShutdown),
            "owner_terminated" to JsonPrimitive(core.ownerTerminated),
            "producer_thread_ids" to JsonArray(diagnostics.producerThreadIds.map { JsonPrimitive(it) }),
            "consumer_thread_id" to (diagnostics.consumerThreadId?.let { JsonPrimitive(it) } ?: JsonNull),
            "owner_thread_id" to (core.ownerThreadId?.let { JsonPrimitive(it) } ?: JsonNull),
            "combined_image_envelope_bytes" to JsonPrimitive(diagnostics.combinedImageEnvelopeBytes),
            "envelope_note" to JsonPrimitive(diagnostics.envelopeNote),
        )
    }

    private fun assertUiGates(
        trackingSize: Int,
        analysisSamples: Int?,
        wantPrefetch: Boolean,
        createdPreviewPresent: Boolean,
        diagnostics: PreviewMmrDiagnostics?,
    ) {
        assertEquals(expectedFrames, trackingSize)
        assertTrue("analysis must return samples", (analysisSamples ?: 0) > 0)
        if (!wantPrefetch) {
            assertTrue("OFF arm must not create a preview wrapper", !createdPreviewPresent && diagnostics == null)
            return
        }
        val preview = requireNotNull(diagnostics) { "ON arm must capture the factory-created preview source" }
        assertTrue("ON arm factory must actually create the source", createdPreviewPresent)
        // 4675 foreach frames after the seed; cadence (frame-0)%5==0 over 1..4675 gives 935.
        assertEquals(expectedForEachFrames, preview.prefetchDiagnostics.produced)
        assertEquals(expectedForEachFrames, preview.prefetchDiagnostics.consumed)
        assertEquals(expectedPreviewPacketsCount, preview.producedPreviewPackets)
        assertEquals(preview.producedPreviewPackets, preview.deliveredPreviewPackets)
        assertEquals(0, preview.droppedOldest)
        assertEquals(0, preview.pendingPreviewPackets)
        assertEquals(0, preview.previewPtsMismatches)
        assertEquals(preview.deliveredPreviewPackets, preview.previewPtsMatches)
        assertTrue(preview.maxPendingPreviewPackets in 0..PreviewMmrPrefetchFrameSource.MAX_PREVIEW_COPIES)
        assertTrue(preview.maxLivePreviewCopies in 0..PreviewMmrPrefetchFrameSource.MAX_PREVIEW_COPIES)
        assertTrue(preview.maxLivePreviewBytes <= PreviewMmrPrefetchFrameSource.MAX_PREVIEW_BYTES)
        assertTrue("producer must join before terminal", preview.prefetchDiagnostics.joined)
        assertTrue("wrapper must be closed before PASSED", preview.closed)
        assertTrue(preview.prefetchDiagnostics.closed)
        assertTrue(preview.prefetchDiagnostics.ownerShutdown)
        assertTrue("owner must terminate before PASSED", preview.prefetchDiagnostics.ownerTerminated)
    }

    private fun runCancelReturn(target: Context, arguments: android.os.Bundle) {
        configureFixture(arguments)
        // Cancel proving run on the same real240 fixture through the actual Cancel action:
        // start via actual READY click -> live preview -> actual Cancel click -> calibration
        // preserved, no AppSession commit, preview gone, closed/joined/terminated confirmed before
        // exit -> popBackStack -> return -> reselect/retry with no stale generation.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(target.filesDir, "encoder-perf-fixtures/new240.mp4")
        assertTrue("missing real240 fixture for cancel canary: ${fixture.path}", fixture.isFile)
        assertFixtureSha256(fixture, expectedFixtureSha)
        val index = VideoFrameIndexer.indexFromPath(fixture.absolutePath)
        assertEquals("fixture frame count changed", expectedFrames, index.frameCount)
        val prefetchArg = arguments.getString(UI_PREFETCH_KEY)
        val wantPrefetch = prefetchArg == UI_PREFETCH_ON
        val createdSources = mutableListOf<PreviewMmrPrefetchFrameSource>()
        val factory: SelectorTrackingSourceFactory? = if (!wantPrefetch) {
            null
        } else {
            SelectorTrackingSourceFactory { context, uri, videoIndex, profiler, seedFrame, originalObserver ->
                PreviewMmrPrefetchFrameSource(
                    context = context,
                    uri = uri,
                    index = videoIndex,
                    profiler = profiler,
                    seedFrameIndex = seedFrame,
                    originalPreviewObserver = originalObserver,
                ).also { createdSources += it }
            }
        }
        val decision = PhysicalTimeline.automaticTimingDecision(VideoSource.IMPORTED, index)
        // Durable lifecycle report id: parent supplies encoder_ui_run_id; fallback stays safe/unique.
        val lifecycleRunId = arguments.getString(UI_RUN_ID_KEY)?.takeIf { EncoderPerformanceSupport.isSafeRunId(it) }
            ?: "lifecycle-${if (wantPrefetch) "on" else "off"}-${(System.nanoTime() and 0xFFFFFF).toString(16)}"
        // Hoisted so finally keeps the actual route/returned VM refs accessible for safe teardown.
        var routeVmRef: FrameSelectorViewModel? = null
        var returnedVmRef: FrameSelectorViewModel? = null
        var lifecycleStage = "setup"
        var bodyError: Throwable? = null
        compose.runOnUiThread {
            AppSession.reset()
            AppSession.beginEncoder(EncoderSetup(exercise, 0.0), athleteId = 1L)
            AppSession.attachVideo(Uri.fromFile(fixture), VideoSource.IMPORTED)
            AppSession.setEncoderTiming(decision)
            AppSession.setFrameIndex(0)
            FrameSelectorViewModel.debugTrackingSourceFactory = factory
        }
        try {
            var navController: androidx.navigation.NavHostController? = null
            compose.setContent {
                OpenJumpTheme {
                    val controller = rememberNavController()
                    androidx.compose.runtime.SideEffect { navController = controller }
                    OpenJumpNavHost(navController = controller)
                }
            }
            compose.runOnUiThread { requireNotNull(navController).navigate("selector") }
            compose.waitForIdle()
            val controller = requireNotNull(navController)
            val routeVm: FrameSelectorViewModel = compose.runOnUiThreadWithReturn {
                val entry = controller.getBackStackEntry("selector")
                ViewModelProvider(entry, FrameSelectorViewModel.Factory)[FrameSelectorViewModel::class.java]
            }
            routeVmRef = routeVm
            lifecycleStage = "gen1-setup"
            compose.waitUntil(120_000) {
                routeVm.displayState.value.canMark && routeVm.displayState.value.renderedIndex == 0
            }
            compose.runOnUiThread {
                routeVm.useManualEncoderSelection()
                check(routeVm.markCalibrationPoint(calibrationA))
                check(routeVm.markCalibrationPoint(calibrationB))
                routeVm.confirmManualEncoderCalibration(referenceLengthM)
                routeVm.selectTrackingTarget(trackingSeed)
            }
            compose.waitUntil(120_000) { routeVm.trackingState.value.phase == TrackingPhase.READY }
            // Generation 1: start through the actual READY reference-primary action.
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()
            // Actual processing preview displayed (live, during PROCESSING).
            compose.waitUntil(120_000) {
                routeVm.trackingState.value.phase == TrackingPhase.PROCESSING &&
                    routeVm.trackingState.value.processingPreview != null &&
                    compose.onAllNodesWithTag("tracking-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            val oldPreview = requireNotNull(routeVm.trackingState.value.processingPreview)
            val oldPixels = oldPreview.bitmap.pixelMap()
            @Suppress("UNUSED_VARIABLE")
            val unusedLiveProof = countNonBlackPixels(oldPreview.bitmap)
            assertTrue(unusedLiveProof > 20)
            val calibrationBefore = AppSession.encoderDraft.value?.calibration
            assertNotNull(calibrationBefore)
            // Actual Cancel through the same reference-primary action (Cancel tracking while busy).
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()
            compose.waitUntil(30_000) { routeVm.trackingState.value.phase == TrackingPhase.IDLE }
            assertEquals(calibrationBefore, AppSession.encoderDraft.value?.calibration)
            assertTrue(AppSession.encoderDraft.value?.tracking.isNullOrEmpty())
            assertTrue(routeVm.trackingState.value.processingPreview == null)
            if (wantPrefetch) {
                assertTrue("ON cancel arm must capture the created source", createdSources.isNotEmpty())
                // The cancelled operation must join and terminate before exit/return.
                compose.waitUntil(30_000) {
                    createdSources.all {
                        val diagnostics = it.previewDiagnostics()
                        diagnostics.closed && diagnostics.prefetchDiagnostics.joined &&
                            diagnostics.prefetchDiagnostics.ownerShutdown &&
                            diagnostics.prefetchDiagnostics.ownerTerminated
                    }
                }
            }
            // Exit and return: popBackStack then navigate selector again; the NavHost clears the
            // old VM after the animation, so the returned VM must be a new instance.
            compose.runOnUiThread { controller.popBackStack() }
            compose.waitForIdle()
            compose.runOnUiThread { controller.navigate("selector") }
            compose.waitForIdle()
            val returnedVm: FrameSelectorViewModel = compose.runOnUiThreadWithReturn {
                val entry = controller.getBackStackEntry("selector")
                ViewModelProvider(entry, FrameSelectorViewModel.Factory)[FrameSelectorViewModel::class.java]
            }
            returnedVmRef = returnedVm
            lifecycleStage = "returned"
            assertTrue("return must produce a new cleared VM, not the stale generation", returnedVm !== routeVm)
            compose.waitUntil(120_000) {
                returnedVm.displayState.value.canMark && returnedVm.displayState.value.renderedIndex == 0
            }
            // Manual calibration state is inherited from AppSession when reset did not clear it;
            // reselect preserves the same endpoints, otherwise recalibrate identically.
            compose.runOnUiThread {
                returnedVm.useManualEncoderSelection()
                if (AppSession.encoderDraft.value?.calibration == null) {
                    check(returnedVm.markCalibrationPoint(calibrationA))
                    check(returnedVm.markCalibrationPoint(calibrationB))
                    returnedVm.confirmManualEncoderCalibration(referenceLengthM)
                }
                returnedVm.selectTrackingTarget(trackingSeed)
            }
            compose.waitUntil(120_000) { returnedVm.trackingState.value.phase == TrackingPhase.READY }
            assertNull("READY must not carry a stale processing preview", returnedVm.trackingState.value.processingPreview)
            // Generation 2: start through the actual READY action; the new preview must belong to
            // the new generation with a distinct bitmap (no old pair reuse).
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()
            compose.waitUntil(120_000) {
                returnedVm.trackingState.value.phase == TrackingPhase.PROCESSING &&
                    returnedVm.trackingState.value.processingPreview != null &&
                    compose.onAllNodesWithTag("tracking-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            val newPreview = requireNotNull(returnedVm.trackingState.value.processingPreview)
            // Same video/seed/frame may legitimately decode to identical PIXELS, so freshness is
            // proven by object identity plus a new VM/source generation and an exact frame/PTS
            // pair in the returned VM results never by requiring different sporting pixels.
            assertTrue(
                "new generation preview must be a distinct bitmap instance, not the old pair",
                newPreview.bitmap !== oldPreview.bitmap,
            )
            if (wantPrefetch) {
                assertEquals("second generation must create its own source", 2, createdSources.size)
                assertTrue("second generation source must be a new instance", createdSources[1] !== createdSources[0])
            }
            val newPair = returnedVm.trackingState.value.results.lastOrNull {
                it.frameIndex == newPreview.result.frameIndex &&
                    it.result.sample.timestampUs == newPreview.result.result.sample.timestampUs
            }
            assertNotNull("new generation preview must match an exact frame/PTS result in the returned VM", newPair)
            assertEquals(index.timeOf(newPreview.result.frameIndex), newPreview.result.result.sample.timestampUs)
            @Suppress("UNUSED_VARIABLE")
            // Diagnostic only: same-frame pixels may legitimately match; never gate on this.
            val newPixelsDifferent = countAdvancedPixels(oldPixels, newPreview.bitmap)
            lifecycleStage = "gen2-processing"
            // Actual Cancel of the second generation.
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()
            compose.waitUntil(30_000) { returnedVm.trackingState.value.phase == TrackingPhase.IDLE }
            assertTrue(returnedVm.trackingState.value.processingPreview == null)
            if (wantPrefetch) {
                assertEquals("both generations must create their own source", 2, createdSources.size)
                compose.waitUntil(30_000) {
                    createdSources.all {
                        val diagnostics = it.previewDiagnostics()
                        diagnostics.closed && diagnostics.prefetchDiagnostics.joined &&
                            diagnostics.prefetchDiagnostics.ownerShutdown &&
                            diagnostics.prefetchDiagnostics.ownerTerminated
                    }
                }
            } else {
                assertTrue("OFF cancel arm creates no wrapper source", createdSources.isEmpty())
            }
            lifecycleStage = "gen2-cancelled"
            // Generation 3: reselect + start until live preview, then exit WHILE active (no Cancel).
            // This exercises real onCleared with a live job: the fixed disposeOfflineTracker must
            // order runner.close after actual job completion instead of racing it. A few seconds
            // per generation; no full 240 re-track.
            compose.runOnUiThread {
                returnedVm.selectTrackingTarget(trackingSeed)
            }
            compose.waitUntil(120_000) { returnedVm.trackingState.value.phase == TrackingPhase.READY }
            compose.onNodeWithTag("reference-primary").assertIsDisplayed()
            compose.onNodeWithTag("reference-primary").performClick()
            compose.waitUntil(120_000) {
                returnedVm.trackingState.value.phase == TrackingPhase.PROCESSING &&
                    returnedVm.trackingState.value.processingPreview != null &&
                    compose.onAllNodesWithTag("tracking-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tracking-preview").assertIsDisplayed()
            requireNotNull(returnedVm.trackingState.value.processingPreview) {
                "third generation live processingPreview missing during PROCESSING"
            }
            lifecycleStage = "gen3-processing-exit-active"
            // Actual nav pop WHILE active, without Cancel: triggers real onCleared with a live job.
            compose.runOnUiThread { controller.popBackStack() }
            compose.waitForIdle()
            if (wantPrefetch) {
                assertEquals("all three generations must create their own source", 3, createdSources.size)
                assertTrue("third generation source must be a new instance", createdSources[2] !== createdSources[1])
                // onCleared must join the producer and terminate the owner; no idle-close crash.
                compose.waitUntil(30_000) {
                    createdSources.all {
                        val diagnostics = it.previewDiagnostics()
                        diagnostics.closed && diagnostics.prefetchDiagnostics.joined &&
                            diagnostics.prefetchDiagnostics.ownerShutdown &&
                            diagnostics.prefetchDiagnostics.ownerTerminated
                    }
                }
            } else {
                assertTrue("OFF lifecycle arm creates no wrapper source", createdSources.isEmpty())
            }
            assertTrue(
                "exit-while-active must not commit canonical tracking",
                AppSession.encoderDraft.value?.tracking.isNullOrEmpty(),
            )
            lifecycleStage = "done"
            // Tiny durable lifecycle report (no snapshot): PASSED only after all gates above held.
            writeDurableReport(
                target = target,
                runId = lifecycleRunId,
                status = EncoderPerformanceSupport.STATUS_PASSED,
                error = null,
                provenance = provenance(target, prefetchArg, lifecycleRunId, arguments),
                measurements = lifecycleMeasurements(
                    stage = lifecycleStage,
                    routeVm = routeVmRef,
                    returnedVm = returnedVmRef,
                    createdSources = createdSources,
                    wantPrefetch = wantPrefetch,
                ),
                output = null,
            )
        } catch (error: Throwable) {
            bodyError = error
            // Durable report preserves the actual primary status: the old Activity-destruction
            // crash masked it, so capture stage/phase/source diagnostics here.
            try {
                writeDurableReport(
                    target = target,
                    runId = lifecycleRunId,
                    status = if (error is kotlinx.coroutines.CancellationException) {
                        EncoderPerformanceSupport.STATUS_CANCELLED
                    } else {
                        EncoderPerformanceSupport.STATUS_FAILED
                    },
                    error = "${error.javaClass.simpleName}: ${error.message}",
                    provenance = provenance(target, prefetchArg, lifecycleRunId, arguments),
                    measurements = lifecycleMeasurements(
                        stage = lifecycleStage,
                        routeVm = routeVmRef,
                        returnedVm = returnedVmRef,
                        createdSources = createdSources,
                        wantPrefetch = wantPrefetch,
                    ),
                    output = null,
                )
            } catch (_: Throwable) {
            }
            throw error
        } finally {
            // Safe teardown ONLY through the normal VM path: resetTracking cancels the job and the
            // runner closes via the job-completion callback. Await callback close/join/owner
            // termination (30s) before Activity destruction so onCleared never meets an active
            // operation; the fallback source close below is then an idle close. Never masks the
            // body failure: cleanup errors are suppressed onto it.
            var cleanupError: Throwable? = null
            try {
                compose.runOnUiThread {
                    try { routeVmRef?.resetTracking() } catch (_: Throwable) { }
                    try { returnedVmRef?.resetTracking() } catch (_: Throwable) { }
                }
            } catch (error: Throwable) {
                cleanupError = error
            }
            try {
                if (createdSources.isNotEmpty()) {
                    compose.waitUntil(30_000) {
                        createdSources.all {
                            val diagnostics = try {
                                it.previewDiagnostics()
                            } catch (_: Throwable) {
                                null
                            }
                            diagnostics != null && diagnostics.closed &&
                                diagnostics.prefetchDiagnostics.joined &&
                                diagnostics.prefetchDiagnostics.ownerShutdown &&
                                diagnostics.prefetchDiagnostics.ownerTerminated
                        }
                    }
                }
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
            }
            createdSources.forEach { source ->
                try {
                    source.close()
                } catch (error: Throwable) {
                    if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
                }
            }
            try {
                compose.runOnUiThread {
                    FrameSelectorViewModel.debugTrackingSourceFactory = null
                    AppSession.reset()
                }
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError.addSuppressed(error)
            }
            if (bodyError == null && cleanupError != null) throw cleanupError
            if (bodyError != null && cleanupError != null) bodyError.addSuppressed(cleanupError)
        }
    }

    private fun assertFixtureSha256(file: File, expectedHex: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals("fixture SHA-256 mismatch at ${file.path}", expectedHex, actual)
    }

    private fun provenance(
        target: Context,
        flag: String?,
        runId: String?,
        arguments: android.os.Bundle,
    ): JsonObject = obj(
        "scope" to JsonPrimitive("UI_PREFETCH_CANARY_REAL_ROUTE"),
        "flag" to JsonPrimitive(flag ?: "NOT_SUPPLIED"),
        "run_id" to JsonPrimitive(runId ?: "NOT_SUPPLIED"),
        "fixture" to JsonPrimitive(arguments.getString(UI_FIXTURE_KEY) ?: "files/encoder-perf-fixtures/new240.mp4"),
        "head" to JsonPrimitive(arguments.getString("encoder_ui_head") ?: "NOT_SUPPLIED_PARENT_VERIFIES"),
        "apk_sha256" to JsonPrimitive(arguments.getString("encoder_ui_apk_sha256") ?: "NOT_SUPPLIED_PARENT_VERIFIES"),
        "source_fingerprint" to JsonPrimitive(arguments.getString("encoder_ui_source_fingerprint") ?: "NOT_SUPPLIED_PARENT_VERIFIES"),
        "package_name" to JsonPrimitive(target.packageName),
        "expected_frames" to JsonPrimitive(expectedFrames),
        "expected_snapshot_sha256" to JsonPrimitive(expectedSnapshotSha),
        "snapshot_assert_enabled" to JsonPrimitive(assertSnapshotSha),
        "exercise" to JsonPrimitive(exercise.name),
        "reference_length_m" to JsonPrimitive(referenceLengthM),
        "seed_px" to JsonPrimitive("${trackingSeed.x},${trackingSeed.y}"),
        "calibration_px" to JsonPrimitive("${calibrationA.x},${calibrationA.y}->${calibrationB.x},${calibrationB.y}"),
    )

    /**
     * Tiny lifecycle report payload (no snapshot): current stage/phase plus per-source
     * close/join/termination flags. Fully defensive: VM/diagnostics reads never throw.
     */
    private fun lifecycleMeasurements(
        stage: String,
        routeVm: FrameSelectorViewModel?,
        returnedVm: FrameSelectorViewModel?,
        createdSources: List<PreviewMmrPrefetchFrameSource>,
        wantPrefetch: Boolean,
    ): JsonObject {
        fun phaseOf(vm: FrameSelectorViewModel?): JsonElement = try {
            vm?.trackingState?.value?.phase?.name?.let { JsonPrimitive(it) } ?: JsonNull
        } catch (_: Throwable) {
            JsonPrimitive("UNREADABLE")
        }
        fun resultsOf(vm: FrameSelectorViewModel?): JsonElement = try {
            vm?.trackingState?.value?.results?.size?.let { JsonPrimitive(it) } ?: JsonNull
        } catch (_: Throwable) {
            JsonPrimitive("UNREADABLE")
        }
        return obj(
            "stage" to JsonPrimitive(stage),
            "arm" to JsonPrimitive(if (wantPrefetch) UI_PREFETCH_ON else UI_PREFETCH_OFF),
            "route_phase" to phaseOf(routeVm),
            "route_results" to resultsOf(routeVm),
            "returned_phase" to phaseOf(returnedVm),
            "returned_results" to resultsOf(returnedVm),
            "created_sources" to JsonPrimitive(createdSources.size),
            "sources" to JsonArray(createdSources.mapIndexed { sourceIndex, source ->
                try {
                    val diagnostics = source.previewDiagnostics()
                    obj(
                        "index" to JsonPrimitive(sourceIndex),
                        "closed" to JsonPrimitive(diagnostics.closed),
                        "joined" to JsonPrimitive(diagnostics.prefetchDiagnostics.joined),
                        "owner_terminated" to JsonPrimitive(diagnostics.prefetchDiagnostics.ownerTerminated),
                        "produced" to JsonPrimitive(diagnostics.prefetchDiagnostics.produced),
                        "consumed" to JsonPrimitive(diagnostics.prefetchDiagnostics.consumed),
                    )
                } catch (error: Throwable) {
                    obj(
                        "index" to JsonPrimitive(sourceIndex),
                        "diagnostics_error" to JsonPrimitive(error.javaClass.simpleName),
                    )
                }
            }),
            "rule" to JsonPrimitive("tiny lifecycle report; no snapshot; unique file per run, never overwritten"),
        )
    }

    private fun writeDurableReport(
        target: Context,
        runId: String,
        status: String,
        error: String?,
        provenance: JsonObject,
        measurements: JsonObject,
        output: JsonObject?,
    ) {
        val directory = File(target.filesDir, "encoder-ui-prefetch").apply { mkdirs() }
        val safeId = if (EncoderPerformanceSupport.isSafeRunId(runId)) runId else "invalid-run-id"
        // Never overwrite a previous run's file: on collision allocate a unique diagnostics id.
        val file = try {
            EncoderPerformanceSupport.distinctReportFile(directory, "encoder_ui_prefetch_$safeId")
        } catch (_: Throwable) {
            EncoderPerformanceSupport.distinctReportFile(
                directory,
                "encoder_ui_prefetch_${safeId.take(32)}_c${(System.nanoTime() and 0xFFFFFF).toString(16)}",
            )
        }
        val report = EncoderPerformanceSupport.buildRunReport(
            runId = file.nameWithoutExtension.removePrefix(EncoderPerformanceSupport.REPORT_FILE_PREFIX),
            status = status,
            provenance = provenance,
            measurements = measurements,
            output = output,
            error = error,
        )
        EncoderPerformanceSupport.writeReport(file, report)
    }

    private fun obj(vararg members: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*members))

    private fun <T> androidx.compose.ui.test.junit4.ComposeContentTestRule.runOnUiThreadWithReturn(action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        runOnUiThread {
            try {
                result = action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        const val UI_PREFETCH_KEY = "encoder_ui_prefetch"
        const val UI_PREFETCH_ON = "ON"
        const val UI_PREFETCH_OFF = "OFF"
        const val UI_RUN_ID_KEY = "encoder_ui_run_id"
        const val UI_FIXTURE_KEY = "encoder_ui_fixture"
        const val UI_CANCEL_KEY = "encoder_ui_cancel_return"
        const val DEFAULT_FIXTURE_SHA256 = "6bd055c9e659bf7b9d51fd402967b5a5543a1277609049f1d2d28951c649f983"
        const val DEFAULT_SNAPSHOT_SHA256 = "63c5901d7175e9e2ee1de90b1933594c864dac15a1b8cf014472df6f5af83ed3"
        const val DEFAULT_FRAME_COUNT = 4676
        const val DEFAULT_FOREACH_FRAMES = 4675
        const val DEFAULT_PREVIEW_PACKETS = 935
    }
}
