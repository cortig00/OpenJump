package com.openjump.app.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceInfoFormatTest {

    @Test
    fun `effective fps uses user locale decimal while nominal whole stays integer`() {
        assertEquals("119.8", VideoSourceInfoFormat.effectiveFps(Locale.US, 119.8))
        assertEquals("119,8", VideoSourceInfoFormat.effectiveFps(Locale.GERMANY, 119.8))
        assertEquals("120", VideoSourceInfoFormat.nominalFps(Locale.US, 120.0))
        assertEquals("120", VideoSourceInfoFormat.nominalFps(Locale.GERMANY, 120.0))
        assertEquals("119,8", VideoSourceInfoFormat.nominalFps(Locale.GERMANY, 119.8))
    }

    @Test
    fun `invalid fps stays unknown without inventing zero`() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -24.0).forEach { fps ->
            assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.effectiveFps(Locale.US, fps))
            assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.nominalFps(Locale.US, fps))
        }
    }

    @Test
    fun `unknown codec and size render as dash never zero or raw mime`() {
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.codecLabel(null))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.codecLabel("  "))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.codecLabel("video/x-raw"))
        assertEquals("H.264", VideoSourceInfoFormat.codecLabel("video/avc"))
        assertEquals("H.264", VideoSourceInfoFormat.codecLabel("VIDEO/AVC"))
        assertEquals("H.265", VideoSourceInfoFormat.codecLabel("video/hevc"))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.sizeMegabytes(Locale.US, null, false))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.sizeMegabytes(Locale.US, -1.0, false))
        assertEquals(VideoSourceInfoFormat.LOADING, VideoSourceInfoFormat.sizeMegabytes(Locale.US, null, true))
        assertEquals(VideoSourceInfoFormat.LOADING, VideoSourceInfoFormat.sizeMegabytes(Locale.US, 12.3, true))
        assertEquals("12.3", VideoSourceInfoFormat.sizeMegabytes(Locale.US, 12.3, false))
        assertEquals("12,3", VideoSourceInfoFormat.sizeMegabytes(Locale.GERMANY, 12.3, false))
    }

    @Test
    fun `resolution alias applies only to exact encoded dimensions`() {
        assertEquals("720p", VideoSourceInfoFormat.resolutionLabel(1280, 720))
        assertEquals("1080p", VideoSourceInfoFormat.resolutionLabel(1920, 1080))
        // Encoded dimensions, never rotation-swapped presentation: a portrait
        // geometry is not relabeled as 720p.
        assertEquals("720×1280", VideoSourceInfoFormat.resolutionLabel(720, 1280))
        assertEquals("640×480", VideoSourceInfoFormat.resolutionLabel(640, 480))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.resolutionLabel(0, 0))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.resolutionLabel(-1, 720))
    }

    @Test
    fun `detail geometry always keeps both encoded dimensions`() {
        assertEquals("1280×720", VideoSourceInfoFormat.resolutionGeometry(1280, 720))
        assertEquals("1920×1080", VideoSourceInfoFormat.resolutionGeometry(1920, 1080))
        assertEquals("720×1280", VideoSourceInfoFormat.resolutionGeometry(720, 1280))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.resolutionGeometry(0, 0))
    }

    @Test
    fun `vfr gaps and bitrate format with user locale`() {
        assertFalse(VideoSourceInfoFormat.hasGaps(0))
        assertTrue(VideoSourceInfoFormat.hasGaps(2))
        assertEquals("83.3", VideoSourceInfoFormat.gapLargestMs(Locale.US, 83_333L))
        assertEquals("83,3", VideoSourceInfoFormat.gapLargestMs(Locale.GERMANY, 83_333L))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.gapLargestMs(Locale.US, 0L))
        assertEquals("10.0", VideoSourceInfoFormat.bitrateMbps(Locale.US, 10_000_000))
        assertEquals("10,0", VideoSourceInfoFormat.bitrateMbps(Locale.GERMANY, 10_000_000))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.bitrateMbps(Locale.US, null))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.bitrateMbps(Locale.US, 0))
    }

    @Test
    fun `frame count uses locale integers without grouping`() {
        assertEquals("5", VideoSourceInfoFormat.frameCount(Locale.US, 5))
        assertEquals("1200", VideoSourceInfoFormat.frameCount(Locale.GERMANY, 1200))
        assertEquals(VideoSourceInfoFormat.UNKNOWN, VideoSourceInfoFormat.frameCount(Locale.US, -1))
    }
}
