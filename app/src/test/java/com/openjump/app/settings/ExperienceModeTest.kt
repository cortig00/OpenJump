package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceModeTest {
    @Test
    fun missingOrUnknownPreferenceDefaultsToPersonal() {
        assertEquals(ExperienceMode.PERSONAL, ExperienceModeStore.decode(null))
        assertEquals(ExperienceMode.PERSONAL, ExperienceModeStore.decode("OLD_ROLE"))
    }

    @Test
    fun storedModesAreReversible() {
        assertEquals(ExperienceMode.PERSONAL, ExperienceModeStore.decode(ExperienceMode.PERSONAL.name))
        assertEquals(ExperienceMode.COACH, ExperienceModeStore.decode(ExperienceMode.COACH.name))
        assertEquals(ExperienceMode.COACH, ExperienceModeStore.decode("BOTH"))
    }
}
