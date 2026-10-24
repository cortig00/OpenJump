package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPreferencesTest {
    private fun snapshot(vararg pairs: Pair<String, String?>) =
        DisplayPreferencesCodec.Snapshot(pairs.toMap())

    @Test fun `legacy and absent preferences decode complete profiles`() {
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot()))
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot(DisplayPreferencesCodec.KEY_LEGACY_SYSTEM to "METRIC")))
        assertEquals(UnitProfile.UNITED_STATES, DisplayPreferencesCodec.decode(snapshot(DisplayPreferencesCodec.KEY_LEGACY_SYSTEM to "US_CUSTOMARY")))
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot(DisplayPreferencesCodec.KEY_LEGACY_SYSTEM to "IMPERIAL")))
    }

    @Test fun `version one round trips all stable ids`() {
        val profile = UnitProfile(
            ShortLengthUnit.INCH, HorizontalDistanceUnit.FOOT,
            EncoderDisplacementUnit.MILLIMETER, MassUnit.POUND,
            SpeedUnit.FEET_PER_SECOND, JumpTimingUnit.SECOND,
        )
        assertEquals(profile, DisplayPreferencesCodec.decode(snapshot(*DisplayPreferencesCodec.encode(profile).map { it.key to it.value }.toTypedArray())))
    }

    @Test fun `marked partial corrupt and unsupported profiles fall back wholly to metric`() {
        val encoded = DisplayPreferencesCodec.encode(UnitProfile.UNITED_STATES).toMutableMap()
        encoded.remove(DisplayPreferencesCodec.KEY_MASS)
        encoded[DisplayPreferencesCodec.KEY_LEGACY_SYSTEM] = "US_CUSTOMARY"
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot(*encoded.map { it.key to it.value }.toTypedArray())))
        encoded[DisplayPreferencesCodec.KEY_MASS] = "stone"
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot(*encoded.map { it.key to it.value }.toTypedArray())))
        encoded[DisplayPreferencesCodec.KEY_VERSION] = "99"
        assertEquals(UnitProfile.METRIC, DisplayPreferencesCodec.decode(snapshot(*encoded.map { it.key to it.value }.toTypedArray())))
    }

    @Test fun `preset is derived and custom transitions are exact`() {
        assertEquals(UnitPreset.METRIC, UnitProfile.METRIC.preset())
        assertEquals(UnitPreset.UNITED_STATES, UnitProfile.UNITED_STATES.preset())
        val custom = UnitProfile.METRIC.copy(encoderDisplacement = EncoderDisplacementUnit.MILLIMETER)
        assertEquals(UnitPreset.CUSTOM, custom.preset())
        assertEquals(UnitPreset.METRIC, custom.copy(encoderDisplacement = EncoderDisplacementUnit.METER).preset())
    }

    @Test fun `encoded custom safely mirrors metric legacy value and contains every field`() {
        val encoded = DisplayPreferencesCodec.encode(UnitProfile.METRIC.copy(jumpTiming = JumpTimingUnit.SECOND))
        assertEquals(UnitSystem.METRIC.name, encoded[DisplayPreferencesCodec.KEY_LEGACY_SYSTEM])
        assertEquals(8, encoded.size)
        assertTrue(encoded.keys.containsAll(listOf(
            DisplayPreferencesCodec.KEY_VERSION, DisplayPreferencesCodec.KEY_SHORT_LENGTH,
            DisplayPreferencesCodec.KEY_HORIZONTAL_DISTANCE, DisplayPreferencesCodec.KEY_ENCODER_DISPLACEMENT,
            DisplayPreferencesCodec.KEY_MASS, DisplayPreferencesCodec.KEY_SPEED,
            DisplayPreferencesCodec.KEY_JUMP_TIMING, DisplayPreferencesCodec.KEY_LEGACY_SYSTEM,
        )))
    }

    @Test fun `theme remains independent`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.decode("LIGHT"))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.LIGHT.isDark(true))
    }

    @Test fun `stable ids do not depend on enum ordinals`() {
        assertEquals(ShortLengthUnit.INCH, ShortLengthUnit.decode("in"))
        assertEquals(EncoderDisplacementUnit.CENTIMETER, EncoderDisplacementUnit.decode("cm"))
        assertEquals(JumpTimingUnit.SECOND, JumpTimingUnit.decode("s"))
    }
}
