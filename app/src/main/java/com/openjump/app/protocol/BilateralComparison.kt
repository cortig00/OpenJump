package com.openjump.app.protocol

/** The two derived ways a completed bilateral comparison can be presented. */
enum class BilateralAggregation { BEST, MEAN }

data class BilateralAttemptSnapshot(
    val side: MeasurementSide,
    /** Visible one-based position within that side (LEFT 1..N, RIGHT 1..N). */
    val sideOrdinal: Int,
    val draft: MeasurementDraft,
    val result: ProtocolResult,
    val detectedFps: Int,
) {
    init {
        require(sideOrdinal in 1..5) { "El ordinal bilateral no es válido." }
        require(draft.protocolId == ProtocolId.UNILATERAL) { "Cada intento bilateral debe ser unilateral." }
        require(draft.setup.side == side) { "El lado del intento no coincide con su borrador." }
        require(result.protocolId == ProtocolId.UNILATERAL) { "El resultado bilateral debe calcularse como unilateral." }
        require(result.primaryMetric.key == MetricKey.HEIGHT_CM) { "La comparación requiere altura." }
        require(result.primaryMetric.unit == MetricUnit.CENTIMETER) { "La comparación requiere centímetros." }
        require(result.allMetrics.all { it.value.isFinite() }) { "Las métricas deben ser finitas." }
    }

    /** Durable ordinal depends on N and is assigned by BilateralComparison in sequence order. */
    fun durableOrdinal(targetAttempts: Int): Int =
        if (side == MeasurementSide.LEFT) sideOrdinal - 1 else targetAttempts + sideOrdinal - 1
}

data class BilateralComparison(
    val sessionKey: String,
    val athleteId: Long,
    val targetAttempts: Int,
    val attempts: List<BilateralAttemptSnapshot>,
    val testingSessionId: Long? = null,
    val notes: String? = null,
) {
    init {
        require(sessionKey.isNotBlank())
        require(athleteId > 0L)
        require(targetAttempts in 1..5) { "La comparación debe tener entre 1 y 5 saltos por pierna." }
        require(attempts.size == targetAttempts * 2) { "La comparación no está completa." }
        attempts.forEachIndexed { index, attempt ->
            val expectedSide = if (index < targetAttempts) MeasurementSide.LEFT else MeasurementSide.RIGHT
            val expectedOrdinal = if (index < targetAttempts) index + 1 else index - targetAttempts + 1
            require(attempt.side == expectedSide && attempt.sideOrdinal == expectedOrdinal) {
                "Los intentos deben estar ordenados LEFT 1..N y RIGHT 1..N."
            }
        }
        val primary = attempts.first().result.primaryMetric
        require(attempts.all { it.result.primaryMetric.key == primary.key && it.result.primaryMetric.unit == primary.unit }) {
            "Los intentos deben usar la misma métrica y unidad."
        }
        require(attempts.all { it.result.primaryMetric.value.isFinite() && it.result.primaryMetric.value > 0.0 }) {
            "La métrica principal debe ser finita y mayor que cero."
        }
    }

    fun attempts(side: MeasurementSide): List<BilateralAttemptSnapshot> = attempts.filter { it.side == side }
}

typealias BilateralAttempt = BilateralAttemptSnapshot

data class BilateralSummary(
    val aggregation: BilateralAggregation,
    val leftValue: Double,
    val rightValue: Double,
    val asymmetryPercent: Double,
    val higherSide: MeasurementSide?,
    val unit: MetricUnit = MetricUnit.CENTIMETER,
) {
    init {
        require(leftValue.isFinite() && rightValue.isFinite() && asymmetryPercent.isFinite())
        require(leftValue > 0.0 && rightValue > 0.0)
        require(asymmetryPercent >= 0.0)
    }
}
