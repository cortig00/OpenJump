package com.openjump.app.ui.components

import com.openjump.app.data.AthleteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AthleteSelectorTest {
    @Test
    fun searchReturnsOnlyActiveMatchingAthletes() {
        val athletes = listOf(
            AthleteEntity(id = 1, displayName = "Ana García"),
            AthleteEntity(id = 2, displayName = "Bruno", archivedAt = 1L),
            AthleteEntity(id = 3, displayName = "Diana"),
        )

        assertEquals(listOf(1L), filterActiveAthletes(athletes, "garcía").map { it.id })
        assertEquals(listOf(1L, 3L), filterActiveAthletes(athletes, "").map { it.id })
    }
}
