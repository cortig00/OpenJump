package com.openjump.app.data

import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpEligibilityFlag
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpPushOffMode
import com.openjump.app.fvp.FvpSeriesInput
import com.openjump.app.fvp.checkSeries
import com.openjump.app.fvp.selectedConditionInputs
import com.openjump.app.fvp.selectedValidAttemptIndex
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.fvp.FvpConditionInput
import com.openjump.fvp.FvpConditionPoint
import com.openjump.fvp.FvpProtocol
import com.openjump.fvp.FvpResult
import com.openjump.fvp.FvpWarning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.openjump.app.fvp.FvpAttemptDraft as FvpEligibilityAttemptDraft
import com.openjump.app.fvp.FvpConditionDraft as FvpEligibilityConditionDraft

/** Estado del snapshot auditado de una fila persistida frente al recalculado con el motor actual. */
enum class FvpSnapshotStatus {
    /**
     * El snapshot persistido coincide exactamente con el recálculo del motor: MISMO
     * `algorithmVersion` y MISMO `methodVersion` y números iguales.
     */
    MATCHES,

    /**
     * Mismo `algorithmVersion` y mismo `methodVersion`, pero los números persistidos no coinciden
     * con el recálculo: corrupción o deriva del dato, nunca una diferencia de versión de política.
     */
    MISMATCH,

    /**
     * `algorithmVersion` distinto: el snapshot no es comparable y no se declara coincidencia
     * (manda sobre cualquier diferencia de `methodVersion`).
     */
    STALE,

    /**
     * Mismo `algorithmVersion` pero `methodVersion` distinto: el snapshot lo escribió otra
     * versión de la app/política (p. ej. otra regla de selección o de avisos) y sus números no son
     * comparables con el recálculo actual. No es corrupción.
     */
    STALE_METHOD,

    /**
     * La fila almacenada no se puede recalcular con la versión actual: alguna clave persistida
     * (protocolo, modo de h_po, método de medida) es desconocida o la serie no es computable hoy.
     * El snapshot sigue siendo legible y se presenta tal cual, pero no hay recálculo con el que
     * compararlo; NUNCA se declara coincidencia ni se inventa un resultado.
     */
    RECALCULATION_UNAVAILABLE,
}

/**
 * Evaluación F-V-P persistida + su recálculo con `FvpEngine` (nunca aritmética local).
 *
 * [eligibility] y [result] son `null` cuando la fila almacenada no se puede recalcular con la
 * versión actual (clave persistida desconocida o serie no computable hoy): el snapshot de
 * [assessment] sigue siendo presentable, pero no hay veredicto ni números recalculados.
 *
 * @param snapshotStatus resultado de comparar el snapshot persistido con el recálculo.
 */
data class StoredFvpAssessment(
    val assessment: FvpAssessmentEntity,
    val conditions: List<FvpConditionEntity>,
    val attempts: List<FvpAttemptEntity>,
    val eligibility: FvpEligibility?,
    val result: FvpResult?,
    val snapshotStatus: FvpSnapshotStatus,
) {
    /** Solo una serie con `profileEligible` es un perfil normal publicable; sin elegibilidad, no. */
    val profileEligible: Boolean get() = eligibility?.profileEligible == true

    /**
     * `true` coincide, `false` no coincide, `null` si no es comparable: cualquier estado que no
     * sea [FvpSnapshotStatus.MATCHES] o [FvpSnapshotStatus.MISMATCH].
     */
    val snapshotMatches: Boolean?
        get() = when (snapshotStatus) {
            FvpSnapshotStatus.MATCHES -> true
            FvpSnapshotStatus.MISMATCH -> false
            FvpSnapshotStatus.STALE,
            FvpSnapshotStatus.STALE_METHOD,
            FvpSnapshotStatus.RECALCULATION_UNAVAILABLE,
            -> null
        }

    /** Warnings del motor + flags de elegibilidad persistidos, en su orden determinista. */
    val warnings: List<String> get() = parseWarningsCsv(assessment.warningsCsv)
}

/**
 * Decisión PURA del estado del snapshot persistido frente al recálculo actual. No toca el motor
 * ni la base de datos: es la ÚNICA definición de la regla y se puede testear con entradas
 * literales.
 *
 * Precedencia (de mayor a menor):
 * 1. `recalculatedAlgorithmVersion == null` (no hay recálculo) ->
 *    [FvpSnapshotStatus.RECALCULATION_UNAVAILABLE].
 * 2. `storedAlgorithmVersion != recalculatedAlgorithmVersion` -> [FvpSnapshotStatus.STALE]
 *    (aunque `methodVersion` también difiera: manda el algoritmo).
 * 3. `storedMethodVersion != currentMethodVersion` -> [FvpSnapshotStatus.STALE_METHOD].
 * 4. `numbersMatch` -> [FvpSnapshotStatus.MATCHES]; en otro caso
 *    [FvpSnapshotStatus.MISMATCH].
 *
 * @param recalculatedAlgorithmVersion versión del motor recalculado; `null` si no hubo recálculo.
 * @param currentMethodVersion versión de política de la app actual (la que produciría un guardado
 *   nuevo).
 * @param numbersMatch comparación de TODOS los números persistidos con los recalculados; su valor
 *   solo se usa cuando hay recálculo y las dos versiones coinciden.
 */
internal fun fvpSnapshotStatusFor(
    storedAlgorithmVersion: String,
    recalculatedAlgorithmVersion: String?,
    storedMethodVersion: String,
    currentMethodVersion: String,
    numbersMatch: Boolean,
): FvpSnapshotStatus = when {
    recalculatedAlgorithmVersion == null -> FvpSnapshotStatus.RECALCULATION_UNAVAILABLE
    storedAlgorithmVersion != recalculatedAlgorithmVersion -> FvpSnapshotStatus.STALE
    storedMethodVersion != currentMethodVersion -> FvpSnapshotStatus.STALE_METHOD
    numbersMatch -> FvpSnapshotStatus.MATCHES
    else -> FvpSnapshotStatus.MISMATCH
}

/**
 * Identidad PERSISTIDA de una serie leída por su `sessionKey` (D6/R3): la fila y su dueño, y nada
 * más. Es lo único que la reconciliación de un borrador recuperado necesita de la base.
 *
 * @param rowId `fvp_assessments.id` de la fila que YA ocupa ese `sessionKey`.
 * @param athleteId `fvp_assessments.athleteId` TAL CUAL: `null` es dueño DESCONOCIDO, nunca "el
 *   mismo atleta" (comparar con `null` no puede declarar coincidencia).
 */
data class FvpPersistedSession(
    val rowId: Long,
    val athleteId: Long?,
)

/** Proyección ligera (sin hijos) para listas/historial, ordenada por `dateTime DESC, id DESC`. */
data class FvpAssessmentSummary(
    val id: Long,
    val sessionKey: String,
    val athleteId: Long?,
    val protocolId: String,
    val dateTime: Long,
    val bodyMassKg: Double,
    val conditionCount: Int,
    val f0N: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val pmaxRelWPerKg: Double?,
    val f0RelNPerKg: Double?,
    val r2: Double?,
    val algorithmVersion: String,
    val methodVersion: String,
    val warnings: List<String>,
    val hasNotes: Boolean,
)

/**
 * Resultado del camino ÚNICO de evaluación de una serie F-V-P (cálculo en vivo y persistencia).
 *
 * @param eligibility veredicto de la capa de elegibilidad ([checkSeries]).
 * @param inputs entradas ya seleccionadas para el motor; vacío si la serie está bloqueada.
 * @param result puntos + perfil del motor, o `null` si la serie está bloqueada o el motor rechazó
 *   la entrada (nunca un valor por defecto silencioso).
 * @param engineFailure excepción del motor cuando la serie era calculable pero el motor la rechazó.
 *   El cálculo en vivo la trata como "sin resultado"; quien persiste la relanza tal cual para no
 *   cambiar el contrato de `FvpRepository.save`.
 */
internal data class FvpEvaluationOutcome(
    val eligibility: FvpEligibility,
    val inputs: List<FvpConditionInput>,
    val result: FvpResult?,
    val engineFailure: Throwable? = null,
) {
    /** `true` solo si hay elegibilidad y resultado: es lo que autoriza a mostrar o persistir. */
    val canCompute: Boolean get() = eligibility.canCompute && result != null
}

/**
 * CAMINO ÚNICO de evaluación compartido por el cálculo en vivo (`FvpViewModel.compute`) y la
 * persistencia ([FvpRepository.save]): elegibilidad -> selección CANÓNICA de intento -> motor.
 *
 * No duplica matemáticas: los puntos y el perfil los produce [FvpEngine] (módulo `:fvp`).
 *
 * Contratos (idénticos para todos los llamantes):
 * - serie bloqueada por [checkSeries] -> el motor NO se llama y `result == null`;
 * - serie calculable -> mismos puntos y perfil, bit a bit, con la selección canónica;
 * - si el motor rechazase una entrada que la elegibilidad aceptaba, el fallo no se convierte en
 *   éxito: `result == null` y quien persiste lanza sin escribir nada.
 */
internal fun evaluateSeries(
    seriesInput: FvpSeriesInput,
    evaluationId: String,
    provenance: String,
    sourceFps: Double?,
    engine: FvpEngine = FvpEngine,
): FvpEvaluationOutcome {
    val eligibility = checkSeries(seriesInput)
    val inputs = if (eligibility.canCompute) seriesInput.selectedConditionInputs() else null
    var engineFailure: Throwable? = null
    val result = inputs?.let { selected ->
        runCatching {
            calculateFvpResult(
                engine = engine,
                id = evaluationId,
                protocol = seriesInput.protocol,
                bodyMassKg = seriesInput.bodyMassKg,
                inputs = selected,
                provenance = provenance,
                sourceFps = sourceFps,
            )
        }.getOrElse { failure ->
            engineFailure = failure
            null
        }
    }
    return FvpEvaluationOutcome(eligibility, inputs.orEmpty(), result, engineFailure)
}

/**
 * Fachada de persistencia de evaluaciones F-V-P.
 *
 * Regla de oro: **la capa de elegibilidad (`checkSeries`) decide si se puede llamar al motor**. Si
 * la serie no es calculable, [save] lanza una excepción visible y NO persiste nada (nunca un valor
 * por defecto silencioso). Si es calculable pero no `profileEligible` (p. ej. 2-3 condiciones o
 * calidad temporal condicional), SÍ se guarda: es un registro diagnóstico marcado con sus warnings
 * y flags. La matemática vive en `:fvp` a través de [FvpEngine]; aquí no hay fórmulas.
 *
 * [save] y el cálculo en vivo (`FvpViewModel.compute`) comparten un ÚNICO evaluador
 * ([evaluateSeries]): misma elegibilidad, misma selección canónica de intento y mismos puntos y
 * perfil. La persistencia solo añade la validación de claves de columna y el guardado.
 */
class FvpRepository(
    private val dao: FvpDao,
    private val engine: FvpEngine = FvpEngine,
) {

    /**
     * Calcula (motor) y persiste una evaluación completa. La masa total de cada condición se
     * deriva aquí (`bodyMassKg + addedLoadKg`), nunca la aporta el llamante.
     *
     * Idempotente por `sessionKey` (mismo contrato que `JumpDao.save`).
     *
     * @throws IllegalArgumentException si el borrador trae claves o rangos inválidos, o si el motor
     *   rechaza una serie que la elegibilidad aceptaba (antes ese fallo también se propagaba).
     * @throws IllegalStateException si `checkSeries(...).canCompute == false`; no se persiste nada.
     */
    suspend fun save(draft: FvpDraft): Long {
        require(draft.sessionKey.isNotBlank()) { "La serie F-V-P necesita un sessionKey no vacío." }
        require(draft.athleteId > 0L) { "El athleteId de una serie F-V-P debe ser > 0." }
        val protocol = FvpEngine.parseProtocol(draft.protocol)
            ?: throw IllegalArgumentException(
                "Protocolo F-V-P desconocido: '${draft.protocol}'. Valores válidos: ${FVP_PROTOCOL_KEYS.sorted()}.",
            )
        require(draft.bodyMassKg.isFinite() && draft.bodyMassKg > 0.0) {
            "bodyMassKg debe ser finito y > 0."
        }
        require(draft.bodyMassSource in FVP_BODY_MASS_SOURCES) {
            "bodyMassSource debe ser uno de ${FVP_BODY_MASS_SOURCES.sorted()}, era '${draft.bodyMassSource}'."
        }
        val pushOffMode = pushOffModeOrNull(draft.pushOffMode)
            ?: throw IllegalArgumentException(
                "pushOffMode desconocido: '${draft.pushOffMode}'. Valores válidos: ${FVP_PUSH_OFF_MODES.sorted()}.",
            )
        require(draft.pushOffM.isFinite()) { "pushOffM debe ser finito." }
        require(draft.pushOffSource in FVP_PUSH_OFF_SOURCES) {
            "pushOffSource debe ser uno de ${FVP_PUSH_OFF_SOURCES.sorted()}, era '${draft.pushOffSource}'."
        }
        require(draft.sourceFps == null || draft.sourceFps.isFinite() && draft.sourceFps > 0.0) {
            "sourceFps debe ser finito y > 0 cuando se informa."
        }
        // --- Procedencia declarada (B1b-1) ----------------------------------------------------
        // Se valida ANTES de calcular/persistir: una clave desconocida o un orden temporal
        // imposible no puede entrar en la tabla. NULL/UNKNOWN = desconocido y NO se rellena aquí
        // con fps, fechas ni perfiles (sin inferencias).
        require(draft.acquisitionContextKind in FVP_ACQUISITION_CONTEXT_KINDS) {
            "acquisitionContextKind debe ser uno de ${FVP_ACQUISITION_CONTEXT_KINDS.sorted()}, " +
                "era '${draft.acquisitionContextKind}'."
        }
        require(draft.bodyMassMeasurement in FVP_BODY_MASS_MEASUREMENTS) {
            "bodyMassMeasurement debe ser uno de ${FVP_BODY_MASS_MEASUREMENTS.sorted()}, " +
                "era '${draft.bodyMassMeasurement}'."
        }
        requireNonNegativeOrNull(draft.acquiredAt, "acquiredAt")
        requireNonNegativeOrNull(draft.bodyMassMeasuredAt, "bodyMassMeasuredAt")
        requireProvenanceKeyOrNull(draft.acquisitionContextKey, "acquisitionContextKey")
        require(draft.acquisitionContextKind != "SAME_ACQUISITION" || draft.acquisitionContextKey != null) {
            "acquisitionContextKind=SAME_ACQUISITION exige una acquisitionContextKey declarada " +
                "(no se deriva de cercanía de fechas ni de otro campo)."
        }
        draft.conditions.forEachIndexed { conditionIndex, condition ->
            val where = "La condición $conditionIndex ('${condition.conditionKey}')"
            require(condition.addedLoadSource in FVP_ADDED_LOAD_SOURCES) {
                "$where: addedLoadSource debe ser uno de ${FVP_ADDED_LOAD_SOURCES.sorted()}, " +
                    "era '${condition.addedLoadSource}'."
            }
            requireNonNegativeOrNull(condition.addedLoadMeasuredAt, "$where: addedLoadMeasuredAt")
            condition.attempts.forEachIndexed { attemptIndex, attempt ->
                validateAttemptProvenance(
                    attempt = attempt,
                    label = "El intento $attemptIndex de la condición $conditionIndex ('${condition.conditionKey}')",
                )
            }
        }

        val seriesInput = draft.toEligibilitySeriesInput(protocol = protocol, pushOffMode = pushOffMode)
        val outcome = evaluateSeries(
            seriesInput = seriesInput,
            evaluationId = draft.sessionKey,
            provenance = FVP_EVALUATION_PROVENANCE,
            sourceFps = draft.sourceFps,
            engine = engine,
        )
        val eligibility = outcome.eligibility
        if (!eligibility.canCompute) {
            throw IllegalStateException(
                "La serie F-V-P no se puede calcular (${eligibility.blocking.joinToString { it.name }}). " +
                    "Corrige la serie antes de guardarla.",
            )
        }
        val inputs = outcome.inputs
        if (inputs.isEmpty()) {
            throw IllegalStateException("La serie F-V-P es calculable pero la selección de intentos no es coherente.")
        }
        val result = outcome.result
        if (result == null) {
            // Nunca un valor por defecto: se falla en voz alta. Si el motor ya lanzo su propio
            // error, se propaga tal cual (mismo contrato que antes de compartir el camino).
            throw outcome.engineFailure ?: IllegalStateException(
                "El motor F-V-P rechazó una serie que la elegibilidad aceptaba: no se persiste nada.",
            )
        }
        val profile = result.profile
        check(profile.f0N.isFinite() && profile.slopeFv.isFinite() && profile.sfvNsPerM.isFinite()) {
            "El motor devolvió un snapshot no finito donde la columna es NOT NULL."
        }

        val inputsByKey = inputs.associateBy { it.conditionId }
        val conditionRows = draft.conditions.mapIndexed { ordinal, condition ->
            val input = inputsByKey[condition.conditionKey]
                ?: throw IllegalStateException(
                    "La condición '${condition.conditionKey}' no tiene entrada calculable.",
                )
            // REGLA CANÓNICA de selección (`FvpEligibility.selectedValidAttemptIndex`): el intento
            // VÁLIDO de mayor altura y, ante empate, el primero. No hay copia local de la regla: se
            // aplica sobre la MISMA condición de elegibilidad que alimentó al motor. Las claves son
            // únicas aquí (`DUPLICATE_CONDITION_KEY` habría bloqueado la serie antes).
            val eligibilityCondition = seriesInput.conditions
                .first { it.conditionKey == condition.conditionKey }
            val selectedIndex = requireNotNull(seriesInput.selectedValidAttemptIndex(eligibilityCondition)) {
                "La condición '${condition.conditionKey}' no tiene intento válido seleccionable."
            }
            check(condition.attempts[selectedIndex].heightM == input.heightM) {
                "La selección de intento de '${condition.conditionKey}' no coincide con la capa de elegibilidad."
            }
            FvpConditionEntity(
                assessmentId = 0,
                ordinal = ordinal,
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                totalMassKg = input.totalMassKg,
                pushOffM = input.pushOffM,
                selectedHeightM = input.heightM,
                selectedAttemptOrdinal = selectedIndex,
                addedLoadSource = condition.addedLoadSource,
                addedLoadMeasuredAt = condition.addedLoadMeasuredAt,
            )
        }
        val attemptRows = draft.conditions.mapIndexed { conditionOrdinal, condition ->
            condition.attempts.mapIndexed { attemptOrdinal, attempt ->
                FvpAttemptEntity(
                    // Clave temporal antes de insertar la condición padre (ver FvpDao.save).
                    conditionId = conditionOrdinal.toLong(),
                    ordinal = attemptOrdinal,
                    heightM = attempt.heightM,
                    method = attempt.method,
                    flightTimeS = attempt.flightTimeS,
                    sourceFps = attempt.sourceFps,
                    sourceAssessmentId = attempt.sourceAssessmentId,
                    sourceAttemptOrdinal = attempt.sourceAttemptOrdinal,
                    sourceProtocolId = attempt.sourceProtocolId,
                    valid = attempt.valid,
                    exclusionReason = attempt.exclusionReason,
                    notes = NoteNormalizer.normalize(attempt.notes),
                    sourceRecordedAt = attempt.sourceRecordedAt,
                    sourceAthleteId = attempt.sourceAthleteId,
                    acquiredAt = attempt.acquiredAt,
                    reuseConfirmedAt = attempt.reuseConfirmedAt,
                    reuseConfirmationVersion = attempt.reuseConfirmationVersion,
                    takeoffPtsUs = attempt.takeoffPtsUs,
                    takeoffPreviousPtsUs = attempt.takeoffPreviousPtsUs,
                    takeoffNextPtsUs = attempt.takeoffNextPtsUs,
                    landingPtsUs = attempt.landingPtsUs,
                    landingPreviousPtsUs = attempt.landingPreviousPtsUs,
                    landingNextPtsUs = attempt.landingNextPtsUs,
                    timeMapping = attempt.timeMapping,
                    timeMappingEvidenceKey = attempt.timeMappingEvidenceKey,
                )
            }
        }.flatten()

        val row = FvpAssessmentEntity(
            sessionKey = draft.sessionKey,
            athleteId = draft.athleteId,
            testingSessionId = null,
            protocolId = draft.protocol,
            dateTime = draft.dateTime,
            bodyMassKg = draft.bodyMassKg,
            bodyMassSource = draft.bodyMassSource,
            pushOffMode = draft.pushOffMode,
            pushOffM = draft.pushOffM,
            pushOffSource = draft.pushOffSource,
            pushOffLandmark = draft.pushOffLandmark,
            pushOffLandmarkHeightCm = draft.pushOffLandmarkHeightCm,
            pushOffProtocol = draft.pushOffProtocol,
            pushOffMeasuredAt = draft.pushOffMeasuredAt,
            pushOffCrouchCm = draft.pushOffCrouchCm,
            pushOffExtendedCm = draft.pushOffExtendedCm,
            sourceFps = draft.sourceFps,
            acquiredAt = draft.acquiredAt,
            acquisitionContextKind = draft.acquisitionContextKind,
            acquisitionContextKey = draft.acquisitionContextKey,
            bodyMassMeasurement = draft.bodyMassMeasurement,
            bodyMassMeasuredAt = draft.bodyMassMeasuredAt,
            algorithmVersion = result.algorithmVersion,
            methodVersion = draft.methodVersion,
            notes = NoteNormalizer.normalize(draft.notes),
            f0N = profile.f0N,
            slopeFv = profile.slopeFv,
            sfvNsPerM = profile.sfvNsPerM,
            v0Mps = profile.v0Mps.finiteOrNull(),
            pmaxW = profile.pmaxW.finiteOrNull(),
            r2 = profile.r2.finiteOrNull(),
            conditionCount = profile.n,
            vMinMps = profile.vMinMps,
            vMaxMps = profile.vMaxMps,
            fMinN = profile.fMinN,
            fMaxN = profile.fMaxN,
            f0RelNPerKg = profile.f0RelNPerKg.finiteOrNull(),
            pmaxRelWPerKg = profile.pmaxRelWPerKg.finiteOrNull(),
            warningsCsv = warningsCsv(profile.warnings, eligibility.flags),
        )
        return dao.save(PersistableFvpAssessment(row, conditionRows, attemptRows))
    }

    /**
     * Lectura SOLO-LECTURA de la fila que ocupa ese `sessionKey`, o `null` si no hay ninguna (D6/R3).
     *
     * SQL reutilizado, sin cambio de proyección:
     * ```sql
     * SELECT * FROM fvp_assessments WHERE sessionKey = :sessionKey LIMIT 1
     * ```
     * (`FvpDao.bySessionKey`; `sessionKey` tiene índice ÚNICO, así que la lectura es acotada. Desde
     * la migración aditiva 11→12 (B1a) la tabla tiene columnas nuevas, pero esta proyección sigue
     * leyendo solo el id de la fila y su dueño.) Se proyecta a
     * [FvpPersistedSession] porque la reconciliación solo necesita el id de la fila y su dueño: no
     * se cargan hijos ni se recalcula nada.
     *
     * No lanza por MOTIVOS DE DATOS: una fila con `athleteId` nulo se devuelve con
     * `athleteId == null`, que la reconciliación trata como dueño distinto (nunca como coincidencia).
     * Un fallo de infraestructura (base cerrada, error de SQLite) sí se propaga: quien restaura
     * debe fallar cerrado, no inventar que no hay fila.
     */
    suspend fun bySessionKey(sessionKey: String): FvpPersistedSession? =
        dao.bySessionKey(sessionKey)?.let { row ->
            FvpPersistedSession(rowId = row.id, athleteId = row.athleteId)
        }

    /**
     * Carga una evaluación con sus hijos y, cuando es POSIBLE, RECALCULA el perfil con el motor
     * actual.
     *
     * NUNCA lanza por motivos de datos persistidos: una clave desconocida (protocolo, modo de h_po
     * o método de medida) o una serie que hoy no es computable devuelve la fila con
     * `result = null`, `eligibility = null` (si ni siquiera se pudo construir la serie) o la
     * elegibilidad calculada, y [FvpSnapshotStatus.RECALCULATION_UNAVAILABLE]. Los números del
     * snapshot persistido siguen siendo legibles.
     */
    suspend fun byId(id: Long): StoredFvpAssessment? {
        val stored = dao.assessmentWithData(id) ?: return null
        val entity = stored.assessment
        val conditions = stored.conditions.sortedWith(compareBy({ it.condition.ordinal }, { it.condition.id }))
        val attempts = stored.conditions.flatMap { it.attempts }
            .sortedWith(compareBy({ it.conditionId }, { it.ordinal }, { it.id }))
        // Mapeo DEFENSIVO: una clave persistida desconocida deja la serie sin recálculo (no hay
        // valor por defecto silencioso), pero la fila sigue devolviéndose con su snapshot legible.
        val seriesInput = storedSeriesInput(entity, conditions)
        val eligibility = seriesInput?.let(::checkSeries)
        val inputs = seriesInput?.selectedConditionInputs()
        // El motor también puede rechazar una serie que la elegibilidad de hoy aceptaba (p. ej. una
        // fila escrita por otra versión): ese rechazo es un motivo de DATO persistido, así que se
        // trata como "sin recálculo" en vez de propagar la excepción. Un fallo de NIVEL DE PROCESO
        // (`Error`: OOM, `LinkageError`, ...) NO es un veredicto sobre el dato y se propaga tal cual:
        // no se convierte en una fila "sin recálculo" aparentemente legible.
        val result = if (seriesInput != null && inputs != null && eligibility != null) {
            runCatching {
                calculateFvpResult(
                    engine = engine,
                    id = entity.sessionKey,
                    protocol = seriesInput.protocol,
                    bodyMassKg = entity.bodyMassKg,
                    inputs = inputs,
                    provenance = FVP_EVALUATION_PROVENANCE,
                    sourceFps = entity.sourceFps,
                )
            }.getOrElse { failure -> if (failure is Error) throw failure else null }
        } else {
            null
        }
        return StoredFvpAssessment(
            assessment = entity,
            conditions = conditions.map { it.condition },
            attempts = attempts,
            eligibility = eligibility,
            result = result,
            snapshotStatus = fvpSnapshotStatusFor(
                storedAlgorithmVersion = entity.algorithmVersion,
                recalculatedAlgorithmVersion = result?.algorithmVersion,
                storedMethodVersion = entity.methodVersion,
                currentMethodVersion = FVP_METHOD_VERSION,
                numbersMatch = result != null && eligibility != null &&
                    snapshotMatches(entity, eligibility, result),
            ),
        )
    }

    /** Resúmenes de todas las evaluaciones, `dateTime DESC, id DESC`. */
    fun recent(): Flow<List<FvpAssessmentSummary>> = dao.recent().map { it.toSummaries() }

    /** Resúmenes de las evaluaciones de un atleta, `dateTime DESC, id DESC`. */
    fun forAthlete(athleteId: Long): Flow<List<FvpAssessmentSummary>> =
        dao.forAthlete(athleteId).map { it.toSummaries() }

    /** Borra la raíz y sus hijos (CASCADE). `true` solo si existía la raíz. */
    suspend fun delete(id: Long): Boolean = dao.delete(id) == 1

    /** Normaliza la nota (trim, vacío -> NULL) y la persiste. `true` solo si existía la fila. */
    suspend fun updateNotes(id: Long, notes: String?): Boolean =
        dao.updateNotes(id, NoteNormalizer.normalize(notes)) == 1

    // --- interno ------------------------------------------------------------------------------

    private fun snapshotMatches(
        entity: FvpAssessmentEntity,
        eligibility: FvpEligibility,
        result: FvpResult,
    ): Boolean {
        val profile = result.profile
        return entity.f0N == profile.f0N &&
            entity.slopeFv == profile.slopeFv &&
            entity.sfvNsPerM == profile.sfvNsPerM &&
            entity.v0Mps.matches(profile.v0Mps) &&
            entity.pmaxW.matches(profile.pmaxW) &&
            entity.r2.matches(profile.r2) &&
            entity.conditionCount == profile.n &&
            entity.vMinMps == profile.vMinMps &&
            entity.vMaxMps == profile.vMaxMps &&
            entity.fMinN == profile.fMinN &&
            entity.fMaxN == profile.fMaxN &&
            entity.f0RelNPerKg.matches(profile.f0RelNPerKg) &&
            entity.pmaxRelWPerKg.matches(profile.pmaxRelWPerKg) &&
            entity.warningsCsv == warningsCsv(profile.warnings, eligibility.flags)
    }
}

/** `true` si el valor persistido coincide con el recalculado (NaN recalculado == NULL persistido). */
private fun Double?.matches(recalculated: Double): Boolean =
    if (recalculated.isFinite()) this == recalculated else this == null

/**
 * Entrada de elegibilidad de una fila PERSISTIDA (misma forma que `save`), o `null` si alguna
 * clave persistida no se puede mapear a la capa de elegibilidad: protocolo, modo de h_po o método
 * de medida desconocidos. En ese caso no se puede construir la serie siquiera y el llamante debe
 * devolver la fila SIN recálculo, nunca un valor por defecto silencioso.
 */
private fun storedSeriesInput(
    entity: FvpAssessmentEntity,
    conditions: List<FvpConditionWithAttempts>,
): FvpSeriesInput? {
    val protocol = FvpEngine.parseProtocol(entity.protocolId) ?: return null
    val pushOffMode = pushOffModeOrNull(entity.pushOffMode) ?: return null
    val conditionDrafts = conditions.map { row ->
        FvpEligibilityConditionDraft(
            conditionKey = row.condition.conditionKey,
            addedLoadKg = row.condition.addedLoadKg,
            pushOffM = row.condition.pushOffM,
            attempts = row.attempts.sortedWith(compareBy({ it.ordinal }, { it.id })).map { attempt ->
                FvpEligibilityAttemptDraft(
                    heightM = attempt.heightM,
                    method = FvpEngine.parseMethod(attempt.method) ?: return null,
                    sourceFps = attempt.sourceFps,
                    valid = attempt.valid,
                    exclusionReason = attempt.exclusionReason,
                )
            },
        )
    }
    return FvpSeriesInput(
        protocol = protocol,
        bodyMassKg = entity.bodyMassKg,
        pushOffMode = pushOffMode,
        commonPushOffM = entity.pushOffM.takeIf { pushOffMode == FvpPushOffMode.COMMON },
        conditions = conditionDrafts,
    )
}

private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

/** Longitud máxima de una clave textual de procedencia (misma regla que el contrato B0). */
private const val FVP_PROVENANCE_KEY_MAX_LENGTH = 128

/**
 * Un timestamp de procedencia (epoch millis) es `>= 0` cuando se informa; `null` = desconocido.
 * Esta validación NO rellena nada: nunca se sustituye NULL por "ahora".
 */
private fun requireNonNegativeOrNull(value: Long?, field: String) {
    require(value == null || value >= 0L) {
        "$field debe ser >= 0 cuando se informa; NULL = desconocido."
    }
}

/**
 * Una clave textual de procedencia es no vacía y de `<= 128` caracteres cuando se informa;
 * `null` = desconocida (nunca se crea una clave por defecto).
 */
private fun requireProvenanceKeyOrNull(value: String?, field: String) {
    if (value == null) return
    require(value.isNotBlank()) {
        "$field no puede estar vacía cuando se informa; NULL = desconocido."
    }
    require(value.length <= FVP_PROVENANCE_KEY_MAX_LENGTH) {
        "$field no puede superar $FVP_PROVENANCE_KEY_MAX_LENGTH caracteres (era ${value.length})."
    }
}

/**
 * Valida la procedencia de un intento (B0/B1b-1): `timeMapping` conocido, clave de evidencia
 * acotada, snapshot de origen/rango de reutilización coherentes y PTS ordenados. Las reglas de
 * orden solo se exigen cuando los valores existen: la ausencia sigue siendo desconocido y el
 * repositorio NO deriva PTS ni fechas.
 */
private fun validateAttemptProvenance(attempt: FvpAttemptDraft, label: String) {
    require(attempt.timeMapping in FVP_TIME_MAPPINGS) {
        "$label: timeMapping debe ser uno de ${FVP_TIME_MAPPINGS.sorted()}, era '${attempt.timeMapping}'."
    }
    requireProvenanceKeyOrNull(attempt.timeMappingEvidenceKey, "$label: timeMappingEvidenceKey")
    require(attempt.timeMapping != "IDENTITY_VERIFIED" || attempt.timeMappingEvidenceKey != null) {
        "$label: timeMapping=IDENTITY_VERIFIED exige timeMappingEvidenceKey declarada " +
            "(la evidencia no se infiere de fps nominales, captura ni checkbox)."
    }
    requireNonNegativeOrNull(attempt.sourceRecordedAt, "$label: sourceRecordedAt")
    requireNonNegativeOrNull(attempt.acquiredAt, "$label: acquiredAt")
    requireNonNegativeOrNull(attempt.reuseConfirmedAt, "$label: reuseConfirmedAt")
    require(attempt.sourceAthleteId == null || attempt.sourceAthleteId > 0L) {
        "$label: sourceAthleteId debe ser > 0 cuando se informa; NULL = desconocido."
    }
    val reuseVersion = attempt.reuseConfirmationVersion
    require(reuseVersion == null || reuseVersion == FVP_REUSE_CONFIRMATION_VERSION) {
        "$label: reuseConfirmationVersion debe ser NULL o '$FVP_REUSE_CONFIRMATION_VERSION', " +
            "era '$reuseVersion'."
    }
    require((reuseVersion == null) == (attempt.reuseConfirmedAt == null)) {
        "$label: reuseConfirmationVersion y reuseConfirmedAt van juntos: " +
            "los dos informados (confirmación durable) o los dos NULL."
    }
    requirePtsOrder(
        label = label,
        eventField = "takeoffPtsUs",
        event = attempt.takeoffPtsUs,
        previousField = "takeoffPreviousPtsUs",
        previous = attempt.takeoffPreviousPtsUs,
        nextField = "takeoffNextPtsUs",
        next = attempt.takeoffNextPtsUs,
    )
    requirePtsOrder(
        label = label,
        eventField = "landingPtsUs",
        event = attempt.landingPtsUs,
        previousField = "landingPreviousPtsUs",
        previous = attempt.landingPreviousPtsUs,
        nextField = "landingNextPtsUs",
        next = attempt.landingNextPtsUs,
    )
    require(
        attempt.takeoffPtsUs == null || attempt.landingPtsUs == null ||
            attempt.landingPtsUs > attempt.takeoffPtsUs,
    ) {
        "$label: landingPtsUs debe ser estrictamente mayor que takeoffPtsUs cuando ambos existen; " +
            "takeoffPtsUs=${attempt.takeoffPtsUs}, landingPtsUs=${attempt.landingPtsUs}."
    }
}

/**
 * PTS [µs] de un evento (`event`) con sus vecinos inmediatos opcionales: no negativos, y cada
 * vecino exige el PTS del evento con orden estricto (`previous < event < next`). No calcula ni
 * aproxima ningún PTS ausente.
 */
private fun requirePtsOrder(
    label: String,
    eventField: String,
    event: Long?,
    previousField: String,
    previous: Long?,
    nextField: String,
    next: Long?,
) {
    requireNonNegativeOrNull(event, "$label: $eventField")
    requireNonNegativeOrNull(previous, "$label: $previousField")
    requireNonNegativeOrNull(next, "$label: $nextField")
    require(previous == null || event != null) {
        "$label: $previousField exige $eventField (un PTS anterior sin evento no es persistible)."
    }
    require(next == null || event != null) {
        "$label: $nextField exige $eventField (un PTS posterior sin evento no es persistible)."
    }
    require(previous == null || event == null || previous < event) {
        "$label: $previousField debe ser estrictamente menor que $eventField."
    }
    require(next == null || event == null || event < next) {
        "$label: $nextField debe ser estrictamente mayor que $eventField."
    }
}

private fun pushOffModeOrNull(storageKey: String): FvpPushOffMode? = when (storageKey) {
    "COMMON" -> FvpPushOffMode.COMMON
    "PER_CONDITION" -> FvpPushOffMode.PER_CONDITION
    else -> null
}

/**
 * Puntos (motor) + perfil (motor) para unas entradas ya seleccionadas. Ninguna fórmula F-V-P se
 * duplica fuera de `:fvp`: este es el único punto del módulo app que llama al motor.
 */
private fun calculateFvpResult(
    engine: FvpEngine,
    id: String,
    protocol: FvpProtocol,
    bodyMassKg: Double,
    inputs: List<FvpConditionInput>,
    provenance: String,
    sourceFps: Double?,
): FvpResult {
    val points = inputs.map { input ->
        val point = engine.point(input.totalMassKg, input.heightM, input.pushOffM)
        FvpConditionPoint(
            conditionId = input.conditionId,
            totalMassKg = input.totalMassKg,
            heightM = input.heightM,
            pushOffM = input.pushOffM,
            forceN = point.forceN,
            velocityMps = point.velocityMps,
            powerW = point.powerW,
        )
    }
    return engine.evaluation(
        id = id,
        protocol = protocol,
        bodyMassKg = bodyMassKg,
        conditions = points,
        provenance = provenance,
        sourceFps = sourceFps,
    )
}

/**
 * Serie de elegibilidad equivalente al borrador de PERSISTENCIA (mapa 1:1, orden preservado, sin
 * cálculo). `protocol` y `pushOffMode` llegan ya parseados por [FvpRepository.save] (la validación
 * de sus claves es previa y conserva sus mensajes); el método de medida de cada intento sí se
 * traduce aquí, y un valor desconocido lanza `IllegalArgumentException` (nunca un defecto
 * silencioso).
 */
internal fun FvpDraft.toEligibilitySeriesInput(
    protocol: FvpProtocol,
    pushOffMode: FvpPushOffMode,
): FvpSeriesInput = FvpSeriesInput(
    protocol = protocol,
    bodyMassKg = bodyMassKg,
    pushOffMode = pushOffMode,
    commonPushOffM = pushOffM.takeIf { pushOffMode == FvpPushOffMode.COMMON },
    conditions = conditions.map { condition ->
        FvpEligibilityConditionDraft(
            conditionKey = condition.conditionKey,
            addedLoadKg = condition.addedLoadKg,
            pushOffM = condition.pushOffM,
            attempts = condition.attempts.map { attempt ->
                FvpEligibilityAttemptDraft(
                    heightM = attempt.heightM,
                    method = FvpEngine.parseMethod(attempt.method)
                        ?: throw IllegalArgumentException(
                            "Método de medida desconocido: '${attempt.method}'. " +
                                "Valores válidos: ${FVP_MEASURE_METHODS.sorted()}.",
                        ),
                    sourceFps = attempt.sourceFps,
                    valid = attempt.valid,
                    exclusionReason = attempt.exclusionReason,
                )
            },
        )
    },
)

/**
 * Nombres de `FvpWarning` del perfil (en el orden en que los emite el motor) seguidos de los
 * flags de elegibilidad (ya ordenados por ordinal de enum), sin duplicados y separados por coma.
 * Es determinista para la misma entrada.
 */
private fun warningsCsv(warnings: List<FvpWarning>, flags: List<FvpEligibilityFlag>): String =
    (warnings.map { it.name } + flags.map { it.name }).distinct().joinToString(",")

/** Lista de nombres persistida en `warningsCsv` (CSV vacío -> lista vacía). */
internal fun parseWarningsCsv(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun List<FvpAssessmentEntity>.toSummaries(): List<FvpAssessmentSummary> =
    map { it.toSummary() }
        .sortedWith(
            compareByDescending<FvpAssessmentSummary> { it.dateTime }.thenByDescending { it.id },
        )

private fun FvpAssessmentEntity.toSummary(): FvpAssessmentSummary = FvpAssessmentSummary(
    id = id,
    sessionKey = sessionKey,
    athleteId = athleteId,
    protocolId = protocolId,
    dateTime = dateTime,
    bodyMassKg = bodyMassKg,
    conditionCount = conditionCount,
    f0N = f0N,
    sfvNsPerM = sfvNsPerM,
    v0Mps = v0Mps,
    pmaxW = pmaxW,
    pmaxRelWPerKg = pmaxRelWPerKg,
    f0RelNPerKg = f0RelNPerKg,
    r2 = r2,
    algorithmVersion = algorithmVersion,
    methodVersion = methodVersion,
    warnings = parseWarningsCsv(warningsCsv),
    hasNotes = !notes.isNullOrBlank(),
)

private const val FVP_EVALUATION_PROVENANCE = "fvp-app"
