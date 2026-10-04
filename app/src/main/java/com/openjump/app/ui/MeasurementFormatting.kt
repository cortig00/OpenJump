package com.openjump.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.JumpTimingUnit
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.SpeedUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.settings.UnitSystem
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

enum class MeasurementQuantity {
    SHORT_LENGTH_CM, DISTANCE_M, DISPLACEMENT_M, MASS_KG, SPEED_MPS,
    DURATION_MS, DURATION_S, POWER_W, RELATIVE_POWER_W_PER_KG, ENERGY_J,
    MOMENTUM_KG_MPS, PERCENT,
}

data class DisplayUnit(val symbol: String, val decimals: Int)

val LocalUnitProfile = staticCompositionLocalOf { UnitProfile.METRIC }
/** Transitional name retained while consumers are migrated; it carries the full profile. */
val LocalUnitSystem = LocalUnitProfile

@Composable
@ReadOnlyComposable
fun currentAppLocale(): Locale = LocalConfiguration.current.locales[0]

object MeasurementFormatting {
    private const val CENTIMETERS_PER_INCH = 2.54
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_INCH = 0.0254
    private const val KILOGRAMS_PER_POUND = 0.45359237

    private fun UnitSystem.asProfile() = if (this == UnitSystem.US_CUSTOMARY) {
        UnitProfile.UNITED_STATES
    } else UnitProfile.METRIC

    fun unit(quantity: MeasurementQuantity, system: UnitSystem): DisplayUnit = unit(quantity, system.asProfile())

    fun unit(quantity: MeasurementQuantity, profile: UnitProfile): DisplayUnit = when (quantity) {
        MeasurementQuantity.SHORT_LENGTH_CM -> when (profile.shortLength) {
            ShortLengthUnit.CENTIMETER -> DisplayUnit("cm", 1)
            ShortLengthUnit.INCH -> DisplayUnit("in", 1)
        }
        MeasurementQuantity.DISTANCE_M -> when (profile.horizontalDistance) {
            com.openjump.app.settings.HorizontalDistanceUnit.METER -> DisplayUnit("m", 2)
            com.openjump.app.settings.HorizontalDistanceUnit.FOOT -> DisplayUnit("ft", 2)
        }
        MeasurementQuantity.DISPLACEMENT_M -> when (profile.encoderDisplacement) {
            EncoderDisplacementUnit.METER -> DisplayUnit("m", 3)
            EncoderDisplacementUnit.MILLIMETER -> DisplayUnit("mm", 1)
            EncoderDisplacementUnit.CENTIMETER -> DisplayUnit("cm", 1)
            EncoderDisplacementUnit.INCH -> DisplayUnit("in", 1)
        }
        MeasurementQuantity.MASS_KG -> when (profile.mass) {
            MassUnit.KILOGRAM -> DisplayUnit("kg", 1)
            MassUnit.POUND -> DisplayUnit("lb", 1)
        }
        MeasurementQuantity.SPEED_MPS -> when (profile.speed) {
            SpeedUnit.METERS_PER_SECOND -> DisplayUnit("m/s", 2)
            SpeedUnit.FEET_PER_SECOND -> DisplayUnit("ft/s", 2)
        }
        MeasurementQuantity.DURATION_MS -> when (profile.jumpTiming) {
            JumpTimingUnit.MILLISECOND -> DisplayUnit("ms", 0)
            JumpTimingUnit.SECOND -> DisplayUnit("s", 3)
        }
        MeasurementQuantity.DURATION_S -> DisplayUnit("s", 3)
        MeasurementQuantity.POWER_W -> DisplayUnit("W", 0)
        MeasurementQuantity.RELATIVE_POWER_W_PER_KG -> DisplayUnit("W/kg", 1)
        MeasurementQuantity.ENERGY_J -> DisplayUnit("J", 1)
        MeasurementQuantity.MOMENTUM_KG_MPS -> DisplayUnit("kg·m/s", 1)
        MeasurementQuantity.PERCENT -> DisplayUnit("%", 1)
    }

    fun toDisplay(value: Double, quantity: MeasurementQuantity, system: UnitSystem): Double =
        toDisplay(value, quantity, system.asProfile())

    fun toDisplay(value: Double, quantity: MeasurementQuantity, profile: UnitProfile): Double = when (quantity) {
        MeasurementQuantity.SHORT_LENGTH_CM -> when (profile.shortLength) {
            ShortLengthUnit.CENTIMETER -> value
            ShortLengthUnit.INCH -> value / CENTIMETERS_PER_INCH
        }
        MeasurementQuantity.DISTANCE_M -> when (profile.horizontalDistance) {
            com.openjump.app.settings.HorizontalDistanceUnit.METER -> value
            com.openjump.app.settings.HorizontalDistanceUnit.FOOT -> value / METERS_PER_FOOT
        }
        MeasurementQuantity.DISPLACEMENT_M -> when (profile.encoderDisplacement) {
            EncoderDisplacementUnit.METER -> value
            EncoderDisplacementUnit.MILLIMETER -> value * 1000.0
            EncoderDisplacementUnit.CENTIMETER -> value * 100.0
            EncoderDisplacementUnit.INCH -> value / METERS_PER_INCH
        }
        MeasurementQuantity.MASS_KG -> when (profile.mass) {
            MassUnit.KILOGRAM -> value
            MassUnit.POUND -> value / KILOGRAMS_PER_POUND
        }
        MeasurementQuantity.SPEED_MPS -> when (profile.speed) {
            SpeedUnit.METERS_PER_SECOND -> value
            SpeedUnit.FEET_PER_SECOND -> value / METERS_PER_FOOT
        }
        MeasurementQuantity.DURATION_MS -> when (profile.jumpTiming) {
            JumpTimingUnit.MILLISECOND -> value
            JumpTimingUnit.SECOND -> value / 1000.0
        }
        else -> value
    }

    fun toCanonical(value: Double, quantity: MeasurementQuantity, system: UnitSystem): Double =
        toCanonical(value, quantity, system.asProfile())

    fun toCanonical(value: Double, quantity: MeasurementQuantity, profile: UnitProfile): Double = when (quantity) {
        MeasurementQuantity.SHORT_LENGTH_CM -> when (profile.shortLength) {
            ShortLengthUnit.CENTIMETER -> value
            ShortLengthUnit.INCH -> value * CENTIMETERS_PER_INCH
        }
        MeasurementQuantity.DISTANCE_M -> when (profile.horizontalDistance) {
            com.openjump.app.settings.HorizontalDistanceUnit.METER -> value
            com.openjump.app.settings.HorizontalDistanceUnit.FOOT -> value * METERS_PER_FOOT
        }
        MeasurementQuantity.DISPLACEMENT_M -> when (profile.encoderDisplacement) {
            EncoderDisplacementUnit.METER -> value
            EncoderDisplacementUnit.MILLIMETER -> value / 1000.0
            EncoderDisplacementUnit.CENTIMETER -> value / 100.0
            EncoderDisplacementUnit.INCH -> value * METERS_PER_INCH
        }
        MeasurementQuantity.MASS_KG -> when (profile.mass) {
            MassUnit.KILOGRAM -> value
            MassUnit.POUND -> value * KILOGRAMS_PER_POUND
        }
        MeasurementQuantity.SPEED_MPS -> when (profile.speed) {
            SpeedUnit.METERS_PER_SECOND -> value
            SpeedUnit.FEET_PER_SECOND -> value * METERS_PER_FOOT
        }
        MeasurementQuantity.DURATION_MS -> when (profile.jumpTiming) {
            JumpTimingUnit.MILLISECOND -> value
            JumpTimingUnit.SECOND -> value * 1000.0
        }
        else -> value
    }

    fun formatValue(canonicalValue: Double, quantity: MeasurementQuantity, system: UnitSystem, locale: Locale, decimals: Int = unit(quantity, system).decimals) =
        formatValue(canonicalValue, quantity, system.asProfile(), locale, decimals)

    fun formatValue(canonicalValue: Double, quantity: MeasurementQuantity, profile: UnitProfile, locale: Locale, decimals: Int = unit(quantity, profile).decimals): String =
        numberFormat(locale, decimals).format(toDisplay(canonicalValue, quantity, profile))

    fun format(canonicalValue: Double, quantity: MeasurementQuantity, system: UnitSystem, locale: Locale, decimals: Int = unit(quantity, system).decimals) =
        format(canonicalValue, quantity, system.asProfile(), locale, decimals)

    fun format(canonicalValue: Double, quantity: MeasurementQuantity, profile: UnitProfile, locale: Locale, decimals: Int = unit(quantity, profile).decimals): String =
        "${formatValue(canonicalValue, quantity, profile, locale, decimals)} ${unit(quantity, profile).symbol}"

    /** Formats visible canonical masses, increasing precision only when labels collide. */
    fun formatMassLabels(canonicalValues: List<Double>, profile: UnitProfile, locale: Locale): Map<Double, String> {
        canonicalValues.forEach { require(it.isFinite() && it >= 0.0) { "Mass values must be finite and non-negative." } }
        val values = canonicalValues.distinct()
        if (values.isEmpty()) return emptyMap()
        val baseDecimals = unit(MeasurementQuantity.MASS_KG, profile).decimals
        val decimals = (baseDecimals..12).firstOrNull { candidate ->
            values.map { format(it, MeasurementQuantity.MASS_KG, profile, locale, candidate) }.distinct().size == values.size
        }
        return if (decimals != null) {
            values.associateWith { format(it, MeasurementQuantity.MASS_KG, profile, locale, decimals) }
        } else {
            values.associateWith { exactMassLabel(it, profile, locale) }
        }
    }

    private fun exactMassLabel(canonicalValue: Double, profile: UnitProfile, locale: Locale): String {
        val display = BigDecimal.valueOf(toDisplay(canonicalValue, MeasurementQuantity.MASS_KG, profile)).toPlainString()
        val localized = display.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
        return "$localized ${unit(MeasurementQuantity.MASS_KG, profile).symbol}"
    }

    /** Formats an unknown historical value without interpreting or converting its stored unit. */
    fun formatRawStoredValue(value: Double, storedUnit: String, locale: Locale, decimals: Int = 2): String =
        "${numberFormat(locale, decimals).format(value)} $storedUnit".trim()

    fun formatInputValue(canonicalValue: Double, quantity: MeasurementQuantity, system: UnitSystem, locale: Locale, maximumDecimals: Int = 4) =
        formatInputValue(canonicalValue, quantity, system.asProfile(), locale, maximumDecimals)

    fun formatInputValue(canonicalValue: Double, quantity: MeasurementQuantity, profile: UnitProfile, locale: Locale, maximumDecimals: Int = 4): String = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        this.maximumFractionDigits = maximumDecimals
    }.format(toDisplay(canonicalValue, quantity, profile))

    fun parseNumber(text: String, locale: Locale): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        if (trimmed == "+" || trimmed == "-" || trimmed.endsWith('.') || trimmed.endsWith(',')) return null
        if (trimmed.count { it == '.' || it == ',' } > 1) return null
        val decimalSeparator = (NumberFormat.getNumberInstance(locale) as? java.text.DecimalFormat)?.decimalFormatSymbols?.decimalSeparator ?: '.'
        val normalized = trimmed.replace('.', decimalSeparator).replace(',', decimalSeparator)
        val position = ParsePosition(0)
        val parser = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false }
        val parsed = parser.parse(normalized, position)?.toDouble()
        return parsed?.takeIf { position.index == normalized.length && it.isFinite() }
    }

    fun parseCanonical(text: String, quantity: MeasurementQuantity, system: UnitSystem, locale: Locale): Double? =
        parseCanonical(text, quantity, system.asProfile(), locale)

    fun parseCanonical(text: String, quantity: MeasurementQuantity, profile: UnitProfile, locale: Locale): Double? =
        parseNumber(text, locale)?.let { toCanonical(it, quantity, profile) }?.takeIf(Double::isFinite)

    fun parseCanonicalInput(text: String, quantity: MeasurementQuantity, system: UnitSystem, locale: Locale, unchangedCanonicalValue: Double? = null) =
        parseCanonicalInput(text, quantity, system.asProfile(), locale, unchangedCanonicalValue)

    fun parseCanonicalInput(text: String, quantity: MeasurementQuantity, profile: UnitProfile, locale: Locale, unchangedCanonicalValue: Double? = null): Double? {
        if (unchangedCanonicalValue != null && text.trim() == formatInputValue(unchangedCanonicalValue, quantity, profile, locale)) return unchangedCanonicalValue
        return parseCanonical(text, quantity, profile, locale)
    }

    fun quantity(metric: MetricValue): MeasurementQuantity = quantity(metric.key)
    fun quantity(key: MetricKey): MeasurementQuantity = when (key) {
        MetricKey.HEIGHT_CM, MetricKey.DISTANCE_CM -> MeasurementQuantity.SHORT_LENGTH_CM
        MetricKey.DISTANCE_M -> MeasurementQuantity.DISTANCE_M
        MetricKey.FLIGHT_TIME_MS, MetricKey.TIME_TO_TAKEOFF_MS, MetricKey.CONTACT_TIME_MS -> MeasurementQuantity.DURATION_MS
        MetricKey.TAKEOFF_VELOCITY_MPS, MetricKey.RSI, MetricKey.RSI_MOD, MetricKey.BEST_FIVE_RSI_MEAN -> MeasurementQuantity.SPEED_MPS
        MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W -> MeasurementQuantity.POWER_W
        MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG -> MeasurementQuantity.RELATIVE_POWER_W_PER_KG
        MetricKey.ESTIMATED_POTENTIAL_ENERGY_J, MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J -> MeasurementQuantity.ENERGY_J
        MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS -> MeasurementQuantity.MOMENTUM_KG_MPS
        MetricKey.ASYMMETRY_PERCENT, MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT, MetricKey.RELATIVE_HORIZONTAL_DISTANCE_PERCENT -> MeasurementQuantity.PERCENT
    }
    fun quantity(unit: MetricUnit): MeasurementQuantity = when (unit) {
        MetricUnit.METER -> MeasurementQuantity.DISTANCE_M
        MetricUnit.CENTIMETER -> MeasurementQuantity.SHORT_LENGTH_CM
        MetricUnit.MILLISECOND -> MeasurementQuantity.DURATION_MS
        MetricUnit.METER_PER_SECOND -> MeasurementQuantity.SPEED_MPS
        MetricUnit.PERCENT -> MeasurementQuantity.PERCENT
        MetricUnit.WATT -> MeasurementQuantity.POWER_W
        MetricUnit.WATT_PER_KILOGRAM -> MeasurementQuantity.RELATIVE_POWER_W_PER_KG
        MetricUnit.JOULE -> MeasurementQuantity.ENERGY_J
        MetricUnit.KILOGRAM_METER_PER_SECOND -> MeasurementQuantity.MOMENTUM_KG_MPS
    }
    fun quantity(unit: EncoderMetricUnit): MeasurementQuantity = when (unit) {
        EncoderMetricUnit.METER_PER_SECOND -> MeasurementQuantity.SPEED_MPS
        EncoderMetricUnit.METER -> MeasurementQuantity.DISPLACEMENT_M
        EncoderMetricUnit.SECOND -> MeasurementQuantity.DURATION_S
        EncoderMetricUnit.PERCENT -> MeasurementQuantity.PERCENT
    }
    private fun numberFormat(locale: Locale, decimals: Int): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
}
