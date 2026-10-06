package com.openjump.apple

import com.openjump.app.math.JumpMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
