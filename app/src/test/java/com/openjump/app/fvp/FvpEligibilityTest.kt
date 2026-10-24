package com.openjump.app.fvp

import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM (JUnit4, sin Android/Room) de la capa de elegibilidad [checkSeries] y su API auxiliar.
 *
 * Reglas de referencia: docs/research/fvp/09-INTEGRATION_GATE_ADDENDUM.md (condiciones 1-6) y
 * docs/research/fvp/10-SCIENTIFIC_GATE_REVIEW.md (B1-B6). Todos los datos son SINTETICOS y
 * elegidos para cubrir cada problema/aviso por separado; ninguna altura alega ser una medicion
 * real de un atleta.
 *
 * Nota de frontera numerica: en [needsThirdAttempt] la referencia es la MENOR altura, por lo que
 * el par 0.3125 / 0.328125 (ambos exactos en binario) da exactamente 5.0 % y el par
 * 0.3125 / 1.05*0.3125 = 0.3284375 da 5.1 %. Con literales decimales no exactos (0.30 / 0.315) el
 * cociente en coma flotante es 5.000000000000004 y la frontera no seria comprobable de forma
 * determinista.
 */
class FvpEligibilityTest {

    // --- Serie de referencia: 4 condiciones, un intento valido a 120 fps, h_po = 0.40 m ---

    @Test
    fun `valid four condition series is eligible with no problems flags or timing warnings`() {
        val result = checkSeries(series())

        assertEquals(4, result.conditionCount)
        assertTrue("no debe haber bloqueos: ${result.blocking}", result.blocking.isEmpty())
        assertTrue("no debe haber avisos: ${result.flags}", result.flags.isEmpty())
        assertEquals(FvpTimingQuality.ALLOW, result.timingQuality)
        assertTrue(result.canCompute)
        assertTrue(result.profileEligible)
    }

    @Test
    fun `single protocol per series is carried through validation`() {
        val cmj = checkSeries(series(protocol = FvpProtocol.CMJ))
        val sj = checkSeries(series(protocol = FvpProtocol.SJ))

        // SJ y CMJ no se mezclan por tipo (un solo protocolo por serie): la validacion es identica
        // y no existe campo de protocolo por condicion que pudiera mezclarlos.
        assertTrue(cmj.canCompute)
        assertTrue(sj.canCompute)
        assertEquals(sj, cmj)
    }

    // --- Problemas bloqueantes, uno por uno ---

    @Test
    fun `invalid body mass blocks for zero negative NaN and infinities`() {
        listOf(0.0, -80.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { mass ->
            val result = checkSeries(series(bodyMassKg = mass))

            assertTrue("masa $mass debe bloquear", result.blocking.contains(FvpEligibilityProblem.INVALID_BODY_MASS))
            assertFalse(result.canCompute)
            assertFalse(result.profileEligible)
        }
        assertFalse(checkSeries(series(bodyMassKg = 0.001)).blocking.contains(FvpEligibilityProblem.INVALID_BODY_MASS))
    }

    @Test
    fun `fewer than two conditions blocks with only too few conditions`() {
        val empty = checkSeries(series(conditions = emptyList()))
        val single = checkSeries(series(conditions = listOf(condition("load-0"))))

        assertEquals(listOf(FvpEligibilityProblem.TOO_FEW_CONDITIONS), empty.blocking)
        assertEquals(listOf(FvpEligibilityProblem.TOO_FEW_CONDITIONS), single.blocking)
        assertTrue(empty.flags.isEmpty())
        assertFalse(empty.canCompute)
        assertFalse(single.canCompute)
    }

    @Test
    fun `two conditions flag diagnostic only and cannot be a normal profile`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                ),
            ),
        )

        assertEquals(2, result.conditionCount)
        assertTrue(result.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS), result.flags)
        assertTrue(result.canCompute)
        assertFalse("2 condiciones son solo diagnostico", result.profileEligible)
    }

    @Test
    fun `three conditions flag few conditions and cannot be a normal profile`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.29))),
                ),
            ),
        )

        assertTrue(result.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.FEW_CONDITIONS), result.flags)
        assertTrue(result.canCompute)
        assertFalse("3 condiciones exigen ruta condicional", result.profileEligible)
    }

    @Test
    fun `four or more conditions carry no condition count flag`() {
        assertTrue(checkSeries(series()).flags.none { it.name.contains("CONDITION") })
        assertTrue(checkSeries(series(conditions = fiveConditions())).flags.isEmpty())
    }

    @Test
    fun `blank condition key blocks while comparison stays exact without trim`() {
        val emptyKey = checkSeries(
            series(conditions = listOf(condition("", 0.0), condition("load-20", 20.0))),
        )
        val spacesKey = checkSeries(
            series(conditions = listOf(condition("   ", 0.0), condition("load-20", 20.0))),
        )
        assertTrue(emptyKey.blocking.contains(FvpEligibilityProblem.INVALID_CONDITION_KEY))
        assertTrue(spacesKey.blocking.contains(FvpEligibilityProblem.INVALID_CONDITION_KEY))
        assertFalse(emptyKey.canCompute)

        // Comparacion EXACTA (sin trim): "load-0" y "load-0 " son claves distintas y validas.
        val trailingSpace = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                    condition("load-0 ", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                ),
            ),
        )
        assertTrue(trailingSpace.blocking.isEmpty())
        assertEquals(2, trailingSpace.conditionCount)
    }

    @Test
    fun `duplicate condition key blocks`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0),
                    condition("load-0", 20.0),
                    condition("load-0", 40.0),
                    condition("load-0", 60.0),
                ),
            ),
        )

        assertTrue(result.blocking.contains(FvpEligibilityProblem.DUPLICATE_CONDITION_KEY))
        assertFalse(result.canCompute)
    }

    @Test
    fun `duplicate added load blocks because one load is one point`() {
        val duplicated = checkSeries(
            series(
                conditions = listOf(
                    condition("load-a", 20.0),
                    condition("load-b", 20.0),
                    condition("load-c", 40.0),
                    condition("load-d", 60.0),
                ),
            ),
        )

        assertTrue(duplicated.blocking.contains(FvpEligibilityProblem.DUPLICATE_LOAD))
        assertFalse(duplicated.canCompute)

        // Sin tolerancia: 20.0 y 20.0000001 son cargas distintas (documentado, comparable exacto).
        val nearlyEqual = checkSeries(
            series(
                conditions = listOf(
                    condition("load-a", 20.0),
                    condition("load-b", 20.0000001),
                    condition("load-c", 40.0),
                    condition("load-d", 60.0),
                ),
            ),
        )
        assertFalse(nearlyEqual.blocking.contains(FvpEligibilityProblem.DUPLICATE_LOAD))
    }

    @Test
    fun `invalid added load blocks for negative NaN and infinity`() {
        listOf(-0.001, -20.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { load ->
            val result = checkSeries(
                series(
                    conditions = listOf(
                        condition("load-a", load),
                        condition("load-b", 20.0),
                        condition("load-c", 40.0),
                        condition("load-d", 60.0),
                    ),
                ),
            )

            assertTrue("carga $load debe bloquear", result.blocking.contains(FvpEligibilityProblem.INVALID_ADDED_LOAD))
        }

        // Dos cargas NaN no deben inventar ademas un DUPLICATE_LOAD: NaN no es una carga comparable.
        val twoNaNs = checkSeries(
            series(
                conditions = listOf(
                    condition("load-a", Double.NaN),
                    condition("load-b", Double.NaN),
                    condition("load-c", 40.0),
                    condition("load-d", 60.0),
                ),
            ),
        )
        assertTrue(twoNaNs.blocking.contains(FvpEligibilityProblem.INVALID_ADDED_LOAD))
        assertFalse(twoNaNs.blocking.contains(FvpEligibilityProblem.DUPLICATE_LOAD))
    }

    @Test
    fun `missing push off blocks when common mode has no value or a condition omits it`() {
        val commonNull = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = null))
        assertTrue(commonNull.blocking.contains(FvpEligibilityProblem.MISSING_PUSH_OFF))
        assertFalse(commonNull.blocking.contains(FvpEligibilityProblem.INVALID_PUSH_OFF))
        assertFalse(commonNull.canCompute)

        val perConditionNull = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, pushOffM = null),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )
        assertTrue(perConditionNull.blocking.contains(FvpEligibilityProblem.MISSING_PUSH_OFF))
        assertFalse(perConditionNull.canCompute)
    }

    @Test
    fun `push off outside the engine domain blocks as invalid`() {
        // Frontera inferior EXCLUSIVA: 0.05 m no pertenece al dominio del motor.
        val lowerBoundary = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = 0.05))
        assertTrue(lowerBoundary.blocking.contains(FvpEligibilityProblem.INVALID_PUSH_OFF))
        assertFalse(lowerBoundary.blocking.contains(FvpEligibilityProblem.MISSING_PUSH_OFF))

        val aboveMaximum = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = 0.81))
        assertTrue(aboveMaximum.blocking.contains(FvpEligibilityProblem.INVALID_PUSH_OFF))

        listOf(0.05, 0.81, 0.0, -0.40, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            val perCondition = checkSeries(
                series(
                    conditions = listOf(
                        condition("load-0", 0.0, pushOffM = value),
                        condition("load-20", 20.0),
                        condition("load-40", 40.0),
                        condition("load-60", 60.0),
                    ),
                ),
            )
            assertTrue(
                "h_po $value debe bloquear",
                perCondition.blocking.contains(FvpEligibilityProblem.INVALID_PUSH_OFF),
            )
        }

        // Frontera superior INCLUSIVA: 0.8 m es valido (y ademas queda fuera de la ventana
        // recomendada 0.20-0.60 m, que es solo aviso).
        val upperBoundary = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = 0.8))
        assertTrue(upperBoundary.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE), upperBoundary.flags)
    }

    @Test
    fun `condition without valid attempt blocks when attempts are absent or all invalid`() {
        val noneValid = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(
                            attempt(heightM = 0.35, valid = false, exclusionReason = "sin partida estatica verificada"),
                            attempt(heightM = 0.32, valid = false, exclusionReason = "rodillas no extendidas"),
                        ),
                    ),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )
        assertTrue(noneValid.blocking.contains(FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT))
        assertFalse("sin intentos validos no se juzga la altura", noneValid.blocking.contains(FvpEligibilityProblem.INVALID_HEIGHT))
        assertFalse(noneValid.canCompute)

        val noAttempts = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = emptyList()),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )
        assertTrue(noAttempts.blocking.contains(FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT))
        assertFalse(noAttempts.canCompute)
    }

    @Test
    fun `invalid selected height blocks for zero negative NaN infinite and above 1_2 m`() {
        listOf(0.0, -0.05, Double.NaN, Double.POSITIVE_INFINITY, 1.3).forEach { height ->
            val result = checkSeries(
                series(
                    conditions = listOf(
                        condition("load-0", 0.0, attempts = listOf(attempt(heightM = height))),
                        condition("load-20", 20.0),
                        condition("load-40", 40.0),
                        condition("load-60", 60.0),
                    ),
                ),
            )

            assertTrue("altura $height debe bloquear", result.blocking.contains(FvpEligibilityProblem.INVALID_HEIGHT))
            assertFalse(result.canCompute)
        }

        // Un intento valido fuera de dominio no puede saltarse en favor de otro menor: es el
        // maximo de los validos, luego bloquea.
        val mixed = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 1.3), attempt(heightM = 0.35))),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )
        assertTrue(mixed.blocking.contains(FvpEligibilityProblem.INVALID_HEIGHT))
        assertFalse(mixed.canCompute)
    }

    @Test
    fun `height 1_2 m is accepted as the inclusive upper limit`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 1.2))),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )

        assertTrue(result.blocking.isEmpty())
        assertTrue(result.canCompute)
    }

    // --- Avisos no bloqueantes ---

    @Test
    fun `push off outside recommended window warns without blocking`() {
        listOf(0.10, 0.19, 0.61, 0.79).forEach { value ->
            val result = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = value))

            assertEquals("h_po $value", listOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE), result.flags)
            assertTrue(result.blocking.isEmpty())
            assertTrue(result.canCompute)
            // Contrato de este slice: el aviso de h_po NO descalifica el perfil normal; el
            // consumidor debe mostrarlo junto al resultado.
            assertTrue(result.profileEligible)
        }

        listOf(0.20, 0.40, 0.60).forEach { value ->
            val result = checkSeries(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = value))

            assertTrue("h_po $value esta en la ventana recomendada", result.flags.isEmpty())
        }
    }

    @Test
    fun `timing quality thresholds follow the gate bands`() {
        assertEquals(FvpTimingQuality.ALLOW, fvpTimingQuality(240.0))
        assertEquals(FvpTimingQuality.ALLOW, fvpTimingQuality(120.0))
        assertEquals(FvpTimingQuality.CONDITIONAL, fvpTimingQuality(119.9))
        assertEquals(FvpTimingQuality.CONDITIONAL, fvpTimingQuality(60.0))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(59.9))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(30.0))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(0.0))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(-1.0))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(Double.NaN))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(Double.POSITIVE_INFINITY))
        assertEquals(FvpTimingQuality.BLOCKED, fvpTimingQuality(Double.NEGATIVE_INFINITY))
        assertEquals(FvpTimingQuality.UNKNOWN, fvpTimingQuality(null))
    }

    @Test
    fun `conditional timing warns and keeps computation but not profile eligibility`() {
        val result = checkSeries(series(conditions = fourConditions(fps = 90.0)))

        assertTrue(result.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.TIMING_CONDITIONAL), result.flags)
        assertEquals(FvpTimingQuality.CONDITIONAL, result.timingQuality)
        assertTrue("el calculo sigue autorizado; el producto decide la sensibilidad temporal", result.canCompute)
        assertFalse(result.profileEligible)
    }

    @Test
    fun `blocked timing warns and keeps computation but not profile eligibility`() {
        val result = checkSeries(series(conditions = fourConditions(fps = 30.0)))

        assertTrue(result.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.TIMING_BLOCKED), result.flags)
        assertEquals(FvpTimingQuality.BLOCKED, result.timingQuality)
        assertTrue(result.canCompute)
        assertFalse(result.profileEligible)
    }

    @Test
    fun `unknown fps on the selected attempt is not eligible automatically`() {
        val result = checkSeries(series(conditions = fourConditions(fps = null)))

        assertTrue(result.blocking.isEmpty())
        assertEquals(listOf(FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE), result.flags)
        assertEquals(FvpTimingQuality.UNKNOWN, result.timingQuality)
        assertFalse(result.profileEligible)
    }

    @Test
    fun `timing aggregates the worst selected attempt with unknown worse than blocked`() {
        val conditional = checkSeries(series(conditions = fourConditions(fps = 120.0, overrideFps = mapOf(1 to 90.0))))
        assertEquals(FvpTimingQuality.CONDITIONAL, conditional.timingQuality)

        val blocked = checkSeries(series(conditions = fourConditions(fps = 120.0, overrideFps = mapOf(2 to 30.0))))
        assertEquals(FvpTimingQuality.BLOCKED, blocked.timingQuality)

        val unknownBeatsBlocked = checkSeries(
            series(conditions = fourConditions(fps = 120.0, overrideFps = mapOf(1 to null, 2 to 30.0))),
        )
        assertEquals("un fps desconocido no se ignora al agregar", FvpTimingQuality.UNKNOWN, unknownBeatsBlocked.timingQuality)
        assertTrue(unknownBeatsBlocked.flags.contains(FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE))
        assertFalse(unknownBeatsBlocked.flags.contains(FvpEligibilityFlag.TIMING_BLOCKED))
    }

    @Test
    fun `timing ignores attempts that are not the selected valid attempt`() {
        // El intento mas alto NO es valido (fps desconocidos) y no debe contaminar la calidad
        // temporal: la seleccion es por intento VALIDO.
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(
                            attempt(heightM = 0.50, sourceFps = null, valid = false, exclusionReason = "tecnica no valida"),
                            attempt(heightM = 0.35, sourceFps = 120.0),
                        ),
                    ),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.29))),
                    condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.26))),
                ),
            ),
        )

        assertEquals(FvpTimingQuality.ALLOW, result.timingQuality)
        assertTrue(result.flags.isEmpty())
        assertTrue(result.profileEligible)
    }

    @Test
    fun `profile eligibility requires four conditions and allow timing everywhere`() {
        assertTrue(checkSeries(series()).profileEligible)
        assertTrue(checkSeries(series(conditions = fiveConditions(fps = 120.0))).profileEligible)
        assertTrue(checkSeries(series(conditions = fourConditions(fps = 240.0))).profileEligible)

        assertFalse(checkSeries(series(conditions = fourConditions(fps = 119.9))).profileEligible)
        assertFalse(checkSeries(series(conditions = fourConditions(fps = 90.0))).profileEligible)
        assertFalse(checkSeries(series(conditions = fourConditions(fps = 30.0))).profileEligible)
        assertFalse(checkSeries(series(conditions = fourConditions(fps = null))).profileEligible)
        assertFalse(checkSeries(series(conditions = twoConditions())).profileEligible)
        assertFalse(checkSeries(series(conditions = threeConditions())).profileEligible)
        assertFalse(checkSeries(series(conditions = emptyList())).profileEligible)
    }

    // --- Seleccion y mapeo a la entrada del motor ---

    @Test
    fun `selected condition inputs pick the valid max height attempt ignoring taller invalid ones`() {
        val inputs: List<FvpConditionInput> = requireNotNull(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(
                            attempt(heightM = 0.50, sourceFps = 240.0, valid = false, exclusionReason = "contramovimiento"),
                            attempt(heightM = 0.35, sourceFps = 120.0),
                        ),
                    ),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.29))),
                    condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.26))),
                ),
            ).selectedConditionInputs(),
        )

        assertEquals(listOf("load-0", "load-20", "load-40", "load-60"), inputs.map { it.conditionId })
        assertEquals(0.35, inputs[0].heightM, 1e-9)
        assertEquals(80.0, inputs[0].totalMassKg, 1e-9)
        assertEquals(0.40, inputs[0].pushOffM, 1e-9)
    }

    @Test
    fun `selected condition inputs keep the first attempt on equal heights`() {
        // Empate de altura: gana el PRIMERO en orden de entrada. Observable por la calidad temporal
        // (el intento empatado primero a 30 fps bloquea; si ganara el segundo, seria elegible).
        val blockedFirst = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(attempt(heightM = 0.35, sourceFps = 30.0), attempt(heightM = 0.35, sourceFps = 240.0)),
                    ),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )
        val allowFirst = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(attempt(heightM = 0.35, sourceFps = 240.0), attempt(heightM = 0.35, sourceFps = 30.0)),
                    ),
                    condition("load-20", 20.0),
                    condition("load-40", 40.0),
                    condition("load-60", 60.0),
                ),
            ),
        )

        assertEquals(FvpTimingQuality.BLOCKED, blockedFirst.timingQuality)
        assertEquals(FvpTimingQuality.ALLOW, allowFirst.timingQuality)
    }

    @Test
    fun `selected condition inputs order by key and compute system mass`() {
        val input = series(
            conditions = listOf(
                condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.24))),
                condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.28))),
                condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.31))),
            ),
        )

        val inputs: List<FvpConditionInput> = requireNotNull(input.selectedConditionInputs())

        assertEquals(listOf("load-0", "load-20", "load-40", "load-60"), inputs.map { it.conditionId })
        assertEquals(listOf(80.0, 100.0, 120.0, 140.0), inputs.map { it.totalMassKg })
        assertEquals(listOf(0.35, 0.31, 0.28, 0.24), inputs.map { it.heightM })
        assertEquals(listOf(0.40, 0.40, 0.40, 0.40), inputs.map { it.pushOffM })
    }

    @Test
    fun `selected condition inputs use the common push off in common mode`() {
        val input = series(
            pushOffMode = FvpPushOffMode.COMMON,
            commonPushOffM = 0.55,
            conditions = listOf(
                condition("load-0", 0.0, pushOffM = null, attempts = listOf(attempt(heightM = 0.35))),
                condition("load-20", 20.0, pushOffM = 0.10, attempts = listOf(attempt(heightM = 0.31))),
                condition("load-40", 40.0, pushOffM = null, attempts = listOf(attempt(heightM = 0.28))),
                condition("load-60", 60.0, pushOffM = null, attempts = listOf(attempt(heightM = 0.24))),
            ),
        )

        val inputs: List<FvpConditionInput> = requireNotNull(input.selectedConditionInputs())

        assertEquals(listOf(0.55, 0.55, 0.55, 0.55), inputs.map { it.pushOffM })
        // El h_po por condicion (0.10) se ignora en modo COMMON y no genera avisos.
        assertTrue(checkSeries(input).flags.isEmpty())
    }

    @Test
    fun `selected condition inputs return null when the series is blocked`() {
        assertNull(series(conditions = emptyList()).selectedConditionInputs())
        assertNull(series(bodyMassKg = 0.0).selectedConditionInputs())
        assertNull(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = null).selectedConditionInputs())
        assertNull(series(conditions = duplicateLoadConditions()).selectedConditionInputs())
        assertNull(series(conditions = fourConditions(fps = 120.0, heightM = 1.3)).selectedConditionInputs())
    }

    @Test
    fun `effective push off follows the declared series mode`() {
        val perCondition = series(pushOffMode = FvpPushOffMode.PER_CONDITION, commonPushOffM = 0.90)
        assertEquals(0.55, perCondition.effectivePushOffM(condition("load-0", 0.0, pushOffM = 0.55)))
        assertNull(perCondition.effectivePushOffM(condition("load-0", 0.0, pushOffM = null)))
        assertNull(perCondition.effectivePushOffM(condition("load-0", 0.0, pushOffM = 0.05)))
        assertNull(perCondition.effectivePushOffM(condition("load-0", 0.0, pushOffM = 0.81)))
        assertNull(perCondition.effectivePushOffM(condition("load-0", 0.0, pushOffM = Double.NaN)))

        val common = series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = 0.55)
        assertEquals(0.55, common.effectivePushOffM(condition("load-0", 0.0, pushOffM = null)))
        assertEquals(0.55, common.effectivePushOffM(condition("load-0", 0.0, pushOffM = 0.20)))
        assertNull(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = null).effectivePushOffM(condition("load-0")))
        assertNull(series(pushOffMode = FvpPushOffMode.COMMON, commonPushOffM = 0.05).effectivePushOffM(condition("load-0")))
    }

    // --- Tercer intento (gate B3) ---

    @Test
    fun `needs third attempt uses a strict five percent boundary and the lower height as reference`() {
        // 0.3125 * 1.05 = 0.328125 exacto en binario: la diferencia es exactamente 5.0 % -> false.
        assertFalse(needsThirdAttempt(0.3125, 0.328125))
        assertFalse(needsThirdAttempt(0.328125, 0.3125))
        assertFalse(needsThirdAttempt(0.35, 0.35))

        // 5.1 % -> true en ambos ordenes.
        assertTrue(needsThirdAttempt(0.3125, 0.3284375))
        assertTrue(needsThirdAttempt(0.3284375, 0.3125))

        // Casos claros y umbral configurable.
        assertFalse(needsThirdAttempt(0.30, 0.31))
        assertTrue(needsThirdAttempt(0.30, 0.36))
        assertFalse(needsThirdAttempt(0.3125, 0.3284375, thresholdPercent = 10.0))
        assertTrue(needsThirdAttempt(0.3125, 0.3284375, thresholdPercent = 1.0))
    }

    @Test
    fun `needs third attempt returns false for unusable heights instead of throwing`() {
        assertFalse(needsThirdAttempt(Double.NaN, 0.35))
        assertFalse(needsThirdAttempt(0.35, Double.NaN))
        assertFalse(needsThirdAttempt(0.0, 0.35))
        assertFalse(needsThirdAttempt(-0.35, 0.35))
        assertFalse(needsThirdAttempt(Double.POSITIVE_INFINITY, 0.35))
        assertFalse(needsThirdAttempt(0.35, Double.NEGATIVE_INFINITY))
        assertFalse(needsThirdAttempt(0.30, 0.36, thresholdPercent = Double.NaN))
    }

    // --- Gate B3 en el DOMINIO: un intento NO valido exige motivo de exclusion -------------------

    /**
     * La regla vivia SOLO en la UI (`FvpConditionsScreen` deshabilitaba Calcular): aqui se comprueba
     * que es una regla de DOMINIO, de modo que el repositorio (que re-ejecuta [checkSeries]) o
     * cualquier otro llamante tampoco puede persistir el descarte sin justificar.
     */
    @Test
    fun `invalid attempt without exclusion reason blocks for null empty and blank reasons`() {
        listOf(null, "", "   ", "\t\n ").forEach { reason ->
            val result = checkSeries(
                seriesWithFirstAttempts(
                    listOf(
                        attempt(heightM = 0.55, valid = false, exclusionReason = reason),
                        attempt(heightM = 0.35),
                    ),
                ),
            )

            assertTrue(
                "motivo ${reason?.let { "'$it'" } ?: "null"} debe bloquear",
                result.blocking.contains(FvpEligibilityProblem.EXCLUSION_REASON_MISSING),
            )
            assertFalse(result.canCompute)
            assertFalse(result.profileEligible)
        }
    }

    @Test
    fun `valid attempt does not need an exclusion reason`() {
        val result = checkSeries(seriesWithFirstAttempts(listOf(attempt(heightM = 0.35))))

        assertFalse(result.blocking.contains(FvpEligibilityProblem.EXCLUSION_REASON_MISSING))
        assertTrue("no debe haber bloqueos: ${result.blocking}", result.blocking.isEmpty())
        assertTrue(result.canCompute)
    }

    @Test
    fun `exclusion reason blocks every condition of the series not just the first`() {
        val base = fourConditions()
        val result = checkSeries(
            series(
                conditions = base.mapIndexed { index, condition ->
                    if (index != 3) {
                        condition
                    } else {
                        condition.copy(
                            attempts = listOf(attempt(heightM = 0.26, valid = false, exclusionReason = "  ")),
                        )
                    }
                },
            ),
        )

        assertTrue(result.blocking.contains(FvpEligibilityProblem.EXCLUSION_REASON_MISSING))
        // La ultima condicion queda ADEMAS sin intento valido: los dos motivos son independientes.
        assertTrue(result.blocking.contains(FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT))
        assertFalse(result.canCompute)
    }

    // --- Metodos de medida de los intentos SELECCIONADOS ----------------------------------------

    /**
     * La mezcla se mide SOLO entre los puntos que de verdad entran en el ajuste: el intento
     * seleccionado de cada condicion. Aqui la primera condicion selecciona `MANUAL` y las otras tres
     * video -> aviso no bloqueante, ni [FvpEligibilityProblem.EXCLUSION_REASON_MISSING] ni cambio de
     * `profileEligible` (el flag no es temporal).
     */
    @Test
    fun `mixed methods among selected attempts flag without blocking`() {
        val base = fourConditions()
        val mixed = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(
                            FvpAttemptDraft(
                                heightM = 0.35,
                                method = FvpMeasureMethod.MANUAL,
                                sourceFps = 120.0,
                            ),
                        ),
                    ),
                ) + base.drop(1),
            ),
        )

        assertEquals(listOf(FvpEligibilityFlag.MIXED_SELECTED_METHODS), mixed.flags)
        assertTrue("la mezcla avisa, no bloquea", mixed.canCompute)
        assertTrue(mixed.blocking.isEmpty())
        assertTrue("el flag no es temporal: la ruta de perfil normal no cambia", mixed.profileEligible)
    }

    @Test
    fun `discarded attempts with another method do not flag mixed methods`() {
        // Primer intento: valido pero NO seleccionado (altura menor) con otro metodo. Segundo:
        // invalido y mas alto con otro metodo. El ganador (y unico punto de la condicion) es video,
        // igual que las otras tres condiciones: NO hay mezcla de metodos seleccionados.
        val result = checkSeries(
            seriesWithFirstAttempts(
                listOf(
                    FvpAttemptDraft(heightM = 0.30, method = FvpMeasureMethod.MANUAL, sourceFps = 120.0),
                    FvpAttemptDraft(
                        heightM = 0.50,
                        method = FvpMeasureMethod.IMPORTED,
                        sourceFps = 120.0,
                        valid = false,
                        exclusionReason = "tecnica",
                    ),
                    attempt(heightM = 0.35),
                ),
            ),
        )

        assertFalse(result.flags.contains(FvpEligibilityFlag.MIXED_SELECTED_METHODS))
        assertTrue(result.blocking.isEmpty())
        assertTrue(result.canCompute)
    }

    @Test
    fun `single measurement method for the whole series carries no mixed warning`() {
        val uniform = checkSeries(
            series(
                conditions = fourConditions().map { condition ->
                    condition.copy(
                        attempts = condition.attempts.map { it.copy(method = FvpMeasureMethod.IMPORTED) },
                    )
                },
            ),
        )

        assertFalse(uniform.flags.contains(FvpEligibilityFlag.MIXED_SELECTED_METHODS))
        assertTrue(uniform.profileEligible)
    }

    // --- Determinismo ---

    @Test
    fun `check series is deterministic and independent of the condition order`() {
        val input = series(
            conditions = listOf(
                condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.30))),
                condition("load-0", 0.0, pushOffM = 0.10, attempts = listOf(attempt(heightM = 0.35, sourceFps = 90.0))),
                condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.28))),
                condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.31))),
            ),
        )

        val first = checkSeries(input)
        val second = checkSeries(input)
        assertEquals(first, second)
        assertEquals(first.blocking, second.blocking)
        assertEquals(first.flags, second.flags)

        val reversed = checkSeries(input.copy(conditions = input.conditions.reversed()))
        assertEquals(first.blocking, reversed.blocking)
        assertEquals(first.flags, reversed.flags)
        assertEquals(first.timingQuality, reversed.timingQuality)
        assertEquals(input.selectedConditionInputs(), input.copy(conditions = input.conditions.reversed()).selectedConditionInputs())
    }

    @Test
    fun `check series reports every independent problem at once in enum order`() {
        val input = series(
            bodyMassKg = Double.NaN,
            conditions = listOf(
                condition("", 20.0, pushOffM = null, attempts = emptyList()),
                condition("", 20.0, pushOffM = 0.81, attempts = listOf(attempt(heightM = 1.3))),
            ),
        )

        val result = checkSeries(input)

        assertEquals(
            listOf(
                FvpEligibilityProblem.INVALID_BODY_MASS,
                FvpEligibilityProblem.INVALID_CONDITION_KEY,
                FvpEligibilityProblem.DUPLICATE_CONDITION_KEY,
                FvpEligibilityProblem.DUPLICATE_LOAD,
                FvpEligibilityProblem.MISSING_PUSH_OFF,
                FvpEligibilityProblem.INVALID_PUSH_OFF,
                FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT,
                FvpEligibilityProblem.INVALID_HEIGHT,
            ),
            result.blocking,
        )
        // TOO_FEW_CONDITIONS no aparece: hay 2 condiciones (diagnostico), no menos de 2.
        assertFalse(result.blocking.contains(FvpEligibilityProblem.TOO_FEW_CONDITIONS))
        assertEquals(listOf(FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS), result.flags)
        assertFalse(result.canCompute)
    }

    // --- Regla CANONICA de seleccion ([selectedValidAttempt]/[selectedValidAttemptIndex]) --------

    @Test
    fun `canonical selection picks the valid max height ignoring taller invalid and non finite attempts`() {
        val input = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = 0.55, sourceFps = 240.0, valid = false, exclusionReason = "contramovimiento"),
                attempt(heightM = Double.NaN, sourceFps = 240.0),
                attempt(heightM = 0.0, sourceFps = 240.0),
                attempt(heightM = 0.42, sourceFps = 240.0),
                attempt(heightM = -0.30, sourceFps = 240.0, valid = false, exclusionReason = "caida"),
            ),
        )
        val condition = input.conditions.first()

        assertEquals("gana el maximo VALIDO con altura utilizable", 3, input.selectedValidAttemptIndex(condition))
        assertEquals(0.42, requireNotNull(input.selectedValidAttempt(condition)).heightM, 1e-9)
        // La seleccion y el dominio son responsabilidades separadas: la serie queda bloqueada (falla
        // en cerrado) por los intentos fuera de dominio, pero la seleccion sigue siendo determinista.
        assertEquals(listOf(FvpEligibilityProblem.INVALID_HEIGHT), checkSeries(input).blocking)
    }

    @Test
    fun `canonical selection keeps the first attempt on equal heights`() {
        val allowFirst = seriesWithFirstAttempts(
            listOf(attempt(heightM = 0.35, sourceFps = 240.0), attempt(heightM = 0.35, sourceFps = 30.0)),
        )
        val blockedFirst = seriesWithFirstAttempts(
            listOf(attempt(heightM = 0.35, sourceFps = 30.0), attempt(heightM = 0.35, sourceFps = 240.0)),
        )

        assertEquals(0, allowFirst.selectedValidAttemptIndex(allowFirst.conditions.first()))
        assertEquals(
            240.0,
            requireNotNull(allowFirst.selectedValidAttempt(allowFirst.conditions.first())).sourceFps!!,
            0.0,
        )
        // Serie espejo: el empate lo gana SIEMPRE el primero en orden de entrada (observable en la
        // calidad temporal agregada, que usa el intento seleccionado).
        assertEquals(0, blockedFirst.selectedValidAttemptIndex(blockedFirst.conditions.first()))
        assertEquals(FvpTimingQuality.ALLOW, checkSeries(allowFirst).timingQuality)
        assertEquals(FvpTimingQuality.BLOCKED, checkSeries(blockedFirst).timingQuality)
    }

    @Test
    fun `canonical selection returns null without a valid usable attempt and never throws`() {
        val allInvalid = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = 0.50, valid = false, exclusionReason = "tecnica"),
                attempt(heightM = Double.NaN, valid = false, exclusionReason = "tecnica"),
            ),
        )
        val noAttempts = seriesWithFirstAttempts(emptyList())

        assertNull(allInvalid.selectedValidAttemptIndex(allInvalid.conditions.first()))
        assertNull(allInvalid.selectedValidAttempt(allInvalid.conditions.first()))
        assertNull(noAttempts.selectedValidAttemptIndex(noAttempts.conditions.first()))
        assertNull(noAttempts.selectedValidAttempt(noAttempts.conditions.first()))
        assertTrue(checkSeries(allInvalid).blocking.contains(FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT))
    }

    @Test
    fun `canonical selection is stable when unrelated conditions are reordered`() {
        val input = series(
            conditions = listOf(
                condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.30))),
                condition(
                    "load-0",
                    0.0,
                    attempts = listOf(
                        attempt(heightM = 0.50, sourceFps = 240.0, valid = false, exclusionReason = "tecnica"),
                        attempt(heightM = 0.35, sourceFps = 90.0),
                        attempt(heightM = 0.35, sourceFps = 30.0),
                    ),
                ),
                condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.31))),
                condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.28))),
            ),
        )
        val reordered = input.copy(conditions = input.conditions.reversed())
        val condition = input.conditions.first { it.conditionKey == "load-0" }
        val sameCondition = reordered.conditions.first { it.conditionKey == "load-0" }

        assertEquals(1, input.selectedValidAttemptIndex(condition))
        assertEquals(1, reordered.selectedValidAttemptIndex(sameCondition))
        assertEquals(input.selectedValidAttempt(condition), reordered.selectedValidAttempt(sameCondition))
        assertEquals(input.selectedConditionInputs(), reordered.selectedConditionInputs())
    }

    @Test
    fun `selected condition inputs keep their order values and contract after the selector extraction`() {
        val input = series(
            conditions = listOf(
                condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.24))),
                condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.28))),
                condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.31))),
            ),
        )
        val expected = listOf(
            FvpConditionInput(conditionId = "load-0", totalMassKg = 80.0, heightM = 0.35, pushOffM = H_PO_M),
            FvpConditionInput(conditionId = "load-20", totalMassKg = 100.0, heightM = 0.31, pushOffM = H_PO_M),
            FvpConditionInput(conditionId = "load-40", totalMassKg = 120.0, heightM = 0.28, pushOffM = H_PO_M),
            FvpConditionInput(conditionId = "load-60", totalMassKg = 140.0, heightM = 0.24, pushOffM = H_PO_M),
        )

        assertEquals(expected, input.selectedConditionInputs())
        input.conditions.forEach { each ->
            assertEquals(each.conditionKey, 0, input.selectedValidAttemptIndex(each))
        }
    }

    // --- Paridad de dominio con la persistencia (FvpDao.save valida TODOS los intentos) ---------

    @Test
    fun `invalid attempt with NaN height blocks eligibility before persistence`() {
        val input = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = Double.NaN, sourceFps = null, valid = false, exclusionReason = "sin partida"),
                attempt(heightM = 0.35, sourceFps = 120.0),
            ),
        )
        val result = checkSeries(input)

        assertEquals(listOf(FvpEligibilityProblem.INVALID_HEIGHT), result.blocking)
        assertFalse(result.canCompute)
        assertFalse(result.profileEligible)
        assertNull("bloqueada no hay entrada al motor", input.selectedConditionInputs())
        // El intento invalido sin altura no contamina la seleccion: sigue ganando el valido 0.35 m.
        assertEquals(0.35, requireNotNull(input.selectedValidAttempt(input.conditions.first())).heightM, 1e-9)
    }

    @Test
    fun `invalid attempt above the domain blocks eligibility before persistence`() {
        val input = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = 1.3, sourceFps = 240.0, valid = false, exclusionReason = "tecnica"),
                attempt(heightM = 0.35, sourceFps = 120.0),
            ),
        )
        val result = checkSeries(input)

        assertEquals(listOf(FvpEligibilityProblem.INVALID_HEIGHT), result.blocking)
        assertNull(input.selectedConditionInputs())
    }

    @Test
    fun `attempt height at the inclusive 1_2 m boundary stays valid for invalid attempts too`() {
        val input = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = 1.2, sourceFps = 240.0, valid = false, exclusionReason = "tecnica"),
                attempt(heightM = 0.35, sourceFps = 120.0),
            ),
        )
        val result = checkSeries(input)

        assertTrue("1.2 m es el limite superior INCLUSIVO: ${result.blocking}", result.blocking.isEmpty())
        assertTrue(result.canCompute)
        assertEquals(1, input.selectedValidAttemptIndex(input.conditions.first()))
    }

    @Test
    fun `condition with NaN added load and an invalid attempt blocks before persistence`() {
        val input = series(
            conditions = listOf(
                condition(
                    "load-0",
                    Double.NaN,
                    attempts = listOf(
                        attempt(heightM = 0.50, sourceFps = 240.0, valid = false, exclusionReason = "tecnica"),
                        attempt(heightM = 0.35, sourceFps = 120.0),
                    ),
                ),
                condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
                condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.29))),
                condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.26))),
            ),
        )
        val result = checkSeries(input)

        assertEquals(listOf(FvpEligibilityProblem.INVALID_ADDED_LOAD), result.blocking)
        assertFalse(result.canCompute)
        assertNull(input.selectedConditionInputs())
    }

    @Test
    fun `valid series with an in domain invalid attempt still computes and selects the valid attempt`() {
        val input = seriesWithFirstAttempts(
            listOf(
                attempt(heightM = 0.50, sourceFps = 240.0, valid = false, exclusionReason = "contramovimiento"),
                attempt(heightM = 0.35, sourceFps = 120.0),
            ),
        )
        val result = checkSeries(input)

        assertTrue("un intento invalido DENTRO de dominio no debe bloquear: ${result.blocking}", result.canCompute)
        assertEquals(1, input.selectedValidAttemptIndex(input.conditions.first()))
        val inputs = requireNotNull(input.selectedConditionInputs())
        assertEquals(listOf("load-0", "load-20", "load-40", "load-60"), inputs.map { it.conditionId })
        assertEquals(listOf(0.35, 0.32, 0.29, 0.26), inputs.map { it.heightM })
    }

    // --- P2 (Wave 4a): alturas seleccionadas identicas -> bloqueo ACCIONABLE antes del motor ---

    /**
     * La velocidad del modelo depende SOLO de la altura (`v = sqrt(g * h / 2)`): con todas las
     * alturas seleccionadas iguales, todas las velocidades son iguales y el motor rechaza la serie
     * ("all measured velocities are identical: slope undefined"). Antes eso pasaba elegibilidad y
     * la UI quedaba con Calcular deshabilitado y un banner sin detalle; ahora la elegibilidad lo
     * bloquea con un motivo accionable. El motor NO se toca: sigue siendo la autoridad.
     */
    @Test
    fun `identical selected heights block with the actionable problem`() {
        val input = series(
            conditions = listOf(0.0, 20.0, 40.0, 60.0).map { load ->
                condition("load-${load.toInt()}", load, attempts = listOf(attempt(heightM = 0.30)))
            },
        )
        val result = checkSeries(input)

        assertTrue(
            "todas las alturas iguales deben bloquear: ${result.blocking}",
            result.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS),
        )
        assertFalse(result.canCompute)
        assertNull("sin bloqueo accionable no se prepara la entrada del motor", input.selectedConditionInputs())
    }

    /**
     * Frontera inferior: DOS condiciones con la misma altura ya son todas las selecciones iguales
     * (minimo [MIN_CONDITIONS]), asi que se bloquean igual que con cuatro.
     */
    @Test
    fun `two conditions with the same selected height are blocked`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.30))),
                ),
            ),
        )

        assertTrue(result.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS))
        assertFalse(result.canCompute)
    }

    /** Alturas distintas: la regla nueva no anade ningun bloqueo (el motor sigue siendo la autoridad). */
    @Test
    fun `distinct selected heights do not add the identical heights problem`() {
        val result = checkSeries(series())

        assertTrue("sin alturas todas iguales no hay bloqueo nuevo: ${result.blocking}", result.blocking.isEmpty())
        assertTrue(result.canCompute)
    }

    /**
     * Repetir UNA altura no se bloquea mientras el conjunto de alturas SELECCIONADAS no sea todo
     * igual: la regla es "no repetir la MISMA altura en TODAS las condiciones", no "sin empates".
     */
    @Test
    fun `repeating one selected height is allowed while the selected set is not all equal`() {
        val result = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.35))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.32))),
                    condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.28))),
                ),
            ),
        )

        assertFalse(result.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS))
        assertTrue("sin alturas todas iguales no hay bloqueo nuevo: ${result.blocking}", result.canCompute)
    }

    /**
     * La comprobacion usa las alturas SELECCIONADAS (regla canonica), no todas las declaradas: un
     * intento INVALIDO de otra altura no cuenta (arriba), y basta que UNA seleccion difiera para no
     * bloquear (abajo).
     */
    @Test
    fun `identical heights rule looks at the selected attempts not at every attempt`() {
        val allEqualSelectionsButInvalidExtra = checkSeries(
            series(
                conditions = listOf(
                    condition(
                        "load-0",
                        0.0,
                        attempts = listOf(
                            attempt(heightM = 0.60, valid = false, exclusionReason = "tecnica"),
                            attempt(heightM = 0.30),
                        ),
                    ),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.30))),
                ),
            ),
        )
        assertTrue(allEqualSelectionsButInvalidExtra.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS))

        val oneSelectionDiffers = checkSeries(
            series(
                conditions = listOf(
                    condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.30))),
                    condition("load-60", 60.0, attempts = listOf(attempt(heightM = 0.31))),
                ),
            ),
        )
        assertFalse(oneSelectionDiffers.blocking.contains(FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS))
        assertTrue(oneSelectionDiffers.canCompute)
    }

    // --- Helpers ---

    private fun attempt(
        heightM: Double = 0.35,
        sourceFps: Double? = 120.0,
        valid: Boolean = true,
        exclusionReason: String? = null,
    ): FvpAttemptDraft = FvpAttemptDraft(
        heightM = heightM,
        method = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
        sourceFps = sourceFps,
        valid = valid,
        exclusionReason = exclusionReason,
    )

    private fun condition(
        conditionKey: String,
        addedLoadKg: Double = 0.0,
        pushOffM: Double? = H_PO_M,
        attempts: List<FvpAttemptDraft> = listOf(attempt()),
    ): FvpConditionDraft = FvpConditionDraft(
        conditionKey = conditionKey,
        addedLoadKg = addedLoadKg,
        pushOffM = pushOffM,
        attempts = attempts,
    )

    private fun series(
        conditions: List<FvpConditionDraft> = fourConditions(),
        bodyMassKg: Double = BODY_MASS_KG,
        pushOffMode: FvpPushOffMode = FvpPushOffMode.PER_CONDITION,
        commonPushOffM: Double? = null,
        protocol: FvpProtocol = FvpProtocol.SJ,
    ): FvpSeriesInput = FvpSeriesInput(
        protocol = protocol,
        bodyMassKg = bodyMassKg,
        pushOffMode = pushOffMode,
        commonPushOffM = commonPushOffM,
        conditions = conditions,
    )

    /**
     * 4 condiciones de carga creciente (0/20/40/60 kg) con alturas DECRECIENTES y DISTINTAS: la
     * velocidad del modelo depende solo de la altura (`v = sqrt(g * h / 2)`), de modo que la serie
     * de referencia debe ser ajustable de verdad. `heightM` es la altura de la PRIMERA condicion;
     * las demas usan las alturas LITERALES de [HEIGHTS_AFTER_FIRST] (distintas y exactas).
     */
    private fun fourConditions(
        fps: Double? = 120.0,
        heightM: Double = FIRST_HEIGHT_M,
        overrideFps: Map<Int, Double?> = emptyMap(),
    ): List<FvpConditionDraft> = listOf(0.0, 20.0, 40.0, 60.0).mapIndexed { index, load ->
        condition(
            conditionKey = "load-${load.toInt()}",
            addedLoadKg = load,
            // containsKey distingue "sin override" de "override a null" (fps desconocidos): con
            // `?:` un override nulo caeria en el valor por defecto y el test no comprobaria nada.
            attempts = listOf(
                attempt(
                    heightM = if (index == 0) heightM else HEIGHTS_AFTER_FIRST[index - 1],
                    sourceFps = if (overrideFps.containsKey(index)) overrideFps[index] else fps,
                ),
            ),
        )
    }

    /**
     * Serie de referencia con los intentos de la PRIMERA condicion (`load-0`) sustituidos: permite
     * aislar los casos de seleccion y de dominio de altura sin repetir la serie completa.
     */
    private fun seriesWithFirstAttempts(attempts: List<FvpAttemptDraft>): FvpSeriesInput {
        val base = fourConditions()
        return series(conditions = listOf(base.first().copy(attempts = attempts)) + base.drop(1))
    }

    /** 5 condiciones con alturas DISTINTAS (misma regla que [fourConditions]). */
    private fun fiveConditions(fps: Double? = 120.0): List<FvpConditionDraft> =
        listOf(0.0, 20.0, 40.0, 60.0, 80.0).mapIndexed { index, load ->
            condition(
                conditionKey = "load-${load.toInt()}",
                addedLoadKg = load,
                attempts = listOf(attempt(heightM = HEIGHTS_FIVE[index], sourceFps = fps)),
            )
        }

    private fun twoConditions(): List<FvpConditionDraft> =
        listOf(
            condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
            condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
        )

    private fun threeConditions(): List<FvpConditionDraft> =
        listOf(
            condition("load-0", 0.0, attempts = listOf(attempt(heightM = 0.35))),
            condition("load-20", 20.0, attempts = listOf(attempt(heightM = 0.32))),
            condition("load-40", 40.0, attempts = listOf(attempt(heightM = 0.29))),
        )

    private fun duplicateLoadConditions(): List<FvpConditionDraft> =
        listOf(
            condition("load-a", 20.0),
            condition("load-b", 20.0),
            condition("load-c", 40.0),
            condition("load-d", 60.0),
        )

    private companion object {
        const val H_PO_M = 0.40
        const val BODY_MASS_KG = 80.0

        /** Altura de la PRIMERA condicion en la serie de referencia. */
        const val FIRST_HEIGHT_M = 0.35

        /**
         * Alturas LITERALES de las condiciones 2 a 4 de la serie de referencia. Son literales
         * exactos en binario a proposito: `0.35 - 0.03` no da el mismo `Double` que `0.32`.
         */
        val HEIGHTS_AFTER_FIRST = listOf(0.32, 0.29, 0.26)

        /** Alturas de las 5 condiciones de la serie extendida (todas distintas). */
        val HEIGHTS_FIVE = listOf(0.35, 0.32, 0.29, 0.26, 0.23)
    }
}
