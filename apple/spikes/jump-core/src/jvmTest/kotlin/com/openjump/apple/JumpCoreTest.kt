package com.openjump.apple

import com.openjump.app.math.JumpMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JumpCoreTest {
    @Test fun knownValuesComeFromSharedJumpMath() {
        val result = JumpCore().calculate(100_000, 400_000, 900_000)
        assertEquals(30.64578125, result.heightCm, 1e-10)
        assertEquals(2.4516625, result.takeoffVelocityMs, 1e-10)
        assertEquals(1.0215260416666667, result.rsiMod, 1e-10)
        assertEquals(500.0, result.flightTimeMs)
    }

    @Test fun minimumFlightBoundaryAndInvalidTimes() {
        assertEquals(50.0, JumpMath.computeFlight(0, 50_000).flightTimeMs)
        assertFailsWith<JumpMath.InvalidTimes> { JumpMath.computeFlight(0, 49_999) }
        assertFailsWith<JumpMath.InvalidTimes> { JumpMath.compute(null, 1, 50_001) }
        assertFailsWith<JumpMath.InvalidTimes> { JumpMath.compute(1, 1, 50_001) }
        assertFailsWith<JumpMath.InvalidTimes> { JumpMath.compute(1, 50_001, 50_000) }
    }

    @Test fun countermovementProtocolsShareCanonicalFiveHundredMsOracle() {
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL")) {
            val result = JumpCore().calculateTemporal(protocol, 0, 200_000, 700_000)
            assertEquals(30.64578125, result.heightCm, 1e-10)
            assertEquals(500.0, result.flightTimeMs, 1e-10)
            assertEquals(2.4516625, result.takeoffVelocityMps, 1e-10)
            assertEquals(200.0, result.timeToTakeoffMs, 1e-10)
            assertEquals(1.5322890625, result.rsiMod, 1e-10)
            assertEquals(0.0, result.contactTimeMs, 1e-10)
            assertEquals(0.0, result.rsi, 1e-10)
            assertTrue(result.hasHeightCm)
            assertTrue(result.hasFlightTimeMs)
            assertTrue(result.hasTakeoffVelocityMps)
            assertTrue(result.hasTimeToTakeoffMs)
            assertTrue(result.hasRsiMod)
            assertFalse(result.hasContactTimeMs)
            assertFalse(result.hasRsi)
        }
    }

    @Test fun squatJumpIgnoresFirstEventIncludingNegative() {
        for (firstEventUs in listOf(0L, -123L)) {
            val result = JumpCore().calculateTemporal("SJ", firstEventUs, 200_000, 700_000)
            assertEquals(30.64578125, result.heightCm, 1e-10)
            assertEquals(500.0, result.flightTimeMs, 1e-10)
            assertEquals(2.4516625, result.takeoffVelocityMps, 1e-10)
            assertEquals(0.0, result.timeToTakeoffMs, 1e-10)
            assertEquals(0.0, result.rsiMod, 1e-10)
            assertEquals(0.0, result.contactTimeMs, 1e-10)
            assertEquals(0.0, result.rsi, 1e-10)
            assertTrue(result.hasHeightCm)
            assertTrue(result.hasFlightTimeMs)
            assertTrue(result.hasTakeoffVelocityMps)
            assertFalse(result.hasTimeToTakeoffMs)
            assertFalse(result.hasRsiMod)
            assertFalse(result.hasContactTimeMs)
            assertFalse(result.hasRsi)
        }
    }

    @Test fun dropJumpReportsRsiAndContactWithSharedFlightOracle() {
        val result = JumpCore().calculateTemporal("DROP_JUMP", 0, 200_000, 700_000)
        assertEquals(30.64578125, result.heightCm, 1e-10)
        assertEquals(500.0, result.flightTimeMs, 1e-10)
        assertEquals(2.4516625, result.takeoffVelocityMps, 1e-10)
        assertEquals(200.0, result.contactTimeMs, 1e-10)
        assertEquals(1.5322890625, result.rsi, 1e-10)
        assertEquals(0.0, result.timeToTakeoffMs, 1e-10)
        assertEquals(0.0, result.rsiMod, 1e-10)
        assertTrue(result.hasHeightCm)
        assertTrue(result.hasFlightTimeMs)
        assertTrue(result.hasTakeoffVelocityMps)
        assertTrue(result.hasContactTimeMs)
        assertTrue(result.hasRsi)
        assertFalse(result.hasTimeToTakeoffMs)
        assertFalse(result.hasRsiMod)
    }

    @Test fun ptsOriginShiftPreservesAllTemporalMetrics() {
        val offset = 15_000_000L
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL", "SJ", "DROP_JUMP")) {
            val base = JumpCore().calculateTemporal(protocol, 0, 200_000, 700_000)
            val shifted = JumpCore().calculateTemporal(protocol, offset, 200_000 + offset, 700_000 + offset)
            assertEquals(base.heightCm, shifted.heightCm, 1e-10)
            assertEquals(base.flightTimeMs, shifted.flightTimeMs, 1e-10)
            assertEquals(base.takeoffVelocityMps, shifted.takeoffVelocityMps, 1e-10)
            assertEquals(base.timeToTakeoffMs, shifted.timeToTakeoffMs, 1e-10)
            assertEquals(base.rsiMod, shifted.rsiMod, 1e-10)
            assertEquals(base.contactTimeMs, shifted.contactTimeMs, 1e-10)
            assertEquals(base.rsi, shifted.rsi, 1e-10)
            assertEquals(base.hasHeightCm, shifted.hasHeightCm)
            assertEquals(base.hasFlightTimeMs, shifted.hasFlightTimeMs)
            assertEquals(base.hasTakeoffVelocityMps, shifted.hasTakeoffVelocityMps)
            assertEquals(base.hasTimeToTakeoffMs, shifted.hasTimeToTakeoffMs)
            assertEquals(base.hasRsiMod, shifted.hasRsiMod)
            assertEquals(base.hasContactTimeMs, shifted.hasContactTimeMs)
            assertEquals(base.hasRsi, shifted.hasRsi)
        }
    }

    @Test fun minimumFiftyMsFlightAcceptedAndFortyNineMsRejected() {
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL", "SJ", "DROP_JUMP")) {
            val accepted = JumpCore().calculateTemporal(protocol, 0, 200_000, 250_000)
            assertEquals(50.0, accepted.flightTimeMs, 1e-10)
            assertTrue(accepted.hasFlightTimeMs)
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 0, 200_000, 249_999)
            }
        }
    }

    @Test fun invalidTemporalOrderingAndProtocolsFailClosed() {
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL", "SJ", "DROP_JUMP")) {
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 0, 700_000, 200_000)
            }
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 0, 200_000, 200_000)
            }
        }
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL")) {
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 200_000, 200_000, 700_000)
            }
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 300_000, 200_000, 700_000)
            }
        }
        assertFailsWith<InvalidTemporalJump> {
            JumpCore().calculateTemporal("DROP_JUMP", 200_000, 200_000, 700_000)
        }
        assertFailsWith<InvalidTemporalJump> {
            JumpCore().calculateTemporal("DROP_JUMP", 300_000, 200_000, 700_000)
        }
        assertFailsWith<InvalidTemporalJump> {
            JumpCore().calculateTemporal("CMJ", 0, -1, 700_000)
        }
        assertFailsWith<InvalidTemporalJump> {
            JumpCore().calculateTemporal("CMJ", 0, 200_000, -1)
        }
        for (protocol in listOf("CMJ", "ABALAKOV", "UNILATERAL", "DROP_JUMP")) {
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, -1, 200_000, 700_000)
            }
        }
        for (protocol in listOf("HORIZONTAL", "ASYMMETRY", "UNKNOWN")) {
            assertFailsWith<InvalidTemporalJump> {
                JumpCore().calculateTemporal(protocol, 0, 200_000, 700_000)
            }
        }
    }
}
