package com.openjump.app.ui

import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.settings.UnitSystem
import com.openjump.app.settings.UnitProfile
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.HorizontalDistanceUnit
import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.SpeedUnit
import com.openjump.app.settings.JumpTimingUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementFormattingTest {
    @Test
    fun `new presentation defaults remain metric`() {
        assertEquals("45,0 cm", MeasurementFormatting.format(45.0, MeasurementQuantity.SHORT_LENGTH_CM, UnitSystem.METRIC, Locale("es")))
        assertEquals("80.0 kg", MeasurementFormatting.format(80.0, MeasurementQuantity.MASS_KG, UnitSystem.METRIC, Locale.ENGLISH))
    }

    @Test
    fun `encoder load labels use active mass unit and locale`() {
        assertEquals(
            "60,0 kg",
            MeasurementFormatting.format(60.0, MeasurementQuantity.MASS_KG, UnitProfile.METRIC, Locale("es")),
        )
        assertEquals(
            "132.3 lb",
            MeasurementFormatting.format(60.0, MeasurementQuantity.MASS_KG, UnitProfile.UNITED_STATES, Locale.ENGLISH),
        )
    }

    @Test
    fun `mass labels keep normal precision when representatives are distinct`() {
        val labels = MeasurementFormatting.formatMassLabels(listOf(60.0, 100.0), UnitProfile.METRIC, Locale.ENGLISH)
        assertEquals("60.0 kg", labels[60.0])
        assertEquals("100.0 kg", labels[100.0])
    }

    @Test
    fun `mass labels increase localized precision only for collisions`() {
        val kgLabels = MeasurementFormatting.formatMassLabels(
            listOf(60.0000005, 60.000002),
            UnitProfile.METRIC,
            Locale("es"),
        )
        assertNotEquals(kgLabels[60.0000005], kgLabels[60.000002])
        assertEquals(true, kgLabels.values.all { it.endsWith(" kg") && it.contains(',') })

        val lbLabels = MeasurementFormatting.formatMassLabels(
            listOf(60.0000005, 60.000002),
            UnitProfile.UNITED_STATES,
            Locale("es"),
        )
        assertNotEquals(lbLabels[60.0000005], lbLabels[60.000002])
        assertEquals(true, lbLabels.values.all { it.endsWith(" lb") && it.contains(',') })
    }

    @Test
    fun `exact international conversions are used`() {
        assertEquals(1.0, MeasurementFormatting.toDisplay(2.54, MeasurementQuantity.SHORT_LENGTH_CM, UnitSystem.US_CUSTOMARY), 0.0)
        assertEquals(1.0, MeasurementFormatting.toDisplay(0.3048, MeasurementQuantity.DISTANCE_M, UnitSystem.US_CUSTOMARY), 0.0)
        assertEquals(1.0, MeasurementFormatting.toDisplay(0.0254, MeasurementQuantity.DISPLACEMENT_M, UnitSystem.US_CUSTOMARY), 0.0)
        assertEquals(1.0, MeasurementFormatting.toDisplay(0.45359237, MeasurementQuantity.MASS_KG, UnitSystem.US_CUSTOMARY), 0.0)
        assertEquals(1.0, MeasurementFormatting.toDisplay(0.3048, MeasurementQuantity.SPEED_MPS, UnitSystem.US_CUSTOMARY), 0.0)
    }

    @Test
    fun `us values round trip to canonical units`() {
        MeasurementQuantity.entries.forEach { quantity ->
            val canonical = 42.625
            val display = MeasurementFormatting.toDisplay(canonical, quantity, UnitSystem.US_CUSTOMARY)
            assertEquals(canonical, MeasurementFormatting.toCanonical(display, quantity, UnitSystem.US_CUSTOMARY), 1e-10)
        }
    }

    @Test
    fun `editable converted values keep precision without trailing zero noise`() {
        assertEquals(
            "17.7165",
            MeasurementFormatting.formatInputValue(
                45.0,
                MeasurementQuantity.SHORT_LENGTH_CM,
                UnitSystem.US_CUSTOMARY,
                Locale.ENGLISH,
            ),
        )
        assertEquals(
            "45",
            MeasurementFormatting.formatInputValue(
                45.0,
                MeasurementQuantity.SHORT_LENGTH_CM,
                UnitSystem.METRIC,
                Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun `unchanged rounded input preserves an exact canonical boundary`() {
        val text = MeasurementFormatting.formatInputValue(
            10.0,
            MeasurementQuantity.SHORT_LENGTH_CM,
            UnitSystem.US_CUSTOMARY,
            Locale.ENGLISH,
        )
        assertEquals("3.937", text)
        assertEquals(
            10.0,
            MeasurementFormatting.parseCanonicalInput(
                text,
                MeasurementQuantity.SHORT_LENGTH_CM,
                UnitSystem.US_CUSTOMARY,
                Locale.ENGLISH,
                unchangedCanonicalValue = 10.0,
            )!!,
            0.0,
        )
    }

    @Test
    fun `localized decimal parser accepts comma or point and rejects ambiguous input`() {
        assertEquals(17.7, MeasurementFormatting.parseNumber("17,7", Locale("es"))!!, 0.0)
        assertEquals(17.7, MeasurementFormatting.parseNumber("17.7", Locale("es"))!!, 0.0)
        assertEquals(17.7, MeasurementFormatting.parseNumber("17,7", Locale.ENGLISH)!!, 0.0)
        assertNull(MeasurementFormatting.parseNumber("1,234.5", Locale.ENGLISH))
        assertNull(MeasurementFormatting.parseNumber("NaN", Locale.ENGLISH))
        assertNull(MeasurementFormatting.parseNumber("17 cm", Locale.ENGLISH))
    }

    @Test
    fun `scientific estimate units remain canonical in both display systems`() {
        assertEquals(
            "4248 W",
            MeasurementFormatting.format(4_247.691, MeasurementQuantity.POWER_W, UnitSystem.US_CUSTOMARY, Locale.ENGLISH),
        )
        assertEquals(
            "53.1 W/kg",
            MeasurementFormatting.format(
                53.096,
                MeasurementQuantity.RELATIVE_POWER_W_PER_KG,
                UnitSystem.US_CUSTOMARY,
                Locale.ENGLISH,
            ),
        )
        assertEquals(
            "346.2 J",
            MeasurementFormatting.format(346.214, MeasurementQuantity.ENERGY_J, UnitSystem.METRIC, Locale.ENGLISH),
        )
    }

    @Test
    fun `custom concrete units convert independently`() {
        val profile = UnitProfile(
            shortLength = ShortLengthUnit.INCH,
            horizontalDistance = HorizontalDistanceUnit.FOOT,
            encoderDisplacement = EncoderDisplacementUnit.MILLIMETER,
            mass = MassUnit.POUND,
            speed = SpeedUnit.FEET_PER_SECOND,
            jumpTiming = JumpTimingUnit.SECOND,
        )
        assertEquals("in", MeasurementFormatting.unit(MeasurementQuantity.SHORT_LENGTH_CM, profile).symbol)
        assertEquals("ft", MeasurementFormatting.unit(MeasurementQuantity.DISTANCE_M, profile).symbol)
        assertEquals("mm", MeasurementFormatting.unit(MeasurementQuantity.DISPLACEMENT_M, profile).symbol)
        assertEquals(1000.0, MeasurementFormatting.toDisplay(1.0, MeasurementQuantity.DISPLACEMENT_M, profile), 0.0)
        assertEquals(1.0, MeasurementFormatting.toCanonical(1000.0, MeasurementQuantity.DISPLACEMENT_M, profile), 1e-12)
        assertEquals(0.5, MeasurementFormatting.toDisplay(500.0, MeasurementQuantity.DURATION_MS, profile), 0.0)
        assertEquals(500.0, MeasurementFormatting.toCanonical(0.5, MeasurementQuantity.DURATION_MS, profile), 0.0)
        assertEquals(2.0, MeasurementFormatting.toDisplay(2.0, MeasurementQuantity.DURATION_S, profile), 0.0)
    }

    @Test
    fun `fixed scientific units and encoder seconds ignore profile`() {
        val custom = UnitProfile.UNITED_STATES.copy(encoderDisplacement = EncoderDisplacementUnit.MILLIMETER)
        listOf(
            MeasurementQuantity.DURATION_S to "s",
            MeasurementQuantity.PERCENT to "%",
            MeasurementQuantity.POWER_W to "W",
            MeasurementQuantity.RELATIVE_POWER_W_PER_KG to "W/kg",
            MeasurementQuantity.ENERGY_J to "J",
            MeasurementQuantity.MOMENTUM_KG_MPS to "kg·m/s",
        ).forEach { (quantity, symbol) -> assertEquals(symbol, MeasurementFormatting.unit(quantity, custom).symbol) }
        assertEquals(MeasurementQuantity.DURATION_S, MeasurementFormatting.quantity(com.openjump.app.encoder.EncoderMetricUnit.SECOND))
    }

    @Test
    fun `metric and united states presets retain semantic defaults`() {
        assertEquals("m", MeasurementFormatting.unit(MeasurementQuantity.DISPLACEMENT_M, UnitProfile.METRIC).symbol)
        assertEquals("in", MeasurementFormatting.unit(MeasurementQuantity.DISPLACEMENT_M, UnitProfile.UNITED_STATES).symbol)
        assertEquals("ms", MeasurementFormatting.unit(MeasurementQuantity.DURATION_MS, UnitProfile.UNITED_STATES).symbol)
        assertEquals("s", MeasurementFormatting.unit(MeasurementQuantity.DURATION_S, UnitProfile.UNITED_STATES).symbol)
    }

    @Test
    fun `unknown historical units stay raw while numbers follow locale`() {
        assertEquals("12,35 FUTURE_UNIT", MeasurementFormatting.formatRawStoredValue(12.345, "FUTURE_UNIT", Locale("es")))
        assertEquals("12.35 FUTURE_UNIT", MeasurementFormatting.formatRawStoredValue(12.345, "FUTURE_UNIT", Locale.ENGLISH))
    }

    @Test
    fun `metric keys choose semantic quantities rather than symbols alone`() {
        assertEquals(
            MeasurementQuantity.SHORT_LENGTH_CM,
            MeasurementFormatting.quantity(MetricValue(MetricKey.HEIGHT_CM, 40.0, MetricUnit.CENTIMETER)),
        )
        assertEquals(
            MeasurementQuantity.DISTANCE_M,
            MeasurementFormatting.quantity(MetricValue(MetricKey.DISTANCE_M, 2.5, MetricUnit.METER)),
        )
        assertEquals(
            MeasurementQuantity.SPEED_MPS,
            MeasurementFormatting.quantity(MetricValue(MetricKey.RSI, 2.0, MetricUnit.METER_PER_SECOND)),
        )
    }
}
