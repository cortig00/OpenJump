package com.openjump.app.ui.athletes

import com.openjump.app.data.AthleteHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Test

class AthleteProfileHistoryTest {

    @Test
    fun `merge uses a deterministic cross-family order at equal timestamps and ids`() {
        val jump = AthleteHistoryPage(
            id = 7,
            dateTime = 100,
            kind = "JUMP",
            athleteId = 1,
            athleteName = "Athlete",
        )
        val encoder = AthleteHistoryPage(
            id = 7,
            dateTime = 100,
            kind = "ENCODER",
            athleteId = 1,
            athleteName = "Athlete",
        )

        val page = mergeAthleteHistory(listOf(jump), listOf(encoder), limit = 2)

        assertEquals(listOf("ENCODER", "JUMP"), page.map { it.kind })
    }

    @Test
    fun `non-empty activity suppresses the empty profile state`() {
        val jump = AthleteHistoryPage(
            id = 3,
            dateTime = 100,
            kind = "JUMP",
            athleteId = 1,
            athleteName = "Athlete",
        )

        assertEquals(false, shouldShowProfileEmptyState(total = 0, history = listOf(jump), loading = false))
        assertEquals(false, shouldShowProfileEmptyState(total = 0, history = emptyList(), loading = true))
        assertEquals(true, shouldShowProfileEmptyState(total = 0, history = emptyList(), loading = false))
    }

    @Test
    fun `merge is bounded after combining both families`() {
        val jumps = (1L..20L).map { id ->
            AthleteHistoryPage(id, id, "JUMP", 1, "Athlete")
        }
        val encoder = (21L..40L).map { id ->
            AthleteHistoryPage(id, id, "ENCODER", 1, "Athlete")
        }

        val page = mergeAthleteHistory(jumps, encoder, limit = 20)

        assertEquals(20, page.size)
        assertEquals(40L, page.first().id)
        assertEquals(21L, page.last().id)
    }
}
