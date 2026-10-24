package com.openjump.app.encoder

import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.ImageRegion
import com.openjump.app.tracking.TrackingDiagnostics
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingResult
import com.openjump.app.tracking.TrackingSample
import com.openjump.app.tracking.TrackingStatus
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderAnalyzerTest {
    private val calibration = MetricCalibrator.create(
        0,
        ImagePoint(0.0, 0.0),
        ImagePoint(100.0, 0.0),
        0.5,
    )

    @Test
    fun `bench reps use eccentric then concentric and velocity loss ignores no valid data`() {
        val q = buildList {
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.50, 0.0, 0.50)
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.75, 0.0, 0.50)
            stationary(0.35, 0.50)
        }

        val analysis = EncoderAnalyzer.analyze(
            setup = EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
            timingDecision = VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration = calibration,
            tracking = tracking(q, fps = 120),
        )

        assertEquals(2, analysis.repetitions.size)
        assertEquals(MovementDirection.DOWN, analysis.repetitions[0].eccentricPhase.direction)
        assertEquals(MovementDirection.UP, analysis.repetitions[0].concentricPhase.direction)
        assertTrue(analysis.repetitions.all { it.quality == RepetitionQuality.VALID })
        val firstMcv = analysis.repetitions[0].metric(EncoderMetricKey.MCV)!!.value!!
        val secondMcv = analysis.repetitions[1].metric(EncoderMetricKey.MCV)!!.value!!
        assertTrue(firstMcv > secondMcv)
        assertEquals(0.0, analysis.repetitions[0].metric(EncoderMetricKey.VELOCITY_LOSS)!!.value!!, 1e-6)
        assertTrue(analysis.repetitions[1].metric(EncoderMetricKey.VELOCITY_LOSS)!!.value!! > 20.0)
        assertEquals(MetricValidity.VALID, analysis.repetitions[0].metric(EncoderMetricKey.PEAK_VELOCITY)!!.validity)
    }

    @Test
    fun `peak velocity validity follows physical sampling rate`() {
        val q = buildList {
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.50, 0.0, 0.50)
            stationary(0.35, 0.50)
        }

        val at60 = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 60.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(q, 60),
        )
        val at30 = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 60.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(q, 30),
        )

        assertEquals(MetricValidity.LOW_CONFIDENCE, at60.repetitions.single().metric(EncoderMetricKey.PEAK_VELOCITY)!!.validity)
        assertEquals(MetricValidity.INVALID, at30.repetitions.single().metric(EncoderMetricKey.PEAK_VELOCITY)!!.validity)
        assertNull(at30.repetitions.single().metric(EncoderMetricKey.PEAK_VELOCITY)!!.value)
    }

    @Test
    fun `uncertain repetition is excluded from set velocity loss`() {
        val q = buildList {
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.50, 0.0, 0.50)
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.60, 0.0, 0.50)
            stationary(0.35, 0.50)
        }
        val base = tracking(q, 120)
        val uncertain = base.mapIndexed { index, frame ->
            if (index < base.size / 2) frame else frame.copy(
                result = frame.result.copy(
                    sample = frame.result.sample.copy(confidence = 0.4, status = TrackingStatus.UNCERTAIN),
                ),
            )
        }

        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            uncertain,
        )

        assertEquals(2, analysis.repetitions.size)
        assertEquals(RepetitionQuality.VALID, analysis.repetitions.first().quality)
        assertEquals(RepetitionQuality.UNCERTAIN, analysis.repetitions.last().quality)
        assertNull(analysis.repetitions.last().metric(EncoderMetricKey.VELOCITY_LOSS)!!.value)
        assertNull(analysis.setVelocityLoss)
    }

    @Test
    fun `lost during final phase cannot produce a valid partial repetition`() {
        val q = buildList {
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.80, 0.0, 0.50)
        }
        val lostAt = 180
        val base = tracking(q, 120).take(lostAt + 1)
        val lost = base.mapIndexed { index, frame ->
            if (index != lostAt) frame else frame.copy(
                result = frame.result.copy(
                    sample = frame.result.sample.copy(point = null, confidence = 0.0, status = TrackingStatus.LOST),
                    region = null,
                    featurePoints = emptyList(),
                ),
            )
        }

        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            lost,
        )

        assertEquals(1, analysis.repetitions.size)
        assertEquals(RepetitionQuality.INVALID, analysis.repetitions.single().quality)
        assertTrue(QualityReason.TRACKING_LOST in analysis.repetitions.single().reasons)
    }

    @Test
    fun `rom outlier is uncertain and excluded after provisional quality`() {
        val q = buildList {
            stationary(0.35, 0.50)
            repeat(2) {
                motion(0.50, 0.50, 0.0)
                stationary(0.20, 0.0)
                motion(0.50, 0.0, 0.50)
                stationary(0.35, 0.50)
            }
            motion(0.40, 0.50, 0.20)
            stationary(0.20, 0.20)
            motion(0.40, 0.20, 0.50)
            stationary(0.35, 0.50)
        }

        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.SQUAT, 100.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(q, 120),
        )

        assertEquals(3, analysis.repetitions.size)
        assertEquals(RepetitionQuality.UNCERTAIN, analysis.repetitions.last().quality)
        assertTrue(QualityReason.ROM_OUTLIER in analysis.repetitions.last().reasons)
        assertEquals(2, analysis.validRepetitions.size)
    }

    @Test
    fun `removing repetition renumbers and recalculates derived quality and velocity loss`() {
        val q = buildList {
            stationary(0.35, 0.50)
            repeat(2) {
                motion(0.50, 0.50, 0.0)
                stationary(0.20, 0.0)
                motion(0.50, 0.0, 0.50)
                stationary(0.35, 0.50)
            }
            motion(0.40, 0.50, 0.20)
            stationary(0.20, 0.20)
            motion(0.40, 0.20, 0.50)
            stationary(0.35, 0.50)
        }
        val original = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.SQUAT, 100.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(q, 120),
        )
        val corrected = EncoderAnalyzer.removeRepetition(original, ordinal = 2)

        assertEquals(original.samples.size, corrected.samples.size)
        assertEquals(listOf(0, 1), corrected.repetitions.map { it.ordinal })
        assertTrue(corrected.repetitions.all { QualityReason.ROM_OUTLIER !in it.reasons })
        assertTrue(corrected.repetitions.all { it.metric(EncoderMetricKey.VELOCITY_LOSS) != null })
        assertEquals(corrected.repetitions.size, corrected.repetitions.sumOf {
            it.metrics.count { metric -> metric.key == EncoderMetricKey.VELOCITY_LOSS }
        })
        assertEquals(0.0, corrected.repetitions.first().metric(EncoderMetricKey.VELOCITY_LOSS)!!.value!!, 1e-6)
        assertTrue(corrected.repetitions.all { it.quality == RepetitionQuality.VALID })
        assertTrue(corrected.warnings.isEmpty())
        assertEquals(original.repetitions[0].startSample, corrected.repetitions[0].startSample)
        assertEquals(original.repetitions[1].endSample, corrected.repetitions[1].endSample)

        val one = EncoderAnalyzer.removeRepetition(corrected, ordinal = 0)
        assertEquals(listOf(0), one.repetitions.map { it.ordinal })
        assertEquals(0.0, one.repetitions.single().metric(EncoderMetricKey.VELOCITY_LOSS)!!.value!!, 1e-6)
        val empty = EncoderAnalyzer.removeRepetition(one, ordinal = 0)
        assertTrue(empty.repetitions.isEmpty())
        assertEquals(listOf(EncoderAnalysisWarnings.NO_REPETITIONS), empty.warnings)
    }

    @Test
    fun `removing nonexistent repetition ordinal is rejected`() {
        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(listOf(0.0, 0.5, 0.0, 0.5), 120),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EncoderAnalyzer.removeRepetition(analysis, ordinal = 42)
        }
    }

    @Test
    fun `severe pts gap invalidates affected repetition without interpolation`() {
        val q = buildList {
            stationary(0.35, 0.50)
            motion(0.50, 0.50, 0.0)
            stationary(0.20, 0.0)
            motion(0.70, 0.0, 0.50)
            stationary(0.35, 0.50)
        }
        val gapAt = 150
        val withGap = tracking(q, 120).mapIndexed { index, frame ->
            if (index < gapAt) frame else frame.copy(
                result = frame.result.copy(
                    sample = frame.result.sample.copy(timestampUs = frame.result.sample.timestampUs + 200_000L),
                ),
            )
        }

        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.BENCH_PRESS, 80.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            withGap,
        )

        assertEquals(1, analysis.repetitions.size)
        assertEquals(RepetitionQuality.INVALID, analysis.repetitions.single().quality)
        assertTrue(QualityReason.PTS_GAP in analysis.repetitions.single().reasons)
        assertTrue(analysis.samples.none { it.observationKind.name == "INTERPOLATED" })
    }

    @Test
    fun `deadlift pairs concentric before eccentric`() {
        val q = buildList {
            stationary(0.35, 0.0)
            motion(0.60, 0.0, 0.55)
            stationary(0.25, 0.55)
            motion(0.65, 0.55, 0.0)
            stationary(0.35, 0.0)
        }

        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.DEADLIFT, 120.0),
            VideoTimingDecision(VideoTimingMode.REAL_TIME),
            calibration,
            tracking(q, 120),
        )

        assertEquals(1, analysis.repetitions.size)
        val rep = analysis.repetitions.single()
        assertTrue(rep.concentricPhase.startSample < rep.eccentricPhase.startSample)
        assertTrue(rep.metric(EncoderMetricKey.MEAN_ECCENTRIC_VELOCITY)!!.value!! < 0.0)
    }

    @Test
    fun `warnings can be reconstructed from persisted domain data`() {
        val samples = listOf(
            EncoderSample(
                ordinal = 0,
                frameIndex = 0,
                sourcePtsUs = 0L,
                physicalTimeUs = null,
                rawPixelPoint = null,
                rawMetricPoint = null,
                smoothedMetricPoint = null,
                velocityXMps = null,
                velocityYMps = null,
                confidence = 0.0,
                trackingStatus = TrackingStatus.LOST,
                observationKind = ObservationKind.MISSING,
                fitResidualM = null,
                gapBeforeUs = null,
            ),
        )
        val warnings = EncoderAnalysisWarnings.derive(
            VideoTimingDecision(VideoTimingMode.UNKNOWN),
            samples,
            emptyList(),
        )
        assertEquals(
            listOf(
                EncoderAnalysisWarnings.UNRELIABLE_TIMELINE,
                EncoderAnalysisWarnings.TRACKING_LOST,
            ),
            warnings,
        )
    }

    @Test
    fun `no valid repetitions warning is reconstructed without blocking persistence`() {
        val repetition = EncoderRepetition(
            ordinal = 0,
            startSample = 0,
            endSample = 1,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, 0, 0),
            concentricPhase = MovementPhase(MovementDirection.UP, 1, 1),
            quality = RepetitionQuality.UNCERTAIN,
            reasons = setOf(QualityReason.LOW_CONFIDENCE),
            metrics = emptyList(),
        )
        assertEquals(
            listOf(EncoderAnalysisWarnings.NO_VALID_REPETITIONS),
            EncoderAnalysisWarnings.derive(
                VideoTimingDecision(VideoTimingMode.REAL_TIME),
                emptyList(),
                listOf(repetition),
            ),
        )
    }

    @Test
    fun `unknown chronology keeps calibrated path but publishes no kinematics`() {
        val q = buildList { motion(0.5, 0.0, 0.5) }
        val analysis = EncoderAnalyzer.analyze(
            EncoderSetup(EncoderExercise.PULL_UP, 0.0),
            VideoTimingDecision(VideoTimingMode.UNKNOWN),
            calibration,
            tracking(q, 30),
        )

        assertTrue(analysis.samples.any { it.rawMetricPoint != null })
        assertTrue(analysis.samples.all { it.physicalTimeUs == null && it.velocityYMps == null })
        assertTrue(analysis.repetitions.isEmpty())
        assertNull(analysis.effectiveFps)
        assertFalse(analysis.warnings.isEmpty())
    }

    private fun MutableList<Double>.stationary(seconds: Double, q: Double) {
        repeat((seconds * 120).toInt()) { add(q) }
    }

    private fun MutableList<Double>.motion(seconds: Double, from: Double, to: Double) {
        val count = (seconds * 120).toInt()
        repeat(count) { index ->
            val progress = (index + 1.0) / count
            val eased = (1.0 - cos(Math.PI * progress)) / 2.0
            add(from + (to - from) * eased)
        }
    }

    private fun tracking(q: List<Double>, fps: Int): List<TrackingFrameResult> {
        val dt = 1_000_000L / fps
        return q.mapIndexed { index, verticalMeters ->
            val point = ImagePoint(500.0, 500.0 - verticalMeters / calibration.metersPerPixel)
            TrackingFrameResult(
                frameIndex = index,
                result = TrackingResult(
                    sample = TrackingSample(index * dt, point, 1.0, TrackingStatus.TRACKING),
                    region = ImageRegion(point, 20.0, 20.0),
                    featurePoints = listOf(point),
                    diagnostics = TrackingDiagnostics(10, 10, 10, 0.1, 0.8),
                ),
            )
        }
    }
}
