package com.openjump.app.ui.history

import com.openjump.app.data.AthleteHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPagingTest {
    @Test
    fun `merge uses kind as deterministic tie breaker for equal timestamp and id`() {
        val jump = AthleteHistoryPage(9L, 100L, "JUMP", null, null)
        val encoder = AthleteHistoryPage(9L, 100L, "ENCODER", null, null)
        val result = mergeAthleteHistory(listOf(jump), listOf(encoder), 30)
        assertEquals(listOf("ENCODER", "JUMP"), result.map { it.kind })
    }

    @Test
    fun `merge remains bounded even when both DAO batches are large`() {
        val jumps = (1L..100L).map { AthleteHistoryPage(it, 1_000L - it, "JUMP", null, null) }
        val encoders = (101L..200L).map { AthleteHistoryPage(it, 1_000L - it, "ENCODER", null, null) }
        assertEquals(30, mergeAthleteHistory(jumps, encoders, 30).size)
    }

    @Test
    fun `full merged page keeps continuation even when neither source filled its batch`() {
        val jumps = (1L..20L).map { AthleteHistoryPage(it, 100L - it, "JUMP", null, null) }
        val encoders = (21L..40L).map { AthleteHistoryPage(it, 100L - it, "ENCODER", null, null) }
        val emitted = mergeAthleteHistory(jumps, encoders, 30)

        assertEquals(true, historyCanLoadMore(emitted.size, 30))
    }

    @Test
    fun `filters count only active criteria`() {
        assertEquals(0, HistoryFilters().activeCount)
        assertEquals(3, HistoryFilters(search = "ana", family = HistoryFamily.JUMP, metricMin = 1.0).activeCount)
        assertEquals(1, HistoryFilters(unassignedOnly = true).activeCount)
    }
}
