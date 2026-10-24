package com.openjump.fvp

/**
 * Envolvente de sensibilidad temporal del perfil F-V-P ante el error de marcado del video.
 *
 * API PURA dentro de `:fvp` (sin Android/Room/Media3, sin E/S). Vive junto a [jumpPoint] y [fit]
 * porque es matematica reutilizable, no un adaptador de UI: la app la consume desde su propio
 * adaptador. NO hay fps por defecto ni sintetica: si una condicion no declara fps, no hay
 * envolvente que calcular (ver [sensitivityEnvelope]).
 *
 * HIPOTESIS (detalle completo en el KDoc de [sensitivityEnvelope]): se desplaza ±1 o ±2 periodos
 * de fotograma el vuelo total de UNA condicion cada vez, usando la fps DECLARADA por esa
 * condicion; se recalcula su altura con h = g·t²/8; se recalcula su punto con [jumpPoint] (misma
 * masa total y mismo h_po) y se reajusta la recta con [fit] dejando las demas condiciones en su
 * valor nominal.
 *
 * ESTOS RANGOS SON EL RANGO DE UNA HIPOTESIS BAJO EL SUPUESTO DOCUMENTADO:
 * NO son un intervalo de confianza, NI incertidumbre de medida, NI una cota de peor caso
 * garantizada. Al perturbar una condicion cada vez se ignoran el error de masa, el error de h_po
 * y la correlacion entre condiciones. No acreditan la cadencia real del video ni la validez del
 * perfil: son un envelope de sensibilidad, no un certificado.
 */

/**
 * Version del metodo de sensibilidad temporal; se publica en [FvpSensitivityBand.method] para que
 * el consumidor pueda mostrar que supuestos produjeron los rangos.
 */
const val SENSITIVITY_METHOD_VERSION = "fvp-sensibilidad-temporal-1.0"

/**
 * Motivo por el que una variante de perturbacion temporal NO se evalua. Toda variante descartada
 * se cuenta en [FvpSensitivityBand.skippedByReason]: nunca se descarta en silencio ni se recorta
 * al limite del dominio.
 */
enum class FvpSensitivitySkip {
    /** t' = t_i + k/fps_i <= 0: el vuelo desplazado no es positivo, no hay altura interpretable. */
    NON_POSITIVE_SHIFTED_FLIGHT_TIME,

    /** h' = g·t'²/8 fuera de (0, [MAX_JUMP_HEIGHT_M]]: altura ajena al dominio valido del motor. */
    HEIGHT_OUT_OF_ENGINE_DOMAIN,

    /** El motor rechaza la regresion de la variante (p. ej. todas sus velocidades coinciden). */
    VARIANT_NOT_EVALUABLE,
}

/**
 * Envolvente de sensibilidad temporal (rango de HIPOTESIS) del perfil F-V-P.
 *
 * Cada par min/max es el rango observado sobre el valor nominal y las variantes evaluadas con
 * valores finitos; el nominal se incluye siempre que sea finito (si el perfil nominal es no
 * fisico, V0/Pmax son NaN y esos dos rangos se construyen solo con las variantes finitas,
 * devolviendo NaN si no hay ninguna). Un rango de ancho 0 NO es lo mismo que "no disponible":
 * significa que ninguna variante evaluada movio ese valor (o que no se pudo evaluar ninguna
 * variante: ver [variantsEvaluated] / [variantsSkipped]).
 *
 * NO es un intervalo de confianza, ni incertidumbre de medida, ni cota de peor caso garantizada.
 */
data class FvpSensitivityBand(
    /** Limites del intercepto F0 [N] sobre el nominal y las variantes evaluadas. */
    val f0MinN: Double,
    val f0MaxN: Double,
    /** Limites de la magnitud de la pendiente Sfv [N*s/m]. */
    val sfvMinNsPerM: Double,
    val sfvMaxNsPerM: Double,
    /** Limites de V0 [m/s]; NaN si ningun valor finito estaba disponible. */
    val v0MinMps: Double,
    val v0MaxMps: Double,
    /** Limites de Pmax [W]; NaN si ningun valor finito estaba disponible. */
    val pmaxMinW: Double,
    val pmaxMaxW: Double,
    /** Minimo R² FINITO observado (nominal + variantes); NaN si ninguno era finito. */
    val r2Min: Double,
    /** Valores nominales (sin perturbar) del ajuste de la serie. */
    val f0NominalN: Double,
    val sfvNominalNsPerM: Double,
    val v0NominalMps: Double,
    val pmaxNominalW: Double,
    val r2Nominal: Double,
    /** Variantes realmente evaluadas (variantes intentadas = 4 * condiciones). */
    val variantsEvaluated: Int,
    /** Variantes descartadas; su desglose por motivo esta en [skippedByReason]. */
    val variantsSkipped: Int,
    /** Descarte por motivo, solo motivos con recuento > 0 y en el orden del enum. */
    val skippedByReason: Map<FvpSensitivitySkip, Int>,
    /** Hipotesis aplicada (version, reglas, recuentos y limites). No es una acreditacion. */
    val method: String,
)

/** Periodos de fotograma de la perturbacion de hipotesis: ±1 y ±2 fotogramas. */
private val FRAME_PERIOD_VARIANTS: List<Int> = listOf(1, -1, 2, -2)

/**
 * Altura [m] desde el tiempo de vuelo total [s]: h = g·t²/8, la misma formula del motor para
 * convertir vuelo en altura, con la constante compartida [GRAVITY] (no se re-escribe la gravedad).
 */
private fun heightFromFlightTimeM(flightTimeS: Double): Double = GRAVITY * flightTimeS * flightTimeS / 8.0

/**
 * Envolvente de sensibilidad temporal del perfil F-V-P cuando el vuelo de UNA condicion se
 * desplaza ±1 o ±2 periodos de fotograma de la fps DECLARADA de esa condicion.
 *
 * METODO (misma descripcion que [FvpSensitivityBand.method]):
 * - Para cada condicion `i` y cada `k` en {+1, -1, +2, -2}: `dt = k / fps_i` (fps de la propia
 *   condicion, sin valor por defecto), `t' = t_i + dt`. Si `t' <= 0` la variante se descarta
 *   ([FvpSensitivitySkip.NON_POSITIVE_SHIFTED_FLIGHT_TIME]).
 * - `h' = GRAVITY * t' * t' / 8` ([heightFromFlightTimeM]). Si `h'` cae fuera de
 *   `(0, MAX_JUMP_HEIGHT_M]` la variante se descarta y se cuenta
 *   ([FvpSensitivitySkip.HEIGHT_OUT_OF_ENGINE_DOMAIN]): NUNCA se recorta al limite.
 * - El punto de esa condicion se recalcula con [jumpPoint] usando la MISMA masa total y el MISMO
 *   h_po de la condicion, y la recta se reajusta con [fit] dejando TODAS las demas condiciones en
 *   su valor nominal (una condicion perturbada a la vez, 4 variantes por condicion).
 * - Los rangos acumulan el valor nominal y las variantes evaluadas con ese valor finito; el
 *   nominal se incluye siempre que sea finito. `r2Min` es el minimo R² finito observado.
 *
 * LO QUE ES Y LO QUE NO ES:
 * - Es el rango de una HIPOTESIS bajo el supuesto documentado arriba (error total de vuelo de
 *   ±1/±2 periodos, una condicion cada vez).
 * - NO es un intervalo de confianza, NI incertidumbre de medida, NI una cota de peor caso
 *   garantizada: perturbando una condicion cada vez se ignoran el error de masa, el error de h_po
 *   y la correlacion entre condiciones. No acredita la cadencia real del video.
 *
 * SIN FALLBACK: no existe fps sintetica/por defecto. Una condicion sin fps declarada es entrada
 * insuficiente y esta funcion devuelve `null`.
 *
 * @param conditions condiciones nominales de la serie (>= [MIN_CONDITIONS]).
 * @param flightTimesS tiempo de vuelo total [s] de cada condicion, mismo orden, finito y > 0.
 *   PRECONDICION del llamante: `flightTimesS[i]` tiene que ser el vuelo del que se derivo
 *   `conditions[i].heightM` (misma toma). Si vinieran de otra fuente, cada variante partiria de
 *   una altura base distinta de la nominal y ese desfase se absorbria como si fuera
 *   sensibilidad temporal; esta funcion no puede comprobarlo (el motor no guarda el vuelo).
 * @param fpsPerCondition fps declarada [Hz] de cada condicion, mismo orden, finita y > 0.
 * @param bodyMassKg masa corporal de referencia [kg], finita y > 0.
 * @return envolvente de sensibilidad, o `null` si no hay envolvente que calcular: entradas
 *   insuficientes (menos de [MIN_CONDITIONS] condiciones, tamanos dispares, vuelo/fps/masa no
 *   finitos o no positivos) o NINGUNA variante evaluable. `null` significa siempre "sensibilidad
 *   NO DISPONIBLE" y NO significa "sensibilidad cero". Con un nominal no fisico SI hay banda
 *   (los rangos de V0 y Pmax quedan NaN por falta de valores finitos).
 * @throws FvpInputError si [bodyMassKg], los vuelos o las fps son suficientes pero [conditions]
 *   viola el contrato del motor (altura fuera de dominio, h_po fuera de rango, masa no positiva,
 *   ids duplicados, todas las velocidades coincidentes...), igual que en [evaluate]/[fit]. Las
 *   entradas insuficientes de vuelo/fps/masa NO lanzan: devuelven `null`.
 */
fun sensitivityEnvelope(
    conditions: List<FvpConditionInput>,
    flightTimesS: List<Double>,
    fpsPerCondition: List<Double>,
    bodyMassKg: Double,
): FvpSensitivityBand? {
    if (conditions.size < MIN_CONDITIONS) return null
    if (flightTimesS.size != conditions.size || fpsPerCondition.size != conditions.size) return null
    if (!bodyMassKg.isFinite() || bodyMassKg <= 0.0) return null
    if (flightTimesS.any { !it.isFinite() || it <= 0.0 }) return null
    if (fpsPerCondition.any { !it.isFinite() || it <= 0.0 }) return null

    // Nominal: puntos y ajuste con los helpers del motor. La fps de origen no se pasa a fit()
    // porque cada condicion tiene la suya: no hay una fps unica que declarar.
    val nominalPoints = conditions.map { it.toPoint() }
    val nominal = fit(nominalPoints, bodyMassKg)

    val f0Values = mutableListOf(nominal.f0N)
    val sfvValues = mutableListOf(nominal.sfvNsPerM)
    val v0Values = mutableListOf<Double>()
    val pmaxValues = mutableListOf<Double>()
    val r2Values = mutableListOf<Double>()
    if (nominal.v0Mps.isFinite()) v0Values += nominal.v0Mps
    if (nominal.pmaxW.isFinite()) pmaxValues += nominal.pmaxW
    if (nominal.r2.isFinite()) r2Values += nominal.r2

    val skippedByReason = mutableMapOf<FvpSensitivitySkip, Int>()
    fun skip(reason: FvpSensitivitySkip) {
        skippedByReason[reason] = (skippedByReason[reason] ?: 0) + 1
    }

    var evaluated = 0
    conditions.indices.forEach { index ->
        val base = conditions[index]
        val flightTimeS = flightTimesS[index]
        val fps = fpsPerCondition[index]
        FRAME_PERIOD_VARIANTS.forEach { frames ->
            val shiftedS = flightTimeS + frames / fps
            if (!shiftedS.isFinite() || shiftedS <= 0.0) {
                skip(FvpSensitivitySkip.NON_POSITIVE_SHIFTED_FLIGHT_TIME)
                return@forEach
            }
            val shiftedHeightM = heightFromFlightTimeM(shiftedS)
            if (shiftedHeightM <= 0.0 || shiftedHeightM > MAX_JUMP_HEIGHT_M) {
                skip(FvpSensitivitySkip.HEIGHT_OUT_OF_ENGINE_DOMAIN)
                return@forEach
            }
            val perturbed = nominalPoints.toMutableList()
            perturbed[index] = base.copy(heightM = shiftedHeightM).toPoint()
            val variant = try {
                fit(perturbed, bodyMassKg)
            } catch (failure: FvpInputError) {
                // La variante no define regresion (p. ej. sus velocidades coinciden): se cuenta
                // como descartada. No se propaga como fallo global ni se descarta en silencio.
                skip(FvpSensitivitySkip.VARIANT_NOT_EVALUABLE)
                return@forEach
            }
            evaluated++
            if (variant.f0N.isFinite()) f0Values += variant.f0N
            if (variant.sfvNsPerM.isFinite()) sfvValues += variant.sfvNsPerM
            if (variant.v0Mps.isFinite()) v0Values += variant.v0Mps
            if (variant.pmaxW.isFinite()) pmaxValues += variant.pmaxW
            if (variant.r2.isFinite()) r2Values += variant.r2
        }
    }

    fun rangeOf(values: List<Double>): Pair<Double, Double> =
        if (values.isEmpty()) Double.NaN to Double.NaN else values.min() to values.max()

    // Sin NINGUNA variante evaluable no hay envolvente: devolver una banda de ancho 0 se leeria
    // como 'sensibilidad cero' y seria un falso certificado. Se devuelve null ('no disponible')
    // igual que con entradas insuficientes, para que el consumidor tenga un unico camino de
    // "no disponible" y no pueda interpretar la ausencia de variantes como estabilidad.
    if (evaluated == 0) return null

    val (f0Min, f0Max) = rangeOf(f0Values)
    val (sfvMin, sfvMax) = rangeOf(sfvValues)
    val (v0Min, v0Max) = rangeOf(v0Values)
    val (pmaxMin, pmaxMax) = rangeOf(pmaxValues)
    val r2Min = if (r2Values.isEmpty()) Double.NaN else r2Values.min()
    val variantsSkipped = skippedByReason.values.sum()
    val skippedOrdered = FvpSensitivitySkip.entries
        .mapNotNull { reason -> skippedByReason[reason]?.let { reason to it } }
        .toMap()

    val method = "$SENSITIVITY_METHOD_VERSION: HIPOTESIS (NO intervalo de confianza, NO " +
        "incertidumbre de medida, NO cota de peor caso garantizada): por cada condicion se " +
        "desplaza su vuelo total en k en {+1, -1, +2, -2} periodos de SU fps declarada " +
        "(sin fps por defecto), se recalcula h' = GRAVITY * t'^2 / 8 y el punto con jumpPoint " +
        "(misma masa total, mismo h_po), y se reajusta la recta con las demas condiciones " +
        "nominales (una condicion perturbada a la vez). El nominal se incluye siempre en los " +
        "rangos SIEMPRE QUE SEA FINITO (los rangos de V0 y Pmax quedan NaN si el nominal no es " +
        "fisico o si ninguna variante lo fue); las variantes con t' <= 0 o h' fuera de " +
        "(0, $MAX_JUMP_HEIGHT_M] se descartan y " +
        "se cuentan, nunca se recortan. nCond=${conditions.size}, " +
        "nVariantsAttempted=${conditions.size * FRAME_PERIOD_VARIANTS.size}, " +
        "nVariantsEvaluated=$evaluated, nVariantsSkipped=$variantsSkipped, " +
        "descartes=$skippedOrdered. Limite: perturbar una condicion cada vez ignora el error de " +
        "masa, el error de h_po y la correlacion entre condiciones."

    return FvpSensitivityBand(
        f0MinN = f0Min,
        f0MaxN = f0Max,
        sfvMinNsPerM = sfvMin,
        sfvMaxNsPerM = sfvMax,
        v0MinMps = v0Min,
        v0MaxMps = v0Max,
        pmaxMinW = pmaxMin,
        pmaxMaxW = pmaxMax,
        r2Min = r2Min,
        f0NominalN = nominal.f0N,
        sfvNominalNsPerM = nominal.sfvNsPerM,
        v0NominalMps = nominal.v0Mps,
        pmaxNominalW = nominal.pmaxW,
        r2Nominal = nominal.r2,
        variantsEvaluated = evaluated,
        variantsSkipped = variantsSkipped,
        skippedByReason = skippedOrdered,
        method = method,
    )
}
