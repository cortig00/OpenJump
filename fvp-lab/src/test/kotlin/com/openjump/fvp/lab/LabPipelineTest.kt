package com.openjump.fvp.lab

import com.openjump.fvp.FvpWarning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests JVM del pipeline de laboratorio sobre series REALES (vuelos PTS del catalogo dorado)
 * con masas/h_po/fps SINTETICOS declarados.
 *
 * - S1 (control-negativo, 5 SJ misma masa): comportamiento CORRECTO es NARROW_VELOCITY_RANGE
 *   + NON_PHYSICAL con R²~=1 (los 5 puntos caen sobre una parabola F(V) que en rango
 *   estrecho parece recta). LOW_R2 NO se espera aqui; el test lo fija como hallazgo honesto.
 * - S2 (pipeline, 4 vuelos reales + masas sinteticas ordenadas): asserts SOLO
 *   finitud/determinismo bit-identico; valores numericos al informe 08 (prohibido
 *   presentarlos como validez biomecanica).
 * - S3 (acuerdo vuelo-vs-plataforma, 22 trials, SIN fitting): media abs < 1 cm con los
 *   datos actuales (0.18 cm observado); si el dato cambiara, el mensaje muestra el valor
 *   real sin forzar el PASS.
 */
class LabPipelineTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadGolden(): List<GoldenTrial> = LabGoldenAdapter.load(resolveGoldenPath())

    @Test
    fun s1_negativeControl_emitsNarrowAndNonPhysical() {
        val golden = loadGolden()
        val byKey = golden.associateBy { it.trialKey }
        val series = LabSeriesParser.load(resolveLabPath("s1-negative-control.json"))
        assertEquals("S1-negative-control", series.id)
        assertEquals(5, series.conditions.size)

        val result = LabPipeline.run(series, byKey)
        assertEquals("fvp-lab-1.0", result.algorithmVersion)
        assertEquals(5, result.points.size)
        // Gate CONDITIONAL (fps declarado 60 en las 5 condiciones).
        assertEquals(FpsGateDecision.CONDITIONAL, result.gate)
        // Comportamiento correcto del control-negativo:
        assertTrue(
            "S1 must emit NARROW_VELOCITY_RANGE, was ${result.warnings}",
            result.warnings.contains(FvpWarning.NARROW_VELOCITY_RANGE),
        )
        assertTrue(
            "S1 must emit NON_PHYSICAL (misma masa => pendiente positiva), was ${result.warnings}",
            result.warnings.contains(FvpWarning.NON_PHYSICAL),
        )
        assertTrue("S1 V0 must be NaN (non-physical)", result.profile.v0Mps.isNaN())
        assertTrue("S1 Pmax must be NaN (non-physical)", result.profile.pmaxW.isNaN())
        // Hallazgo honesto: R²~=1 aunque el perfil sea invalido (parabola en rango estrecho).
        assertTrue("S1 R² must be finite", result.profile.r2.isFinite())
        assertTrue(
            "S1 R²~=1 so LOW_R2 must NOT trigger; warnings=${result.warnings}",
            !result.warnings.contains(FvpWarning.LOW_R2),
        )
        assertEquals(1.0, result.profile.r2, 0.001)
        // Alturas recomputadas desde PTS coinciden con el balistico cacheado (misma formula).
        result.points.forEach { p ->
            val trial = byKey.getValue(p.conditionId)
            assertEquals(trial.ballisticHcm, p.heightM * 100.0, 1e-6)
        }
        // Bandas contienen al nominal aunque sea no-fisico (F0/Sfv finitos; V0/Pmax NaN).
        assertTrue(result.bands.f0MinN.isFinite() && result.bands.f0MaxN.isFinite())
        assertTrue(result.bands.method.contains("60.0Hz"))
    }

    @Test
    fun s2_pipeline_isFiniteAndBitIdentical() {
        val golden = loadGolden()
        val byKey = golden.associateBy { it.trialKey }
        val series = LabSeriesParser.load(resolveLabPath("s2-pipeline.json"))
        assertEquals("S2-pipeline", series.id)
        assertEquals(4, series.conditions.size)

        val r1 = LabPipeline.run(series, byKey)
        val r2 = LabPipeline.run(series, byKey)
        // Determinismo bit-identico.
        assertEquals(r1.profile, r2.profile)
        assertEquals(r1.points, r2.points)
        assertEquals(r1.warnings, r2.warnings)
        // Finitud (serie ordenada altura-desc/masa-asc da pendiente fisica computable).
        assertTrue("F0 finite, was ${r1.profile.f0N}", r1.profile.f0N.isFinite())
        assertTrue("Sfv finite, was ${r1.profile.sfvNsPerM}", r1.profile.sfvNsPerM.isFinite())
        assertTrue("V0 finite, was ${r1.profile.v0Mps}", r1.profile.v0Mps.isFinite())
        assertTrue("Pmax finite, was ${r1.profile.pmaxW}", r1.profile.pmaxW.isFinite())
        assertTrue("R² finite, was ${r1.profile.r2}", r1.profile.r2.isFinite())
        assertEquals(FpsGateDecision.CONDITIONAL, r1.gate)
        // Bandas finitas y metodo documentado (valores numericos al informe 08, no aqui).
        assertTrue(r1.bands.f0MinN.isFinite() && r1.bands.f0MaxN.isFinite())
        assertTrue(r1.bands.v0MinMps.isFinite() && r1.bands.v0MaxMps.isFinite())
        assertTrue(r1.bands.r2Min.isFinite())
        assertTrue(r1.bands.method.contains("perturb-1-condition-at-a-time"))
        // Trazabilidad: 4 puntos con trialKeys de la serie.
        assertEquals(
            series.conditions.map { it.trialKey }.sorted(),
            r1.points.map { it.conditionId }.sorted(),
        )
    }

    @Test
    fun s3_agreement_meanAbsBelow1cm() {
        val golden = loadGolden()
        val manifestText = File(resolveLabPath("s3-agreement.json")).readText(Charsets.UTF_8)
        val manifest = json.parseToJsonElement(manifestText).jsonObject
        assertEquals(
            "com.openjump.fvp-lab-agreement",
            manifest["schema"]?.jsonPrimitive?.content,
        )
        val keys = manifest["trialKeys"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(22, keys.size)
        val byKey = golden.associateBy { it.trialKey }
        val trials = keys.map { k ->
            byKey[k] ?: throw IllegalStateException("S3 manifest key not in golden: $k")
        }
        val agreement = LabPipeline.agreement(trials)
        assertEquals(22, agreement.rows.size)
        // Valor OBSERVADO con los datos actuales: media abs ~= 0.18 cm (ver 08).
        // El assert fija el umbral del contrato (< 1 cm) pero el mensaje muestra el valor
        // real para no forzar un PASS si los datos cambian.
        assertTrue(
            "S3 meanAbs=${agreement.meanAbsCm} cm must be < 1.0 cm with current data",
            agreement.meanAbsCm < 1.0,
        )
        // El outlier conocido (CMJ-01 rep_02, diff -2.23 cm) debe seguir visible en la tabla.
        val outlier = agreement.rows.first { it.trialKey == "JUMP-CMJ-01__rep_02" }
        assertEquals(-2.2269774075000015, outlier.diffCm, 1e-9)
        // Media abs coherente con el calculo manual (0.1847 cm).
        assertEquals(0.1847, agreement.meanAbsCm, 0.01)
        // Cada fila: diff = balistica - instrumento.
        agreement.rows.forEach { row ->
            val t = byKey.getValue(row.trialKey)
            assertEquals(t.ballisticHcm - t.instrumentHcm, row.diffCm, 1e-12)
            val check: Double? = row.diffCm
            assertTrue(check!!.isFinite())
        }
        // Sin fitting en S3: este test no toca :fvp fit (solo acuerdo).
        assertTrue(agreement.maxAbsCm.isFinite() && agreement.maxAbsCm > agreement.meanAbsCm)
    }

    @Test
    fun s3_manifestListsAllGoldenKeys() {
        val goldenKeys = loadGolden().map { it.trialKey }.sorted()
        val manifestText = File(resolveLabPath("s3-agreement.json")).readText(Charsets.UTF_8)
        val keys = json.parseToJsonElement(manifestText).jsonObject["trialKeys"]!!
            .jsonArray.map { it.jsonPrimitive.content }.sorted()
        assertEquals(goldenKeys, keys)
    }
}
