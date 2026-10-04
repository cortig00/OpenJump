package com.openjump.app.math

import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult

/** The real PTS neighbors captured for one marked event. */
data class TemporalEventNeighborhood(
    val key: EventKey,
    val frameIndex: Int?,
    val ptsUs: Long,
    val previousPtsUs: Long?,
    val nextPtsUs: Long?,
)

enum class TemporalShift { PREVIOUS, NEXT }

data class TemporalPerturbation(
    val eventKey: EventKey,
    val shift: TemporalShift,
    val fromPtsUs: Long,
    val toPtsUs: Long,
)

data class TemporalMetricRange(
    val key: MetricKey,
    val unit: MetricUnit,
    val base: Double,
    val minimum: Double,
    val maximum: Double,
    val contributingVariants: List<TemporalPerturbation>,
)

data class TemporalSensitivityResult(
    val protocolId: ProtocolId,
    val validVariants: List<TemporalPerturbation>,
    val ranges: List<TemporalMetricRange>,
)

/**
 * Recalculates direct jump metrics after moving exactly one marker by one indexed
 * frame. This class deliberately knows nothing about FPS or Android.
 */
object JumpTemporalSensitivity {
    private val supportedProtocols = setOf(
        ProtocolId.CMJ,
        ProtocolId.SJ,
        ProtocolId.ABALAKOV,
        ProtocolId.UNILATERAL,
        ProtocolId.DROP_JUMP,
    )

    fun compute(
        draft: MeasurementDraft,
        neighborhoods: Collection<TemporalEventNeighborhood>,
    ): TemporalSensitivityResult? {
        if (draft.protocolId !in supportedProtocols) return null
        if (draft.validationError() != null) return null

        val base = runCatching { ProtocolCalculator.compute(draft) }.getOrNull() ?: return null
        val baseDirect = base.allMetrics.filterNot { it.key.isEstimate || excluded(it.key) }
        if (baseDirect.isEmpty()) return null

        val byKey = neighborhoods.associateBy { it.key }
        val perturbations = buildList {
            draft.definition.requiredEvents.forEach { type ->
                val key = EventKey(type, 0)
                val neighborhood = byKey[key] ?: return@forEach
                val mark = draft.events[key] ?: return@forEach
                if (neighborhood.ptsUs != mark.ptsUs ||
                    neighborhood.frameIndex == null ||
                    neighborhood.frameIndex < 0 ||
                    mark.frameIndex != neighborhood.frameIndex
                ) return@forEach
                neighborhood.previousPtsUs
                    ?.takeIf { neighborhood.frameIndex > 0 && it < neighborhood.ptsUs }
                    ?.let { previous ->
                        add(
                            Candidate(
                                TemporalPerturbation(key, TemporalShift.PREVIOUS, neighborhood.ptsUs, previous),
                                mark.copy(ptsUs = previous, frameIndex = neighborhood.frameIndex - 1),
                            ),
                        )
                    }
                neighborhood.nextPtsUs
                    ?.takeIf { it > neighborhood.ptsUs }
                    ?.let { next ->
                        add(
                            Candidate(
                                TemporalPerturbation(key, TemporalShift.NEXT, neighborhood.ptsUs, next),
                                mark.copy(ptsUs = next, frameIndex = neighborhood.frameIndex + 1),
                            ),
                        )
                    }
            }
        }
        if (perturbations.isEmpty()) return null

        val valid = mutableListOf<VariantMetrics>()
        perturbations.forEach { candidate ->
            val events = draft.events + (candidate.perturbation.eventKey to candidate.mark)
            val variantDraft = draft.copy(events = events)
            val result = runCatching { ProtocolCalculator.compute(variantDraft) }.getOrNull() ?: return@forEach
            if (!sameDirectMetricSet(baseDirect, result)) return@forEach
            valid += VariantMetrics(candidate.perturbation, result)
        }
        if (valid.isEmpty()) return null

        val ranges = baseDirect.mapNotNull { baseMetric ->
            val values = valid.mapNotNull { variant ->
                variant.result.allMetrics.firstOrNull {
                    it.key == baseMetric.key && it.ordinal == baseMetric.ordinal
                }?.takeIf { it.unit == baseMetric.unit }
            }
            if (values.size != valid.size) return@mapNotNull null
            val all = values.map { it.value } + baseMetric.value
            val minimum = all.minOrNull() ?: return@mapNotNull null
            val maximum = all.maxOrNull() ?: return@mapNotNull null
            if (minimum >= maximum) return@mapNotNull null
            TemporalMetricRange(
                key = baseMetric.key,
                unit = baseMetric.unit,
                base = baseMetric.value,
                minimum = minimum,
                maximum = maximum,
                contributingVariants = valid.filter { variant ->
                    variant.result.allMetrics.firstOrNull {
                        it.key == baseMetric.key && it.ordinal == baseMetric.ordinal && it.unit == baseMetric.unit
                    }?.value?.let { it == minimum || it == maximum } == true
                }.map { it.perturbation },
            )
        }
        if (ranges.isEmpty()) return null
        return TemporalSensitivityResult(
            protocolId = draft.protocolId,
            validVariants = valid.map { it.perturbation },
            ranges = ranges,
        )
    }

    /** Convenience adapter for a draft whose marks already carry their neighbors. */
    fun compute(draft: MeasurementDraft): TemporalSensitivityResult? = compute(
        draft,
        draft.events.values.map {
            TemporalEventNeighborhood(it.key, it.frameIndex, it.ptsUs, it.previousPtsUs, it.nextPtsUs)
        },
    )

    private fun excluded(key: MetricKey): Boolean = key.name.startsWith("DISTANCE") || key == MetricKey.ASYMMETRY_PERCENT

    private fun sameDirectMetricSet(expected: List<com.openjump.app.protocol.MetricValue>, actual: ProtocolResult): Boolean {
        val values = actual.allMetrics.filterNot { it.key.isEstimate || excluded(it.key) }
        if (values.size != expected.size) return false
        return expected.all { expectedMetric ->
            values.singleOrNull { it.key == expectedMetric.key && it.ordinal == expectedMetric.ordinal }
                ?.let { it.unit == expectedMetric.unit && it.value.isFinite() } == true
        }
    }

    private data class Candidate(val perturbation: TemporalPerturbation, val mark: EventMark)
    private data class VariantMetrics(val perturbation: TemporalPerturbation, val result: ProtocolResult)
}
