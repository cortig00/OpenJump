package com.openjump.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryScopeTest {
    @Test
    fun personalScopeIsRepresentedAsAnAthleteQuery() {
        val query = HistoryFilters(athleteId = 42L).toQuery()
        assertEquals(42L, query.athleteId)
        assertFalse(query.unassignedOnly)
    }

    @Test
    fun coachDefaultRemainsGlobal() {
        val query = HistoryFilters().toQuery()
        assertEquals(null, query.athleteId)
        assertFalse(query.unassignedOnly)
    }
}
