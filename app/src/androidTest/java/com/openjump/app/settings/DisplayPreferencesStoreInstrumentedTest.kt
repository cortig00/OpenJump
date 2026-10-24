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
class DisplayPreferencesStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun completeProfileIsAtomicAndRestoresFromFreshStore() {
        val profile = UnitProfile(
            ShortLengthUnit.INCH,
            HorizontalDistanceUnit.FOOT,
            EncoderDisplacementUnit.MILLIMETER,
            MassUnit.POUND,
            SpeedUnit.FEET_PER_SECOND,
            JumpTimingUnit.SECOND,
        )
        DisplayPreferencesStore(context).setUnitProfile(profile)
        assertEquals(profile, DisplayPreferencesStore(context).state.value.unitProfile)
        val prefs = context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE)
        assertEquals("1", prefs.getString(DisplayPreferencesCodec.KEY_VERSION, null))
        listOf(
            DisplayPreferencesCodec.KEY_SHORT_LENGTH,
            DisplayPreferencesCodec.KEY_HORIZONTAL_DISTANCE,
            DisplayPreferencesCodec.KEY_ENCODER_DISPLACEMENT,
            DisplayPreferencesCodec.KEY_MASS,
            DisplayPreferencesCodec.KEY_SPEED,
            DisplayPreferencesCodec.KEY_JUMP_TIMING,
        ).forEach { requireNotNull(prefs.getString(it, null)) }
    }

    @Test
    fun rapidTransformsUseLatestCompleteTuple() {
        val store = DisplayPreferencesStore(context)
        store.updateUnitProfile { it.copy(shortLength = ShortLengthUnit.INCH) }
        store.updateUnitProfile { it.copy(mass = MassUnit.POUND) }
        assertEquals(ShortLengthUnit.INCH, store.state.value.unitProfile.shortLength)
        assertEquals(MassUnit.POUND, store.state.value.unitProfile.mass)
    }

    @Test
    fun markedPartialProfileDoesNotUseLegacyFallback() {
        context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE).edit()
            .putString(DisplayPreferencesCodec.KEY_VERSION, "1")
            .putString(DisplayPreferencesCodec.KEY_LEGACY_SYSTEM, "US_CUSTOMARY")
            .putString(DisplayPreferencesCodec.KEY_SHORT_LENGTH, "in")
            .commit()
        assertEquals(UnitProfile.METRIC, DisplayPreferencesStore(context).state.value.unitProfile)
    }
}
