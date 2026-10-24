package com.openjump.fvp.lab

import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.GRAVITY
import com.openjump.fvp.fit
import com.openjump.fvp.jumpPoint

/**
 * Bandas de incertidumbre FPS por perturbacion de a UNA condicion cada vez.
 *
 * HIPOTESIS FPS EXPLICITA (metodo documentado, serie sintetica salvo alturas base):
 * - La altura base de cada condicion es REAL (vuelo PTS del catalogo dorado, h=g·t²/8).
 * - El error de marcado se modela como error TOTAL de vuelo de ±1 frame (±1/fps) y
 *   peor-caso ±2 frames (±2/fps). Es error total (no por evento): cubre el caso en que
 *   despegue y aterrizaje se desplazan en sentidos opuestos en 1 frame cada uno de forma
 *   agregada como ±1 periodo, y el doble para el peor-caso.
 * - Para cada condicion i y cada variante (k ∈ {1,2} frames, signo ±): se recalcula
 *   t_i' = t_i ± k/fps, h_i' = g·t_i'²/8 (clamp > 0), se re-estima el punto via
 *   `:fvp jumpPoint(m_i, h_i', h_po,i)` y se reajusta la recta con TODAS las demas
 *   condiciones nominales (perturbacion de a UNA cada vez, 4 variantes por condicion).
 * - Se publican rangos [min, max] de F0/Sfv/V0/Pmax sobre variantes con valores finitos
 *   (las variantes no-fisicas con V0/Pmax NaN se excluyen de esos rangos pero cuentan
 *   para R²min) y R²min = minimo R² finito observado (nominal + variantes).
 * - El nominal SIEMPRE se incluye en los rangos (los rangos contienen al nominal por
 *   construccion: min=min(nominal, variantes), max=max(nominal, variantes)).
 *
 * Limitacion: el modelo ignora error en masa/h_po y correlacion entre condiciones; solo
 * propaga error temporal de video. Masas, h_po, fps asumidos y emparejamientos son
 * SINTETICOS donde la serie asi lo declare.
 */
data class LabBandsResult(
    val f0MinN: Double,
    val f0MaxN: Double,
    val sfvMinNsPerM: Double,
    val sfvMaxNsPerM: Double,
    val v0MinMps: Double,
    val v0MaxMps: Double,
    val pmaxMinW: Double,
    val pmaxMaxW: Double,
    val r2Min: Double,
    val r2Nominal: Double,
    /** Metodo aplicado (hipotesis fps + frames + n de variantes evaluadas). */
    val method: String,
)

object LabBands {
    /**
     * Delta de altura [cm] al desplazar el vuelo total en [frames] periodos a [fps].
     * Positivo para +frames, negativo para -frames (antisimetrico salvo redondeo).
     */
    fun heightDeltaCm(flightTimeS: Double, fps: Double, frames: Int = 1): Double {
        require(flightTimeS.isFinite() && flightTimeS > 0.0) { "flightTimeS must be > 0" }
        require(fps.isFinite() && fps > 0.0) { "fps must be > 0" }
        require(frames != 0) { "frames must be != 0" }
        val dt = frames / fps
        val hBase = GRAVITY * flightTimeS * flightTimeS / 8.0
        val hShift = GRAVITY * (flightTimeS + dt) * (flightTimeS + dt) / 8.0
        return (hShift - hBase) * 100.0
    }

    /**
     * Calcula bandas perturbando UNA condicion cada vez.
     *
     * @param basePoints puntos nominales (ya calculados via `:fvp jumpPoint`).
     * @param flightTimesS tiempos de vuelo nominales [s] en el MISMO orden que [basePoints].
     * @param bodyMassKg masa corporal para normalizacion del fit (m_ref PoC).
     * @param fps fps asumido para las bandas (> 0). SINTETICO salvo que la serie lo mida.
     */
    fun compute(
        basePoints: List<FvpConditionPoint>,
        flightTimesS: List<Double>,
        bodyMassKg: Double,
        fps: Double,
    ): LabBandsResult {
        require(basePoints.size >= 2) { "need >= 2 base points" }
        require(basePoints.size == flightTimesS.size) { "points/flights size mismatch" }
        require(bodyMassKg.isFinite() && bodyMassKg > 0.0) { "bodyMassKg must be > 0" }
        require(fps.isFinite() && fps > 0.0) { "fps must be > 0" }
        flightTimesS.forEach { require(it.isFinite() && it > 0.0) { "flightTime must be > 0" } }

        val nominal = fit(basePoints, bodyMassKg)
        val frameVariants = listOf(1, -1, 2, -2)
        var evaluated = 0
        val f0s = mutableListOf(nominal.f0N)
        val sfvs = mutableListOf(nominal.sfvNsPerM)
        val v0s = mutableListOf<Double>()
        val pmaxs = mutableListOf<Double>()
        val r2s = mutableListOf<Double>()
        if (nominal.r2.isFinite()) r2s += nominal.r2
        if (nominal.v0Mps.isFinite()) v0s += nominal.v0Mps
        if (nominal.pmaxW.isFinite()) pmaxs += nominal.pmaxW

        basePoints.indices.forEach { idx ->
            val base = basePoints[idx]
            val t = flightTimesS[idx]
            frameVariants.forEach { k ->
                val dt = k / fps
                val t2 = t + dt
                if (t2 <= 0.0) return@forEach
                val h2 = GRAVITY * t2 * t2 / 8.0
                // Clamp al rango valido de :fvp (0, 1.2]; si sale de rango se omite la variante.
                if (h2 <= 0.0 || h2 > 1.2) return@forEach
                val r = jumpPoint(base.totalMassKg, h2, base.pushOffM)
                val perturbed = basePoints.toMutableList()
                perturbed[idx] = base.copy(heightM = h2, forceN = r.forceN, velocityMps = r.velocityMps, powerW = r.powerW)
                val p = fit(perturbed, bodyMassKg)
                evaluated++
                if (p.f0N.isFinite()) f0s += p.f0N
                if (p.sfvNsPerM.isFinite()) sfvs += p.sfvNsPerM
                if (p.v0Mps.isFinite()) v0s += p.v0Mps
                if (p.pmaxW.isFinite()) pmaxs += p.pmaxW
                if (p.r2.isFinite()) r2s += p.r2
            }
        }

        fun range(vals: List<Double>): Pair<Double, Double> =
            if (vals.isEmpty()) Double.NaN to Double.NaN else vals.min() to vals.max()

        val (f0Min, f0Max) = range(f0s)
        val (sfvMin, sfvMax) = range(sfvs)
        val (v0Min, v0Max) = range(v0s)
        val (pMin, pMax) = range(pmaxs)
        val r2Min = if (r2s.isEmpty()) Double.NaN else r2s.min()
        return LabBandsResult(
            f0MinN = f0Min,
            f0MaxN = f0Max,
            sfvMinNsPerM = sfvMin,
            sfvMaxNsPerM = sfvMax,
            v0MinMps = v0Min,
            v0MaxMps = v0Max,
            pmaxMinW = pMin,
            pmaxMaxW = pMax,
            r2Min = r2Min,
            r2Nominal = nominal.r2,
            method = "fps=${fps}Hz total-flight ±1f/±2f, perturb-1-condition-at-a-time, " +
                "nCond=${basePoints.size}, nVariants=$evaluated, h=g·t²/8 recomputed",
        )
    }
}
