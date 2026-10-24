package com.openjump.fvp

/**
 * Modelos de entrada del PoC del perfil Fuerza-Velocidad-Potencia (F-V-P) de salto vertical.
 *
 * Alcance Fase C+D (PoC JVM-only): tipos por valor sin ninguna dependencia Android/Room/Media3.
 * Solo imports de la stdlib de Kotlin. Las unidades internas son SI (kg, m, s).
 *
 * Referencias: docs/research/fvp/03-MATHEMATICAL_SPEC.md (spec), 01-SCIENTIFIC_RESEARCH.md (evidencia).
 * Etiqueta PENDIENTE-EXCEL: las convenciones de normalizacion (masa corporal en vacio como
 * referencia) se fijan desde literatura nucleo + matematica estandar; la hoja Excel original
 * Samozino-Morin (ResearchGate 320146284) no pudo contrastarse. Ver 01, seccion 8.4.
 */

/** Protocolo de salto de la serie F-V. SJ y CMJ NUNCA se mezclan en la misma regresion. */
enum class FvpProtocol {
    /** Squat jump: canonico para F-V (partida estatica ~90 grados, sin contramovimiento). */
    SJ,

    /** Countermovement jump: variante ecologica; mismo motor, etiqueta separada. */
    CMJ,
}

/** Metodo con el que se obtuvo la altura de salto de un intento. */
enum class FvpMeasureMethod {
    /** Altura via tiempo de vuelo medido en video (h = g*t^2/8). */
    FLIGHT_TIME_VIDEO,

    /** Altura introducida manualmente (cinta, plataforma externa transcrita, etc.). */
    MANUAL,

    /** Altura importada de otra fuente (documentar en provenance). */
    IMPORTED,
}

/**
 * Un intento individual de salto dentro de una condicion de carga.
 *
 * @param conditionId identificador estable de la condicion de carga (p. ej. "load-0kg").
 * @param attemptId identificador unico del intento dentro de la condicion.
 * @param bodyMassKg masa corporal del atleta [kg], > 0.
 * @param addedLoadKg carga adicional de la condicion [kg], >= 0.
 * @param jumpHeightM altura de salto del CdM [m], (0, 1.2].
 * @param pushOffM distancia vertical de impulsion h_po [m], (0.05, 0.8].
 * @param flightTimeS tiempo de vuelo [s], opcional, > 0 si se informa.
 * @param sourceFps FPS efectivos del video origen, opcional, > 0 si se informa.
 * @param method metodo de medida de la altura.
 */
data class FvpAttempt(
    val conditionId: String,
    val attemptId: String,
    val bodyMassKg: Double,
    val addedLoadKg: Double,
    val jumpHeightM: Double,
    val pushOffM: Double,
    val flightTimeS: Double? = null,
    val sourceFps: Double? = null,
    val method: FvpMeasureMethod = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
)

/**
 * Entrada agregada por condicion de carga (tras seleccion de intentos).
 *
 * @param conditionId identificador estable de la condicion.
 * @param totalMassKg masa del sistema = corporal + carga [kg], > 0.
 * @param heightM altura de salto seleccionada [m], (0, 1.2].
 * @param pushOffM distancia de impulsion [m], (0.05, 0.8].
 */
data class FvpConditionInput(
    val conditionId: String,
    val totalMassKg: Double,
    val heightM: Double,
    val pushOffM: Double,
)

/**
 * Punto (V, F) medido/estimado por condicion, con sus valores derivados.
 * F, V, P se estiman desde (m, h, h_po) segun spec 03 seccion 2.1; NO son medidas directas.
 */
data class FvpConditionPoint(
    val conditionId: String,
    val totalMassKg: Double,
    val heightM: Double,
    val pushOffM: Double,
    val forceN: Double,
    val velocityMps: Double,
    val powerW: Double,
)

/**
 * Serie F-V completa lista para regresion.
 *
 * @param algorithmVersion version del algoritmo; fijada a "fvp-poc-1.0" en el PoC.
 * @param provenance descripcion libre del origen (operador, fecha, dispositivo, hito h_po...).
 */
data class FvpEvaluation(
    val id: String,
    val protocol: FvpProtocol,
    val bodyMassKg: Double,
    val conditions: List<FvpConditionPoint>,
    val algorithmVersion: String = ALGORITHM_VERSION,
    val provenance: String = "unspecified",
)

/** Convierte una entrada por condicion en punto (V, F, P) calculado. Valida rangos. */
fun FvpConditionInput.toPoint(): FvpConditionPoint {
    val r = jumpPoint(totalMassKg, heightM, pushOffM)
    return FvpConditionPoint(
        conditionId = conditionId,
        totalMassKg = totalMassKg,
        heightM = heightM,
        pushOffM = pushOffM,
        forceN = r.forceN,
        velocityMps = r.velocityMps,
        powerW = r.powerW,
    )
}

internal fun requireFinite(value: Double, name: String): Double {
    if (!value.isFinite()) throw FvpInputError("$name must be finite, was $value")
    return value
}

internal fun validateAttemptFields(a: FvpAttempt) {
    if (a.conditionId.isBlank()) throw FvpInputError("conditionId must not be blank")
    if (a.attemptId.isBlank()) throw FvpInputError("attemptId must not be blank")
    requireFinite(a.bodyMassKg, "bodyMassKg")
    requireFinite(a.addedLoadKg, "addedLoadKg")
    requireFinite(a.jumpHeightM, "jumpHeightM")
    requireFinite(a.pushOffM, "pushOffM")
    if (a.bodyMassKg <= 0.0) throw FvpInputError("bodyMassKg must be > 0, was ${a.bodyMassKg}")
    if (a.addedLoadKg < 0.0) throw FvpInputError("addedLoadKg must be >= 0, was ${a.addedLoadKg}")
    if (a.jumpHeightM <= 0.0 || a.jumpHeightM > MAX_JUMP_HEIGHT_M) {
        throw FvpInputError("jumpHeightM must be in (0, $MAX_JUMP_HEIGHT_M], was ${a.jumpHeightM}")
    }
    if (a.pushOffM <= MIN_PUSH_OFF_M || a.pushOffM > MAX_PUSH_OFF_M) {
        throw FvpInputError("pushOffM must be in ($MIN_PUSH_OFF_M, $MAX_PUSH_OFF_M], was ${a.pushOffM}")
    }
    a.flightTimeS?.let {
        requireFinite(it, "flightTimeS")
        if (it <= 0.0) throw FvpInputError("flightTimeS must be > 0, was $it")
    }
    a.sourceFps?.let {
        requireFinite(it, "sourceFps")
        if (it <= 0.0) throw FvpInputError("sourceFps must be > 0, was $it")
    }
}

/**
 * Seleccion de intentos: elige, por cada condicion, el intento con mayor [FvpAttempt.jumpHeightM].
 *
 * Funcion pura (sin estado, sin E/S), determinista: ordena la salida por conditionId y ante
 * empates conserva el primer intento en orden de entrada. Valida cada intento (ver
 * [validateAttemptFields]) y lanza [FvpInputError] ante cualquier intento invalido.
 * La lista vacia devuelve lista vacia (no es error: no hay nada que seleccionar).
 *
 * La masa total de la condicion es bodyMassKg + addedLoadKg del intento ganador.
 */
fun selectBestPerCondition(attempts: List<FvpAttempt>): List<FvpConditionInput> {
    attempts.forEach(::validateAttemptFields)
    return attempts
        .groupBy { it.conditionId }
        .toSortedMap()
        .map { (conditionId, group) ->
            // maxBy devuelve el PRIMER maximo ante empates: criterio estable y documentado.
            val best = group.maxBy { it.jumpHeightM }
            FvpConditionInput(
                conditionId = conditionId,
                totalMassKg = best.bodyMassKg + best.addedLoadKg,
                heightM = best.jumpHeightM,
                pushOffM = best.pushOffM,
            )
        }
}
