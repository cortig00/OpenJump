@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openjump.app.ui.fvp

import androidx.compose.foundation.relocation.BringIntoViewRequester
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.fvp.MAX_JUMP_HEIGHT_M
import com.openjump.fvp.MAX_PUSH_OFF_M
import com.openjump.fvp.MIN_PUSH_OFF_M

/**
 * Destino de revision de la pantalla de Condiciones (plan `.pi/plans/fvp-guidance-ux.md`, slice B).
 *
 * Es una decision de PRESENTACION: no decide elegibilidad, no cambia gates ni muta el borrador. Solo
 * dice a que control de ESTA pantalla corresponde un motivo que la elegibilidad ya calculo.
 */
internal enum class FvpConditionFieldTarget {
    /** No hay un campo inequivoco en esta pantalla (p. ej. masa corporal o h_po comun). */
    NONE,

    /** El boton existente "Anadir condicion" del pie: el destino ya esta a la vista. */
    ADD_CONDITION,

    /** Clave de una condicion. */
    CONDITION_KEY,

    /** Carga anadida de una condicion. */
    ADDED_LOAD,

    /** h_po por condicion (solo en modo PER_CONDITION; en COMMON el campo vive en el paso 1). */
    PUSH_OFF,

    /** Altura de un intento concreto, valido o excluido. */
    ATTEMPT_HEIGHT,

    /** Motivo de exclusion de un intento NO valido. */
    EXCLUSION_REASON,

    /** Bloque completo de intentos de una condicion (su primer campo no es el problema). */
    ATTEMPT_LIST,

    /**
     * Clave de contexto de adquisicion (seccion "origen de intentos" de esta pantalla).
     *
     * Es un destino de PRESENTACION: el motivo lo aporta la capa de UI (declaracion
     * `SAME_ACQUISITION` sin clave), no la formula de elegibilidad, que no cambia.
     */
    ACQUISITION_CONTEXT,
}

/**
 * Un motivo BLOQUEANTE ya existente, con el control al que hay que llevar al usuario.
 *
 * [problem] es SIEMPRE el problema de elegibilidad que lo origina: la lista de motivos localizados
 * se construye a partir de esta misma lista, de modo que el motivo del pie no puede discrepar del
 * motivo completo. [conditionDraftKey]/[attemptDraftKey] son identidades ESTABLES del borrador (no
 * indices visibles): tras borrar una condicion el motivo se recalcula desde el borrador actual y no
 * queda ningun destino obsoleto.
 */
internal data class FvpConditionPending(
    /** Problema de elegibilidad YA existente, o `null` si el motivo es de la capa de UI. */
    val problem: FvpEligibilityProblem? = null,
    val target: FvpConditionFieldTarget = FvpConditionFieldTarget.NONE,
    val conditionDraftKey: String? = null,
    val attemptDraftKey: String? = null,
    /**
     * `true` cuando el motivo es la clave de contexto de adquisicion ausente; NUNCA lo produce la
     * elegibilidad (no hay formula nueva): la declaracion `SAME_ACQUISITION` sin clave la bloquea
     * la UI y este campo lo declara para que el pie y el listado completo digan el motivo EXACTO.
     */
    val contextKeyMissing: Boolean = false,
) {
    /** Ancla de desplazamiento, o `null` si no hay un campo inequivoco que revisar. */
    val anchorKey: String?
        get() {
            if (target == FvpConditionFieldTarget.ACQUISITION_CONTEXT) {
                return FVP_ACQUISITION_CONTEXT_ANCHOR_KEY
            }
            val conditionKey = conditionDraftKey ?: return null
            return when (target) {
                FvpConditionFieldTarget.CONDITION_KEY -> fvpConditionAnchorKey(conditionKey, FIELD_KEY)
                FvpConditionFieldTarget.ADDED_LOAD -> fvpConditionAnchorKey(conditionKey, FIELD_LOAD)
                FvpConditionFieldTarget.PUSH_OFF -> fvpConditionAnchorKey(conditionKey, FIELD_PUSH_OFF)
                FvpConditionFieldTarget.ATTEMPT_LIST -> fvpConditionAnchorKey(conditionKey, FIELD_ATTEMPTS)
                FvpConditionFieldTarget.ATTEMPT_HEIGHT -> attemptDraftKey?.let {
                    fvpAttemptAnchorKey(conditionKey, it, FIELD_HEIGHT)
                }
                FvpConditionFieldTarget.EXCLUSION_REASON -> attemptDraftKey?.let {
                    fvpAttemptAnchorKey(conditionKey, it, FIELD_REASON)
                }
                FvpConditionFieldTarget.NONE,
                FvpConditionFieldTarget.ADD_CONDITION,
                FvpConditionFieldTarget.ACQUISITION_CONTEXT,
                -> null
            }
        }

    /** `true` solo si "Revisar dato pendiente" tiene un control concreto al que desplazarse. */
    val hasReviewAnchor: Boolean get() = anchorKey != null

    private companion object {
        const val FIELD_KEY = "key"
        const val FIELD_LOAD = "load"
        const val FIELD_PUSH_OFF = "pushoff"
        const val FIELD_ATTEMPTS = "attempts"
        const val FIELD_HEIGHT = "height"
        const val FIELD_REASON = "reason"
    }
}

/**
 * Ancla del campo de clave del contexto de adquisicion (unico en la pantalla: no es por condicion).
 */
internal const val FVP_ACQUISITION_CONTEXT_ANCHOR_KEY: String = "context:acquisition:key"

/**
 * Motivo UI-only de la clave de contexto ausente con `SAME_ACQUISITION`. Se inserta ANTES de los
 * motivos de elegibilidad porque el campo esta por encima de las cargas en la pantalla, y NO toca
 * ningun gate de elegibilidad: la pantalla lo anade a `canCalculate` como una comprobacion propia.
 */
internal fun fvpAcquisitionContextKeyPending(): FvpConditionPending = FvpConditionPending(
    problem = null,
    target = FvpConditionFieldTarget.ACQUISITION_CONTEXT,
    contextKeyMissing = true,
)

/**
 * Estado de PRESENTACION de una carga en el resumen compacto. No es una calificacion cientifica ni
 * cambia la elegibilidad: solo distingue entre una carga todavia sin datos, una con datos
 * registrados y una que tiene un motivo bloqueante (y por tanto se puede revisar).
 */
internal enum class FvpConditionSummaryStatus {
    PENDING,
    REGISTERED,
    REVIEW,
}

/**
 * Estado del resumen de [condition]. `review` manda si hay un motivo bloqueante cuyo destino es
 * ESTA condicion (misma identidad `draftKey`, nunca por indice) y la carga YA tiene algun dato
 * declarado: una carga recien creada, todavia vacia, es `pending` y no un problema del usuario.
 * Sin motivo, esta `registered` cuando al menos un intento valido tiene una altura utilizable.
 */
internal fun fvpConditionSummaryStatus(
    condition: FvpConditionDraft,
    hasBlockingCondition: Boolean,
): FvpConditionSummaryStatus {
    val hasDeclaredData = condition.addedLoadKg != null ||
        condition.attempts.any { it.heightM != null }
    return when {
        hasBlockingCondition && hasDeclaredData -> FvpConditionSummaryStatus.REVIEW
        condition.attempts.any { it.valid && fvpHeightUsable(it.heightM) } ->
            FvpConditionSummaryStatus.REGISTERED
        else -> FvpConditionSummaryStatus.PENDING
    }
}

/** Clave de ancla de un campo de condicion (identidad estable del borrador, no indice de fila). */
internal fun fvpConditionAnchorKey(conditionDraftKey: String, field: String): String =
    "cond:$conditionDraftKey:$field"

/** Clave de ancla de un campo de intento. */
internal fun fvpAttemptAnchorKey(
    conditionDraftKey: String,
    attemptDraftKey: String,
    field: String,
): String = "attempt:$conditionDraftKey:$attemptDraftKey:$field"

/**
 * Altura UTILIZABLE segun el MISMO dominio que exige la elegibilidad y la persistencia
 * (finita, `> 0` y `<= MAX_JUMP_HEIGHT_M`). Una altura `null` cuenta como inutilizable: la capa de
 * elegibilidad la proyecta a `NaN` y la bloquea como `INVALID_HEIGHT`.
 */
internal fun fvpHeightUsable(heightM: Double?): Boolean =
    heightM != null && heightM.isFinite() && heightM > 0.0 && heightM <= MAX_JUMP_HEIGHT_M

/** h_po utilizable segun el dominio del motor (`(MIN_PUSH_OFF_M, MAX_PUSH_OFF_M]`). */
internal fun fvpPushOffUsable(pushOffM: Double?): Boolean =
    pushOffM != null && pushOffM.isFinite() && pushOffM > MIN_PUSH_OFF_M && pushOffM <= MAX_PUSH_OFF_M

/** Carga anadida valida para la elegibilidad: finita y `>= 0`. */
internal fun fvpAddedLoadUsable(loadKg: Double?): Boolean =
    loadKg != null && loadKg.isFinite() && loadKg >= 0.0

/**
 * Lista ORDENADA de motivos pendientes de la pantalla, con su destino.
 *
 * Reutiliza EXACTAMENTE la misma materia prima que el listado global: los booleanos de borrador que
 * ya forman el gate ([missingAttemptHeight]/[missingExclusionReason]) y `blocking`, la lista que
 * devuelve la elegibilidad. No se recalcula ningun gate ni se anade una regla nueva.
 *
 * Orden: primero los dos motivos que la pantalla ya destacaba (altura inutilizable y motivo de
 * exclusion ausente), despues `blocking` en su orden de declaracion. Se deduplica por problema (el
 * mismo problema puede llegar por la via del borrador y por la del dominio). El primer elemento es,
 * por construccion, el primer motivo del listado completo que se muestra al usuario.
 */
internal fun fvpConditionPendings(
    conditions: List<FvpConditionDraft>,
    pushOffMode: FvpPushOffUiMode,
    blocking: List<FvpEligibilityProblem>,
    missingAttemptHeight: Boolean,
    missingExclusionReason: Boolean,
): List<FvpConditionPending> {
    val pendings = mutableListOf<FvpConditionPending>()
    fun add(pending: FvpConditionPending) {
        if (pendings.none { it.problem == pending.problem }) pendings += pending
    }
    if (missingAttemptHeight) {
        add(fvpConditionPendingFor(FvpEligibilityProblem.INVALID_HEIGHT, conditions, pushOffMode))
    }
    if (missingExclusionReason) {
        add(fvpConditionPendingFor(FvpEligibilityProblem.EXCLUSION_REASON_MISSING, conditions, pushOffMode))
    }
    blocking.forEach { problem ->
        add(fvpConditionPendingFor(problem, conditions, pushOffMode))
    }
    return pendings
}

/**
 * Destino de un problema concreto sobre el borrador ACTUAL.
 *
 * Cuando la correspondencia no es inequivoca no se inventa un campo: `target = NONE` (sin accion de
 * revision) y el motivo completo sigue visible. `INVALID_HEIGHT` incluye los intentos NO validos
 * (excluidos): la altura se valida en el dominio para todos ellos.
 */
internal fun fvpConditionPendingFor(
    problem: FvpEligibilityProblem,
    conditions: List<FvpConditionDraft>,
    pushOffMode: FvpPushOffUiMode,
): FvpConditionPending = when (problem) {
    FvpEligibilityProblem.INVALID_HEIGHT ->
        conditions.firstAttemptWhere { !fvpHeightUsable(it.heightM) }
            ?.let { (condition, attempt) ->
                FvpConditionPending(
                    problem = problem,
                    target = FvpConditionFieldTarget.ATTEMPT_HEIGHT,
                    conditionDraftKey = condition.draftKey,
                    attemptDraftKey = attempt.draftKey,
                )
            }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.EXCLUSION_REASON_MISSING ->
        conditions.firstAttemptWhere { !it.valid && it.exclusionReason.isNullOrBlank() }
            ?.let { (condition, attempt) ->
                FvpConditionPending(
                    problem = problem,
                    target = FvpConditionFieldTarget.EXCLUSION_REASON,
                    conditionDraftKey = condition.draftKey,
                    attemptDraftKey = attempt.draftKey,
                )
            }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.INVALID_CONDITION_KEY ->
        conditions.firstConditionWhere { it.conditionKey.isBlank() }
            ?.let { FvpConditionPending(problem, FvpConditionFieldTarget.CONDITION_KEY, it.draftKey) }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.DUPLICATE_CONDITION_KEY ->
        conditions.firstDuplicateKey()
            ?.let { FvpConditionPending(problem, FvpConditionFieldTarget.CONDITION_KEY, it.draftKey) }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.INVALID_ADDED_LOAD ->
        conditions.firstConditionWhere { !fvpAddedLoadUsable(it.addedLoadKg) }
            ?.let { FvpConditionPending(problem, FvpConditionFieldTarget.ADDED_LOAD, it.draftKey) }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.DUPLICATE_LOAD ->
        conditions.firstDuplicateLoad()
            ?.let { FvpConditionPending(problem, FvpConditionFieldTarget.ADDED_LOAD, it.draftKey) }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.MISSING_PUSH_OFF, FvpEligibilityProblem.INVALID_PUSH_OFF ->
        // En modo COMMON el campo efectivo vive en el paso 1: aqui no hay un control al que llevar
        // sin salir de la pantalla, de modo que el motivo se muestra sin accion de revision.
        if (pushOffMode == FvpPushOffUiMode.PER_CONDITION) {
            val offending = conditions.firstConditionWhere { condition ->
                if (problem == FvpEligibilityProblem.MISSING_PUSH_OFF) {
                    condition.pushOffM == null
                } else {
                    condition.pushOffM != null && !fvpPushOffUsable(condition.pushOffM)
                }
            }
            offending?.let {
                FvpConditionPending(problem, FvpConditionFieldTarget.PUSH_OFF, it.draftKey)
            } ?: FvpConditionPending(problem)
        } else {
            FvpConditionPending(problem)
        }

    FvpEligibilityProblem.CONDITION_WITHOUT_VALID_ATTEMPT ->
        conditions.firstConditionWhere { condition -> condition.attempts.none { it.valid } }
            ?.let {
                FvpConditionPending(
                    problem = problem,
                    target = FvpConditionFieldTarget.ATTEMPT_LIST,
                    conditionDraftKey = it.draftKey,
                )
            }
            ?: FvpConditionPending(problem)

    FvpEligibilityProblem.TOO_FEW_CONDITIONS ->
        // El destino es el boton existente "Anadir condicion", siempre visible en el pie.
        FvpConditionPending(problem, FvpConditionFieldTarget.ADD_CONDITION)

    // Alturas identicas: no hay UN campo culpable (todas participan). La orientacion "revisa las
    // mediciones originales, no cambies valores para obtener un ajuste" acompana al motivo; no se
    // desplaza a un campo que sugeriria tocar una medicion concreta.
    FvpEligibilityProblem.IDENTICAL_SELECTED_HEIGHTS,
    FvpEligibilityProblem.INVALID_BODY_MASS -> FvpConditionPending(problem)
}

private inline fun List<FvpConditionDraft>.firstConditionWhere(
    predicate: (FvpConditionDraft) -> Boolean,
): FvpConditionDraft? = firstOrNull(predicate)

private inline fun List<FvpConditionDraft>.firstAttemptWhere(
    predicate: (FvpAttemptDraft) -> Boolean,
): Pair<FvpConditionDraft, FvpAttemptDraft>? {
    for (condition in this) {
        val attempt = condition.attempts.firstOrNull(predicate)
        if (attempt != null) return condition to attempt
    }
    return null
}

/** Primera condicion cuya clave aparece mas de una vez (comparacion exacta, como el dominio). */
internal fun List<FvpConditionDraft>.firstDuplicateKey(): FvpConditionDraft? {
    val counts = groupingBy { it.conditionKey }.eachCount()
    return firstOrNull { counts[it.conditionKey]?.let { count -> count > 1 } == true }
}

/** Primera condicion cuya carga finita aparece mas de una vez (misma regla que el dominio). */
internal fun List<FvpConditionDraft>.firstDuplicateLoad(): FvpConditionDraft? {
    val finiteLoads = mapNotNull { it.addedLoadKg?.takeIf { load -> load.isFinite() } }
    val counts = finiteLoads.groupingBy { it }.eachCount()
    return firstOrNull { condition ->
        condition.addedLoadKg
            ?.takeIf { load -> load.isFinite() }
            ?.let { load -> counts[load]?.let { count -> count > 1 } == true } == true
    }
}

/**
 * Anclas de desplazamiento de la pantalla: se piden por clave estable al componer cada campo y se
 * olvidan al desaparecer su condicion. No piden foco ni abren el teclado (solo desplazan).
 */
internal class FvpConditionAnchors {
    private val requesters = mutableMapOf<String, BringIntoViewRequester>()

    /** Ancla de [key], creada la primera vez y reutilizada en las recomposiciones. */
    fun requester(key: String): BringIntoViewRequester =
        requesters.getOrPut(key) { BringIntoViewRequester() }

    /** Ancla del destino de [pending], o `null` si ese destino no esta compuesto. */
    fun requesterFor(pending: FvpConditionPending): BringIntoViewRequester? =
        pending.anchorKey?.let { requesters[it] }

    /** Olvida todas las anclas de una condicion (y de sus intentos) al salir de composicion. */
    fun forget(conditionDraftKey: String) {
        val prefixes = listOf("cond:$conditionDraftKey:", "attempt:$conditionDraftKey:")
        requesters.keys
            .filter { key -> prefixes.any { prefix -> key.startsWith(prefix) } }
            .forEach(requesters::remove)
    }
}
