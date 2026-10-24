package com.openjump.fvp

import kotlin.math.sqrt

/**
 * Calculadora pura del perfil Fuerza-Velocidad-Potencia (F-V-P) de salto vertical.
 *
 * Metodo simplificado Samozino/Morin (SJ) y extension CMJ (Jimenez-Reyes): cada condicion de
 * carga produce UN punto (V media, F media) estimado desde (m, h, h_po); la regresion OLS sobre
 * esos puntos extrapola F0/V0 y deriva Pmax. Ver docs/research/fvp/03-MATHEMATICAL_SPEC.md.
 *
 * Cero dependencias Android/Room/Media3. Todos los errores bloqueantes lanzan [FvpInputError];
 * las condiciones degradadas se devuelven como [FvpWarning] (nunca excepcion).
 *
 * Etiqueta PENDIENTE-EXCEL: f Formulas (1)-(3),(6)-(9) fijadas desde literatura nucleo +
 * matematica estandar; la hoja Excel original no fue contrastada. NO se calculan FVimb,
 * pendiente optima ni prescripcion de entrenamiento (NO_RECOMENDADA en Fase A).
 */

/** Gravedad [m/s^2]. Fijada a 9.80665 por coherencia con JumpMath.GRAVITY (repo app). */
const val GRAVITY = 9.80665

/** Version del algoritmo PoC, registrada en cada evaluacion. */
const val ALGORITHM_VERSION = "fvp-poc-1.0"

/** Cota superior de altura de salto aceptada [m]. Por encima: error (probable artefacto). */
const val MAX_JUMP_HEIGHT_M = 1.2

/** Cotas de distancia de impulsion h_po [m]: valido en (MIN, MAX]. */
const val MIN_PUSH_OFF_M = 0.05
const val MAX_PUSH_OFF_M = 0.8

/** Minimo de condiciones para ajustar una recta (n = 2: solo diagnostico EXPERIMENTAL). */
const val MIN_CONDITIONS = 2

/** Recomendado para un perfil publicable (por debajo: warning FEW_POINTS). */
const val RECOMMENDED_MIN_CONDITIONS = 4

/** Rango minimo de velocidad medida [m/s] para una pendiente estable. */
const val MIN_VELOCITY_RANGE_MPS = 0.15

/** R^2 minimo para considerar el ajuste lineal bueno. */
const val MIN_R2 = 0.90

/** Factor maximo de extrapolacion V0/maxV o F0/maxF antes del warning HIGH_EXTRAPOLATION. */
const val MAX_EXTRAPOLATION_FACTOR = 1.5

/**
 * Umbral FPS sugerido para el gate de calidad de video [fps].
 * Por debajo se emite FPS_30_BLOCKED (30 fps es inviable: +/-1 frame ~= +/-4 cm en h=0.30 m);
 * 60 fps es CONDICIONAL; >= 120 fps es el requisito por defecto. Ver spec 03 seccion 5.2.
 * El PoC solo ADVIERTE (warning); el bloqueo en UI es decision de producto futura.
 */
const val FPS_BLOCK_THRESHOLD = 45.0

/** Error bloqueante de entrada del modulo F-V-P (analogo a JumpMath.InvalidTimes en app). */
class FvpInputError(message: String) : IllegalArgumentException(message)

/** Avisos no bloqueantes del perfil. La presencia de warnings no invalida el calculo. */
enum class FvpWarning {
    /** n < 4: recta calculada pero solo diagnostica, no publicable. */
    FEW_POINTS,

    /** Rango de velocidad medida < 0.15 m/s: pendiente inestable aunque R^2 sea alto. */
    NARROW_VELOCITY_RANGE,

    /** R^2 < 0.90: el ajuste lineal no representa bien los puntos medidos. */
    LOW_R2,

    /**
     * Perfil no fisico: pendiente >= 0 (caso NON_PHYSICAL_SLOPE: Sfv <= 0) o F0 <= 0 o V0 <= 0.
     * V0/Pmax se devuelven como NaN en este caso.
     */
    NON_PHYSICAL,

    /** V0/maxV > 1.5 o F0/maxF > 1.5: los interceptos viven lejos del rango medido. */
    HIGH_EXTRAPOLATION,

    /**
     * Gate de calidad de video SUGERIDO: FPS efectivos bajo el umbral (~30 fps).
     * A 30 fps un fotograma mueve h ~= +/-4 cm (salto 30 cm): serie no apta para F-V.
     */
    FPS_30_BLOCKED,
}

/** Punto (F, V, P) de una condicion. Unidades: [N], [m/s], [W]. */
data class FvpPointResult(
    val forceN: Double,
    val velocityMps: Double,
    val powerW: Double,
)

/** Perfil F-V ajustado. F0/V0 son EXTRAPOLACIONES teoricas, no medidas. Unidades indicadas. */
data class FvpProfile(
    /** Fuerza a velocidad nula (intercepto) [N]. */
    val f0N: Double,
    /** Pendiente dF/dV de la recta (negativa en perfiles fisicos) [N*s/m]. */
    val slopeFv: Double,
    /** Magnitud positiva de la pendiente Sfv = -slope [N*s/m]. */
    val sfvNsPerM: Double,
    /** Velocidad a fuerza nula (intercepto) [m/s]; NaN si no fisico. */
    val v0Mps: Double,
    /** Potencia maxima del modelo parabolico F0*V0/4 [W]; NaN si no fisico. */
    val pmaxW: Double,
    /** Bondad del ajuste 1-SSE/SST [adimensional]; NaN si varianza de F == 0. */
    val r2: Double,
    /** Numero de condiciones ajustadas. */
    val n: Int,
    /** Rango medido de velocidad [m/s]; publicar siempre junto a F0/V0. */
    val vMinMps: Double,
    val vMaxMps: Double,
    /** Rango medido de fuerza [N]. */
    val fMinN: Double,
    val fMaxN: Double,
    /** F0 normalizada por masa corporal [N/kg]. m_ref = masa en vacio (convencion PoC). */
    val f0RelNPerKg: Double,
    /** Pmax normalizada por masa corporal [W/kg]. */
    val pmaxRelWPerKg: Double,
    /** Avisos no bloqueantes (lista; vacia = serie nominal). */
    val warnings: List<FvpWarning>,
)

/** Resultado completo de evaluar una serie: puntos + perfil + trazabilidad. */
data class FvpResult(
    val evaluationId: String,
    val protocol: FvpProtocol,
    val algorithmVersion: String,
    val points: List<FvpConditionPoint>,
    val profile: FvpProfile,
)

/**
 * Punto (F, V, P) de una condicion desde (m, h, h_po). Spec 03 (1)-(3):
 *   F [N]   = m * g * (h / h_po + 1)
 *   V [m/s] = sqrt(g * h / 2)  (= v_despegue / 2)
 *   P [W]   = F * V
 * Cuando h -> 0, F -> m*g (control fisico: sostiene el peso).
 *
 * @throws FvpInputError si algun parametro no es finito o viola rangos
 *   (m > 0; h en (0, 1.2]; h_po en (0.05, 0.8]).
 */
fun jumpPoint(massKg: Double, heightM: Double, pushOffM: Double): FvpPointResult {
    requireFinite(massKg, "massKg")
    requireFinite(heightM, "heightM")
    requireFinite(pushOffM, "pushOffM")
    if (massKg <= 0.0) throw FvpInputError("massKg must be > 0, was $massKg")
    if (heightM <= 0.0 || heightM > MAX_JUMP_HEIGHT_M) {
        throw FvpInputError("heightM must be in (0, $MAX_JUMP_HEIGHT_M], was $heightM")
    }
    if (pushOffM <= MIN_PUSH_OFF_M || pushOffM > MAX_PUSH_OFF_M) {
        throw FvpInputError("pushOffM must be in ($MIN_PUSH_OFF_M, $MAX_PUSH_OFF_M], was $pushOffM")
    }
    val force = massKg * GRAVITY * (heightM / pushOffM + 1.0)
    val velocity = sqrt(GRAVITY * heightM / 2.0)
    return FvpPointResult(forceN = force, velocityMps = velocity, powerW = force * velocity)
}

/**
 * Ajusta la recta F(V) = F0 - Sfv*V por minimos cuadrados ordinarios sobre puntos ya
 * calculados. Spec 03 (6)-(9).
 *
 * @param sourceFps FPS efectivos del video (opcional). Solo alimenta el warning FPS_30_BLOCKED.
 * @throws FvpInputError si n < 2, hay conditionId duplicados, algun campo no es finito o viola
 *   rangos, bodyMassKg no es finita/> 0, o todas las velocidades son identicas (pendiente
 *   indefinida: cargas indistinguibles).
 */
fun fit(
    points: List<FvpConditionPoint>,
    bodyMassKg: Double,
    sourceFps: Double? = null,
): FvpProfile {
    requireFinite(bodyMassKg, "bodyMassKg")
    if (bodyMassKg <= 0.0) throw FvpInputError("bodyMassKg must be > 0, was $bodyMassKg")
    if (points.size < MIN_CONDITIONS) {
        throw FvpInputError("need at least $MIN_CONDITIONS conditions, was ${points.size}")
    }
    val ids = points.map { it.conditionId }
    if (ids.any { it.isBlank() }) throw FvpInputError("conditionId must not be blank")
    if (ids.toSet().size != ids.size) throw FvpInputError("duplicate conditionIds: $ids")
    points.forEach {
        requireFinite(it.totalMassKg, "totalMassKg(${it.conditionId})")
        requireFinite(it.heightM, "heightM(${it.conditionId})")
        requireFinite(it.pushOffM, "pushOffM(${it.conditionId})")
        requireFinite(it.forceN, "forceN(${it.conditionId})")
        requireFinite(it.velocityMps, "velocityMps(${it.conditionId})")
        requireFinite(it.powerW, "powerW(${it.conditionId})")
        if (it.totalMassKg <= 0.0) throw FvpInputError("totalMassKg must be > 0 (${it.conditionId})")
        if (it.heightM <= 0.0 || it.heightM > MAX_JUMP_HEIGHT_M) {
            throw FvpInputError("heightM must be in (0, $MAX_JUMP_HEIGHT_M] (${it.conditionId})")
        }
        if (it.pushOffM <= MIN_PUSH_OFF_M || it.pushOffM > MAX_PUSH_OFF_M) {
            throw FvpInputError("pushOffM must be in ($MIN_PUSH_OFF_M, $MAX_PUSH_OFF_M] (${it.conditionId})")
        }
        if (it.forceN < 0.0 || it.velocityMps < 0.0) {
            throw FvpInputError("force/velocity must be >= 0 (${it.conditionId})")
        }
    }
    sourceFps?.let {
        requireFinite(it, "sourceFps")
        if (it <= 0.0) throw FvpInputError("sourceFps must be > 0, was $it")
    }

    val n = points.size
    val meanV = points.sumOf { it.velocityMps } / n
    val meanF = points.sumOf { it.forceN } / n
    var sxx = 0.0
    var sxy = 0.0
    points.forEach {
        val dv = it.velocityMps - meanV
        sxx += dv * dv
        sxy += dv * (it.forceN - meanF)
    }
    if (sxx == 0.0) {
        throw FvpInputError("all measured velocities are identical: slope undefined")
    }
    val slope = sxy / sxx // dF/dV; fisica: negativa.
    val sfv = -slope
    val f0 = meanF + sfv * meanV // == meanF - slope*meanV (intercepto V=0).

    val physical = sfv > 0.0 && f0 > 0.0
    val v0 = if (physical) f0 / sfv else Double.NaN
    val pmax = if (physical) f0 * v0 / 4.0 else Double.NaN

    var sse = 0.0
    var sst = 0.0
    points.forEach {
        val predicted = f0 - sfv * it.velocityMps
        val resid = it.forceN - predicted
        sse += resid * resid
        val dev = it.forceN - meanF
        sst += dev * dev
    }
    val r2 = if (sst == 0.0) Double.NaN else 1.0 - sse / sst

    val vMin = points.minOf { it.velocityMps }
    val vMax = points.maxOf { it.velocityMps }
    val fMin = points.minOf { it.forceN }
    val fMax = points.maxOf { it.forceN }

    val warnings = mutableListOf<FvpWarning>()
    if (n < RECOMMENDED_MIN_CONDITIONS) warnings += FvpWarning.FEW_POINTS
    if (vMax - vMin < MIN_VELOCITY_RANGE_MPS) warnings += FvpWarning.NARROW_VELOCITY_RANGE
    if (r2.isFinite() && r2 < MIN_R2) warnings += FvpWarning.LOW_R2
    if (!physical || v0.isNaN() || v0 <= 0.0) warnings += FvpWarning.NON_PHYSICAL
    if (physical && v0.isFinite() && f0.isFinite() && vMax > 0.0 && fMax > 0.0 &&
        (v0 / vMax > MAX_EXTRAPOLATION_FACTOR || f0 / fMax > MAX_EXTRAPOLATION_FACTOR)
    ) {
        warnings += FvpWarning.HIGH_EXTRAPOLATION
    }
    if (sourceFps != null && sourceFps < FPS_BLOCK_THRESHOLD) warnings += FvpWarning.FPS_30_BLOCKED

    return FvpProfile(
        f0N = f0,
        slopeFv = slope,
        sfvNsPerM = sfv,
        v0Mps = v0,
        pmaxW = pmax,
        r2 = r2,
        n = n,
        vMinMps = vMin,
        vMaxMps = vMax,
        fMinN = fMin,
        fMaxN = fMax,
        f0RelNPerKg = f0 / bodyMassKg,
        pmaxRelWPerKg = pmax / bodyMassKg,
        warnings = warnings,
    )
}

/**
 * Evalua condiciones de entrada (calcula puntos y ajusta). Equivalente a mapear
 * [FvpConditionInput.toPoint] + [fit].
 */
fun evaluate(
    conditions: List<FvpConditionInput>,
    bodyMassKg: Double,
    sourceFps: Double? = null,
): FvpResult = evaluatePoints(conditions.map { it.toPoint() }, bodyMassKg, sourceFps, null)

/**
 * Evalua una serie completa [FvpEvaluation] (sus puntos ya vienen calculados).
 * Valida que la masa corporal coincida en signo con los puntos (solo positividad; la
 * normalizacion usa bodyMassKg como m_ref documentada) y que el id no sea vacio.
 */
fun evaluate(
    evaluation: FvpEvaluation,
    sourceFps: Double? = null,
): FvpResult {
    if (evaluation.id.isBlank()) throw FvpInputError("evaluation id must not be blank")
    return evaluatePoints(
        evaluation.conditions,
        evaluation.bodyMassKg,
        sourceFps,
        evaluation,
    )
}

private fun evaluatePoints(
    points: List<FvpConditionPoint>,
    bodyMassKg: Double,
    sourceFps: Double?,
    evaluation: FvpEvaluation?,
): FvpResult {
    val profile = fit(points, bodyMassKg, sourceFps)
    return FvpResult(
        evaluationId = evaluation?.id ?: "poc-direct",
        protocol = evaluation?.protocol ?: FvpProtocol.SJ,
        algorithmVersion = evaluation?.algorithmVersion ?: ALGORITHM_VERSION,
        points = points,
        profile = profile,
    )
}
