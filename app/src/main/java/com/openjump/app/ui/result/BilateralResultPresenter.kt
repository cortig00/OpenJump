package com.openjump.app.ui.result

import com.openjump.app.math.BilateralComparisonCalculator
import com.openjump.app.protocol.BilateralAggregation
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.BilateralSummary
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredMeasurement

/** Presentation model for the one-root bilateral result. BEST is deliberately the default. */
data class BilateralResultUiModel(
    val best: BilateralSummary,
    val mean: BilateralSummary,
    val attempts: List<ResultUiModel>,
    val note: String? = null,
)

object BilateralResultPresenter {
    class InvalidResultData(message: String) : IllegalArgumentException(message)

    fun presentLive(comparison: BilateralComparison): BilateralResultUiModel = BilateralResultUiModel(
        note = comparison.notes,
        best = BilateralComparisonCalculator.best(comparison),
        mean = BilateralComparisonCalculator.mean(comparison),
        attempts = comparison.attempts.map { snapshot ->
            ResultPresenter.presentLive(
                snapshot.draft,
                snapshot.result,
                snapshot.detectedFps,
                includeTemporalSensitivity = false,
            )
        },
    )

    fun presentStored(measurement: StoredMeasurement): BilateralResultUiModel {
        if (measurement.protocolId != ProtocolId.ASYMMETRY) throw InvalidResultData("La medición no es bilateral.")
        val attempts = measurement.attempts.sortedBy { it.ordinal }
        if (attempts.isEmpty() || attempts.size !in 2..10 || attempts.size % 2 != 0) {
            throw InvalidResultData("La comparación bilateral no contiene intentos válidos.")
        }
        val half = attempts.size / 2
        attempts.forEachIndexed { index, attempt ->
            val expectedSide = if (index < half) MeasurementSide.LEFT else MeasurementSide.RIGHT
            if (attempt.ordinal != index || attempt.side != expectedSide) {
                throw InvalidResultData("Los intentos bilaterales están corruptos.")
            }
        }
        val values = attempts.map { attempt ->
            attempt.metrics.singleOrNull { it.key == MetricKey.HEIGHT_CM }
                ?.takeIf { it.value.isFinite() && it.value > 0.0 }
                ?: throw InvalidResultData("Falta la altura de un intento bilateral.")
        }
        fun summary(aggregation: BilateralAggregation): BilateralSummary {
            val leftValues = values.take(half).map(MetricValue::value)
            val rightValues = values.drop(half).map(MetricValue::value)
            val left = if (aggregation == BilateralAggregation.BEST) leftValues.maxOrNull()!! else leftValues.average()
            val right = if (aggregation == BilateralAggregation.BEST) rightValues.maxOrNull()!! else rightValues.average()
            val asymmetry = kotlin.math.abs(left - right) / maxOf(left, right) * 100.0
            return BilateralSummary(aggregation, left, right, asymmetry, when {
                left > right -> MeasurementSide.LEFT
                right > left -> MeasurementSide.RIGHT
                else -> null
            })
        }
        val bestSummary = summary(BilateralAggregation.BEST)
        if (measurement.primaryMetric.key != MetricKey.ASYMMETRY_PERCENT ||
            measurement.primaryMetric.unit != com.openjump.app.protocol.MetricUnit.PERCENT ||
            !measurement.primaryMetric.value.isFinite() ||
            kotlin.math.abs(measurement.primaryMetric.value - bestSummary.asymmetryPercent) > 1e-9
        ) {
            throw InvalidResultData("El resumen bilateral guardado no coincide con BEST.")
        }
        val detailModels = attempts.map { attempt ->
            val primary = attempt.metrics.firstOrNull { it.key == MetricKey.HEIGHT_CM }
                ?: throw InvalidResultData("Falta la métrica principal del intento.")
            val detail = StoredMeasurement(
                id = measurement.id,
                dateTime = measurement.dateTime,
                athleteId = measurement.athleteId,
                athleteName = measurement.athleteName,
                protocolId = ProtocolId.UNILATERAL,
                primaryMetric = primary,
                attempts = listOf(attempt),
                notes = measurement.notes,
            )
            ResultPresenter.presentStored(detail, includeTemporalSensitivity = false)
        }
        return BilateralResultUiModel(bestSummary, summary(BilateralAggregation.MEAN), detailModels, measurement.notes)
    }
}
