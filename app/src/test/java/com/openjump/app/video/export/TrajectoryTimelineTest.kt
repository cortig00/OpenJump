package com.openjump.app.video.export

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TrajectoryTimelineTest {

    @Test
    fun `normaliza PTS inicial no cero y nunca muestra una muestra futura`() {
        val index = index(longArrayOf(900_000L, 904_167L, 908_333L, 912_500L))
        val timeline = TrajectoryTimeline(index, listOf(result(index, 1), result(index, 2)))

        assertNull(timeline.sampleAtOrBefore(4_166L))
        assertEquals(1, timeline.sampleAtOrBefore(4_167L)?.frameIndex)
        assertEquals(1, timeline.sampleAtOrBefore(8_332L)?.frameIndex)
        assertEquals(2, timeline.sampleAtOrBefore(8_333L)?.frameIndex)
    }

    @Test
    fun `usa PTS reales para CFR 30 60 120 y 240`() {
        listOf(33_333L, 16_667L, 8_333L, 4_167L).forEach { delta ->
            val index = index(LongArray(4) { 100_000L + it * delta })
            val timeline = TrajectoryTimeline(index, (0..3).map { result(index, it) })

            assertEquals(2, timeline.sampleAtOrBefore(delta * 3 - 1)?.frameIndex)
            assertEquals(3, timeline.sampleAtOrBefore(delta * 3)?.frameIndex)
        }
    }

    @Test
    fun `respeta VFR seed intermedio ultimo punto y LOST sin posicion`() {
        val index = index(longArrayOf(50_000L, 60_000L, 95_000L, 101_000L, 150_000L))
        val results = listOf(
            result(index, 2),
            result(index, 3),
            result(index, 4, TrackingStatus.LOST),
        )
        val timeline = TrajectoryTimeline(index, results)

        assertNull(timeline.sampleAtOrBefore(44_999L))
        assertEquals(ImagePoint(3.0, 6.0), timeline.sampleAtOrBefore(51_000L)?.point)
        val lost = timeline.sampleAtOrBefore(100_000L)
        assertEquals(TrackingStatus.LOST, lost?.status)
        assertNull(lost?.point)
    }

    @Test
    fun `rechaza PTS desalineado y resultados desordenados`() {
        val index = index(longArrayOf(0L, 10_000L, 20_000L))
        val badPts = result(index, 1).let { frame ->
            frame.copy(result = frame.result.copy(sample = frame.result.sample.copy(timestampUs = 9_999L)))
        }

        assertThrows(IllegalArgumentException::class.java) {
            TrajectoryTimeline(index, listOf(badPts))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrajectoryTimeline(index, listOf(result(index, 2), result(index, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrajectoryTimeline(index, listOf(result(index, 0, TrackingStatus.LOST)))
        }
    }

    private fun index(times: LongArray) = VideoFrameIndex(
        frameTimesUs = times,
        durationUs = times.last() - times.first() + 10_000L,
        width = 1920,
        height = 1080,
        rotationDegrees = 0,
        mimeType = "video/avc",
    )

    private fun result(
        index: VideoFrameIndex,
        frame: Int,
        status: TrackingStatus = TrackingStatus.TRACKING,
    ): TrackingFrameResult {
        val point = if (status == TrackingStatus.LOST) null else ImagePoint(frame.toDouble(), frame * 2.0)
        return TrackingFrameResult(
            frameIndex = frame,
            result = TrackingResult(
                sample = TrackingSample(index.timeOf(frame), point, if (point == null) 0.0 else 1.0, status),
                region = null,
                featurePoints = emptyList(),
                diagnostics = TrackingDiagnostics.EMPTY,
            ),
        )
    }
}
