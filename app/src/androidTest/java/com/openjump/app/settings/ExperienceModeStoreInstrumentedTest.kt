package com.openjump.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceModeStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToPersonalAndPersistsReversibleMode() {
        assertEquals(ExperienceMode.PERSONAL, ExperienceModeStore(context).mode.value)

        ExperienceModeStore(context).set(ExperienceMode.COACH)
        assertEquals(ExperienceMode.COACH, ExperienceModeStore(context).mode.value)

        assertEquals(ExperienceMode.COACH, ExperienceModeStore(context).mode.value)

        ExperienceModeStore(context).set(ExperienceMode.PERSONAL)
        assertEquals(ExperienceMode.PERSONAL, ExperienceModeStore(context).mode.value)
    }

    @Test
    fun legacyBothPreferenceBecomesCoachWithoutChangingOtherSettings() {
        val preferences = context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE)
        preferences.edit().putString("experience_mode", "BOTH").putLong("selected_athlete_id", 7L).commit()

        assertEquals(ExperienceMode.COACH, ExperienceModeStore(context).mode.value)
        assertEquals(ExperienceMode.COACH.name, preferences.getString("experience_mode", null))
        assertEquals(7L, preferences.getLong("selected_athlete_id", -1L))
    }
}
