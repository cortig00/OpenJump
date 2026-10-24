package com.openjump.app.fvp

import com.openjump.fvp.ALGORITHM_VERSION
import com.openjump.fvp.FvpAttempt
import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpInputError
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpWarning
import com.openjump.fvp.GRAVITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests JVM del adaptador app-side [FvpEngine] (JUnit4, sin Android/Room).
 *
 * Los valores esperados se toman de la referencia documentada, NO de la salida del codigo:
 * `docs/research/fvp/03-MATHEMATICAL_SPEC.md` (secciones 2.1-2.5) y
 * `docs/research/fvp/04-VALIDATION_REPORT.md` (dataset D1/D2). Todos los inputs son SINTETICOS.
 *
 * Nota de tolerancias (spec 03 seccion 2.5): los valores de velocidad publicados en la tabla
 * estan redondeados a 6 decimales (1.310024 y 1.107173), por lo que se comprueban con delta 1e-6;
 * la tolerancia estricta 1e-9 se aplica contra la forma cerrada documentada sqrt(g*h/2) (formula 2).
 */
class FvpEngineTest {

    // --- Punto (F, V, P): ejemplo numerico de control, spec 03 seccion 2.5 / 04 tabla D1 ---

    @Test
    fun `point spec 2_5 golden condition A matches documented reference`() {
        // m = 80 kg, h = 0.35 m, h_po = 0.40 m -> F = 1470.9975 N, V ~= 1.310024 m/s, P ~= 1927.0425 W.
        val a = FvpEngine.point(totalMassKg = 80.0, heightM = 0.35, pushOffM = 0.40)

        assertEquals(1470.9975, a.forceN, 0.01)
        assertEquals(1.310024, a.velocityMps, 1e-6)
        assertEquals(sqrt(GRAVITY * 0.35 / 2.0), a.velocityMps, 1e-9)
        assertEquals(1927.0425, a.powerW, 0.05)
    }

    @Test
    fun `point spec 2_5 golden condition B matches documented reference`() {
        // m = 100 kg (+20 kg), h = 0.25 m, h_po = 0.40 m -> F = 1593.5806 N, V ~= 1.107173 m/s.
        val b = FvpEngine.point(totalMassKg = 100.0, heightM = 0.25, pushOffM = 0.40)

        assertEquals(1593.5806, b.forceN, 0.01)
        assertEquals(1.107173, b.velocityMps, 1e-6)
        assertEquals(sqrt(GRAVITY * 0.25 / 2.0), b.velocityMps, 1e-9)
        assertEquals(1764.3689, b.powerW, 0.05)
    }

    // --- OLS perfecto sobre F = 3000 - 1000*V (dataset D2 de 04) ---

    /** 4 condiciones que caen EXACTAMENTE sobre F = 3000 - 1000*V (V = 1.0, 1.2, 1.5, 1.8). */
    private fun perfectLinePoints(): List<FvpConditionPoint> =
        listOf(1.0, 1.2, 1.5, 1.8).mapIndexed { index, v ->
            val heightM = 2.0 * v * v / GRAVITY
            val forceN = 3000.0 - 1000.0 * v
            val totalMassKg = forceN / (GRAVITY * (heightM / H_PO_M + 1.0))
            pointFrom(
                conditionId = "c$index",
                totalMassKg = totalMassKg,
                heightM = heightM,
            )
        }

    @Test
    fun `profile perfect line recovers documented parameters and single high extrapolation warning`() {
        val profile = FvpEngine.profile(
            points = perfectLinePoints(),
            bodyMassKg = BODY_MASS_KG,
            sourceFps = null,
        )

        assertEquals(3000.0, profile.f0N, 1e-6)
        assertEquals(1000.0, profile.sfvNsPerM, 1e-6)
        assertEquals(-1000.0, profile.slopeFv, 1e-6)
        assertEquals(3.0, profile.v0Mps, 1e-9)
        assertEquals(2250.0, profile.pmaxW, 1e-6)
        assertEquals(1.0, profile.r2, 1e-9)
        assertEquals(4, profile.n)
        assertEquals(1.0, profile.vMinMps, 1e-9)
        assertEquals(1.8, profile.vMaxMps, 1e-9)
        // m_ref = masa corporal en vacio (convencion PoC, PENDIENTE-EXCEL en spec 03 2.4).
        assertEquals(37.5, profile.f0RelNPerKg, 1e-9)
        assertEquals(28.125, profile.pmaxRelWPerKg, 1e-9)

        // Recta perfecta pero V0/maxV = 3.0/1.8 = 1.667 > 1.5: HIGH_EXTRAPOLATION es el UNICO
        // warning esperado (hallazgo registrado en spec 03 2.5 y 04 tabla D2).
        assertEquals(listOf(FvpWarning.HIGH_EXTRAPOLATION), profile.warnings)
    }

    @Test
    fun `profile propagates source Fps into fps blocked warning`() {
        val profile = FvpEngine.profile(
            points = perfectLinePoints(),
            bodyMassKg = BODY_MASS_KG,
            sourceFps = 30.0,
        )

        assertTrue(profile.warnings.contains(FvpWarning.FPS_30_BLOCKED))
    }

    // --- Camino no fisico: pendiente positiva -> V0/Pmax NaN + NON_PHYSICAL ---

    @Test
    fun `profile positive slope returns NaN intercepts and non physical warning`() {
        // A igualdad de carga, F y V crecen juntos con h: pendiente POSITIVA (Sfv < 0). Este es el
        // caso NON_PHYSICAL_SLOPE documentado en spec 03 2.5 (dos alturas a igual masa NO son dos
        // condiciones F-V de una serie real, pero el motor debe degradar sin excepcion).
        val profile = FvpEngine.profile(
            points = listOf(
                pointFrom(conditionId = "low", totalMassKg = 80.0, heightM = 0.25),
                pointFrom(conditionId = "high", totalMassKg = 80.0, heightM = 0.35),
            ),
            bodyMassKg = BODY_MASS_KG,
            sourceFps = null,
        )

        assertTrue("V0 must be NaN on a non-physical profile", profile.v0Mps.isNaN())
        assertTrue("Pmax must be NaN on a non-physical profile", profile.pmaxW.isNaN())
        assertTrue(profile.sfvNsPerM < 0.0)
        assertTrue(profile.warnings.contains(FvpWarning.NON_PHYSICAL))
        // Acceso a las formas relativas sin excepcion; NaN permitido en la relativa de Pmax.
        assertTrue(profile.pmaxRelWPerKg.isNaN())
        assertTrue(profile.f0RelNPerKg.isFinite())
    }

    @Test
    fun `profile positive slope keeps v0 and relative pmax NaN instead of zero`() {
        // Serie sintetica de 3 condiciones a masa Y altura crecientes: F y V crecen juntos, luego la
        // pendiente es POSITIVA y el perfil es NON_PHYSICAL (spec 03 2.3). Se comprueba con isNaN
        // (`== 0.0` daria un falso positivo si el motor devolviera 0.0 por error).
        val profile = FvpEngine.profile(
            points = listOf(
                pointFrom(conditionId = "m80", totalMassKg = 80.0, heightM = 0.25),
                pointFrom(conditionId = "m90", totalMassKg = 90.0, heightM = 0.30),
                pointFrom(conditionId = "m100", totalMassKg = 100.0, heightM = 0.35),
            ),
            bodyMassKg = BODY_MASS_KG,
            sourceFps = null,
        )

        assertTrue("pendiente positiva => Sfv < 0", profile.sfvNsPerM < 0.0)
        assertTrue("V0 debe ser NaN, no 0.0", profile.v0Mps.isNaN())
        assertTrue("Pmax relativa debe ser NaN, no 0.0", profile.pmaxRelWPerKg.isNaN())
        assertTrue(profile.f0N.isFinite())
        assertTrue(profile.warnings.contains(FvpWarning.NON_PHYSICAL))
    }

    @Test
    fun `profile with constant force returns NaN r2 and non physical warning without throwing`() {
        // Tres condiciones con LA MISMA fuerza (2000 N exactos) y velocidades distintas (1.0, 1.2,
        // 1.5 m/s): caso degenerado de spec 03 2.3, varianza de F == 0 => R^2 = NaN (no 1.0
        // espurio) y pendiente nula => NON_PHYSICAL. Las fuerzas se fijan exactamente a 2000 N
        // porque el motor no recalcula F a partir de (m, h, h_po), recibe los puntos ya calculados;
        // asi la varianza es exactamente cero y el test no depende del redondeo de la masa.
        val points = listOf(1.0, 1.2, 1.5).map { velocityMps ->
            val heightM = 2.0 * velocityMps * velocityMps / GRAVITY
            FvpConditionPoint(
                conditionId = "v$velocityMps",
                totalMassKg = FORCE_N / (GRAVITY * (heightM / H_PO_M + 1.0)),
                heightM = heightM,
                pushOffM = H_PO_M,
                forceN = FORCE_N,
                velocityMps = velocityMps,
                powerW = FORCE_N * velocityMps,
            )
        }

        val profile = FvpEngine.profile(points = points, bodyMassKg = BODY_MASS_KG, sourceFps = null)

        assertTrue("varianza de F == 0 => R^2 NaN", profile.r2.isNaN())
        assertTrue("sin pendiente fisica V0 es NaN", profile.v0Mps.isNaN())
        assertTrue(profile.warnings.contains(FvpWarning.NON_PHYSICAL))
    }

    // --- Errores bloqueantes ---

    @Test
    fun `profile identical velocities throws input error`() {
        val error = assertThrows(FvpInputError::class.java) {
            FvpEngine.profile(
                points = listOf(
                    pointFrom(conditionId = "m80", totalMassKg = 80.0, heightM = 0.30),
                    pointFrom(conditionId = "m100", totalMassKg = 100.0, heightM = 0.30),
                ),
                bodyMassKg = BODY_MASS_KG,
                sourceFps = null,
            )
        }
        // Pendiente indefinida: cargas indistinguibles es un error de entrada, no un perfil degradado.
        assertTrue(error.message.orEmpty().contains("identical"))
    }

    // --- Seleccion de intentos ---

    @Test
    fun `bestPerCondition picks highest jump and carries winner total mass sorted by condition id`() {
        val attempts = listOf(
            attempt(conditionId = "cond-b", attemptId = "b1", addedLoadKg = 0.0, jumpHeightM = 0.32),
            attempt(conditionId = "cond-a", attemptId = "a1", addedLoadKg = 0.0, jumpHeightM = 0.30),
            attempt(conditionId = "cond-a", attemptId = "a2", addedLoadKg = 20.0, jumpHeightM = 0.35),
        )

        val selected = FvpEngine.bestPerCondition(attempts)

        // Ordenado por conditionId; masa total = bodyMass + carga del intento GANADOR (0.35 m).
        assertEquals(listOf("cond-a", "cond-b"), selected.map { it.conditionId })
        assertEquals(100.0, selected[0].totalMassKg, 1e-9)
        assertEquals(0.35, selected[0].heightM, 1e-9)
        assertEquals(0.40, selected[0].pushOffM, 1e-9)
        assertEquals(80.0, selected[1].totalMassKg, 1e-9)
        assertEquals(0.32, selected[1].heightM, 1e-9)
    }

    @Test
    fun `bestPerCondition empty input returns empty output`() {
        assertTrue(FvpEngine.bestPerCondition(emptyList()).isEmpty())
    }

    // --- Determinismo ---

    @Test
    fun `profile is deterministic for identical input`() {
        val points = perfectLinePoints()

        val first = FvpEngine.profile(points = points, bodyMassKg = BODY_MASS_KG, sourceFps = 60.0)
        val second = FvpEngine.profile(points = points, bodyMassKg = BODY_MASS_KG, sourceFps = 60.0)

        assertEquals(first, second)
        assertEquals(first.warnings, second.warnings)
    }

    // --- Evaluacion completa: trazabilidad + delegacion ---

    @Test
    fun `evaluation delegates and carries traceability for the documented two point example`() {
        // Spec 03 2.5: recta por 2 puntos -> Sfv = 604.30 N*s/m, F0 = 2262.64 N,
        // V0 = 3.7442 m/s, Pmax = 2117.97 W; n = 2 => FEW_POINTS + HIGH_EXTRAPOLATION.
        val conditions = listOf(
            pointFrom(conditionId = "A", totalMassKg = 80.0, heightM = 0.35),
            pointFrom(conditionId = "B", totalMassKg = 100.0, heightM = 0.25),
        )

        val result = FvpEngine.evaluation(
            id = "eval-1",
            protocol = FvpProtocol.SJ,
            bodyMassKg = BODY_MASS_KG,
            conditions = conditions,
            provenance = "test-fixture",
            sourceFps = null,
        )

        assertEquals("eval-1", result.evaluationId)
        assertEquals(FvpProtocol.SJ, result.protocol)
        assertEquals(ALGORITHM_VERSION, result.algorithmVersion)
        assertEquals(conditions, result.points)
        assertEquals(2262.6442, result.profile.f0N, 0.01)
        assertEquals(604.2992, result.profile.sfvNsPerM, 0.01)
        assertEquals(3.7442, result.profile.v0Mps, 1e-3)
        assertEquals(2117.9734, result.profile.pmaxW, 0.01)
        assertEquals(
            listOf(FvpWarning.FEW_POINTS, FvpWarning.HIGH_EXTRAPOLATION),
            result.profile.warnings,
        )
    }

    // --- Claves de almacenamiento ---

    @Test
    fun `parseProtocol maps known keys and rejects unknown without defaulting`() {
        assertEquals(FvpProtocol.SJ, FvpEngine.parseProtocol("SJ"))
        assertEquals(FvpProtocol.CMJ, FvpEngine.parseProtocol("CMJ"))

        assertEquals(null, FvpEngine.parseProtocol(null))
        assertEquals(null, FvpEngine.parseProtocol(""))
        assertEquals(null, FvpEngine.parseProtocol("sj"))
        assertEquals(null, FvpEngine.parseProtocol("SJ "))
        assertEquals(null, FvpEngine.parseProtocol("CMJX"))
    }

    @Test
    fun `parseMethod maps known keys and rejects unknown without defaulting`() {
        assertEquals(FvpMeasureMethod.FLIGHT_TIME_VIDEO, FvpEngine.parseMethod("FLIGHT_TIME_VIDEO"))
        assertEquals(FvpMeasureMethod.MANUAL, FvpEngine.parseMethod("MANUAL"))
        assertEquals(FvpMeasureMethod.IMPORTED, FvpEngine.parseMethod("IMPORTED"))

        assertEquals(null, FvpEngine.parseMethod(null))
        assertEquals(null, FvpEngine.parseMethod(""))
        assertEquals(null, FvpEngine.parseMethod("manual"))
        assertEquals(null, FvpEngine.parseMethod("FLIGHT_TIME_VIDEO "))
    }

    // --- Helpers ---

    /** Construye un punto de condicion pasando por el adaptador (nunca por `com.openjump.fvp`). */
    private fun pointFrom(
        conditionId: String,
        totalMassKg: Double,
        heightM: Double,
    ): FvpConditionPoint {
        val point = FvpEngine.point(totalMassKg = totalMassKg, heightM = heightM, pushOffM = H_PO_M)
        return FvpConditionPoint(
            conditionId = conditionId,
            totalMassKg = totalMassKg,
            heightM = heightM,
            pushOffM = H_PO_M,
            forceN = point.forceN,
            velocityMps = point.velocityMps,
            powerW = point.powerW,
        )
    }

    private fun attempt(
        conditionId: String,
        attemptId: String,
        addedLoadKg: Double,
        jumpHeightM: Double,
    ): FvpAttempt = FvpAttempt(
        conditionId = conditionId,
        attemptId = attemptId,
        bodyMassKg = BODY_MASS_KG,
        addedLoadKg = addedLoadKg,
        jumpHeightM = jumpHeightM,
        pushOffM = H_PO_M,
    )

    private companion object {
        const val H_PO_M = 0.40
        const val BODY_MASS_KG = 80.0
        const val FORCE_N = 2000.0
    }
}
