package com.openjump.fvp.lab

import com.openjump.fvp.GRAVITY
import com.openjump.fvp.jumpPoint
import com.openjump.fvp.toPoint
import com.openjump.fvp.FvpConditionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests de bandas FPS con inputs SINTETICOS (h=0.30 m base) + una serie sintetica de 4
 * condiciones (cargas 0/20/40/60 kg, alturas decrecientes). Verifican magnitudes del
 * contrato 60/120/240 y propiedades de los rangos (contienen al nominal, R²min <= nominal).
 */
class LabBandsTest {

    @Test
    fun heightDelta_oneFrame_matchesContract60_120_240() {
        // h=0.30 m sintetico: t = sqrt(8h/g) ~= 0.4947 s (igual que FvpSensitivityTest de :fvp).
        val t = sqrt(8.0 * 0.30 / GRAVITY)
        assertEquals(0.4947, t, 0.002)
        // Contrato: ±1 frame ~= ±2.0 cm a 60 fps, ±1.0 cm a 120, ±0.5 cm a 240 (δ 0.15 cm).
        assertEquals(2.0, LabBands.heightDeltaCm(t, 60.0, 1), 0.15)
        assertEquals(-2.0, LabBands.heightDeltaCm(t, 60.0, -1), 0.15)
        assertEquals(1.0, LabBands.heightDeltaCm(t, 120.0, 1), 0.15)
        assertEquals(-1.0, LabBands.heightDeltaCm(t, 120.0, -1), 0.15)
        assertEquals(0.5, LabBands.heightDeltaCm(t, 240.0, 1), 0.15)
        assertEquals(-0.5, LabBands.heightDeltaCm(t, 240.0, -1), 0.15)
    }

    @Test
    fun heightDelta_twoFrames_doublesResponse() {
        val t = sqrt(8.0 * 0.30 / GRAVITY)
        val d1 = LabBands.heightDeltaCm(t, 60.0, 1)
        val d2 = LabBands.heightDeltaCm(t, 60.0, 2)
        assertTrue(d1 > 0.0 && d2 > 0.0)
        // Aproximadamente el doble (linealizacion dh ~= (g·t/4)·dt; segundo orden pequeno).
        assertEquals(2.0, d2 / d1, 0.08)
    }

    private fun syntheticSeries(): List<FvpConditionInput> = listOf(
        FvpConditionInput("load-00", 80.0, 0.35, 0.40),
        FvpConditionInput("load-20", 100.0, 0.30, 0.40),
        FvpConditionInput("load-40", 120.0, 0.25, 0.40),
        FvpConditionInput("load-60", 140.0, 0.20, 0.40),
    )

    @Test
    fun bands_containNominal_andR2MinBelowNominal() {
        val conds = syntheticSeries()
        val points = conds.map { it.toPoint() }
        val flights = conds.map { sqrt(8.0 * it.heightM / GRAVITY) }
        val bands = LabBands.compute(points, flights, 80.0, 60.0)
        val nominal = com.openjump.fvp.fit(points, 80.0)
        // Rangos finitos y contienen al nominal por construccion.
        assertTrue(bands.f0MinN.isFinite() && bands.f0MaxN.isFinite())
        assertTrue(bands.f0MinN <= nominal.f0N && nominal.f0N <= bands.f0MaxN)
        assertTrue(bands.sfvMinNsPerM.isFinite() && bands.sfvMaxNsPerM.isFinite())
        assertTrue(bands.sfvMinNsPerM <= nominal.sfvNsPerM && nominal.sfvNsPerM <= bands.sfvMaxNsPerM)
        assertTrue(bands.v0MinMps.isFinite() && bands.v0MaxMps.isFinite())
        assertTrue(bands.v0MinMps <= nominal.v0Mps && nominal.v0Mps <= bands.v0MaxMps)
        assertTrue(bands.pmaxMinW.isFinite() && bands.pmaxMaxW.isFinite())
        assertTrue(bands.pmaxMinW <= nominal.pmaxW && nominal.pmaxW <= bands.pmaxMaxW)
        // R²min <= nominal (las perturbaciones no mejoran el ajuste perfecto mas alla del nominal).
        assertTrue(bands.r2Min.isFinite() && nominal.r2.isFinite())
        assertTrue(bands.r2Min <= nominal.r2 + 1e-12)
        assertTrue(bands.method.contains("60.0Hz"))
        // La serie sintetica base es fisica: el punto base verifica jumpPoint.
        val base = jumpPoint(80.0, 0.30, 0.40)
        assertTrue(base.forceN.isFinite() && base.velocityMps.isFinite())
    }

    @Test
    fun bands_lowerFps_widerThanHigherFps() {
        val conds = syntheticSeries()
        val points = conds.map { it.toPoint() }
        val flights = conds.map { sqrt(8.0 * it.heightM / GRAVITY) }
        val b60 = LabBands.compute(points, flights, 80.0, 60.0)
        val b240 = LabBands.compute(points, flights, 80.0, 240.0)
        val w60 = b60.f0MaxN - b60.f0MinN
        val w240 = b240.f0MaxN - b240.f0MinN
        assertTrue(w60.isFinite() && w240.isFinite())
        assertTrue("60fps band ($w60) should exceed 240fps band ($w240)", w60 > w240)
    }
}
