package com.openjump.app.encoder

import com.openjump.app.protocol.VideoSource
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalTimelineTest {
    @Test
    fun `slow motion factor scales canonical pts rather than frame indices`() {
        val resolved = PhysicalTimeline.resolve(
            listOf(1_000_000L, 1_033_333L, 1_070_000L),
            VideoTimingDecision(VideoTimingMode.SLOW_MOTION, 4.0),
        )

        assertEquals(listOf(0L, 8_333L, 17_500L), resolved.timesUs)
        assertEquals(114.29, resolved.effectiveFps!!, 0.1)
    }

    @Test
    fun `unknown timing has no physical timestamps`() {
        val resolved = PhysicalTimeline.resolve(
            listOf(0L, 33_333L),
            VideoTimingDecision(VideoTimingMode.UNKNOWN),
        )

        assertEquals(listOf(null, null), resolved.timesUs)
        assertNull(resolved.effectiveFps)
    }

    @Test
    fun `capture metadata alone suggests standard factor`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 16_667L, 33_334L),
            durationUs = 33_334L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            metadataCaptureFrameRate = 240.0,
        )

        assertEquals(4.0, PhysicalTimeline.suggestedSlowMotionFactor(index)!!, 0.0)
    }

    @Test
    fun `capture metadata takes precedence over conflicting nominal metadata`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 33_333L, 66_666L),
            durationUs = 66_666L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 240.0,
            metadataCaptureFrameRate = 30.0,
        )

        assertNull(PhysicalTimeline.suggestedSlowMotionFactor(index))
        assertEquals(
            VideoTimingMode.REAL_TIME,
            PhysicalTimeline.automaticTimingDecision(VideoSource.IMPORTED, index).mode,
        )
    }

    @Test
    fun `nominal metadata alone suggests standard factor`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 33_333L, 66_666L),
            durationUs = 66_666L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 240.0,
        )

        assertEquals(8.0, PhysicalTimeline.suggestedSlowMotionFactor(index)!!, 0.0)
    }

    @Test
    fun `ratio inside tolerance is accepted and ratio outside is ignored`() {
        val nearStandard = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 20_000L, 40_000L),
            durationUs = 40_000L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 110.0,
        )
        val outsideTolerance = nearStandard.copy(nominalFrameRate = 112.5)

        assertEquals(2.0, PhysicalTimeline.suggestedSlowMotionFactor(nearStandard)!!, 0.0)
        assertNull(PhysicalTimeline.suggestedSlowMotionFactor(outsideTolerance))
    }

    @Test
    fun `non standard ratio falls back to real time`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 20_000L, 40_000L),
            durationUs = 40_000L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 75.0,
        )

        assertEquals(
            VideoTimingMode.REAL_TIME,
            PhysicalTimeline.automaticTimingDecision(VideoSource.IMPORTED, index).mode,
        )
    }

    @Test
    fun `unknown source does not infer from metadata`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 33_333L, 66_666L),
            durationUs = 66_666L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 240.0,
        )

        assertEquals(
            VideoTimingMode.REAL_TIME,
            PhysicalTimeline.automaticTimingDecision(VideoSource.UNKNOWN, index).mode,
        )
    }

    @Test
    fun `recorded source always resolves to real time`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 33_333L, 66_666L),
            durationUs = 66_666L,
            width = 100,
            height = 100,
            rotationDegrees = 0,
            mimeType = "video/avc",
            nominalFrameRate = 240.0,
        )

        assertEquals(
            VideoTimingMode.REAL_TIME,
            PhysicalTimeline.automaticTimingDecision(VideoSource.RECORDED, index).mode,
        )
    }
}
