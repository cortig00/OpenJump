package com.openjump.app.ui.fvp

import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estado numerico del paso 1 del flujo F-V-P (plan 13, D1/U1/U2): identidad EXACTA de la semilla,
 * re-base por canonico al cambiar de unidades o de locale, conservacion + latch de un texto parcial
 * y round-trip del estado saveable.
 *
 * Cifras fijas calculadas con aritmetica decimal (kg->lb 0,45359237; cm->in 2,54), de modo que la
 * MISMA API bajo prueba no es el unico oraculo. Locale `es` con coma decimal y `en-US` con punto.
 */
class FvpSetupInputTest {

    private val es = Locale.forLanguageTag("es")
    private val enUs = Locale.US
    private val metric = UnitProfile.METRIC
    private val unitedStates = UnitProfile.UNITED_STATES

    /** Perfil personalizado del seam: masa en kg y longitud corta en pulgadas. */
    private val kilogramsAndInches = UnitProfile(mass = MassUnit.KILOGRAM, shortLength = ShortLengthUnit.INCH)

    private data class Phase(
        val name: String,
        val profile: UnitProfile,
        val locale: Locale,
        val mass: String,
        val pushOff: String,
        val crouch: String,
    )

    private val phases = listOf(
        Phase("metrico/es", metric, es, "78,1235", "80", "34,4567"),
        Phase("EE. UU./es", unitedStates, es, "172,2327", "31,4961", "13,5656"),
        Phase("kg-in/es", kilogramsAndInches, es, "78,1235", "31,4961", "13,5656"),
        Phase("EE. UU./en-US", unitedStates, enUs, "172.2327", "31.4961", "13.5656"),
        Phase("metrico/en-US", metric, enUs, "78.1235", "80", "34.4567"),
    )

    /** Igualdad EXACTA (bit a bit): un anclaje no admite tolerancias. */
    private fun assertExactly(expected: Double, actual: Double?, message: String) {
        assertTrue(
            "$message: se esperaba exactamente $expected (bits ${expected.toBits()}) pero fue $actual",
            actual != null && expected.toBits() == actual.toBits(),
        )
    }

    private fun assertText(expected: String, state: FvpSetupNumber, message: String) {
        assertEquals("$message (texto visible)", expected, state.text)
    }

    @Test
    fun `untouched seeded fields keep the exact canonical across unit and locale transitions`() {
        var mass = FvpSetupNumber.seeded(78.123456, FvpSetupNumberQuantity.MASS_KG, metric, es)
        var pushOff = FvpSetupNumber.seeded(0.80, FvpSetupNumberQuantity.PUSH_OFF_M, metric, es)
        var crouch = FvpSetupNumber.seeded(34.4567, FvpSetupNumberQuantity.SHORT_LENGTH_CM, metric, es)

        phases.forEach { phase ->
            val where = phase.name
            mass = FvpSetupNumber.rebase(mass, phase.profile, phase.locale)
            pushOff = FvpSetupNumber.rebase(pushOff, phase.profile, phase.locale)
            crouch = FvpSetupNumber.rebase(crouch, phase.profile, phase.locale)

            assertText(phase.mass, mass, "masa $where")
            assertText(phase.pushOff, pushOff, "h_po $where")
            assertText(phase.crouch, crouch, "flexion $where")
            // El texto intacto ES la semilla reformateada: la identidad se conserva entre fases.
            assertEquals("la semilla de masa se reformatea con el campo ($where)", phase.mass, mass.seedText)
            assertEquals("la semilla de h_po se reformatea con el campo ($where)", phase.pushOff, pushOff.seedText)
            assertFalse("un campo intacto no queda en revision ($where)", mass.requiresReview)
            assertExactly(
                78.123456,
                mass.canonical(phase.profile, phase.locale),
                "masa exacta $where",
            )
            assertExactly(0.80, pushOff.canonical(phase.profile, phase.locale), "h_po exacto $where")
            assertExactly(34.4567, crouch.canonical(phase.profile, phase.locale), "flexion exacta $where")
        }
    }

    @Test
    fun `valid edits are rebased through their canonical and keep the typed value exact`() {
        val massEdited = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
            "82,5",
            metric,
            es,
        )
        assertExactly(82.5, massEdited.canonical(metric, es), "masa tecleada en metrico")

        val massUs = FvpSetupNumber.rebase(massEdited, unitedStates, es)
        assertText("181,8814", massUs, "masa tecleada tras cambio a EE. UU.")
        assertEquals("176,3698", massUs.seedText)
        assertExactly(82.5, massUs.canonical(unitedStates, es), "masa tecleada conserva 82,5 kg exactos")

        val massCustom = FvpSetupNumber.rebase(massUs, kilogramsAndInches, es)
        assertText("82,5", massCustom, "masa tecleada en kg/in")
        assertExactly(82.5, massCustom.canonical(kilogramsAndInches, es), "masa tecleada vuelve a kg")

        val pushOffEdited = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(0.4, FvpSetupNumberQuantity.PUSH_OFF_M, metric, es),
            "45",
            metric,
            es,
        )
        val pushOffUs = FvpSetupNumber.rebase(pushOffEdited, unitedStates, es)
        assertText("17,7165", pushOffUs, "h_po tecleado tras cambio a EE. UU.")
        assertExactly(0.45, pushOffUs.canonical(unitedStates, es), "h_po tecleado conserva 0,45 m exactos")
        assertFalse(pushOffUs.requiresReview)

        val backToMetric = FvpSetupNumber.rebase(pushOffUs, metric, es)
        assertText("45", backToMetric, "h_po tecleado de vuelta a metrico")
        assertExactly(0.45, backToMetric.canonical(metric, es), "h_po tecleado de vuelta: 0,45 m exactos")
    }

    @Test
    fun `partial text is retained and latched across unit and locale transitions`() {
        val partial = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
            "82,",
            metric,
            es,
        )
        assertNull("un texto parcial no resuelve canonico", partial.canonical(metric, es))

        val afterUnits = FvpSetupNumber.rebase(partial, unitedStates, es)
        assertText("82,", afterUnits, "el texto parcial no se borra ni se reinterpreta al cambiar de unidades")
        assertTrue("el texto parcial queda en revision", afterUnits.requiresReview)
        assertNull(afterUnits.canonical(unitedStates, es))
        assertEquals(
            "la semilla se reformatea en la base nueva aunque el texto quede en revision",
            "176,3698",
            afterUnits.seedText,
        )

        val afterLocale = FvpSetupNumber.rebase(afterUnits, unitedStates, enUs)
        assertText("82,", afterLocale, "el texto parcial tampoco cambia al cambiar de locale")
        assertTrue(afterLocale.requiresReview)
        assertNull(afterLocale.canonical(unitedStates, enUs))

        val fixed = FvpSetupNumber.edited(afterLocale, "180", unitedStates, enUs)
        assertFalse("una edicion explicita apaga el latch de revision", fixed.requiresReview)
        assertText("180", fixed, "la edicion explicita se conserva tal cual")
        assertExactly(180.0 * 0.45359237, fixed.canonical(unitedStates, enUs), "masa editada bajo EE. UU.")

        val partialPushOff = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(0.4, FvpSetupNumberQuantity.PUSH_OFF_M, metric, es),
            "1,",
            metric,
            es,
        )
        val pushOffUs = FvpSetupNumber.rebase(partialPushOff, unitedStates, es)
        assertText("1,", pushOffUs, "el h_po parcial se conserva")
        assertTrue(pushOffUs.requiresReview)
        assertNull(pushOffUs.canonical(unitedStates, es))
    }

    @Test
    fun `reverting to the reformatted seed text recovers the exact original value`() {
        val edited = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
            "82,5",
            metric,
            es,
        )
        val usState = FvpSetupNumber.rebase(edited, unitedStates, es)
        assertEquals("176,3698", usState.seedText)

        // Volver a dejar EXACTAMENTE el texto de la semilla (en las unidades actuales) recupera la
        // semilla original bit a bit, no su re-parseo redondeado (79,9999956 kg).
        val reverted = FvpSetupNumber.edited(usState, usState.seedText, unitedStates, es)
        assertExactly(80.0, reverted.canonical(unitedStates, es), "reversion a la semilla en EE. UU.")

        val metricAgain = FvpSetupNumber.rebase(reverted, metric, es)
        assertText("80", metricAgain, "la semilla reformateada vuelve a metrico")
        assertExactly(80.0, metricAgain.canonical(metric, es), "la reversion conserva 80 kg exactos")
    }

    @Test
    fun `clearing the field keeps the seed identity exact in the current units`() {
        val cleared = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
            "",
            metric,
            es,
        )
        assertText("", cleared, "vaciar el campo deja el texto vacio")
        assertNull(cleared.canonical(metric, es))

        val clearedUs = FvpSetupNumber.rebase(cleared, unitedStates, es)
        assertText("", clearedUs, "un campo vaciado sigue vacio tras cambiar de unidades")
        assertEquals("176,3698", clearedUs.seedText)
        // Volver a teclear la forma visible de la semilla en las unidades ACTUALES recupera 80 kg
        // exactos (no el re-parseo 79,9999956 kg).
        val retyped = FvpSetupNumber.edited(clearedUs, clearedUs.seedText, unitedStates, es)
        assertExactly(80.0, retyped.canonical(unitedStates, es), "reversion a la semilla tras vaciar")
        assertFalse(retyped.requiresReview)
    }

    @Test
    fun `locale transition parses with the old locale and formats with the new one`() {
        val spanish = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
            "82,5",
            metric,
            es,
        )
        val english = FvpSetupNumber.rebase(spanish, metric, enUs)
        assertText("82.5", english, "el texto en es se reformatea en en-US")
        assertExactly(82.5, english.canonical(metric, enUs), "el valor no cambia al cambiar de locale")
        assertFalse("un valor valido no queda en revision al cambiar de locale", english.requiresReview)

        val backToSpanish = FvpSetupNumber.rebase(english, metric, es)
        assertText("82,5", backToSpanish, "el texto en en-US se reformatea en es")
        assertExactly(82.5, backToSpanish.canonical(metric, es), "el valor sigue intacto de vuelta a es")
    }

    @Test
    fun `unknown and blank values stay unknown and blank`() {
        var state = FvpSetupNumber.seeded(null, FvpSetupNumberQuantity.MASS_KG, metric, es)
        assertText("", state, "sin semilla el campo nace vacio")
        assertNull(state.canonical(metric, es))
        assertFalse(state.requiresReview)

        state = FvpSetupNumber.rebase(state, unitedStates, enUs)
        assertText("", state, "un campo vacio sigue vacio tras cambiar de unidades y locale")
        assertNull(state.canonical(unitedStates, enUs))
        assertFalse("un campo vacio no queda en revision", state.requiresReview)

        // Un texto parcial tecleado sobre un campo sin semilla tampoco se convierte en la semilla.
        val typed = FvpSetupNumber.edited(state, "12,", unitedStates, enUs)
        val afterRebase = FvpSetupNumber.rebase(typed, metric, es)
        assertText("12,", afterRebase, "el parcial sobre un campo sin semilla se conserva")
        assertTrue(afterRebase.requiresReview)
        assertNull(afterRebase.canonical(metric, es))

        // Una semilla no finita no se convierte en valor: el campo queda vacio y desconocido.
        val notFinite = FvpSetupNumber.seeded(Double.NaN, FvpSetupNumberQuantity.PUSH_OFF_M, metric, es)
        assertText("", notFinite, "una semilla no finita no produce texto")
        assertNull(notFinite.seedCanonical)
    }

    @Test
    fun `a rebased edit wins over a seed whose rounded display collides`() {
        var state = FvpSetupNumber.edited(
            FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, enUs),
            "80.000001",
            metric,
            enUs,
        )
        state = FvpSetupNumber.rebase(state, unitedStates, enUs)
        assertEquals("176.3698", state.text)
        assertEquals("the old seed renders identically", state.seedText, state.text)
        assertExactly(80.000001, state.canonical(unitedStates, enUs), "anchor wins over rounded seed")
        // Rebase must also prefer the surviving anchor; fixing canonical() alone is insufficient.
        state = FvpSetupNumber.rebase(state, metric, es)
        assertExactly(80.000001, state.canonical(metric, es), "anchor survives another transition")
        state = FvpSetupNumber.rebase(state, unitedStates, es)
        assertExactly(80.000001, state.canonical(unitedStates, es), "anchor survives repeated collisions")
        // Explicitly retyping the original seed still has its documented U1 meaning.
        state = FvpSetupNumber.edited(state, state.seedText, unitedStates, es)
        assertExactly(80.0, state.canonical(unitedStates, es), "explicit seed reversion")
    }

    @Test
    fun `malformed snapshot numbers and review latch are rejected`() {
        val valid = FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, enUs)
        for (index in listOf(2, 3)) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "80")) {
                val snapshot = valid.saveSnapshot().toMutableList().also { it[index] = bad }
                assertNull("invalid canonical at index $index: $bad", FvpSetupNumber.restoreSnapshot(snapshot))
            }
        }
        val badLatch = valid.saveSnapshot().toMutableList().also { it[7] = "false" }
        assertNull("a malformed review latch must not silently clear", FvpSetupNumber.restoreSnapshot(badLatch))
    }

    @Test
    fun `save snapshot roundtrip preserves exact anchors and the review latch`() {
        val anchored = FvpSetupNumber.rebase(
            FvpSetupNumber.edited(
                FvpSetupNumber.seeded(0.4, FvpSetupNumberQuantity.PUSH_OFF_M, metric, es),
                "45",
                metric,
                es,
            ),
            unitedStates,
            enUs,
        )
        assertEquals(anchored, FvpSetupNumber.restoreSnapshot(anchored.saveSnapshot()))

        val review = FvpSetupNumber.rebase(
            FvpSetupNumber.edited(
                FvpSetupNumber.seeded(80.0, FvpSetupNumberQuantity.MASS_KG, metric, es),
                "82,",
                metric,
                es,
            ),
            unitedStates,
            es,
        )
        assertTrue(review.requiresReview)
        assertEquals(review, FvpSetupNumber.restoreSnapshot(review.saveSnapshot()))

        // Snapshots corruptos o de otra version: se descartan, nunca se reinterpretan.
        assertNull(FvpSetupNumber.restoreSnapshot(emptyList()))
        assertNull(FvpSetupNumber.restoreSnapshot(review.saveSnapshot().dropLast(1)))
        val unknownQuantity = review.saveSnapshot().toMutableList().also { it[4] = "UNKNOWN" }
        assertNull(FvpSetupNumber.restoreSnapshot(unknownQuantity))
        val unknownUnit = review.saveSnapshot().toMutableList().also { it[5] = "stone" }
        assertNull(FvpSetupNumber.restoreSnapshot(unknownUnit))
    }
}
