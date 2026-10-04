package com.openjump.app.math

import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import kotlin.math.sqrt

/** Athlete-dependent estimates appended to an otherwise athlete-independent protocol result. */
object JumpEstimates {
    private val sayersProtocols = setOf(ProtocolId.SJ, ProtocolId.CMJ)

    fun enrich(result: ProtocolResult, athlete: AthleteAnthropometrics): ProtocolResult {
        val baseMetrics = result.allMetrics.filterNot { it.key.isEstimate }
        val estimates = calculate(result.protocolId, baseMetrics, athlete)
        return result.copy(
            primaryMetric = baseMetrics.firstOrNull { metric ->
                metric.key == result.primaryMetric.key && metric.ordinal == result.primaryMetric.ordinal
            } ?: result.primaryMetric,
            secondaryMetrics = baseMetrics.filterNot { metric ->
                metric.key == result.primaryMetric.key && metric.ordinal == result.primaryMetric.ordinal
            } + estimates,
        )
    }

    fun calculate(
        protocolId: ProtocolId,
        metrics: List<MetricValue>,
        athlete: AthleteAnthropometrics,
    ): List<MetricValue> = buildList {
        val jumpHeightCm = metrics.firstValue(MetricKey.HEIGHT_CM)?.takeIf { it > 0.0 }
        val jumpHeightM = jumpHeightCm?.div(100.0)
        val distanceM = metrics.firstValue(MetricKey.DISTANCE_M)?.takeIf { it > 0.0 }
            ?: metrics.firstValue(MetricKey.DISTANCE_CM)?.takeIf { it > 0.0 }?.div(100.0)
        val massKg = athlete.weightKg
        val statureCm = athlete.heightCm

        if (massKg != null && jumpHeightCm != null && protocolId in sayersProtocols) {
            val powerW = 60.7 * jumpHeightCm + 45.3 * massKg - 2_055.0
            if (powerW.isFinite() && powerW > 0.0) {
                add(MetricValue(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W, powerW, MetricUnit.WATT))
                add(
                    MetricValue(
                        MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG,
                        powerW / massKg,
                        MetricUnit.WATT_PER_KILOGRAM,
                    ),
                )
            }
        }

        if (massKg != null && jumpHeightM != null) {
            val takeoffVelocity = metrics.firstValue(MetricKey.TAKEOFF_VELOCITY_MPS)
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: sqrt(2.0 * JumpMath.GRAVITY * jumpHeightM)
            add(
                MetricValue(
                    MetricKey.ESTIMATED_POTENTIAL_ENERGY_J,
                    massKg * JumpMath.GRAVITY * jumpHeightM,
                    MetricUnit.JOULE,
                ),
            )
            add(
                MetricValue(
                    MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J,
                    0.5 * massKg * takeoffVelocity * takeoffVelocity,
                    MetricUnit.JOULE,
                ),
            )
            add(
                MetricValue(
                    MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS,
                    massKg * takeoffVelocity,
                    MetricUnit.KILOGRAM_METER_PER_SECOND,
                ),
            )
        }

        if (statureCm != null && jumpHeightCm != null) {
            add(
                MetricValue(
                    MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT,
                    100.0 * jumpHeightCm / statureCm,
                    MetricUnit.PERCENT,
                ),
            )
        }
        if (statureCm != null && distanceM != null && protocolId == ProtocolId.HORIZONTAL) {
            add(
                MetricValue(
                    MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT,
                    100.0 * distanceM / (statureCm / 100.0),
                    MetricUnit.PERCENT,
                ),
            )
        }
    }.filter { it.value.isFinite() && it.value >= 0.0 }

    private fun List<MetricValue>.firstValue(key: MetricKey): Double? = firstOrNull { it.key == key }?.value
}
