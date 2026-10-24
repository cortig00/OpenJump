package com.openjump.fvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests JVM del motor F-V-P. Todos los inputs son SINTETICOS (valores a mano, no experimentales).
 * G = 9.80665 fijada (coherente con JumpMath.GRAVITY de app).
 */
class FvpCalculatorTest {

    // --- Conversion punto (F, V, P): ejemplo sintetico a mano, spec 03 seccion 2.1 ---

    @Test
    fun jumpPoint_syntheticExample_matchesHandComputation() {
        // m=80 kg, h=0.35 m, h_po=0.40 m.
        val r = jumpPoint(massKg = 80.0, heightM = 0.35, pushOffM = 0.40)

        // Valores exactos a mano: F = 80*9.80665*1.875 = 1470.9975 N;
        // V = sqrt(9.80665*0.35/2) ~= 1.31002 m/s; P = F*V ~= 1927.0 W.
        assertEquals(1470.9975, r.forceN, 0.01)
        assertEquals(sqrt(9.80665 * 0.35 / 2.0), r.velocityMps, 1e-9)
        assertEquals(1470.9975 * r.velocityMps, r.powerW, 0.05)

        // Tolerancia 1 % frente a los valores de referencia del contrato de tarea.
        assertEquals(1475.3, r.forceN, 1475.3 * 0.01)
        assertEquals(1.3106, r.velocityMps, 1.3106 * 0.01)
        assertEquals(1933.5, r.powerW, 1933.5 * 0.01)
    }

    @Test
    fun jumpPoint_zeroHeight_returnsBodyWeight() {
        // Control fisico: h -> 0 implica F -> m*g. (h diminuta pero valida.)
        val r = jumpPoint(massKg = 80.0, heightM = 0.001, pushOffM = 0.40)
        assertEquals(80.0 * GRAVITY, r.forceN, 80.0 * GRAVITY * 0.005)
    }

    // --- OLS sobre dataset sintetico perfecto F = 3000 - 1000*V (4 puntos) ---

    /** Construye 4 condiciones que caen EXACTAMENTE sobre F=3000-1000*V (salvo redondeo fp). */
    private fun perfectLineConditions(): List<FvpConditionInput> {
        val hpo = 0.40
        return listOf(1.0, 1.2, 1.5, 1.8).mapIndexed { i, v ->
            val h = 2.0 * v * v / GRAVITY
            val f = 3000.0 - 1000.0 * v
            val m = f / (GRAVITY * (h / hpo + 1.0))
            FvpConditionInput(conditionId = "c$i", totalMassKg = m, heightM = h, pushOffM = hpo)
        }
    }

    @Test
    fun fit_perfectLine_recoversParameters() {
        val points = perfectLineConditions().map { it.toPoint() }
        val p = fit(points, bodyMassKg = 80.0)

        assertEquals(3000.0, p.f0N, 1e-6)
        assertEquals(1000.0, p.sfvNsPerM, 1e-6)
        assertEquals(-1000.0, p.slopeFv, 1e-6)
        assertEquals(3.0, p.v0Mps, 1e-9)
        assertEquals(2250.0, p.pmaxW, 1e-6)
        assertEquals(1.0, p.r2, 1e-9)
        assertEquals(4, p.n)
        // La recta es perfecta pero V0=3.0 frente a Vmax=1.8 (factor 1.67 > 1.5):
        // HIGH_EXTRAPOLATION es el unico warning y es CORRECTO (los interceptos viven
        // fuera del rango medido aunque R^2 = 1; ver spec 03 seccion 3.5).
        assertEquals(listOf(FvpWarning.HIGH_EXTRAPOLATION), p.warnings)
    }

    @Test
    fun fit_relativeForms_normalizeByBodyMass() {
        val points = perfectLineConditions().map { it.toPoint() }
        val p = fit(points, bodyMassKg = 80.0)
        // Convencion PoC: m_ref = masa corporal en vacio (PENDIENTE-EXCEL, ver spec 03 2.4).
        assertEquals(3000.0 / 80.0, p.f0RelNPerKg, 1e-9)
        assertEquals(2250.0 / 80.0, p.pmaxRelWPerKg, 1e-9)
    }

    // --- Errores bloqueantes ---

    @Test(expected = FvpInputError::class)
    fun jumpPoint_zeroMass_throws() {
        jumpPoint(massKg = 0.0, heightM = 0.30, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_negativeMass_throws() {
        jumpPoint(massKg = -80.0, heightM = 0.30, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_zeroHeight_throws() {
        jumpPoint(massKg = 80.0, heightM = 0.0, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_heightAboveLimit_throws() {
        jumpPoint(massKg = 80.0, heightM = 1.21, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_zeroPushOff_throws() {
        jumpPoint(massKg = 80.0, heightM = 0.30, pushOffM = 0.0)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_pushOffAtLowerBound_throws() {
        // Rango valido (0.05, 0.8]: el borde inferior 0.05 exacto se rechaza.
        jumpPoint(massKg = 80.0, heightM = 0.30, pushOffM = 0.05)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_pushOffAboveLimit_throws() {
        jumpPoint(massKg = 80.0, heightM = 0.30, pushOffM = 0.81)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_nanMass_throws() {
        jumpPoint(massKg = Double.NaN, heightM = 0.30, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun jumpPoint_infiniteHeight_throws() {
        jumpPoint(massKg = 80.0, heightM = Double.POSITIVE_INFINITY, pushOffM = 0.40)
    }

    @Test(expected = FvpInputError::class)
    fun fit_singlePoint_throws() {
        val pts = listOf(FvpConditionInput("c0", 80.0, 0.30, 0.40).toPoint())
        fit(pts, bodyMassKg = 80.0)
    }

    @Test(expected = FvpInputError::class)
    fun fit_duplicateConditionIds_throws() {
        val pts = listOf(
            FvpConditionInput("c0", 80.0, 0.30, 0.40).toPoint(),
            FvpConditionInput("c0", 100.0, 0.25, 0.40).toPoint(),
        )
        fit(pts, bodyMassKg = 80.0)
    }

    @Test(expected = FvpInputError::class)
    fun fit_nanForce_throws() {
        val pts = listOf(
            FvpConditionInput("c0", 80.0, 0.30, 0.40).toPoint(),
            FvpConditionInput("c1", 100.0, 0.25, 0.40).toPoint().copy(forceN = Double.NaN),
        )
        fit(pts, bodyMassKg = 80.0)
    }

    @Test(expected = FvpInputError::class)
    fun fit_zeroBodyMass_throws() {
        fit(perfectLineConditions().map { it.toPoint() }, bodyMassKg = 0.0)
    }

    @Test(expected = FvpInputError::class)
    fun fit_identicalVelocities_throws() {
        // Cargas indistinguibles en V: pendiente indefinida (no recta vertical espuria).
        val p = FvpConditionInput("c0", 80.0, 0.30, 0.40).toPoint()
        fit(listOf(p, p.copy(conditionId = "c1")), bodyMassKg = 80.0)
    }

    // --- Warnings (no excepcion) ---

    @Test
    fun fit_positiveSlope_emitsNonPhysicalWarning() {
        // Recta perfecta pero con pendiente positiva: F crece con V (imposible en F-V).
        val pts = listOf(
            FvpConditionPoint("c0", 80.0, 0.30, 0.40, forceN = 1000.0, velocityMps = 1.0, powerW = 1000.0),
            FvpConditionPoint("c1", 80.0, 0.30, 0.40, forceN = 1500.0, velocityMps = 1.5, powerW = 2250.0),
        )
        val p = fit(pts, bodyMassKg = 80.0)
        assertTrue(p.warnings.contains(FvpWarning.NON_PHYSICAL))
        assertTrue(p.v0Mps.isNaN())
        assertTrue(p.pmaxW.isNaN())
    }

    @Test
    fun fit_twoPoints_emitsFewPointsWarning() {
        val pts = perfectLineConditions().take(2).map { it.toPoint() }
        val p = fit(pts, bodyMassKg = 80.0)
        assertTrue(p.warnings.contains(FvpWarning.FEW_POINTS))
        assertEquals(3000.0, p.f0N, 1e-6)
    }

    @Test
    fun fit_lowFps_emitsFpsBlockedWarning() {
        val pts = perfectLineConditions().map { it.toPoint() }
        val p = fit(pts, bodyMassKg = 80.0, sourceFps = 30.0)
        assertTrue(p.warnings.contains(FvpWarning.FPS_30_BLOCKED))
    }

    // --- Reproducibilidad ---

    @Test
    fun evaluate_sameInputs_bitIdenticalResults() {
        val conds = perfectLineConditions()
        val r1 = evaluate(conds, bodyMassKg = 80.0)
        val r2 = evaluate(conds, bodyMassKg = 80.0)
        assertEquals(r1.profile, r2.profile)
        assertEquals(r1.points, r2.points)
    }
}
