package com.openjump.app.math

import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.HorizontalJumpDraft
import com.openjump.app.protocol.MeasurementMethod
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.tracking.ImagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCalculatorTest {

    @Test
    fun `asymmetry cannot be calculated as an individual draft`() {
        val draft = MeasurementDraft(sessionKey = "asymmetry", protocolId = ProtocolId.ASYMMETRY, athleteId = 1L)
        assertThrows(ProtocolCalculator.InvalidMeasurement::class.java) { ProtocolCalculator.compute(draft) }
    }

    @Test
    fun `cmj abalakov and unilateral share flight-time metrics`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocol ->
            val setup = if (protocol == ProtocolId.UNILATERAL) {
                ProtocolSetup(side = MeasurementSide.RIGHT)
            } else {
                ProtocolSetup()
            }
            val result = ProtocolCalculator.compute(
                draft(
                    protocol,
                    setup,
                    EventType.MOVEMENT_START to 100_000L,
                    EventType.TAKEOFF to 700_000L,
                    EventType.LANDING to 1_300_000L,
                ),
            )
            assertEquals(MetricKey.HEIGHT_CM, result.primaryMetric.key)
            assertEquals(44.13, result.primaryMetric.value, 0.01)
            assertTrue(result.secondaryMetrics.any { it.key == MetricKey.RSI_MOD })
        }
    }

    @Test
    fun `sj does not invent time to takeoff or rsi mod`() {
        val result = ProtocolCalculator.compute(
            draft(
                ProtocolId.SJ,
                ProtocolSetup(),
                EventType.TAKEOFF to 700_000L,
                EventType.LANDING to 1_300_000L,
            ),
        )
        assertEquals(44.13, result.primaryMetric.value, 0.01)
        assertFalse(result.allMetrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS })
        assertFalse(result.allMetrics.any { it.key == MetricKey.RSI_MOD })
    }

    @Test
    fun `drop jump computes contact time and rsi`() {
        val result = ProtocolCalculator.compute(
            draft(
                ProtocolId.DROP_JUMP,
                ProtocolSetup(dropHeightCm = 40.0),
                EventType.INITIAL_CONTACT to 100_000L,
                EventType.TAKEOFF to 300_000L,
                EventType.LANDING to 900_000L,
            ),
        )
        assertEquals(MetricKey.RSI, result.primaryMetric.key)
        assertEquals(2.206, result.primaryMetric.value, 0.001)
        assertEquals(200.0, result.allMetrics.first { it.key == MetricKey.CONTACT_TIME_MS }.value, 0.001)
    }

    @Test
    fun `horizontal jump publishes projected calibrated distance in meters`() {
        val horizontal = HorizontalJumpDraft()
            .markCalibrationPoint(ImagePoint(0.0, 0.0), 0)
            .markCalibrationPoint(ImagePoint(200.0, 0.0), 0)
            .confirmCalibration(1.0)
            .markNextPoint(ImagePoint(10.0, 10.0), 1, 100_000L)
            .markNextPoint(ImagePoint(496.0, 80.0), 20, 700_000L)
        val measurement = MeasurementDraft(
            sessionKey = "horizontal",
            protocolId = ProtocolId.HORIZONTAL,
            horizontalJump = horizontal,
        )
        assertEquals(horizontal, measurement.horizontalJump)
        assertNull(measurement.validationError())
        val result = ProtocolCalculator.compute(measurement)

        assertEquals(MetricKey.DISTANCE_M, result.primaryMetric.key)
        assertEquals(MetricUnit.METER, result.primaryMetric.unit)
        assertEquals(2.43, result.primaryMetric.value, 1e-9)
        assertEquals(MeasurementMethod.MANUAL_CALIBRATED_DISTANCE, result.method)
    }

    @Test
    fun `protocol calculation appends estimates from the captured athlete snapshot`() {
        val measurement = draft(
            ProtocolId.SJ,
            ProtocolSetup(),
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        ).copy(
            athleteAnthropometrics = AthleteAnthropometrics(weightKg = 80.0, heightCm = 180.0),
        )

        val result = ProtocolCalculator.compute(measurement)

        assertTrue(result.allMetrics.any { it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W })
        assertTrue(result.allMetrics.any { it.key == MetricKey.ESTIMATED_POTENTIAL_ENERGY_J })
        assertTrue(result.allMetrics.any { it.key == MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT })
    }

    @Test
    fun `missing or unordered events are rejected`() {
        assertThrows(ProtocolCalculator.InvalidMeasurement::class.java) {
            ProtocolCalculator.compute(
                draft(
                    ProtocolId.SJ,
                    ProtocolSetup(),
                    EventType.TAKEOFF to 700_000L,
                ),
            )
        }
        assertThrows(ProtocolCalculator.InvalidMeasurement::class.java) {
            ProtocolCalculator.compute(
                draft(
                    ProtocolId.DROP_JUMP,
                    ProtocolSetup(dropHeightCm = 40.0),
                    EventType.INITIAL_CONTACT to 400_000L,
                    EventType.TAKEOFF to 300_000L,
                    EventType.LANDING to 900_000L,
                ),
            )
        }
    }

    @Test
    fun `countermovement protocols compute flight metrics from two real events`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocol ->
            val setup = if (protocol == ProtocolId.UNILATERAL) {
                ProtocolSetup(side = MeasurementSide.RIGHT)
            } else {
                ProtocolSetup()
            }
            val result = ProtocolCalculator.compute(
                draft(protocol, setup, EventType.TAKEOFF to 700_000L, EventType.LANDING to 1_300_000L),
            )

            assertEquals(MetricKey.HEIGHT_CM, result.primaryMetric.key)
            assertEquals(9.80665 * 0.6 * 0.6 / 8.0 * 100.0, result.primaryMetric.value, 1e-9)
            assertEquals(600.0, value(result, MetricKey.FLIGHT_TIME_MS), 1e-9)
            assertEquals(9.80665 * 0.3, value(result, MetricKey.TAKEOFF_VELOCITY_MPS), 1e-9)
            assertFalse(result.allMetrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS })
            assertFalse(result.allMetrics.any { it.key == MetricKey.RSI_MOD })
            assertEquals(3, result.allMetrics.size)
        }
    }

    @Test
    fun `three real events keep exactly the previous countermovement results`() {
        listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).forEach { protocol ->
            val setup = if (protocol == ProtocolId.UNILATERAL) {
                ProtocolSetup(side = MeasurementSide.LEFT)
            } else {
                ProtocolSetup()
            }
            val result = ProtocolCalculator.compute(
                draft(
                    protocol,
                    setup,
                    EventType.MOVEMENT_START to 100_000L,
                    EventType.TAKEOFF to 700_000L,
                    EventType.LANDING to 1_300_000L,
                ),
            )
            val previous = JumpMath.compute(100_000L, 700_000L, 1_300_000L)

            assertEquals(previous.heightCm, result.primaryMetric.value, 0.0)
            assertEquals(previous.flightTimeMs, value(result, MetricKey.FLIGHT_TIME_MS), 0.0)
            assertEquals(previous.takeoffVelocityMS, value(result, MetricKey.TAKEOFF_VELOCITY_MPS), 0.0)
            assertEquals(previous.timeToTakeoffMs, value(result, MetricKey.TIME_TO_TAKEOFF_MS), 0.0)
            assertEquals(previous.rsiMod, value(result, MetricKey.RSI_MOD), 0.0)
            // Independent numbers for the same real marks: g = 9.80665 m/s².
            assertEquals(600.0, value(result, MetricKey.TIME_TO_TAKEOFF_MS), 1e-9)
            assertEquals(0.44129925 / 0.6, value(result, MetricKey.RSI_MOD), 1e-12)
        }
    }

    @Test
    fun `an invalid movement start is rejected instead of inventing tto`() {
        listOf(
            arrayOf(EventType.MOVEMENT_START to 700_000L),
            arrayOf(EventType.MOVEMENT_START to 800_000L),
        ).forEach { start ->
            assertThrows(ProtocolCalculator.InvalidMeasurement::class.java) {
                ProtocolCalculator.compute(
                    draft(
                        ProtocolId.CMJ,
                        ProtocolSetup(),
                        start[0],
                        EventType.TAKEOFF to 700_000L,
                        EventType.LANDING to 1_300_000L,
                    ),
                )
            }
        }
        assertThrows(ProtocolCalculator.InvalidMeasurement::class.java) {
            ProtocolCalculator.compute(
                draft(ProtocolId.CMJ, ProtocolSetup(), EventType.TAKEOFF to 700_000L),
            )
        }
    }

    private fun value(result: ProtocolResult, key: MetricKey): Double =
        result.allMetrics.single { it.key == key }.value

    private fun draft(
        protocol: ProtocolId,
        setup: ProtocolSetup,
        vararg events: Pair<EventType, Long>,
    ): MeasurementDraft = MeasurementDraft(
        sessionKey = "test",
        protocolId = protocol,
        setup = setup,
        events = events.mapIndexed { index, (type, pts) ->
            EventKey(type) to EventMark(EventKey(type), pts, index)
        }.toMap(),
    )
}
