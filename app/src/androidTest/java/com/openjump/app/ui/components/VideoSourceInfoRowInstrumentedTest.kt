package com.openjump.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.video.VideoSourceInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Row contract over in-memory [VideoSourceInfo] snapshots only: no file I/O and
 * no fake track metadata (size fixtures already live in S1's reader tests).
 */
@RunWith(AndroidJUnit4::class)
class VideoSourceInfoRowInstrumentedTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val locale get() = context.resources.configuration.locales[0]

    private fun fullInfo() = VideoSourceInfo(
        videoUri = "content://test/clip",
        encodedWidth = 1280,
        encodedHeight = 720,
        durationUs = 40_000L,
        playbackDurationUs = 40_000L,
        frameCount = 5,
        ptsFramesPerSecond = 119.8,
        nominalFrameRate = 120.0,
        metadataCaptureFrameRate = null,
        codecMimeType = "video/avc",
        declaredAverageBitrateBps = 10_000_000,
        medianDeltaUs = 8_347L,
        gapThresholdUs = 16_694L,
        gapCount = 0,
        largestGapUs = 8_347L,
        sizeBytes = 12_300_000L,
        sizeMegabytesDecimal = 12.3,
    )

    private fun setContent(info: VideoSourceInfo, isSizeLoading: Boolean = false, dark: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OpenJumpTheme(darkTheme = dark) {
                    VideoSourceInfoRowContent(info = info, isSizeLoading = isSizeLoading)
                }
            }
        }
    }

    @Test
    fun summaryShowsEncodedResolutionAndEffectivePtsRateDistinctFromNominal() {
        val info = fullInfo()
        setContent(info)
        val effective = VideoSourceInfoFormat.effectiveFps(locale, 119.8)
        val nominal = VideoSourceInfoFormat.nominalFps(locale, 120.0)
        compose.onNodeWithTag("video-source-summary").assertIsDisplayed()
        compose.onNodeWithTag("video-source-summary").assertTextContains("720p", substring = true)
        compose.onNodeWithTag("video-source-summary").assertTextContains(effective, substring = true)
        // The summary carries the effective PTS rate, not the declared nominal one.
        compose.onNodeWithTag("video-source-details-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("video-source-sheet").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_effective_fps)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_nominal_fps)).assertIsDisplayed()
        compose.onNodeWithText(nominal, substring = true).assertExists()
    }

    @Test
    fun unknownCodecAndSizeRenderAsDashWithoutRawMime() {
        val info = fullInfo().copy(codecMimeType = "video/x-raw", sizeBytes = null, sizeMegabytesDecimal = null)
        setContent(info)
        compose.onNodeWithTag("video-source-summary").assertTextContains(VideoSourceInfoFormat.UNKNOWN, substring = true)
        compose.onNodeWithTag("video-source-details-button").performClick()
        compose.onNodeWithTag("video-source-sheet").assertIsDisplayed()
        compose.onNodeWithText("video/x-raw").assertDoesNotExist()
        compose.onAllNodesWithText(VideoSourceInfoFormat.UNKNOWN).assertCountEquals(2)
    }

    @Test
    fun encodedLandscapeKeeps720pAliasAndFullGeometry() {
        // Encoded 1280×720 keeps its 720p alias (rotation lives in presentation,
        // not in these dimensions).
        setContent(fullInfo())
        compose.onNodeWithTag("video-source-summary").assertTextContains("720p", substring = true)
        compose.onNodeWithText("720×1280").assertDoesNotExist()
        // Details keep the full encoded geometry behind the summary alias.
        compose.onNodeWithTag("video-source-details-button").performClick()
        compose.onNodeWithTag("video-source-sheet").assertIsDisplayed()
        compose.onNodeWithText("1280×720", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.video_source_close)).performScrollTo().performClick()
        compose.onNodeWithTag("video-source-sheet").assertDoesNotExist()
    }

    @Test
    fun encodedPortraitKeepsFullGeometryWithoutSwap() {
        val portrait = fullInfo().copy(encodedWidth = 720, encodedHeight = 1280)
        setContent(portrait)
        compose.onNodeWithTag("video-source-summary").assertTextContains("720×1280", substring = true)
    }

    @Test
    fun vfrGapsOpenInSheetAndCloseReturnsToSummary() {
        val info = fullInfo().copy(gapCount = 2, largestGapUs = 100_000L)
        setContent(info)
        compose.onNodeWithTag("video-source-details-button").performClick()
        compose.onNodeWithTag("video-source-sheet").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_gaps)).assertIsDisplayed()
        val expectedGaps = context.getString(
            R.string.video_source_gaps_value,
            2,
            VideoSourceInfoFormat.gapLargestMs(locale, 100_000L),
        )
        compose.onNodeWithText(expectedGaps).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_close)).assertIsDisplayed().performScrollTo().performClick()
        compose.onNodeWithTag("video-source-sheet").assertDoesNotExist()
        compose.onNodeWithTag("video-source-summary").assertIsDisplayed()
    }

    @Test
    fun loadingSizeShowsDiscretePlaceholderWhileDarkAndLargeFontStayUsable() {
        setContent(fullInfo(), isSizeLoading = true, dark = true, fontScale = 1.5f)
        compose.onNodeWithTag("video-source-summary").assertIsDisplayed()
        compose.onNodeWithTag("video-source-summary").assertTextContains(VideoSourceInfoFormat.LOADING, substring = true)
        compose.onNodeWithTag("video-source-details-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("video-source-sheet").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.video_source_close)).performScrollTo().performClick()
        compose.onNodeWithTag("video-source-sheet").assertDoesNotExist()
    }
}
