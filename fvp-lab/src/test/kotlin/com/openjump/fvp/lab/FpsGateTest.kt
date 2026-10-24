package com.openjump.fvp.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FpsGateTest {

    @Test
    fun gate_120_isAllow() {
        assertEquals(FpsGateDecision.ALLOW, FpsGate.evaluate(120.0))
        assertEquals(FpsGateDecision.ALLOW, FpsGate.evaluate(240.0))
    }

    @Test
    fun gate_60_isConditional() {
        assertEquals(FpsGateDecision.CONDITIONAL, FpsGate.evaluate(60.0))
        assertEquals(FpsGateDecision.CONDITIONAL, FpsGate.evaluate(119.9))
    }

    @Test
    fun gate_30_isBlocked() {
        assertEquals(FpsGateDecision.BLOCKED, FpsGate.evaluate(30.0))
        assertEquals(FpsGateDecision.BLOCKED, FpsGate.evaluate(59.9))
    }

    @Test
    fun gate_null_isUnknown() {
        assertEquals(null, FpsGate.evaluateOrNull(null))
    }

    @Test
    fun gate_invalid_throws() {
        for (bad in listOf(0.0, -60.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                FpsGate.evaluate(bad)
                fail("expected IllegalArgumentException for fps=$bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("fps"))
            }
        }
    }
}
