package com.openjump.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRecordsTest {
    private fun row(
        id: Long, value: Double, protocol: String = "CMJ", side: String? = null,
        drop: Double? = null, date: Long = id, ordinal: Int = 0, bilateral: Boolean = false,
        metric: String = if (protocol == "DROP_JUMP") "RSI" else if (protocol == "HORIZONTAL") "DISTANCE_M" else "HEIGHT_CM",
        unit: String = if (protocol == "DROP_JUMP") "METER_PER_SECOND" else if (protocol == "HORIZONTAL") "METER" else "CENTIMETER",
    ) = PersonalRecordCandidate(id, ordinal, date, protocol, side, drop, metric, value, unit, bilateral)

    @Test fun separatesProtocolsSidesAndDropHeightsWithoutRounding() {
        val records = calculatePersonalRecords(listOf(
            row(1, 32.0), row(2, 34.0), row(3, 40.0, "SJ"),
            row(4, 21.0, "UNILATERAL", "LEFT"), row(5, 26.0, "UNILATERAL", "RIGHT"),
            row(6, 1.8, "DROP_JUMP", drop = 30.0),
            row(7, 2.0, "DROP_JUMP", drop = 40.0),
            row(8, 2.2, "DROP_JUMP", drop = 30.0),
            row(9, 2.3, "HORIZONTAL"),
        ))
        assertEquals(7, records.size)
        assertEquals(2L, records.single { it.series.protocolId.name == "CMJ" }.assessmentId)
        assertEquals(32.0, records.single { it.series.protocolId.name == "CMJ" }.previousBest!!, 0.0)
        assertEquals(8L, records.single { it.series.dropHeightCm == 30.0 }.assessmentId)
        assertEquals(7L, records.single { it.series.dropHeightCm == 40.0 }.assessmentId)
        assertEquals(setOf("LEFT", "RIGHT"), records.filter { it.series.protocolId.name == "UNILATERAL" }.map { it.series.side!!.name }.toSet())
    }

    @Test fun bilateralAttemptCanWinUnilateralAndRetainsOriginOrdinal() {
        val records = calculatePersonalRecords(listOf(
            row(1, 21.0, "UNILATERAL", "LEFT"),
            row(2, 24.0, "UNILATERAL", "LEFT", ordinal = 3, bilateral = true),
            row(2, 20.0, "UNILATERAL", "RIGHT", ordinal = 4, bilateral = true),
            row(3, 23.0, "UNILATERAL", "LEFT"),
        ))
        val left = records.single { it.series.side!!.name == "LEFT" }
        assertEquals(2L, left.assessmentId)
        assertEquals(3, left.attemptOrdinal)
        assertTrue(left.fromBilateral)
        assertEquals(21.0, left.previousBest!!, 0.0)
        assertNull(records.single { it.series.side!!.name == "RIGHT" }.previousBest)
    }

    @Test fun earliestEqualValueWinsAndIneligibleRowsAreExcluded() {
        val records = calculatePersonalRecords(listOf(
            row(10, 42.0, date = 100), row(9, 42.0, date = 100),
            row(20, 55.0, "UNILATERAL"), row(21, 3.0, "DROP_JUMP"),
            row(22, 5.0, "ASYMMETRY", metric = "ASYMMETRY_PERCENT", unit = "PERCENT"),
            row(23, Double.NaN), row(24, 0.0),
            row(25, 9.0, metric = "RSI"),
        ))
        assertEquals(1, records.size)
        assertEquals(9L, records.single().assessmentId)
        assertNull(records.single().previousBest)
        assertFalse(records.single().fromBilateral)
    }

    @Test fun twoHundredOldResultsAreConsideredAndDeletionRecomputes() {
        val rows = (1L..200L).map { row(it, it.toDouble()) }
        assertEquals(200L, calculatePersonalRecords(rows).single().assessmentId)
        assertEquals(199L, calculatePersonalRecords(rows.dropLast(1)).single().assessmentId)
    }
}
