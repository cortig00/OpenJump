package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPagingTest {
    @Test
    fun `merge uses kind as deterministic tie breaker for equal timestamp and id`() {
        val result = mergeMeasurementHistory(
            jumps = listOf(jump(9L, 100L)),
            encoderSessions = listOf(encoder(9L, 100L)),
            angleAnalyses = listOf(angle(9L, 100L)),
            limit = 30,
        )
        // ENCODER < JUMP < ANGLE. El orden NO es alfabético: el cursor keyset de saltos y encoder
        // depende de que Ángulos vaya última.
        assertEquals(listOf("ENCODER", "JUMP", "ANGLE"), result.map { it.kindKey() })
    }

    @Test
    fun `merge remains bounded even when both DAO batches are large`() {
        val jumps = (1L..100L).map { jump(it, 1_000L - it) }
        val encoders = (101L..200L).map { encoder(it, 1_000L - it) }
        assertEquals(30, mergeMeasurementHistory(jumps, encoders, 30).size)
    }

    @Test
    fun `full merged page keeps continuation even when neither source filled its batch`() {
        val jumps = (1L..20L).map { jump(it, 100L - it) }
        val encoders = (21L..40L).map { encoder(it, 100L - it) }
        val emitted = mergeMeasurementHistory(jumps, encoders, 30)

        assertEquals(true, historyCanLoadMore(emitted.size, 30))
    }

    @Test
    fun `filters count only active criteria`() {
        assertEquals(0, HistoryFilters().activeCount)
        assertEquals(3, HistoryFilters(search = "ana", family = HistoryFamily.JUMP, metricMin = 1.0).activeCount)
        assertEquals(1, HistoryFilters(unassignedOnly = true).activeCount)
        assertEquals(1, HistoryFilters(family = HistoryFamily.ANGLE).activeCount)
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
        exercise = "SQUAT",
        loadKg = 60.0,
        validRepetitions = 3,
        bestMcv = 0.6,
    )

    private fun angle(id: Long, dateTime: Long) = AngleAnalysisHistoryEntry(
        id = id,
        dateTime = dateTime,
        athleteId = 1L,
        athleteName = "Ana",
        markCount = 2,
        videoUri = "content://video/1",
    )
}
