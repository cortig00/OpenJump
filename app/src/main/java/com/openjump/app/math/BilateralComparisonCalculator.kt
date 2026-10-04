package com.openjump.app.math

import com.openjump.app.protocol.BilateralAggregation
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.BilateralSummary
import com.openjump.app.protocol.MeasurementSide

/** Pure aggregation for a completed bilateral set. No persistence or UI concerns belong here. */
object BilateralComparisonCalculator {
    class InvalidComparison(message: String) : IllegalArgumentException(message)

    fun summarize(comparison: BilateralComparison, aggregation: BilateralAggregation): BilateralSummary {
        return try {
            val left = aggregate(comparison.attempts(MeasurementSide.LEFT), aggregation)
            val right = aggregate(comparison.attempts(MeasurementSide.RIGHT), aggregation)
            val denominator = maxOf(left, right)
            if (!denominator.isFinite() || denominator <= 0.0) {
                throw InvalidComparison("El denominador de asimetría debe ser mayor que cero.")
            }
            BilateralSummary(
                aggregation = aggregation,
                leftValue = left,
                rightValue = right,
                asymmetryPercent = asymmetryPercentage(left, right),
                higherSide = when {
                    left > right -> MeasurementSide.LEFT
                    right > left -> MeasurementSide.RIGHT
                    else -> null
                },
            )
        } catch (error: IllegalArgumentException) {
            if (error is InvalidComparison) throw error
            throw InvalidComparison(error.message ?: "La comparación no es válida.")
        }
    }

    fun calculate(comparison: BilateralComparison, aggregation: BilateralAggregation): BilateralSummary =
        summarize(comparison, aggregation)

    fun best(comparison: BilateralComparison): BilateralSummary = summarize(comparison, BilateralAggregation.BEST)
    fun mean(comparison: BilateralComparison): BilateralSummary = summarize(comparison, BilateralAggregation.MEAN)

    fun asymmetryPercentage(left: Double, right: Double): Double {
        if (!left.isFinite() || !right.isFinite() || left <= 0.0 || right <= 0.0) {
            throw InvalidComparison("Los valores deben ser finitos y mayores que cero.")
        }
        return kotlin.math.abs(left - right) / maxOf(left, right) * 100.0
    }

    private fun aggregate(attempts: List<BilateralAttemptSnapshot>, aggregation: BilateralAggregation): Double {
        if (attempts.isEmpty()) throw InvalidComparison("Faltan intentos de una pierna.")
        val values = attempts.map { it.result.primaryMetric.value }
        val result = when (aggregation) {
            BilateralAggregation.BEST -> values.maxOrNull()!!
            BilateralAggregation.MEAN -> values.average()
        }
        if (!result.isFinite() || result <= 0.0) throw InvalidComparison("La métrica agregada no es válida.")
        return result
    }
}
