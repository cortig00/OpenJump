package com.openjump.app.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JumpMathTest {

    // Caso conocido: flightTime = 0.600 s
    //  - altura = 9.80665 * 0.600^2 / 8 = 0.441299... m = 44.13 cm
    //  - velocidad de despegue = 9.80665 * 0.600 / 2 = 2.942 m/s
    @Test
    fun `caso conocido - altura y velocidad de despegue`() {
        val r = JumpMath.compute(
            movementStartUs = 0L,
            takeoffUs = 1_000_000L,
            landingUs = 1_600_000L, // 600 ms de vuelo
        )
        assertEquals(0.6, r.flightTimeS, 1e-9)
        assertEquals(44.13, r.heightCm, 0.01)
        assertEquals(2.942, r.takeoffVelocityMS, 0.001)
        assertEquals(600.0, r.flightTimeMs, 0.001)
    }

    @Test
    fun `caso conocido - rsi mod`() {
        // altura 44.13 cm = 0.4413 m, timeToTakeoff 684 ms = 0.684 s
        val r = JumpMath.compute(
            movementStartUs = 100_000L,
            takeoffUs = 784_000L,      // 684 ms después del inicio
            landingUs = 1_384_000L,    // 600 ms después del despegue
        )
        assertEquals(0.684, r.timeToTakeoffS, 1e-6)
        assertEquals(0.4413, r.heightM, 0.01)
        assertEquals(0.65, r.rsiMod, 0.01)
    }

    @Test
    fun `aterrizaje anterior al despegue lanza error`() {
        assertThrows(JumpMath.InvalidTimes::class.java) {
            JumpMath.compute(0L, 1_000_000L, 900_000L)
        }
    }

    @Test
    fun `aterrizaje igual al despegue lanza error`() {
        assertThrows(JumpMath.InvalidTimes::class.java) {
            JumpMath.compute(0L, 1_000_000L, 1_000_000L)
        }
    }

    @Test
    fun `despegue anterior al inicio lanza error`() {
        assertThrows(JumpMath.InvalidTimes::class.java) {
            JumpMath.compute(1_000_000L, 900_000L, 2_000_000L)
        }
    }

    @Test
    fun `tiempo de vuelo demasiado pequeno lanza error`() {
        // 10 ms < umbral de 50 ms
        assertThrows(JumpMath.InvalidTimes::class.java) {
            JumpMath.compute(0L, 1_000_000L, 1_010_000L)
        }
    }

    @Test
    fun `marcadores faltantes lanzan error`() {
        assertThrows(JumpMath.InvalidTimes::class.java) { JumpMath.compute(null, 1L, 2L) }
        assertThrows(JumpMath.InvalidTimes::class.java) { JumpMath.compute(1L, null, 2L) }
        assertThrows(JumpMath.InvalidTimes::class.java) { JumpMath.compute(1L, 2L, null) }
    }

    @Test
    fun `time to takeoff cero lanza error`() {
        assertThrows(JumpMath.InvalidTimes::class.java) {
            JumpMath.compute(1_000_000L, 1_000_000L, 1_600_000L)
        }
    }

    @Test
    fun `conversion microsegundos a segundos`() {
        assertEquals(0.0, JumpMath.usToSeconds(0L), 1e-9)
        assertEquals(1.204167, JumpMath.usToSeconds(1_204_167L), 1e-9)
        assertEquals(1.7625, JumpMath.usToSeconds(1_762_500L), 1e-9)
        assertEquals(2.363333, JumpMath.usToSeconds(2_363_333L), 1e-9)
        assertEquals(1.0, JumpMath.usToSeconds(1_000_000L), 1e-9)
    }

    @Test
    fun `redondeo a dos decimales del ejemplo de la spec`() {
        // Espejo del ejemplo del enunciado: 44.3 cm · flightTime 601 ms
        val r = JumpMath.compute(0L, 1_000_000L, 1_601_000L)
        assertEquals(44.3, r.heightCm, 0.1)
        assertEquals(601.0, r.flightTimeMs, 0.001)
    }
}
