package com.openjump.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Concrete presentation unit for short lengths (canonical value is centimetres). */
enum class ShortLengthUnit(val id: String) {
    CENTIMETER("cm"), INCH("in");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class HorizontalDistanceUnit(val id: String) {
    METER("m"), FOOT("ft");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class EncoderDisplacementUnit(val id: String) {
    METER("m"), MILLIMETER("mm"), CENTIMETER("cm"), INCH("in");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class MassUnit(val id: String) {
    KILOGRAM("kg"), POUND("lb");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class SpeedUnit(val id: String) {
    METERS_PER_SECOND("m/s"), FEET_PER_SECOND("ft/s");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class JumpTimingUnit(val id: String) {
    MILLISECOND("ms"), SECOND("s");
    companion object { fun decode(id: String?) = entries.firstOrNull { it.id == id } }
}

data class UnitProfile(
    val shortLength: ShortLengthUnit = ShortLengthUnit.CENTIMETER,
    val horizontalDistance: HorizontalDistanceUnit = HorizontalDistanceUnit.METER,
    val encoderDisplacement: EncoderDisplacementUnit = EncoderDisplacementUnit.METER,
    val mass: MassUnit = MassUnit.KILOGRAM,
    val speed: SpeedUnit = SpeedUnit.METERS_PER_SECOND,
    val jumpTiming: JumpTimingUnit = JumpTimingUnit.MILLISECOND,
) {
    fun preset(): UnitPreset = when (this) {
        METRIC -> UnitPreset.METRIC
        UNITED_STATES -> UnitPreset.UNITED_STATES
        else -> UnitPreset.CUSTOM
    }

    companion object {
        val METRIC = UnitProfile()
        val UNITED_STATES = UnitProfile(
            shortLength = ShortLengthUnit.INCH,
            horizontalDistance = HorizontalDistanceUnit.FOOT,
            encoderDisplacement = EncoderDisplacementUnit.INCH,
            mass = MassUnit.POUND,
            speed = SpeedUnit.FEET_PER_SECOND,
            jumpTiming = JumpTimingUnit.MILLISECOND,
        )
    }
}

enum class UnitPreset { METRIC, UNITED_STATES, CUSTOM }

/** Legacy binary enum retained only for source compatibility and old preference decoding. */
enum class UnitSystem { METRIC, US_CUSTOMARY }

/** User override for the existing light/dark OpenJump themes. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
    companion object {
        internal fun decode(value: String?): ThemeMode =
            value?.let { stored -> entries.firstOrNull { it.name == stored } } ?: SYSTEM
    }
}

data class DisplayPreferences(
    val unitProfile: UnitProfile = UnitProfile.METRIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    /** Compatibility projection for callers not yet migrated. */
    val unitSystem: UnitSystem
        get() = if (unitProfile == UnitProfile.UNITED_STATES) UnitSystem.US_CUSTOMARY else UnitSystem.METRIC
}

/** Pure, versioned codec for the complete profile. */
object DisplayPreferencesCodec {
    const val VERSION = 1
    const val KEY_VERSION = "display_unit_profile_version"
    const val KEY_SHORT_LENGTH = "display_unit_short_length"
    const val KEY_HORIZONTAL_DISTANCE = "display_unit_horizontal_distance"
    const val KEY_ENCODER_DISPLACEMENT = "display_unit_encoder_displacement"
    const val KEY_MASS = "display_unit_mass"
    const val KEY_SPEED = "display_unit_speed"
    const val KEY_JUMP_TIMING = "display_unit_jump_timing"
    const val KEY_LEGACY_SYSTEM = "display_unit_system"
    const val KEY_THEME = "display_theme_mode"

    data class Snapshot(val values: Map<String, String?>) {
        operator fun get(key: String): String? = values[key]
    }

    fun decode(snapshot: Snapshot): UnitProfile {
        val marker = snapshot[KEY_VERSION]
        if (marker == null) {
            return when (snapshot[KEY_LEGACY_SYSTEM]) {
                UnitSystem.US_CUSTOMARY.name -> UnitProfile.UNITED_STATES
                else -> UnitProfile.METRIC
            }
        }
        if (marker != VERSION.toString()) return UnitProfile.METRIC
        val short = ShortLengthUnit.decode(snapshot[KEY_SHORT_LENGTH]) ?: return UnitProfile.METRIC
        val distance = HorizontalDistanceUnit.decode(snapshot[KEY_HORIZONTAL_DISTANCE]) ?: return UnitProfile.METRIC
        val displacement = EncoderDisplacementUnit.decode(snapshot[KEY_ENCODER_DISPLACEMENT]) ?: return UnitProfile.METRIC
        val mass = MassUnit.decode(snapshot[KEY_MASS]) ?: return UnitProfile.METRIC
        val speed = SpeedUnit.decode(snapshot[KEY_SPEED]) ?: return UnitProfile.METRIC
        val timing = JumpTimingUnit.decode(snapshot[KEY_JUMP_TIMING]) ?: return UnitProfile.METRIC
        return UnitProfile(short, distance, displacement, mass, speed, timing)
    }

    fun encode(profile: UnitProfile): Map<String, String> = mapOf(
        KEY_VERSION to VERSION.toString(),
        KEY_SHORT_LENGTH to profile.shortLength.id,
        KEY_HORIZONTAL_DISTANCE to profile.horizontalDistance.id,
        KEY_ENCODER_DISPLACEMENT to profile.encoderDisplacement.id,
        KEY_MASS to profile.mass.id,
        KEY_SPEED to profile.speed.id,
        KEY_JUMP_TIMING to profile.jumpTiming.id,
        KEY_LEGACY_SYSTEM to if (profile == UnitProfile.UNITED_STATES) UnitSystem.US_CUSTOMARY.name else UnitSystem.METRIC.name,
    )
}

class DisplayPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DisplayPreferences> = _state.asStateFlow()

    @Synchronized
    fun setUnitProfile(profile: UnitProfile) {
        val editor = preferences.edit()
        DisplayPreferencesCodec.encode(profile).forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
        _state.value = _state.value.copy(unitProfile = profile)
    }

    /** Atomically transforms the latest complete tuple, avoiding stale Compose callbacks. */
    @Synchronized
    fun updateUnitProfile(transform: (UnitProfile) -> UnitProfile) {
        setUnitProfile(transform(_state.value.unitProfile))
    }

    /** Compatibility entry point; new UI always writes complete profiles. */
    fun setUnitSystem(unitSystem: UnitSystem) = setUnitProfile(
        if (unitSystem == UnitSystem.US_CUSTOMARY) UnitProfile.UNITED_STATES else UnitProfile.METRIC,
    )

    fun setThemeMode(themeMode: ThemeMode) {
        preferences.edit().putString(DisplayPreferencesCodec.KEY_THEME, themeMode.name).apply()
        _state.value = _state.value.copy(themeMode = themeMode)
    }

    private fun read(): DisplayPreferences {
        val snapshot = DisplayPreferencesCodec.Snapshot(
            listOf(
                DisplayPreferencesCodec.KEY_VERSION,
                DisplayPreferencesCodec.KEY_SHORT_LENGTH,
                DisplayPreferencesCodec.KEY_HORIZONTAL_DISTANCE,
                DisplayPreferencesCodec.KEY_ENCODER_DISPLACEMENT,
                DisplayPreferencesCodec.KEY_MASS,
                DisplayPreferencesCodec.KEY_SPEED,
                DisplayPreferencesCodec.KEY_JUMP_TIMING,
                DisplayPreferencesCodec.KEY_LEGACY_SYSTEM,
                DisplayPreferencesCodec.KEY_THEME,
            ).associateWith { preferences.getString(it, null) },
        )
        return DisplayPreferences(
            unitProfile = DisplayPreferencesCodec.decode(snapshot),
            themeMode = ThemeMode.decode(snapshot[DisplayPreferencesCodec.KEY_THEME]),
        )
    }

    companion object { private const val PREFERENCES_NAME = "openjump_settings" }
}
