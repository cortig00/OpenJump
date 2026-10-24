package com.openjump.app.ui.fvp

import com.openjump.app.fvp.FvpAttemptDraft
import com.openjump.app.fvp.FvpConditionDraft
import com.openjump.app.fvp.FvpEligibilityFlag
import com.openjump.app.fvp.FvpPushOffMode
import com.openjump.app.fvp.FvpSeriesInput
import com.openjump.app.fvp.RECOMMENDED_MAX_PUSH_OFF_M
import com.openjump.app.fvp.RECOMMENDED_MIN_PUSH_OFF_M
import com.openjump.app.fvp.checkSeries
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.MAX_JUMP_HEIGHT_M
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Texto de los limites F-V-P (decision D1 del plan 13, slice U2): el usuario debe poder TECLEAR las
 * cifras que se le anuncian y el dominio de ingenieria debe ACEPTARLAS.
 *
 * Contrato que se fija aqui, en literales (nunca calculados a partir del propio helper):
 * - Dominio de h_po `(0,05, 0,80] m` y de la altura `(0, 1,2] m`, en la unidad del CAMPO y con su
 *   precision (1 decimal en cm y en in), redondeando HACIA DENTRO: el extremo inferior ABIERTO se
 *   redondea hacia arriba y el superior CERRADO hacia abajo. Forma `(min–max] unidad`.
 * - La ventana RECOMENDADA 0,20-0,60 m sigue siendo un rango advisory plano
 *   (`20,0–60,0 cm` / `7,9–23,6 in`) y sale de la MISMA constante que usa la elegibilidad.
 *
 * Los literales esperados son: `(5,1–80,0] cm` y `(2,0–31,4] in` para h_po, `(0,0–120,0] cm` y
 * `(0,0–47,2] in` para la altura. Los valores anteriores (`5,0–80,0 cm`, `2,0–31,5 in`) eran
 * INALCANZABLES: 5,0 cm es exactamente el extremo EXCLUIDO y 31,5 in son 0,8001 m, que el motor
 * rechaza. El test de aceptacion de esta clase falla si el numero anunciado esta fuera del dominio.
 *
 * Todos los datos de serie son SINTETICOS y elegidos para disparar (o no) el aviso de ventana
 * recomendada; ninguna altura alega ser una medicion real.
 */
class FvpRangeTextTest {

    private val es = Locale.forLanguageTag("es")
    private val enUs = Locale.forLanguageTag("en-US")

    /** Longitud corta en pulgadas y TODO lo demas metrico: aislado de `UNITED_STATES`. */
    private val customInch = UnitProfile(shortLength = ShortLengthUnit.INCH)

    /** Longitud corta en cm y otra magnitud en US: el texto de estos limites NO debe cambiar. */
    private val customCentimeterWithUsMass =
        UnitProfile(shortLength = ShortLengthUnit.CENTIMETER, mass = MassUnit.POUND)

    private val profiles = listOf(
        UnitProfile.METRIC,
        UnitProfile.UNITED_STATES,
        customInch,
        customCentimeterWithUsMass,
    )

    // --- Forma del texto: redondeo hacia dentro + extremos marcados ---

    @Test
    fun pushOffDomainTextAnnouncesInwardRoundedBounds() {
        assertEquals("(5,1–80,0] cm", pushOffDomainRangeText(UnitProfile.METRIC, es))
        assertEquals("(2,0–31,4] in", pushOffDomainRangeText(UnitProfile.UNITED_STATES, es))

        // Solo la longitud corta decide la unidad de estos textos: el perfil personalizado en
        // pulgadas produce EXACTAMENTE el mismo texto que el preset de EE. UU.
        assertEquals(pushOffDomainRangeText(UnitProfile.UNITED_STATES, es), pushOffDomainRangeText(customInch, es))
        assertEquals("(5,1–80,0] cm", pushOffDomainRangeText(customCentimeterWithUsMass, es))

        // Otro locale cambia SOLO el separador decimal (ver equivalencia mas abajo).
        assertEquals("(5.1–80.0] cm", pushOffDomainRangeText(UnitProfile.METRIC, enUs))
        assertEquals("(2.0–31.4] in", pushOffDomainRangeText(UnitProfile.UNITED_STATES, enUs))
    }

    @Test
    fun jumpHeightDomainTextAnnouncesInwardRoundedBounds() {
        assertEquals("(0,0–120,0] cm", jumpHeightRangeText(UnitProfile.METRIC, es))
        assertEquals("(0,0–47,2] in", jumpHeightRangeText(UnitProfile.UNITED_STATES, es))

        assertEquals(jumpHeightRangeText(UnitProfile.UNITED_STATES, es), jumpHeightRangeText(customInch, es))
        assertEquals("(0,0–120,0] cm", jumpHeightRangeText(customCentimeterWithUsMass, es))

        assertEquals("(0.0–120.0] cm", jumpHeightRangeText(UnitProfile.METRIC, enUs))
        assertEquals("(0.0–47.2] in", jumpHeightRangeText(UnitProfile.UNITED_STATES, enUs))
    }

    // --- ACEPTACION: el dominio acepta las cifras anunciadas ---

    /**
     * El objeto de este slice: las cifras que el usuario LEE tienen que poder teclearse y el dominio
     * tiene que aceptarlas. Se parsea el TEXTO producido (no las constantes) con
     * [MeasurementFormatting.parseCanonical], la misma ruta que el campo del formulario.
     *
     * Falla con el comportamiento anterior en los dos sentidos: el minimo metrico `5,0 cm` parsea a
     * 0,05 m, que NO es `> MIN_PUSH_OFF_M` (extremo excluido), y el maximo de EE. UU. `31,5 in`
     * parsea a 0,8001 m, que NO es `<= MAX_PUSH_OFF_M`.
     */
    @Test
    fun announcedPushOffBoundsAreTypeableAndAcceptedByTheDomain() {
        listOf(es, enUs).forEach { locale ->
            profiles.forEach { profile ->
                val text = pushOffDomainRangeText(profile, locale)
                val (lowerM, upperM) = announcedDomainMeters(text, profile, locale)

                // Se comprueba con el PREDICADO DE PRODUCCION que usa el campo (`isUsablePushOff`,
                // visible para test), no con una copia del criterio: si la UI endureciera o
                // relajara el limite, este test lo detecta.
                assertTrue(
                    "El minimo anunciado de h_po ($text) debe estar ESTRICTAMENTE dentro del dominio " +
                        "(> $MIN_PUSH_OFF_M m) y el predicado de produccion debe ACEPTARLO; " +
                        "el extremo 5,0 cm / 2,0 in (excluido) fallaba aqui",
                    lowerM > MIN_PUSH_OFF_M && isUsablePushOff(lowerM),
                )
                // El extremo EXCLUIDO en si no puede ser usable: el valor que la version anterior
                // anunciaba como minimo metrico (5,0 cm = 0,05 m exactos) queda rechazado.
                assertFalse(
                    "El extremo inferior EXCLUIDO ($MIN_PUSH_OFF_M m) no puede ser usable",
                    isUsablePushOff(MIN_PUSH_OFF_M),
                )
                assertTrue(
                    "El maximo anunciado de h_po ($text) debe ser ACEPTADO por el dominio " +
                        "(<= $MAX_PUSH_OFF_M m) y el predicado de produccion lo rechaza: " +
                        "31,5 in parseaba 0,8001 m y se rechazaba",
                    upperM <= MAX_PUSH_OFF_M && isUsablePushOff(upperM),
                )
                assertTrue("El rango anunciado debe ser creciente: $text", upperM > lowerM)
            }
        }
    }

    /**
     * El dominio de la altura es `(0, 1,2]`: su extremo inferior esta EXCLUIDO, asi que el `0,0`
     * anunciado (no hay minimo positivo que inventar; el parentesis ya lo marca) DEBE ser rechazado
     * por el dominio, y el maximo anunciado aceptado.
     */
    @Test
    fun announcedHeightBoundsExposeTheOpenZeroAndTheAcceptedMaximum() {
        listOf(es, enUs).forEach { locale ->
            profiles.forEach { profile ->
                val text = jumpHeightRangeText(profile, locale)
                val (lowerM, upperM) = announcedDomainMeters(text, profile, locale)

                assertTrue(
                    "El minimo anunciado de altura ($text) es el extremo ABIERTO: 0 no pertenece a (0, $MAX_JUMP_HEIGHT_M]",
                    lowerM <= 0.0,
                )
                assertTrue("El minimo anunciado de altura ($text) no puede ser negativo", lowerM >= 0.0)
                assertTrue(
                    "El maximo anunciado de altura ($text) debe ser ACEPTADO (<= $MAX_JUMP_HEIGHT_M m)",
                    upperM > 0.0 && upperM <= MAX_JUMP_HEIGHT_M,
                )
            }
        }
    }

    /** Regresion del defecto exacto: el texto de EE. UU. anunciaba `31,5 in` = 0,8001 m, rechazado. */
    @Test
    fun unitedStatesDomainTextNeverAnnouncesTheRejectedThirtyOnePointFiveInches() {
        listOf(es, enUs).forEach { locale ->
            val pushOff = pushOffDomainRangeText(UnitProfile.UNITED_STATES, locale)
            val height = jumpHeightRangeText(UnitProfile.UNITED_STATES, locale)

            assertFalse("h_po no puede anunciar 31,5 in: parsea 0,8001 m y el dominio lo rechaza ($pushOff)", pushOff.contains("31,5"))
            assertFalse("h_po no puede anunciar 31.5 in con locale US ($pushOff)", pushOff.contains("31.5"))
            // La altura en pulgadas se anuncia con SU propio maximo: 47,3 in = 1,2014 m quedaria
            // fuera de (0; 1,2] m, asi que el texto no puede citarlo.
            assertFalse("La altura no puede anunciar 47,3 in (1,2014 m, rechazado) ($height)", height.contains("47,3"))
            assertFalse("La altura no puede anunciar 47.3 in con locale US ($height)", height.contains("47.3"))

            // El limite metrico tampoco puede ser el extremo EXCLUIDO (0,05 m = 5,0 cm).
            assertFalse(
                "h_po metrico no puede anunciar 5,0 cm como minimo: es el extremo EXCLUIDO",
                pushOffDomainRangeText(UnitProfile.METRIC, locale).contains("(5,0–"),
            )
        }
    }

    // --- Ventana recomendada: advisory plano, dentro del dominio y con constante unica ---

    @Test
    fun recommendedWindowIsAPlainAdvisoryRangeInsideTheDomainForEveryProfile() {
        assertEquals("20,0–60,0 cm", pushOffRecommendedRangeText(UnitProfile.METRIC, es))
        assertEquals("7,9–23,6 in", pushOffRecommendedRangeText(UnitProfile.UNITED_STATES, es))
        assertEquals(
            pushOffRecommendedRangeText(UnitProfile.UNITED_STATES, es),
            pushOffRecommendedRangeText(customInch, es),
        )
        assertEquals("20,0–60,0 cm", pushOffRecommendedRangeText(customCentimeterWithUsMass, es))

        profiles.forEach { profile ->
            val text = pushOffRecommendedRangeText(profile, es)
            val (lowerM, upperM) = announcedPlainMeters(text, profile, es)

            assertTrue("La ventana recomendada ($text) debe estar dentro del dominio (> $MIN_PUSH_OFF_M)", lowerM > MIN_PUSH_OFF_M)
            assertTrue("La ventana recomendada ($text) debe estar dentro del dominio (<= $MAX_PUSH_OFF_M)", upperM <= MAX_PUSH_OFF_M)
            assertTrue("La ventana recomendada ($text) debe ser creciente", upperM > lowerM)
            assertTrue(
                "La ventana recomendada ($text) no puede salirse de la ventana compartida 0,20-0,60 m",
                lowerM >= RECOMMENDED_MIN_PUSH_OFF_M && upperM <= RECOMMENDED_MAX_PUSH_OFF_M,
            )
        }
    }

    /**
     * La ventana recomendada que se ANUNCIA (texto) y la que se EVALUA (flag) salen de la misma
     * declaracion. Se fija por los dos lados: el texto metrico parsea EXACTAMENTE a las constantes
     * compartidas, y una serie con h_po justo en el limite NO recibe
     * [FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE] mientras que otra justo por debajo (o
     * por encima) SI. Si alguien reintrodujera una copia local con otro valor en la UI, el literal
     * del texto o el valor de la constante dejarian de cuadrar con el flag.
     */
    @Test
    fun recommendedWindowIsTheSameForTheAnnouncedTextAndForTheEligibilityFlag() {
        assertEquals("Limite inferior compartido", 0.20, RECOMMENDED_MIN_PUSH_OFF_M, 0.0)
        assertEquals("Limite superior compartido", 0.60, RECOMMENDED_MAX_PUSH_OFF_M, 0.0)

        // Lado TEXTO (aviso no bloqueante que ve el usuario).
        val (textMinM, textMaxM) = announcedPlainMeters(pushOffRecommendedRangeText(UnitProfile.METRIC, es), UnitProfile.METRIC, es)
        assertEquals("El texto anunciado y la constante compartida deben coincidir", RECOMMENDED_MIN_PUSH_OFF_M, textMinM, 1e-12)
        assertEquals("El texto anunciado y la constante compartida deben coincidir", RECOMMENDED_MAX_PUSH_OFF_M, textMaxM, 1e-12)

        // Lado ELEGIBILIDAD (flag): limites inclusivos.
        assertFalse("0,20 m esta DENTRO de la ventana recomendada", raisesOutsideRecommendedRange(RECOMMENDED_MIN_PUSH_OFF_M))
        assertTrue("Justo por debajo de 0,20 m debe avisar", raisesOutsideRecommendedRange(RECOMMENDED_MIN_PUSH_OFF_M - 0.001))
        assertFalse("0,60 m esta DENTRO de la ventana recomendada", raisesOutsideRecommendedRange(RECOMMENDED_MAX_PUSH_OFF_M))
        assertTrue("Justo por encima de 0,60 m debe avisar", raisesOutsideRecommendedRange(RECOMMENDED_MAX_PUSH_OFF_M + 0.001))
    }

    // --- Locale: solo cambia el separador, no el valor ---

    /**
     * El texto de estos limites NO necesita clave de idioma: [MeasurementFormatting.parseNumber]
     * normaliza ',' y '.' al separador del locale y rechaza dos separadores o espacios, de modo que
     * `5,1` (es) y `5.1` (en-US) resuelven al MISMO canonico. Se comprueban las dos direcciones y el
     * valor numerico literal. Casos que SI corromperian un valor (miles con separador de grupo, mas
     * de un separador, espacios de agrupacion) no pueden aparecer aqui: los extremos anunciados van
     * de 0 a 120 con UN decimal y sin agrupacion.
     */
    @Test
    fun localeOnlyChangesTheSeparatorAndNeverTheCanonicalValue() {
        val esMetric = pushOffDomainRangeText(UnitProfile.METRIC, es)
        val enMetric = pushOffDomainRangeText(UnitProfile.METRIC, enUs)
        assertNotEquals("El separador decimal si cambia con el locale", esMetric, enMetric)

        val (lowerEs, upperEs) = announcedDomainMeters(esMetric, UnitProfile.METRIC, es)
        val (lowerCross, upperCross) = announcedDomainMeters(esMetric, UnitProfile.METRIC, enUs)
        assertEquals("5,1 leido con locale en-US debe valer lo mismo", lowerEs, lowerCross, 0.0)
        assertEquals("80,0 leido con locale en-US debe valer lo mismo", upperEs, upperCross, 0.0)

        val (lowerEn, _) = announcedDomainMeters(enMetric, UnitProfile.METRIC, enUs)
        assertEquals("5.1 leido con locale es debe valer lo mismo", lowerEs, lowerEn, 0.0)

        // Valores literales del canonico: 5,1 cm y 80,0 cm en metros.
        assertEquals(0.051, lowerEs, 1e-12)
        assertEquals(0.800, upperEs, 1e-12)

        val usText = pushOffDomainRangeText(UnitProfile.UNITED_STATES, es)
        val (usLowerEs, usUpperEs) = announcedDomainMeters(usText, UnitProfile.UNITED_STATES, es)
        val (usLowerCross, usUpperCross) = announcedDomainMeters(usText, UnitProfile.UNITED_STATES, enUs)
        assertEquals("2,0 in leido con locale en-US debe valer lo mismo", usLowerEs, usLowerCross, 0.0)
        assertEquals("31,4 in leido con locale en-US debe valer lo mismo", usUpperEs, usUpperCross, 0.0)
    }

    // --- Helpers ---

    /**
     * Extremos ANUNCIADOS de un rango de DOMINIO `(min–max] unidad`, en metros canonicos.
     *
     * Las cifras se extraen del TEXTO producido (nunca de las constantes) y se parsean con
     * [MeasurementFormatting.parseCanonical] en la unidad del campo, igual que el campo del
     * formulario ([parsePushOffInput]); despues se pasan al canonico en METROS, que es el unico
     * dominio que se valida. @param locale locale con el que se LEE el texto (el mismo con el que
     * se formateo, o el otro: la equivalencia de separadores se comprueba en
     * [localeOnlyChangesTheSeparatorAndNeverTheCanonicalValue]).
     */
    private fun announcedDomainMeters(
        text: String,
        unitProfile: UnitProfile,
        locale: Locale,
    ): Pair<Double, Double> {
        assertTrue("El extremo inferior debe marcarse como ABIERTO con '(': $text", text.startsWith("("))
        assertTrue("El extremo superior debe marcarse como CERRADO con ']': $text", text.contains("]"))
        val body = text.removePrefix("(").substringBefore("]")
        assertTrue("El rango debe usar el guion en raya del archivo: $text", body.contains('–'))
        val numbers = body.split('–')
        assertEquals("El rango debe tener exactamente dos extremos: $text", 2, numbers.size)

        val lowerText = numbers[0]
        val upperText = numbers[1]
        val lowerM = parseMeters(lowerText, unitProfile, locale, text)
        val upperM = parseMeters(upperText, unitProfile, locale, text)

        // La cifra anunciada es exactamente la que el campo del formulario acepta.
        val lowerFromField = parsePushOffInput(lowerText, unitProfile, locale)
        assertNotNull("El campo debe aceptar la cifra anunciada '$lowerText' de '$text'", lowerFromField)
        assertEquals("El campo debe interpretar la cifra anunciada igual que la prueba: $text", lowerM, lowerFromField!!, 0.0)
        return lowerM to upperM
    }

    /** Extremos de un rango ADVISORY plano `min–max unidad` (sin marcadores), en metros canonicos. */
    private fun announcedPlainMeters(text: String, unitProfile: UnitProfile, locale: Locale): Pair<Double, Double> {
        assertFalse("La ventana recomendada es advisory y NO lleva marca de apertura: $text", text.startsWith("("))
        assertFalse("La ventana recomendada es advisory y NO lleva marca de cierre: $text", text.contains("]"))
        val numbers = text.substringBefore(' ').split('–')
        assertEquals("El rango debe tener exactamente dos extremos: $text", 2, numbers.size)
        return parseMeters(numbers[0], unitProfile, locale, text) to
            parseMeters(numbers[1], unitProfile, locale, text)
    }

    /** Cifra de la unidad del campo -> canonico en METROS, via la MISMA ruta que el campo. */
    private fun parseMeters(numberText: String, unitProfile: UnitProfile, locale: Locale, text: String): Double {
        val canonicalCm = MeasurementFormatting.parseCanonical(
            numberText,
            MeasurementQuantity.SHORT_LENGTH_CM,
            unitProfile,
            locale,
        )
        assertNotNull("La cifra anunciada '$numberText' debe ser parseable en '$text'", canonicalCm)
        return canonicalCm!! / CENTIMETERS_PER_METER
    }

    /**
     * Serie SINTETICA de 4 condiciones con alturas distintas y 120 fps (elegible, sin otros flags):
     * permite aislar el aviso de ventana recomendada de h_po.
     */
    private fun series(pushOffM: Double): FvpSeriesInput = FvpSeriesInput(
        protocol = FvpProtocol.SJ,
        bodyMassKg = 80.0,
        pushOffMode = FvpPushOffMode.COMMON,
        commonPushOffM = pushOffM,
        conditions = listOf(
            condition("load-0", 0.0, 0.35),
            condition("load-20", 20.0, 0.32),
            condition("load-40", 40.0, 0.29),
            condition("load-60", 60.0, 0.26),
        ),
    )

    private fun condition(conditionKey: String, addedLoadKg: Double, heightM: Double): FvpConditionDraft =
        FvpConditionDraft(
            conditionKey = conditionKey,
            addedLoadKg = addedLoadKg,
            attempts = listOf(
                FvpAttemptDraft(
                    heightM = heightM,
                    method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                    sourceFps = 120.0,
                ),
            ),
        )

    private fun raisesOutsideRecommendedRange(pushOffM: Double): Boolean =
        FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE in checkSeries(series(pushOffM)).flags
}
