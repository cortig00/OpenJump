package com.openjump.app.encoder

import com.openjump.app.tracking.TrackingStatus
import kotlin.math.abs

/** The two signals exposed by the Encoder result charts. */
enum class EncoderSeriesKind { POSITION, VELOCITY }

enum class GapReason {
    MISSING_OBSERVATION,
    TRACKING_LOST,
    PTS_GAP,
    NON_CONTIGUOUS_FRAME,
    MISSING_VALUE,
}

enum class PhaseKind { ECCENTRIC, CONCENTRIC, NONE }

data class TimePoint(
    val ordinal: Int,
    val frame: Int,
    val sourcePts: Long,
    val elapsedPhysical: Long,
    val value: Double,
    val trackingStatus: TrackingStatus,
    val phase: PhaseKind,
) {
    val frameIndex: Int get() = frame
    val sourcePtsUs: Long get() = sourcePts
    val elapsedPhysicalUs: Long get() = elapsedPhysical
}

data class TimeSegment(val points: List<TimePoint>) {
    init { require(points.isNotEmpty()) }
}

data class TimeGap(
    val fromOrdinal: Int,
    val toOrdinal: Int,
    val reason: GapReason,
)

data class PhaseBand(
    val phase: PhaseKind,
    val startElapsedPhysical: Long,
    val endElapsedPhysical: Long,
    val label: String,
    val pattern: String,
)

data class TimeScaleLabels(val startUs: Long, val endUs: Long)

fun timeScaleLabels(points: List<TimePoint>): TimeScaleLabels = TimeScaleLabels(
    startUs = points.firstOrNull()?.elapsedPhysical ?: 0L,
    endUs = points.lastOrNull()?.elapsedPhysical ?: 0L,
)

data class RepetitionTimeSeries(
    val repetitionOrdinal: Int,
    val kind: EncoderSeriesKind,
    val segments: List<TimeSegment>,
    val gaps: List<TimeGap>,
    val phaseBands: List<PhaseBand>,
    val quality: RepetitionQuality,
) {
    val points: List<TimePoint> get() = segments.flatMap { it.points }
}

/** A malformed historical row must make the chart unavailable, never crash the result screen. */
enum class TimeSeriesUnavailableReason {
    UNKNOWN_TIMEBASE,
    PHYSICAL_TIME_UNAVAILABLE,
    MALFORMED_DATA,
    NO_POINTS,
}

sealed interface EncoderTimeSeriesResult {
    data class Available(val series: RepetitionTimeSeries) : EncoderTimeSeriesResult
    data class Unavailable(
        val reason: TimeSeriesUnavailableReason,
        val detail: String? = null,
    ) : EncoderTimeSeriesResult
}

private class TimeSeriesBuildException(
    val unavailableReason: TimeSeriesUnavailableReason,
    message: String,
) : IllegalArgumentException(message)

object EncoderTimeSeriesBuilder {
    fun build(
        analysis: EncoderAnalysis,
        repetition: EncoderRepetition,
        kind: EncoderSeriesKind,
    ): EncoderTimeSeriesResult = runCatching {
        buildChecked(analysis, repetition, kind)
    }.fold(
        onSuccess = { EncoderTimeSeriesResult.Available(it) },
        onFailure = { error ->
            val reason = (error as? TimeSeriesBuildException)?.unavailableReason
                ?: TimeSeriesUnavailableReason.MALFORMED_DATA
            EncoderTimeSeriesResult.Unavailable(reason, error.message)
        },
    )

    private fun buildChecked(
        analysis: EncoderAnalysis,
        repetition: EncoderRepetition,
        kind: EncoderSeriesKind,
    ): RepetitionTimeSeries {
        val samples = analysis.samples
        if (samples.isEmpty()) throw TimeSeriesBuildException(TimeSeriesUnavailableReason.NO_POINTS, "No Encoder samples")
        if (samples.indices.any { samples[it].ordinal != it }) {
            throw IllegalArgumentException("Sample ordinals are not contiguous")
        }
        if (samples.any { it.frameIndex < 0 || it.sourcePtsUs < 0L || (it.gapBeforeUs != null && it.gapBeforeUs < 0L) } ||
            samples.zipWithNext().any { (a, b) ->
                a.sourcePtsUs >= b.sourcePtsUs || a.frameIndex >= b.frameIndex
            }) {
            throw IllegalArgumentException("Sample PTS, gaps or frames are invalid")
        }
        if (samples.any { it.physicalTimeUs != null && it.physicalTimeUs < 0L }) {
            throw TimeSeriesBuildException(TimeSeriesUnavailableReason.MALFORMED_DATA, "Physical time is invalid")
        }
        if (!analysis.timingDecision.isReliable) {
            throw TimeSeriesBuildException(TimeSeriesUnavailableReason.UNKNOWN_TIMEBASE, "Physical time is unavailable")
        }
        val knownPhysicalTimes = samples.mapNotNull { it.physicalTimeUs }
        if (knownPhysicalTimes.zipWithNext().any { (a, b) -> a >= b }) {
            throw TimeSeriesBuildException(TimeSeriesUnavailableReason.PHYSICAL_TIME_UNAVAILABLE, "Physical time is not strictly increasing")
        }
        validateRepetition(analysis.setup.exercise, repetition, samples.size)
        val selected = samples.subList(repetition.startSample, repetition.endSample + 1)
        if (selected.any { it.physicalTimeUs == null }) {
            throw TimeSeriesBuildException(TimeSeriesUnavailableReason.PHYSICAL_TIME_UNAVAILABLE, "Physical time is unavailable")
        }
        if (selected.zipWithNext().any { (a, b) -> (a.physicalTimeUs ?: 0L) >= (b.physicalTimeUs ?: 0L) }) {
            throw TimeSeriesBuildException(TimeSeriesUnavailableReason.PHYSICAL_TIME_UNAVAILABLE, "Physical time is not strictly increasing")
        }

        val cadenceDeltas = selected.zipWithNext().map { (previous, current) ->
            current.gapBeforeUs ?: ((current.physicalTimeUs!! - previous.physicalTimeUs!!).coerceAtLeast(0L))
        }
        val cadenceMedian = median(cadenceDeltas)
        val threshold = cadenceMedian?.times(1.5) ?: Double.POSITIVE_INFINITY
        val firstPhysical = selected.first().physicalTimeUs!!
        val gaps = mutableListOf<TimeGap>()
        val segments = mutableListOf<TimeSegment>()
        var current = mutableListOf<TimePoint>()

        fun close() {
            if (current.isNotEmpty()) segments += TimeSegment(current.toList())
            current = mutableListOf()
        }
        selected.forEachIndexed { index, sample ->
            val phase = phaseOf(sample.ordinal, repetition)
            val value = when (kind) {
                EncoderSeriesKind.POSITION -> sample.smoothedMetricPoint?.y
                EncoderSeriesKind.VELOCITY -> sample.velocityYMps
            }
            val previousValue = selected.getOrNull(index - 1)?.let {
                when (kind) {
                    EncoderSeriesKind.POSITION -> it.smoothedMetricPoint?.y
                    EncoderSeriesKind.VELOCITY -> it.velocityYMps
                }
            }
            val reason = if (index > 0) gapReason(
                selected[index - 1], sample, previousValue, value,
                sample.gapBeforeUs ?: (sample.physicalTimeUs!! - selected[index - 1].physicalTimeUs!!),
                threshold,
            ) else null
            if (reason != null) {
                gaps += TimeGap(selected[index - 1].ordinal, sample.ordinal, reason)
                close()
            }
            if (sample.trackingStatus != TrackingStatus.LOST &&
                sample.observationKind != ObservationKind.MISSING && value != null
            ) {
                current += TimePoint(
                    ordinal = sample.ordinal,
                    frame = sample.frameIndex,
                    sourcePts = sample.sourcePtsUs,
                    elapsedPhysical = sample.physicalTimeUs!! - firstPhysical,
                    value = value,
                    trackingStatus = sample.trackingStatus,
                    phase = phase,
                )
            } else if (reason == null && index > 0) {
                // A bad row cuts even when it happens to contain a corrupt coordinate.
                val missingReason = when {
                    sample.trackingStatus == TrackingStatus.LOST -> GapReason.TRACKING_LOST
                    sample.observationKind == ObservationKind.MISSING -> GapReason.MISSING_OBSERVATION
                    else -> GapReason.MISSING_VALUE
                }
                gaps += TimeGap(selected[index - 1].ordinal, sample.ordinal, missingReason)
                close()
            }
        }
        close()
        if (segments.isEmpty()) throw TimeSeriesBuildException(TimeSeriesUnavailableReason.NO_POINTS, "No values for Encoder time series")
        val bands = listOfNotNull(
            phaseBand(repetition.eccentricPhase, selected, firstPhysical, PhaseKind.ECCENTRIC, "eccentric", "////"),
            phaseBand(repetition.concentricPhase, selected, firstPhysical, PhaseKind.CONCENTRIC, "concentric", "\\\\\\\\"),
        ).sortedBy { it.startElapsedPhysical }
        return RepetitionTimeSeries(
            repetitionOrdinal = repetition.ordinal,
            kind = kind,
            segments = segments,
            gaps = gaps,
            phaseBands = bands,
            quality = repetition.quality,
        )
    }

    private fun validateRepetition(exercise: EncoderExercise, rep: EncoderRepetition, size: Int) {
        fun validRange(start: Int, end: Int, name: String) {
            if (start !in 0 until size || end !in 0 until size || start > end) {
                throw IllegalArgumentException("$name is outside the sample range")
            }
        }
        if (rep.startSample < 0 || rep.endSample < rep.startSample || rep.endSample >= size) {
            throw IllegalArgumentException("Repetition bounds are invalid")
        }
        validRange(rep.eccentricPhase.startSample, rep.eccentricPhase.endSample, "eccentric phase")
        validRange(rep.concentricPhase.startSample, rep.concentricPhase.endSample, "concentric phase")
        if (rep.eccentricPhase.startSample !in rep.startSample..rep.endSample ||
            rep.eccentricPhase.endSample !in rep.startSample..rep.endSample ||
            rep.concentricPhase.startSample !in rep.startSample..rep.endSample ||
            rep.concentricPhase.endSample !in rep.startSample..rep.endSample
        ) throw IllegalArgumentException("Phase is outside repetition")
        if (rep.eccentricPhase.direction != MovementDirection.DOWN ||
            rep.concentricPhase.direction != MovementDirection.UP
        ) throw IllegalArgumentException("Phase directions are invalid")
        val ordered = if (exercise.eccentricFirst) {
            rep.eccentricPhase.endSample < rep.concentricPhase.startSample
        } else {
            rep.concentricPhase.endSample < rep.eccentricPhase.startSample
        }
        if (!ordered) throw IllegalArgumentException("Phases overlap or are out of exercise order")
    }

    private fun phaseOf(index: Int, rep: EncoderRepetition): PhaseKind = when {
        index in rep.eccentricPhase.startSample..rep.eccentricPhase.endSample -> PhaseKind.ECCENTRIC
        index in rep.concentricPhase.startSample..rep.concentricPhase.endSample -> PhaseKind.CONCENTRIC
        else -> PhaseKind.NONE
    }

    private fun phaseBand(
        phase: MovementPhase,
        selected: List<EncoderSample>,
        firstPhysical: Long,
        kind: PhaseKind,
        label: String,
        pattern: String,
    ): PhaseBand = PhaseBand(
        phase = kind,
        startElapsedPhysical = selected[phase.startSample - selected.first().ordinal].physicalTimeUs!! - firstPhysical,
        endElapsedPhysical = selected[phase.endSample - selected.first().ordinal].physicalTimeUs!! - firstPhysical,
        label = label,
        pattern = pattern,
    )

    private fun gapReason(
        previous: EncoderSample,
        current: EncoderSample,
        previousValue: Double?,
        value: Double?,
        gapBeforeUs: Long,
        threshold: Double,
    ): GapReason? = when {
        previous.frameIndex + 1 != current.frameIndex -> GapReason.NON_CONTIGUOUS_FRAME
        gapBeforeUs > threshold -> GapReason.PTS_GAP
        current.trackingStatus == TrackingStatus.LOST || previous.trackingStatus == TrackingStatus.LOST -> GapReason.TRACKING_LOST
        current.observationKind == ObservationKind.MISSING || previous.observationKind == ObservationKind.MISSING -> GapReason.MISSING_OBSERVATION
        previousValue == null || value == null -> GapReason.MISSING_VALUE
        else -> null
    }

    private fun median(values: List<Long>): Double? = values.takeIf { it.isNotEmpty() }?.sorted()?.let {
        if (it.size % 2 == 1) it[it.size / 2].toDouble()
        else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
    }
}

/** XY chart segmentation mirrors the time-series breaks but remains available without timebase. */
fun encoderTrajectorySegments(
    analysis: EncoderAnalysis,
    repetition: EncoderRepetition?,
): List<List<MetricPoint>> {
    val all = analysis.samples
    val range = if (repetition == null) {
        all.indices
    } else if (repetition.startSample in all.indices && repetition.endSample in all.indices && repetition.startSample <= repetition.endSample) {
        repetition.startSample..repetition.endSample
    } else return emptyList()
    val selected = range.map(all::get)
    val median = selected.zipWithNext().map { (previous, current) ->
        current.gapBeforeUs ?: (current.sourcePtsUs - previous.sourcePtsUs)
    }.sorted().let { values ->
        if (values.isEmpty()) Double.POSITIVE_INFINITY
        else if (values.size % 2 == 1) values[values.size / 2].toDouble()
        else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
    }
    val threshold = median * 1.5
    val result = mutableListOf<List<MetricPoint>>()
    var current = mutableListOf<MetricPoint>()
    fun close() { if (current.isNotEmpty()) result += current.toList(); current = mutableListOf() }
    selected.forEachIndexed { index, sample ->
        val point = sample.smoothedMetricPoint ?: sample.rawMetricPoint
        val previous = selected.getOrNull(index - 1)
        val cut = previous != null && (
            previous.frameIndex + 1 != sample.frameIndex ||
                (sample.gapBeforeUs ?: (sample.sourcePtsUs - previous.sourcePtsUs)) > threshold ||
                previous.trackingStatus == TrackingStatus.LOST || sample.trackingStatus == TrackingStatus.LOST ||
                previous.observationKind == ObservationKind.MISSING || sample.observationKind == ObservationKind.MISSING ||
                point == null
            )
        if (cut) close()
        if (sample.trackingStatus != TrackingStatus.LOST && sample.observationKind != ObservationKind.MISSING && point != null) {
            current += point
        } else close()
    }
    close()
    return result
}

/** Nearest sample mapping used by chart taps. Ties deliberately resolve to the earlier sample. */
fun nearestTimePoint(points: List<TimePoint>, elapsedPhysicalUs: Long): TimePoint? = points.minWithOrNull(
    compareBy<TimePoint> { abs(it.elapsedPhysical - elapsedPhysicalUs) }
        .thenByDescending { it.elapsedPhysical <= elapsedPhysicalUs },
)

fun nearestTimePointBySource(points: List<TimePoint>, sourcePtsUs: Long): TimePoint? = points.minWithOrNull(
    compareBy<TimePoint> { abs(it.sourcePts - sourcePtsUs) }
        .thenByDescending { it.sourcePts <= sourcePtsUs },
)

data class ChartCursors(
    val confirmedCursorPoint: TimePoint?,
    val previewPoint: TimePoint?,
)

/** Confirmed and local cursors remain separate until rendered metadata acknowledges the request. */
fun resolveChartCursors(
    points: List<TimePoint>,
    localSourcePtsUs: Long?,
    renderedSourcePtsUs: Long?,
): ChartCursors = ChartCursors(
    confirmedCursorPoint = renderedSourcePtsUs?.let { nearestTimePointBySource(points, it) },
    previewPoint = localSourcePtsUs
        ?.takeUnless { it == renderedSourcePtsUs }
        ?.let { nearestTimePointBySource(points, it) },
)
