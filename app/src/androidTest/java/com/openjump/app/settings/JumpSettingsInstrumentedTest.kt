package com.openjump.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jump marking preference contract. Uses a dedicated sandbox file and never clears or writes the
 * production `openjump_settings` file.
 */
@RunWith(AndroidJUnit4::class)
class JumpSettingsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences(SANDBOX_NAME, Context.MODE_PRIVATE)

    @Before
    @After
    fun resetSandbox() {
        prefs.edit().clear().commit()
    }

    @Test
    fun absentValueReadsOffForEveryNewInstance() {
        assertFalse(JumpSettings.DEFAULT_MARK_MOVEMENT_START)
        assertFalse(JumpSettings(prefs).markMovementStart)
        assertFalse(JumpSettings(prefs).markMovementStart)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun enabledValueSurvivesANewInstance() {
        JumpSettings(prefs).markMovementStart = true

        assertTrue(JumpSettings(prefs).markMovementStart)
        assertTrue(prefs.getBoolean(JumpSettings.KEY_MARK_MOVEMENT_START, false))
    }

    @Test
    fun disabledValuePersistsExplicitly() {
        val settings = JumpSettings(prefs)
        settings.markMovementStart = true
        assertTrue(settings.markMovementStart)

        JumpSettings(prefs).markMovementStart = false

        assertFalse(settings.markMovementStart)
        assertFalse(JumpSettings(prefs).markMovementStart)
        assertFalse(prefs.getBoolean(JumpSettings.KEY_MARK_MOVEMENT_START, true))
    }

    @Test
    fun corruptOrWronglyTypedValueFallsBackToOffWithoutCrashing() {
        prefs.edit().putString(JumpSettings.KEY_MARK_MOVEMENT_START, "true").commit()
        assertFalse(JumpSettings(prefs).markMovementStart)

        prefs.edit().putInt(JumpSettings.KEY_MARK_MOVEMENT_START, 1).commit()
        assertFalse(JumpSettings(prefs).markMovementStart)

        // A later explicit write replaces the corrupt entry with a real boolean.
        JumpSettings(prefs).markMovementStart = true
        assertTrue(JumpSettings(prefs).markMovementStart)
    }

    @Test
    fun unrelatedPreferencesAreNeverTouched() {
        prefs.edit()
            .putString("unrelated_string", "retain")
            .putInt("unrelated_int", 7)
            .putBoolean("another_flag", true)
            .commit()
        val settings = JumpSettings(prefs)

        settings.markMovementStart = true
        settings.markMovementStart = false

        assertEquals("retain", prefs.getString("unrelated_string", null))
        assertEquals(7, prefs.getInt("unrelated_int", -1))
        assertTrue(prefs.getBoolean("another_flag", false))
        assertEquals(
            setOf("unrelated_string", "unrelated_int", "another_flag", JumpSettings.KEY_MARK_MOVEMENT_START),
            prefs.all.keys,
        )
    }

    private companion object {
        const val SANDBOX_NAME = "jump-marking-settings-sandbox"
    }
}
