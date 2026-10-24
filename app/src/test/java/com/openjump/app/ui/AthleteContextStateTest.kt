package com.openjump.app.ui

import com.openjump.app.data.AthleteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AthleteContextStateTest {
    private val one = AthleteEntity(id = 1L, displayName = "One")
    private val two = AthleteEntity(id = 2L, displayName = "Two")

    @Test
    fun personalProfileUsesOnlyValidSelectedAthlete() {
        assertEquals(2L, personalProfileAthleteId(listOf(one, two), 2L))
    }

    @Test
    fun personalProfileHasNoFallbackForAbsentStaleOrSoleSurvivorSelection() {
        assertNull(personalProfileAthleteId(listOf(one, two), null))
        assertNull(personalProfileAthleteId(listOf(one, two), 99L))
        assertNull(personalProfileAthleteId(listOf(one), null))
    }
}
