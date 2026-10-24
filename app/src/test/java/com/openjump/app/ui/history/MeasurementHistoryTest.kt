package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementHistoryTest {
    @Test
    fun `merges jump and encoder measurements by newest date`() {
        val jumps = listOf(jump(id = 1, dateTime = 100), jump(id = 2, dateTime = 300))
        val encoder = listOf(encoder(id = 7, dateTime = 200))

        val result = mergeMeasurementHistory(jumps, encoder)

        assertEquals(listOf(300L, 200L, 100L), result.map { it.dateTime })
        assertTrue(result[0] is MeasurementHistoryItem.Jump)
        assertTrue(result[1] is MeasurementHistoryItem.Encoder)
    }

    @Test
    fun `limits recent activity after merging both measurement types`() {
        val result = mergeMeasurementHistory(
            jumps = listOf(jump(id = 1, dateTime = 400), jump(id = 2, dateTime = 100)),
            encoderSessions = listOf(encoder(id = 3, dateTime = 300), encoder(id = 4, dateTime = 200)),
            limit = 3,
        )

        assertEquals(listOf(400L, 300L, 200L), result.map { it.dateTime })
    }

    private fun jump(id: Long, dateTime: Long) = RecentMeasurement(
        id = id,
        dateTime = dateTime,
        protocolId = "CMJ",
        primaryMetricKey = "HEIGHT_CM",
        primaryMetricValue = 42.0,
        primaryMetricUnit = "CENTIMETER",
        side = null,
    )

    private fun encoder(id: Long, dateTime: Long) = RecentEncoderSession(
        id = id,
        dateTime = dateTime,
        exercise = "BENCH_PRESS",
        loadKg = 60.0,
        validRepetitions = 3,
        bestMcv = 0.71,
    )
}
