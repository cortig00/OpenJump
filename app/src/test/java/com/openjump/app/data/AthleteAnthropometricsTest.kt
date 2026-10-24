package com.openjump.app.data

import com.openjump.app.protocol.AthleteAnthropometrics
import org.junit.Assert.assertEquals
import org.junit.Test

class AthleteAnthropometricsTest {

    @Test
    fun `snapshot lookup selects the assigned athlete rather than list position`() {
        val athletes = listOf(
            AthleteEntity(id = 3L, displayName = "First", weightKg = 60.0, heightCm = 170.0),
            AthleteEntity(id = 9L, displayName = "Assigned", weightKg = 82.0, heightCm = 191.0),
        )

        assertEquals(
            AthleteAnthropometrics(weightKg = 82.0, heightCm = 191.0),
            athletes.anthropometricsSnapshotFor(9L),
        )
    }

    @Test
    fun `missing athlete produces an empty snapshot`() {
        assertEquals(
            AthleteAnthropometrics(),
            emptyList<AthleteEntity>().anthropometricsSnapshotFor(99L),
        )
    }
}
