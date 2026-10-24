package com.openjump.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderPlaybackPositionMapperTest {
    @Test
    fun `maps actual source position and duration without frame rate`() {
        assertEquals(0.25f, EncoderPlaybackPositionMapper.fraction(positionUs = 625L, durationUs = 2_500L), 0.0001f)
        assertEquals(625L, EncoderPlaybackPositionMapper.positionUs(fraction = 0.25f, durationUs = 2_500L))
    }

    @Test
    fun `normalizes a nonzero source PTS origin for Media3`() {
        assertEquals(250L, EncoderPlaybackPositionMapper.presentationPtsUs(1_250L, 1_000L))
        assertEquals(0L, EncoderPlaybackPositionMapper.presentationPtsUs(900L, 1_000L))
    }

    @Test
    fun `uses VFR PTS with nonzero origin and duration after last frame`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(100_000L, 150_000L, 275_000L),
            durationUs = 400_000L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 30.0,
        )
        val samePtsDifferentFps = index.copy(nominalFrameRate = 120.0)
        assertEquals(300_000L, EncoderPlaybackPositionMapper.playbackDurationUs(index))
        assertEquals(0.5f, EncoderPlaybackPositionMapper.fraction(250_000L, index), 0.0001f)
        assertEquals(275_000L, EncoderPlaybackPositionMapper.sourcePtsForFraction(0.5f, index))
        assertEquals(
            EncoderPlaybackPositionMapper.sourcePtsForFraction(0.5f, index),
            EncoderPlaybackPositionMapper.sourcePtsForFraction(0.5f, samePtsDifferentFps),
        )
    }

    @Test
    fun `snaps fraction ties to the previous real PTS`() {
        val index = VideoFrameIndex(longArrayOf(100_000L, 150_000L, 275_000L), 325_000L, 100, 100, 0, "video/avc")
        // 212,500µs is exactly midway between 150,000 and 275,000.
        assertEquals(150_000L, EncoderPlaybackPositionMapper.sourcePtsForFraction(0.5f, index))
    }

    @Test
    fun `clamps slider values and handles unavailable duration`() {
        assertEquals(0f, EncoderPlaybackPositionMapper.fraction(positionUs = -1L, durationUs = 2_500L), 0f)
        assertEquals(1f, EncoderPlaybackPositionMapper.fraction(positionUs = 3_000L, durationUs = 2_500L), 0f)
        assertEquals(0L, EncoderPlaybackPositionMapper.positionUs(fraction = Float.NaN, durationUs = 2_500L))
        assertEquals(2_500L, EncoderPlaybackPositionMapper.positionUs(fraction = 2f, durationUs = 2_500L))
        assertEquals(0L, EncoderPlaybackPositionMapper.positionUs(fraction = 0.5f, durationUs = 0L))
    }
}
