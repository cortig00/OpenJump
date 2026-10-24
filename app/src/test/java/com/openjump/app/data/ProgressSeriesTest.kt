package com.openjump.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSeriesTest {
    private fun row(id: Long, value: Double, protocol: String = "UNILATERAL", side: String? = "LEFT",
        box: Double? = null, date: Long = id, ordinal: Int = 0, bilateral: Boolean = false,
    ) = PersonalRecordCandidate(
        id, ordinal, date, protocol, side, box,
        if (protocol == "DROP_JUMP") "RSI" else "HEIGHT_CM", value,
        if (protocol == "DROP_JUMP") "METER_PER_SECOND" else "CENTIMETER", bilateral,
    )

    @Test fun conditionsMatchPrAndBilateralAttemptsRetainTheirLink() {
        val input = listOf(
            row(1, 16.0), row(2, 22.0, ordinal = 0, bilateral = true),
            row(2, 23.0, ordinal = 1, bilateral = true), row(3, 20.0),
            row(4, 19.0, side = "RIGHT"),
            row(5, 1.4, "DROP_JUMP", null, box = 20.0),
            row(6, 1.9, "DROP_JUMP", null, box = 40.0),
            row(7, 1.5, "DROP_JUMP", null, box = 20.0),
        )
        val groups = buildProgressSeries(input)
        val left = groups.single { it.condition.side?.name == "LEFT" }
        assertEquals(listOf(1L, 2L, 2L, 3L), left.points.map { it.assessmentId })
        assertTrue(left.points[1].fromBilateral)
        assertEquals(1, left.points[2].attemptOrdinal)
        assertEquals(2L, left.personalBest.assessmentId)
        assertEquals(1, left.personalBest.attemptOrdinal)
        assertEquals(2, groups.count { it.condition.protocolId.name == "DROP_JUMP" })
        assertEquals(listOf(5L, 7L), groups.single { it.condition.dropHeightCm == 20.0 }.points.map { it.assessmentId })
        assertEquals(listOf(6L), groups.single { it.condition.dropHeightCm == 40.0 }.points.map { it.assessmentId })
    }

    @Test fun windowCapsPlotWithoutLosingHistoricalPrOrChronology() {
        val input = (1L..70).map { row(it, if (it == 1L) 99.0 else it.toDouble(), date = 1000 - it) }
        val series = buildProgressSeries(input, pointLimit = 5).single()
        assertEquals(70, series.totalCount)
        assertEquals(5, series.points.size)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), series.points.map { it.assessmentId })
        assertEquals(1L, series.personalBest.assessmentId)
        assertFalse(series.points.first().fromBilateral)
    }

    @Test fun timestampsDetermineSpacingAndSinglePointIsCentered() {
        assertEquals(listOf(0f, 0.1f, 1f), progressXPositions(listOf(0L, 10L, 100L)))
        assertEquals(listOf(0.5f), progressXPositions(listOf(42L)))
        assertEquals(listOf(0.5f, 0.5f), progressXPositions(listOf(42L, 42L)))
        assertEquals(emptyList<Float>(), progressXPositions(emptyList()))
    }

    @Test fun oneEligibleMeasurementHasOnePointAndFirstMarkAsBest() {
        val series = buildProgressSeries(listOf(row(5, 21.0))).single()
        assertEquals(1, series.totalCount)
        assertEquals(5L, series.points.single().assessmentId)
        assertEquals(5L, series.personalBest.assessmentId)
    }

    @Test fun invalidRowsCannotAppearInProgressEvenIfPresentInRoomProjection() {
        assertTrue(buildProgressSeries(listOf(row(1, 0.0), row(2, 4.0, side = null),
            row(3, 3.0, "DROP_JUMP", null, box = null))).isEmpty())
    }
}
