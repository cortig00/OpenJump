package com.openjump.app.ui.fvp

import com.openjump.app.settings.EncoderDisplacementUnit
import com.openjump.app.settings.HorizontalDistanceUnit
import com.openjump.app.settings.JumpTimingUnit
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.SpeedUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice U1: un campo numerico del flujo F-V-P SIN editar debe resolver EXACTAMENTE (bit a bit) el
 * canonico con el que se sembro, en cualquier perfil de unidades y locale. Antes se re-parseaba la
 * forma redondeada visible y el valor guardado derivaba en silencio:
 *
 * - 0,40 m en EE. UU. se muestra "15,748" y re-parseaba 0,3999992 m;
 * - 0,80 m en EE. UU. se muestra "31,4961" y re-parseaba 0,80000094 m (fuera del dominio (0,05, 0,8]);
 * - 80 kg en EE. UU. se muestra "176,3698" y re-parseaba 79,9999956 kg.
 */
class FvpFieldInputTest {

    private val es = Locale.forLanguageTag("es")
    private val enUs = Locale.US
    private val metric = UnitProfile.METRIC
    private val unitedStates = UnitProfile.UNITED_STATES
    private val custom = UnitProfile(
        shortLength = ShortLengthUnit.INCH,
        horizontalDistance = HorizontalDistanceUnit.METER,
        encoderDisplacement = EncoderDisplacementUnit.MILLIMETER,
        mass = MassUnit.POUND,
        speed = SpeedUnit.METERS_PER_SECOND,
        jumpTiming = JumpTimingUnit.SECOND,
    )

    private val profiles = listOf("metric" to metric, "us" to unitedStates, "custom" to custom)
    private val locales = listOf("es" to es, "en-US" to enUs)

    /** Texto con el que la pantalla de setup siembra la masa corporal / la carga anadida. */
    private fun massSeedText(kg: Double, profile: UnitProfile, locale: Locale): String =
        formatCanonical(kg, MeasurementQuantity.MASS_KG, profile, locale)

    /** Texto con el que la pantalla de setup siembra h_po (`formatPushOffInput`). */
    private fun pushOffSeedText(m: Double, profile: UnitProfile, locale: Locale): String =
        formatPushOffInput(m, profile, locale)

    /** Texto con el que las pantallas siembran los componentes del h_po (flexion/extension). */
    private fun shortLengthSeedText(cm: Double, profile: UnitProfile, locale: Locale): String =
        formatCanonical(cm, MeasurementQuantity.SHORT_LENGTH_CM, profile, locale)

    /** Texto con el que el editor de intentos siembra la altura (cm/in; el borrador guarda metros). */
    private fun heightSeedText(m: Double, profile: UnitProfile, locale: Locale): String =
        formatCanonical(m * CENTIMETERS_PER_METER, MeasurementQuantity.SHORT_LENGTH_CM, profile, locale)

    /** Igualdad EXACTA (bit a bit) del canonico resuelto: la semilla no admite tolerancias. */
    private fun assertExactly(expected: Double, actual: Double?, message: String) {
        assertTrue(
            "$message: se esperaba exactamente $expected (bits ${expected.toBits()}) pero fue $actual",
            actual != null && expected.toBits() == actual.toBits(),
        )
    }

    /** Editar el campo debe resolver OTRO valor: si no, la vuelta a la semilla seria tautologica. */
    private fun assertEditableDifferent(seed: Double, edited: Double?, message: String) {
        assertTrue("$message: el texto editado debe resolver otro valor, pero fue $edited", edited != null && edited != seed)
    }

    @Test
    fun `seeded fields resolve the exact stored value in every profile and locale`() {
        profiles.forEach { (profileName, profile) ->
            locales.forEach { (localeName, locale) ->
                val where = "$profileName/$localeName"
                listOf(80.0, 72.5).forEach { kg ->
                    val text = massSeedText(kg, profile, locale)
                    assertExactly(kg, resolveSeededMassKg(text, text, kg, profile, locale), "masa $kg kg $where")
                }
                listOf(0.40, 0.80, 0.42, 0.051).forEach { m ->
                    val text = pushOffSeedText(m, profile, locale)
                    assertExactly(m, resolveSeededPushOffM(text, text, m, profile, locale), "h_po $m m $where")
                }
                listOf(45.0, 12.0).forEach { cm ->
                    val text = shortLengthSeedText(cm, profile, locale)
                    assertExactly(
                        cm,
                        resolveSeededShortLengthCm(text, text, cm, profile, locale),
                        "flexion/extension $cm cm $where",
                    )
                }
                listOf(0.42, 0.79, 1.2).forEach { m ->
                    val text = heightSeedText(m, profile, locale)
                    assertExactly(m, resolveSeededHeightM(text, text, m, profile, locale), "altura $m m $where")
                }
                listOf(20.0, 0.5).forEach { kg ->
                    val text = massSeedText(kg, profile, locale)
                    assertExactly(kg, resolveSeededMassKg(text, text, kg, profile, locale), "carga $kg kg $where")
                }
            }
        }
    }

    /** Caso de siembra de h_po con el texto visible LITERAL que debe producir cada pantalla. */
    private data class PushOffSeedCase(
        val meters: Double,
        val profileName: String,
        val profile: UnitProfile,
        val expected: String,
    )

    @Test
    fun `setup and conditions seed the push off field with the same literal text`() {
        // Literales fijados (locale `es`, el mismo que usa el resto de la prueba): la unidad del
        // campo es la longitud CORTA del perfil (cm/in) y su forma visible esta redondeada. Se
        // comprueban las DOS expresiones de siembra de las pantallas, de modo que cambiar
        // cualquiera de ellas (o la conversion/precision del formateo) haga fallar la prueba. La
        // version anterior comparaba `formatPushOffInput` con la definicion de
        // `formatPushOffInput`, asi que no podia fallar y no validaba ninguna de las dos pantallas.
        listOf(
            PushOffSeedCase(0.40, "metric", metric, "40"),
            PushOffSeedCase(0.42, "metric", metric, "42"),
            PushOffSeedCase(0.40, "us", unitedStates, "15,748"),
            PushOffSeedCase(0.42, "us", unitedStates, "16,5354"),
            PushOffSeedCase(0.80, "us", unitedStates, "31,4961"),
        ).forEach { case ->
            assertEquals(
                "setup (FvpSetupScreen -> formatPushOffInput) debe sembrar h_po ${case.meters} m" +
                    " como \"${case.expected}\" en ${case.profileName}",
                case.expected,
                formatPushOffInput(case.meters, case.profile, es),
            )
            assertEquals(
                "condiciones (FvpConditionsScreen -> formatCanonical de h_po*CENTIMETERS_PER_METER)" +
                    " debe sembrar ${case.meters} m como \"${case.expected}\" en ${case.profileName}",
                case.expected,
                formatCanonical(
                    case.meters * CENTIMETERS_PER_METER,
                    MeasurementQuantity.SHORT_LENGTH_CM,
                    case.profile,
                    es,
                ),
            )
        }
    }

    @Test
    fun `seeded 0 05 m resolves exactly while the lower domain boundary stays exclusive`() {
        // El dominio de h_po es (MIN_PUSH_OFF_M, MAX_PUSH_OFF_M]: 0,05 m esta EN el limite, NO
        // dentro. El limite es exclusivo y esta prueba lo documenta; la semilla exacta no lo
        // relaja (0,05 m sigue quedando fuera del dominio para la elegibilidad).
        assertTrue("0,05 m debe seguir fuera del dominio abierto de h_po", 0.05 <= MIN_PUSH_OFF_M)
        listOf("metric" to metric, "us" to unitedStates).forEach { (profileName, profile) ->
            val text = pushOffSeedText(0.05, profile, es)
            assertExactly(
                0.05,
                resolveSeededPushOffM(text, text, 0.05, profile, es),
                "h_po sembrado 0,05 m en $profileName",
            )
            if (profile == unitedStates) {
                // El caso metrico es exacto por construccion ("5" cm / 100 = 0,05); el de EE. UU.
                // NO es tautologico: la forma visible redondeada re-parsea otro valor.
                assertTrue(
                    "el texto visible de 0,05 m en EE. UU. debe re-parsear otro valor",
                    parsePushOffInput(text, unitedStates, es)!! != 0.05,
                )
            }
        }
    }

    @Test
    fun `edited mass and height resolve exactly as the plain parser`() {
        // Masa editada en metrico: "70" -> 70,0 kg EXACTO (sin tolerancias ni redondeos nuevos).
        val editedMass = resolveSeededMassKg("70", massSeedText(80.0, metric, es), 80.0, metric, es)!!
        assertExactly(70.0, editedMass, "masa editada 70 kg")
        assertEquals(
            MeasurementFormatting.parseCanonical("70", MeasurementQuantity.MASS_KG, metric, es)!!.toBits(),
            editedMass.toBits(),
        )

        // Altura editada en EE. UU.: "16" in -> bit a bit el mismo que parseCanonical(...)/100.
        val parsedHeightCm = MeasurementFormatting.parseCanonical(
            "16",
            MeasurementQuantity.SHORT_LENGTH_CM,
            unitedStates,
            es,
        )!!
        val expectedHeightM = parsedHeightCm.div(CENTIMETERS_PER_METER)
        val editedHeight = resolveSeededHeightM(
            "16",
            heightSeedText(0.42, unitedStates, es),
            0.42,
            unitedStates,
            es,
        )!!
        assertEquals(expectedHeightM.toBits(), editedHeight.toBits())
        assertTrue("16 in editados deben ser distintos de la semilla 0,42 m", editedHeight != 0.42)
    }

    @Test
    fun `edit then revert restores the exact seeded value not the rounded re-parse`() {
        // Editar el texto NUNCA toca la semilla: resolver otro texto T y despues volver al texto
        // sembrado S devuelve EXACTAMENTE el valor sembrado V, no el re-parseo de la forma visible.
        profiles.forEach { (profileName, profile) ->
            locales.forEach { (localeName, locale) ->
                val where = "$profileName/$localeName"

                val massSeed = massSeedText(80.0, profile, locale)
                assertEditableDifferent(
                    80.0,
                    resolveSeededMassKg("70", massSeed, 80.0, profile, locale),
                    "masa en $where",
                )
                assertExactly(
                    80.0,
                    resolveSeededMassKg(massSeed, massSeed, 80.0, profile, locale),
                    "masa vuelta al texto sembrado en $where",
                )

                val pushOffSeed = pushOffSeedText(0.42, profile, locale)
                assertEditableDifferent(
                    0.42,
                    resolveSeededPushOffM("41", pushOffSeed, 0.42, profile, locale),
                    "h_po en $where",
                )
                assertExactly(
                    0.42,
                    resolveSeededPushOffM(pushOffSeed, pushOffSeed, 0.42, profile, locale),
                    "h_po vuelto al texto sembrado en $where",
                )

                val componentSeed = shortLengthSeedText(45.0, profile, locale)
                assertEditableDifferent(
                    45.0,
                    resolveSeededShortLengthCm("50", componentSeed, 45.0, profile, locale),
                    "componente en $where",
                )
                assertExactly(
                    45.0,
                    resolveSeededShortLengthCm(componentSeed, componentSeed, 45.0, profile, locale),
                    "componente vuelto al texto sembrado en $where",
                )

                val heightSeed = heightSeedText(0.42, profile, locale)
                assertEditableDifferent(
                    0.42,
                    resolveSeededHeightM("16", heightSeed, 0.42, profile, locale),
                    "altura en $where",
                )
                assertExactly(
                    0.42,
                    resolveSeededHeightM(heightSeed, heightSeed, 0.42, profile, locale),
                    "altura vuelta al texto sembrado en $where",
                )
            }
        }

        // Casos en los que re-parsear el texto sembrado NO devuelve la semilla: volver al texto
        // original solo puede pasar por la semilla (una implementacion que re-parseara fallaria).
        val pounds = massSeedText(80.0, unitedStates, es)
        assertTrue(
            "80 kg en EE. UU. debe mostrar un texto que re-parsea otro valor",
            MeasurementFormatting
                .parseCanonical(pounds, MeasurementQuantity.MASS_KG, unitedStates, es)!! != 80.0,
        )
        val editedPounds = resolveSeededMassKg("70", pounds, 80.0, unitedStates, es)!!
        assertEquals(
            MeasurementFormatting.parseCanonical("70", MeasurementQuantity.MASS_KG, unitedStates, es)!!.toBits(),
            editedPounds.toBits(),
        )
        assertTrue("editar el texto debe apartarse de la semilla de 80 kg", editedPounds != 80.0)
        assertExactly(
            80.0,
            resolveSeededMassKg(pounds, pounds, 80.0, unitedStates, es),
            "masa vuelta al texto sembrado",
        )

        val inches = pushOffSeedText(0.80, unitedStates, es)
        assertTrue(
            "0,80 m en EE. UU. debe mostrar un texto que re-parsea fuera del dominio",
            parsePushOffInput(inches, unitedStates, es)!! > MAX_PUSH_OFF_M,
        )
        assertExactly(
            0.4064,
            resolveSeededPushOffM("16", inches, 0.80, unitedStates, es),
            "h_po editado antes de volver",
        )
        assertExactly(
            0.80,
            resolveSeededPushOffM(inches, inches, 0.80, unitedStates, es),
            "h_po vuelto al texto sembrado",
        )
    }

    @Test
    fun `raw parse keeps the documented rounding while the seeded resolver is exact`() {
        // 0,40 m: la forma visible en EE. UU. es "15,748" y su re-parseo pierde precision.
        val untouchedUs = pushOffSeedText(0.40, unitedStates, es)
        assertEquals("15,748", untouchedUs)
        assertEquals(0.3999992, parsePushOffInput(untouchedUs, unitedStates, es)!!, 1e-7)
        assertTrue(parsePushOffInput(untouchedUs, unitedStates, es)!! != 0.40)
        assertExactly(0.40, resolveSeededPushOffM(untouchedUs, untouchedUs, 0.40, unitedStates, es), "h_po sembrado 0,40 m")

        // 0,80 m: la forma visible en EE. UU. ("31,4961") re-parsea FUERA del dominio; intacta vale 0,80.
        val maxInches = pushOffSeedText(0.80, unitedStates, es)
        assertEquals("31,4961", maxInches)
        assertTrue(
            "el re-parseo crudo de \"31,4961\" debe seguir cayendo fuera del dominio",
            parsePushOffInput(maxInches, unitedStates, es)!! > MAX_PUSH_OFF_M,
        )
        val seededMax = resolveSeededPushOffM(maxInches, maxInches, 0.80, unitedStates, es)!!
        assertExactly(0.80, seededMax, "h_po sembrado 0,80 m")
        assertTrue(seededMax > MIN_PUSH_OFF_M && seededMax <= MAX_PUSH_OFF_M)

        // 80 kg: la forma visible en EE. UU. es "176,3698" y su re-parseo pierde precision.
        val pounds = massSeedText(80.0, unitedStates, es)
        assertEquals("176,3698", pounds)
        assertEquals(
            79.9999956,
            MeasurementFormatting.parseCanonical(pounds, MeasurementQuantity.MASS_KG, unitedStates, es)!!,
            1e-6,
        )
        assertExactly(80.0, resolveSeededMassKg(pounds, pounds, 80.0, unitedStates, es), "masa sembrada 80 kg")
    }

    @Test
    fun `edited text is parsed exactly as before with new tolerance or defaults`() {
        val metricEdited = resolveSeededPushOffM("41", pushOffSeedText(0.40, metric, es), 0.40, metric, es)!!
        assertEquals(parsePushOffInput("41", metric, es)!!.toBits(), metricEdited.toBits())
        assertExactly(0.41, metricEdited, "h_po editado 41 cm")

        val usEdited = resolveSeededPushOffM("16", pushOffSeedText(0.40, unitedStates, es), 0.40, unitedStates, es)!!
        assertEquals(parsePushOffInput("16", unitedStates, es)!!.toBits(), usEdited.toBits())
        assertExactly(0.4064, usEdited, "h_po editado 16 in")

        // El maximo visible en pulgadas, tecleado a mano, sigue cayendo fuera del dominio (U2 es dueno
        // de los textos de limite; aqui solo se fija que el dominio siga rechazando).
        val typedMax = resolveSeededPushOffM("31,4961", "16,5354", 0.42, unitedStates, es)!!
        assertTrue(typedMax > MAX_PUSH_OFF_M)
        assertEquals(parsePushOffInput("31,4961", unitedStates, es)!!.toBits(), typedMax.toBits())

        // Editar el texto no introduce tolerancias ni valores por defecto.
        assertNull(resolveSeededPushOffM("", pushOffSeedText(0.40, metric, es), 0.40, metric, es))
        assertNull(resolveSeededPushOffM("abc", pushOffSeedText(0.40, metric, es), 0.40, metric, es))
        assertNull(resolveSeededMassKg("abc", massSeedText(80.0, metric, es), 80.0, metric, es))
        assertNull(resolveSeededHeightM("abc", heightSeedText(0.42, metric, es), 0.42, metric, es))
        assertNull(resolveSeededShortLengthCm("", shortLengthSeedText(45.0, metric, es), 45.0, metric, es))
    }

    @Test
    fun `null seed stays null and never becomes zero`() {
        profiles.forEach { (profileName, profile) ->
            assertNull("masa vacia $profileName", resolveSeededMassKg("", "", null, profile, es))
            assertNull("h_po vacio $profileName", resolveSeededPushOffM("", "", null, profile, es))
            assertNull("altura vacia $profileName", resolveSeededHeightM("", "", null, profile, es))
            assertNull("componente vacio $profileName", resolveSeededShortLengthCm("", "", null, profile, es))
        }
        // Texto vacio con semilla NO nula (el usuario borro el campo): null, nunca 0,0.
        assertNull(resolveSeededMassKg("", massSeedText(80.0, metric, es), 80.0, metric, es))
        assertNull(resolveSeededPushOffM("", pushOffSeedText(0.40, metric, es), 0.40, metric, es))
        assertNull(resolveSeededHeightM("", heightSeedText(0.42, metric, es), 0.42, metric, es))
        // Y un campo sembrado con VACIO no se convierte en 0,0 al resolverlo.
        val emptyResolved = resolveSeededMassKg("", "", null, metric, es)
        assertNull(emptyResolved)
        assertTrue(emptyResolved != 0.0)
    }

    @Test
    fun `profile suggestion replaces text and seed so the field resolves the profile weight`() {
        val profileWeightKg = 72.5
        val suggestion = massSeedText(profileWeightKg, unitedStates, es)
        // Tal como lo deja la LaunchedEffect de la sugerencia: texto Y semilla actualizados.
        assertExactly(
            profileWeightKg,
            resolveSeededMassKg(suggestion, suggestion, profileWeightKg, unitedStates, es),
            "sugerencia del perfil",
        )
        // `bodyMassFromProfile = !massTouched && bodyMassKg != null` no cambia: el valor resuelto
        // sigue siendo no nulo cuando hay sugerencia.
        assertNotNull(resolveSeededMassKg(suggestion, suggestion, profileWeightKg, unitedStates, es))
        // Si la pantalla NO actualizara la semilla, el texto de la sugerencia se re-parsearia
        // redondeado (por eso la sugerencia debe re-sembrar texto y semilla a la vez).
        val staleSeedText = massSeedText(80.0, unitedStates, es)
        assertTrue(resolveSeededMassKg(suggestion, staleSeedText, 80.0, unitedStates, es)!! != profileWeightKg)
        // Sin sugerencia (atleta sin peso, campo vacio) nada se inventa.
        assertNull(resolveSeededMassKg("", "", null, unitedStates, es))
    }

    @Test
    fun `untouched load keeps its exact draft value and the default condition key`() {
        profiles.forEach { (profileName, profile) ->
            listOf(20.0, 0.5).forEach { kg ->
                val text = massSeedText(kg, profile, es)
                val resolved = resolveSeededMassKg(text, text, kg, profile, es)
                assertExactly(kg, resolved, "carga $kg kg en $profileName")
                assertEquals("clave por defecto distinta en $profileName", defaultConditionKey(kg), defaultConditionKey(resolved))
            }
        }
        val resolvedUntouched = resolveSeededMassKg(
            massSeedText(20.0, unitedStates, es),
            massSeedText(20.0, unitedStates, es),
            20.0,
            unitedStates,
            es,
        )
        assertEquals("20kg", defaultConditionKey(resolvedUntouched))
        // Sin la semilla, la forma visible en libras derivaria la carga y con ella la clave por
        // defecto: dejaria de ser 20 kg (44,0925 lb re-parsea 20,000021574225002 kg) y
        // `defaultConditionKey` dejaria de dar "20kg" (daria "20.000021574225002kg").
        val pounds = massSeedText(20.0, unitedStates, es)
        assertEquals("44,0925", pounds)
        val driftedKg = MeasurementFormatting.parseCanonical(pounds, MeasurementQuantity.MASS_KG, unitedStates, es)!!
        assertTrue(driftedKg != 20.0)
        assertTrue(defaultConditionKey(driftedKg).startsWith("20.0000"))
        assertTrue(defaultConditionKey(driftedKg) != "20kg")
        val halfDrift = MeasurementFormatting.parseCanonical(
            massSeedText(0.5, unitedStates, es),
            MeasurementQuantity.MASS_KG,
            unitedStates,
            es,
        )!!
        assertTrue(defaultConditionKey(halfDrift).startsWith("0.4999"))
        assertTrue(defaultConditionKey(halfDrift) != "0.5kg")
    }

    @Test
    fun `height reconciliation re-seeds text and seed when the draft changes from elsewhere`() {
        // Campo intacto sembrado con 0,42 m y el selector de saltos guardados escribiendo 0,335 m.
        val seeded = heightSeedText(0.42, unitedStates, es)
        val reseed = reconcileHeightField(seeded, seeded, 0.42, 0.335, unitedStates, es)
        assertNotNull("la altura nueva debe re-sembrar el texto", reseed)
        assertExactly(0.335, reseed!!.canonicalValue, "semilla re-sembrada")
        // El texto re-sembrado resuelve EXACTAMENTE el valor nuevo (y la pantalla guarda esa semilla).
        assertExactly(
            0.335,
            resolveSeededHeightM(reseed.text, reseed.text, reseed.canonicalValue, unitedStates, es),
            "altura re-sembrada",
        )
        assertExactly(
            0.335,
            resolveSeededHeightM(reseed.text, reseed.text, 0.335, unitedStates, es),
            "altura re-sembrada con la semilla nueva",
        )
        // Sin diferencia real no se pisa lo que el usuario esta tecleando: en metrico "33,5" ES el
        // mismo valor que el borrador (33,5 cm -> 0,335 m) y el campo no se reescribe.
        assertNull(reconcileHeightField(seeded, seeded, 0.42, 0.42, unitedStates, es))
        assertNull(
            reconcileHeightField(
                "33,5",
                heightSeedText(0.42, metric, es),
                0.42,
                0.335,
                metric,
                es,
            ),
        )
        // El re-sembrado es idempotente: el texto ya re-sembrado no dispara otra reescritura.
        assertNull(reconcileHeightField(reseed.text, reseed.text, 0.335, 0.335, unitedStates, es))
        // Borrar la altura desde fuera vacia el campo (no la deja con el valor viejo).
        val cleared = reconcileHeightField(seeded, seeded, 0.42, null, unitedStates, es)
        assertNotNull(cleared)
        assertEquals("", cleared!!.text)
        assertNull(cleared.canonicalValue)
        // Campo y borrador vacios: estado estable, sin reescrituras.
        assertNull(reconcileHeightField("", "", null, null, unitedStates, es))
    }
}
