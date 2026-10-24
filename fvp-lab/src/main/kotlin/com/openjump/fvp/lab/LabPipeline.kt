package com.openjump.fvp.lab

import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpProfile
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpWarning
import com.openjump.fvp.GRAVITY
import com.openjump.fvp.fit
import com.openjump.fvp.jumpPoint

/**
 * Fila de acuerdo vuelo-vs-plataforma (SIN fitting): compara por trial la altura balistica
 * (vuelo PTS reales, h=g·t²/8) con la altura de instrumento independiente (display de
 * plataforma/alfombra transcrito en el catalogo). Ambas en cm.
 */
data class AgreementRow(
    val trialKey: String,
    val ballisticCm: Double,
    val instrumentCm: Double,
    val diffCm: Double,
)

/** Resultado de acuerdo: tabla por trial + media absoluta (SIN regresion F-V). */
data class AgreementResult(
    val rows: List<AgreementRow>,
    val meanAbsCm: Double,
    val maxAbsCm: Double,
)

/**
 * Resultado versionado del pipeline de laboratorio para UNA serie con fitting.
 *
 * - [points]: puntos (F,V,P) via `:fvp jumpPoint` (altura REAL por PTS, masa/h_po SINTETICOS).
 * - [profile]: regresion `:fvp fit` (advertencias de `:fvp` incluidas).
 * - [bands]: bandas FPS [LabBands] (hipotesis ±1f/±2f documentada en el metodo).
 * - [gate]: gate FPS sobre el minimo sourceFps declarado (null = UNKNOWN si no hay fps).
 * - [warnings]: union de warnings `:fvp` (el pipeline no inventa warnings propios).
 */
data class LabResult(
    val seriesId: String,
    val protocol: FvpProtocol,
    val algorithmVersion: String,
    val points: List<FvpConditionPoint>,
    val profile: FvpProfile,
    val bands: LabBandsResult,
    val gate: FpsGateDecision?,
    val warnings: List<FvpWarning>,
    val flightTimesS: List<Double>,
)

/**
 * Pipeline adapter -> `:fvp` (JVM-only). Funciones puras salvo lectura de mapas de entrada.
 *
 * CERO imports `com.openjump.app` / `androidx` / `room`. Solo `:fvp` + stdlib + JSON ya
 * parseado por [LabGoldenAdapter]/[LabSeriesParser].
 */
object LabPipeline {
    const val ALGORITHM_VERSION = "fvp-lab-1.0"

    /** fps asumido para bandas cuando ningun sourceFps viene declarado (SINTETICO, documentado). */
    const val DEFAULT_BAND_FPS = 60.0

    /**
     * Ejecuta una serie con fitting (S1/S2). La altura de cada condicion se RECOMPUTA desde
     * PTS reales (landing-takeoff) con h=g·t²/8 usando [GRAVITY] de `:fvp` (no se reutiliza
     * a ciegas el `ballistic_expected` cacheado; el test de pipeline verifica la coincidencia
     * dentro de 1e-9 m).
     *
     * @param bandFps fps para bandas; por defecto el minimo sourceFps declarado, o
     *   [DEFAULT_BAND_FPS] si no hay ninguno (documentado como SINTETICO en el metodo).
     */
    fun run(
        series: LabSeriesDef,
        goldenByKey: Map<String, GoldenTrial>,
        bandFps: Double? = null,
    ): LabResult {
        require(series.conditions.isNotEmpty()) { "series ${series.id} has no conditions" }
        val flights = mutableListOf<Double>()
        val points = series.conditions.map { cond ->
            val trial = goldenByKey[cond.trialKey]
                ?: throw IllegalArgumentException("series ${series.id}: unknown trialKey '${cond.trialKey}'")
            // Protocolo del trial debe coincidir con el de la serie (prohibida la mezcla SJ+CMJ).
            if (trial.protocol != series.protocol.name) {
                throw IllegalArgumentException(
                    "series ${series.id}: trial ${cond.trialKey} protocol ${trial.protocol} " +
                        "!= series protocol ${series.protocol}",
                )
            }
            val t = (trial.landingUs - trial.takeoffUs) / 1_000_000.0
            if (!t.isFinite() || t <= 0.0) {
                throw IllegalArgumentException("series ${series.id}: invalid flight time $t for ${cond.trialKey}")
            }
            flights += t
            val h = GRAVITY * t * t / 8.0
            val r = jumpPoint(cond.totalMassKg, h, cond.pushOffM)
            FvpConditionPoint(
                conditionId = cond.trialKey,
                totalMassKg = cond.totalMassKg,
                heightM = h,
                pushOffM = cond.pushOffM,
                forceN = r.forceN,
                velocityMps = r.velocityMps,
                powerW = r.powerW,
            )
        }
        // Gate sobre el minimo fps declarado (peor caso); null si ninguno declarado.
        val declaredFps = series.conditions.mapNotNull { it.sourceFps }.minOrNull()
        val gate = FpsGate.evaluateOrNull(declaredFps)
        val effectiveBandFps = bandFps ?: declaredFps ?: DEFAULT_BAND_FPS
        if (!effectiveBandFps.isFinite() || effectiveBandFps <= 0.0) {
            throw IllegalArgumentException("invalid bandFps $effectiveBandFps")
        }
        // Warnings de :fvp con el fps declarado minimo como sourceFps (solo alimenta FPS_30_BLOCKED).
        val profile = fit(points, series.bodyMassKg, declaredFps)
        val bands = LabBands.compute(points, flights, series.bodyMassKg, effectiveBandFps)
        return LabResult(
            seriesId = series.id,
            protocol = series.protocol,
            algorithmVersion = ALGORITHM_VERSION,
            points = points,
            profile = profile,
            bands = bands,
            gate = gate,
            warnings = profile.warnings,
            flightTimesS = flights,
        )
    }

    /** Acuerdo vuelo-vs-plataforma SIN fitting (S3): tabla + media/max absolutos. */
    fun agreement(trials: List<GoldenTrial>): AgreementResult {
        require(trials.isNotEmpty()) { "agreement needs >= 1 trial" }
        val rows = trials.map { t ->
            val diff = t.ballisticHcm - t.instrumentHcm
            AgreementRow(
                trialKey = t.trialKey,
                ballisticCm = t.ballisticHcm,
                instrumentCm = t.instrumentHcm,
                diffCm = diff,
            )
        }
        val meanAbs = rows.sumOf { kotlin.math.abs(it.diffCm) } / rows.size
        val maxAbs = rows.maxOf { kotlin.math.abs(it.diffCm) }
        return AgreementResult(rows = rows, meanAbsCm = meanAbs, maxAbsCm = maxAbs)
    }
}
