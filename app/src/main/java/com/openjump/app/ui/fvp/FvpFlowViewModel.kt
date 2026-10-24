package com.openjump.app.ui.fvp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.FvpRepository
import com.openjump.app.fvp.FvpEligibilityProblem
import com.openjump.app.fvp.checkSeries
import com.openjump.fvp.FvpProtocol
import com.openjump.app.ui.FVP_CONDITIONS_ROUTE
import com.openjump.app.ui.FVP_LIVE_RESULT_ROUTE
import com.openjump.app.ui.FVP_SETUP_ROUTE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Clave del payload recuperable dentro del `SavedStateHandle` del dueño ([FvpFlowViewModel]).
 * Es un detalle PRIVADO del dueño: ningun destino lee ni escribe el payload por su cuenta.
 */
internal const val FVP_RECOVERY_PAYLOAD_KEY = "fvp.recovery.payload"

/**
 * Clave de la MARCA DE VIGENCIA del payload retenido dentro del mismo `SavedStateHandle` (A1).
 *
 * Es lo que permite cumplir D6 ("conservar el payload anterior ante un rechazo") SIN resucitar
 * contenido viejo ni una identidad ajena: los bytes del payload anterior se conservan EXACTOS,
 * pero quedan INERTES mientras esta marca este activa, de modo que una reconstruccion (muerte de
 * proceso) no los decodifica, no los revalida y no los adopta.
 *
 * - ausente o `false`: el payload (si lo hay) es el JSON VIGENTE y puede considerarse;
 * - `true`: el payload retenido NO es vigente. Si hay bytes, el dueño avisa
 *   ([FvpRecoveryNotice.Unrecoverable]) y los conserva como material inerte; si NO hay bytes, no
 *   hay nada que neutralizar y no se inventa un aviso (no habia recuperacion que perder).
 *
 * Igual que el payload, es un detalle PRIVADO del dueño: ningun destino la lee ni la escribe. Solo
 * vive en el estado que Android conserva (`SavedStateHandle`), nunca en disco.
 */
internal const val FVP_RECOVERY_INVALIDATED_KEY = "fvp.recovery.invalidated"

/**
 * Cota de espera (ms) de la lectura de la verdad persistida que el dueño hace en su `init` (D6/R3).
 *
 * La lectura es ASINCRONA y ACOTADA (A3): el constructor NO bloquea. Publica
 * [FvpFlowViewModel.reconciliationPending] de forma SINCRONA y despues lanza la consulta en
 * `viewModelScope`; mientras esta pendiente, los destinos del flujo (setup, condiciones y resultado
 * en vivo) muestran un cuerpo de carga NO interactivo y no siembran formularios, no declaran
 * caducidad, no calculan ni guardan. La consulta es acotada (indice UNICO por `sessionKey` mas la PK
 * del atleta), de modo que esta cota es la red de seguridad contra un bloqueo anormal: al agotarse
 * NO se adopta (fallo cerrado), se avisa ([FvpRecoveryNotice.Unrecoverable]) y el payload se
 * CONSERVA como candidato todavia valido.
 */
internal const val FVP_TRUTH_LOOKUP_TIMEOUT_MS: Long = 2_000L

/**
 * Motivo por el que el flujo NO dispone de recuperacion para su borrador. Es la causa de DATOS que
 * el codec reporta ([FvpDraftStateCodec.encodeDetailed] mas el fallo de decodificacion/
 * revalidacion), nunca un texto de usuario: la UI elige el copy localizado por motivo.
 */
enum class FvpRecoveryRefusal {
    /** El payload superaria el presupuesto de 64 KiB: no se trunca ni se guarda a medias. */
    TOO_LARGE,

    /** El estado no es recuperable: incoherente, no representable o indescifrable al restaurar. */
    NOT_RECOVERABLE,
}

/**
 * Aviso visible del dueño de la recuperacion (D6). Es estado INMUTABLE y pequeno: los destinos lo
 * proyectan a copy localizado, no lo reconstruyen.
 *
 * - [None]: o el estado se guarda para recuperar, o no hay nada que avisar.
 * - [RefusedToStore]: el dueño RECHAZO guardar el estado del borrador VIVO (motivo del codec). El
 *   borrador sigue solo en vivo y el payload anterior se conserva intacto.
 * - [Unrecoverable]: habia un payload pero NO se puede usar. Nunca se inventa un borrador valido:
 *   el aviso sustituye al mensaje generico de sesion caducada. Las causas son las de R2
 *   (indescifrable / no revalidable) mas las de R3: la verdad PERSISTIDA de la serie dice que el
 *   estado no se puede adoptar (atleta ausente o archivado, `sessionKey` ya ocupada por otro
 *   atleta) o no se pudo LEER esa verdad persistida (fallo cerrado). A1 anade el payload RETENIDO
 *   pero marcado como NO vigente: un rechazo anterior del borrador vivo lo dejo inerte, de modo
 *   que se avisa y NO se decodifica.
 *
 * Nota de copy (R3): el caso "la serie ya estaba guardada" no tiene hoy un texto propio porque eso
 * exige una variante nueva de este aviso + su linea en `fvpRecoveryNoticeText`
 * (`FvpSetupScreen.kt`, `when` exhaustivo) + la clave en los 9 `strings.xml`; nada de eso esta en
 * el alcance de ficheros de R3. Mientras no se apruebe, ese caso NO publica aviso (queda el mensaje
 * generico de sesion caducada, que no afirma nada falso) en vez de decir "no se pudo recuperar"
 * sobre una serie que SI esta guardada.
 */
sealed interface FvpRecoveryNotice {
    data object None : FvpRecoveryNotice

    data class RefusedToStore(val reason: FvpRecoveryRefusal) : FvpRecoveryNotice

    data object Unrecoverable : FvpRecoveryNotice
}

/**
 * Plan de recuperacion de un payload, PURO y decidible sin Compose ni Android.
 *
 * - [Restored]: el payload decodifico y el estado paso la revalidacion; es adoptable.
 * - [Refused]: habia payload pero NO se puede usar (con su motivo). Nunca se devuelve un estado a
 *   medias: o el estado completo revalidado, o un rechazo con causa.
 * - [Nothing]: no hay payload que recuperar (flujo nuevo). No es un fallo.
 */
sealed interface FvpRecoverPlan {
    data class Restored(val state: FvpRecoveryState) : FvpRecoverPlan

    data class Refused(val reason: FvpRecoveryRefusal) : FvpRecoverPlan

    data object Nothing : FvpRecoverPlan
}

/**
 * Regla de ACTUALIZACION del payload ante un cambio del borrador vivo:
 *
 * - [Store]: hay payload nuevo y completo; sustituye al anterior.
 * - [KeepPrevious]: el estado NO es almacenable (con motivo). El payload ANTERIOR se conserva
 *   EXACTAMENTE como estaba (no se marca como actualizado ni se borra) por D6, pero deja de ser
 *   VIGENTE: el dueño activa su marca de invalidacion, de modo que solo es material inerte y una
 *   reconstruccion posterior no puede resucitarlo ni adoptarlo (A1). La UI avisa de que este
 *   borrador no tiene recuperacion.
 * - [Clear]: no hay borrador vivo (flujo terminado o sin empezar): no se conserva nada.
 */
sealed interface FvpDraftPayloadUpdate {
    data class Store(val payload: String) : FvpDraftPayloadUpdate

    data class KeepPrevious(val reason: FvpRecoveryRefusal) : FvpDraftPayloadUpdate

    data object Clear : FvpDraftPayloadUpdate
}

/**
 * Decide que hacer con un payload RESTAURADO, sin tocar ningun singleton ni el `SavedStateHandle`.
 *
 * Reglas (literales, cubiertas por `FvpFlowRecoveryTest`):
 * - `null` -> [FvpRecoverPlan.Nothing] (flujo nuevo; no es un fallo).
 * - por encima del presupuesto -> [FvpRecoverPlan.Refused] con [FvpRecoveryRefusal.TOO_LARGE]:
 *   [FvpDraftStateCodec.decode] tampoco lo aceptaria, y el motivo se conoce antes de intentarlo.
 * - payload indescifrable (version desconocida, JSON corrupto, clave de enum no reconocida, campo
 *   ausente, numero no finito, incoherencia estructural) -> rechazo con
 *   [FvpRecoveryRefusal.NOT_RECOVERABLE]. No se inventa un borrador valido.
 * - estado decodificado que NO pasa la revalidacion de [fvpRecoveredStateIsAdoptable] -> rechazo
 *   con [FvpRecoveryRefusal.NOT_RECOVERABLE]: no se adopta a ciegas.
 */
internal fun fvpRecoverPlan(payload: String?): FvpRecoverPlan {
    if (payload == null) return FvpRecoverPlan.Nothing
    if (payload.toByteArray(Charsets.UTF_8).size > FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES) {
        return FvpRecoverPlan.Refused(FvpRecoveryRefusal.TOO_LARGE)
    }
    val state = FvpDraftStateCodec.decode(payload)
        ?: return FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE)
    if (!fvpRecoveredStateIsAdoptable(state)) {
        return FvpRecoverPlan.Refused(FvpRecoveryRefusal.NOT_RECOVERABLE)
    }
    return FvpRecoverPlan.Restored(state)
}

/**
 * REVALIDACION obligatoria antes de adoptar un estado decodificado (D6: "revalidacion tras
 * decode"; el codec NO revalida). Se usa la MISMA ruta de elegibilidad que el flujo vivo
 * ([checkSeries] sobre [FvpDraft.toSeriesInput], la proyeccion que usan el calculo y el guardado),
 * y se exige la admision IDENTICA que satisface todo borrador que el flujo instala en la sesion:
 * una masa corporal utilizable (finita y > 0).
 *
 * Por que NO se exige `canCompute`: un borrador parcial es legitimo en vivo (el paso de condiciones
 * abre con la serie todavia incompleta y en `PER_CONDITION` la primera condicion aun no existe), de
 * modo que exigir el perfil completo convertiria la recuperacion de un borrador real en un rechazo
 * falso.
 *
 * Alcance (R2 + R3): esta revalidacion es la mitad PURA de la ruta ("elegibilidad" por contenido).
 * La otra mitad -la verdad PERSISTIDA: fila que YA ocupa el `sessionKey`, atleta borrado/archivado-
 * la aporta [fvpRecoveredStateReconciliation], que el dueño consulta DESPUES de esta revalidacion y
 * ANTES de adoptar. `FvpRepository.save` sigue volviendo a validar al guardar.
 */
internal fun fvpRecoveredStateIsAdoptable(state: FvpRecoveryState): Boolean {
    val eligibility = checkSeries(state.draft.toSeriesInput())
    return FvpEligibilityProblem.INVALID_BODY_MASS !in eligibility.blocking
}

/**
 * Resultado de RECONCILIAR un estado recuperado con la verdad PERSISTIDA (D6/R3). Es la respuesta a
 * "¿puede este borrador recuperado llegar a la sesion viva?".
 *
 * Solo [ADOPT] autoriza a adoptarlo. Las otras tres salidas significan que el estado NUNCA se
 * instala en la sesion, de modo que ninguna pantalla puede llamar a `FvpRepository.save` con esa
 * identidad recuperada (y en [MISMATCHED_OWNER] esa llamada lanzaria "Conflicto de sessionKey"
 * porque el `sessionKey` ya pertenece a otro atleta).
 */
internal enum class FvpRecoveryReconciliation {
    /**
     * Con ese `sessionKey` YA hay una fila persistida y su dueño es la MISMA identidad
     * (`athleteId`) del borrador recuperado: la serie ya esta guardada, asi que la verdad
     * persistida manda y no hay nada que recuperar (adoptar el borrador lo volveria a ofrecer como
     * serie nueva y reescribiria una fila que ya existe).
     */
    ALREADY_SAVED,

    /**
     * Con ese `sessionKey` hay una fila, pero su dueño NO es la identidad recuperada (otro atleta o
     * dueño desconocido/`null`), o la identidad recuperada no es utilizable (`athleteId <= 0`).
     * Llamar a `save` con el borrador recuperado lanzaria por conflicto de `sessionKey`: la sesion
     * no es recuperable.
     */
    MISMATCHED_OWNER,

    /**
     * No hay fila con ese `sessionKey` y el atleta de la serie no existe (borrado) o esta archivado.
     * El borrador no se puede guardar contra un atleta inutilizable: la sesion no es recuperable.
     */
    ATHLETE_GONE,

    /**
     * No hay fila con ese `sessionKey` y el atleta de la serie existe y esta activo: unica salida
     * que autoriza a adoptar el borrador recuperado.
     */
    ADOPT,
}

/**
 * RECONCILIACION PURA de un estado recuperado con la verdad PERSISTIDA que el llamante ya ha leido
 * ([persistedRowId]/[persistedAthleteId] de la fila con ese `sessionKey`, y el estado del atleta de
 * la serie). Sin Room, sin Android y sin `SavedStateHandle`: es la unica definicion de la regla y se
 * prueba con entradas literales.
 *
 * PRECEDENCIA (de mayor a menor; la primera fila que aplica decide, y por eso el resultado no
 * depende de cuantas condiciones se cumplan a la vez):
 *
 * | # | condicion | salida |
 * |---|---|---|
 * | 1 | `persistedRowId != null` y el dueño persistido ES la identidad recuperada (`athleteId` del
 * borrador > 0 e igual a `persistedAthleteId`) | [FvpRecoveryReconciliation.ALREADY_SAVED] |
 * | 2 | `persistedRowId != null` (dueño distinto, `null`/desconocido, o identidad recuperada
 * inutilizable) | [FvpRecoveryReconciliation.MISMATCHED_OWNER] |
 * | 3 | no hay fila y (`athleteId` del borrador <= 0, o el atleta no existe, o esta archivado) |
 * [FvpRecoveryReconciliation.ATHLETE_GONE] |
 * | 4 | no hay fila y el atleta existe y esta activo | [FvpRecoveryReconciliation.ADOPT] |
 *
 * Por que la FILA va ANTES que el estado del atleta: la fila es la verdad persistida sobre ese
 * `sessionKey` exacto y decide si el borrador podria siquiera escribirse. Si ya esta guardado (1) o
 * pertenece a otro atleta (2), el estado del atleta NO cambia la decision: en (1) no hay nada que
 * recuperar y en (2) el `save` es imposible. Solo cuando no hay fila el atleta decide entre
 * "no recuperable" (3) y "adoptable" (4).
 *
 * `persistedRowId` es la señal de EXISTENCIA de la fila: `persistedAthleteId` solo se consulta
 * cuando hay fila, de modo que un `persistedAthleteId` sin fila (entrada incoherente) no fabrica un
 * conflicto de dueño inexistente.
 *
 * ALCANCE: esta funcion NO decide si el borrador es adoptable por CONTENIDO (eso es
 * [fvpRecoveredStateIsAdoptable], que se aplica antes) ni escribe nada. La verdad persistida no
 * sustituye al borrador: decide si el borrador se puede usar.
 */
internal fun fvpRecoveredStateReconciliation(
    state: FvpRecoveryState,
    persistedRowId: Long?,
    persistedAthleteId: Long?,
    athleteExists: Boolean,
    athleteArchived: Boolean,
): FvpRecoveryReconciliation {
    val recoveredOwner = state.draft.athleteId
    if (persistedRowId != null) {
        val sameOwner = recoveredOwner > 0L &&
            persistedAthleteId != null &&
            persistedAthleteId == recoveredOwner
        return if (sameOwner) {
            FvpRecoveryReconciliation.ALREADY_SAVED
        } else {
            FvpRecoveryReconciliation.MISMATCHED_OWNER
        }
    }
    if (recoveredOwner <= 0L || !athleteExists || athleteArchived) {
        return FvpRecoveryReconciliation.ATHLETE_GONE
    }
    return FvpRecoveryReconciliation.ADOPT
}

/**
 * Verdad PERSISTIDA que la reconciliacion necesita, ya leida de la base (D6/R3).
 *
 * @param persistedRowId fila que YA ocupa el `sessionKey` del borrador recuperado, o `null` si no
 *   hay ninguna.
 * @param persistedAthleteId dueño de esa fila TAL CUAL (`null` = desconocido).
 * @param athleteExists si el atleta de la serie sigue existiendo (archivado incluido: un atleta
 *   archivado EXISTE pero no es utilizable).
 * @param athleteArchived si ese atleta esta archivado.
 */
internal data class FvpPersistedTruth(
    val persistedRowId: Long? = null,
    val persistedAthleteId: Long? = null,
    val athleteExists: Boolean = false,
    val athleteArchived: Boolean = false,
)

/**
 * Fuente de la verdad persistida del dueño. Devuelve `null` cuando la verdad NO se pudo leer
 * (fallo de infraestructura): el dueño falla CERRADO y no adopta. No es un detalle inyectable por
 * gusto: es lo que permite probar la reconciliacion en JVM, sin Room.
 */
internal fun interface FvpPersistedTruthSource {
    /**
     * Lee la fila con el `sessionKey` del borrador y el estado de su atleta. Puede lanzar (base
     * cerrada, error de SQLite): quien restaura lo trata como verdad DESCONOCIDA, nunca como "no
     * hay fila".
     */
    suspend fun truthFor(draft: FvpDraft): FvpPersistedTruth?
}

/**
 * Fuente REAL de la verdad persistida: la fila por `sessionKey` ([FvpRepository.bySessionKey], solo
 * lectura y sin cambio de esquema) mas el atleta de la serie ([AthleteRepository.byId] + `archivedAt`).
 *
 * Una identidad recuperada inutilizable (`athleteId <= 0`) NO se consulta en la tabla de atletas: se
 * declara como atleta ausente, porque `save` la rechazaria siempre. Asi la reconciliacion no depende
 * de que la base "no encuentre" un id 0.
 */
internal fun fvpPersistedTruthSource(
    sessions: FvpRepository,
    athletes: AthleteRepository,
): FvpPersistedTruthSource = FvpPersistedTruthSource { draft ->
    val persisted = sessions.bySessionKey(draft.sessionKey)
    val athlete = draft.athleteId.takeIf { it > 0L }?.let { athletes.byId(it) }
    FvpPersistedTruth(
        persistedRowId = persisted?.rowId,
        persistedAthleteId = persisted?.athleteId,
        athleteExists = athlete != null,
        athleteArchived = athlete?.archivedAt != null,
    )
}

/**
 * Guard PURO de OBSOLESCENCIA de una resolucion de reconciliacion (A3): una resolucion capturada
 * con [capturedGeneration] solo puede aplicar efectos si la generacion del dueño NO ha cambiado
 * ([currentGeneration]). Extraida para poder probarla con literales: el `init` publica PENDIENTE y
 * lanza la consulta, y cualquier mutacion externa o descarte explicito incrementa la generacion.
 *
 * Es la garantia que la CANCELACION no aporta: una fuente no cooperativa puede entregar la verdad
 * despues de una mutacion, y entonces hay que descartar la resolucion ENTERA (adopcion, limpieza de
 * payload, aviso y bandera de pendiente).
 */
internal fun fvpReconciliationIsStale(capturedGeneration: Long, currentGeneration: Long): Boolean =
    capturedGeneration != currentGeneration

/**
 * Regla PURA de la limpieza al salir del flujo hacia Home: solo se limpia cuando el dueño ya ha
 * visto algun paso real del flujo (ver `hasSeenFlowStep`). Extraida para poder probarla sin
 * `SavedStateHandle` ni Compose.
 */
internal fun fvpLeftFlowClears(hasSeenFlowStep: Boolean): Boolean = hasSeenFlowStep

/** Decide la actualizacion del payload para un borrador vivo (o su ausencia). PURA. */
internal fun fvpDraftPayloadUpdate(state: FvpRecoveryState?): FvpDraftPayloadUpdate {
    if (state == null) return FvpDraftPayloadUpdate.Clear
    // SIMETRIA con la restauracion (P2 de la revision de R2): no se guarda un estado que la
    // revalidacion rechazaria, porque entonces el propio dueño produciria un payload que luego
    // se negaria a restaurar (y el usuario veria "no se pudo recuperar" sobre un borrador que la
    // app consideraba guardable). Mismo predicado que [fvpRecoveredStateIsAdoptable].
    if (!fvpRecoveredStateIsAdoptable(state)) {
        return FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.NOT_RECOVERABLE)
    }
    return when (val result = FvpDraftStateCodec.encodeDetailed(state)) {
        is FvpEncodeResult.Encoded -> FvpDraftPayloadUpdate.Store(result.payload)
        FvpEncodeResult.TooLarge -> FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.TOO_LARGE)
        FvpEncodeResult.NotRecoverable -> FvpDraftPayloadUpdate.KeepPrevious(FvpRecoveryRefusal.NOT_RECOVERABLE)
    }
}

/**
 * Aplica una [FvpDraftPayloadUpdate] al payload anterior. PURA, para que la regla "ante un rechazo
 * se conserva el payload ANTERIOR intacto" (y "vaciar deja de conservar nada") sea comprobable con
 * literales, sin `SavedStateHandle` ni Compose.
 */
internal fun fvpApplyPayloadUpdate(previous: String?, update: FvpDraftPayloadUpdate): String? =
    when (update) {
        is FvpDraftPayloadUpdate.Store -> update.payload
        is FvpDraftPayloadUpdate.KeepPrevious -> previous
        FvpDraftPayloadUpdate.Clear -> null
    }

/**
 * Paso del flujo al que corresponde una ruta del `NavHost`, o `null` si la ruta no es del flujo
 * F-V-P (Home, historial, resultado guardado, pasos de video...). PURA: el `NavHost` la usa para
 * comunicar al dueño el paso REAL del payload (metadato, no navegacion).
 */
internal fun fvpFlowStepForRoute(route: String?): FvpFlowStep? = when (route) {
    FVP_SETUP_ROUTE -> FvpFlowStep.SETUP
    FVP_CONDITIONS_ROUTE -> FvpFlowStep.CONDITIONS
    FVP_LIVE_RESULT_ROUTE -> FvpFlowStep.RESULT
    else -> null
}

/**
 * UNICO dueño del estado recuperable del flujo F-V-P y de su `SavedStateHandle` (plan 13, D6; el
 * contrato que R1 documento en [FvpDraftSession]).
 *
 * CONTRATO:
 * - Se crea UNA vez por grafo de navegacion (nivel de `NavHost`), NO una por destino: las copias
 *   por pantalla quedarian obsoletas. Tampoco es una SEGUNDA fuente de verdad: ENVUELVE a
 *   [FvpDraftSession] (observa su `StateFlow` y adopta/limpia), de modo que el borrador vivo sigue
 *   siendo el unico estado compartido en memoria.
 * - RESTAURA en su `init` SIN BLOQUEAR (A3): lee el payload, decide con [fvpRecoverPlan] y, solo si
 *   el plan es [FvpRecoverPlan.Restored], publica [reconciliationPending] = `true` de forma
 *   SINCRONA y LANZA la reconciliacion con la verdad PERSISTIDA
 *   ([fvpRecoveredStateReconciliation]) en `viewModelScope`, antes de adoptar nada. Nunca adopta un
 *   estado rechazado ni inventa uno valido: ante un rechazo deja el payload intacto y publica el
 *   aviso. Un plan rechazado o ausente no deja nada pendiente y no toca la sesion.
 * - PRECEDENCIA DE LA SESION VIVA: si al construir el dueño YA hay un borrador vivo en
 *   [FvpDraftSession] (Activity nueva en un proceso que SOBREVIVIO; la sesion es por proceso y no
 *   sobrevive a una muerte real), la SESION manda: el payload retenido del dueño anterior NO se
 *   decodifica, NO se revalida y NO se reconcilia (cero consultas a la base) y jamas se adopta por
 *   encima de lo vivo. Si habia candidato retenido o marca de rechazo, la recuperabilidad se deriva
 *   de forma SINCRONA del borrador ACTUAL COMPLETO, marca de vigencia de A1 incluida. Sin payload
 *   ni marca, crear un dueño NO rearma una salida previa a Home: espera la entrada explicita al
 *   flujo o una nueva mutacion viva.
 * - PENDIENTE ES UN ESTADO DE PRIMERA CLASE (A3): mientras [reconciliationPending] sea `true` los
 *   destinos del flujo (setup, condiciones y resultado EN VIVO) muestran un cuerpo de carga no
 *   interactivo y NO siembran formularios, NO declaran caducidad, NO calculan y NO guardan; el
 *   historial y el resultado GUARDADO no esperan (solo leen sus propias filas). Asi una pantalla
 *   jamas ve un borrador a medias ni toma por caducada una restauracion en curso.
 * - PRESUPUESTO DE LA CONSULTA: la reconciliacion necesita la base, pero la lectura se resuelve en
 *   una corrutina acotada ([FVP_TRUTH_LOOKUP_TIMEOUT_MS]) sin bloquear el hilo que construye al
 *   dueño. Son dos lecturas Room suspendidas e indexadas (indice UNICO por `sessionKey` + PK
 *   del atleta); la cota es la red de seguridad contra un bloqueo anormal.
 * - SOLO [FvpRecoveryReconciliation.ADOPT] autoriza a adoptar. Si la reconciliacion dice
 *   [FvpRecoveryReconciliation.ALREADY_SAVED] (ya hay fila con ESE `sessionKey` y el mismo dueño),
 *   [FvpRecoveryReconciliation.ATHLETE_GONE] o [FvpRecoveryReconciliation.MISMATCHED_OWNER], el
 *   borrador NO se instala en la sesion y el payload se LIMPIA: es un estado que la verdad
 *   persistida prueba inutilizable y conservarlo solo repetiria el mismo rechazo. Como la sesion
 *   nunca recibe ese borrador, ninguna pantalla puede llamar a `FvpRepository.save` con esa
 *   identidad (que en [FvpRecoveryReconciliation.MISMATCHED_OWNER] lanzaria por conflicto de
 *   `sessionKey`). ORDEN DE PUBLICACION: al adoptar, el METADATO restaurado (paso del flujo y
 *   "borrador ya escrito") se fija ANTES de publicar el borrador en la sesion, porque el observador
 *   del borrador vivo corre en el mismo despachador y un colector ya activo puede reanudarse EN LINEA
 *   dentro del `setter` del `StateFlow`: al publicar primero, ese colector re-codificaria el payload
 *   con el paso VIEJO y perderia el paso restaurado.
 * - FALLO CERRADO: si la verdad persistida no se puede leer (excepcion ordinaria o cota de espera
 *   agotada), NO se adopta, se avisa ([FvpRecoveryNotice.Unrecoverable]) y el payload se CONSERVA
 *   como candidato todavia valido: la lectura fallida no es un veredicto sobre el estado, de modo
 *   que un intento posterior sigue pudiendo recuperarlo (misma regla que el rechazo de R2: el
 *   payload anterior se conserva intacto). La CANCELACION de ciclo de vida
 *   ([CancellationException]: limpieza del dueño/`viewModelScope`) es otra cosa: se PROPAGA y
 *   no se reinterpreta como verdad ausente, de modo que no publica aviso ni toca el candidato.
 *   VIDA DE LA CORRUTINA: como LIMPIAR el dueño (`ViewModelStore.clear()`) NO cambia la generacion,
 *   la guarda de obsolescencia no puede cubrir ese caso; por eso la resolucion comprueba de forma
 *   EXPLICITA que el trabajo sigue vivo antes de CUALQUIER efecto: una fuente que se come la
 *   cancelacion (p. ej. esperando dentro de `NonCancellable`) puede devolver su verdad -o el fallo
 *   cerrado de su propia cota- DESPUES de que el dueño se haya limpiado, y un dueño muerto no puede
 *   adoptar, ni limpiar el payload, ni publicar avisos.
 * - Es el UNICO escritor del payload: cada cambio del borrador vivo se codifica con
 *   [fvpDraftPayloadUpdate]. Ante un RECHAZO ([FvpEncodeResult.TooLarge] /
 *   [FvpEncodeResult.NotRecoverable]) el payload ANTERIOR se conserva EXACTAMENTE como estaba y se
 *   avisa; jamas se guarda un payload recortado ni se marca el anterior como actualizado.
 * - MARCA DE VIGENCIA (A1, [FVP_RECOVERY_INVALIDATED_KEY]): la conservacion de D6 no puede
 *   resucitar contenido viejo. La marca se activa ANTES de escribir cada payload y se retira SOLO
 *   cuando el vigente ya es el JSON COMPLETO y revalidado del borrador ACTUAL; en un RECHAZO la
 *   marca queda activa, de modo que el payload anterior sigue ahi BYTE A BYTE pero es inerte y
 *   una reconstruccion no lo adopta (ni el contenido viejo ni la identidad ajena de otro
 *   `sessionKey`). Solo se retira, ademas, en la salida terminal ([clearPayload]), que es donde si
 *   se descartan los bytes.
 * - LIMPIA el payload cuando el flujo termina: borrador vivo -> `null` (guardado confirmado,
 *   descarte o borrado) y al volver a Home ([onLeftFlow]). Un borrador descartado no se resucita.
 * - MUTACIONES EXTERNAS (A2): los destinos NO escriben el singleton por su cuenta; cada
 *   begin/adopt/update/reset pasa por este dueño ([FvpDraftMutations]), que de forma SINCRONA
 *   incrementa una GENERACION, CANCELA la reconciliacion pendiente, invalida la recuperabilidad e
 *   inmediatamente despues aplica el cambio y guarda el estado COMPLETO actual (o conserva los
 *   bytes anteriores ya inertes bajo la marca de A1). Un colector de `StateFlow` NO basta: un reset
 *   sobre una sesion ya `null` no emite y los cambios rapidos se conflacionan; por eso el reset
 *   explicito funciona incluso sin borrador vivo. Toda resolucion capturada con una generacion
 *   anterior es OBSOLETA y no aplica NINGUN efecto (adopcion, limpieza de payload, aviso ni
 *   bandera de pendiente): la cancelacion sola no es la garantia. Guardado confirmado: el callback
 *   captura la `sessionKey` y usa [FvpDraftMutations.resetIfSessionKey], de modo que la serie A no
 *   puede descartar ni navegar fuera de la serie B empezada mientras tanto.
 * - NO promete recuperacion real de proceso: el alcance es lo que Android conserve en el
 *   `SavedStateHandle` (nunca force-stop, eliminacion de tarea ni reinicio). La verificacion real
 *   es R3/V2 [G-DEVICE].
 *
 * El estado que publica ([recoveryNotice] y [reconciliationPending]) es inmutable y pequeno: la UI
 * decide el copy y el cuerpo de carga.
 *
 * MITAD ANDROID DE R3 (V2, [G-DEVICE]): requiere evidencia separada, registrada por fuente en
 * `docs/research/fvp/19-R3_RUNTIME_CHECKPOINT.md`. Los tests JVM prueban la DECISION (que borradores se
 * adoptan, cual manda la verdad persistida, que la restauracion pendiente no deja pasar a los
 * destinos y que el payload se limpia o se conserva), nunca que Android conserve el estado.
 * Protocolo que V2 debe ejecutar en un dispositivo, tal cual:
 *
 * 1. con la app abierta en `fvp/conditions` y un borrador parcial ya escrito (payload presente),
 *    poner la app en SEGUNDO PLANO y esperar a que Android haga la foto del `SavedStateHandle`
 *    (`onSaveInstanceState`/`onStop`);
 * 2. matar SOLO el proceso: `adb shell am kill com.openjump.app` (NO `force-stop`: eso descarta el
 *    estado guardado y no es el escenario de recuperacion);
 * 3. volver a la app desde el reciente y comprobar que el paso y el borrador vuelven, que la
 *    reconciliacion no adopta un estado que la verdad persistida prohibe (guardar la serie antes de
 *    matar el proceso para cubrir `ALREADY_SAVED`; borrar o archivar el atleta para cubrir
 *    `ATHLETE_GONE`);
 * 4. medir DOS presupuestos SEPARADOS (no uno):
 *    - el JSON del payload recuperable frente a [FvpDraftStateCodec.MAX_PAYLOAD_UTF8_BYTES]
 *      (65 536 bytes UTF-8);
 *    - el estado AGREGADO que Android conserva de la Activity (el `Bundle` COMPLETO, con la
 *      navegacion y el resto de proveedores, no solo el `SavedStateHandle` de este dueño)
 *      frente a 262 144 bytes, serializandolo con `Parcel.obtain()` + `writeBundle`/`dataSize()`.
 *
 *    Ninguno de los dos umbrales garantiza seguridad universal de Binder. Los escenarios y bytes
 *    efectivamente medidos se documentan junto a su APK, no se deducen de estas constantes.
 *
 * LIMITES DE EVIDENCIA: recrear Activity/registry no equivale a matar el proceso; un smoke con PID
 * distinto y campos visibles tampoco demuestra por si solo identidad interna ni todas las ramas.
 * Consultar el checkpoint para lo ejecutado y lo que sigue pendiente, sin asumir cierre global.
 */
class FvpFlowViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    /** Verdad persistida para reconciliar el borrador antes de adoptarlo (D6/R3). */
    private val persistedTruth: FvpPersistedTruthSource,
    /** Dispatcher del observador del borrador vivo; inyectable para poder probarlo en JVM. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Cota de espera de la lectura de la verdad persistida (red de seguridad anti-bloqueo). */
    private val truthLookupTimeoutMs: Long = FVP_TRUTH_LOOKUP_TIMEOUT_MS,
) : ViewModel(), FvpDraftMutations {

    private val _recoveryNotice = MutableStateFlow<FvpRecoveryNotice>(FvpRecoveryNotice.None)

    /** Aviso visible de recuperacion (o [FvpRecoveryNotice.None]). */
    val recoveryNotice: StateFlow<FvpRecoveryNotice> = _recoveryNotice.asStateFlow()

    /**
     * Ultimo borrador ya procesado por el dueño. Evita re-codificar el estado recien restaurado
     * (que ya tiene SU payload), o rearmar al iniciar en Home un borrador vivo sin payload tras una
     * salida terminal. La entrada explicita al flujo ([onStepVisible]) siempre vuelve a codificar.
     */
    private var lastStoredDraft: FvpDraft? = null

    /** Paso visible del flujo. Es metadato del payload; por defecto, el paso inicial. */
    private var currentStep: FvpFlowStep = FvpFlowStep.SETUP

    /**
     * `true` en cuanto el dueño ha visto UN paso real del flujo. Es lo que distingue "el usuario ha
     * salido del flujo hacia Home" de "la primera ruta observada es Home" (arranque o restauracion
     * de la pila): sin esta bandera, limpiar al ver Home borraria el payload recien restaurado.
     */
    private var hasSeenFlowStep: Boolean = false

    private val _reconciliationPending = MutableStateFlow(false)

    /**
     * `true` mientras la reconciliacion con la verdad persistida esta EN VUELO (A3). Es estado de
     * PRIMERA CLASE: los destinos del flujo no siembran formularios, no declaran caducidad, no
     * calculan ni guardan mientras este activo. Se publica de forma SINCRONA antes de lanzar la
     * consulta y se retira como ULTIMO paso de la resolucion (o de forma sincrona en la mutacion
     * que la supersede), de modo que quien lo vea en `false` ya tiene el borrador adoptado o el
     * rechazo aplicado.
     */
    val reconciliationPending: StateFlow<Boolean> = _reconciliationPending.asStateFlow()

    /**
     * Generacion de MUTACIONES EXTERNAS del borrador vivo (A2): se incrementa en CADA
     * begin/adopt/update/reset del dueño y en cada descarte explicito ([onLeftFlow]). Toda
     * resolucion capturada con una generacion anterior es OBSOLETA.
     */
    private var generation: Long = 0L

    /** Consulta de verdad persistida EN VUELO (una sola: la del `init`), cancelable. */
    private var reconciliationJob: Job? = null

    init {
        // PRECEDENCIA DE LA SESION VIVA (A2): un dueño NUEVO puede nacer con un borrador VIVO ya en
        // la sesion (Activity nueva en un proceso que SOBREVIVIO: la sesion es por proceso y NO
        // sobrevive a una muerte real). En ese caso la SESION es la fuente de verdad y el payload
        // retenido del dueño anterior es material VIEJO: no se decodifica, no se revalida, no se
        // reconcilia (CERO consultas a la base) y JAMAS se adopta por encima de lo vivo. La
        // recuperabilidad de un candidato RETENIDO se deriva de forma SINCRONA del borrador
        // ACTUAL COMPLETO ([storeDraft]), con la marca de vigencia de A1. Sin payload ni marca NO
        // se rearma nada aqui: una salida previa a Home pudo limpiar la recuperacion dejando solo
        // la sesion en memoria. La nueva Activity no convierte ese abandono en recuperable; solo
        // una entrada explicita a un paso ([onStepVisible]) o una nueva mutacion lo rearma.
        val liveDraft = FvpDraftSession.draft.value
        if (liveDraft != null) {
            if (savedStateHandle.contains(FVP_RECOVERY_PAYLOAD_KEY) || payloadIsInvalidated()) {
                storeDraft(liveDraft)
            } else {
                lastStoredDraft = liveDraft
            }
        } else {
            // ESTA rama es la restauracion: ocurre en el constructor SIN BLOQUEAR (A3) y publica
            // PENDIENTE de forma SINCRONA antes de que los destinos del flujo se compongan. Un plan
            // rechazado o ausente no toca la sesion y no deja nada pendiente.
            // ORDEN OBLIGATORIO (A1): la marca de vigencia se consulta ANTES de decodificar. Con un
            // payload retenido pero NO vigente, ese JSON no se decodifica, no se revalida, no se
            // reconcilia y no se adopta: un rechazo del borrador VIVO jamas resucita contenido viejo
            // ni la identidad ajena que pudiera llevar. Los bytes se conservan como material inerte.
            val retainedPayload = savedStateHandle.get<String>(FVP_RECOVERY_PAYLOAD_KEY)
            if (retainedPayload != null && payloadIsInvalidated()) {
                _recoveryNotice.value = FvpRecoveryNotice.Unrecoverable
            } else {
                when (val plan = fvpRecoverPlan(retainedPayload)) {
                    is FvpRecoverPlan.Restored -> launchReconciliation(plan.state)
                    is FvpRecoverPlan.Refused -> {
                        // El payload NO se borra (es lo unico que hay) NI se marca como actualizado: se
                        // avisa de que no hay recuperacion y se deja que el usuario empiece de nuevo.
                        _recoveryNotice.value = FvpRecoveryNotice.Unrecoverable
                    }
                    FvpRecoverPlan.Nothing -> Unit
                }
            }
        }
        viewModelScope.launch(dispatcher) { observeLiveDraft() }
    }

    /**
     * Publica [reconciliationPending] = `true` de forma SINCRONA y DESPUES lanza la consulta
     * acotada de la verdad persistida (A3). El constructor NUNCA espera a la base: mientras el
     * lookup este en vuelo, los destinos del flujo muestran el cuerpo de carga no interactivo.
     *
     * [generation] se captura al lanzar: si una mutacion externa o un descarte explicito la
     * incrementa, esta resolucion es OBSOLETA y no aplicara ningun efecto.
     */
    private fun launchReconciliation(state: FvpRecoveryState) {
        val capturedGeneration = generation
        _reconciliationPending.value = true
        reconciliationJob = viewModelScope.launch(dispatcher) {
            resolveReconciliation(state, capturedGeneration)
        }
    }

    /**
     * RESUELVE la reconciliacion de un estado restaurado con la verdad PERSISTIDA y adopta SOLO si
     * la decision es [FvpRecoveryReconciliation.ADOPT] (D6/R3).
     *
     * GUARDA DE OBSOLESCENCIA: justo despues de leer la verdad se comprueba la GENERACION capturada
     * ANTES de aplicar cualquier efecto (adopcion, limpieza del payload, aviso o retirada de la
     * bandera de pendiente). Una mutacion externa posterior al lanzamiento invalida esta resolucion
     * porque la verdad leida ya no describe al borrador vivo; la cancelacion sola NO es la garantia
     * (una fuente no cooperativa puede entregar la verdad tarde), asi que el guard cubre tambien las
     * salidas de fallo cerrado.
     *
     * VIDA DE LA CORRUTINA: la GENERACION no cambia cuando el dueño se LIMPIA
     * (`ViewModelStore.clear()` cancela el `viewModelScope`), de modo que la guarda de obsolescencia
     * no puede decidir ese caso. Por eso, y ANTES de cualquier efecto, se comprueba de forma EXPLICITA
     * que el trabajo sigue vivo ([currentCoroutineContext] + `ensureActive`): una fuente que se come la
     * cancelacion (p. ej. esperando dentro de `NonCancellable`) puede devolver su verdad -o el fallo
     * cerrado de su propia cota- DESPUES de que el dueño se haya limpiado, y un dueño muerto no puede
     * adoptar, ni limpiar el payload, ni publicar avisos.
     *
     * CANCELACION DE CICLO DE VIDA: [CancellationException] NO se reinterpreta como verdad ausente
     * (no publica aviso ni toca el candidato): se propaga, de modo que un dueño limpiado o un
     * `viewModelScope` cancelado no rechaza ni resucita nada.
     *
     * No-ADOPT: la verdad persistida manda. El borrador no llega a [FvpDraftSession] y el payload se
     * limpia; en [FvpRecoveryReconciliation.ALREADY_SAVED] no se publica aviso porque no hay nada
     * falso que decir con el copy disponible ("no se pudo recuperar" seria mentira sobre una serie
     * que SI esta guardada: ver la nota de copy en [FvpRecoveryNotice]).
     */
    private suspend fun resolveReconciliation(state: FvpRecoveryState, capturedGeneration: Long) {
        val truth = readPersistedTruthOrNull(state.draft)
        // VIDA DE LA CORRUTINA: la fuente puede entregar su verdad DESPUES de que el dueño se haya
        // limpiado, y limpiar el dueño NO cambia la generacion; la comprobacion va antes de CUALQUIER
        // efecto. `ensureActive()` lanza [CancellationException], que se propaga como cualquier otra
        // cancelacion de ciclo de vida (y por tanto no publica aviso ni toca el candidato).
        currentCoroutineContext().ensureActive()
        // OBSOLETA: la generacion cambio mientras la consulta estaba en vuelo -> cero efectos.
        if (fvpReconciliationIsStale(capturedGeneration = capturedGeneration, currentGeneration = generation)) {
            return
        }
        if (truth == null) {
            // FALLO CERRADO: la verdad persistida no se pudo leer (excepcion ordinaria o cota
            // agotada), de modo que NO se adopta y se dice. El payload se CONSERVA como candidato
            // todavia valido: un fallo de lectura no es un veredicto sobre el borrador y un intento
            // posterior puede recuperarlo.
            _recoveryNotice.value = FvpRecoveryNotice.Unrecoverable
            _reconciliationPending.value = false
            return
        }
        val reconciliation = fvpRecoveredStateReconciliation(
            state = state,
            persistedRowId = truth.persistedRowId,
            persistedAthleteId = truth.persistedAthleteId,
            athleteExists = truth.athleteExists,
            athleteArchived = truth.athleteArchived,
        )
        when (reconciliation) {
            FvpRecoveryReconciliation.ADOPT -> {
                // ORDEN OBLIGATORIO: el METADATO restaurado (paso real y borrador ya escrito) se fija
                // ANTES de publicar el borrador en la sesion. `FvpDraftSession.draft` es un
                // `StateFlow` y el observador del borrador vivo corre en el mismo despachador que
                // esta resolucion (en produccion `Dispatchers.Main.immediate`): al publicar, un
                // colector YA activo puede reanudarse EN LINEA dentro del propio `setter` y
                // re-codificar el payload con el paso VIEJO del dueño (`currentStep`, aun `SETUP`),
                // perdiendo el paso restaurado y reescribiendo bytes que YA eran correctos. Con el
                // metadato fijado antes, `observeLiveDraft` reconoce el borrador restaurado y no
                // re-codifica nada.
                currentStep = state.step
                lastStoredDraft = state.draft
                FvpDraftSession.adopt(state.draft)
                _recoveryNotice.value = FvpRecoveryNotice.None
            }
            FvpRecoveryReconciliation.ALREADY_SAVED,
            FvpRecoveryReconciliation.ATHLETE_GONE,
            FvpRecoveryReconciliation.MISMATCHED_OWNER,
            -> {
                clearPayload()
                _recoveryNotice.value = if (reconciliation == FvpRecoveryReconciliation.ALREADY_SAVED) {
                    FvpRecoveryNotice.None
                } else {
                    FvpRecoveryNotice.Unrecoverable
                }
            }
        }
        // ULTIMO paso: quien vea `pending == false` ya tiene todos los efectos aplicados.
        _reconciliationPending.value = false
    }

    /**
     * Lee la verdad persistida con una cota de espera; `null` = DESCONOCIDA (excepcion ordinaria o
     * cota agotada). Cualquier fallo ordinario se trata igual: nunca se convierte en "no hay fila ni
     * atleta" a partir de un error, porque eso adoptaria un borrador sin haber comprobado la verdad
     * persistida.
     *
     * [CancellationException] NO es un fallo de lectura: es CANCELACION (ciclo de vida) y se propaga
     * tal cual, sin convertirse en un veredicto sobre el borrador.
     */
    private suspend fun readPersistedTruthOrNull(draft: FvpDraft): FvpPersistedTruth? = try {
        withTimeoutOrNull(truthLookupTimeoutMs) { persistedTruth.truthFor(draft) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Comunica el paso REAL del flujo (ruta visible). Al entrar en una ruta del flujo se re-arma
     * la recuperacion desde el borrador VIVO: si el payload se limpio al salir a Home, volver al
     * flujo lo vuelve a codificar (nunca desde un payload viejo, siempre desde la sesion viva), y
     * un paso nuevo se refleja en el payload para que no declare un paso viejo. Sin borrador vivo
     * no hay nada que guardar.
     *
     * MIENTRAS LA RECONCILIACION ESTA PENDIENTE no se reescribe NADA (A3): el payload del candidato
     * es la unica copia de la serie que se esta restaurando y re-codificar por encima de ella
     * perderia el paso real guardado. Se anota el paso y la bandera de "paso visto" (metadatos del
     * propio dueño) solo cuando la resolucion los use.
     */
    fun onStepVisible(step: FvpFlowStep) {
        hasSeenFlowStep = true
        currentStep = step
        if (_reconciliationPending.value) return
        FvpDraftSession.draft.value?.let(::storeDraft)
    }

    /**
     * Salida terminal del flujo hacia Home: se limpia el payload para no resucitar un borrador
     * abandonado. NO toca el borrador vivo (resetearlo es responsabilidad de los destinos, que ya
     * lo hacen en sus salidas terminales); solo recorta el alcance de la recuperacion. Es un
     * DESCARTE explicito: supersede de forma SINCRONA cualquier reconciliacion pendiente (A2/A3),
     * porque la resolucion tardia de un candidato que el usuario ha abandonado no puede adoptar nada.
     *
     * NO limpia si el dueño todavia no ha visto NINGUN paso del flujo: en el arranque la primera
     * ruta observada puede ser Home (p. ej. al restaurar la pila) y limpiar ahi borraria un payload
     * que acaba de restaurarse. Una salida REAL del flujo siempre pasa antes por setup, condiciones
     * o resultado, de modo que [hasSeenFlowStep] es la condicion honesta (no `currentStep`, que es
     * no nulable y arranca en SETUP: usarlo aqui seria una condicion siempre falsa).
     */
    fun onLeftFlow() {
        if (!fvpLeftFlowClears(hasSeenFlowStep)) return
        supersedeRecovery()
        clearRecovery()
    }

    // --- A2: mutaciones externas del borrador vivo (contrato [FvpDraftMutations]) -------------------

    /**
     * SUPERSEDE de forma SINCRONA la reconciliacion y la recuperabilidad vigente: incrementa la
     * generacion, cancela la consulta en vuelo (si la hay), retira la bandera de pendiente e
     * invalida el payload retenido. Se ejecuta ANTES de aplicar cualquier cambio del borrador: a
     * partir de aqui, una resolucion tardia de la generacion anterior es obsoleta y no aplica
     * ningun efecto.
     */
    private fun supersedeRecovery() {
        generation++
        reconciliationJob?.cancel()
        reconciliationJob = null
        _reconciliationPending.value = false
        invalidatePayload()
    }

    override fun begin(athleteId: Long, protocol: FvpProtocol): FvpDraft {
        supersedeRecovery()
        val draft = FvpDraftSession.begin(athleteId, protocol)
        storeDraft(draft)
        return draft
    }

    override fun adopt(draft: FvpDraft): FvpDraft {
        supersedeRecovery()
        FvpDraftSession.adopt(draft)
        storeDraft(draft)
        return draft
    }

    /**
     * Aplica el cambio al borrador vivo. Si NO hay sesion viva el cambio no se aplica (misma regla
     * que la sesion) y la recuperacion queda SIN contenido: el estado actual es `null`, de modo que
     * no se conserva un payload que ya no representa ningun flujo abierto.
     */
    override fun update(transform: (FvpDraft) -> FvpDraft): FvpDraft? {
        supersedeRecovery()
        val updated = FvpDraftSession.update(transform)
        if (updated == null) clearRecovery() else storeDraft(updated)
        return updated
    }

    /** Descarte explicito: funciona INCLUSO si la sesion ya era `null` (un coletor no emitiria). */
    override fun reset() {
        supersedeRecovery()
        FvpDraftSession.reset()
        clearRecovery()
    }

    override fun resetIfSessionKey(sessionKey: String): Boolean {
        val live = FvpDraftSession.draft.value
        if (live == null || live.sessionKey != sessionKey) return false
        reset()
        return true
    }

    /**
     * Observa el unico borrador vivo y mantiene el payload en sincronia. No es una segunda fuente
     * de verdad: solo DERIVA el payload de la sesion.
     */
    private suspend fun observeLiveDraft() {
        var previous: FvpDraft? = null
        FvpDraftSession.draft.collect { draft ->
            when {
                // Un borrador vivo -> null es una SALIDA terminal (guardado/descarte/borrado): el
                // payload ya no representa ningun flujo abierto. El `null` inicial (arranque sin
                // sesion) NO es una salida y no borra nada.
                draft == null -> if (previous != null) clearRecovery()
                // El estado recien restaurado ya tiene SU payload: re-codificarlo lo reescribiria
                // sin motivo (las claves de memoria regeneradas cambian los bytes).
                draft === lastStoredDraft -> Unit
                else -> storeDraft(draft)
            }
            previous = draft
        }
    }

    /** Codifica el borrador con el paso actual y aplica la regla de actualizacion del payload. */
    private fun storeDraft(draft: FvpDraft) {
        val update = fvpDraftPayloadUpdate(FvpRecoveryState(draft = draft, step = currentStep))
        when (update) {
            is FvpDraftPayloadUpdate.Store -> {
                // Regla PURA de aplicacion ([fvpApplyPayloadUpdate]): con `Store` el payload a
                // escribir es el JSON nuevo y COMPLETO del borrador actual, nunca una mezcla con
                // los bytes anteriores.
                val next = fvpApplyPayloadUpdate(savedStateHandle.get(FVP_RECOVERY_PAYLOAD_KEY), update)
                // ORDEN OBLIGATORIO (A1): primero se invalida el payload vigente y solo despues se
                // escribe el nuevo; la marca se retira como ULTIMO paso, de modo que el estado
                // guardado nunca acredita como vigente un JSON que aun no esta escrito.
                invalidatePayload()
                savedStateHandle[FVP_RECOVERY_PAYLOAD_KEY] = next
                clearPayloadInvalidation()
                lastStoredDraft = draft
                _recoveryNotice.value = FvpRecoveryNotice.None
            }
            is FvpDraftPayloadUpdate.KeepPrevious -> {
                // RECHAZO: los bytes anteriores quedan EXACTAMENTE como estaban (no se escribe
                // nada en el payload) pero NO vigentes; queda activa la marca, de modo que una
                // reconstruccion no puede decodificarlos ni adoptarlos. El borrador vivo NO se
                // toca: el rechazo solo afecta a la recuperacion.
                invalidatePayload()
                _recoveryNotice.value = FvpRecoveryNotice.RefusedToStore(update.reason)
            }
            FvpDraftPayloadUpdate.Clear -> clearRecovery()
        }
    }

    /**
     * Activa la marca de NO vigencia del payload retenido. Se escribe ANTES de tocar el payload
     * (A1): a partir de aqui, y mientras siga activa, esos bytes son material inerte que una
     * reconstruccion no decodifica ni adopta.
     */
    private fun invalidatePayload() {
        savedStateHandle[FVP_RECOVERY_INVALIDATED_KEY] = true
    }

    /**
     * Retira la marca de NO vigencia. SOLO es correcto cuando el payload vigente ya es el JSON
     * completo y revalidado del borrador ACTUAL, o cuando se han descartado los bytes.
     */
    private fun clearPayloadInvalidation() {
        savedStateHandle.remove<Boolean>(FVP_RECOVERY_INVALIDATED_KEY)
    }

    /** `true` si el payload retenido esta marcado como NO vigente (inerte) en el estado guardado. */
    private fun payloadIsInvalidated(): Boolean =
        savedStateHandle.get<Boolean>(FVP_RECOVERY_INVALIDATED_KEY) == true

    /** Limpia el payload y el aviso: sin payload no hay nada que avisar. */
    private fun clearRecovery() {
        clearPayload()
        _recoveryNotice.value = FvpRecoveryNotice.None
    }

    /**
     * Salida TERMINAL: aqui si se descartan los bytes anteriores (y su marca: sin bytes no hay nada
     * que neutralizar), sin tocar el aviso (cada salida decide que dice).
     */
    private fun clearPayload() {
        savedStateHandle.remove<String>(FVP_RECOVERY_PAYLOAD_KEY)
        clearPayloadInvalidation()
        lastStoredDraft = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                FvpFlowViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    persistedTruth = fvpPersistedTruthSource(app.fvpRepository, app.athleteRepository),
                )
            }
        }
    }
}
