package com.openjump.app.encoder

import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object EncoderAnalyzer {
    /**
     * Removes one detected repetition from an active analysis without touching the source samples.
     * Quality and set metrics are derived again because removing a repetition can change the ROM
     * baseline and the velocity-loss reference.
     */
    fun removeRepetition(analysis: EncoderAnalysis, ordinal: Int): EncoderAnalysis {
        require(analysis.repetitions.any { it.ordinal == ordinal }) {
            "No existe la repetición $ordinal."
        }
        val retained = analysis.repetitions
            .filterNot { it.ordinal == ordinal }
            .mapIndexed { index, repetition ->
                val reasons = repetition.reasons - QualityReason.ROM_OUTLIER
                repetition.copy(
                    ordinal = index,
                    quality = if (repetition.quality == RepetitionQuality.UNCERTAIN && reasons.isEmpty()) {
                        RepetitionQuality.VALID
                    } else {
                        repetition.quality
                    },
                    reasons = reasons,
                    metrics = repetition.metrics.filterNot { it.key == EncoderMetricKey.VELOCITY_LOSS },
                )
            }
        val recalculated = applyRomQuality(retained, analysis.config)
        val withVelocityLoss = applyVelocityLoss(recalculated)
        return analysis.copy(
            repetitions = withVelocityLoss,
            warnings = EncoderAnalysisWarnings.derive(analysis.timingDecision, analysis.samples, withVelocityLoss),
        )
    }

    fun analyze(
        setup: EncoderSetup,
        timingDecision: VideoTimingDecision,
        calibration: MetricCalibration,
        tracking: List<TrackingFrameResult>,
        config: EncoderAnalysisConfig = EncoderAnalysisConfig(),
    ): EncoderAnalysis {
        require(tracking.isNotEmpty()) { "El tracking no contiene muestras." }
        val ordered = tracking.sortedBy { it.frameIndex }
        val pts = ordered.map { it.result.sample.timestampUs }
        val timeline = PhysicalTimeline.resolve(pts, timingDecision)
        val origin = ordered.firstNotNullOfOrNull { it.result.sample.point }
            ?: throw IllegalArgumentException("El tracking no contiene posiciones válidas.")
        val rawMetric = ordered.map { frame ->
            frame.result.sample.point?.let { MetricCalibrator.transform(it, origin, calibration) }
        }
        val window = LocalPolynomialKinematics.windowForFps(timeline.effectiveFps)
        val medianDelta = timeline.medianDeltaUs
        var block = 0
        val validOrdinals = mutableListOf<Int>()
        val observations = mutableListOf<LocalPolynomialKinematics.Observation>()
        ordered.indices.forEach { ordinal ->
            val time = timeline.timesUs[ordinal]
            val point = rawMetric[ordinal]
            if (ordinal > 0 && medianDelta != null) {
                val gap = timeline.gapsBeforeUs[ordinal]
                if (gap != null && gap * 2L > 3L * medianDelta) block += 1
            }
            if (time != null && point != null) {
                validOrdinals += ordinal
                observations += LocalPolynomialKinematics.Observation(
                    timeUs = time,
                    point = point,
                    confidence = ordered[ordinal].result.sample.confidence,
                    block = block,
                )
            }
        }
        val estimates = LocalPolynomialKinematics.estimateAll(observations, window)
        val estimateByOrdinal = validOrdinals.zip(estimates).toMap()
        val samples = ordered.mapIndexed { ordinal, frame ->
            val trackingSample = frame.result.sample
            val estimate = estimateByOrdinal[ordinal]
            EncoderSample(
                ordinal = ordinal,
                frameIndex = frame.frameIndex,
                sourcePtsUs = trackingSample.timestampUs,
                physicalTimeUs = timeline.timesUs[ordinal],
                rawPixelPoint = trackingSample.point,
                rawMetricPoint = rawMetric[ordinal],
                smoothedMetricPoint = estimate?.point,
                velocityXMps = estimate?.velocityX,
                velocityYMps = estimate?.velocityY,
                confidence = trackingSample.confidence,
                trackingStatus = trackingSample.status,
                observationKind = if (trackingSample.point == null) ObservationKind.MISSING else ObservationKind.MEASURED,
                fitResidualM = estimate?.residualM,
                gapBeforeUs = timeline.gapsBeforeUs[ordinal],
            )
        }
        if (!timingDecision.isReliable) {
            return EncoderAnalysis(
                setup = setup,
                timingDecision = timingDecision,
                calibration = calibration,
                samples = samples,
                repetitions = emptyList(),
                effectiveFps = null,
                smoothingWindowSamples = 0,
                velocityEnterMps = null,
                velocityExitMps = null,
                warnings = EncoderAnalysisWarnings.derive(timingDecision, samples, emptyList()),
                config = config,
            )
        }

        val segmentation = RepetitionSegmenter.segment(setup.exercise, samples, config)
        var repetitions = segmentation.repetitions.mapIndexed { index, (eccentric, concentric) ->
            buildRepetition(index, eccentric, concentric, samples, timeline.medianDeltaUs, config)
        }
        repetitions = applyRomQuality(repetitions, config)
        repetitions = applyVelocityLoss(repetitions)
        val warnings = EncoderAnalysisWarnings.derive(timingDecision, samples, repetitions)
        return EncoderAnalysis(
            setup = setup,
            timingDecision = timingDecision,
            calibration = calibration,
            samples = samples,
            repetitions = repetitions,
            effectiveFps = timeline.effectiveFps,
            smoothingWindowSamples = window,
            velocityEnterMps = segmentation.velocityEnterMps,
            velocityExitMps = segmentation.velocityExitMps,
            warnings = warnings,
            config = config,
        )
    }

    private fun buildRepetition(
        ordinal: Int,
        eccentric: MovementPhase,
        concentric: MovementPhase,
        samples: List<EncoderSample>,
        medianDeltaUs: Long?,
        config: EncoderAnalysisConfig,
    ): EncoderRepetition {
        val start = min(eccentric.startSample, concentric.startSample)
        val end = max(eccentric.endSample, concentric.endSample)
        val reasons = linkedSetOf<QualityReason>()
        val slice = samples.subList(start, end + 1)
        if (slice.any { it.trackingStatus == TrackingStatus.LOST || it.rawMetricPoint == null } ||
            samples.getOrNull(end + 1)?.trackingStatus == TrackingStatus.LOST) {
            reasons += QualityReason.TRACKING_LOST
        }
        val severeGap = medianDeltaUs?.let { median -> max(3L * median, 100_000L) }
        val moderateGap = medianDeltaUs?.let { (it * 3L) / 2L }
        val transitionStart = max(eccentric.startSample, concentric.startSample)
        val transitionEnd = min(eccentric.endSample, concentric.endSample)
        slice.forEach { sample ->
            val gap = sample.gapBeforeUs ?: return@forEach
            if (severeGap != null && gap > severeGap) reasons += QualityReason.PTS_GAP
            else if (moderateGap != null && gap > moderateGap) reasons += QualityReason.PTS_GAP
            if (sample.ordinal in (transitionEnd - 1)..(transitionStart + 1) && moderateGap != null && gap > moderateGap) {
                reasons += QualityReason.TURNAROUND_GAP
            }
        }
        val uncertainFraction = slice.count { it.trackingStatus == TrackingStatus.UNCERTAIN }.toDouble() / slice.size
        if (uncertainFraction > config.maximumUncertainFraction ||
            slice.any { it.trackingStatus == TrackingStatus.UNCERTAIN &&
                abs(it.ordinal - transitionStart) <= 1 }) {
            reasons += QualityReason.UNCERTAIN_TRACKING
        }
        if (median(slice.map { it.confidence }) < config.minimumMedianConfidence) reasons += QualityReason.LOW_CONFIDENCE
        val residuals = slice.mapNotNull { it.fitResidualM }
        if (residuals.isNotEmpty()) {
            val threshold = max(0.01, 6.0 * median(residuals))
            if (residuals.any { it > threshold }) reasons += QualityReason.DISCONTINUITY
        }
        val severe = QualityReason.TRACKING_LOST in reasons || QualityReason.TURNAROUND_GAP in reasons ||
            (severeGap != null && slice.any { (it.gapBeforeUs ?: 0L) > severeGap })
        val quality = when {
            severe -> RepetitionQuality.INVALID
            reasons.isNotEmpty() -> RepetitionQuality.UNCERTAIN
            else -> RepetitionQuality.VALID
        }
        val metricValidity = if (quality == RepetitionQuality.INVALID) MetricValidity.INVALID else MetricValidity.VALID
        val reason = reasons.firstOrNull()
        val concentricTime = durationSeconds(concentric, samples)
        val eccentricTime = durationSeconds(eccentric, samples)
        val concentricStart = samples[concentric.startSample].verticalPositionM
        val concentricEnd = samples[concentric.endSample].verticalPositionM
        val eccentricStart = samples[eccentric.startSample].verticalPositionM
        val eccentricEnd = samples[eccentric.endSample].verticalPositionM
        val mcv = if (concentricTime > 0.0 && concentricStart != null && concentricEnd != null) {
            (concentricEnd - concentricStart) / concentricTime
        } else null
        val mev = if (eccentricTime > 0.0 && eccentricStart != null && eccentricEnd != null) {
            (eccentricEnd - eccentricStart) / eccentricTime
        } else null
        val positions = slice.mapNotNull { it.verticalPositionM }
        val rom = if (positions.isNotEmpty()) positions.max() - positions.min() else null
        val peak = samples.subList(concentric.startSample, concentric.endSample + 1)
            .mapNotNull { it.verticalVelocityMps }.maxOrNull()
        val fps = effectiveFps(samples)
        val peakValidity = when {
            quality == RepetitionQuality.INVALID || peak == null -> MetricValidity.INVALID
            fps != null && fps >= 119.5 -> MetricValidity.VALID
            fps != null && fps >= 59.5 -> MetricValidity.LOW_CONFIDENCE
            else -> MetricValidity.INVALID
        }
        val firstPhase = if (eccentric.startSample < concentric.startSample) eccentric else concentric
        val secondPhase = if (firstPhase === eccentric) concentric else eccentric
        val pauseSeconds = pauseSeconds(firstPhase, secondPhase, samples, medianDeltaUs)
        val metrics = buildList {
            add(metric(EncoderMetricKey.MCV, mcv, EncoderMetricUnit.METER_PER_SECOND, metricValidity, reason))
            add(metric(
                EncoderMetricKey.PEAK_VELOCITY,
                if (peakValidity == MetricValidity.INVALID) null else peak,
                EncoderMetricUnit.METER_PER_SECOND,
                peakValidity,
                if (peakValidity == MetricValidity.INVALID) {
                    reason ?: QualityReason.INSUFFICIENT_SAMPLING_RATE
                } else null,
            ))
            add(metric(EncoderMetricKey.ROM, rom, EncoderMetricUnit.METER, if (rom == null) MetricValidity.INVALID else metricValidity, reason))
            add(metric(EncoderMetricKey.CONCENTRIC_TIME, concentricTime.takeIf { it > 0.0 }, EncoderMetricUnit.SECOND, metricValidity, reason))
            add(metric(EncoderMetricKey.ECCENTRIC_TIME, eccentricTime.takeIf { it > 0.0 }, EncoderMetricUnit.SECOND, metricValidity, reason))
            add(metric(EncoderMetricKey.MEAN_ECCENTRIC_VELOCITY, mev, EncoderMetricUnit.METER_PER_SECOND, metricValidity, reason))
            if (pauseSeconds * 1_000_000.0 >= config.pauseDurationUs) {
                add(metric(EncoderMetricKey.PAUSE_TIME, pauseSeconds, EncoderMetricUnit.SECOND, metricValidity, reason))
            }
        }
        return EncoderRepetition(
            ordinal = ordinal,
            startSample = start,
            endSample = end,
            eccentricPhase = eccentric,
            concentricPhase = concentric,
            quality = quality,
            reasons = reasons,
            metrics = metrics,
        )
    }

    private fun applyRomQuality(
        repetitions: List<EncoderRepetition>,
        config: EncoderAnalysisConfig,
    ): List<EncoderRepetition> {
        val provisional = repetitions.filter { it.quality == RepetitionQuality.VALID }
        if (provisional.size < 3) return repetitions
        val medianRom = median(provisional.mapNotNull { it.metric(EncoderMetricKey.ROM)?.value })
        if (medianRom <= 0.0) return repetitions
        return repetitions.map { repetition ->
            val rom = repetition.metric(EncoderMetricKey.ROM)?.value
            if (repetition.quality == RepetitionQuality.VALID && rom != null && abs(rom - medianRom) / medianRom > config.romOutlierFraction) {
                repetition.copy(
                    quality = RepetitionQuality.UNCERTAIN,
                    reasons = repetition.reasons + QualityReason.ROM_OUTLIER,
                )
            } else repetition
        }
    }

    private fun applyVelocityLoss(repetitions: List<EncoderRepetition>): List<EncoderRepetition> {
        var best: Double? = null
        return repetitions.map { repetition ->
            val mcv = repetition.metric(EncoderMetricKey.MCV)?.value
            val eligible = repetition.quality == RepetitionQuality.VALID && mcv != null && mcv > 0.0
            val velocityLoss = if (eligible) {
                best = max(best ?: mcv, mcv)
                100.0 * ((best ?: mcv) - mcv) / (best ?: mcv)
            } else null
            repetition.copy(
                metrics = repetition.metrics + EncoderMetric(
                    key = EncoderMetricKey.VELOCITY_LOSS,
                    value = velocityLoss,
                    unit = EncoderMetricUnit.PERCENT,
                    validity = if (velocityLoss == null) MetricValidity.INVALID else MetricValidity.VALID,
                    reason = if (velocityLoss == null) repetition.reasons.firstOrNull() else null,
                ),
            )
        }
    }

    private fun metric(
        key: EncoderMetricKey,
        value: Double?,
        unit: EncoderMetricUnit,
        desiredValidity: MetricValidity,
        reason: QualityReason?,
    ): EncoderMetric {
        val validity = if (value == null) MetricValidity.INVALID else desiredValidity
        return EncoderMetric(key, if (validity == MetricValidity.INVALID) null else value, unit, validity, reason)
    }

    private fun durationSeconds(phase: MovementPhase, samples: List<EncoderSample>): Double {
        val start = samples[phase.startSample].physicalTimeUs ?: return 0.0
        val end = samples[phase.endSample].physicalTimeUs ?: return 0.0
        return (end - start) / 1_000_000.0
    }

    private fun pauseSeconds(
        first: MovementPhase,
        second: MovementPhase,
        samples: List<EncoderSample>,
        medianDeltaUs: Long?,
    ): Double {
        val firstEnd = samples[first.endSample].physicalTimeUs ?: return 0.0
        val secondStart = samples[second.startSample].physicalTimeUs ?: return 0.0
        return ((secondStart - firstEnd - (medianDeltaUs ?: 0L)).coerceAtLeast(0L)) / 1_000_000.0
    }

    private fun effectiveFps(samples: List<EncoderSample>): Double? {
        val times = samples.mapNotNull { it.physicalTimeUs }
        if (times.size < 2) return null
        val medianDelta = medianLong(times.zipWithNext { a, b -> b - a }) ?: return null
        return if (medianDelta > 0L) 1_000_000.0 / medianDelta else null
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
    }
}
