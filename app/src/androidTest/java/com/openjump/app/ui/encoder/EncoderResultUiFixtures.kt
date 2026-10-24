package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderAnalysisConfig
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.EncoderAnalysisWarnings
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus

/** Deterministic presentation data only; these tests never create a repository, session, or media item. */
internal fun encoderResultUiFixture(
    repetitionCount: Int = 3,
    invalidMcvAt: Set<Int> = emptySet(),
    invalidQualityAt: Set<Int> = emptySet(),
    lowConfidencePvAt: Set<Int> = emptySet(),
    uncertainAt: Set<Int> = emptySet(),
    allInvalid: Boolean = false,
    tieUncertainWithBest: Boolean = false,
    unknownTimebase: Boolean = false,
    trackingLost: Boolean = false,
): EncoderAnalysis {
    val repetitions = (0 until repetitionCount).map { ordinal ->
        val firstSample = ordinal * 4
        val quality = when {
            allInvalid || ordinal in invalidQualityAt -> RepetitionQuality.INVALID
            ordinal in uncertainAt -> RepetitionQuality.UNCERTAIN
            else -> RepetitionQuality.VALID
        }
        val ordinaryMcv = if (tieUncertainWithBest) {
            when (ordinal) {
                0, 1 -> 0.9
                else -> 0.55 + ordinal * 0.001
            }
        } else 0.55 + ordinal * 0.01
        val mcv = if (ordinal in invalidMcvAt) 9_876.54321 else ordinaryMcv
        val metrics = listOf(
            EncoderMetric(
                EncoderMetricKey.MCV,
                mcv,
                EncoderMetricUnit.METER_PER_SECOND,
                if (allInvalid || ordinal in invalidMcvAt) MetricValidity.INVALID else MetricValidity.VALID,
                if (allInvalid || ordinal in invalidMcvAt) QualityReason.INCOMPLETE_PHASE else null,
            ),
            EncoderMetric(
                EncoderMetricKey.PEAK_VELOCITY,
                1.05 + ordinal * 0.01,
                EncoderMetricUnit.METER_PER_SECOND,
                when {
                    allInvalid -> MetricValidity.INVALID
                    ordinal in lowConfidencePvAt -> MetricValidity.LOW_CONFIDENCE
                    else -> MetricValidity.VALID
                },
            ),
            EncoderMetric(EncoderMetricKey.ROM, 0.4 + ordinal * 0.001, EncoderMetricUnit.METER, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
            EncoderMetric(EncoderMetricKey.CONCENTRIC_TIME, 0.7, EncoderMetricUnit.SECOND, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
            EncoderMetric(EncoderMetricKey.ECCENTRIC_TIME, 0.8, EncoderMetricUnit.SECOND, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
            EncoderMetric(EncoderMetricKey.MEAN_ECCENTRIC_VELOCITY, 0.4, EncoderMetricUnit.METER_PER_SECOND, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
            EncoderMetric(EncoderMetricKey.PAUSE_TIME, 0.1, EncoderMetricUnit.SECOND, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
            EncoderMetric(EncoderMetricKey.VELOCITY_LOSS, 5.0, EncoderMetricUnit.PERCENT, if (allInvalid) MetricValidity.INVALID else MetricValidity.VALID),
        )
        EncoderRepetition(
            ordinal = ordinal,
            startSample = firstSample,
            endSample = firstSample + 3,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, firstSample, firstSample + 1),
            concentricPhase = MovementPhase(MovementDirection.UP, firstSample + 2, firstSample + 3),
            quality = quality,
            reasons = if (quality == RepetitionQuality.VALID) emptySet() else setOf(QualityReason.LOW_CONFIDENCE),
            metrics = metrics,
        )
    }
    val sampleCount = repetitionCount * 4
    val samples = (0 until sampleCount).map { index ->
        val phase = index % 4
        EncoderSample(
            ordinal = index,
            frameIndex = index,
            sourcePtsUs = 100_000L + index * 50_000L,
            physicalTimeUs = if (unknownTimebase) null else index * 50_000L,
            rawPixelPoint = ImagePoint(10.0 + index, 20.0 + phase),
            rawMetricPoint = MetricPoint(index * 0.01, phase * 0.1),
            smoothedMetricPoint = MetricPoint(index * 0.01, phase * 0.1),
            velocityXMps = 0.2,
            velocityYMps = if (phase == 1) -0.3 else 0.5,
            confidence = 0.95,
            // A derived LOST warning is only truthful when a LOST sample exists.
            trackingStatus = if (trackingLost && index == sampleCount - 1) TrackingStatus.LOST else TrackingStatus.TRACKING,
            observationKind = ObservationKind.MEASURED,
            fitResidualM = 0.001,
            gapBeforeUs = null,
        )
    }
    return EncoderAnalysis(
        setup = EncoderSetup(EncoderExercise.BENCH_PRESS, 40.0),
        timingDecision = VideoTimingDecision(if (unknownTimebase) VideoTimingMode.UNKNOWN else VideoTimingMode.REAL_TIME),
        calibration = MetricCalibration(0, ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.45, 0.0045),
        samples = samples,
        repetitions = repetitions,
        effectiveFps = if (unknownTimebase) null else 60.0,
        smoothingWindowSamples = 5,
        velocityEnterMps = 0.08,
        velocityExitMps = 0.04,
        warnings = buildList {
            if (unknownTimebase) add(EncoderAnalysisWarnings.UNRELIABLE_TIMELINE)
            if (trackingLost) add(EncoderAnalysisWarnings.TRACKING_LOST)
        },
        config = EncoderAnalysisConfig(),
    )
}
