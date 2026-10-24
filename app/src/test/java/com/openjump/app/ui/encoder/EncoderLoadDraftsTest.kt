package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class EncoderLoadDraftsTest {
    private val bench = EncoderExercise.BENCH_PRESS
    private val squat = EncoderExercise.SQUAT
    private val metric = UnitProfile.METRIC
    private val us = UnitProfile.UNITED_STATES
    private val locale = Locale.ENGLISH

    private fun kg(drafts: EncoderLoadDrafts, exercise: EncoderExercise = bench): Double? =
        UnitAwareNumericInputState.canonical(drafts.input(exercise), MeasurementQuantity.MASS_KG, locale)

    @Test fun `all absent defaults start at zero with two decimals in the chosen unit`() {
        for (profile in listOf(metric, us)) {
            val drafts = EncoderLoadDrafts.initial(emptyMap(), profile, locale)
            EncoderExercise.entries.forEach {
                assertEquals("0.00", drafts.input(it).text)
                assertEquals(profile, drafts.input(it).profile)
                assertEquals(0.0, kg(drafts, it)!!, 0.0)
            }
        }
    }

    @Test fun `manual load stays with its exercise and does not change default values`() {
        val defaults = mapOf(bench to 40.0, squat to 120.0)
        val drafts = EncoderLoadDrafts.initial(defaults, metric, locale).edited(bench, "61.25", metric, locale)
        assertEquals(61.25, kg(drafts)!!, 0.0)
        assertEquals(120.0, kg(drafts, squat)!!, 0.0)
        assertEquals("61.25", drafts.input(bench).text)
        assertEquals(40.0, defaults.getValue(bench), 0.0)
    }

    @Test fun `typing never reformats or increases values and partial decimals stay incomplete`() {
        for (language in listOf("en", "es", "de", "fr", "it", "pt-BR", "pt-PT", "tr")) {
            val editLocale = Locale.forLanguageTag(language)
            var drafts = EncoderLoadDrafts.initial(emptyMap(), metric, editLocale)
            for (text in listOf("", "0", "0.", "0.5", "0,", "0,50", "80.25", "80,25")) {
                drafts = drafts.edited(bench, text, metric, editLocale)
                assertEquals(text, drafts.input(bench).text)
                val actual = UnitAwareNumericInputState.canonical(drafts.input(bench), MeasurementQuantity.MASS_KG, editLocale)
                val expected = when (text) {
                    "", "0.", "0," -> null
                    "0" -> 0.0
                    "0.5", "0,50" -> 0.5
                    else -> 80.25
                }
                assertEquals(expected, actual)
            }
        }
    }

    @Test fun `rounded pound display and repeated conversion or reopen preserve exact canonical load`() {
        val expected = 131.54
        var drafts = EncoderLoadDrafts.initial(mapOf(bench to expected), us, locale)
        assertEquals(expected.toRawBits(), kg(drafts)!!.toRawBits())
        repeat(100) {
            drafts = drafts.rebase(metric, locale).rebase(us, locale)
            drafts = EncoderLoadDrafts.restoreSnapshot(drafts.saveSnapshot())!!
            assertEquals(expected.toRawBits(), kg(drafts)!!.toRawBits())
            // Simulate saving/reopening an unchanged displayed default, never parsing its rounded label.
            drafts = EncoderLoadDrafts.initial(mapOf(bench to kg(drafts)!!), us, locale)
            assertEquals(expected.toRawBits(), kg(drafts)!!.toRawBits())
        }
    }

    @Test fun `explicit pound edit converts once and both exercise drafts survive restore`() {
        val original = EncoderLoadDrafts.initial(mapOf(squat to 80.25), us, locale)
            .edited(bench, "100", us, locale)
        val restored = EncoderLoadDrafts.restoreSnapshot(original.saveSnapshot())!!
        assertEquals(45.359237, kg(restored)!!, 0.0)
        assertEquals(80.25, kg(restored, squat)!!, 0.0)
        assertEquals("100", restored.input(bench).text)
    }

    @Test fun `invalid injected defaults fall back to zero and malformed save state is rejected`() {
        val drafts = EncoderLoadDrafts.initial(mapOf(bench to Double.NaN, squat to -1.0), metric, locale)
        assertEquals(0.0, kg(drafts)!!, 0.0)
        assertEquals(0.0, kg(drafts, squat)!!, 0.0)
        assertNull(EncoderLoadDrafts.restoreSnapshot(emptyList()))
        assertNull(EncoderLoadDrafts.restoreSnapshot(List(4) { drafts.saveSnapshot().first() }))
    }
}
