package com.openjump.fvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests JVM de sensibilidad del PoC F-V-P.
 *
 * TODOS los inputs son SINTETICOS (punto base: m=80 kg, h=0.30 m, h_po=0.40 m; serie de 4
 * condiciones con cargas 0/20/40/60 kg y alturas decrecientes). Verifican ORDENES DE MAGNITUD
 * analiticos (derivadas de (1)-(2) de la spec) y MONOTONICIDAD de la respuesta F0/V0/Pmax ante
 * perturbaciones crecientes. No son datos experimentales.
 */
class FvpSensitivityTest {

    private val baseM = 80.0
    private val baseH = 0.30
    private val baseHpo = 0.40
    private val base = jumpPoint(baseM, baseH, baseHpo)

    /** Serie sintetica de 4 condiciones: a mas carga, menos altura (pendiente fisica). */
    private fun syntheticSeries(): List<FvpConditionInput> = listOf(
        FvpConditionInput("load-00", 80.0, 0.35, 0.40),
        FvpConditionInput("load-20", 100.0, 0.30, 0.40),
        FvpConditionInput("load-40", 120.0, 0.25, 0.40),
        FvpConditionInput("load-60", 140.0, 0.20, 0.40),
    )

    @Test
    fun heightPlusMinus1cm_movesForceAboutOnePointFourPercent() {
        // dF/F = dh/(h+h_po) = 0.01/0.70 ~= 1.43 %.
        val up = jumpPoint(baseM, baseH + 0.01, baseHpo)
        val down = jumpPoint(baseM, baseH - 0.01, baseHpo)
        val relUp = (up.forceN - base.forceN) / base.forceN
        val relDown = (down.forceN - base.forceN) / base.forceN
        assertEquals(0.0143, relUp, 0.003)
        assertEquals(-0.0143, relDown, 0.003)
    }

    @Test
    fun heightPlusMinus2cm_doublesResponse() {
        val d1 = jumpPoint(baseM, baseH + 0.01, baseHpo).forceN - base.forceN
        val d2 = jumpPoint(baseM, baseH + 0.02, baseHpo).forceN - base.forceN
        assertTrue(d1 > 0.0 && d2 > 0.0)
        assertEquals(2.0, d2 / d1, 0.05)
    }

    @Test
    fun bodyMassPlusMinusHalfKg_movesForceAboutZeroPointSixPercent() {
        // dF/F = dm/m = 0.5/80 = 0.625 %; V no depende de la masa.
        val up = jumpPoint(baseM + 0.5, baseH, baseHpo)
        val rel = (up.forceN - base.forceN) / base.forceN
        assertEquals(0.00625, rel, 0.0015)
        assertEquals(base.velocityMps, up.velocityMps, 0.0)
    }

    @Test
    fun pushOffPlusMinus1cm_movesForceAboutOnePercentOppositeSign() {
        // dF/F = -[h/(h+h_po)]*(dh_po/h_po) = -(0.30/0.70)*0.025 ~= -1.07 %.
        val up = jumpPoint(baseM, baseH, baseHpo + 0.01)
        val rel = (up.forceN - base.forceN) / base.forceN
        assertEquals(-0.0107, rel, 0.003)
        assertEquals(base.velocityMps, up.velocityMps, 0.0)
    }

    @Test
    fun oneFrameAt60fps_movesHeightAbout2cm() {
        // Salto sintetico h=0.30 m: t = sqrt(8h/g) ~= 0.4947 s; dh ~= (g*t/4)*dt.
        val t = sqrt(8.0 * baseH / GRAVITY)
        assertEquals(0.4947, t, 0.002)
        checkFrameDt(t, fps = 60.0, expectedCm = 2.0)
        checkFrameDt(t, fps = 120.0, expectedCm = 1.0)
        checkFrameDt(t, fps = 240.0, expectedCm = 0.5)
    }

    private fun checkFrameDt(t: Double, fps: Double, expectedCm: Double) {
        val dt = 1.0 / fps
        val hUp = GRAVITY * (t + dt) * (t + dt) / 8.0
        val hDown = GRAVITY * (t - dt) * (t - dt) / 8.0
        val dhUpCm = (hUp - baseH) * 100.0
        val dhDownCm = (hDown - baseH) * 100.0
        // Orden de magnitud del contrato: +/-2.0 / +/-1.0 / +/-0.5 cm.
        assertEquals(expectedCm, dhUpCm, 0.15)
        assertEquals(-expectedCm, dhDownCm, 0.15)
    }

    @Test
    fun profileResponse_monotoneInPerturbationSize() {
        // Perturbar la altura de UNA condicion +1 cm / +2 cm: F0, V0 y Pmax responden con el
        // mismo signo y magnitud creciente (suavidad/monotonicidad local, inputs sinteticos).
        val ref = fit(syntheticSeries().map { it.toPoint() }, bodyMassKg = 80.0)
        assertTrue(ref.warnings.none { it == FvpWarning.NON_PHYSICAL })

        fun perturbed(deltaH: Double): FvpProfile {
            val conds = syntheticSeries().toMutableList()
            conds[0] = conds[0].copy(heightM = conds[0].heightM + deltaH)
            return fit(conds.map { it.toPoint() }, bodyMassKg = 80.0)
        }

        val p1 = perturbed(0.01)
        val p2 = perturbed(0.02)
        checkMonotone(ref.f0N, p1.f0N, p2.f0N, "F0")
        checkMonotone(ref.v0Mps, p1.v0Mps, p2.v0Mps, "V0")
        checkMonotone(ref.pmaxW, p1.pmaxW, p2.pmaxW, "Pmax")
    }

    @Test
    fun profileResponse_massPerturbation_monotoneInSize() {
        // Perturbar la masa de UNA condicion +0.5 kg / +1.0 kg: mismo chequeo de monotonicidad.
        val ref = fit(syntheticSeries().map { it.toPoint() }, bodyMassKg = 80.0)

        fun perturbed(deltaM: Double): FvpProfile {
            val conds = syntheticSeries().toMutableList()
            conds[3] = conds[3].copy(totalMassKg = conds[3].totalMassKg + deltaM)
            return fit(conds.map { it.toPoint() }, bodyMassKg = 80.0)
        }

        val p1 = perturbed(0.5)
        val p2 = perturbed(1.0)
        checkMonotone(ref.f0N, p1.f0N, p2.f0N, "F0")
        checkMonotone(ref.v0Mps, p1.v0Mps, p2.v0Mps, "V0")
        checkMonotone(ref.pmaxW, p1.pmaxW, p2.pmaxW, "Pmax")
    }

    private fun checkMonotone(ref: Double, p1: Double, p2: Double, name: String) {
        val d1 = p1 - ref
        val d2 = p2 - ref
        assertTrue("$name moves (d1=$d1)", d1 != 0.0)
        assertTrue("$name keeps sign (d1=$d1, d2=$d2)", d1 * d2 > 0.0)
        assertTrue("$name grows with perturbation (|d1|=$d1, |d2|=$d2)", kotlin.math.abs(d2) > kotlin.math.abs(d1))
    }
}

/**
 * Tests de la envolvente de sensibilidad temporal ([sensitivityEnvelope], `:fvp`).
 *
 * Clase independiente de [FvpSensitivityTest] (que cubre la sensibilidad de [jumpPoint] y NO se
 * modifica). Inputs SINTETICOS; sin Android. El oraculo de esta clase calcula los valores
 * esperados con aritmetica explicita (formula cerrada de la recta por dos puntos) SIN llamar a
 * [jumpPoint], [fit] ni [sensitivityEnvelope].
 */
class FvpSensitivityEnvelopeTest {

    private val bodyMassKg = 80.0

    private fun series2() = listOf(
        FvpConditionInput("c-0", 80.0, 0.30, 0.40),
        FvpConditionInput("c-20", 100.0, 0.20, 0.40),
    )

    /** Tiempo de vuelo total coherente con la altura declarada: t = sqrt(8h/g). */
    private fun flightsOf(conditions: List<FvpConditionInput>): List<Double> =
        conditions.map { sqrt(8.0 * it.heightM / GRAVITY) }

    // --- Oraculo independiente (aritmetica explicita; no llama a la produccion) ---

    private class ExpectedPoint(val forceN: Double, val velocityMps: Double)

    private class ExpectedProfile(
        val f0N: Double,
        val sfvNsPerM: Double,
        val v0Mps: Double,
        val pmaxW: Double,
    )

    private class ExpectedEnvelope(
        val f0: Pair<Double, Double>,
        val sfv: Pair<Double, Double>,
        val v0: Pair<Double, Double>,
        val pmax: Pair<Double, Double>,
        val evaluated: Int,
        val skipped: Int,
    )

    private fun expectedHeightM(flightTimeS: Double): Double = GRAVITY * flightTimeS * flightTimeS / 8.0

    private fun expectedPoint(massKg: Double, heightM: Double, pushOffM: Double) = ExpectedPoint(
        forceN = massKg * GRAVITY * (heightM / pushOffM + 1.0),
        velocityMps = sqrt(GRAVITY * heightM / 2.0),
    )

    /**
     * Recta por DOS puntos con formula cerrada: sfv = -(F2-F1)/(V2-V1), f0 = F1 + sfv*V1,
     * V0 = F0/Sfv y Pmax = F0*V0/4 cuando Sfv > 0 y F0 > 0 (misma condicion de fisicalidad de la
     * produccion, para reproducir el NaN de V0/Pmax).
     */
    private fun expectedLine(first: ExpectedPoint, second: ExpectedPoint): ExpectedProfile {
        val sfv = -(second.forceN - first.forceN) / (second.velocityMps - first.velocityMps)
        val f0 = first.forceN + sfv * first.velocityMps
        val physical = sfv > 0.0 && f0 > 0.0
        val v0 = if (physical) f0 / sfv else Double.NaN
        return ExpectedProfile(f0, sfv, v0, if (physical) f0 * v0 / 4.0 else Double.NaN)
    }

    /**
     * Envolvente esperada para una serie de DOS condiciones con nominal fisico (limite del
     * oraculo de formula cerrada). Reproduce el descarte por dominio (t' <= 0 o h' fuera de
     * (0, MAX]) y el filtro de valores finitos de V0/Pmax, que forman parte del contrato.
     */
    private fun expectedEnvelope(
        conditions: List<FvpConditionInput>,
        flightTimesS: List<Double>,
        fpsPerCondition: List<Double>,
    ): ExpectedEnvelope {
        require(conditions.size == 2) { "oraculo de formula cerrada solo para 2 condiciones" }
        val nominal = conditions.map { expectedPoint(it.totalMassKg, it.heightM, it.pushOffM) }
        val nominalProfile = expectedLine(nominal[0], nominal[1])
        val f0 = mutableListOf(nominalProfile.f0N)
        val sfv = mutableListOf(nominalProfile.sfvNsPerM)
        val v0 = mutableListOf<Double>()
        val pmax = mutableListOf<Double>()
        if (nominalProfile.v0Mps.isFinite()) v0 += nominalProfile.v0Mps
        if (nominalProfile.pmaxW.isFinite()) pmax += nominalProfile.pmaxW
        var evaluated = 0
        var skipped = 0
        conditions.forEachIndexed { index, condition ->
            for (frames in listOf(1, -1, 2, -2)) {
                val shifted = flightTimesS[index] + frames / fpsPerCondition[index]
                if (shifted <= 0.0) {
                    skipped++
                    continue
                }
                val height = expectedHeightM(shifted)
                if (height <= 0.0 || height > MAX_JUMP_HEIGHT_M) {
                    skipped++
                    continue
                }
                val perturbed = nominal.toMutableList()
                perturbed[index] = expectedPoint(condition.totalMassKg, height, condition.pushOffM)
                val line = expectedLine(perturbed[0], perturbed[1])
                evaluated++
                f0 += line.f0N
                sfv += line.sfvNsPerM
                if (line.v0Mps.isFinite()) v0 += line.v0Mps
                if (line.pmaxW.isFinite()) pmax += line.pmaxW
            }
        }
        return ExpectedEnvelope(
            f0 = f0.min() to f0.max(),
            sfv = sfv.min() to sfv.max(),
            v0 = v0.min() to v0.max(),
            pmax = pmax.min() to pmax.max(),
            evaluated = evaluated,
            skipped = skipped,
        )
    }

    // --- Tests ---

    @Test
    fun insufficientInputs_returnNull_whichIsNotZeroSensitivity() {
        val conditions = series2()
        val flights = flightsOf(conditions)
        val fps = listOf(240.0, 240.0)

        // Menos de 2 condiciones, o longitudes que no emparejan.
        assertNull(sensitivityEnvelope(emptyList(), emptyList(), emptyList(), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions.take(1), flights.take(1), fps.take(1), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights.take(1), fps, bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights, fps.take(1), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights + 0.5, fps, bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights, fps + 240.0, bodyMassKg))

        // Tiempo de vuelo no finito o <= 0.
        assertNull(sensitivityEnvelope(conditions, listOf(Double.NaN, flights[1]), fps, bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, listOf(Double.POSITIVE_INFINITY, flights[1]), fps, bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, listOf(0.0, flights[1]), fps, bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, listOf(-0.5, flights[1]), fps, bodyMassKg))

        // FPS no finita o <= 0: entrada insuficiente, NUNCA una fps sintetica de relleno.
        assertNull(sensitivityEnvelope(conditions, flights, listOf(Double.NaN, 240.0), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights, listOf(Double.NEGATIVE_INFINITY, 240.0), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights, listOf(0.0, 240.0), bodyMassKg))
        assertNull(sensitivityEnvelope(conditions, flights, listOf(-120.0, 240.0), bodyMassKg))

        // Masa corporal de referencia invalida.
        assertNull(sensitivityEnvelope(conditions, flights, fps, Double.NaN))
        assertNull(sensitivityEnvelope(conditions, flights, fps, Double.POSITIVE_INFINITY))
        assertNull(sensitivityEnvelope(conditions, flights, fps, 0.0))
        assertNull(sensitivityEnvelope(conditions, flights, fps, -80.0))
    }

    @Test
    fun handCheckableTwoConditionSeries_matchesExplicitArithmetic() {
        val conditions = series2()
        val flights = flightsOf(conditions)
        val fps = listOf(240.0, 240.0)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        // Alturas desplazadas a mano (h' = g*t'^2/8): 0.305075 m para c-0 con +1 frame a 240 fps
        // y 0.191833 m para c-20 con -2 frames (t = 0.4039240 s).
        assertEquals(0.305075, expectedHeightM(flights[0] + 1.0 / 240.0), 1e-6)
        assertEquals(0.191833, expectedHeightM(flights[1] - 2.0 / 240.0), 1e-6)

        // Endpoints y nominales esperados (aritmetica explicita + literales).
        assertEquals(1907.343385861, band.f0NominalN, 1e-6)
        assertEquals(440.626434005, band.sfvNominalNsPerM, 1e-6)
        assertEquals(4.328708490, band.v0NominalMps, 1e-6)
        assertEquals(2064.083376999, band.pmaxNominalW, 1e-6)
        assertEquals(1762.467995822, band.f0MinN, 1e-6)
        assertEquals(2084.010081292, band.f0MaxN, 1e-6)
        assertEquals(321.175747276, band.sfvMinNsPerM, 1e-6)
        assertEquals(586.289255592, band.sfvMaxNsPerM, 1e-6)
        assertEquals(3.515999969, band.v0MinMps, 1e-6)
        assertEquals(5.568382512, band.v0MaxMps, 1e-6)
        assertEquals(1799.970485829, band.pmaxMinW, 1e-6)
        assertEquals(2490.720847869, band.pmaxMaxW, 1e-6)

        // El nominal es el mismo ajuste de 2 puntos: los 9 valores son colineales, r2 = 1.
        assertEquals(1.0, band.r2Nominal, 1e-9)
        assertEquals(1.0, band.r2Min, 1e-9)

        // 8 variantes (2 condiciones * 4 k) y ninguna descartada: a 240 fps todo h' esta en dominio.
        assertEquals(8, band.variantsEvaluated)
        assertEquals(0, band.variantsSkipped)
        assertTrue(band.skippedByReason.isEmpty())

        // Mismo resultado contra el oraculo independiente del archivo (formula cerrada).
        val expected = expectedEnvelope(conditions, flights, fps)
        assertEquals(8, expected.evaluated)
        assertEquals(0, expected.skipped)
        assertEquals(expected.f0.first, band.f0MinN, 1e-6)
        assertEquals(expected.f0.second, band.f0MaxN, 1e-6)
        assertEquals(expected.sfv.first, band.sfvMinNsPerM, 1e-6)
        assertEquals(expected.sfv.second, band.sfvMaxNsPerM, 1e-6)
        assertEquals(expected.v0.first, band.v0MinMps, 1e-6)
        assertEquals(expected.v0.second, band.v0MaxMps, 1e-6)
        assertEquals(expected.pmax.first, band.pmaxMinW, 1e-6)
        assertEquals(expected.pmax.second, band.pmaxMaxW, 1e-6)

        // El nominal SIEMPRE queda dentro de los cuatro rangos.
        assertTrue(band.f0MinN <= band.f0NominalN && band.f0NominalN <= band.f0MaxN)
        assertTrue(band.sfvMinNsPerM <= band.sfvNominalNsPerM && band.sfvNominalNsPerM <= band.sfvMaxNsPerM)
        assertTrue(band.v0MinMps <= band.v0NominalMps && band.v0NominalMps <= band.v0MaxMps)
        assertTrue(band.pmaxMinW <= band.pmaxNominalW && band.pmaxNominalW <= band.pmaxMaxW)
    }

    @Test
    fun nominalValues_areInsideEveryRange_andMethodDocumentsTheHypothesis() {
        val conditions = listOf(
            FvpConditionInput("load-00", 80.0, 0.35, 0.40),
            FvpConditionInput("load-20", 100.0, 0.30, 0.40),
            FvpConditionInput("load-40", 120.0, 0.25, 0.40),
            FvpConditionInput("load-60", 140.0, 0.20, 0.40),
        )
        val flights = flightsOf(conditions)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, List(4) { 120.0 }, bodyMassKg))

        assertTrue(band.f0MinN.isFinite() && band.f0MaxN.isFinite())
        assertTrue(band.f0MinN <= band.f0NominalN && band.f0NominalN <= band.f0MaxN)
        assertTrue(band.sfvMinNsPerM.isFinite() && band.sfvMaxNsPerM.isFinite())
        assertTrue(band.sfvMinNsPerM <= band.sfvNominalNsPerM && band.sfvNominalNsPerM <= band.sfvMaxNsPerM)
        assertTrue(band.v0MinMps.isFinite() && band.v0MaxMps.isFinite())
        assertTrue(band.v0MinMps <= band.v0NominalMps && band.v0NominalMps <= band.v0MaxMps)
        assertTrue(band.pmaxMinW.isFinite() && band.pmaxMaxW.isFinite())
        assertTrue(band.pmaxMinW <= band.pmaxNominalW && band.pmaxNominalW <= band.pmaxMaxW)
        assertTrue(band.r2Min.isFinite() && band.r2Min <= band.r2Nominal + 1e-12)

        // 4 condiciones * 4 k = 16 variantes, ninguna descartada a 120 fps.
        assertEquals(16, band.variantsEvaluated)
        assertEquals(0, band.variantsSkipped)

        // El metodo publica version, supuesto y limites (no una acreditacion).
        assertTrue(band.method.startsWith(SENSITIVITY_METHOD_VERSION))
        assertTrue(band.method.contains("NO intervalo de confianza"))
        assertTrue(band.method.contains("NO incertidumbre de medida"))
        assertTrue(band.method.contains("NO cota de peor caso garantizada"))
        assertTrue(band.method.contains("nVariantsEvaluated=16"))
        assertTrue(band.method.contains("nVariantsSkipped=0"))
    }

    @Test
    fun variantOutOfDomain_isCountedAsSkipped_andNeverClamped() {
        val conditions = listOf(
            FvpConditionInput("hi", 80.0, 1.15, 0.40),
            FvpConditionInput("lo", 100.0, 0.90, 0.40),
        )
        val flights = flightsOf(conditions)
        val fps = listOf(30.0, 240.0)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        // Los desplazamientos +1 y +2 de "hi" a 30 fps salen de (0, 1.2].
        val shiftedPlusOne = expectedHeightM(flights[0] + 1.0 / 30.0)
        val shiftedPlusTwo = expectedHeightM(flights[0] + 2.0 / 30.0)
        assertTrue(shiftedPlusOne > MAX_JUMP_HEIGHT_M && shiftedPlusTwo > MAX_JUMP_HEIGHT_M)
        assertEquals(6, band.variantsEvaluated)
        assertEquals(2, band.variantsSkipped)
        assertEquals(mapOf(FvpSensitivitySkip.HEIGHT_OUT_OF_ENGINE_DOMAIN to 2), band.skippedByReason)

        val expected = expectedEnvelope(conditions, flights, fps)
        assertEquals(6, expected.evaluated)
        assertEquals(2, expected.skipped)
        assertEquals(expected.f0.first, band.f0MinN, 1e-6)
        assertEquals(expected.f0.second, band.f0MaxN, 1e-6)
        assertEquals(expected.sfv.first, band.sfvMinNsPerM, 1e-6)
        assertEquals(expected.sfv.second, band.sfvMaxNsPerM, 1e-6)
        assertEquals(expected.v0.first, band.v0MinMps, 1e-6)
        assertEquals(expected.v0.second, band.v0MaxMps, 1e-6)
        assertEquals(expected.pmax.first, band.pmaxMinW, 1e-6)
        assertEquals(expected.pmax.second, band.pmaxMaxW, 1e-6)

        // Si se hubiera RECORTADO h' a 1.2 m en vez de descartar la variante, esos valores
        // habrian entrado en los rangos: los cuatro rangos los excluyen.
        val clamped = expectedLine(
            expectedPoint(80.0, MAX_JUMP_HEIGHT_M, 0.40),
            expectedPoint(100.0, 0.90, 0.40),
        )
        assertTrue("F0min=${band.f0MinN} deberia excluir la variante recortada", band.f0MinN > clamped.f0N)
        assertTrue("Sfvmin=${band.sfvMinNsPerM} deberia excluir la variante recortada", band.sfvMinNsPerM > clamped.sfvNsPerM)
        assertTrue("V0max=${band.v0MaxMps} deberia excluir la variante recortada", band.v0MaxMps < clamped.v0Mps)
        assertTrue("Pmaxmax=${band.pmaxMaxW} deberia excluir la variante recortada", band.pmaxMaxW < clamped.pmaxW)
    }

    @Test
    fun shiftedFlightTimeNotPositive_isCountedAsSkipped_andNonPhysicalVariantStaysOut() {
        val conditions = listOf(
            FvpConditionInput("short", 120.0, 0.05, 0.40),
            FvpConditionInput("tall", 60.0, 0.35, 0.40),
        )
        val flights = flightsOf(conditions)
        val fps = listOf(4.0, 240.0)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        // "short" tiene t = 0.201962 s: -1 y -2 frames a 4 fps dan t' <= 0.
        assertTrue(flights[0] - 1.0 / 4.0 <= 0.0 && flights[0] - 2.0 / 4.0 <= 0.0)
        assertEquals(6, band.variantsEvaluated)
        assertEquals(2, band.variantsSkipped)
        assertEquals(mapOf(FvpSensitivitySkip.NON_POSITIVE_SHIFTED_FLIGHT_TIME to 2), band.skippedByReason)

        // Una variante evaluada puede ser NO FISICA (V0/Pmax NaN): cuenta como evaluada y queda
        // fuera de los rangos de V0/Pmax, que solo acumulan valores finitos.
        val nonPhysical = expectedLine(
            expectedPoint(120.0, expectedHeightM(flights[0] + 2.0 / 4.0), 0.40),
            expectedPoint(60.0, 0.35, 0.40),
        )
        assertTrue(nonPhysical.v0Mps.isNaN() && nonPhysical.pmaxW.isNaN())

        val expected = expectedEnvelope(conditions, flights, fps)
        assertEquals(6, expected.evaluated)
        assertEquals(2, expected.skipped)
        assertEquals(expected.f0.first, band.f0MinN, 1e-6)
        assertEquals(expected.f0.second, band.f0MaxN, 1e-6)
        assertEquals(expected.sfv.first, band.sfvMinNsPerM, 1e-6)
        assertEquals(expected.sfv.second, band.sfvMaxNsPerM, 1e-6)
        assertEquals(expected.v0.first, band.v0MinMps, 1e-6)
        assertEquals(expected.v0.second, band.v0MaxMps, 1e-6)
        assertEquals(expected.pmax.first, band.pmaxMinW, 1e-6)
        assertEquals(expected.pmax.second, band.pmaxMaxW, 1e-6)
        assertTrue(band.v0MinMps.isFinite() && band.v0MaxMps.isFinite())
    }

    @Test
    fun noEvaluableVariant_returnsNull_becauseThereIsNoEnvelopeToShow() {
        val conditions = listOf(
            FvpConditionInput("a", 80.0, 0.11, 0.40),
            FvpConditionInput("b", 100.0, 0.09, 0.40),
        )
        val flights = flightsOf(conditions)
        val fps = listOf(1.0, 1.0)

        // Ninguna variante evaluable: NO se devuelve una banda colapsada al nominal (se leeria
        // como 'sensibilidad cero'). `null` es el unico camino de "no disponible".
        assertNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        // El oraculo independiente confirma que efectivamente no habia ninguna variante evaluable
        // (los 8 intentos se descartan por t' <= 0 o h' fuera de dominio), asi que el null de
        // arriba no se debe a un fallo de las entradas.
        val expected = expectedEnvelope(conditions, flights, fps)
        assertEquals(0, expected.evaluated)
        assertEquals(8, expected.skipped)
    }

    /**
     * Banda NO nula con rangos NaN: si TODAS las variantes (y el nominal) son no fisicas, V0 y
     * Pmax no tienen ningun valor finito. Es alcanzable (misma masa total en todas las
     * condiciones => la fuerza crece con la velocidad) y el consumidor debe leerlo como "no
     * disponible" para esos dos valores, NUNCA como sensibilidad cero.
     */
    @Test
    fun allVariantsNonPhysical_bandIsNotNullButVelocityAndPowerRangesAreNaN() {
        val conditions = listOf(
            FvpConditionInput("a", 80.0, 0.25, 0.40),
            FvpConditionInput("b", 80.0, 0.35, 0.40),
        )
        val flights = flightsOf(conditions)
        val fps = listOf(240.0, 240.0)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        assertEquals(8, band.variantsEvaluated)
        assertEquals(0, band.variantsSkipped)
        assertTrue("el nominal no fisico no tiene V0", band.v0NominalMps.isNaN())
        assertTrue("rangos de V0 sin valores finitos", band.v0MinMps.isNaN() && band.v0MaxMps.isNaN())
        assertTrue("rangos de Pmax sin valores finitos", band.pmaxMinW.isNaN() && band.pmaxMaxW.isNaN())
        assertTrue("F0 si se puede acotar", band.f0MinN.isFinite() && band.f0MaxN.isFinite())
        assertTrue("Sfv si se puede acotar", band.sfvMinNsPerM.isFinite() && band.sfvMaxNsPerM.isFinite())
    }

    /**
     * Ancho 0 documentado: si las alturas nominales son EXACTAMENTE las que produce la formula
     * del vuelo y la fps es tan alta que k periodos no cambian el tiempo en doble precision, las
     * variantes se evaluan (recuento = 4 por condicion) pero no mueven ningun numero. El
     * resultado es una banda real, NO `null`, con ancho 0: eso es "sin movimiento", no
     * "no disponible".
     */
    @Test
    fun variantsThatDoNotMoveTheNumbers_giveARealZeroWidthBand_notNull() {
        // Alturas y vuelos coherentes por construccion: h_i sale de la MISMA formula que usa la
        // funcion (asi el viaje de ida y vuelta es exacto y las variantes no cambian nada).
        val flights = listOf(0.32, 0.26)
        val conditions = flights.mapIndexed { index, t ->
            FvpConditionInput("c$index", 80.0 + index * 20.0, GRAVITY * t * t / 8.0, 0.40)
        }
        val fps = listOf(1.0e20, 1.0e20)
        val band = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))

        assertEquals(8, band.variantsEvaluated)
        assertEquals(0, band.variantsSkipped)
        assertEquals(band.f0NominalN, band.f0MinN, 0.0)
        assertEquals(band.f0NominalN, band.f0MaxN, 0.0)
        assertEquals(band.sfvNominalNsPerM, band.sfvMinNsPerM, 0.0)
        assertEquals(band.sfvNominalNsPerM, band.sfvMaxNsPerM, 0.0)
    }

    @Test
    fun sameInputs_produceIdenticalResult() {
        val conditions = series2()
        val flights = flightsOf(conditions)
        val fps = listOf(60.0, 240.0)
        val first = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))
        val second = requireNotNull(sensitivityEnvelope(conditions, flights, fps, bodyMassKg))
        assertEquals(first, second)
        assertEquals(first.method, second.method)
        assertEquals(first.skippedByReason, second.skippedByReason)
    }

    @Test
    fun lowerFps_widensTheF0Range_forThisConstruction() {
        val conditions = listOf(
            FvpConditionInput("load-00", 80.0, 0.35, 0.40),
            FvpConditionInput("load-20", 100.0, 0.30, 0.40),
            FvpConditionInput("load-40", 120.0, 0.25, 0.40),
            FvpConditionInput("load-60", 140.0, 0.20, 0.40),
        )
        val flights = flightsOf(conditions)
        val lowFps = requireNotNull(sensitivityEnvelope(conditions, flights, List(4) { 60.0 }, bodyMassKg))
        val highFps = requireNotNull(sensitivityEnvelope(conditions, flights, List(4) { 240.0 }, bodyMassKg))

        // Con esta serie sintetica ninguna variante se descarta a 60 ni a 240 fps, asi que la
        // monotonia es comparable directamente. No es un teorema general: una variante descartada
        // a baja fps si podria estrechar el rango.
        assertEquals(16, lowFps.variantsEvaluated)
        assertEquals(16, highFps.variantsEvaluated)
        assertEquals(0, lowFps.variantsSkipped)
        assertEquals(0, highFps.variantsSkipped)

        val lowWidth = lowFps.f0MaxN - lowFps.f0MinN
        val highWidth = highFps.f0MaxN - highFps.f0MinN
        assertEquals(1932.577112389206, lowWidth, 1e-6)
        assertEquals(499.109037314085, highWidth, 1e-6)
        assertTrue("ancho a 60 fps ($lowWidth) deberia ser >= ancho a 240 fps ($highWidth)", lowWidth >= highWidth)
    }
}
