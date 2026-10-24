package com.openjump.app.ui

import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.UnitProfile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitAwareNumericInputTest {
    private val locale = Locale.ENGLISH
    private val metric = UnitProfile.METRIC
    private val us = UnitProfile.UNITED_STATES

    @Test fun `valid text rebases through canonical value`() {
        val state = UnitAwareNumericInputState.initial("100", metric)
        val rebased = UnitAwareNumericInputState.rebase(state, us, MeasurementQuantity.MASS_KG, locale)
        assertEquals("220.4623", rebased.text)
        assertFalse(rebased.requiresReview)
        assertEquals(100.0, rebased.canonicalAnchor!!, 0.0)
        assertEquals(100.0, UnitAwareNumericInputState.canonical(rebased, MeasurementQuantity.MASS_KG, locale)!!, 1e-4)
    }

    @Test fun `canonical is unavailable while profile rebase is pending`() {
        val state = UnitAwareNumericInputState.initial("100", metric)
        assertNull(UnitAwareNumericInputState.canonical(state, MeasurementQuantity.MASS_KG, locale, us))
        val rebased = UnitAwareNumericInputState.rebase(state, us, MeasurementQuantity.MASS_KG, locale)
        assertEquals(100.0, UnitAwareNumericInputState.canonical(rebased, MeasurementQuantity.MASS_KG, locale, us)!!, 1e-4)
    }

    @Test fun `empty text stays empty`() {
        val rebased = UnitAwareNumericInputState.rebase(
            UnitAwareNumericInputState.initial("", metric), us, MeasurementQuantity.MASS_KG, locale,
        )
        assertEquals("", rebased.text)
        assertFalse(rebased.requiresReview)
    }

    @Test fun `partial or invalid text remains visible and latched`() {
        val state = UnitAwareNumericInputState.initial("1.", metric)
        val rebased = UnitAwareNumericInputState.rebase(state, us, MeasurementQuantity.MASS_KG, locale)
        assertEquals("1.", rebased.text)
        assertTrue(rebased.requiresReview)
        assertNull(UnitAwareNumericInputState.canonical(rebased, MeasurementQuantity.MASS_KG, locale))
    }

    @Test fun `explicit edit clears latch and adopts current profile`() {
        val invalid = UnitAwareNumericInputState("1.", us, requiresReview = true)
        val edited = UnitAwareNumericInputState.edited("2", us, MeasurementQuantity.MASS_KG, locale)
        assertFalse(edited.requiresReview)
        assertEquals(2.0 * 0.45359237, UnitAwareNumericInputState.canonical(edited, MeasurementQuantity.MASS_KG, locale)!!, 1e-12)
        assertFalse(UnitAwareNumericInputState.edited(invalid.text, us, MeasurementQuantity.MASS_KG, locale).requiresReview)
        assertNull(UnitAwareNumericInputState.canonical(
            UnitAwareNumericInputState.edited(invalid.text, us, MeasurementQuantity.MASS_KG, locale),
            MeasurementQuantity.MASS_KG,
            locale,
        ))
    }

    @Test fun `saved basis preserves custom displacement semantics`() {
        val custom = metric.copy(encoderDisplacement = EncoderDisplacementUnit.MILLIMETER)
        val state = UnitAwareNumericInputState.initial("1000", custom)
        val restored = UnitAwareNumericInputState.restoreSnapshot(state.saveSnapshot())!!
        assertEquals(custom, restored.profile)
        assertEquals(1.0, UnitAwareNumericInputState.canonical(restored, MeasurementQuantity.DISPLACEMENT_M, locale)!!, 1e-12)
    }

    @Test fun `saved rebased anchor survives recreation`() {
        val rebased = UnitAwareNumericInputState.rebase(
            UnitAwareNumericInputState.initial("100", metric), us, MeasurementQuantity.MASS_KG, locale,
        )
        val restored = UnitAwareNumericInputState.restoreSnapshot(rebased.saveSnapshot())!!
        assertEquals(100.0, UnitAwareNumericInputState.canonical(restored, MeasurementQuantity.MASS_KG, locale)!!, 0.0)
    }

    @Test fun `reformatting does not lose canonical anchor`() {
        val state = UnitAwareNumericInputState.initial("100", metric)
        val changed = UnitAwareNumericInputState.rebase(state, us, MeasurementQuantity.MASS_KG, locale)
        val editedBack = UnitAwareNumericInputState.rebase(changed, metric, MeasurementQuantity.MASS_KG, locale)
        assertEquals(100.0, UnitAwareNumericInputState.canonical(editedBack, MeasurementQuantity.MASS_KG, locale)!!, 1e-8)
    }

    @Test fun `rounded converted display keeps exact known canonical default`() {
        val canonical = 45.0
        val display = MeasurementFormatting.formatInputValue(canonical, MeasurementQuantity.SHORT_LENGTH_CM, us, locale)
        val state = UnitAwareNumericInputState.initial(display, us, canonical)
        assertEquals(17.7165, display.toDouble(), 0.0001)
        assertEquals(canonical, UnitAwareNumericInputState.canonical(state, MeasurementQuantity.SHORT_LENGTH_CM, locale)!!, 0.0)
        val back = UnitAwareNumericInputState.rebase(state, metric, MeasurementQuantity.SHORT_LENGTH_CM, locale)
        assertEquals("45", back.text)
        assertEquals(canonical, UnitAwareNumericInputState.canonical(back, MeasurementQuantity.SHORT_LENGTH_CM, locale)!!, 0.0)
    }
}
