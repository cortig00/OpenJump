package com.openjump.app.fvp

import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpWarning
import com.openjump.fvp.RECOMMENDED_MIN_CONDITIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM (JUnit4, sin Android/Room) de la politica de calidad F-V-P: la tabla EXACTA de
 * [fvpQualityState] con entradas literales, sus contratos (PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE no
 * descalifica; PROFILE es subconjunto de `profileEligible`) y el adaptador desde la elegibilidad de
 * dominio ([FvpEligibility.toQualityInput]).
 *
 * No se duplican umbrales: la frontera de condiciones se expresa con [RECOMMENDED_MIN_CONDITIONS] y
 * los avisos con los nombres de [FvpWarning], de modo que un cambio en `:fvp` mueve tambien estos
 * tests en vez de dejarlos pasando con valores obsoletos.
 */
class FvpQualityPolicyTest {

    /** Q2: el UNICO aviso del motor que deja la serie en CONDITIONAL (el resto falla en cerrado). */
    private val conditionalWarnings = listOf(FvpWarning.HIGH_EXTRAPOLATION)

    /** Q2: avisos del motor que degradan a DIAGNOSTIC (no hay perfil revisable que comparar). */
    private val diagnosticWarnings = listOf(
        FvpWarning.LOW_R2,
        FvpWarning.NARROW_VELOCITY_RANGE,
        FvpWarning.FPS_30_BLOCKED,
    )

    // --- Tabla, regla a regla -------------------------------------------------------------------

    @Test
    fun `1 not computable is incomplete no matter what else holds`() {
        listOf(
            input(canCompute = false),
            input(canCompute = false, conditionCount = 2),
            input(
                canCompute = false,
                profileEligible = false,
                timingAllows = false,
                r2Finite = false,
                mixedSelectedMethods = true,
                warningNames = setOf(FvpWarning.NON_PHYSICAL.name, FvpWarning.LOW_R2.name),
            ),
        ).forEach { candidate ->
            assertEquals("INCOMPLETE gana a todo: $candidate", FvpQualityState.INCOMPLETE, fvpQualityState(candidate))
        }
    }

    @Test
    fun `2 two and three conditions are diagnostic even with a clean fit`() {
        listOf(2, 3).forEach { count ->
            assertEquals(
                "con $count condiciones no hay perfil normal",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(input(conditionCount = count, profileEligible = false)),
            )
        }
    }

    @Test
    fun `3 non physical fit is diagnostic`() {
        val state = fvpQualityState(
            input(warningNames = setOf(FvpWarning.NON_PHYSICAL.name)),
        )

        assertEquals(FvpQualityState.DIAGNOSTIC, state)
    }

    @Test
    fun `3 non finite r2 is diagnostic`() {
        assertEquals(FvpQualityState.DIAGNOSTIC, fvpQualityState(input(r2Finite = false)))
    }

    @Test
    fun `3 no usable fit wins over the conditional warnings`() {
        val state = fvpQualityState(
            input(warningNames = setOf(FvpWarning.LOW_R2.name, FvpWarning.NON_PHYSICAL.name)),
        )

        assertEquals("no hay ajuste utilizable: DIAGNOSTIC, no CONDITIONAL", FvpQualityState.DIAGNOSTIC, state)
    }

    @Test
    fun `4 every diagnostic engine warning downgrades the clean profile to diagnostic`() {
        diagnosticWarnings.forEach { warning ->
            assertEquals(
                "${warning.name} no deja un perfil revisable",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(input(warningNames = setOf(warning.name))),
            )
        }
    }

    @Test
    fun `4 high extrapolation is the only engine warning that stays conditional`() {
        conditionalWarnings.forEach { warning ->
            assertEquals(
                "${warning.name} condiciona el perfil",
                FvpQualityState.CONDITIONAL,
                fvpQualityState(input(warningNames = setOf(warning.name))),
            )
        }
    }

    /**
     * Q2: UNKNOWN, BLOCKED y 60-<120 fps sin sensibilidad revisada NO tienen ruta condicional
     * habilitada (D4): un timing no `ALLOW` deja la serie en DIAGNOSTIC, tambien si todo lo demas
     * esta limpio y la elegibilidad de dominio la declaraba perfil.
     */
    @Test
    fun `4 timing not allow is diagnostic`() {
        listOf(true, false).forEach { profileEligible ->
            assertEquals(
                "timing no ALLOW con profileEligible=$profileEligible",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(input(timingAllows = false, profileEligible = profileEligible)),
            )
        }
    }

    /** Q2: mezclar metodos de medida entre intentos SELECCIONADOS no tiene compatibilidad revisada. */
    @Test
    fun `4 mixed selected methods is diagnostic because no reviewed compatibility exists`() {
        assertEquals(FvpQualityState.DIAGNOSTIC, fvpQualityState(input(mixedSelectedMethods = true)))
    }

    @Test
    fun `4 degraded states beat profile eligible`() {
        // Un perfil ELEGIBLE con R^2 bajo, rango estrecho o fps bloqueados no es PROFILE: Q2 lo
        // declara DIAGNOSTIC; HIGH_EXTRAPOLATION lo deja CONDITIONAL; nada lo promociona.
        diagnosticWarnings.forEach { warning ->
            assertEquals(
                "DIAGNOSTIC gana a PROFILE: ${warning.name}",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(input(warningNames = setOf(warning.name))),
            )
        }
        listOf(
            input(timingAllows = false),
            input(mixedSelectedMethods = true),
        ).forEach { candidate ->
            assertEquals(
                "DIAGNOSTIC gana a PROFILE: $candidate",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(candidate),
            )
        }
        assertEquals(
            "CONDITIONAL gana a PROFILE: ${FvpWarning.HIGH_EXTRAPOLATION.name}",
            FvpQualityState.CONDITIONAL,
            fvpQualityState(input(warningNames = setOf(FvpWarning.HIGH_EXTRAPOLATION.name))),
        )
    }

    @Test
    fun `5 clean profile eligible input is profile`() {
        val candidate = input()

        assertTrue(candidate.profileEligible)
        assertEquals(FvpQualityState.PROFILE, fvpQualityState(candidate))
    }

    @Test
    fun `6 computable but not profile eligible is conditional`() {
        val state = fvpQualityState(input(profileEligible = false))

        assertEquals(FvpQualityState.CONDITIONAL, state)
    }

    @Test
    fun `push off outside recommended range alone keeps profile without changing the state table`() {
        val candidate = input(
            warningNames = setOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name),
        )

        assertEquals(
            "el aviso de ventana recomendada es advisory: no descalifica",
            FvpQualityState.PROFILE,
            fvpQualityState(candidate),
        )
    }

    // --- Q2 (fix de seguridad): nada sin revisar establece comparabilidad -----------------------

    /**
     * Un nombre persistido que esta version NO clasifica no se interpreta como "sin problemas":
     * falla en cerrado a DIAGNOSTIC (y la UI lo declara como limitacion generica).
     */
    @Test
    fun `an unclassified persisted warning name is diagnostic`() {
        assertEquals(
            FvpQualityState.DIAGNOSTIC,
            fvpQualityState(input(warningNames = setOf("FUTURE_ENGINE_WARNING"))),
        )
    }

    /**
     * Clasificacion EXACTA y fail-closed de TODO nombre persistible (avisos del MOTOR + flags de
     * elegibilidad ACTUALES: las dos familias que escribe `warningsCsv`). El mapa es EXPLICITO
     * (nombre a nombre) y se compara por IGUALDAD DE CLAVES con las dos familias de enums: anadir
     * un valor nuevo a cualquiera de los dos enums rompe este test hasta que se clasifique aqui a
     * mano, en vez de heredar el `else -> DIAGNOSTIC` sin decision explicita. Solo
     * [FvpWarning.HIGH_EXTRAPOLATION] deja la serie en CONDITIONAL y solo el aviso ADVISORY de la
     * ventana recomendada de h_po conserva el PROFILE; cualquier otro nombre degrada a DIAGNOSTIC.
     */
    @Test
    fun `every persisted warning name is classified fail closed`() {
        val engineNames = FvpWarning.entries.map { it.name }
        val flagNames = FvpEligibilityFlag.entries.map { it.name }
        assertTrue("la lista de avisos del motor no puede estar vacia", engineNames.isNotEmpty())
        assertTrue("la lista de flags de elegibilidad no puede estar vacia", flagNames.isNotEmpty())

        val expectedByEngineName = mapOf(
            FvpWarning.FEW_POINTS.name to FvpQualityState.DIAGNOSTIC,
            FvpWarning.NARROW_VELOCITY_RANGE.name to FvpQualityState.DIAGNOSTIC,
            FvpWarning.LOW_R2.name to FvpQualityState.DIAGNOSTIC,
            FvpWarning.NON_PHYSICAL.name to FvpQualityState.DIAGNOSTIC,
            FvpWarning.HIGH_EXTRAPOLATION.name to FvpQualityState.CONDITIONAL,
            FvpWarning.FPS_30_BLOCKED.name to FvpQualityState.DIAGNOSTIC,
        )
        val expectedByFlagName = mapOf(
            FvpEligibilityFlag.DIAGNOSTIC_ONLY_TWO_CONDITIONS.name to FvpQualityState.DIAGNOSTIC,
            FvpEligibilityFlag.FEW_CONDITIONS.name to FvpQualityState.DIAGNOSTIC,
            FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name to FvpQualityState.PROFILE,
            FvpEligibilityFlag.TIMING_CONDITIONAL.name to FvpQualityState.DIAGNOSTIC,
            FvpEligibilityFlag.TIMING_BLOCKED.name to FvpQualityState.DIAGNOSTIC,
            FvpEligibilityFlag.TIMING_UNKNOWN_NOT_ELIGIBLE.name to FvpQualityState.DIAGNOSTIC,
            FvpEligibilityFlag.MIXED_SELECTED_METHODS.name to FvpQualityState.DIAGNOSTIC,
        )

        assertEquals(
            "faltan o sobran avisos del motor sin clasificar en FvpQualityPolicy.kt",
            engineNames.toSet(),
            expectedByEngineName.keys,
        )
        assertEquals(
            "faltan o sobran flags de elegibilidad sin clasificar en FvpQualityPolicy.kt",
            flagNames.toSet(),
            expectedByFlagName.keys,
        )

        val expectations = expectedByEngineName + expectedByFlagName
        assertEquals(
            "las dos familias de enums no pueden compartir un nombre persistido",
            engineNames.size + flagNames.size,
            expectations.size,
        )

        expectations.forEach { (name, expected) ->
            assertEquals(
                "clasificacion fail-closed de $name",
                expected,
                fvpQualityState(input(warningNames = setOf(name))),
            )
        }
    }

    @Test
    fun `the condition count threshold is the engine recommended minimum`() {
        assertEquals(4, RECOMMENDED_MIN_CONDITIONS)
        assertEquals(
            FvpQualityState.PROFILE,
            fvpQualityState(input(conditionCount = RECOMMENDED_MIN_CONDITIONS)),
        )
        assertEquals(
            FvpQualityState.DIAGNOSTIC,
            fvpQualityState(input(conditionCount = RECOMMENDED_MIN_CONDITIONS - 1, profileEligible = false)),
        )
    }

    @Test
    fun `profile is always a subset of profile eligible`() {
        val warningSets = listOf(
            emptySet(),
            setOf(FvpWarning.NON_PHYSICAL.name),
            setOf(FvpWarning.LOW_R2.name),
            setOf(FvpWarning.NON_PHYSICAL.name, FvpWarning.LOW_R2.name),
            setOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name),
        )
        var profiles = 0
        listOf(2, 3, RECOMMENDED_MIN_CONDITIONS, 5).forEach { count ->
            listOf(true, false).forEach { canCompute ->
                listOf(true, false).forEach { profileEligible ->
                    listOf(true, false).forEach { timingAllows ->
                        listOf(true, false).forEach { r2Finite ->
                            listOf(true, false).forEach { mixed ->
                                warningSets.forEach { names ->
                                    val candidate = input(
                                        canCompute = canCompute,
                                        conditionCount = count,
                                        profileEligible = profileEligible,
                                        timingAllows = timingAllows,
                                        r2Finite = r2Finite,
                                        mixedSelectedMethods = mixed,
                                        warningNames = names,
                                    )
                                    if (fvpQualityState(candidate) == FvpQualityState.PROFILE) {
                                        profiles++
                                        assertTrue(
                                            "PROFILE exige profileEligible en $candidate",
                                            candidate.profileEligible,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("el caso limpio debe producir al menos un PROFILE", profiles > 0)
    }

    @Test
    fun `the policy is deterministic for the same input`() {
        val candidate = input(warningNames = setOf(FvpWarning.LOW_R2.name))

        assertEquals(fvpQualityState(candidate), fvpQualityState(candidate.copy()))
    }

    // --- Adaptador desde la elegibilidad de dominio ---------------------------------------------

    @Test
    fun `adapter copies the series fields and reads timing from the aggregated quality`() {
        val eligibility = FvpEligibility(
            conditionCount = 4,
            blocking = emptyList(),
            flags = listOf(
                FvpEligibilityFlag.MIXED_SELECTED_METHODS,
                FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE,
            ),
            timingQuality = FvpTimingQuality.ALLOW,
        )

        val adapter = eligibility.toQualityInput(
            r2Finite = true,
            warningNames = setOf(FvpWarning.LOW_R2.name),
        )

        assertTrue(adapter.canCompute)
        assertEquals(4, adapter.conditionCount)
        assertTrue(adapter.profileEligible)
        assertTrue("timing ALLOW de la elegibilidad", adapter.timingAllows)
        assertTrue(adapter.r2Finite)
        assertTrue("el flag de mezcla llega al estado", adapter.mixedSelectedMethods)
        assertEquals(setOf(FvpWarning.LOW_R2.name), adapter.warningNames)
        assertEquals("Q2: LOW_R2 no deja perfil revisable", FvpQualityState.DIAGNOSTIC, fvpQualityState(adapter))
    }

    @Test
    fun `adapter turns a non allow timing quality into a diagnostic state`() {
        val eligibility = FvpEligibility(
            conditionCount = 4,
            blocking = emptyList(),
            flags = listOf(FvpEligibilityFlag.TIMING_CONDITIONAL),
            timingQuality = FvpTimingQuality.CONDITIONAL,
        )

        val adapter = eligibility.toQualityInput(r2Finite = true, warningNames = emptySet())

        assertFalse(adapter.timingAllows)
        assertFalse("un timing condicional no es perfil normal", adapter.profileEligible)
        assertEquals(
            "Q2: 60-<120 fps sin sensibilidad revisada no tiene ruta condicional",
            FvpQualityState.DIAGNOSTIC,
            fvpQualityState(adapter),
        )
    }

    @Test
    fun `adapter reports incomplete when the series has a blocking problem`() {
        val eligibility = FvpEligibility(
            conditionCount = 4,
            blocking = listOf(FvpEligibilityProblem.EXCLUSION_REASON_MISSING),
            flags = emptyList(),
            timingQuality = FvpTimingQuality.ALLOW,
        )

        val adapter = eligibility.toQualityInput(r2Finite = true, warningNames = emptySet())

        assertFalse(adapter.canCompute)
        assertEquals(FvpQualityState.INCOMPLETE, fvpQualityState(adapter))
    }

    /**
     * Integracion con el DOMINIO (sin duplicar la regla): una serie real con un intento seleccionado
     * `MANUAL` y el resto video, a 90 fps, llega al adaptador con mezcla de metodos y timing no
     * `ALLOW`, y el estado resultante es DIAGNOSTIC (Q2: no PROFILE ni CONDITIONAL comparable).
     */
    @Test
    fun `adapter on a real mixed series carries mix and timing into a diagnostic state`() {
        val series = FvpSeriesInput(
            protocol = FvpProtocol.SJ,
            bodyMassKg = 80.0,
            pushOffMode = FvpPushOffMode.PER_CONDITION,
            conditions = listOf(0.0, 20.0, 40.0, 60.0).mapIndexed { index, load ->
                FvpConditionDraft(
                    conditionKey = "load-${load.toInt()}",
                    addedLoadKg = load,
                    pushOffM = 0.40,
                    attempts = listOf(
                        FvpAttemptDraft(
                            heightM = listOf(0.35, 0.32, 0.29, 0.26)[index],
                            method = if (index == 0) FvpMeasureMethod.MANUAL else FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                            sourceFps = 90.0,
                        ),
                    ),
                )
            },
        )

        val eligibility = checkSeries(series)
        val adapter = eligibility.toQualityInput(
            r2Finite = true,
            warningNames = setOf(FvpWarning.NARROW_VELOCITY_RANGE.name),
        )

        assertTrue(eligibility.canCompute)
        assertFalse(eligibility.profileEligible)
        assertTrue(adapter.mixedSelectedMethods)
        assertFalse(adapter.timingAllows)
        assertEquals(FvpQualityState.DIAGNOSTIC, fvpQualityState(adapter))
    }

    /**
     * Un nombre DESCONOCIDO nunca se ignora (Q2): la serie cae en DIAGNOSTIC aunque el resto este
     * limpio y aunque acompane a un nombre reconocido que por si solo solo condicionaria.
     */
    @Test
    fun `unknown warning names fail closed to diagnostic even next to known ones`() {
        val base = input(warningNames = setOf(FvpWarning.HIGH_EXTRAPOLATION.name))
        assertEquals(FvpQualityState.CONDITIONAL, fvpQualityState(base))

        listOf(
            setOf("UNKNOWN_WARNING"),
            setOf("UNKNOWN_WARNING", "TIMING_CONDITIONAL"),
            setOf("UNKNOWN_WARNING", FvpWarning.HIGH_EXTRAPOLATION.name),
        ).forEach { names ->
            assertEquals(
                "un nombre sin clasificar no se ignora: $names",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(base.copy(warningNames = names)),
            )
        }
    }

    // --- Completitud: ningun aviso del motor se queda sin clasificar ------------------------------

    /**
     * INVARIANTE DE COMPLETITUD (Q2): la politica es CERRADA por defecto. Un aviso NUEVO del motor
     * (o un nombre persistido por una version futura) no puede dejar la serie en PROFILE en
     * silencio: cualquier nombre que la politica no reconozca degrada a DIAGNOSTIC. El control
     * negativo fija que el unico nombre ADVISORY reconocido si conserva el PROFILE (no se
     * sobre-bloquea) y que los nombres ya clasificados siguen degradando por su regla propia.
     */
    @Test
    fun `a warning name added later cannot stay profile in silence`() {
        assertEquals(
            "un nombre desconocido degrada en cerrado, este o no en el enum actual",
            FvpQualityState.DIAGNOSTIC,
            fvpQualityState(input(warningNames = setOf("BRAND_NEW_ENGINE_WARNING"))),
        )
        assertEquals(
            "advisory reconocido: conserva el PROFILE (no se sobre-bloquea)",
            FvpQualityState.PROFILE,
            fvpQualityState(
                input(warningNames = setOf(FvpEligibilityFlag.PUSH_OFF_OUTSIDE_RECOMMENDED_RANGE.name)),
            ),
        )
        assertEquals(
            "aviso del motor ya clasificado: degrada por su regla",
            FvpQualityState.DIAGNOSTIC,
            fvpQualityState(input(warningNames = setOf(FvpWarning.NON_PHYSICAL.name, FvpWarning.LOW_R2.name))),
        )
    }

    /**
     * [FvpWarning.FEW_POINTS] esta ahora CLASIFICADO (degradaria por su nombre), y ademas sigue
     * cubierto por el recuento: en TODO recuento donde el motor puede emitirlo
     * (`n < RECOMMENDED_MIN_CONDITIONS`) el paso 2 ya da DIAGNOSTIC, con el aviso o sin el.
     */
    @Test
    fun `few points is already diagnostic by the condition count rule`() {
        (1 until RECOMMENDED_MIN_CONDITIONS).forEach { count ->
            assertEquals(
                "con $count condiciones el estado ya es DIAGNOSTIC (y una serie asi no es profileEligible)",
                FvpQualityState.DIAGNOSTIC,
                fvpQualityState(
                    input(
                        conditionCount = count,
                        profileEligible = false,
                        warningNames = setOf(FvpWarning.FEW_POINTS.name),
                    ),
                ),
            )
        }
    }

    private fun input(
        canCompute: Boolean = true,
        conditionCount: Int = RECOMMENDED_MIN_CONDITIONS,
        profileEligible: Boolean = true,
        timingAllows: Boolean = true,
        r2Finite: Boolean = true,
        mixedSelectedMethods: Boolean = false,
        warningNames: Set<String> = emptySet(),
    ): FvpQualityInput = FvpQualityInput(
        canCompute = canCompute,
        conditionCount = conditionCount,
        profileEligible = profileEligible,
        timingAllows = timingAllows,
        r2Finite = r2Finite,
        mixedSelectedMethods = mixedSelectedMethods,
        warningNames = warningNames,
    )
}
