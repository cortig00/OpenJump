package com.openjump.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSourceInfoTest {

    private fun index(
        frameTimesUs: LongArray = longArrayOf(0L, 8_333L, 16_666L, 24_999L, 33_332L),
        durationUs: Long = 40_000L,
        width: Int = 1280,
        height: Int = 720,
        mimeType: String? = "video/avc",
        nominalFrameRate: Double? = 120.0,
        averageBitrate: Int? = 10_000_000,
    ) = VideoFrameIndex(
        frameTimesUs = frameTimesUs,
        durationUs = durationUs,
        width = width,
        height = height,
        rotationDegrees = 0,
        mimeType = mimeType,
        nominalFrameRate = nominalFrameRate,
        averageBitrate = averageBitrate,
    )

    @Test
    fun `full snapshot exposes real pts fps encoding bitrate gaps and decimal megabytes`() {
        val info = VideoSourceInfoFactory.fromIndex(
            videoUri = "content://media/video/42",
            index = index(),
            sizeBytes = 172_630_378L,
        )

        assertEquals("content://media/video/42", info.videoUri)
        assertEquals(1280, info.encodedWidth)
        assertEquals(720, info.encodedHeight)
        assertEquals(40_000L, info.durationUs)
        assertEquals(40_000L, info.playbackDurationUs)
        assertEquals(5, info.frameCount)
        assertEquals(1_000_000.0 / 8_333.0, info.ptsFramesPerSecond!!, 1e-9)
        assertEquals(120.0, info.nominalFrameRate!!, 0.0)
        assertEquals("video/avc", info.codecMimeType)
        assertEquals(10_000_000, info.declaredAverageBitrateBps)
        assertEquals(8_333L, info.medianDeltaUs)
        assertEquals(16_666L, info.gapThresholdUs)
        assertEquals(0, info.gapCount)
        assertEquals(172_630_378L, info.sizeBytes)
        assertEquals(172.630378, info.sizeMegabytesDecimal!!, 1e-9)
    }

    @Test
    fun `pts gap is reported while declared facts stay intact`() {
        val info = VideoSourceInfoFactory.fromIndex(
            videoUri = "file:///cache/clip.mp4",
            index = index(frameTimesUs = longArrayOf(0L, 8_333L, 16_666L, 100_000L)),
            sizeBytes = 1_000_000L,
        )

        assertEquals(8_333L, info.medianDeltaUs)
        assertEquals(1, info.gapCount)
        assertEquals(1.0, info.sizeMegabytesDecimal!!, 1e-9)
        assertEquals(4, info.frameCount)
    }

    @Test
    fun `unknown size stays null without blocking the indexed facts`() {
        val info = VideoSourceInfoFactory.fromIndex(
            videoUri = "content://media/video/stale",
            index = index(),
            sizeBytes = null,
        )

        assertEquals("content://media/video/stale", info.videoUri)
        assertNull(info.sizeBytes)
        assertNull(info.sizeMegabytesDecimal)
        assertEquals(5, info.frameCount)
        assertEquals("video/avc", info.codecMimeType)
    }

    @Test
    fun `negative size is sanitized to unknown instead of a false value`() {
        val info = VideoSourceInfoFactory.fromIndex(
            videoUri = "content://media/video/stale",
            index = index(),
            sizeBytes = -1L,
        )

        assertNull(info.sizeBytes)
        assertNull(info.sizeMegabytesDecimal)
    }

    @Test
    fun `undisclosed codec and bitrate stay null`() {
        val info = VideoSourceInfoFactory.fromIndex(
            videoUri = "file:///cache/clip.mp4",
            index = index(mimeType = null, nominalFrameRate = null, averageBitrate = null),
            sizeBytes = 2_500_000L,
        )

        assertNull(info.codecMimeType)
        assertNull(info.nominalFrameRate)
        assertNull(info.declaredAverageBitrateBps)
        assertEquals(2.5, info.sizeMegabytesDecimal!!, 1e-9)
    }
}
