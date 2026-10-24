package com.openjump.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.encoder.EncoderExercise
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated test preferences only; never clear or modify openjump_settings/Room. */
@RunWith(AndroidJUnit4::class)
class EncoderSettingsLoadInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("encoder-default-loads-storage-test", Context.MODE_PRIVATE)

    @Before @After fun resetSandbox() { prefs.edit().clear().commit() }

    @Test fun eachExercisePersistsIndependentlyAndASeparateSettingsInstanceRestoresExactValues() {
        val settings = EncoderSettings(prefs)
        EncoderExercise.entries.forEach { assertEquals(0.0, settings.defaultLoadKg(it), 0.0) }
        val values = listOf(131.54, 80.25, 45.359237, 0.12345678912345678)
        EncoderExercise.entries.zip(values).forEach { (exercise, value) -> settings.setDefaultLoadKg(exercise, value) }
        EncoderExercise.entries.zip(values).forEach { (exercise, value) ->
            assertEquals(value.toRawBits(), EncoderSettings(prefs).defaultLoadKg(exercise).toRawBits())
        }
    }

    @Test fun resettingLoadsPreservesPlateDiameterAndUnrelatedPreferences() {
        val settings = EncoderSettings(prefs)
        settings.defaultPlateDiameterCm = 40.0
        prefs.edit().putString("unrelated", "retain").commit()
        EncoderExercise.entries.forEach { settings.setDefaultLoadKg(it, 131.54) }
        settings.resetDefaultLoads()
        EncoderExercise.entries.forEach { assertEquals(0.0, EncoderSettings(prefs).defaultLoadKg(it), 0.0) }
        assertEquals(40.0, settings.defaultPlateDiameterCm, 0.0)
        assertEquals("retain", prefs.getString("unrelated", null))
    }

    @Test fun invalidWritesAreRejectedWithoutChangingAnyExercise() {
        val settings = EncoderSettings(prefs)
        val bench = EncoderExercise.BENCH_PRESS
        settings.setDefaultLoadKg(bench, 80.25)
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { settings.setDefaultLoadKg(bench, value) }
            assertEquals(80.25, settings.defaultLoadKg(bench), 0.0)
        }
        assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.SQUAT), 0.0)
    }

    @Test fun wronglyTypedOrCorruptPreferenceFallsBackWithoutCrashing() {
        val key = "encoder_default_load_kg_BENCH_PRESS"
        val settings = EncoderSettings(prefs)
        prefs.edit().putFloat(key, 80f).commit()
        assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
        prefs.edit().putString(key, "NaN").commit()
        assertEquals(0.0, settings.defaultLoadKg(EncoderExercise.BENCH_PRESS), 0.0)
    }
}
