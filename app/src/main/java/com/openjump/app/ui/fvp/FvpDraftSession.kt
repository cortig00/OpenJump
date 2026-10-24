package com.openjump.app.ui.fvp

import com.openjump.app.data.FVP_REUSE_CONFIRMATION_VERSION
import com.openjump.app.data.ReusableJumpMeasurement
import com.openjump.app.data.StoredFvpAssessment
import com.openjump.app.fvp.FvpEngine
import com.openjump.app.fvp.FvpPushOffMode
import com.openjump.fvp.FvpMeasureMethod
import com.openjump.fvp.FvpProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Modo de agregacion de h_po en el borrador de UI. Las claves de almacenamiento son las mismas que
 * usa la capa de datos (`fvp_assessments.pushOffMode`), nunca una copia divergente.
 */
enum class FvpPushOffUiMode(val storageKey: String) {
    COMMON("COMMON"),
    PER_CONDITION("PER_CONDITION"),
    ;

    companion object {
        fun fromStorageKey(key: String?): FvpPushOffUiMode? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/** Modo de UI equivalente al de la capa de elegibilidad. */
fun FvpPushOffUiMode.toEligibilityMode(): FvpPushOffMode = when (this) {
    FvpPushOffUiMode.COMMON -> FvpPushOffMode.COMMON
    FvpPushOffUiMode.PER_CONDITION -> FvpPushOffMode.PER_CONDITION
}

/**
 * Intento del borrador de UI. `heightM` es nullable: una fila vacia se mapea a la capa de
 * elegibilidad como altura inutilizable (bloqueo explicito), nunca como altura inventada.
 *
 * [flightTimeS] y [notes] son columnas persistidas (`fvp_attempts`) que tambien se exportan: sin
 * ellos en el borrador, volver a guardar una serie duplicada las borraba en silencio (decision D2
 * del plan 13: duplicar y editar es una COPIA FIEL).
 *
 * [draftKey] es una identidad SOLO en memoria (claves de Compose y estado de texto); no se persiste.
 */
data class FvpAttemptDraft(
    val draftKey: String = UUID.randomUUID().toString(),
    val heightM: Double? = null,
    val method: FvpMeasureMethod = FvpMeasureMethod.FLIGHT_TIME_VIDEO,
    val flightTimeS: Double? = null,
    val sourceFps: Double? = null,
    val sourceAssessmentId: Long? = null,
    val sourceAttemptOrdinal: Int? = null,
    val sourceProtocolId: String? = null,
    val valid: Boolean = true,
    val exclusionReason: String? = null,
    val notes: String? = null,
    // Procedencia de origen/reutilizacion y snapshots temporales (B0/B1b-2a): los MISMOS nombres y
    // defaults que el borrador de persistencia. NULL/UNKNOWN = desconocido y NUNCA se infiere (ni
    // de los fps, ni de fechas cercanas, ni de la sola existencia de un PTS).
    /** Fecha en que el intento de ORIGEN quedo registrado en su sistema (epoch millis); null =
     *  desconocida. Snapshot informativo: no prueba captura ni adquisicion. */
    val sourceRecordedAt: Long? = null,
    /** Atleta dueno del intento de origen (snapshot informativo sin FK); null = desconocido. */
    val sourceAthleteId: Long? = null,
    /** Momento en que se adquirio ESTE intento (epoch millis); null = desconocido. Nunca se copia
     *  [sourceRecordedAt] aqui como si fuera una captura. */
    val acquiredAt: Long? = null,
    /** Momento en que el usuario confirmo la reutilizacion del salto; null = sin confirmacion. */
    val reuseConfirmedAt: Long? = null,
    /** Version del protocolo de confirmacion (si se informa, `fvp-reuse-1`); null = sin confirmacion. */
    val reuseConfirmationVersion: String? = null,
    /** PTS [us] del despegue en el timeline original del medio; null = desconocido. */
    val takeoffPtsUs: Long? = null,
    /** PTS [us] inmediatamente anterior al despegue; null = desconocido (jamas derivado de fps). */
    val takeoffPreviousPtsUs: Long? = null,
    /** PTS [us] inmediatamente posterior al despegue; null = desconocido. */
    val takeoffNextPtsUs: Long? = null,
    /** PTS [us] del aterrizaje; null = desconocido. */
    val landingPtsUs: Long? = null,
    /** PTS [us] inmediatamente anterior al aterrizaje; null = desconocido. */
    val landingPreviousPtsUs: Long? = null,
    /** PTS [us] inmediatamente posterior al aterrizaje; null = desconocido. */
    val landingNextPtsUs: Long? = null,
    /** Mapeo temporal aplicado al importar: "UNKNOWN" | "IDENTITY_VERIFIED". */
    val timeMapping: String = "UNKNOWN",
    /** Clave opaca de la evidencia que justifica el mapeo temporal; null = desconocida. */
    val timeMappingEvidenceKey: String? = null,
)

/** Condicion de carga del borrador de UI (misma forma que la capa de datos + [draftKey]). */
data class FvpConditionDraft(
    val draftKey: String = UUID.randomUUID().toString(),
    val conditionKey: String = "",
    val addedLoadKg: Double? = null,
    val pushOffM: Double? = null,
    val attempts: List<FvpAttemptDraft> = emptyList(),
    // Procedencia de la carga anadida (B0/B1b-2a): mismos nombres/defaults que persistencia.
    /** Como se obtuvo la carga anadida: "UNKNOWN" | "WEIGHED" | "NOMINAL". `UNKNOWN` no se
     *  promueve por existir un valor numerico de carga. */
    val addedLoadSource: String = "UNKNOWN",
    /** Fecha de la medida de la carga anadida (epoch millis); null = desconocida. */
    val addedLoadMeasuredAt: Long? = null,
)

/**
 * Borrador en memoria de una serie F-V-P (mismo contrato que el borrador de persistencia, con los
 * campos de UI que no se guardan: [bodyMassFromProfile] y las claves de borrador).
 *
 * Decisiones vinculantes del contrato:
 * - h_po SIEMPRE declarado por el usuario; nunca derivado de la estatura (no existe campo de talla).
 * - la masa corporal editada desde el perfil es una SUGERENCIA: si no se toca queda como
 *   procedencia `PROFILE`, en cuanto se edita pasa a `MANUAL`.
 * - los 20 campos de PROCEDENCIA (adquisicion, pesaje de masa, carga anadida y origen/PTS de los
 *   intentos) viajan 1:1 en los dos sentidos ([storedFvpToDraft] y `toPersistDraftOrNull`) y jamas
 *   se derivan de fps, fechas cercanas ni perfiles: `UNKNOWN`/`null` sigue siendo desconocido.
 */
data class FvpDraft(
    val sessionKey: String = UUID.randomUUID().toString(),
    val athleteId: Long = 0L,
    val protocol: FvpProtocol = FvpProtocol.SJ,
    val bodyMassKg: Double? = null,
    val bodyMassFromProfile: Boolean = false,
    val pushOffMode: FvpPushOffUiMode = FvpPushOffUiMode.COMMON,
    val commonPushOffM: Double? = null,
    val pushOffLandmark: String = "",
    /**
     * Altura del hito anatomico [cm] (`fvp_assessments.pushOffLandmarkHeightCm`): columna
     * PERSISTIDA, exportada y respaldada. Sin campo en el borrador de UI, volver a guardar una
     * serie que la llevaba la borraba en silencio (misma clase de defecto que [flightTimeS]/
     * [notes] de los intentos: decision D2 del plan 13).
     */
    val pushOffLandmarkHeightCm: Double? = null,
    val pushOffProtocol: String = "",
    val pushOffMeasuredAt: Long? = null,
    val pushOffCrouchCm: Double? = null,
    val pushOffExtendedCm: Double? = null,
    // Nota: NO existe un `sourceFps` de serie en el borrador. Los fps de origen de la serie se
    // derivan SIEMPRE de los intentos SELECCIONADOS (`seriesSourceFps()`), la misma regla que la
    // capa de elegibilidad; un campo aparte permitia contaminar la serie con intentos descartados.
    val conditions: List<FvpConditionDraft> = emptyList(),
    val notes: String? = null,
    // Procedencia de adquisicion (B0/B1b-2a): los MISMOS nombres y defaults que el borrador de
    // persistencia. NULL/UNKNOWN = desconocido: el borrador NUNCA rellena estos campos (ni con la
    // fecha del test, ni con la hora del guardado, ni por perfil).
    /** Momento en que se ADQUIRIO la evaluacion (epoch millis); null = desconocido. Es distinto de
     *  la fecha de la SERIE: declarar una fecha de test no prueba que la adquisicion ocurriera
     *  entonces. */
    val acquiredAt: Long? = null,
    /** Contexto de adquisicion declarado: "UNKNOWN" | "SAME_ACQUISITION" | "HISTORICAL_COMPOSITE".
     *  `UNKNOWN` nunca se infiere por cercania de fechas. */
    val acquisitionContextKind: String = "UNKNOWN",
    /** Clave opaca del contexto declarado (<=128 caracteres, no vacia); null = desconocida. */
    val acquisitionContextKey: String? = null,
    /** Como se obtuvo la masa corporal: "UNKNOWN" | "WEIGHED" | "DECLARED". Es independiente de
     *  [bodyMassSource] y `UNKNOWN` no se promueve por declarar un valor. */
    val bodyMassMeasurement: String = "UNKNOWN",
    /** Fecha de la pesada/declaracion de masa (epoch millis); null = desconocida. */
    val bodyMassMeasuredAt: Long? = null,
) {
    /** Procedencia declarada de la masa corporal (claves de almacenamiento de la capa de datos). */
    val bodyMassSource: String get() = if (bodyMassFromProfile) "PROFILE" else "MANUAL"

    /**
     * h_po de referencia de la serie [m] tal como la exige la capa de datos (nunca null en una fila
     * persistible): en COMMON es el valor comun; en PER_CONDITION la primera condicion declarada.
     */
    val referencePushOffM: Double?
        get() = when (pushOffMode) {
            FvpPushOffUiMode.COMMON -> commonPushOffM
            FvpPushOffUiMode.PER_CONDITION -> conditions.firstNotNullOfOrNull { it.pushOffM }
        }

    /**
     * Procedencia de h_po. Regla B1 (addendum 09, condicion 1): `MEASURED` EXIGE hito anatomico
     * no vacio + procedimiento no vacio + fecha de medicion, y solo entonces se registra como
     * medicion; cualquier otro caso es `MANUAL` (estimacion declarada, nunca silenciosa) y la UI
     * muestra el aviso dedicado `fvp_warning_push_off_manual_source`. Las componentes
     * crouch/extension NO bastan por si solas. Nunca `ESTIMATED`/por talla
     * (`FVP_PUSH_OFF_SOURCES` en la capa de datos solo acepta `MEASURED`/`MANUAL`).
     */
    val pushOffSource: String
        get() = fvpEffectivePushOffSource(
            declaredSource = null,
            landmark = pushOffLandmark,
            procedure = pushOffProtocol,
            measuredAt = pushOffMeasuredAt,
        )
}

/**
 * Peso de perfil utilizable como SUGERENCIA de masa corporal (finito y > 0), o `null` si no sirve.
 */
internal fun fvpUsableProfileMassKg(suggestedWeightKg: Double?): Double? =
    suggestedWeightKg?.takeIf { it.isFinite() && it > 0.0 }

/**
 * Borrador vivo que pertenece a la IDENTIDAD de una serie (`athleteId` + `protocol`), o `null` si
 * no hay ningun borrador de esa identidad.
 *
 * [FvpDraftSession.draft] es un borrador vivo POR PROCESO: puede pertenecer a otro atleta o a otro
 * protocolo (p. ej. se abrio una serie guardada de A y despues se entra al paso 1 para B). Esa
 * semilla ajena no sirve para ninguna serie nueva: la pantalla del paso 1 debe sembrar TODOS sus
 * campos (y decidir la procedencia de la masa) solo con el borrador de ESTA identidad; si no lo
 * hay, la serie empieza de cero. `commitSetupDraft` empieza una serie NUEVA cuando la identidad no
 * coincide, asi que la pantalla y el commit no pueden divergir.
 *
 * `athleteId` null (sin atleta elegido) no coincide con NINGUN borrador.
 */
internal fun FvpDraft?.seriesSeedFor(athleteId: Long?, protocol: FvpProtocol): FvpDraft? =
    this?.takeIf { athleteId != null && it.athleteId == athleteId && it.protocol == protocol }

/**
 * Decision PURA de la pantalla de setup (D2): la masa del perfil se sugiere SOLO al empezar una
 * serie NUEVA de ESTA identidad ([matchingDraft] `null`), con el campo sin tocar y con un peso de
 * perfil utilizable.
 *
 * Un borrador de la misma identidad -el que abre "duplicar y editar" desde una serie guardada-
 * conserva SU masa: el peso actual del perfil no puede sustituir en silencio el snapshot guardado.
 * Un borrador de OTRA identidad no llega aqui: la pantalla pasa la semilla ya acotada por
 * [seriesSeedFor].
 */
internal fun fvpShouldSuggestProfileMass(
    matchingDraft: FvpDraft?,
    suggestedWeightKg: Double?,
    massTouchedByUser: Boolean,
): Boolean = matchingDraft == null && !massTouchedByUser && fvpUsableProfileMassKg(suggestedWeightKg) != null

/**
 * Procedencia de la masa corporal que se va a guardar cuando el campo NO se ha editado. Regla
 * determinista y honesta (tabla de verdad, en orden):
 *
 * | campo tocado | sin valor | borrador de ESTA identidad | peso del perfil elegido | resultado |
 * |---|---|---|---|---|
 * | si | - | - | - | `false` (MANUAL) |
 * | no | si | - | - | `false` (nunca `PROFILE` sin valor) |
 * | no | no | si | - | procedencia GUARDADA del borrador (D2) |
 * | no | no | no | igual al valor | `true` (el valor ES el peso del perfil) |
 * | no | no | no | distinto o `null` | `false` (MANUAL) |
 *
 * La ultima fila es la que impide una procedencia FALSA: tras el recorte por identidad, un valor
 * heredado de otra serie se trata como serie NUEVA y solo puede reclamar `PROFILE` si de verdad es
 * el peso ACTUAL del perfil del atleta elegido.
 *
 * [currentProfileMassKg] llega ya normalizado por [fvpUsableProfileMassKg] (`null` si el perfil no
 * tiene un peso utilizable); con `null` NUNCA se marca `PROFILE`. [matchingDraft] es el borrador de
 * la MISMA identidad ([seriesSeedFor]) o `null`.
 */
internal fun fvpBodyMassProvenanceIsFromProfile(
    matchingDraft: FvpDraft?,
    currentProfileMassKg: Double?,
    bodyMassKg: Double?,
    massTouchedByUser: Boolean,
): Boolean = when {
    massTouchedByUser -> false
    bodyMassKg == null -> false
    matchingDraft != null -> matchingDraft.bodyMassFromProfile
    else -> currentProfileMassKg != null && bodyMassKg == currentProfileMassKg
}

/**
 * Sesion en memoria del flujo F-V-P (mismo patron que `AppSession`): un unico borrador vivo por
 * proceso. Fuera del proceso no sobrevive nada: la fuente de verdad de lo guardado es Room.
 *
 * CONTRATO DE PROPIEDAD UNICA (plan 13, D6; R1 documento este contrato y aporto el codec puro
 * [FvpDraftStateCodec]; el dueño que lo implanta es [FvpFlowViewModel], R2):
 *
 * - UN SOLO dueño para todo el flujo F-V-P (setup, condiciones y resultado en vivo) y su estado
 *   recuperable. Prohibido un handle por destino (las copias por pantalla se vuelven obsoletas) y
 *   prohibido que este singleton sea una SEGUNDA fuente de verdad del estado recuperado: el dueño
 *   envuelve a la sesion, no al reves.
 * - la recuperacion es SOLO de FVP: NO cambia la politica de sesion transitoria de video/Encoder
 *   (`TransientSessionPolicy`), que sigue sin recuperarse.
 * - el dueño que restaure un estado debe RECONCILIARLO con lo persistido y PREFERIR la verdad
 *   persistida: el atleta de la serie puede haberse borrado o archivado desde que se guardo el
 *   payload. [FvpDraftStateCodec.decode] no revalida nada; el estado recuperado debe pasar por la
 *   MISMA ruta de elegibilidad/persistencia antes de usarse. Ojo: `FvpRepository.save` es idempotente
 *   por `sessionKey`, pero eso NO basta - si ya hay una fila con ese `sessionKey` bajo OTRO atleta,
 *   `save` lanza por conflicto en vez de duplicar-, de modo que la reconciliacion decide ANTES de
 *   adoptar: fila con ese `sessionKey` -> la verdad persistida manda (si es del mismo dueño, la serie
 *   ya esta guardada; si es de otro, el `save` es imposible), atleta ausente/archivado -> sesion no
 *   recuperable, y solo sin fila y con atleta activo se adopta
 *   ([com.openjump.app.ui.fvp.fvpRecoveredStateReconciliation], R3). Nunca se adopta un borrador que
 *   la verdad persistida prohibe: asi ninguna pantalla puede llamar a `save` con esa identidad.
 * - el alcance es lo que Android conserve (SavedStateHandle): NO se promete recuperacion tras
 *   force-stop, eliminacion de tarea ni reinicio. La mitad Android se ejecuto en el AVD autorizado:
 *   registry real y muerte normal por background/am kill/Recientes con identidad interna conservada
 *   (checkpoints19/20 en docs/research/fvp). Eso no prueba todas las ramas bajo muerte real.
 *   Su protocolo esta documentado en [com.openjump.app.ui.fvp.FvpFlowViewModel]. Se miden DOS
 *   presupuestos SEPARADOS: JSON recuperable (<= 65 536 bytes UTF-8, cota de
 *   [FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES]) y estado AGREGADO de Activity (`Bundle`
 *   parcelado, <= 262 144 bytes). Son presupuestos de ingenieria medidos en escenarios concretos,
 *   no una afirmacion de seguridad universal de Binder.
 */

/**
 * Contrato de MUTACION del borrador vivo del flujo F-V-P (A2). Es lo que los destinos del flujo
 * usan para TOCAR el borrador, de modo que ninguna pantalla escribe el singleton por su cuenta.
 *
 * En produccion el objetivo es SIEMPRE el dueño [FvpFlowViewModel], que ademas supersede de forma
 * SINCRONA la reconciliacion pendiente y la recuperabilidad antes de aplicar el cambio (A2/A3).
 * `FvpDraftSession` implementa el MISMO contrato como FALLBACK de sesion aislada (montajes de
 * pantalla sin dueño, p. ej. tests): en ese caso no hay reconciliacion ni payload que superseder.
 * Prohibido crear un segundo dueño: el fallback solo se usa cuando NO hay dueño.
 *
 * [resetIfSessionKey] es el guard de una salida terminal ASINCRONA (guardado confirmado): solo
 * descarta el borrador si el vivo es TODAVIA el que se guardo, de modo que la confirmacion de la
 * serie A no puede descartar ni desviar la serie B que el usuario haya empezado mientras tanto.
 */
interface FvpDraftMutations {

    /** Crea un borrador nuevo (o reemplaza el vivo) para el atleta/protocolo indicados. */
    fun begin(athleteId: Long, protocol: FvpProtocol = FvpProtocol.SJ): FvpDraft

    /** Actualiza el borrador vivo; no hace nada (y devuelve null) si no hay sesion viva. */
    fun update(transform: (FvpDraft) -> FvpDraft): FvpDraft?

    /** Descarta el borrador (salida terminal del flujo: guardado, borrado o vuelta a Home). */
    fun reset()

    /** Instala como sesion viva un borrador ya construido (p. ej. editar una evaluacion guardada). */
    fun adopt(draft: FvpDraft): FvpDraft

    /**
     * Descarta el borrador SOLO si el vivo pertenece todavia a [sessionKey]; `false` si el vivo es
     * de otra serie (o no hay ninguno), en cuyo caso NO se toca nada.
     */
    fun resetIfSessionKey(sessionKey: String): Boolean
}

object FvpDraftSession : FvpDraftMutations {

    private val _draft = MutableStateFlow<FvpDraft?>(null)
    val draft: StateFlow<FvpDraft?> = _draft.asStateFlow()

    override fun begin(athleteId: Long, protocol: FvpProtocol): FvpDraft {
        val draft = FvpDraft(athleteId = athleteId, protocol = protocol)
        _draft.value = draft
        return draft
    }

    override fun update(transform: (FvpDraft) -> FvpDraft): FvpDraft? {
        var updated: FvpDraft? = null
        _draft.update { current ->
            if (current == null) {
                null
            } else {
                transform(current).also { updated = it }
            }
        }
        return updated
    }

    override fun reset() {
        _draft.value = null
    }

    override fun adopt(draft: FvpDraft): FvpDraft {
        _draft.value = draft
        return draft
    }

    override fun resetIfSessionKey(sessionKey: String): Boolean {
        if (_draft.value?.sessionKey != sessionKey) return false
        reset()
        return true
    }

    fun isLive(): Boolean = _draft.value != null
}

/**
 * Guard del flujo F-V-P (mismo patron que `TransientSessionPolicy` para el flujo de salto):
 * condiciones/resultado en vivo solo se abren con un borrador en memoria.
 */
object FvpSessionPolicy {
    fun canOpenConditions(hasLiveDraft: Boolean): Boolean = hasLiveDraft

    fun canOpenLiveResult(hasLiveDraft: Boolean): Boolean = hasLiveDraft
}

/**
 * Reconstruye un borrador NUEVO (sessionKey nuevo) a partir de una evaluacion guardada, para
 * editar sin sobrescribir la fila original: `FvpRepository.save` es idempotente por `sessionKey`,
 * asi que reutilizarlo actualizaria por identidad, no por contenido.
 *
 * COPIA FIEL (D2, contrato 18 §2): duplicar conserva adquisicion, fecha original de adquisicion/
 * registro/pesaje, evidencias (PTS y mapeo temporal) y confirmacion de reutilizacion TAL CUAL. La
 * copia es una serie NUEVA (`sessionKey` y la fecha de la fila son de la nueva creacion), pero no
 * afirma captura ni pesaje nuevos: no se regenera ninguna fecha de procedencia con "ahora" y los
 * intentos que guardaban las fechas de origen no reciben [FvpAttemptDraft.acquiredAt] inventado.
 */
fun storedFvpToDraft(stored: StoredFvpAssessment): FvpDraft {
    val entity = stored.assessment
    val conditions = stored.conditions
        .sortedBy { it.ordinal }
        .map { condition ->
            FvpConditionDraft(
                conditionKey = condition.conditionKey,
                addedLoadKg = condition.addedLoadKg,
                pushOffM = condition.pushOffM,
                attempts = stored.attempts
                    .filter { it.conditionId == condition.id }
                    .sortedBy { it.ordinal }
                    .map { attempt ->
                        FvpAttemptDraft(
                            heightM = attempt.heightM,
                            method = FvpEngine.parseMethod(attempt.method) ?: FvpMeasureMethod.MANUAL,
                            flightTimeS = attempt.flightTimeS,
                            sourceFps = attempt.sourceFps,
                            sourceAssessmentId = attempt.sourceAssessmentId,
                            sourceAttemptOrdinal = attempt.sourceAttemptOrdinal,
                            sourceProtocolId = attempt.sourceProtocolId,
                            valid = attempt.valid,
                            exclusionReason = attempt.exclusionReason,
                            notes = attempt.notes,
                            // Procedencia 1:1 (sin derivar): fechas de origen, reutilizacion y PTS.
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
        }
    return FvpDraft(
        athleteId = entity.athleteId ?: 0L,
        protocol = FvpEngine.parseProtocol(entity.protocolId) ?: FvpProtocol.SJ,
        bodyMassKg = entity.bodyMassKg,
        bodyMassFromProfile = entity.bodyMassSource == "PROFILE",
        pushOffMode = FvpPushOffUiMode.fromStorageKey(entity.pushOffMode) ?: FvpPushOffUiMode.COMMON,
        commonPushOffM = entity.pushOffM,
        pushOffLandmark = entity.pushOffLandmark.orEmpty(),
        pushOffLandmarkHeightCm = entity.pushOffLandmarkHeightCm,
        pushOffProtocol = entity.pushOffProtocol.orEmpty(),
        pushOffMeasuredAt = entity.pushOffMeasuredAt,
        pushOffCrouchCm = entity.pushOffCrouchCm,
        pushOffExtendedCm = entity.pushOffExtendedCm,
        conditions = conditions,
        notes = entity.notes,
        // Procedencia de adquisicion (B1b-2a): copia 1:1 de las columnas de la fila de origen.
        acquiredAt = entity.acquiredAt,
        acquisitionContextKind = entity.acquisitionContextKind,
        acquisitionContextKey = entity.acquisitionContextKey,
        bodyMassMeasurement = entity.bodyMassMeasurement,
        bodyMassMeasuredAt = entity.bodyMassMeasuredAt,
    )
}

// ---------------------------------------------------------------------------------------------
// B4a-2: mutaciones del borrador con INVALIDACION SINCRONA de la confirmacion de reutilizacion
// ---------------------------------------------------------------------------------------------

/**
 * Retira la confirmacion de reutilizacion de UN intento (contrato 18 §2, «Confirmacion de
 * reutilizacion v1»). Las dos columnas se limpian JUNTAS (`reuseConfirmedAt` y
 * `reuseConfirmationVersion`): la capa de datos, el codec de recuperacion y la validacion del
 * backup exigen que sean nulas a la vez.
 *
 * Los SNAPSHOTS de origen (fuente, fps, tiempo de vuelo, PTS, fechas de registro) NO se tocan: la
 * declaracion deja de valer, no la evidencia de que el intento se reutilizo de alli.
 *
 * Es la UNICA forma de retirar una confirmacion: ningun `null`/`UNKNOWN` la concede, porque solo la
 * declaracion explicita del usuario la escribe ([FvpAttemptDraft.withReuseConfirmation]).
 */
internal fun FvpAttemptDraft.withoutReuseConfirmation(): FvpAttemptDraft =
    if (reuseConfirmedAt == null && reuseConfirmationVersion == null) {
        this
    } else {
        copy(reuseConfirmedAt = null, reuseConfirmationVersion = null)
    }

/** Retira las confirmaciones de los intentos de UNA condicion (carga o procedencia de esa carga). */
internal fun FvpConditionDraft.withoutReuseConfirmations(): FvpConditionDraft =
    copy(attempts = attempts.map { it.withoutReuseConfirmation() })

/**
 * Retira TODAS las confirmaciones de la serie. Se usa cuando el cambio afecta a la aceptacion
 * completa (protocolo, h_po de la serie, contexto de adquisicion declarado), no a una sola carga.
 */
internal fun FvpDraft.withoutReuseConfirmations(): FvpDraft =
    copy(conditions = conditions.map { it.withoutReuseConfirmations() })

/**
 * Constancia DURABLE de la confirmacion explicita de reutilizacion (contrato 18 §2):
 * `reuseConfirmedAt` = instante REAL de la declaracion del usuario (epoch ms, jamas derivado de la
 * fecha del origen ni de la fecha de guardado) y `reuseConfirmationVersion` =
 * [FVP_REUSE_CONFIRMATION_VERSION].
 *
 * La fecha registra un EVENTO del checklist (cuando el usuario acepto reutilizar esa medicion); NO
 * verifica tiempo fisico ni convierte el origen en prueba de captura.
 */
internal fun FvpAttemptDraft.withReuseConfirmation(confirmedAtMillis: Long): FvpAttemptDraft =
    copy(
        reuseConfirmedAt = confirmedAtMillis,
        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
    )

/** Mapa de UNA condicion por su clave de borrador (identidad SOLO en memoria). */
private fun FvpDraft.mapCondition(
    conditionDraftKey: String,
    transform: (FvpConditionDraft) -> FvpConditionDraft,
): FvpDraft = copy(
    conditions = conditions.map { condition ->
        if (condition.draftKey == conditionDraftKey) transform(condition) else condition
    },
)

/** Mapa de UN intento por las claves de borrador de su condicion y de el mismo. */
private fun FvpDraft.mapAttempt(
    conditionDraftKey: String,
    attemptDraftKey: String,
    transform: (FvpAttemptDraft) -> FvpAttemptDraft,
): FvpDraft = mapCondition(conditionDraftKey) { condition ->
    condition.copy(
        attempts = condition.attempts.map { attempt ->
            if (attempt.draftKey == attemptDraftKey) transform(attempt) else attempt
        },
    )
}

/**
 * Carga anadida de UNA condicion, conservando la clave por defecto (`<carga>kg`) mientras el usuario
 * no la haya editado (misma regla que la pantalla).
 *
 * Cambiar la carga (incluido vaciarla) INVALIDA las confirmaciones de esa condicion: la declaracion
 * cubre una carga concreta y `null` no es la carga confirmada. Devolver el MISMO valor no invalida
 * nada, porque el snapshot confirmado no ha cambiado.
 */
internal fun FvpDraft.withConditionLoad(conditionDraftKey: String, addedLoadKg: Double?): FvpDraft =
    mapCondition(conditionDraftKey) { condition ->
        val previousDefault = defaultConditionKey(condition.addedLoadKg)
        val wasDefault = condition.conditionKey.isBlank() || condition.conditionKey == previousDefault
        val updated = condition.copy(
            addedLoadKg = addedLoadKg,
            conditionKey = if (wasDefault) defaultConditionKey(addedLoadKg) else condition.conditionKey,
        )
        if (condition.addedLoadKg == addedLoadKg) updated else updated.withoutReuseConfirmations()
    }

/**
 * Procedencia DECLARADA de la carga anadida de UNA condicion (`UNKNOWN`/`WEIGHED`/`NOMINAL`).
 *
 * Cualquier cambio invalida las confirmaciones de esa condicion, tambien el paso a `UNKNOWN`: la
 * declaracion de reutilizacion cubre la procedencia de la carga y cambiar a desconocido no puede
 * conservar una aceptacion hecha sobre otra procedencia.
 */
internal fun FvpDraft.withConditionAddedLoadSource(
    conditionDraftKey: String,
    addedLoadSource: String,
): FvpDraft = mapCondition(conditionDraftKey) { condition ->
    if (condition.addedLoadSource == addedLoadSource) {
        condition
    } else {
        condition.copy(addedLoadSource = addedLoadSource).withoutReuseConfirmations()
    }
}

/**
 * h_po por condicion (modo PER_CONDITION). Cambiarlo invalida las confirmaciones de ESA condicion
 * (`h_po relevante`): en modo COMMON el valor por condicion no entra en la serie y no invalida nada.
 */
internal fun FvpDraft.withConditionPushOffM(conditionDraftKey: String, pushOffM: Double?): FvpDraft =
    mapCondition(conditionDraftKey) { condition ->
        if (condition.pushOffM == pushOffM) {
            condition
        } else {
            val updated = condition.copy(pushOffM = pushOffM)
            if (pushOffMode == FvpPushOffUiMode.PER_CONDITION) {
                updated.withoutReuseConfirmations()
            } else {
                updated
            }
        }
    }

/**
 * Altura DEL intento. Cambiarla invalida la confirmacion de ESE intento (la declaracion cubre la
 * altura reutilizada); reescribir la misma altura no cambia el snapshot confirmado y no invalida.
 */
internal fun FvpDraft.withAttemptHeight(
    conditionDraftKey: String,
    attemptDraftKey: String,
    heightM: Double?,
): FvpDraft = mapAttempt(conditionDraftKey, attemptDraftKey) { attempt ->
    if (attempt.heightM == heightM) attempt else attempt.copy(heightM = heightM).withoutReuseConfirmation()
}

/** Metodo de medida DEL intento: cambiarlo invalida la confirmacion de ESE intento. */
internal fun FvpDraft.withAttemptMethod(
    conditionDraftKey: String,
    attemptDraftKey: String,
    method: FvpMeasureMethod,
): FvpDraft = mapAttempt(conditionDraftKey, attemptDraftKey) { attempt ->
    if (attempt.method == method) attempt else attempt.copy(method = method).withoutReuseConfirmation()
}

/**
 * Escritura del intento al confirmar la REUTILIZACION de una medicion guardada: copia el snapshot
 * reutilizable ([FvpAttemptDraft.withReusedMeasurement], que retira la confirmacion si la fuente
 * cambia) y deja la constancia durable de la declaracion
 * ([FvpAttemptDraft.withReuseConfirmation]).
 *
 * Con la MISMA fuente ya confirmada, re-verificar la medicion no borra la confirmacion por
 * accidente: la declaracion se renueva con el instante real de esta confirmacion.
 */
internal fun FvpDraft.withAttemptConfirmedReuse(
    conditionDraftKey: String,
    attemptDraftKey: String,
    measurement: ReusableJumpMeasurement,
    confirmedAtMillis: Long,
): FvpDraft = mapAttempt(conditionDraftKey, attemptDraftKey) { attempt ->
    attempt.withReusedMeasurement(measurement).withReuseConfirmation(confirmedAtMillis)
}

/**
 * Protocolo de la SERIE (SJ/CMJ/...). Cambiarlo invalida TODAS las confirmaciones: el protocolo
 * forma parte de la aceptacion completa de la reutilizacion.
 */
internal fun FvpDraft.withSeriesProtocol(protocol: FvpProtocol): FvpDraft =
    if (protocol == this.protocol) this else copy(protocol = protocol).withoutReuseConfirmations()

/**
 * Modo de h_po de la serie (COMMON vs PER_CONDITION). Cambiarlo cambia QUE h_po es el relevante
 * para la serie (el comun o el de cada condicion), asi que invalida TODAS las confirmaciones de
 * reutilizacion: la aceptacion incluye el h_po relevante, no solo su valor numerico. Escribir el
 * mismo modo no invalida nada.
 */
internal fun FvpDraft.withSeriesPushOffMode(mode: FvpPushOffUiMode): FvpDraft =
    if (pushOffMode == mode) this else withoutReuseConfirmations().copy(pushOffMode = mode)

/**
 * h_po comun de la serie (modo COMMON). Cambiarlo invalida TODAS las confirmaciones; en modo
 * PER_CONDITION este campo no entra en la serie y cambiarlo no invalida nada. En el setup se llama
 * DESPUES de [withSeriesPushOffMode] para juzgar con el modo resultante.
 */
internal fun FvpDraft.withSeriesPushOffM(commonPushOffM: Double?): FvpDraft = when {
    commonPushOffM == this.commonPushOffM -> this
    pushOffMode != FvpPushOffUiMode.COMMON -> copy(commonPushOffM = commonPushOffM)
    else -> copy(commonPushOffM = commonPushOffM).withoutReuseConfirmations()
}

/**
 * Contexto de adquisicion DECLARADO de la serie (contexto + clave opaca). Cambiarlo invalida TODAS
 * las confirmaciones: la aceptacion de reutilizacion incluye el contexto de la serie.
 *
 * `acquiredAt` no tiene productor en la UI todavia: cuando lo tenga, formara parte del mismo cambio
 * de contexto y debera invalidar igual (no se adelanta aqui).
 */
internal fun FvpDraft.withAcquisitionContext(kind: String, key: String?): FvpDraft =
    if (kind == acquisitionContextKind && key == acquisitionContextKey) {
        this
    } else {
        copy(acquisitionContextKind = kind, acquisitionContextKey = key).withoutReuseConfirmations()
    }
