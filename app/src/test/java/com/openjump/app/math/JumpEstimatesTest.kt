package com.openjump.app.math

import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpEstimatesTest {

    @Test
    fun `sj calculates Sayers mechanics and stature estimates in canonical units`() {
        val estimates = JumpEstimates.calculate(
            protocolId = ProtocolId.SJ,
            metrics = listOf(
                MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
                MetricValue(MetricKey.TAKEOFF_VELOCITY_MPS, 3.0, MetricUnit.METER_PER_SECOND),
            ),
            athlete = AthleteAnthropometrics(weightKg = 80.0, heightCm = 180.0),
        ).associateBy { it.key }

        assertEquals(4_247.691, estimates.value(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W), 1e-6)
        assertEquals(
            53.0961375,
            estimates.value(MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG),
            1e-6,
        )
        assertEquals(346.2139716, estimates.value(MetricKey.ESTIMATED_POTENTIAL_ENERGY_J), 1e-6)
        assertEquals(360.0, estimates.value(MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J), 1e-6)
        assertEquals(240.0, estimates.value(MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS), 1e-6)
        assertEquals(24.5166667, estimates.value(MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT), 1e-6)
    }

    @Test
    fun `horizontal jump only calculates distance relative to stature`() {
        val estimates = JumpEstimates.calculate(
            protocolId = ProtocolId.HORIZONTAL,
            metrics = listOf(MetricValue(MetricKey.DISTANCE_M, 2.43, MetricUnit.METER)),
            athlete = AthleteAnthropometrics(weightKg = 80.0, heightCm = 180.0),
        )

        assertEquals(listOf(MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT), estimates.map { it.key })
        assertEquals(135.0, estimates.single().value, 1e-9)
    }

    @Test
    fun `missing profile values omit dependent estimates without affecting base result`() {
        val height = MetricValue(MetricKey.HEIGHT_CM, 40.0, MetricUnit.CENTIMETER)
        val withoutProfile = JumpEstimates.calculate(
            ProtocolId.CMJ,
            listOf(height),
            AthleteAnthropometrics(),
        )
        val statureOnly = JumpEstimates.calculate(
            ProtocolId.CMJ,
            listOf(height),
            AthleteAnthropometrics(heightCm = 200.0),
        )

        assertTrue(withoutProfile.isEmpty())
        assertEquals(listOf(MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT), statureOnly.map { it.key })
    }

    @Test
    fun `Sayers power is limited to validated SJ and CMJ protocols`() {
        val metrics = listOf(MetricValue(MetricKey.HEIGHT_CM, 45.0, MetricUnit.CENTIMETER))
        val athlete = AthleteAnthropometrics(weightKg = 75.0, heightCm = 180.0)

        listOf(ProtocolId.ABALAKOV, ProtocolId.UNILATERAL, ProtocolId.DROP_JUMP).forEach { protocol ->
            val estimates = JumpEstimates.calculate(protocol, metrics, athlete)
            assertFalse(estimates.any { it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W })
            assertTrue(estimates.any { it.key == MetricKey.ESTIMATED_POTENTIAL_ENERGY_J })
        }
    }

    private fun Map<MetricKey, MetricValue>.value(key: MetricKey): Double = getValue(key).value
}
