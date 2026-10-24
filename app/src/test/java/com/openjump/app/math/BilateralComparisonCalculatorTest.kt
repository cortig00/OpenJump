package com.openjump.app.math

import com.openjump.app.protocol.BilateralAggregation
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BilateralComparisonCalculatorTest {
    @Test
    fun `best and mean use each side and asymmetry`() {
        val comparison = comparison(3, listOf(20.0, 30.0, 25.0), listOf(10.0, 20.0, 15.0))
        val best = BilateralComparisonCalculator.summarize(comparison, BilateralAggregation.BEST)
        assertEquals(30.0, best.leftValue, 0.0)
        assertEquals(20.0, best.rightValue, 0.0)
        assertEquals(33.3333, best.asymmetryPercent, 0.001)
        assertEquals(MeasurementSide.LEFT, best.higherSide)
        val mean = BilateralComparisonCalculator.mean(comparison)
        assertEquals(25.0, mean.leftValue, 0.0)
        assertEquals(15.0, mean.rightValue, 0.0)
    }

    @Test
    fun `equal sides have no winner and zero asymmetry`() {
        val result = BilateralComparisonCalculator.best(comparison(1, listOf(24.0), listOf(24.0)))
        assertEquals(0.0, result.asymmetryPercent, 0.0)
        assertNull(result.higherSide)
    }

    @Test
    fun `formula rejects non-positive and non-finite values`() {
        assertThrows(IllegalArgumentException::class.java) { BilateralComparisonCalculator.asymmetryPercentage(0.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { BilateralComparisonCalculator.asymmetryPercentage(Double.NaN, 10.0) }
    }

    @Test
    fun `comparison validates bounds and order`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            comparison(1, listOf(20.0, 21.0), listOf(10.0, 11.0))
        }
        val valid = comparison(5, List(5) { 20.0 }, List(5) { 10.0 })
        assertEquals(10, valid.attempts.size)
    }

    private fun comparison(n: Int, left: List<Double>, right: List<Double>): BilateralComparison {
        val attempts = (left.mapIndexed { index, value -> snapshot(MeasurementSide.LEFT, index + 1, value) } +
            right.mapIndexed { index, value -> snapshot(MeasurementSide.RIGHT, index + 1, value) })
        return BilateralComparison("root", 1L, n, attempts)
    }

    private fun snapshot(side: MeasurementSide, ordinal: Int, height: Double): BilateralAttemptSnapshot {
        val marks = listOf(
            EventType.MOVEMENT_START to 100L,
            EventType.TAKEOFF to 200L,
            EventType.LANDING to 400L,
        ).associate { (type, pts) ->
            val key = EventKey(type)
            key to EventMark(key, pts, pts.toInt())
        }
        val draft = MeasurementDraft("$side-$ordinal", ProtocolId.UNILATERAL, 1L, setup = ProtocolSetup(side = side), events = marks)
        val result = ProtocolResult(
            ProtocolId.UNILATERAL,
            MetricValue(MetricKey.HEIGHT_CM, height, MetricUnit.CENTIMETER),
            emptyList(),
        )
        return BilateralAttemptSnapshot(side, ordinal, draft, result, 30)
    }
}
