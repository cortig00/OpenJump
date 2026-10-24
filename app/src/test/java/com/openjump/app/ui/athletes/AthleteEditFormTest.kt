package com.openjump.app.ui.athletes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AthleteEditFormTest {
    @Test
    fun `dirty state detects field and avatar changes`() {
        assertEquals(
            false,
            hasAthleteEdits(
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", null,
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", null,
            ),
        )
        assertEquals(
            true,
            hasAthleteEdits(
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", "avatar",
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", null,
            ),
        )
        assertEquals(
            true,
            hasAthleteEdits(
                "Ana 2", "2024-01-01", "F", "60.5", "170", "Note", null,
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", null,
            ),
        )
        assertEquals(
            true,
            hasAthleteEdits(
                "Ana", "2024-01-01", "F", "61.2", "170", "Note", null,
                "Ana", "2024-01-01", "F", "60.5", "170", "Note", null,
            ),
        )
    }

    @Test
    fun `birth date uses calendar epoch day and rejects malformed input`() {
        assertEquals(19723L, parseEpochDay("2024-01-01"))
        assertNull(parseEpochDay("2024-02-31"))
        assertNull(parseEpochDay("not-a-date"))
        assertNull(parseEpochDay(""))
    }
}
