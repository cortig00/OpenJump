package com.openjump.app.video

import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EncoderPlaybackTimelineTest {
    @Test fun `repetition segments resolve inclusive source PTS and active independently`() {
        val samples = listOf(sample(0, ImagePoint(10.0, 10.0)), sample(1, ImagePoint(11.0, 11.0)), sample(2, ImagePoint(12.0, 12.0)))
        val phase = com.openjump.app.encoder.MovementPhase(com.openjump.app.encoder.MovementDirection.UP, 0, 1)
        val repetition = com.openjump.app.encoder.EncoderRepetition(
            ordinal = 0,
            startSample = 1,
            endSample = 2,
            eccentricPhase = phase.copy(startSample = 1, endSample = 1),
            concentricPhase = phase.copy(startSample = 1, endSample = 2),
            quality = com.openjump.app.encoder.RepetitionQuality.VALID,
            reasons = emptySet(),
            metrics = emptyList(),
        )
        val segments = buildEncoderRepetitionPlaybackSegments(listOf(repetition), samples)
        assertEquals(43_333L, segments.single().startSourcePtsUs)
        assertEquals(60_000L, segments.single().endSourcePtsUs)
        assertEquals(0, activeEncoderRepetitionOrdinal(segments, 43_333L))
        assertEquals(0, activeEncoderRepetitionOrdinal(segments, 60_000L))
        assertNull(activeEncoderRepetitionOrdinal(segments, 60_001L))
    }

    @Test fun `rejects duplicate ordinals and inclusive shared bounds`() {
        val first = EncoderRepetitionPlaybackSegment(0, 10L, 20L)
        assertThrows(IllegalArgumentException::class.java) {
            validateEncoderRepetitionPlaybackSegments(listOf(first, EncoderRepetitionPlaybackSegment(0, 30L, 40L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateEncoderRepetitionPlaybackSegments(listOf(first, EncoderRepetitionPlaybackSegment(1, 20L, 30L)))
        }
    }

    @Test fun `rail hit testing respects gaps tail borders and density scaled tolerance`() {
        val segments = listOf(
            EncoderRepetitionPlaybackSegment(0, 10_000L, 20_000L),
            EncoderRepetitionPlaybackSegment(1, 40_000L, 50_000L),
        )
        val geometry = encoderRepetitionRailGeometry(segments, index, railWidthPx = 100f)
        assertEquals(0, hitTestEncoderRepetitionRail(geometry, 0f, 100f, 10f))
        assertEquals(0, hitTestEncoderRepetitionRail(geometry, geometry[0].endPx, 100f, 0f))
        assertNull(hitTestEncoderRepetitionRail(geometry, 30f, 100f, 10f))
        assertEquals(1, hitTestEncoderRepetitionRail(geometry, 45f, 100f, 10f))
        assertNull(hitTestEncoderRepetitionRail(geometry, 90f, 100f, 10f))

        val denseGeometry = encoderRepetitionRailGeometry(segments, index, railWidthPx = 200f)
        assertEquals(1, hitTestEncoderRepetitionRail(denseGeometry, 100f, 200f, 20f))
    }

    @Test fun `omits labels for narrow rail segments`() {
        assertTrue(shouldShowEncoderRepetitionLabel(32f, measuredTextWidth = 20f, horizontalPadding = 12f))
        assertFalse(shouldShowEncoderRepetitionLabel(31.9f, measuredTextWidth = 20f, horizontalPadding = 12f))
        assertFalse(shouldShowEncoderRepetitionLabel(Float.NaN, measuredTextWidth = 20f, horizontalPadding = 12f))
    }

    private val index = VideoFrameIndex(longArrayOf(10_000L, 43_333L, 60_000L), 70_000L, 100, 50, 90, "video/avc")
    private val calibration = MetricCalibration(0, ImagePoint(20.0, 20.0), ImagePoint(20.0, 40.0), 0.2, 0.01)

    private fun sample(frame: Int, point: ImagePoint?, status: TrackingStatus = if (point == null) TrackingStatus.LOST else TrackingStatus.TRACKING) = EncoderSample(
        ordinal = frame,
        frameIndex = frame,
        sourcePtsUs = index.timeOf(frame),
        physicalTimeUs = frame.toLong(),
        rawPixelPoint = point,
        rawMetricPoint = point?.let { MetricPoint(it.x, it.y) },
        smoothedMetricPoint = null,
        velocityXMps = null,
        velocityYMps = null,
        confidence = 1.0,
        trackingStatus = status,
        observationKind = if (point == null) ObservationKind.MISSING else ObservationKind.MEASURED,
        fitResidualM = null,
        gapBeforeUs = null,
    )

    @Test fun `normalizes nonzero PTS and uses floor without future point`() {
        val timeline = EncoderPlaybackTimeline(index, listOf(sample(0, ImagePoint(10.0, 10.0)), sample(1, ImagePoint(11.0, 11.0)), sample(2, null)), calibration)
        assertEquals(0, timeline.indexAtOrBefore(0L))
        assertEquals(-1, timeline.indexAtOrBefore(-1L))
        assertEquals(0, timeline.indexAtOrBefore(33_332L))
        assertEquals(listOf(ImagePoint(10.0, 10.0)), timeline.pathAtOrBefore(33_332L))
        assertNull(timeline.sampleAtOrBefore(-1L))
    }

    @Test fun `lost samples split accumulated trajectory and never show a future point`() {
        val timeline = EncoderPlaybackTimeline(
            index,
            listOf(
                sample(0, ImagePoint(10.0, 10.0)),
                sample(1, null),
                sample(2, ImagePoint(12.0, 12.0)),
            ),
            calibration,
        )

        assertEquals(listOf(listOf(ImagePoint(10.0, 10.0))), timeline.pathSegmentsAtOrBefore(40_000L))
        assertEquals(
            listOf(
                listOf(ImagePoint(10.0, 10.0)),
                listOf(ImagePoint(12.0, 12.0)),
            ),
            timeline.pathSegmentsAtOrBefore(50_000L),
        )
    }

    @Test fun `keeps the first valid point after a lost corrupt row in a new segment`() {
        val timeline = EncoderPlaybackTimeline(
            index,
            listOf(
                sample(0, ImagePoint(10.0, 10.0)),
                sample(1, ImagePoint(49.0, 99.0), TrackingStatus.LOST),
                sample(2, ImagePoint(12.0, 12.0)),
            ),
            calibration,
        )
        assertEquals(
            listOf(
                listOf(ImagePoint(10.0, 10.0)),
                listOf(ImagePoint(12.0, 12.0)),
            ),
            timeline.pathSegmentsAtOrBefore(50_000L),
        )
    }

    @Test fun `missing corrupt coordinate is not used by the current overlay sample`() {
        val missing = sample(1, ImagePoint(49.0, 49.0)).copy(observationKind = ObservationKind.MISSING)
        val timeline = EncoderPlaybackTimeline(
            index,
            listOf(sample(0, ImagePoint(10.0, 10.0)), missing, sample(2, ImagePoint(12.0, 12.0))),
            calibration,
        )
        assertNull(timeline.samples[1].takeIf { it.hasUsablePoint }?.point)
        assertEquals(listOf(listOf(ImagePoint(10.0, 10.0)), listOf(ImagePoint(12.0, 12.0))), timeline.pathSegmentsAtOrBefore(50_000L))
    }

    @Test fun `rejects duplicate frame order and invalid calibration`() {
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPlaybackTimeline(index, listOf(sample(0, ImagePoint(10.0, 10.0)), sample(0, ImagePoint(11.0, 11.0))), calibration)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPlaybackTimeline(
                index,
                listOf(sample(0, ImagePoint(10.0, 10.0))),
                calibration.copy(pointB = ImagePoint(99.0, 100.0)),
            )
        }
    }

    @Test fun `rejects PTS mismatch and out of range geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPlaybackTimeline(index, listOf(sample(0, ImagePoint(10.0, 10.0)).copy(sourcePtsUs = 11_000L)), calibration)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncoderPlaybackTimeline(index, listOf(sample(0, ImagePoint(500.0, 10.0))), calibration)
        }
    }
}
