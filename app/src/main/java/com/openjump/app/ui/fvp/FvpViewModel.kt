package com.openjump.app.ui.fvp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.FvpAssessmentSummary
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.ReusableJumpMeasurement
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.data.FVP_METHOD_VERSION
import com.openjump.app.data.evaluateSeries
import com.openjump.app.data.FvpAttemptDraft as PersistAttemptDraft
import com.openjump.app.data.FvpConditionDraft as PersistConditionDraft
import com.openjump.app.data.FvpDraft as PersistDraft
import com.openjump.app.fvp.FvpAttemptDraft as EligibilityAttemptDraft
import com.openjump.app.fvp.FvpConditionDraft as EligibilityConditionDraft
import com.openjump.app.fvp.FvpEligibility
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpSeriesInput
import com.openjump.app.fvp.selectedValidAttemptIndex
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Clave de protocolo del flujo F-V-P por defecto (SJ canonico; CMJ es condicional). */
internal const val FVP_SJ_PROTOCOL_KEY = "SJ"

/** Numero maximo de saltos reutilizables ofrecidos en el selector (ventana acotada). */
internal const val FVP_REUSABLE_LIMIT = 20

/** Estado de guardado del resultado F-V-P (mismo patron que `ResultViewModel`). */
sealed interface FvpSaveState {
    data object Idle : FvpSaveState
    data object Saving : FvpSaveState
    data object Saved : FvpSaveState

    /**
     * El guardado NO ocurrio. Es un `data object` SIN payload a proposito: ningun mensaje tecnico
     * (Kotlin/SQLite/DAO) puede llegar a la pantalla. La UI muestra el titulo localizado
     * `fvp_save_error` con cuerpo vacio, igual que `fvp_delete_error`.
     */
    data object Error : FvpSaveState
}

/**
 * Estado del borrado de una evaluacion guardada (mismo patron que [FvpSaveState]).
 *
 * [Error] significa que el borrado NO ocurrio (la fila no existia o la persistencia fallo): la
 * pantalla debe quedarse donde esta y mostrar `fvp_delete_error`, nunca navegar como si hubiera
 * borrado.
 *
 * [Deleting] es un estado NO interrumpible desde la UI: la pantalla de resultado bloquea la salida
 * (Back consumido, flecha ignorada, Editar deshabilitado) hasta que el estado pase a [Deleted]
 * (navegacion via `onDeleted`) o a [Error] (`fvp_delete_error` visible). Si se dejara salir, el
 * ViewModel de destino se destruiria y la corrutina se cancelaria sin mostrar el resultado.
 */
sealed interface FvpDeleteState {
    data object Idle : FvpDeleteState
    data object Deleting : FvpDeleteState
    data object Deleted : FvpDeleteState
    data object Error : FvpDeleteState
}

/**
 * Elegibilidad + resultado de un borrador vivo. `result` es null si la serie no es calculable.
 *
 * Es la proyeccion publica del camino unico de evaluacion
 * ([com.openjump.app.data.evaluateSeries]); no anade ningun criterio propio.
 */
data class FvpLivePresentation(
    val eligibility: FvpEligibility,
    val result: FvpResult?,
) {
    val canCompute: Boolean get() = eligibility.canCompute && result != null
}

/**
 * ViewModel del flujo F-V-P. No duplica matematicas ni orquestacion: delega el calculo en
 * [evaluateSeries] (el mismo camino que `FvpRepository.save`) y el borrado en el repositorio.
 */
class FvpViewModel internal constructor(
    private val repository: FvpRepository,
    private val jumpRepository: JumpRepository,
) : ViewModel() {

    private val _saveState = MutableStateFlow<FvpSaveState>(FvpSaveState.Idle)
    val saveState: StateFlow<FvpSaveState> = _saveState.asStateFlow()

    private val _deleteState = MutableStateFlow<FvpDeleteState>(FvpDeleteState.Idle)
    val deleteState: StateFlow<FvpDeleteState> = _deleteState.asStateFlow()

    /**
     * Calcula el perfil de un borrador vivo sin persistir nada. Delega en el camino UNICO de
     * evaluacion ([com.openjump.app.data.evaluateSeries]) que tambien usa `FvpRepository.save`:
     * misma elegibilidad, misma seleccion canonica de intento y mismos puntos/perfil. Si la serie
     * esta bloqueada, el motor no se llama y `result` es null (nunca un valor por defecto).
     */
    fun compute(draft: FvpDraft): FvpLivePresentation {
        val outcome = evaluateSeries(
            seriesInput = draft.toSeriesInput(),
            evaluationId = draft.sessionKey,
            provenance = FVP_UI_PROVENANCE,
            sourceFps = draft.seriesSourceFps(),
        )
        return FvpLivePresentation(eligibility = outcome.eligibility, result = outcome.result)
    }

    /**
     * Persiste el borrador. La serie debe ser calculable; `FvpRepository.save` vuelve a validar la
     * elegibilidad y lanza si no lo es (nunca se guarda un valor por defecto silencioso).
     *
     * La orquestacion vive en [fvpPersistWithState] (que jamas expone el mensaje de una excepcion).
     * La guarda anti-doble-invocacion (Saving/Saved) es INCONDICIONAL: el estado se marca aqui,
     * ANTES de lanzar la corrutina, de modo que no depende del dispatcher ni del hilo llamante
     * (un segundo toque, o una llamada desde otro hilo, ya ve `Saving`). El helper vuelve a emitir
     * `Saving` dentro de la corrutina; al ser un `data object` igual, el `StateFlow` lo deduplica.
     */
    fun save(draft: FvpDraft, onSaved: (Long) -> Unit) {
        if (_saveState.value == FvpSaveState.Saving || _saveState.value == FvpSaveState.Saved) return
        val persistable = draft.toPersistDraftOrNull()
        if (persistable == null) {
            // Sin masa corporal o sin h_po no hay nada persistible: fallo visible e inmediato, sin
            // llegar a lanzar la corrutina (mismo contrato que el helper para el caso `null`).
            _saveState.value = FvpSaveState.Error
            return
        }
        _saveState.value = FvpSaveState.Saving
        viewModelScope.launch {
            fvpPersistWithState(
                persistable = persistable,
                save = { repository.save(it) },
                onState = { _saveState.value = it },
                onSaved = onSaved,
            )
        }
    }

    fun clearError() {
        if (_saveState.value is FvpSaveState.Error) _saveState.value = FvpSaveState.Idle
    }

    fun historyFor(athleteId: Long): Flow<List<FvpAssessmentSummary>> = repository.forAthlete(athleteId)

    suspend fun stored(id: Long): StoredFvpAssessment? = repository.byId(id)

    /**
     * Borra una evaluacion guardada dentro del ambito del ViewModel (no en el de la pantalla, que
     * se cancela al salir de composicion) y avisa con [onDeleted] SOLO si el borrado ocurrio de
     * verdad. Si la fila no existia o la persistencia falla, el estado queda en
     * [FvpDeleteState.Error]: la pantalla no navega, muestra `fvp_delete_error` y no se propaga
     * ningun mensaje tecnico de la excepcion.
     *
     * La orquestacion vive en [fvpDeleteWithState] (una cancelacion del scope se propaga en vez de
     * convertirse en un fallo). La guarda anti-doble-invocacion es INCONDICIONAL: el estado se marca
     * aqui, ANTES de lanzar la corrutina, asi que no depende del dispatcher ni del hilo llamante, y
     * el bloqueo de salida de la pantalla ve `Deleting` desde el primer momento.
     *
     * Mientras el estado sea [FvpDeleteState.Deleting] la pantalla de resultado bloquea la salida
     * (Back del sistema y flecha de la barra, Editar deshabilitado): la operacion siempre termina en
     * un estado VISIBLE ([FvpDeleteState.Deleted] con `onDeleted`, o [FvpDeleteState.Error] con
     * `fvp_delete_error`) y no se cancela por un pop del destino.
     */
    fun delete(id: Long, onDeleted: () -> Unit) {
        if (_deleteState.value == FvpDeleteState.Deleting || _deleteState.value == FvpDeleteState.Deleted) return
        _deleteState.value = FvpDeleteState.Deleting
        viewModelScope.launch {
            fvpDeleteWithState(
                delete = { repository.delete(id) },
                onState = { _deleteState.value = it },
                onDeleted = onDeleted,
            )
        }
    }

    /** Saltos SJ guardados reutilizables como medicion de una condicion (ventana acotada). */
    suspend fun reusableMeasurements(
        athleteId: Long,
        limit: Int = FVP_REUSABLE_LIMIT,
    ): List<ReusableJumpMeasurement> =
        jumpRepository.reusableMeasurementsFor(athleteId, FVP_SJ_PROTOCOL_KEY, limit)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                FvpViewModel(app.fvpRepository, app.repository)
            }
        }
    }
}

/** Resultado de un intento de guardado: solo exito o fallo visible, nunca un detalle tecnico. */
internal sealed interface FvpSaveOutcome {
    data object Saved : FvpSaveOutcome
    data object Failed : FvpSaveOutcome
}

/**
 * Orquestacion PURA de un guardado (suspend, sin Android ni dispatchers): la usan `FvpViewModel.save`
 * y los tests JVM con `runBlocking`. Reglas vinculantes:
 *
 * - `persistable == null` (falta masa corporal o h_po): [FvpSaveState.Error] y el saver NO se llama.
 * - exito: [FvpSaveState.Saving] -> [FvpSaveState.Saved] -> `onSaved(id)` UNA sola vez.
 * - cualquier otra excepcion: [FvpSaveState.Error], sin `onSaved` y sin exponer el mensaje.
 * - [CancellationException]: se RE-LANZA (cancelar no es fallar) y nunca se convierte en Error.
 *
 * REINTENTO SEGURO: el helper no toca `sessionKey`; el repositorio es idempotente por esa clave, de
 * modo que reintentar tras un fallo no duplica la fila y `onSaved` sigue disparandose una sola vez.
 */
internal suspend fun fvpPersistWithState(
    persistable: PersistDraft?,
    save: suspend (PersistDraft) -> Long,
    onState: (FvpSaveState) -> Unit,
    onSaved: (Long) -> Unit,
): FvpSaveOutcome {
    if (persistable == null) {
        onState(FvpSaveState.Error)
        return FvpSaveOutcome.Failed
    }
    onState(FvpSaveState.Saving)
    val id = try {
        save(persistable)
    } catch (cancellation: CancellationException) {
        // Cancelacion del scope (ViewModel destruido), no un fallo de persistencia.
        throw cancellation
    } catch (_: Exception) {
        // El mensaje tecnico NO se guarda ni se pinta: la pantalla usa `fvp_save_error` con cuerpo vacio.
        onState(FvpSaveState.Error)
        return FvpSaveOutcome.Failed
    }
    onState(FvpSaveState.Saved)
    onSaved(id)
    return FvpSaveOutcome.Saved
}

/**
 * Orquestacion PURA de un borrado (suspend, sin Android ni dispatchers), espejo de
 * [fvpPersistWithState]. Reglas vinculantes:
 *
 * - emite [FvpDeleteState.Deleting] antes de llamar al repositorio.
 * - `delete()` devuelve `true`: [FvpDeleteState.Deleted] y `onDeleted()` UNA sola vez; el resto, fallo.
 * - `false` o excepcion: [FvpDeleteState.Error], sin `onDeleted` y sin exponer el mensaje.
 * - [CancellationException]: se RE-LANZA y nunca se convierte en Error.
 *
 * Devuelve si el borrado ocurrio de verdad (misma semantica que `FvpRepository.delete`).
 */
internal suspend fun fvpDeleteWithState(
    delete: suspend () -> Boolean,
    onState: (FvpDeleteState) -> Unit,
    onDeleted: () -> Unit,
): Boolean {
    onState(FvpDeleteState.Deleting)
    val deleted = try {
        delete()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        onState(FvpDeleteState.Error)
        return false
    }
    if (!deleted) {
        // La fila no existia (o la persistencia fallo): no se navega, la pantalla muestra `fvp_delete_error`.
        onState(FvpDeleteState.Error)
        return false
    }
    onState(FvpDeleteState.Deleted)
    onDeleted()
    return true
}

private const val FVP_UI_PROVENANCE = "fvp-ui"

/**
 * FPS de origen declarado de la serie: el PEOR fps entre los intentos SELECCIONADOS (uno por
 * condicion, el mejor intento valido: la misma regla que la capa de elegibilidad), o `null` si
 * ninguno declara fps.
 *
 * Gate B6/P1-2: los intentos DESCARTADOS (invalidos o no seleccionados) NO pueden contaminar la
 * serie; un intento a 30 fps marcado como no valido no debe estampar `FPS_BLOCKED` en un perfil
 * cuyos intentos seleccionados van a >= 120 fps.
 */
internal fun FvpDraft.seriesSourceFps(): Double? =
    selectedAttemptsByCondition().values.mapNotNull { it.sourceFps }
        .filter { it.isFinite() }
        .minOrNull()

/**
 * Borrador de persistencia equivalente, o `null` si falta un dato imprescindible (masa corporal o
 * h_po de referencia). Los intentos/filas incompletos se mapean con NaN: la capa de elegibilidad
 * los rechaza de forma explicita (`INVALID_HEIGHT`/`INVALID_ADDED_LOAD`), nunca se inventa un valor.
 *
 * Los 20 campos de PROCEDENCIA (B1b-2a) se copian 1:1 desde el borrador de UI: `UNKNOWN`/`null`
 * viaja tal cual y NUNCA se deriva de fps, de la fecha de guardado ni del perfil. La fecha de la
 * fila sigue siendo la de la nueva creacion ([PersistDraft.dateTime]); no es una fecha de
 * adquisicion y no alimenta ninguna columna de procedencia.
 */
internal fun FvpDraft.toPersistDraftOrNull(): PersistDraft? {
    val mass = bodyMassKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val pushOff = referencePushOffM?.takeIf { it.isFinite() } ?: return null
    return PersistDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        protocol = protocol.name,
        dateTime = System.currentTimeMillis(),
        bodyMassKg = mass,
        bodyMassSource = bodyMassSource,
        pushOffMode = pushOffMode.storageKey,
        pushOffM = pushOff,
        pushOffSource = pushOffSource,
        pushOffLandmark = pushOffLandmark.trim().takeIf { it.isNotEmpty() },
        // Altura del hito: columna persistida SIN campo en el paso 1; se conserva tal cual (D2).
        pushOffLandmarkHeightCm = pushOffLandmarkHeightCm,
        pushOffProtocol = pushOffProtocol.trim().takeIf { it.isNotEmpty() },
        pushOffMeasuredAt = pushOffMeasuredAt,
        pushOffCrouchCm = pushOffCrouchCm,
        pushOffExtendedCm = pushOffExtendedCm,
        sourceFps = seriesSourceFps(),
        methodVersion = FVP_METHOD_VERSION,
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        // Procedencia de adquisicion (B1b-2a): copia 1:1, sin derivar ni rellenar.
        acquiredAt = acquiredAt,
        acquisitionContextKind = acquisitionContextKind,
        acquisitionContextKey = acquisitionContextKey,
        bodyMassMeasurement = bodyMassMeasurement,
        bodyMassMeasuredAt = bodyMassMeasuredAt,
        conditions = conditions.map { condition ->
            PersistConditionDraft(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg ?: Double.NaN,
                pushOffM = condition.pushOffM,
                attempts = condition.attempts.map { attempt ->
                    PersistAttemptDraft(
                        heightM = attempt.heightM ?: Double.NaN,
                        method = attempt.method.name,
                        flightTimeS = attempt.flightTimeS,
                        sourceFps = attempt.sourceFps,
                        sourceAssessmentId = attempt.sourceAssessmentId,
                        sourceAttemptOrdinal = attempt.sourceAttemptOrdinal,
                        sourceProtocolId = attempt.sourceProtocolId,
                        valid = attempt.valid,
                        exclusionReason = attempt.exclusionReason?.trim()?.takeIf { it.isNotEmpty() },
                        notes = attempt.notes?.trim()?.takeIf { it.isNotEmpty() },
                        // Procedencia de origen/reutilizacion y PTS (B1b-2a): copia 1:1.
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
                },
                // Procedencia de la carga anadida (B1b-2a): copia 1:1.
                addedLoadSource = condition.addedLoadSource,
                addedLoadMeasuredAt = condition.addedLoadMeasuredAt,
            )
        },
    )
}

/** Entrada de la capa de elegibilidad equivalente al borrador de UI. */
internal fun FvpDraft.toSeriesInput(): FvpSeriesInput = FvpSeriesInput(
    protocol = protocol,
    bodyMassKg = bodyMassKg ?: Double.NaN,
    pushOffMode = pushOffMode.toEligibilityMode(),
    commonPushOffM = commonPushOffM,
    conditions = conditions.map { condition ->
        EligibilityConditionDraft(
            conditionKey = condition.conditionKey,
            addedLoadKg = condition.addedLoadKg ?: Double.NaN,
            pushOffM = condition.pushOffM,
            attempts = condition.attempts.map { attempt ->
                EligibilityAttemptDraft(
                    heightM = attempt.heightM ?: Double.NaN,
                    method = attempt.method,
                    sourceFps = attempt.sourceFps,
                    valid = attempt.valid,
                    exclusionReason = attempt.exclusionReason,
                )
            },
        )
    },
)

/**
 * Intento seleccionado por condicion para la tabla de puntos: PROYECCION del intento elegido por
 * la REGLA CANONICA de la capa de elegibilidad ([com.openjump.app.fvp.selectedValidAttemptIndex]):
 * mayor altura entre los intentos VALIDOS y, ante empate, el primero. Este mapa no define ninguna
 * regla propia; si hay intentos validos pero ninguno con altura utilizable, no hay punto publicable
 * y la serie queda bloqueada.
 */
internal fun FvpDraft.selectedAttemptsByCondition(): Map<String, FvpAttemptUi> {
    // La seleccion se aplica sobre la serie de elegibilidad equivalente (mismo orden, 1:1): asi la
    // proyeccion no puede divergir de la regla canonica ni de la eleccion real del motor.
    val seriesInput = toSeriesInput()
    return conditions.mapIndexed { index, condition ->
        val selectedIndex = seriesInput.selectedValidAttemptIndex(seriesInput.conditions[index])
        val selected = selectedIndex?.let { condition.attempts[it] }
        condition.conditionKey to FvpAttemptUi(
            heightM = selected?.heightM ?: condition.attempts.firstOrNull()?.heightM,
            method = selected?.method ?: condition.attempts.firstOrNull()?.method
                ?: FvpMeasureMethod.FLIGHT_TIME_VIDEO,
            sourceFps = selected?.sourceFps,
            // Sin intento valido usable no hay punto publicable: la serie queda bloqueada.
            valid = selected != null,
            exclusionReason = selected?.exclusionReason,
        )
    }.toMap()
}

/**
 * Intento seleccionado por condicion de una evaluacion guardada, para presentar la misma tabla de
 * puntos que en vivo. Es una PROYECCION PURA de las filas persistidas: usa el ordinal que ya se
 * eligio con la regla canonica al guardar (`FvpConditionEntity.selectedAttemptOrdinal`) y NO
 * vuelve a aplicar ningun criterio de seleccion.
 */
internal fun StoredFvpAssessment.selectedAttemptsByCondition(): Map<String, FvpAttemptUi> =
    conditions.associate { condition ->
        val attempts = attempts.filter { it.conditionId == condition.id }.sortedBy { it.ordinal }
        val selected = condition.selectedAttemptOrdinal
            ?.let { ordinal -> attempts.firstOrNull { it.ordinal == ordinal } }
        condition.conditionKey to
            FvpAttemptUi(
                heightM = selected?.heightM,
                method = FvpEngine.parseMethod(selected?.method) ?: FvpMeasureMethod.FLIGHT_TIME_VIDEO,
                sourceFps = selected?.sourceFps,
                valid = selected?.valid ?: false,
                exclusionReason = selected?.exclusionReason,
            )
    }
