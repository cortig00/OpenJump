package com.openjump.app.encoder

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderTimeSeriesTest {
    private fun analysis(
        physical: List<Long?> = listOf(0L, 10L, 20L, 30L),
        pts: List<Long> = listOf(100L, 110L, 120L, 130L),
        statuses: List<TrackingStatus> = List(4) { TrackingStatus.TRACKING },
        observations: List<ObservationKind> = List(4) { ObservationKind.MEASURED },
        position: List<Double?> = listOf(1.0, 1.1, 1.2, 1.3),
        velocity: List<Double?> = listOf(-1.0, -0.5, 0.5, 1.0),
    ): EncoderAnalysis {
        val samples = pts.indices.map { i ->
            EncoderSample(
                ordinal = i,
                frameIndex = i,
                sourcePtsUs = pts[i],
                physicalTimeUs = physical[i],
                rawPixelPoint = ImagePoint(i.toDouble(), i.toDouble()),
                rawMetricPoint = MetricPoint(i.toDouble(), position[i] ?: 0.0),
                smoothedMetricPoint = position[i]?.let { MetricPoint(i.toDouble(), it) },
                velocityXMps = null,
                velocityYMps = velocity[i],
                confidence = 1.0,
                trackingStatus = statuses[i],
                observationKind = observations[i],
                fitResidualM = null,
                gapBeforeUs = null,
            )
        }
        val eccentric = MovementPhase(MovementDirection.DOWN, 0, 1)
        val concentric = MovementPhase(MovementDirection.UP, 2, 3)
        return EncoderAnalysis(
            setup = EncoderSetup(EncoderExercise.SQUAT, 80.0),
            timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration = MetricCalibrator.create(0, ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 1.0),
            samples = samples,
            repetitions = listOf(EncoderRepetition(0, 0, 3, eccentric, concentric, RepetitionQuality.VALID, emptySet(), emptyList())),
            effectiveFps = 100.0,
            smoothingWindowSamples = 3,
            velocityEnterMps = null,
            velocityExitMps = null,
            warnings = emptyList(),
        )
    }

    @Test fun `nearest time point resolves an exact tie toward the floor`() {
        val a = analysis()
        val series = (EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertEquals(0, nearestTimePoint(series.points, 5L)?.ordinal)
        assertEquals(0, nearestTimePointBySource(series.points, 105L)?.ordinal)
    }

    @Test fun `time scale labels preserve physical microsecond endpoints`() {
        val a = analysis(physical = listOf(100L, 125L, 180L, 240L))
        val series = (EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertEquals(0L, timeScaleLabels(series.points).startUs)
        assertEquals(140L, timeScaleLabels(series.points).endUs)
    }

    @Test fun `chart cursor resolver keeps pending preview separate from confirmed`() {
        val a = analysis()
        val series = (EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        val beforeAck = resolveChartCursors(series.points, localSourcePtsUs = 130L, renderedSourcePtsUs = 110L)
        assertEquals(1, beforeAck.confirmedCursorPoint?.ordinal)
        assertEquals(3, beforeAck.previewPoint?.ordinal)
        val afterAck = resolveChartCursors(series.points, localSourcePtsUs = 130L, renderedSourcePtsUs = 130L)
        assertEquals(3, afterAck.confirmedCursorPoint?.ordinal)
        assertEquals(null, afterAck.previewPoint)
    }

    @Test fun `missing observation cuts corrupt coordinate and retains following point`() {
        val a = analysis(observations = listOf(ObservationKind.MEASURED, ObservationKind.MISSING, ObservationKind.MEASURED, ObservationKind.MEASURED))
        val series = (EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertEquals(listOf(1, 2), series.segments.map { it.points.size })
        assertEquals(2, series.segments.last().points.first().ordinal)
        assertTrue(series.gaps.any { it.reason == GapReason.MISSING_OBSERVATION })
    }

    @Test fun `position uses smoothed metric y and physical elapsed time`() {
        val result = EncoderTimeSeriesBuilder.build(analysis(), analysis().repetitions.single(), EncoderSeriesKind.POSITION)
        val series = (result as EncoderTimeSeriesResult.Available).series
        assertEquals(listOf(0L, 10L, 20L, 30L), series.points.map { it.elapsedPhysical })
        assertEquals(listOf(1.0, 1.1, 1.2, 1.3), series.points.map { it.value })
    }

    @Test fun `lost and missing rows split even when coordinates are present`() {
        val a = analysis(
            statuses = listOf(TrackingStatus.TRACKING, TrackingStatus.LOST, TrackingStatus.TRACKING, TrackingStatus.TRACKING),
            observations = listOf(ObservationKind.MEASURED, ObservationKind.MEASURED, ObservationKind.MEASURED, ObservationKind.MEASURED),
        )
        val rep = a.repetitions.single()
        val series = (EncoderTimeSeriesBuilder.build(a, rep, EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertEquals(2, series.segments.size)
        assertTrue(series.gaps.any { it.reason == GapReason.TRACKING_LOST })
    }

    @Test fun `unknown timing is unavailable and bad bounds do not throw`() {
        val unknown = analysis().copy(timingDecision = VideoTimingDecision(VideoTimingMode.UNKNOWN))
        val unknownResult = EncoderTimeSeriesBuilder.build(unknown, unknown.repetitions.single(), EncoderSeriesKind.VELOCITY)
        assertEquals(TimeSeriesUnavailableReason.UNKNOWN_TIMEBASE, (unknownResult as EncoderTimeSeriesResult.Unavailable).reason)
        val bad = unknown.repetitions.single().copy(startSample = 99)
        assertTrue(EncoderTimeSeriesBuilder.build(analysis(), bad, EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
        val badFrame = analysis().copy(samples = analysis().samples.mapIndexed { index, sample ->
            if (index == 1) sample.copy(frameIndex = -1) else sample
        })
        assertTrue(EncoderTimeSeriesBuilder.build(badFrame, badFrame.repetitions.single(), EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
        val badTime = analysis().copy(samples = analysis().samples.mapIndexed { index, sample ->
            if (index == 1) sample.copy(physicalTimeUs = -1L) else sample
        })
        assertTrue(EncoderTimeSeriesBuilder.build(badTime, badTime.repetitions.single(), EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
        val badPts = analysis().copy(samples = analysis().samples.mapIndexed { index, sample ->
            when (index) {
                1 -> sample.copy(sourcePtsUs = -1L)
                2 -> sample.copy(gapBeforeUs = -1L)
                else -> sample
            }
        })
        assertEquals(TimeSeriesUnavailableReason.MALFORMED_DATA, (EncoderTimeSeriesBuilder.build(badPts, badPts.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Unavailable).reason)
        val retrogradeOutsideSelected = analysis().copy(
            samples = analysis().samples.mapIndexed { index, sample -> if (index == 3) sample.copy(physicalTimeUs = 5L) else sample },
            repetitions = listOf(analysis().repetitions.single().copy(
                endSample = 2,
                concentricPhase = MovementPhase(MovementDirection.UP, 2, 2),
            )),
        )
        assertEquals(TimeSeriesUnavailableReason.PHYSICAL_TIME_UNAVAILABLE, (EncoderTimeSeriesBuilder.build(retrogradeOutsideSelected, retrogradeOutsideSelected.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Unavailable).reason)
        val overlap = analysis().repetitions.single().copy(
            eccentricPhase = MovementPhase(MovementDirection.DOWN, 0, 2),
            concentricPhase = MovementPhase(MovementDirection.UP, 2, 3),
        )
        assertTrue(EncoderTimeSeriesBuilder.build(analysis(), overlap, EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
        val wrongDirection = analysis().repetitions.single().copy(
            eccentricPhase = MovementPhase(MovementDirection.UP, 0, 1),
        )
        assertTrue(EncoderTimeSeriesBuilder.build(analysis(), wrongDirection, EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
    }

    @Test fun `empty position and velocity signals are unavailable`() {
        val positionMissing = analysis(position = List(4) { null })
        assertTrue(EncoderTimeSeriesBuilder.build(positionMissing, positionMissing.repetitions.single(), EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Unavailable)
        val velocityMissing = analysis(velocity = List(4) { null })
        assertTrue(EncoderTimeSeriesBuilder.build(velocityMissing, velocityMissing.repetitions.single(), EncoderSeriesKind.VELOCITY) is EncoderTimeSeriesResult.Unavailable)
    }

    @Test fun `uncertain samples remain visible in the available series`() {
        val uncertain = analysis(statuses = listOf(TrackingStatus.TRACKING, TrackingStatus.UNCERTAIN, TrackingStatus.TRACKING, TrackingStatus.TRACKING))
        val series = (EncoderTimeSeriesBuilder.build(uncertain, uncertain.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertEquals(TrackingStatus.UNCERTAIN, series.points.single { it.ordinal == 1 }.trackingStatus)
        assertEquals(RepetitionQuality.VALID, series.quality)
    }

    @Test fun `XY segmentation cuts corrupt lost rows and retains post-gap point`() {
        val a = analysis(statuses = listOf(TrackingStatus.TRACKING, TrackingStatus.LOST, TrackingStatus.TRACKING, TrackingStatus.TRACKING))
        val segments = encoderTrajectorySegments(a, a.repetitions.single())
        assertEquals(listOf(1, 2), segments.map { it.size })
        assertEquals(2.0, segments.last().first().x, 0.0)
    }

    @Test fun `all four exercises enforce their phase order`() {
        val base = analysis()
        listOf(EncoderExercise.BENCH_PRESS, EncoderExercise.SQUAT).forEach { exercise ->
            val a = base.copy(setup = base.setup.copy(exercise = exercise))
            assertTrue(EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Available)
        }
        listOf(EncoderExercise.DEADLIFT, EncoderExercise.PULL_UP).forEach { exercise ->
            val a = base.copy(
                setup = base.setup.copy(exercise = exercise),
                repetitions = listOf(base.repetitions.single().copy(
                    eccentricPhase = MovementPhase(MovementDirection.DOWN, 2, 3),
                    concentricPhase = MovementPhase(MovementDirection.UP, 0, 1),
                )),
            )
            assertTrue(EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) is EncoderTimeSeriesResult.Available)
        }
    }

    @Test fun `gap threshold is strictly greater than one point five times median`() {
        val a = analysis(pts = listOf(100L, 110L, 125L, 135L), physical = listOf(0L, 10L, 25L, 35L))
        val series = (EncoderTimeSeriesBuilder.build(a, a.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertTrue(series.gaps.none { it.reason == GapReason.PTS_GAP })
        val b = analysis(pts = listOf(100L, 110L, 126L, 136L), physical = listOf(0L, 10L, 26L, 36L))
        val bSeries = (EncoderTimeSeriesBuilder.build(b, b.repetitions.single(), EncoderSeriesKind.POSITION) as EncoderTimeSeriesResult.Available).series
        assertTrue(bSeries.gaps.any { it.reason == GapReason.PTS_GAP })
    }
}
