package com.openjump.app.ui.encoder

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.video.EncoderPlaybackPositionMapper
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-player UI checks against the bundled short clip; fixture data is not accuracy evidence. */
@RunWith(AndroidJUnit4::class)
class EncoderResultVideoSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var localized: Context
    private lateinit var video: File
    private lateinit var index: VideoFrameIndex
    private var originalOrientation: Int? = null
    private val playerLifecycleEvents = mutableListOf<Boolean>()

    private fun prepareVideo() {
        video = File(context.cacheDir, "encoder-result-video-selection.mp4")
        val fixturePath = InstrumentationRegistry.getArguments().getString("encoderResultsVideo")
        val input = if (fixturePath == null) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("tracking_translation.mp4")
        } else {
            require(File(fixturePath).canRead()) { "Video fixture is not readable: $fixturePath" }
            File(fixturePath).inputStream()
        }
        input.use { video.outputStream().use { output -> it.copyTo(output) } }
        index = VideoFrameIndexer.indexFromPath(video.absolutePath)
        assertTrue("Fixture has enough real indexed PTS for the three non-overlapping 4-sample repetitions", index.frameCount >= 12)
    }

    private fun forceOptionalLandscape() {
        if (InstrumentationRegistry.getArguments().getString("encoderResultsOrientation") != "landscape") return
        compose.runOnUiThread {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun restoreHostAndFixture() {
        try {
            compose.runOnUiThread {
                originalOrientation?.let { requested ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull()?.requestedOrientation = requested
                }
            }
        } finally {
            if (::video.isInitialized) video.delete()
        }
    }

    private fun evidence(name: String) {
        if (InstrumentationRegistry.getArguments().getString("encoderResultsEvidence") != "true") return
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        val folder = File(context.getExternalFilesDir(null), "encoder-results-video").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot.recycle()
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun render(analysis: EncoderAnalysis = indexedFixture(), videoUri: String? = Uri.fromFile(video).toString()) {
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        localized = context.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalUnitSystem provides UnitProfile.METRIC,
            ) {
                OpenJumpTheme(darkTheme = InstrumentationRegistry.getArguments().getString("encoderResultsDark") == "true") {
                    Scaffold(topBar = {
                        OpenJumpTopAppBar(title = localized.getString(R.string.encoder_result_title))
                    }) { padding ->
                        EncoderResultContent(
                            analysis = analysis,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            videoUri = videoUri,
                            videoIndex = index,
                            noteDraft = null,
                            onPlaybackPlayerLifecycleChanged = { active -> playerLifecycleEvents += active },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        if (InstrumentationRegistry.getArguments().getString("encoderResultsOrientation") == "landscape") {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Disposable test host must actually be landscape", root.width > root.height)
        }
    }

    @Test
    fun rapidRootSelectionsSeekOnlyLatestRAndVideoRailSelectionReturnsToAnalysis() {
        prepareVideo()
        try {
            forceOptionalLandscape()
            val analysis = indexedFixture()
            render(analysis)
            selectFromList(1)
            selectFromList(2)
            selectFromList(3)
            compose.onNodeWithTag("encoder-result-content")
                .performScrollToNode(hasText(text(R.string.encoder_view_in_video)))
            compose.onNodeWithText(text(R.string.encoder_view_in_video)).performClick()

            evidence("enter-r3-video")
            awaitPosition(analysis.repetitions[2].startSample)
            evidence("selected-r3-video")
            compose.onNodeWithTag("encoder-result-content")
                .performScrollToNode(hasTestTag("encoder-video-repetition-2"))
            // Bringing the tall lazy item into view doesn't expose its nested rail.
            // performScrollTo on the chip handles only its nearest (horizontal) LazyRow.
            val rail = compose.onNodeWithTag("encoder-video-repetition-2")
            val content = compose.onNodeWithTag("encoder-result-content")
            val scrollPage = content.fetchSemanticsNode().boundsInRoot.height
            content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollPage) }
            compose.waitForIdle()
            rail.performScrollTo().assertIsDisplayed().performClick()
            awaitPosition(analysis.repetitions[1].startSample)
            compose.onNodeWithTag("encoder-tab-analysis").performClick()
            compose.onNodeWithTag("encoder-analysis-chip-2").assertIsSelected()

            compose.onNodeWithTag("encoder-tab-video").performClick()
            compose.onNodeWithTag("encoder-result-content")
                .performScrollToNode(hasContentDescription(text(R.string.selector_video_play)))
            compose.onNodeWithContentDescription(text(R.string.selector_video_play)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("encoder-tab-analysis").performClick()
            compose.onNodeWithTag("encoder-result-video").assertDoesNotExist()
            compose.onNodeWithTag("encoder-video-player-surface").assertDoesNotExist()
            assertEquals("The real ExoPlayer is disposed when leaving Video", listOf(true, false, true, false), playerLifecycleEvents)

            compose.onNodeWithTag("encoder-tab-video").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("encoder-result-video").fetchSemanticsNodes().size == 1
            }
            compose.onAllNodesWithTag("encoder-result-video").assertCountEquals(1)
            compose.onAllNodesWithTag("encoder-video-player-surface").assertCountEquals(1)
            awaitPosition(analysis.repetitions[1].startSample)
            assertEquals("Returning creates exactly one active real player", listOf(true, false, true, false, true), playerLifecycleEvents)
        } finally {
            originalOrientation?.let { requested ->
                compose.runOnUiThread {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull()?.requestedOrientation = requested
                }
                originalOrientation = null
            }
        }
    }

    @Test
    fun cachedIndexReentryRepeatedlyCreatesExactlyOneBoundPlayerAndSeeksCanonicalPts() {
        prepareVideo()
        forceOptionalLandscape()
        val analysis = indexedFixture()
        render(analysis)
        repeat(6) { visit ->
            val ordinal = visit % 3 + 1
            selectFromList(ordinal)
            compose.onNodeWithTag("encoder-tab-video").performScrollTo().performClick()
            awaitPosition(analysis.repetitions[ordinal - 1].startSample)
            compose.onAllNodesWithTag("encoder-video-player-surface").assertCountEquals(1)
            compose.onNodeWithTag("encoder-tab-summary").performScrollTo().performClick()
            compose.onNodeWithTag("encoder-video-player-surface").assertDoesNotExist()
            assertEquals(List((visit + 1) * 2) { it % 2 == 0 }, playerLifecycleEvents)
        }
    }

    @Test
    fun unavailableVideoDoesNotRemoveAnalysisOrSelectedMetrics() {
        prepareVideo()
        try {
            forceOptionalLandscape()
            render(videoUri = null)
            compose.onNodeWithTag("encoder-tab-video").performScrollTo().performClick()
            compose.onNodeWithTag("encoder-result-video").assertIsDisplayed()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text(R.string.selector_video_unavailable)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.selector_video_unavailable)).assertIsDisplayed()
            compose.onNodeWithTag("encoder-tab-analysis").performClick()
            compose.onNodeWithTag("encoder-analysis-header").assertExists()
            compose.onNodeWithTag("encoder-analysis-metrics").assertExists()
        } finally {
            originalOrientation?.let { requested ->
                compose.runOnUiThread {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull()?.requestedOrientation = requested
                }
                originalOrientation = null
            }
        }
    }

    private fun selectFromList(ordinal: Int) {
        compose.onNodeWithTag("encoder-tab-repetitions").performClick()
        compose.onNodeWithTag("encoder-result-content")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("encoder-repetition-row-$ordinal"))
        compose.onNodeWithTag("encoder-repetition-row-$ordinal").performClick()
        compose.onNodeWithTag("encoder-tab-analysis").assertIsDisplayed()
    }

    private fun awaitPosition(sampleIndex: Int) {
        val expected = localized.getString(
            R.string.encoder_video_position,
            Formatting.usToClockMs(index.timeOf(sampleIndex) - index.frameTimesUs.first()),
            Formatting.usToClockMs(EncoderPlaybackPositionMapper.playbackDurationUs(index)),
        )
        try {
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNodeWithTag("encoder-video-position").assertTextEquals(expected)
                    true
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            runCatching { evidence("seek-failed") }
            throw AssertionError("Expected rendered position: $expected\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
        compose.onNodeWithTag("encoder-video-position").assertTextEquals(expected)
        val viewport = compose.onNodeWithTag("encoder-video-player-surface").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("encoder-result-content").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithTag("encoder-tab-video").fetchSemanticsNode().boundsInRoot
        val capPx = with(compose.density) {
            ((list.height + tab.height).toDp() - 300.dp).coerceAtLeast(128.dp).toPx()
        }
        assertTrue("Video honors height cap without stretching the FIT content", viewport.height <= capPx + 2f)
    }

    private fun indexedFixture(): EncoderAnalysis {
        val base = encoderResultUiFixture(repetitionCount = 3)
        val geometry = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)
        val samples = base.samples.mapIndexed { sampleIndex, sample ->
            val sourcePtsUs = index.timeOf(sampleIndex)
            sample.copy(
                frameIndex = sampleIndex,
                sourcePtsUs = sourcePtsUs,
                physicalTimeUs = (sourcePtsUs - index.frameTimesUs.first()) / 2L,
                rawPixelPoint = ImagePoint(
                    geometry.width * 0.3 + sampleIndex,
                    geometry.height * 0.5,
                ),
            )
        }
        return base.copy(
            samples = samples,
            calibration = MetricCalibration(
                frameIndex = 0,
                pointA = ImagePoint(geometry.width * 0.2, geometry.height * 0.2),
                pointB = ImagePoint(geometry.width * 0.8, geometry.height * 0.2),
                referenceLengthM = 0.45,
                metersPerPixel = 0.0045,
            ),
        )
    }

    private fun text(id: Int, vararg args: Any): String = localized.getString(id, *args)
}
