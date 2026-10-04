package com.openjump.app.ui.result

import com.openjump.app.math.JumpTemporalSensitivity
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.math.TemporalEventNeighborhood
import com.openjump.app.math.TemporalSensitivityResult
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.SpatialMarkType
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.protocol.StoredMeasurement

/** Converts domain measurements into a renderer-friendly, testable result model. */
object ResultPresenter {

    class InvalidResultData(message: String) : IllegalArgumentException(message)

    fun presentLive(
        draft: MeasurementDraft,
        result: ProtocolResult,
        detectedFps: Int,
        includeTemporalSensitivity: Boolean = true,
    ): ResultUiModel {
        val attempt = StoredAttempt(
            ordinal = 0,
            side = draft.setup.side,
            source = draft.source ?: com.openjump.app.protocol.VideoSource.UNKNOWN,
            videoUri = draft.videoUri,
            detectedFps = detectedFps,
            setup = draft.setup,
            events = (if (draft.protocolId == ProtocolId.HORIZONTAL) {
                draft.events.values.sortedBy { it.ptsUs }
            } else {
                draft.orderedMarks()
            }).map {
                StoredEvent(
                    type = it.key.type,
                    ordinal = it.key.ordinal,
                    ptsUs = it.ptsUs,
                    frameIndex = it.frameIndex,
                    previousPtsUs = it.previousPtsUs,
                    nextPtsUs = it.nextPtsUs,
                )
            },
            metrics = result.allMetrics,
            calibration = draft.horizontalJump?.calibration,
            spatialMarks = listOfNotNull(
                draft.horizontalJump?.startPoint,
                draft.horizontalJump?.landingHeel,
            ),
        )
        return build(
            protocolId = draft.protocolId,
            primaryMetric = result.primaryMetric,
            metrics = result.allMetrics,
            method = result.method,
            attempt = attempt,
            dateTime = null,
            temporalSensitivity = if (includeTemporalSensitivity) {
                temporalSensitivity(draft, attempt, result.allMetrics)
            } else null,
            note = draft.notes,
        )
    }

    fun presentStored(
        measurement: StoredMeasurement,
        includeTemporalSensitivity: Boolean = true,
    ): ResultUiModel {
        val attempt = measurement.attempts.minByOrNull { it.ordinal }
            ?: throw InvalidResultData("La medición guardada no contiene intentos.")
        return build(
            protocolId = measurement.protocolId,
            primaryMetric = measurement.primaryMetric,
            metrics = attempt.metrics,
            method = methodFor(measurement.protocolId),
            attempt = attempt,
            dateTime = measurement.dateTime,
            temporalSensitivity = if (includeTemporalSensitivity) {
                temporalSensitivity(measurement.protocolId, attempt, measurement.primaryMetric, attempt.metrics)
            } else null,
            note = measurement.notes,
        )
    }

    private fun build(
        protocolId: ProtocolId,
        primaryMetric: MetricValue,
        metrics: List<MetricValue>,
        method: com.openjump.app.protocol.MeasurementMethod,
        attempt: StoredAttempt,
        dateTime: Long?,
        temporalSensitivity: TemporalSensitivityResult? = null,
        note: String? = null,
    ): ResultUiModel {
        val definition = com.openjump.app.protocol.ProtocolCatalog.find(protocolId)
        validateMetric(primaryMetric, "métrica principal")
        metrics.forEach { validateMetric(it, "métrica") }

        val timelineResult = timeline(protocolId, attempt.events)
        return ResultUiModel(
            protocolId = protocolId,
            protocolTitle = definition.title,
            protocolShortName = definition.shortName,
            dateTime = dateTime,
            primaryMetric = primaryMetric,
            secondaryMetrics = secondaryMetrics(protocolId, primaryMetric, metrics),
            estimatedMetrics = estimatedMetrics(metrics),
            method = method,
            attempt = AttemptContextUiModel(
                ordinal = attempt.ordinal,
                side = attempt.side,
                source = attempt.source,
                detectedFps = attempt.detectedFps,
                dropHeightCm = attempt.setup.dropHeightCm,
                distanceCm = attempt.setup.distanceCm,
            ),
            timeline = timelineResult.timeline,
            events = attempt.events
                .sortedWith(compareBy<StoredEvent> { it.ordinal }.thenBy { it.ptsUs })
                .map { ResultEventUiModel(it.type, it.ordinal, it.ptsUs) },
            horizontal = horizontalResult(protocolId, attempt),
            warning = timelineResult.warning,
            temporalSensitivity = temporalSensitivity,
            note = note,
        )
    }

    private fun temporalSensitivity(
        draft: MeasurementDraft,
        attempt: StoredAttempt,
        expectedMetrics: List<MetricValue>,
    ): TemporalSensitivityResult? {
        val computed = runCatching { ProtocolCalculator.compute(draft) }.getOrNull() ?: return null
        if (!sameDirectMetrics(computed.allMetrics, expectedMetrics)) return null
        return JumpTemporalSensitivity.compute(
            draft,
            attempt.events.map { event ->
                TemporalEventNeighborhood(
                    key = EventKey(event.type, event.ordinal),
                    frameIndex = event.frameIndex,
                    ptsUs = event.ptsUs,
                    previousPtsUs = event.previousPtsUs,
                    nextPtsUs = event.nextPtsUs,
                )
            },
        )
    }

    private fun temporalSensitivity(
        protocolId: ProtocolId,
        attempt: StoredAttempt,
        primaryMetric: MetricValue,
        persistedMetrics: List<MetricValue>,
    ): TemporalSensitivityResult? {
        if (protocolId == ProtocolId.HORIZONTAL || protocolId == ProtocolId.ASYMMETRY) return null
        val events = attempt.events.associate { event ->
            EventKey(event.type, event.ordinal) to EventMark(
                key = EventKey(event.type, event.ordinal),
                ptsUs = event.ptsUs,
                frameIndex = event.frameIndex ?: -1,
                previousPtsUs = event.previousPtsUs,
                nextPtsUs = event.nextPtsUs,
            )
        }
        if (events.values.any { it.frameIndex < 0 }) return null
        val draft = MeasurementDraft(
            sessionKey = "stored-${attempt.ordinal}",
            protocolId = protocolId,
            setup = attempt.setup,
            source = attempt.source,
            videoUri = attempt.videoUri,
            events = events,
        )
        val direct = listOf(primaryMetric) + persistedMetrics.filterNot { it.key.isEstimate }
        return temporalSensitivity(draft, attempt, direct)
    }

    private fun sameDirectMetrics(
        expected: List<MetricValue>,
        actual: List<MetricValue>,
    ): Boolean {
        val expectedDirect = expected.filterNot { it.key.isEstimate }.distinctBy { it.key to it.ordinal }
        val actualDirect = actual.filterNot { it.key.isEstimate }.distinctBy { it.key to it.ordinal }
        if (expectedDirect.size != actualDirect.size) return false
        return expectedDirect.all { expectedMetric ->
            actualDirect.singleOrNull { it.key == expectedMetric.key && it.ordinal == expectedMetric.ordinal }
                ?.let {
                    it.unit == expectedMetric.unit &&
                        kotlin.math.abs(it.value - expectedMetric.value) <= 1e-9 * maxOf(1.0, kotlin.math.abs(expectedMetric.value))
                } == true
        }
    }

    private fun validateMetric(metric: MetricValue, name: String) {
        if (!metric.value.isFinite()) throw InvalidResultData("La $name no es un número válido.")
    }

    private fun secondaryMetrics(
        protocolId: ProtocolId,
        primary: MetricValue,
        metrics: List<MetricValue>,
    ): List<MetricValue> {
        val remaining = metrics.filterNot {
            (it.key == primary.key && it.ordinal == primary.ordinal) || it.key.isEstimate
        }
        val order = when (protocolId) {
            ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL -> listOf(
                MetricKey.FLIGHT_TIME_MS,
                MetricKey.TAKEOFF_VELOCITY_MPS,
                MetricKey.TIME_TO_TAKEOFF_MS,
                MetricKey.RSI_MOD,
            )
            ProtocolId.SJ -> listOf(MetricKey.FLIGHT_TIME_MS, MetricKey.TAKEOFF_VELOCITY_MPS)
            ProtocolId.DROP_JUMP -> listOf(
                MetricKey.CONTACT_TIME_MS,
                MetricKey.HEIGHT_CM,
                MetricKey.FLIGHT_TIME_MS,
            )
            else -> emptyList()
        }
        return if (order.isEmpty()) remaining else {
            remaining.sortedWith(compareBy<MetricValue> { order.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { it.ordinal })
        }
    }

    private fun estimatedMetrics(metrics: List<MetricValue>): List<MetricValue> {
        val order = listOf(
            MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W,
            MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG,
            MetricKey.ESTIMATED_POTENTIAL_ENERGY_J,
            MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J,
            MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS,
            MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT,
            MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT,
        )
        return metrics.filter { it.key.isEstimate }.sortedWith(
            compareBy<MetricValue> {
                order.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it.ordinal },
        )
    }

    private data class TimelineResult(val timeline: TimelineUiModel?, val warning: TimelineWarning?)

    private fun timeline(protocolId: ProtocolId, events: List<StoredEvent>): TimelineResult {
        val points = events
            .filter { it.ordinal == 0 }
            .associateBy { it.type }
        val specification = when (protocolId) {
            ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL -> listOf(
                EventType.MOVEMENT_START to TimelineSegmentKind.PREPARATION,
                EventType.TAKEOFF to TimelineSegmentKind.FLIGHT,
                EventType.LANDING to null,
            )
            ProtocolId.SJ -> listOf(
                EventType.TAKEOFF to TimelineSegmentKind.FLIGHT,
                EventType.LANDING to null,
            )
            ProtocolId.DROP_JUMP -> listOf(
                EventType.INITIAL_CONTACT to TimelineSegmentKind.CONTACT,
                EventType.TAKEOFF to TimelineSegmentKind.FLIGHT,
                EventType.LANDING to null,
            )
            ProtocolId.HORIZONTAL -> return TimelineResult(null, null)
            else -> return TimelineResult(null, TimelineWarning.UNSUPPORTED_PROTOCOL)
        }
        val ordered = specification.map { (type, _) -> points[type] }
        if (ordered.any { it == null }) {
            return TimelineResult(null, TimelineWarning.MISSING_EVENTS)
        }
        val confirmed = ordered.filterNotNull()
        if (confirmed.zipWithNext().any { (first, second) -> second.ptsUs <= first.ptsUs }) {
            return TimelineResult(null, TimelineWarning.INVALID_PTS)
        }
        val segments = specification.dropLast(1).mapIndexed { index, (_, kind) ->
            val segmentKind = requireNotNull(kind)
            TimelineSegmentUiModel(
                kind = segmentKind,
                durationUs = confirmed[index + 1].ptsUs - confirmed[index].ptsUs,
            )
        }
        val totalDurationUs = confirmed.last().ptsUs - confirmed.first().ptsUs
        return TimelineResult(
            timeline = TimelineUiModel(
                segments = segments,
                totalDurationUs = totalDurationUs,
            ),
            warning = null,
        )
    }

    private fun methodFor(protocolId: ProtocolId): com.openjump.app.protocol.MeasurementMethod =
        if (protocolId == ProtocolId.HORIZONTAL) {
            com.openjump.app.protocol.MeasurementMethod.MANUAL_CALIBRATED_DISTANCE
        } else {
            com.openjump.app.protocol.MeasurementMethod.FLIGHT_TIME
        }

    private fun horizontalResult(protocolId: ProtocolId, attempt: StoredAttempt): HorizontalResultUiModel? {
        if (protocolId != ProtocolId.HORIZONTAL) return null
        val calibration = attempt.calibration
            ?: throw InvalidResultData("La medición horizontal no contiene calibración.")
        val start = attempt.spatialMarks.singleOrNull { it.type == SpatialMarkType.START_POINT }
            ?: throw InvalidResultData("La medición horizontal no contiene el punto de salida.")
        val landing = attempt.spatialMarks.singleOrNull { it.type == SpatialMarkType.LANDING_HEEL }
            ?: throw InvalidResultData("La medición horizontal no contiene el talón de aterrizaje.")
        return HorizontalResultUiModel(calibration, start, landing)
    }

}
