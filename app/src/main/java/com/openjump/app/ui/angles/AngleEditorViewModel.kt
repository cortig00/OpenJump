package com.openjump.app.ui.angles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleDiscardConfirmation
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorRejection
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMarkers
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleAnalysisEntity
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleMarkDraft
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.data.AngleRepository
import com.openjump.app.data.AngleUpdateDraft
import com.openjump.app.data.AngleUpdateResult
import com.openjump.app.data.NEW_ANGLE_MARK_ID
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.angles.AngleV2SaveRejection
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.PtsTimelineMapper
import com.openjump.app.ui.selector.ViewerTransport
import com.openjump.app.ui.selector.viewerOpeningFrameConfirmed
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoUriGrantReconciler
import com.openjump.app.video.angles.FrozenFrameSourceOpen
import com.openjump.app.video.angles.FrozenFrameSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Estado de apertura del vídeo del editor. Solo [Ready] habilita gestos: antes de tener índice real
 * no existe frame canónico ni PTS que confirmar.
 */
internal sealed interface AngleVideoState {

    data object Idle : AngleVideoState

    /** Registrando el grant, tomando el permiso persistible e indexando. */
    data object Opening : AngleVideoState

    /** El ViewModel ya está abierto sobre otro vídeo; abrir otro exige una instancia nueva. */
    data object AlreadyOpen : AngleVideoState

    data class Ready(
        val frameCount: Int,
        val frameIndex: Int,
        val sourcePtsUs: Long,
    ) : AngleVideoState

    /** El vídeo no está disponible. No se ha tocado ni el repositorio ni la fila guardada. */
    data class Unavailable(val reason: AngleVideoUnavailableReason) : AngleVideoState

    /** El editor se cerró y liberó; la instancia no vuelve a abrir vídeo. */
    data object Closed : AngleVideoState
}

/** Resultado del último intento de guardado; [Idle]/[Saving] describen el ciclo en curso. */
internal sealed interface AngleSaveState {

    data object Idle : AngleSaveState

    data object Saving : AngleSaveState

    /** No hay vídeo abierto: no se puede describir el sistema de coordenadas de las marcas. */
    data object NoVideo : AngleSaveState

    /** Sin ancla o sin ángulos no hay nada persistible; no se escribe para no borrar datos. */
    data object EmptyAnalysis : AngleSaveState

    data class Saved(val analysisId: Long, val revision: Int) : AngleSaveState

    /** Compare-and-swap fallido: la copia persistida cambió y no se ha sobrescrito nada. */
    data class Conflict(val analysisId: Long, val storedRevision: Int) : AngleSaveState

    /** La raíz guardada ya no existe (borrada en otra pantalla). */
    data object Deleted : AngleSaveState

    data class Failed(val message: String?) : AngleSaveState
}

/**
 * Recuperación pendiente de una edición que ya **no** se puede guardar sobre su análisis.
 *
 * Se publica como observable propio ([AngleEditorViewModel.saveRecovery]) y no se deduce del último
 * [AngleSaveState] porque ese estado describe **el último intento**, no la situación de la edición:
 * tras un «guardar como análisis nuevo» fallido el último intento es un `Failed` genérico, pero la
 * edición sigue sin destino y el usuario debe poder reintentar la recuperación sin perderla. Por eso
 * este estado solo se limpia cuando la edición queda adoptada de verdad (copia verificada o
 * reapertura del análisis persistido), nunca al publicar un fallo.
 *
 * Mientras no sea [None], el guardado normal no puede persistir la edición tal cual: la raíz de
 * destino ya no existe ([Deleted]), su contenido no se pudo verificar ([Unverified]) o avanzó a una
 * revisión que esta edición no adopta ([Conflict]), y sobrescribirla sería destructivo. La única
 * salida sin pérdida es guardarla como análisis **nuevo** ([AngleEditorViewModel.saveAsNew]).
 *
 * [Conflict] no cambia el resultado de un guardado normal: sigue publicándose
 * [AngleSaveState.Conflict] (sin escritura, sin adoptar la revisión ajena) y el siguiente intento
 * reenvía la misma `expectedRevision`. Lo que añade es la salida explícita para no perder el
 * borrador: la copia se crea con `sessionKey` derivado y deja intacta la fila que otro editor guardó.
 */
internal sealed interface AngleSaveRecovery {

    /** No hay nada pendiente: el guardado normal es seguro. */
    data object None : AngleSaveRecovery

    /** La raíz guardada se borró en otra pantalla; recrearla con su clave está prohibido. */
    data class Deleted(val analysisId: Long?) : AngleSaveRecovery

    /** La escritura anterior pudo efectuarse pero su foto no coincidió con lo enviado. */
    data class Unverified(val analysisId: Long?, val revision: Int?) : AngleSaveRecovery

    /**
     * El CAS falló: la copia persistida está en [storedRevision] y esta edición parte de una revisión
     * anterior. No se adopta la revisión ajena ni se pisa su contenido; la edición viva solo se puede
     * conservar como análisis nuevo.
     */
    data class Conflict(val analysisId: Long, val storedRevision: Int) : AngleSaveRecovery
}

/** Resultado de reabrir un análisis guardado sobre el vídeo ya abierto. */
internal sealed interface AngleReopenResult {

    data object Reopened : AngleReopenResult

    data object NoVideo : AngleReopenResult

    data object NotFound : AngleReopenResult

    data class Rejected(val reason: AngleReopenRejection) : AngleReopenResult

    data class Failed(val message: String?) : AngleReopenResult
}

/**
 * Estado publicado del último intento de reapertura.
 *
 * [reopenInternal][AngleEditorViewModel.reopenInternal] devuelve su resultado al llamante, pero la
 * UI no puede observar ese valor: este flujo publica **todas** las salidas (éxito, rechazo, fallo,
 * sin vídeo o análisis inexistente), de modo que la pantalla nunca se queda mostrando
 * [Reopening] cuando ya se decidió un resultado distinto.
 */
internal sealed interface AngleReopenState {

    data object Idle : AngleReopenState

    /** Lectura del análisis en curso; es el único estado transitorio. */
    data object Reopening : AngleReopenState

    data object Reopened : AngleReopenState

    data object NoVideo : AngleReopenState

    data object NotFound : AngleReopenState

    data class Rejected(val reason: AngleReopenRejection) : AngleReopenState

    data class Failed(val message: String?) : AngleReopenState
}

/** Proyección uno a uno de [AngleReopenResult] para el flujo observable; no pierde el motivo. */
internal fun AngleReopenResult.toReopenState(): AngleReopenState = when (this) {
    AngleReopenResult.Reopened -> AngleReopenState.Reopened
    AngleReopenResult.NoVideo -> AngleReopenState.NoVideo
    AngleReopenResult.NotFound -> AngleReopenState.NotFound
    is AngleReopenResult.Rejected -> AngleReopenState.Rejected(reason)
    is AngleReopenResult.Failed -> AngleReopenState.Failed(message)
}

/** Motivos por los que un análisis guardado no se rehidrata sobre el vídeo actual. */
internal enum class AngleReopenRejection {
    /** La identidad de la fuente o la URI no coinciden: nunca se mezclan análisis de dos vídeos. */
    SOURCE_MISMATCH,

    /** El análisis apunta a un frame que el índice actual no tiene. */
    FRAME_NOT_INDEXED,

    /** El PTS guardado no es el canónico del frame en el índice actual: no se inventa uno nuevo. */
    PTS_MISMATCH,

    /** Dimensiones de presentación o rotación distintas: las coordenadas no serían comparables. */
    GEOMETRY_MISMATCH,

    /** Modelo geométrico distinto del vigente; migrarlo queda fuera de este slice. */
    UNSUPPORTED_GEOMETRY,

    /** Marcas persistidas incoherentes (ordinales no consecutivos o terna no medible). */
    CORRUPT_MARKS,

    /**
     * El análisis guardado pertenece a otro atleta que la sesión abierta (o no declara dueño).
     * Rehidratarlo mezclaría análisis de dos atletas: se rechaza sin escribir nada.
     */
    ATHLETE_MISMATCH,

    /**
     * Hay un guardado en curso sobre el mismo editor: reabrir reemplazaría el estado y la revisión
     * a mitad de la escritura, así que se rechaza mientras dure (no se toca nada).
     */
    SAVE_IN_FLIGHT,
}

/** Resultado de una acción de edición sobre el estado actual. */
internal sealed interface AngleEditorActionResult {

    data object Applied : AngleEditorActionResult

    data class Rejected(val reason: AngleEditorRejection) : AngleEditorActionResult

    /** Todavía no hay estado de editor (vídeo no abierto). */
    data object NoEditor : AngleEditorActionResult

    /**
     * El gesto no pertenece al frame y la generación que el visor confirma ahora mismo: se aborta
     * sin tocar el estado (otro frame, otra superficie o player en marcha).
     */
    data object FrameNotConfirmed : AngleEditorActionResult

    /**
     * Hay un guardado en curso: la acción se rechaza sin tocar el estado. Permitir una edición a
     * mitad de escritura haría que la foto persistida y la edición viva divergieran sin que el
     * llamante pudiera saberlo.
     */
    data object SaveInFlight : AngleEditorActionResult

    /**
     * El ángulo es el último de un análisis YA persistido: borrarlo dejaría el análisis guardado
     * sin geometría y sin recuperación dentro del editor. Para eliminarlo hay que borrar el
     * análisis desde el historial (decisión explícita fuera de esta pantalla).
     */
    data object LastAngleProtected : AngleEditorActionResult
}

/** Puerta de escritura del agregado; aísla al ViewModel de Room para poder probarlo en JVM. */
internal interface AngleAnalysisStore {

    suspend fun saveNew(draft: AngleAnalysisDraft): Long

    suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult

    suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot?
}

/** Adaptador de producción sobre [AngleRepository] (única puerta transaccional ya existente). */
internal class RoomAngleAnalysisStore(
    private val repository: AngleRepository,
) : AngleAnalysisStore {

    override suspend fun saveNew(draft: AngleAnalysisDraft): Long = repository.saveNew(draft)

    override suspend fun update(draft: AngleUpdateDraft): AngleUpdateResult = repository.update(draft)

    override suspend fun snapshot(analysisId: Long): AngleAnalysisSnapshot? = repository.snapshot(analysisId)
}

/** Frontera de grants persistibles; el orden registrar → tomar permiso lo impone el ViewModel. */
internal interface AngleVideoGrants {

    fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease

    suspend fun takeReadGrant(uri: String)
}

internal class ReconcilerAngleVideoGrants(
    private val reconciler: VideoUriGrantReconciler,
) : AngleVideoGrants {

    override fun registerOwner(uris: Set<String>): VideoUriGrantReconciler.Lease =
        reconciler.registerOwner(uris)

    override suspend fun takeReadGrant(uri: String) = reconciler.takeReadGrant(uri)
}

/** Mapeo bidireccional entre ids locales del editor y filas de `angle_marks`. */
internal data class AnglePersistedBinding(
    val markIdByAngleId: Map<AngleId, Long>,
    val persistedMarkIds: Set<Long>,
)

/** Ángulos y mapeo reconstruidos desde marcas persistidas, con ids locales nuevos y estables. */
internal data class AngleRehydratedAngles(
    val angles: List<Angle>,
    val markIdByAngleId: Map<AngleId, Long>,
)

/**
 * Comprueba que un análisis guardado pertenece exactamente al vídeo y atleta abiertos.
 *
 * Orden determinista: atleta → fuente → modelo geométrico → geometría de presentación → frame →
 * PTS. No "adapta" nada: cualquier discrepancia se rechaza y los datos guardados quedan intactos.
 * El atleta se valida primero porque un análisis de otro atleta no debe rehidratarse en la sesión
 * actual ni siquiera para "advertir" después: se rechaza con [AngleReopenRejection.ATHLETE_MISMATCH].
 */
internal fun checkAngleAnalysisCompatibility(
    analysis: AngleAnalysisEntity,
    athleteId: Long,
    sourceIdentity: String,
    videoUri: String,
    index: VideoFrameIndex,
): AngleReopenRejection? {
    if (analysis.athleteId != athleteId) return AngleReopenRejection.ATHLETE_MISMATCH
    val storedIdentity = analysis.sourceIdentity
    val sameSource = if (storedIdentity != null) {
        storedIdentity == sourceIdentity
    } else {
        analysis.videoUri == videoUri
    }
    if (!sameSource) return AngleReopenRejection.SOURCE_MISMATCH
    if (analysis.geometryVersion != ANGLE_GEOMETRY_VERSION) {
        return AngleReopenRejection.UNSUPPORTED_GEOMETRY
    }
    val presentationWidth = if (index.rotationDegrees % 180 == 0) index.width else index.height
    val presentationHeight = if (index.rotationDegrees % 180 == 0) index.height else index.width
    if (analysis.rotationDegrees != index.rotationDegrees ||
        analysis.encodedWidth != index.width ||
        analysis.encodedHeight != index.height ||
        analysis.presentationWidth != presentationWidth ||
        analysis.presentationHeight != presentationHeight
    ) {
        return AngleReopenRejection.GEOMETRY_MISMATCH
    }
    if (analysis.frameIndex !in 0 until index.frameCount) {
        return AngleReopenRejection.FRAME_NOT_INDEXED
    }
    if (index.timeOf(analysis.frameIndex) != analysis.sourcePtsUs) {
        return AngleReopenRejection.PTS_MISMATCH
    }
    return null
}

/**
 * Reconstruye los ángulos de un análisis persistido: ordinales consecutivos desde 0, ids locales
 * `1..n` en ese orden y el mapeo inverso a las filas de Room. Devuelve `null` si las marcas no son
 * coherentes (no medibles, nombre vacío, ordinales repetidos): el llamante no debe adivinar.
 */
internal fun rehydrateAnglesFromMarks(marks: List<AngleMarkEntity>): AngleRehydratedAngles? {
    if (marks.isEmpty()) return null
    val ordered = marks.sortedWith(compareBy({ it.ordinal }, { it.id }))
    if (ordered.mapIndexed { position, mark -> mark.ordinal } != ordered.indices.toList()) return null
    return try {
        val angles = ordered.mapIndexed { position, mark ->
            Angle(
                id = AngleId(position + 1L),
                name = mark.name,
                colorArgb = mark.colorArgb,
                visible = mark.visible,
                markers = AngleMarkers(
                    a = AnglePoint(mark.pointAX, mark.pointAY),
                    b = AnglePoint(mark.pointBX, mark.pointBY),
                    c = AnglePoint(mark.pointCX, mark.pointCY),
                ),
            )
        }
        AngleRehydratedAngles(
            angles = angles,
            markIdByAngleId = angles.mapIndexed { position, angle -> angle.id to ordered[position].id }.toMap(),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Empareja las marcas recién persistidas con los ángulos en memoria **por ordinal** (el orden de
 * dibujo es la posición en la lista). Devuelve `null` si el conjunto no corresponde exactamente.
 *
 * Solo es seguro llamarlo después de [marksMatch]: emparejar por posición marcas cuyo contenido
 * no coincide entregaría al editor filas de otro contenido (y un guardado posterior las pisaría).
 */
internal fun bindPersistedMarks(
    angles: List<Angle>,
    marks: List<AngleMarkEntity>,
): AnglePersistedBinding? {
    if (angles.isEmpty() || marks.size != angles.size) return null
    val ordered = marks.sortedWith(compareBy({ it.ordinal }, { it.id }))
    if (ordered.mapIndexed { position, mark -> mark.ordinal } != angles.indices.toList()) return null
    return AnglePersistedBinding(
        markIdByAngleId = angles.mapIndexed { position, angle -> angle.id to ordered[position].id }.toMap(),
        persistedMarkIds = ordered.mapTo(linkedSetOf()) { it.id },
    )
}

/**
 * Marcas a persistir en el orden de la lista: [markIds] aporta la fila existente de cada ángulo y
 * [NEW_ANGLE_MARK_ID] marca las nuevas. Incluye **todos** los ángulos, también los ocultos: guardar
 * nunca debe perder ni cambiar la visibilidad de una marca.
 */
internal fun angleMarkDraftsForSave(
    angles: List<Angle>,
    markIds: Map<AngleId, Long>,
): List<AngleMarkDraft> = angles.mapIndexed { ordinal, angle ->
    AngleMarkDraft(
        id = markIds[angle.id] ?: NEW_ANGLE_MARK_ID,
        ordinal = ordinal,
        name = angle.name,
        colorArgb = angle.colorArgb,
        visible = angle.visible,
        pointA = angle.markers.a,
        pointB = angle.markers.b,
        pointC = angle.markers.c,
    )
}

/**
 * Igualdad **completa** entre lo enviado a persistir y lo releído: ordinal, nombre, color,
 * visibilidad y las seis coordenadas de cada marca.
 *
 * Los ids no se comparan a propósito: al insertar, Room asigna ids que el borrador no puede
 * predecir. La identidad que importa aquí es de contenido y de orden, de modo que un emparejamiento
 * por ordinal siempre asocie el mismo ángulo. Un hueco o un ordinal repetido invalida la igualdad.
 */
internal fun marksMatch(persisted: List<AngleMarkEntity>, expected: List<AngleMarkDraft>): Boolean {
    if (persisted.size != expected.size) return false
    val ordered = persisted.sortedWith(compareBy({ it.ordinal }, { it.id }))
    if (ordered.mapIndexed { position, mark -> mark.ordinal } != ordered.indices.toList()) return false
    return ordered.zip(expected).all { (stored, draft) ->
        stored.name == draft.name &&
            stored.colorArgb == draft.colorArgb &&
            stored.visible == draft.visible &&
            stored.pointAX == draft.pointA.x && stored.pointAY == draft.pointA.y &&
            stored.pointBX == draft.pointB.x && stored.pointBY == draft.pointB.y &&
            stored.pointCX == draft.pointC.x && stored.pointCY == draft.pointC.y
    }
}

/**
 * Verifica que el snapshot releído es **exactamente** el guardado nuevo que se envió, antes de
 * atribuir [AngleSaveState.Saved].
 *
 * `saveNew` es idempotente por `sessionKey`: un reintento puede devolver la raíz que ya existía, con
 * otro frame, otro PTS u otras marcas. Adoptar esa raíz por posición mapearía los ángulos en memoria
 * a filas ajenas y un guardado posterior pisaría datos que el usuario no editó. La identidad de la
 * URI del vídeo no se compara porque el repositorio permite que cambie (la identidad lógica es la
 * fuente); todo lo demás (atleta, fuente, geometría, frame, PTS y marcas) sí.
 */
internal fun snapshotMatchesDraft(snapshot: AngleAnalysisSnapshot, draft: AngleAnalysisDraft): Boolean {
    val analysis = snapshot.analysis
    return analysis.sessionKey == draft.sessionKey &&
        analysis.athleteId == draft.athleteId &&
        analysis.sourceIdentity == draft.sourceIdentity &&
        analysis.encodedWidth == draft.encodedWidth &&
        analysis.encodedHeight == draft.encodedHeight &&
        analysis.presentationWidth == draft.presentationWidth &&
        analysis.presentationHeight == draft.presentationHeight &&
        analysis.rotationDegrees == draft.rotationDegrees &&
        analysis.frameIndex == draft.frameIndex &&
        analysis.sourcePtsUs == draft.sourcePtsUs &&
        analysis.geometryVersion == draft.geometryVersion &&
        marksMatch(snapshot.marks, draft.marks)
}

/** Estado publicado cuando la edición viva ya no es la instantánea que se envió a persistir. */
private fun divergedSaveMessage(analysisId: Long, revision: Int): String =
    "La edición cambió durante el guardado: la revisión $revision del análisis $analysisId quedó " +
        "persistida con la instantánea enviada, pero la edición actual NO. Vuelve a guardar para " +
        "persistirla."

/**
 * Estado publicado cuando la clave de la sesión ya tiene otro contenido: la edición actual solo se
 * puede conservar como análisis nuevo.
 */
private fun existingCopyBlockedMessage(analysisId: Long, revision: Int): String =
    "Ya existe un análisis con la clave de esta sesión (id $analysisId, revisión $revision) y otro " +
        "contenido: no se adopta ni se sobrescribe. La edición actual solo se puede conservar como " +
        "análisis nuevo."

/**
 * Estado publicado cuando la clave derivada del intento ya materializó otro contenido: el intento se
 * cierra sin adoptar ni pisar la copia existente y se puede pedir otra explícitamente.
 */
private fun divergedCopyMessage(analysisId: Long, revision: Int): String =
    "La copia de este intento ya existe (id $analysisId, revisión $revision) con otro contenido: no " +
        "se adopta ni se sobrescribe. La edición actual sigue intacta; guarda de nuevo como análisis " +
        "nuevo para conservarla aparte."

/**
 * Estado publicado cuando la escritura se adoptó pero el editor conserva un borrador de ángulo sin
 * completar: no se informa `Saved` para que la pantalla no cierre el editor y descarte esos puntos.
 */
private fun pendingDraftExitMessage(analysisId: Long, revision: Int): String =
    "El guardado quedó persistido (revisión $revision del análisis $analysisId), pero el editor tiene " +
        "un borrador de ángulo sin completar: complétalo o descártalo antes de salir; sus puntos no se " +
        "han guardado."

/** La intención de copia ya no aplica: la recuperación pendiente se resolvió o nunca existió. */
private const val OBSOLETE_SAVE_AS_NEW_MESSAGE =
    "No hay ninguna edición que recuperar: la copia ya se guardó o la edición actual se guarda con el " +
        "botón normal."

/** Estado publicado cuando la escritura devolvió una foto que no es la enviada: no se adopta nada. */
private fun unverifiedSaveMessage(revision: Int): String =
    "El guardado no se pudo verificar contra lo enviado (revisión persistida $revision): la edición " +
        "actual sigue intacta y no se sobrescribe nada. Guarda como análisis nuevo o reabre el análisis."

/** Guardado bloqueado mientras la recuperación pendiente no se resuelva de forma explícita. */
private fun recoveryBlockedMessage(revision: Int?): String =
    "El guardado anterior quedó sin verificar" + (revision?.let { " (revisión $it)" } ?: "") +
        ": no se sobrescribe el análisis. Guarda la edición actual como análisis nuevo."

/** La reapertura tiene que esperar: reemplazaría el estado y la revisión a mitad de escritura. */
private const val REOPEN_IN_FLIGHT_MESSAGE = "Hay una reapertura en curso; reintenta el guardado después."

/**
 * El guardado mono-fotograma espera al multi-fotograma: describen la misma edición y no pueden
 * escribir a la vez con geometrías distintas.
 */
private const val MULTI_FRAME_SAVE_IN_FLIGHT_MESSAGE =
    "Hay un guardado multi-fotograma en curso; reintenta el guardado cuando termine."

/**
 * Detalle del rechazo cuando el intento se canceló sin cerrar el editor: no hay resultado
 * verificable, así que no se afirma ni éxito ni que no se escribiera nada.
 */
private const val CANCELED_BEFORE_PUBLISH =
    "El guardado multi-fotograma se canceló antes de publicar; no se afirma que no se escribiera nada."

/**
 * Cota de la espera a la primera salida renderizada del visor antes de reposicionar la sesión.
 *
 * Es una red de seguridad, no el caso normal: el frame de apertura se presenta en cuanto la
 * superficie recibe el player, así que la espera real es de milisegundos. Si el visor no llegara a
 * presentar nada, la reapertura falla sin mover el vídeo ni adoptar el análisis en vez de dejar la
 * pantalla bloqueada.
 */
private const val OPENING_FRAME_RENDER_TIMEOUT_MS = 5_000L

/** La reapertura no pudo anclar el visor: no se ha movido la sesión ni se ha adoptado nada. */
private const val OPENING_FRAME_NOT_RENDERED_MESSAGE =
    "El visor no llegó a presentar el frame de apertura: la reapertura no movió el vídeo ni adoptó " +
        "el análisis guardado."

/**
 * Puerta observable de los gestos: el motivo por el que [AngleEditorViewModel.currentConfirmation]
 * devuelve `null` **además** de la salida del visor.
 *
 * [AngleEditorViewModel.currentConfirmation] depende de dos cosas que no viven en
 * [com.openjump.app.ui.selector.FrameDisplayState]: el guardado en vuelo y la reapertura en vuelo.
 * Una reapertura puede terminar conservando el frame ya confirmado (el transporte no emite otra
 * salida), así que una pantalla que memorice la confirmación solo por `displayState` guarda un
 * `null` obsoleto y deja la UI esperando un frame que sí está confirmado. Esta puerta es el
 * observable que cambia en esas transiciones, con la misma fuente que las guardas del ViewModel.
 */
internal enum class AngleEditorGestureGate {
    /** Sin operación en vuelo: la confirmación sale de la salida viva del visor. */
    Open,

    /** Un guardado en vuelo: no se inicia geometría que la escritura en curso no vería. */
    SaveInFlight,

    /** Una reapertura en vuelo: el estado vivo está a punto de ser reemplazado. */
    ReopenInFlight,
}

/**
 * Estado de la confirmación del frame de apertura, que es el que fija el desfase PTS→frame en
 * [com.openjump.app.video.ViewerFrameAlignment].
 */
private enum class OpeningFrameRender {
    /** El transporte confirmó la salida del frame de apertura: el desfase está fijado. */
    Rendered,

    /** El player falló antes de presentar el frame de apertura. */
    PlayerFailed,

    /** El editor se cerró (o cambió de generación) mientras se esperaba. */
    Abandoned,

    /** Se agotó [OPENING_FRAME_RENDER_TIMEOUT_MS] sin salida ni error. */
    TimedOut,
}

/**
 * ViewModel del editor manual de ángulos: vídeo + transporte comunes, [AngleEditorState] como única
 * fuente de geometría y [AngleAnalysisStore] como única puerta de persistencia.
 *
 * ## Vídeo, grants y sesión
 *
 * - Abre **un** vídeo por instancia, sin `AppSession` ([AngleViewerSession]). El índice se calcula
 *   una vez y se comparte con el transporte, así que el frame canónico y su PTS son los reales.
 * - El permiso persistible se toma **después** de registrar el propietario (lease) y **antes** de
 *   transferir la URI al transporte. El lease dura **toda la vida del editor** y solo se cierra al
 *   cerrarlo (o al abandonar una apertura fallida); guardar no lo cierra. La fila persistida es la
 *   referencia durable para poder reabrir el análisis, pero no sustituye a la protección del vídeo
 *   que la pantalla sigue mostrando: si esa fila se borra en otra pantalla mientras el editor sigue
 *   vivo, un reconciliador sin propietario revocaría el último grant y el editor —y cualquier copia
 *   de recuperación que se guarde después— apuntaría a una URI ya ilegible. Cerrado el editor, la
 *   fila guardada (original o copia) mantiene el grant; solo se pide reconciliación al cerrar un
 *   lease, que es cuando el reconciliador existente decide si el permiso sigue siendo necesario.
 * - Si la URI no está disponible o el vídeo no se puede indexar, el editor queda
 *   [AngleVideoState.Unavailable] y **no** escribe, borra ni reasigna nada en el repositorio.
 *
 * ## Reapertura de un análisis guardado
 *
 * `reopen` valida atleta → fuente → modelo geométrico → geometría → frame → PTS contra el índice
 * real y rehidrata las marcas antes de tocar nada. El reposicionamiento del vídeo al frame del
 * análisis ocurre **después** de que el transporte haya presentado su primera salida (el frame de
 * apertura): ese primer render es el que fija el desfase PTS→frame del visor, y mover la sesión
 * antes dejaría el visor desplazado un número constante de frames (frame pedido != renderizado,
 * `canMark` falso para siempre) aunque la imagen en pantalla fuera la correcta. Mientras la
 * reapertura está en vuelo se rechazan transporte y gestos, porque cualquiera de los dos movería
 * ese frame de apertura. Si el visor no llega a presentar nada, la reapertura falla sin mover el
 * vídeo ni adoptar el análisis. El frame del análisis se pide de forma explícita al transporte
 * ([AngleViewerViewer.seekToFrame]) y no solo fijando el frame de la sesión: el flujo de la sesión
 * deduplica valores iguales y el colector ignora peticiones redundantes, así que la emisión sola no
 * garantiza un seek.
 *
 * ## Gestos
 *
 * [currentConfirmation] construye la confirmación desde **una única instantánea** de
 * `displayState` con `canMark`, usando el frame renderizado y su PTS canónico del índice. Nunca
 * inventa PTS: si el PTS renderizado no es el del índice, no hay confirmación. Las acciones de
 * coordenadas reciben esa confirmación y se abortan si ya no coincide con la instantánea viva
 * (otro frame, player en marcha o re-seek por superficie nueva) antes de tocar el estado.
 *
 * ## Persistencia
 *
 * - Primero `saveNew` (idempotente por `sessionKey`); después siempre `update` con
 *   `expectedRevision` (CAS). Un conflicto de CAS se publica como [AngleSaveState.Conflict] y no
 *   se sobrescribe nada; la revisión ajena **no** se adopta (el siguiente intento reenvía la misma
 *   `expectedRevision`), así que un editor cuya copia avanzó en otra pantalla nunca pisa lo ajeno.
 *   Ese conflicto publica además [AngleSaveRecovery.Conflict], que habilita la copia explícita: es
 *   la única forma de conservar el borrador de esta edición sin aceptar la revisión ajena.
 * - **Nunca se informa [AngleSaveState.Saved] sin verificar la foto persistida.** Tras `saveNew` se
 *   relee el snapshot y se exige igualdad completa (atleta, fuente, geometría, frame, PTS y
 *   contenido de las marcas por ordinal) con lo enviado: la idempotencia por `sessionKey` puede
 *   devolver una raíz con contenido viejo y adoptarla mapearía los ángulos por posición a filas
 *   ajenas. Si la foto no coincide, no se adopta ningún id ni mapeo; como `saveNew` no puede
 *   actualizar esa fila, se publica el estado de recuperación explícita (nunca un conflicto sin
 *   salida) y el guardado normal queda bloqueado para no reintentar en vano contra la misma clave.
 * - El guardado solo se informa [AngleSaveState.Saved] si el editor no conserva además un borrador
 *   de ángulo sin completar: el borrador nunca se escribe como marca incompleta, pero tampoco se
 *   descarta, así que cerrando el editor (navegación de `Saved`) se perdería. En ese caso la fila
 *   queda adoptada y se publica [AngleSaveState.Failed] para que la pantalla no cierre el editor;
 *   el usuario completa o descarta el borrador y el siguiente guardado va por CAS.
 * - Durante un guardado en curso las mutaciones de geometría y las operaciones de presentación se
 *   rechazan de forma explícita ([AngleEditorActionResult.SaveInFlight], transporte `false`,
 *   reapertura [AngleReopenRejection.SAVE_IN_FLIGHT]): la instantánea enviada no puede divergir de
 *   la edición viva. Aun así, antes de publicar `Saved` se compara el estado vivo con lo enviado; si
 *   divergiera, se publica [AngleSaveState.Failed] con la revisión efectiva (nunca `Saved`).
 * - Si `update` ya se efectuó pero su foto no es exactamente lo enviado, no se afirma conflicto
 *   (hubo escritura): se publica el estado de recuperación sin sobrescribir y se recuerda la
 *   revisión persistida. La recuperación explícita es [saveAsNewInternal]: guarda como análisis
 *   NUEVO con un `sessionKey` derivado que no pisa la raíz existente.
 * - `update` que responde `NotFound` publica [AngleSaveState.Deleted] y bloquea la sobrescritura: la
 *   edición solo se puede conservar guardándola como análisis nuevo (nuevo `sessionKey`).
 * - Las cuatro situaciones anteriores (raíz borrada, escritura sin verificar, clave de sesión
 *   ocupada con otro contenido y conflicto de CAS) se publican además en
 *   [saveRecovery], que se mantiene hasta que la edición quede adoptada de verdad: es el observable
 *   que la pantalla usa para ofrecer la única salida sin pérdida
 *   ([saveAsNew]). Un fallo de ese guardado no lo limpia, así que el usuario puede reintentarlo sin
 *   perder la edición ni el borrador. La copia es **explícita, de un solo uso por intención**: sin
 *   recuperación pendiente la acción se rechaza sin escribir (una segunda pulsación tras adoptar la
 *   copia no crea otra), y si la clave derivada del intento ya materializó otro contenido el intento
 *   se cierra para que una nueva pulsación explícita derive una clave distinta y no devuelva para
 *   siempre la copia vieja.
 * - Las marcas se envían siempre completas (incluidas las ocultas) y las bajas solo por
 *   `removedMarkIds` calculados desde el último mapeo persistido: guardar no puede perder marcas.
 * - El mapeo `AngleId` ↔ id de `angle_marks` se reconstruye desde las marcas persistidas por
 *   ordinal, también al reabrir un análisis, y solo cuando el contenido coincide.
 * - Borrar el último ángulo de un análisis ya persistido se rechaza
 *   ([AngleEditorActionResult.LastAngleProtected]) para no dejar la fila guardada sin geometría por
 *   un toque accidental; eliminarlo exige borrar el análisis desde el historial.
 *
 * Los métodos `*Internal` son la implementación suspend (probada en JVM); los métodos públicos sin
 * `Internal` los lanzan sobre [viewModelScope] y publican su resultado en los flujos.
 *
 * ## Sesión multi-fotograma (controlador y captura congelada; todavía sin UI ni guardado)
 *
 * [openMultiFrameSession] abre, de forma **perezosa**, una [AngleMultiFrameSession] sobre el mismo
 * vídeo ya abierto: fotogramas congelados independientes del player, geometría por fotograma y
 * préstamo del bitmap exacto a la capa de dibujo. Su estado se publica en [multiFrameState].
 *
 * Este slice **no** la conecta a la pantalla (los botones y la superficie siguen siendo los del flujo
 * mono-fotograma). El guardado V2 sí está cableado en el `ViewModel`
 * ([saveMultiFrame], [multiFrameSaveState]) con el puente real de [AngleMultiFrameSaveCoordinator],
 * pero la pantalla de fotogramas **no** lo invoca todavía: el botón de guardado sigue sin exponerse
 * hasta que el backup y la coordinación pasen su gate. Abrirla tampoco cuesta nada al flujo anterior:
 * copiar y sellar el vídeo es E/S cara y solo ocurre cuando se pide explícitamente. La reapertura de
 * un análisis descarta esa sesión (no hay lectura V2 que conserve sus fotogramas y arrastrarlos a la
 * edición reabierta los perdería en el primer guardado) y [close] la cierra antes de apagar su
 * ejecutor de E/S.
 *
 * ## Concurrencia y cierre
 *
 * - Las aperturas se serializan con un [Mutex]: dos `open` concurrentes no pueden pisarse el lease
 *   ni crear dos players; la segunda devuelve el estado vivo sin volver a publicar Opening.
 * - [close] es la única salida: cancela los trabajos del ViewModel (apertura, guardado, reapertura)
 *   y el scope propio del transporte, cierra el lease y libera el player. Toda continuación
 *   suspendida vuelve a comprobar el cierre antes de publicar o mutar nada, así que un cierre
 *   durante `open`/`save`/`reopen` no resucita estado ni player.
 */
internal class AngleEditorViewModel(
    private val store: AngleAnalysisStore,
    private val grants: AngleVideoGrants,
    private val requestReconcile: () -> Unit,
    private val indexer: AngleVideoIndexer,
    private val defaults: AngleEditorDefaults,
    createViewer: (AngleViewerSession, CoroutineScope) -> AngleViewerViewer,
    /**
     * Apertura de la fuente congelada de la sesión multi-fotograma.
     *
     * Es E/S bloqueante (copia, sellado e índice del vídeo completo) y el ViewModel la invoca siempre
     * en [Dispatchers.IO]. `null` significa que esta instancia no puede abrir sesiones
     * multi-fotograma: [openMultiFrameSession] publica entonces
     * [AngleMultiFrameUnavailableReason.OpenerUnavailable] en vez de fingir una sesión vacía. La
     * `factory` de producción siempre la aporta.
     */
    private val frozenSourceOpener: AngleFrozenSourceOpener? = null,
    /**
     * Coordinador del guardado multi-fotograma real (render → PNG → almacén → Room).
     *
     * `null` significa que esta instancia no puede guardar análisis multi-fotograma: la acción
     * publica [AngleMultiFrameSaveRejection.SaveNotWired] en vez de fingir un guardado. La `factory`
     * de producción siempre lo aporta; instanciarlo con `null` mantiene la instancia verificable en
     * JVM sin base de datos ni dispositivos.
     */
    private val multiFrameSaveCoordinator: AngleMultiFrameSaveCoordinator? = null,
    /**
     * Dueño de aplicación del guardado multi-fotograma ya iniciado
     * ([AngleMultiFrameSaveOwnership]).
     *
     * `null` significa que esta instancia no tiene ámbito de aplicación cableado: el guardado corre
     * en su propio `viewModelScope` y un cierre a mitad descartaría el resultado (comportamiento de
     * test, no el de producción). La `factory` siempre lo aporta.
     */
    private val multiFrameSaveOwnership: AngleMultiFrameSaveOwnership? = null,
) : ViewModel() {

    private val session = AngleViewerSession()

    /**
     * Scope propio de los trabajos que el transporte lanza en nombre del ViewModel. Es un
     * supervisor hijo de `viewModelScope` (mismo despachador, mismo aislamiento de fallos y misma
     * cancelación al limpiar), pero [close] puede cancelarlo sin matar el scope del ViewModel ni
     * dejar vivos collectors o seeks pendientes.
     */
    private val viewerJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val viewerScope = CoroutineScope(viewModelScope.coroutineContext + viewerJob)

    /** Única frontera Android del ViewModel: el transporte común del visor. */
    val viewer: AngleViewerViewer = createViewer(session, viewerScope)

    private val _editor = MutableStateFlow<AngleEditorState?>(null)
    val editor: StateFlow<AngleEditorState?> = _editor.asStateFlow()

    /**
     * Línea base semántica del contenido **verificado** como persistido (o del editor recién
     * abierto, que está vacío).
     *
     * `null` = desconocida: no se puede afirmar que el contenido vivo esté guardado, así que
     * cualquier contenido exige confirmar al cerrar.
     *
     * Solo se fija en tres puntos y **nunca** se limpia por un guardado que no verificó su foto:
     *
     * 1. apertura ([AngleEditorState.initial]): edición vacía, línea base vacía;
     * 2. reapertura verificada de un análisis persistido: la línea base es el estado rehidratado;
     * 3. escritura adoptada en [adoptPersisted]: la línea base es la **instantánea enviada** (ya
     *    releída e igual), no la edición viva, que puede haber divergido durante el guardado.
     *
     * Un fallo, un conflicto de CAS o una escritura no verificada no la tocan: la protección frente
     * a la pérdida silenciosa se conserva. La compara la política pura de cierre
     * ([angleEditorCloseDecision]); aquí solo se publica el valor que esa política necesita.
     */
    private val _closeBaseline = MutableStateFlow<AngleEditorContent?>(null)
    val closeBaseline: StateFlow<AngleEditorContent?> = _closeBaseline.asStateFlow()

    private val _videoState = MutableStateFlow<AngleVideoState>(AngleVideoState.Idle)
    val videoState: StateFlow<AngleVideoState> = _videoState.asStateFlow()

    private val _saveState = MutableStateFlow<AngleSaveState>(AngleSaveState.Idle)
    val saveState: StateFlow<AngleSaveState> = _saveState.asStateFlow()

    private val _saveRecovery = MutableStateFlow<AngleSaveRecovery>(AngleSaveRecovery.None)

    /**
     * Recuperación pendiente de la edición viva (ver [AngleSaveRecovery]).
     *
     * Es el observable que habilita en la UI la acción explícita «guardar como análisis nuevo»: se
     * mantiene mientras no se adopte una copia verificada, incluso si el último intento de guardado
     * falló, de modo que un fallo de la recuperación no deja al usuario sin forma de conservar su
     * edición.
     */
    val saveRecovery: StateFlow<AngleSaveRecovery> = _saveRecovery.asStateFlow()

    private val _reopenState = MutableStateFlow<AngleReopenState>(AngleReopenState.Idle)

    /** Resultado publicado de la última reapertura (todas las salidas, no solo el éxito). */
    val reopenState: StateFlow<AngleReopenState> = _reopenState.asStateFlow()

    private val _videoGeometry = MutableStateFlow<VideoPresentationGeometry?>(null)

    /**
     * Geometría de presentación del vídeo abierto, derivada del [VideoFrameIndex] real (dimensiones
     * codificadas + rotación) y **no** del player: el llamante no debe leer `Tracks` ni inferir la
     * rotación, porque Media3 puede no haber publicado el track cuando la capa ya se dibuja.
     */
    val videoGeometry: StateFlow<VideoPresentationGeometry?> = _videoGeometry.asStateFlow()

    private val _timelineMapper = MutableStateFlow<PtsTimelineMapper?>(null)

    /**
     * Timeline temporal del vídeo abierto, construida **una sola vez** por apertura a partir de los
     * `frameTimesUs` reales de [VideoFrameIndex] (el mismo índice que ya usa el transporte).
     *
     * Es la pieza que permite que el scrub posterior use el eje real de PTS —VFR con huecos— en
     * lugar de índices sintéticos o de una duración aproximada por FPS. El índice es la única
     * autoridad: no se expone el array de PTS ni se fabrica ninguna timeline con el player.
     *
     * Ciclo de vida, atado al del vídeo:
     *
     * - `null` antes de que la apertura tenga índice real (Idle/Opening), si la apertura falla y tras
     *   [close] (o un cierre/cancelación de la apertura en vuelo);
     * - no nulo desde que el vídeo queda [AngleVideoState.Ready] hasta el cierre de la instancia;
     * - la reapertura legacy de un análisis sobre el **mismo** vídeo no la reconstruye: conserva la
     *   instancia publicada (la fuente sigue siendo la misma, y su índice no ha cambiado).
     *
     * Orden de publicación: la instancia se construye del índice real antes de publicar
     * [AngleVideoState.Ready] y se publica **después** de ese Ready, solo si la apertura sigue
     * abierta y su generación es la propia. Un observador de Ready que cierre el editor de forma
     * reentrante no recibe una timeline que sobreviva al cierre, y ningún consumidor puede observar
     * una timeline no nula con el vídeo todavía abriéndose. Se pinta a `null` antes de cualquier
     * estado que deje de ser Ready. Dos `StateFlow` no se pueden mutar de forma atómica; por eso los
     * consumidores exigen Ready **y** timeline no nula.
     */
    val timelineMapper: StateFlow<PtsTimelineMapper?> = _timelineMapper.asStateFlow()

    val displayState: StateFlow<FrameDisplayState> get() = viewer.displayState
    val loading: StateFlow<Boolean> get() = viewer.loading
    val error: StateFlow<String?> get() = viewer.error
    val player: StateFlow<ExoPlayer?> get() = viewer.player

    /**
     * Id de la raíz guardada que esta edición edita **ahora**, si ya se guardó o se reabrió.
     *
     * No es una identidad estable de la instancia: una recuperación explícita
     * ([AngleSaveRecovery.Conflict]/[AngleSaveRecovery.Unverified] → [saveAsNew]) la mueve a la copia
     * adoptada, porque es la raíz donde el siguiente guardado escribirá por CAS. La identidad de
     * apertura de la sesión se guarda aparte en [openingAnalysisId].
     */
    var savedAnalysisId: Long? = null
        private set

    /**
     * Identidad de **apertura** de esta instancia: el análisis que pidió la primera reapertura
     * servida sobre el vídeo abierto (la que transporta la ruta del editor).
     *
     * Se fija una sola vez y no se mueve cuando la edición adopta otra raíz, porque la ruta sigue
     * nombrando la de apertura: al recrear la Activity la composición vuelve a pedir **ese** id
     * aunque este ViewModel sobreviva. Comparar solo [savedAnalysisId] dejaba pasar esa repetición
     * después de una recuperación explícita: adoptada la copia tras un conflicto o una escritura sin
     * verificar, la reapertura de la identidad de apertura releía la raíz original y reemplazaba el
     * editor, el ancla y los bindings de la copia, perdiendo el borrador pendiente.
     *
     * Un rechazo o un fallo no la consumen por sí solos (no adoptan nada): mientras la edición no
     * tenga un destino persistido propio, una petición posterior se reintenta de verdad y republica
     * su resultado real. Una vez hay destino persistido, una repetición de esta identidad es una
     * reemisión obsoleta de la ruta y no toca el estado. Una identidad **distinta** —navegación
     * deliberada a otro análisis— no coincide y sigue su curso normal.
     */
    var openingAnalysisId: Long? = null
        private set

    private val _gestureGate = MutableStateFlow(AngleEditorGestureGate.Open)

    /**
     * Puerta observable de los gestos, para que la UI recalcule su confirmación cuando cambia el
     * motivo del bloqueo sin esperar una nueva salida del visor. Nunca se publica [Open] antes de
     * que la operación en vuelo haya terminado (ver [AngleEditorGestureGate]).
     *
     * No sustituye a las guardas internas: [currentConfirmation] sigue comprobando los indicadores
     * reales, así que una puerta desincronizada no podría abrir un gesto que el ViewModel rechaza.
     */
    val gestureGate: StateFlow<AngleEditorGestureGate> = _gestureGate.asStateFlow()

    private data class OpenVideo(
        val request: AngleEditorRequest,
        val index: VideoFrameIndex,
    ) {
        /** Identidad lógica de la fuente: la URI canónica del vídeo abierto. */
        val sourceIdentity: String get() = request.uri

        val presentationWidth: Int get() = if (index.rotationDegrees % 180 == 0) index.width else index.height

        val presentationHeight: Int get() = if (index.rotationDegrees % 180 == 0) index.height else index.width
    }

    private var opened: OpenVideo? = null
    private var generation = 0L
    private var lease: VideoUriGrantReconciler.Lease? = null
    private var revision = 0
    private var boundAnchor: AngleFrameAnchor? = null
    private var markIdByAngleId: Map<AngleId, Long> = emptyMap()
    private var persistedMarkIds: Set<Long> = emptySet()
    private var saveInFlight = false
    private var reopenInFlight = false

    /**
     * Republica [gestureGate] desde los indicadores reales. Se llama **después** de cada cambio de
     * [saveInFlight] o [reopenInFlight], nunca antes: cerrar la puerta tarde abriría una ventana con
     * la operación ya en vuelo, y abrirla antes de tiempo dejaría confirmar geometría con el estado
     * a medio reemplazar.
     */
    private fun updateGestureGate() {
        _gestureGate.value = when {
            reopenInFlight -> AngleEditorGestureGate.ReopenInFlight
            saveInFlight -> AngleEditorGestureGate.SaveInFlight
            else -> AngleEditorGestureGate.Open
        }
    }

    /**
     * Serializa reaperturas de la instancia: la segunda espera y ve el estado ya adoptado.
     *
     * La idempotencia no usa un booleano de UI: la composición que dispara la reapertura usa
     * `remember`, que se reinicia al recrear la Activity, pero este ViewModel sobrevive a la
     * recreación. Se comparan dos identidades: la persistida vigente ([savedAnalysisId], la raíz que
     * esta edición edita ahora) y la de **apertura** ([openingAnalysisId], la que pidió la ruta), así
     * que una segunda petición del mismo `analysisId` no vuelve a leer la raíz por Room ni reemplaza
     * el editor, el ancla ni los bindings (perdería la edición sin guardar). Hacen falta las dos:
     * tras adoptar la copia de una recuperación, [savedAnalysisId] ya no coincide con el id de la
     * ruta y sin la de apertura la repetición pisaría el borrador pendiente con la raíz original. No
     * bloquea una recarga legítima: otro `analysisId`, otra instancia o una reapertura que no llegó a
     * adoptar nada (rechazo, fallo o vídeo aún sin abrir) siguen su curso normal.
     */
    private val reopenMutex = Mutex()

    /**
     * Una escritura anterior se efectuó pero no pudo verificarse: no se vuelve a escribir sobre esa
     * raíz (podría pisar contenido ajeno) hasta una recuperación explícita como análisis nuevo.
     */
    private var recoveryRequired = false

    /** Última revisión persistida conocida cuando la escritura no se pudo verificar. */
    private var recoveryRevision: Int? = null

    /** Intento de recuperación vigente: fija el `sessionKey` derivado para que un reintento sea idempotente. */
    private var saveAsNewAttempt = 0
    private var saveAsNewSessionKey: String? = null

    /**
     * Cerrado de forma definitiva. Se lee también desde el dueño de aplicación del guardado
     * multi-fotograma (otro hilo) para abortar antes de publicar imágenes.
     */
    @Volatile
    private var closed = false

    /**
     * Señal observable del cierre para las esperas suspendidas del ViewModel (la reapertura espera
     * la primera salida del visor): un cierre las despierta sin depender de que el transporte vuelva
     * a publicar estado después de soltar el player.
     */
    private val closedSignal = MutableStateFlow(false)

    /** Serializa aperturas: un lease, un índice y un player como máximo por instancia viva. */
    private val openMutex = Mutex()
    private var openJob: Job? = null
    private var saveJob: Job? = null
    private var reopenJob: Job? = null

    // --- ciclo de vida del vídeo -----------------------------------------------------------------

    /** Abre el vídeo de [request]. Lanza la carga en [viewModelScope] y publica [videoState]. */
    fun open(request: AngleEditorRequest) {
        // Un guardado multi-fotograma está leyendo la fuente sellada y sus leases: abrir otro vídeo
        // descartaría esa sesión a mitad de la transacción. Se ignora el cambio hasta que termine.
        if (multiFrameSaveInFlight.get()) return
        openJob = viewModelScope.launch { openInternal(request) }
    }

    /**
     * Lectura síncrona de la guarda de salida: `true` mientras una escritura (mono-fotograma o
     * multi-fotograma) está en vuelo.
     *
     * La pantalla la consulta en el mismo instante del toque para no decidir con un valor de
     * composición viejo: una escritura que empieza entre la última recomposición y el toque no
     * puede quedar puenteada por un diálogo de descarte ya abierto.
     */
    fun isSaveInFlight(): Boolean = saveInFlight || multiFrameSaveInFlight.get()

    /**
     * Cierra el editor: cancela los trabajos del ViewModel y del transporte, libera player, lease y
     * estado. Idempotente y definitivo: la instancia no vuelve a abrir vídeo.
     */
    fun close() {
        if (closed) return
        closed = true
        closedSignal.value = true
        openJob?.cancel()
        saveJob?.cancel()
        reopenJob?.cancel()
        multiFrameSaveJob?.cancel()
        openJob = null
        saveJob = null
        reopenJob = null
        multiFrameSaveJob = null
        // Un guardado multi-fotograma ya comprometido pertenece al dueño de aplicación: su resultado
        // real (y las claves huérfanas) se registran allí. Publicar Idle aquí diría «nada guardado»
        // de una transacción que puede haber confirmado.
        if (!multiFrameSaveInFlight.get()) {
            _multiFrameSaveState.value = AngleMultiFrameSaveState.Idle
        }
        // La identidad del borrador es la clave base de la sesión (estable ante recreación y muerte
        // del proceso), así que aquí solo se reinicia el intento: un reintento del mismo borrador
        // vuelve a derivar la misma clave (idempotente) en lugar de crear una raíz nueva por haberse
        // cerrado el editor.
        multiFrameDraftAttempt = 0
        viewerJob.cancel()
        // La sesión multi-fotograma se descarta ANTES de apagar su ejecutor: el cierre de la fuente
        // se encola en él y `shutdown()` deja drenar la cola, así que el snapshot sellado se libera
        // igualmente. Un lease que la pantalla siga teniendo abierto mantiene sus píxeles hasta que
        // lo cierre: no se recicla nada por debajo de un dibujo en vuelo.
        discardMultiFrameSession(AngleMultiFrameState.Closed)
        multiFrameIo.shutdown()
        releaseLease()
        viewer.cancelPendingScrubRelease()
        viewer.release()
        opened = null
        boundAnchor = null
        markIdByAngleId = emptyMap()
        persistedMarkIds = emptySet()
        recoveryRequired = false
        recoveryRevision = null
        saveAsNewSessionKey = null
        saveAsNewAttempt = 0
        _closeBaseline.value = null
        _editor.value = null
        _videoGeometry.value = null
        _timelineMapper.value = null
        _saveState.value = AngleSaveState.Idle
        _saveRecovery.value = AngleSaveRecovery.None
        _reopenState.value = AngleReopenState.Idle
        _videoState.value = AngleVideoState.Closed
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    internal suspend fun openInternal(request: AngleEditorRequest): AngleVideoState {
        if (closed) return AngleVideoState.Closed
        // Serializa aperturas concurrentes: la segunda espera y, si la primera ya abrió, devuelve
        // el estado vivo sin registrar otro lease, reindexar ni crear otro player.
        return openMutex.withLock { openLocked(request) }
    }

    private suspend fun openLocked(request: AngleEditorRequest): AngleVideoState {
        if (closed) return AngleVideoState.Closed
        val current = opened
        if (current != null) {
            // Ya hay vídeo: ni se republica Opening ni se vuelve a publicar Ready.
            return if (current.request.uri == request.uri) _videoState.value else AngleVideoState.AlreadyOpen
        }
        _videoState.value = AngleVideoState.Opening
        // Registro síncrono del propietario ANTES de tocar la sesión o el transporte: el
        // reconciliador no puede liberar el permiso en la ventana entre la transferencia y la toma.
        lease = grants.registerOwner(setOf(request.uri))
        try {
            grants.takeReadGrant(request.uri)
            if (closed) return abandonOpen()
            val index = indexer.index(request.uri)
            if (closed) return abandonOpen()
            require(index.frameCount > 0) { "El vídeo no contiene frames analizables." }
            val initialFrame = request.initialFrameIndex.coerceIn(0, index.frameCount - 1)
            generation += 1L
            // Identidad de ESTA apertura: la publicación de la timeline comprueba después que
            // sigue siendo la generación propia y que el editor no se cerró mientras Ready se
            // publicaba (un observador de Ready puede cerrar de forma reentrante).
            val openGeneration = generation
            session.prepare(request.uri, index, initialFrame)
            if (closed) return abandonOpen()
            opened = OpenVideo(request, index)
            val initialEditor = AngleEditorState.initial(
                sourceIdentity = request.uri,
                generation = generation,
                defaults = defaults,
            )
            _editor.value = initialEditor
            // Editor recién abierto y vacío: la línea base conocida es «nada», así que navegar o
            // seleccionar no exige confirmar al cerrar y el primer punto sí.
            _closeBaseline.value = angleEditorBaselineContent(initialEditor)
            // Geometría del índice real: el llamante no necesita (ni debe) mirar el player.
            _videoGeometry.value = VideoPresentationGeometry.fromEncoded(
                width = index.width,
                height = index.height,
                rotationDegrees = index.rotationDegrees,
            )
            // Timeline temporal del índice REAL: una instancia por vídeo abierto, construida una
            // sola vez aquí (la reapertura legacy no la reconstruye). `PtsTimelineMapper` copia los
            // PTS, así que la timeline no puede cambiar aunque el índice se reutilice fuera.
            // Se construye ANTES de Ready pero se publica DESPUÉS: un consumidor que observe Ready
            // nunca puede leer una timeline que todavía no corresponde a un vídeo listo.
            val timeline = PtsTimelineMapper(index.frameTimesUs)
            _videoState.value = AngleVideoState.Ready(
                frameCount = index.frameCount,
                frameIndex = initialFrame,
                sourcePtsUs = index.timeOf(initialFrame),
            )
            if (!closed && generation == openGeneration) {
                _timelineMapper.value = timeline
            }
            viewer.start()
            viewer.startSessionCollection()
            if (closed) {
                // Cierre durante el arranque: el player recién creado no puede quedar vivo.
                viewer.cancelPendingScrubRelease()
                viewer.release()
                return AngleVideoState.Closed
            }
            return _videoState.value
        } catch (cancelled: CancellationException) {
            releaseLease()
            throw cancelled
        } catch (failure: AngleVideoUnavailableException) {
            return failOpen(failure.reason)
        } catch (failure: Exception) {
            // Mismo criterio que el adaptador: solo permiso/fichero ausente es URI_UNAVAILABLE.
            return failOpen(angleVideoUnavailableReasonFor(failure))
        }
    }

    /** El editor se cerró con la apertura suspendida: no se resucita estado, lease ni player. */
    private fun abandonOpen(): AngleVideoState {
        opened = null
        _editor.value = null
        _videoGeometry.value = null
        _timelineMapper.value = null
        releaseLease()
        return AngleVideoState.Closed
    }

    private fun failOpen(reason: AngleVideoUnavailableReason): AngleVideoState {
        opened = null
        _editor.value = null
        _videoGeometry.value = null
        _timelineMapper.value = null
        releaseLease()
        // Un fallo posterior al cierre no republica Unavailable: el editor ya está cerrado.
        if (closed) return AngleVideoState.Closed
        val state = AngleVideoState.Unavailable(reason)
        _videoState.value = state
        return state
    }

    // --- reapertura ------------------------------------------------------------------------------

    /** Reabre un análisis guardado sobre el vídeo ya abierto. */
    fun reopen(analysisId: Long) {
        reopenJob = viewModelScope.launch { reopenInternal(analysisId) }
    }

    /**
     * Reabre un análisis guardado sobre el vídeo ya abierto y publica el resultado en
     * [reopenState].
     *
     * El vídeo solo se reposiciona al frame del análisis cuando el transporte ya presentó su frame
     * de apertura (ver [awaitOpeningFrameRendered]); hasta entonces no se adopta nada y el
     * transporte queda cerrado ([reopenInFlight]).
     */
    internal suspend fun reopenInternal(analysisId: Long): AngleReopenResult {
        if (closed) return AngleReopenResult.NoVideo
        // Reabrir reemplaza estado, ancla y revisión: hacerlo a mitad de una escritura dejaría al
        // guardado comparando contra un estado que ya no es el suyo.
        if (saveInFlight) {
            return publishReopen(AngleReopenResult.Rejected(AngleReopenRejection.SAVE_IN_FLIGHT))
        }
        // Reabrir descarta la sesión multi-fotograma (no hay escritor que conserve sus fotogramas).
        // La exclusión con el guardado no se decide con banderas leídas por separado (TOCTOU): se
        // reclama la propiedad de la sesión de forma atómica y, si el guardado la tiene, se rechaza
        // sin esperar. El reclamo se mantiene hasta el final de la reapertura, así que el descarte
        // de la sesión ocurre siempre sin un guardado en vuelo y viceversa.
        return reopenMutex.withLock {
            val claim = claimMultiFrameWrite(MultiFrameWriteKind.Reopen)
                ?: return@withLock publishReopen(
                    AngleReopenResult.Rejected(AngleReopenRejection.SAVE_IN_FLIGHT),
                )
            try {
                reopenLocked(analysisId)
            } finally {
                // La puerta se libera siempre, también si la reapertura se cancela a mitad.
                releaseMultiFrameWrite(claim)
            }
        }
    }

    /**
     * Reapertura serializada e idempotente por instancia e identidad.
     *
     * Si esta instancia ya sirvió esa identidad, la petición devuelve [Reopened] sin volver a leer
     * la raíz ni tocar el editor: es lo que ocurre al recrear la Activity con este ViewModel vivo.
     * Hay dos casos, y se comparan por separado porque son identidades distintas:
     *
     * - el destino persistido vigente ya es ese análisis ([savedAnalysisId]);
     * - la identidad de **apertura** ya servida es ese análisis ([openingAnalysisId]) y la edición
     *   tiene ya otro destino persistido: la copia adoptada tras un conflicto o una escritura sin
     *   verificar. La ruta reemite su id original al recrear, y releerlo reemplazaría el editor, el
     *   ancla y los bindings de la copia (perdiendo el borrador pendiente).
     *
     * Un rechazo o un fallo no adoptan nada, así que no cierran por sí solos la identidad: mientras
     * no haya destino persistido, una petición posterior vuelve a intentarlo y republica su
     * resultado real (nunca un éxito falso).
     */
    private suspend fun reopenLocked(analysisId: Long): AngleReopenResult {
        if (closed) return AngleReopenResult.NoVideo
        if (savedAnalysisId != null && savedAnalysisId == analysisId) return AngleReopenResult.Reopened
        if (savedAnalysisId != null && openingAnalysisId == analysisId) return AngleReopenResult.Reopened
        if (opened == null) return publishReopen(AngleReopenResult.NoVideo)
        if (analysisId <= 0L) return publishReopen(AngleReopenResult.NotFound)
        // Primera petición servida sobre este vídeo: fija la identidad de apertura de la instancia.
        if (openingAnalysisId == null) openingAnalysisId = analysisId

        reopenInFlight = true
        updateGestureGate()
        _reopenState.value = AngleReopenState.Reopening
        val result = try {
            reopenLive(analysisId)
        } finally {
            reopenInFlight = false
            // La puerta solo se abre cuando la reapertura ya terminó (o falló): el frame que confirma
            // después es el que el transporte dejó vivo, no uno inventado.
            updateGestureGate()
        }
        return publishReopen(result)
    }

    private suspend fun reopenLive(analysisId: Long): AngleReopenResult {
        val open = opened ?: return AngleReopenResult.NoVideo
        if (analysisId <= 0L) return AngleReopenResult.NotFound
        val analysisGeneration = generation
        val snapshot = try {
            store.snapshot(analysisId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AngleReopenResult.Failed(failure.message)
        } ?: return AngleReopenResult.NotFound
        if (closed || generation != analysisGeneration) return AngleReopenResult.NoVideo

        checkAngleAnalysisCompatibility(
            analysis = snapshot.analysis,
            athleteId = open.request.athleteId,
            sourceIdentity = open.sourceIdentity,
            videoUri = open.request.uri,
            index = open.index,
        )?.let { return AngleReopenResult.Rejected(it) }

        val rehydrated = rehydrateAnglesFromMarks(snapshot.marks)
            ?: return AngleReopenResult.Rejected(AngleReopenRejection.CORRUPT_MARKS)
        val anchor = AngleFrameAnchor(
            sourceIdentity = open.sourceIdentity,
            frameIndex = snapshot.analysis.frameIndex,
            sourcePtsUs = snapshot.analysis.sourcePtsUs,
        )
        val state = try {
            AngleEditorState.rehydrate(
                sourceIdentity = open.sourceIdentity,
                generation = analysisGeneration,
                anchor = anchor,
                angles = rehydrated.angles,
                defaults = defaults,
                selectedAngleId = null,
                externallyPersistedAngleIds = rehydrated.angles.mapTo(linkedSetOf()) { it.id },
            )
        } catch (_: IllegalArgumentException) {
            return AngleReopenResult.Rejected(AngleReopenRejection.CORRUPT_MARKS)
        }
        // El cierre puede haber ocurrido mientras se leía y reconstruía: no se rehidrata nada.
        if (closed || generation != analysisGeneration) return AngleReopenResult.NoVideo

        // ORDEN CRÍTICO. El transporte ancla la alineación PTS→frame en el frame de APERTURA y fija
        // el desfase con la PRIMERA salida del player. Reposicionar la sesión antes de esa salida
        // hace que el ancla sea otro frame: todos los PTS se traducen con un desfase constante, el
        // visor pinta un índice y la sesión pide otro, `canMark` no se cumple nunca y el editor se
        // queda en "esperando el frame confirmado" aunque la imagen correcta esté en pantalla (y
        // guardar, que solo necesita ancla y ángulos, siga permitido). Por eso se espera al frame de
        // apertura ANTES de mover la sesión y de adoptar el análisis; un rechazo o un fallo previos
        // no esperan a nadie.
        when (awaitOpeningFrameRendered(analysisGeneration)) {
            OpeningFrameRender.Rendered -> Unit
            OpeningFrameRender.Abandoned -> return AngleReopenResult.NoVideo
            OpeningFrameRender.TimedOut -> return AngleReopenResult.Failed(OPENING_FRAME_NOT_RENDERED_MESSAGE)
            OpeningFrameRender.PlayerFailed -> return AngleReopenResult.Failed(viewer.error.value)
        }
        if (closed || generation != analysisGeneration) return AngleReopenResult.NoVideo

        // La reapertura reemplaza la edición viva por la persistida. La sesión multi-fotograma
        // pertenece a otra edición y todavía no tiene escritor de persistencia: se descarta aquí en
        // vez de arrastrar sus fotogramas a la edición reabierta, donde el primer guardado los
        // perdería en silencio. Los leases que la pantalla tenga abiertos siguen siendo válidos hasta
        // que los cierre (sus píxeles no se reciclan por debajo del dibujo en vuelo).
        discardMultiFrameSession(AngleMultiFrameState.NotOpened)

        savedAnalysisId = analysisId
        revision = snapshot.analysis.revision
        boundAnchor = anchor
        markIdByAngleId = rehydrated.markIdByAngleId
        persistedMarkIds = snapshot.marks.mapTo(linkedSetOf()) { it.id }
        // La reapertura adopta una revisión persistida verificada y reemplaza la edición viva: la
        // recuperación pendiente sobre la raíz anterior deja de aplicar (el mensaje del guardado
        // bloqueado ya la ofrecía como salida) y el siguiente `update` parte de esta revisión.
        recoveryRequired = false
        recoveryRevision = null
        _saveRecovery.value = AngleSaveRecovery.None
        _editor.value = state
        // Reapertura verificada: la línea base es el contenido persistido que se acaba de adoptar.
        _closeBaseline.value = angleEditorBaselineContent(state)
        _saveState.value = AngleSaveState.Idle
        // El player vuelve al frame del análisis; su confirmación real habilitará los gestos. Se pide
        // EXPLÍCITAMENTE además de fijar el frame de la sesión: `setFrameIndex` es un StateFlow que
        // deduplica valores iguales y el colector del transporte ignora una petición que ya es el
        // objetivo pendiente, así que confiar solo en la emisión dejaría el visor sin buscar (y sin
        // confirmar nunca) cuando el frame pedido coincide con el que la sesión ya tenía. La petición
        // de reapertura conserva la confirmación solo si ese frame ya está confirmado en pausa con el
        // player vivo; en cualquier otro caso reinicia la fuente para exigir una salida real, porque
        // un seek a la misma posición no emite callback. Aquí no se inventa ni el frame ni el PTS.
        session.setFrameIndex(snapshot.analysis.frameIndex)
        viewer.reopenFrame(snapshot.analysis.frameIndex)
        _videoState.value = AngleVideoState.Ready(
            frameCount = open.index.frameCount,
            frameIndex = snapshot.analysis.frameIndex,
            sourcePtsUs = snapshot.analysis.sourcePtsUs,
        )
        return AngleReopenResult.Reopened
    }

    /** Publica el resultado de la reapertura salvo que el editor se haya cerrado entretanto. */
    private fun publishReopen(result: AngleReopenResult): AngleReopenResult {
        if (!closed) _reopenState.value = result.toReopenState()
        return result
    }

    /**
     * Espera a que el transporte publique el frame de **apertura**: la salida que coincide con lo
     * pedido y que fija el desfase PTS→frame en `ViewerFrameAlignment`. Devuelve en cuanto ese frame
     * está confirmado, el player falla o el editor se cierra; sin esa salida ni error agota
     * [OPENING_FRAME_RENDER_TIMEOUT_MS].
     *
     * Una salida cualquiera **no** basta ([viewerOpeningFrameConfirmed]): si la primera salida no es
     * el frame pedido, el desfase queda corrido y dar la reapertura por buena publicaría un éxito
     * con los gestos deshabilitados para siempre. En ese caso se sigue esperando una salida que sí
     * coincida y, si no llega, la reapertura falla sin mover la sesión ni adoptar nada.
     *
     * No se puede inferir de [AngleVideoState.Ready] ni de `loading`: el player ya está preparado
     * antes de presentar el primer frame, y es justo esa ventana la que rompía el ancla.
     */
    private suspend fun awaitOpeningFrameRendered(generationSnapshot: Long): OpeningFrameRender {
        val settled: Triple<FrameDisplayState, String?, Boolean>? = withTimeoutOrNull(
            OPENING_FRAME_RENDER_TIMEOUT_MS,
        ) {
            combine(viewer.displayState, viewer.error, closedSignal) { display, error, closedNow ->
                Triple(display, error, closedNow)
            }.first { (display, error, closedNow) ->
                viewerOpeningFrameConfirmed(display) || error != null || closedNow
            }
        }
        return when {
            settled == null -> OpeningFrameRender.TimedOut
            settled.third || closed || generation != generationSnapshot -> OpeningFrameRender.Abandoned
            viewerOpeningFrameConfirmed(settled.first) -> OpeningFrameRender.Rendered
            else -> OpeningFrameRender.PlayerFailed
        }
    }

    // --- guardado --------------------------------------------------------------------------------

    /** Guarda (o actualiza con CAS) el análisis actual. */
    fun save() {
        saveJob = viewModelScope.launch { saveInternal() }
    }

    /**
     * Recuperación explícita: guarda la edición actual como un análisis **nuevo**.
     *
     * No sobrescribe la raíz cuyo guardado se borró en otra pantalla, la que no se pudo verificar ni
     * la que avanzó a otra revisión por un conflicto de CAS: usa un `sessionKey` derivado propio, de
     * modo que un reintento es idempotente sin pisar el análisis original ni perder la edición viva.
     * El conflicto de CAS no adopta la revisión ajena precisamente porque esta copia es la salida:
     * aceptarla cambiaría la fila de otro editor. Solo aplica con una recuperación pendiente: sin
     * ella la intención es obsoleta (la copia ya se adoptó) y se rechaza sin escribir otra raíz.
     */
    fun saveAsNew() {
        saveJob = viewModelScope.launch { saveAsNewInternal() }
    }

    internal suspend fun saveInternal(): AngleSaveState {
        if (closed) return AngleSaveState.NoVideo
        if (saveInFlight) return _saveState.value
        // Nada que escribir: la última escritura no pudo verificarse y no se pisa la raíz.
        if (recoveryRequired) {
            return publishSave(AngleSaveState.Failed(recoveryBlockedMessage(recoveryRevision)))
        }
        return runSave { open, editor, anchor ->
            if (savedAnalysisId == null) {
                saveNew(open, editor, anchor)
            } else {
                saveUpdate(editor, anchor)
            }
        }
    }

    internal suspend fun saveAsNewInternal(): AngleSaveState {
        if (closed) return AngleSaveState.NoVideo
        // Sin recuperación pendiente no hay edición sin destino que conservar: la copia anterior ya
        // se adoptó (o nunca hubo nada que recuperar). Crear otra clave derivada duplicaría el
        // análisis sin una segunda intención real del usuario, así que la intención obsoleta se
        // rechaza sin escribir.
        if (_saveRecovery.value == AngleSaveRecovery.None) {
            return publishSave(AngleSaveState.Failed(OBSOLETE_SAVE_AS_NEW_MESSAGE))
        }
        return runSave { open, editor, anchor -> saveAsNewCopy(open, editor, anchor) }
    }

    /**
     * Puerta común de las dos escrituras: exige vídeo, edición persistible y ausencia de otra
     * operación en curso, y publica [AngleSaveState.Saving] mientras dura la escritura.
     */
    private suspend fun runSave(
        write: suspend (OpenVideo, AngleEditorState, AngleFrameAnchor) -> AngleSaveState,
    ): AngleSaveState {
        if (closed) return AngleSaveState.NoVideo
        val open = opened ?: return publishSave(AngleSaveState.NoVideo)
        val editor = _editor.value ?: return publishSave(AngleSaveState.NoVideo)
        if (saveInFlight) return _saveState.value
        if (reopenInFlight) return publishSave(AngleSaveState.Failed(REOPEN_IN_FLIGHT_MESSAGE))
        // Exclusión simétrica con el guardado multi-fotograma: mientras dura (o mientras la sesión
        // multi-fotograma tiene su propiedad reclamada por un guardado o una reapertura), esta
        // escritura no entra. Es la misma invariante que [withMultiFrameSession] aplica en sentido
        // contrario, y evita que dos rutas de escritura describan geometrías distintas a la vez.
        if (multiFrameSaveInFlight.get() || multiFrameWriteOwner.get() != null) {
            return publishSave(AngleSaveState.Failed(MULTI_FRAME_SAVE_IN_FLIGHT_MESSAGE))
        }
        val anchor = editor.anchor ?: return publishSave(AngleSaveState.EmptyAnalysis)
        if (editor.angles.isEmpty()) return publishSave(AngleSaveState.EmptyAnalysis)

        saveInFlight = true
        updateGestureGate()
        _saveState.value = AngleSaveState.Saving
        val result = try {
            write(open, editor, anchor)
        } catch (cancelled: CancellationException) {
            if (!closed) _saveState.value = AngleSaveState.Idle
            throw cancelled
        } catch (failure: Exception) {
            AngleSaveState.Failed(failure.message)
        } finally {
            saveInFlight = false
            updateGestureGate()
        }
        // Un cierre durante el guardado ya publicó Idle: no se resucita resultado.
        if (closed) return AngleSaveState.NoVideo
        _saveState.value = result
        when (result) {
            is AngleSaveState.Deleted -> {
                // Único embudo de publicación de `Deleted`: la raíz ya no existe, así que la edición
                // solo se puede conservar como análisis nuevo (nunca recreando la clave original).
                _saveRecovery.value = AngleSaveRecovery.Deleted(savedAnalysisId)
            }

            is AngleSaveState.Conflict -> {
                // Único embudo de publicación del conflicto de CAS. `Conflict` sigue siendo el
                // resultado del intento (no se adopta la revisión ajena ni se pisa nada), y la
                // recuperación explícita evita que la edición viva quede sin destino cuando el
                // otro editor ya guardó: la copia usa un `sessionKey` derivado.
                _saveRecovery.value = AngleSaveRecovery.Conflict(result.analysisId, result.storedRevision)
            }

            else -> Unit
        }
        return result
    }

    private fun analysisDraft(
        open: OpenVideo,
        editor: AngleEditorState,
        anchor: AngleFrameAnchor,
        sessionKey: String,
    ): AngleAnalysisDraft = AngleAnalysisDraft(
        sessionKey = sessionKey,
        athleteId = open.request.athleteId,
        videoUri = open.request.uri,
        sourceIdentity = open.sourceIdentity,
        encodedWidth = open.index.width,
        encodedHeight = open.index.height,
        presentationWidth = open.presentationWidth,
        presentationHeight = open.presentationHeight,
        rotationDegrees = open.index.rotationDegrees,
        frameIndex = anchor.frameIndex,
        sourcePtsUs = anchor.sourcePtsUs,
        marks = angleMarkDraftsForSave(editor.angles, emptyMap()),
    )

    private suspend fun saveNew(
        open: OpenVideo,
        editor: AngleEditorState,
        anchor: AngleFrameAnchor,
    ): AngleSaveState {
        val draft = analysisDraft(open, editor, anchor, open.request.sessionKey)
        // saveNew es idempotente por sessionKey: un reintento devuelve la raíz existente, que puede
        // tener otro frame, otro PTS u otras marcas.
        val analysisId = store.saveNew(draft)
        if (closed) return AngleSaveState.NoVideo
        val snapshot = store.snapshot(analysisId)
            ?: return AngleSaveState.Failed("El análisis $analysisId se guardó pero no se pudo releer.")
        if (closed) return AngleSaveState.NoVideo
        // Sin igualdad completa no se adopta ni se mapea nada: atribuir Saved aquí daría por
        // persistida una edición que no está en la fila devuelta.
        if (!snapshotMatchesDraft(snapshot, draft)) {
            // La clave de sesión es de ESTE editor: una fila con otro contenido en ella solo puede
            // venir de un intento anterior cuya verificación falló (su foto no se pudo releer) y que
            // el usuario siguió editando. `saveNew` nunca actualiza, así que reintentar aquí devuelve
            // esa copia para siempre: un `Conflict` sin salida dejaría la edición sin destino. Se
            // publica la recuperación explícita, sin adoptar ni pisar la fila existente.
            return blockOnExistingCopy(analysisId, snapshot.analysis.revision)
        }
        val binding = bindPersistedMarks(editor.angles, snapshot.marks)
            ?: return adoptUnverifiedWrite(snapshot.analysis.revision)
        return adoptPersisted(
            analysisId = analysisId,
            editor = editor,
            anchor = anchor,
            storedRevision = snapshot.analysis.revision,
            markIds = binding.markIdByAngleId,
            persistedIds = binding.persistedMarkIds,
        )
    }

    private suspend fun saveUpdate(
        editor: AngleEditorState,
        anchor: AngleFrameAnchor,
    ): AngleSaveState {
        val analysisId = savedAnalysisId ?: return AngleSaveState.Deleted
        if (boundAnchor != anchor) {
            return AngleSaveState.Failed("El análisis guardado pertenece a otro frame; reábrelo para editar.")
        }
        val marks = angleMarkDraftsForSave(editor.angles, markIdByAngleId)
        val keptMarkIds = marks.mapNotNullTo(linkedSetOf()) { mark ->
            mark.id.takeIf { it != NEW_ANGLE_MARK_ID }
        }
        val removed = persistedMarkIds - keptMarkIds
        val result = store.update(
            AngleUpdateDraft(
                analysisId = analysisId,
                expectedRevision = revision,
                marks = marks,
                removedMarkIds = removed,
            ),
        )
        if (closed) return AngleSaveState.NoVideo
        return when (result) {
            is AngleUpdateResult.Updated -> {
                val snapshot = result.snapshot
                // La escritura YA se efectuó: si la foto no es exactamente lo enviado no se afirma
                // conflicto (sería falso) ni se adopta nada por posición.
                if (!marksMatch(snapshot.marks, marks)) {
                    return adoptUnverifiedWrite(snapshot.analysis.revision)
                }
                val binding = bindPersistedMarks(editor.angles, snapshot.marks)
                    ?: return adoptUnverifiedWrite(snapshot.analysis.revision)
                adoptPersisted(
                    analysisId = analysisId,
                    editor = editor,
                    anchor = anchor,
                    storedRevision = snapshot.analysis.revision,
                    markIds = binding.markIdByAngleId,
                    persistedIds = binding.persistedMarkIds,
                )
            }

            // CAS: no hubo escritura, así que el conflicto es exacto y no se sobrescribe nada. La
            // revisión ajena NO se adopta aquí (`revision` sigue siendo la de esta edición): el
            // siguiente guardado normal reenvía la misma `expectedRevision` y vuelve a conflicto en
            // vez de pisar lo que guardó el otro editor. La salida sin pérdida la publica el embudo
            // de [runSave] como [AngleSaveRecovery.Conflict].
            is AngleUpdateResult.Conflict -> AngleSaveState.Conflict(analysisId, result.storedRevision)

            // La raíz se borró en otra pantalla: no se recrea con la misma clave desde aquí.
            AngleUpdateResult.NotFound -> AngleSaveState.Deleted
        }
    }

    /**
     * Guarda la edición como raíz nueva con un `sessionKey` derivado (idempotente por intento).
     *
     * Si la clave derivada ya existiera con otro contenido no se adopta ni se pisa: el intento queda
     * cerrado (la siguiente pulsación explícita deriva otra clave) y la edición sigue intacta.
     */
    private suspend fun saveAsNewCopy(
        open: OpenVideo,
        editor: AngleEditorState,
        anchor: AngleFrameAnchor,
    ): AngleSaveState {
        val sessionKey = saveAsNewSessionKey
            ?: recoverySessionKey(open.request.sessionKey, saveAsNewAttempt).also {
                saveAsNewSessionKey = it
            }
        val draft = analysisDraft(open, editor, anchor, sessionKey)
        val analysisId = store.saveNew(draft)
        if (closed) return AngleSaveState.NoVideo
        val snapshot = store.snapshot(analysisId)
            ?: return AngleSaveState.Failed("El análisis $analysisId se guardó pero no se pudo releer.")
        if (closed) return AngleSaveState.NoVideo
        if (!snapshotMatchesDraft(snapshot, draft)) {
            // La clave derivada ya materializó OTRO contenido (una copia anterior de este mismo
            // intento, creada y luego editada): no se pisa ni se adopta. El intento queda cerrado
            // para que un nuevo toque explícito derive una clave distinta, en vez de devolver esta
            // copia para siempre con un `Conflict` sin salida.
            closeSaveAsNewAttempt()
            return AngleSaveState.Failed(divergedCopyMessage(analysisId, snapshot.analysis.revision))
        }
        val binding = bindPersistedMarks(editor.angles, snapshot.marks)
            ?: return adoptUnverifiedWrite(snapshot.analysis.revision)
        // La copia existe: este intento queda cerrado y el siguiente `saveAsNew` explícito crea otra.
        closeSaveAsNewAttempt()
        return adoptPersisted(
            analysisId = analysisId,
            editor = editor,
            anchor = anchor,
            storedRevision = snapshot.analysis.revision,
            markIds = binding.markIdByAngleId,
            persistedIds = binding.persistedMarkIds,
        )
    }

    /** Cierra el intento de copia: el siguiente toque explícito derivará una clave nueva. */
    private fun closeSaveAsNewAttempt() {
        saveAsNewSessionKey = null
        saveAsNewAttempt += 1
    }

    /**
     * La clave de esta sesión ya tiene una fila con otro contenido y [AngleRepository.saveNew] no la
     * puede actualizar: no se adopta (mapear por posición pisaría filas ajenas) ni se pisa, y el
     * guardado normal queda bloqueado en vez de reintentar en vano contra la misma fila. La única
     * salida sin pérdida es la copia explícita ([saveAsNew]), con clave derivada propia.
     */
    private fun blockOnExistingCopy(analysisId: Long, storedRevision: Int): AngleSaveState {
        recoveryRequired = true
        recoveryRevision = storedRevision
        _saveRecovery.value = AngleSaveRecovery.Unverified(analysisId, storedRevision)
        return AngleSaveState.Failed(existingCopyBlockedMessage(analysisId, storedRevision))
    }

    /**
     * La escritura pudo efectuarse pero su foto no se pudo verificar contra lo enviado: no se adopta
     * ningún id ni mapeo (mapear por posición pisaría filas ajenas) y se bloquea el siguiente
     * `update`. La revisión persistida se recuerda para informar sin adivinar.
     */
    private fun adoptUnverifiedWrite(storedRevision: Int): AngleSaveState {
        recoveryRequired = true
        recoveryRevision = storedRevision
        _saveRecovery.value = AngleSaveRecovery.Unverified(savedAnalysisId, storedRevision)
        return AngleSaveState.Failed(unverifiedSaveMessage(storedRevision))
    }

    /**
     * Adopta una fila recién persistida **ya verificada** y decide la salida del guardado.
     *
     * Si el estado vivo cambió respecto de la instantánea enviada, la fila queda adoptada (la
     * revisión efectiva permite un CAS seguro en el siguiente intento) pero **nunca** se informa
     * [AngleSaveState.Saved]: la edición actual no está persistida y el llamante debe reintentar.
     */
    private fun adoptPersisted(
        analysisId: Long,
        editor: AngleEditorState,
        anchor: AngleFrameAnchor,
        storedRevision: Int,
        markIds: Map<AngleId, Long>,
        persistedIds: Set<Long>,
    ): AngleSaveState {
        savedAnalysisId = analysisId
        revision = storedRevision
        boundAnchor = anchor
        markIdByAngleId = markIds
        persistedMarkIds = persistedIds
        recoveryRequired = false
        recoveryRevision = null
        // La edición ya está adoptada como raíz verificada: no queda recuperación pendiente.
        _saveRecovery.value = AngleSaveRecovery.None
        // La línea base es la instantánea ENVIADA y verificada, nunca la edición viva: si el editor
        // cambió durante el guardado (o conserva un borrador), esa divergencia sigue siendo contenido
        // sin guardar y el cierre debe confirmarla. Un guardado que no llega aquí (fallo, conflicto,
        // foto no verificada) no toca la línea base.
        _closeBaseline.value = angleEditorBaselineContent(editor)
        val current = _editor.value ?: editor
        val diverged = current.anchor != anchor || current.angles != editor.angles
        // Un borrador A/B a medias no es persistible (nunca se escribe como marca incompleta), pero
        // tampoco se descarta: informar `Saved` haría que la pantalla cerrara el editor y perdiera
        // esos puntos. La escritura sí quedó adoptada; el usuario decide completando o descartando el
        // borrador, y el siguiente guardado ya va por CAS sobre la fila adoptada.
        val pendingDraft = current.draft.points.isNotEmpty()
        _editor.value = markPersisted(current, markIds.keys)
        // El lease de la URI NO se cierra aquí, aunque la fila ya esté persistida: el editor sigue
        // vivo mostrando ese vídeo y puede recibir más ediciones (el borrador pendiente de arriba es
        // el caso normal). La fila guardada es la referencia durable para reabrir el análisis, pero
        // no protege al reconciliador de este editor: si esa fila se borra en otra pantalla mientras
        // la pantalla sigue abierta, un reconciliador sin propietario revocaría el último grant y el
        // vídeo en vivo —y la copia que se guarde luego para conservar la edición— quedaría sin
        // permiso de lectura. El lease se libera en [close], una sola vez, y solo entonces se pide
        // reconciliación; así tampoco se solapa la liberación con una escritura posterior.
        return when {
            pendingDraft -> AngleSaveState.Failed(pendingDraftExitMessage(analysisId, storedRevision))
            diverged -> AngleSaveState.Failed(divergedSaveMessage(analysisId, storedRevision))
            else -> AngleSaveState.Saved(analysisId, storedRevision)
        }
    }

    private fun markPersisted(editor: AngleEditorState, angleIds: Set<AngleId>): AngleEditorState {
        var state = _editor.value ?: editor
        angleIds.forEach { angleId ->
            when (val outcome = state.markExternallyPersisted(angleId)) {
                is AngleEditorOutcome.Applied -> state = outcome.state
                is AngleEditorOutcome.Rejected -> Unit
            }
        }
        return state
    }

    private fun publishSave(state: AngleSaveState): AngleSaveState {
        _saveState.value = state
        return state
    }

    private fun releaseLease() {
        val held = lease ?: return
        lease = null
        held.close()
        requestReconcile()
    }

    // --- transporte ------------------------------------------------------------------------------

    /**
     * Paso relativo exacto de fotograma (+1 siguiente / -1 anterior) delegado en el transporte
     * común. El ViewModel no crea otro player ni mueve el frame por su cuenta. `false` = rechazado
     * (sin vídeo, editor cerrado o guardado en curso).
     */
    fun stepFrames(delta: Int): Boolean = withOpenVideo { viewer.stepFrames(delta) }

    /** Reproduce o pausa con el player existente; `false` si la acción se rechaza. */
    fun togglePlayback(): Boolean = withOpenVideo { viewer.togglePlayback() }

    /** Pausa explícita con el player existente (sin inventar frame si ya estaba en pausa). */
    fun pausePlayback(): Boolean = withOpenVideo { viewer.pausePlayback() }

    /** Abre una interacción de scrubbing (arrastre del timeline) sobre el player existente. */
    fun beginScrubbing(): Boolean = withOpenVideo { viewer.beginScrubbing() }

    /** Busca un frame indexado durante el scrubbing; el índice real acota el rango. */
    fun scrubToFrame(frameIndex: Int): Boolean = withOpenVideo { open ->
        viewer.scrubToFrame(frameIndex.coerceIn(0, open.index.frameCount - 1))
    }

    /** Cierra el scrubbing: exige el render del target antes de habilitar gestos. */
    fun endScrubbing(): Boolean = withOpenVideo { viewer.endScrubbing() }

    /**
     * Puerta de todas las acciones de transporte: sin vídeo abierto, con el editor cerrado o con un
     * guardado o una reapertura en curso no se delega nada (y nunca se crea un player nuevo desde
     * aquí).
     *
     * Durante el guardado la presentación también se rechaza: mover el frame o reanudar la
     * reproducción a mitad de escritura cambiaría la confirmación con la que se capturó la
     * instantánea persistida. Durante una reapertura se rechaza por el mismo motivo de orden: el
     * transporte fija el desfase PTS→frame con la PRIMERA salida del player y la reapertura depende
     * de que esa salida siga siendo el frame de apertura; mover la sesión antes dejaría el visor
     * desplazado (frame pedido != frame renderizado) para siempre.
     */
    private inline fun withOpenVideo(block: (OpenVideo) -> Unit): Boolean {
        if (closed || saveInFlight || reopenInFlight) return false
        val open = opened ?: return false
        block(open)
        return true
    }

    // --- gestos ----------------------------------------------------------------------------------

    /**
     * Confirmación del frame visible para iniciar un gesto, o `null` si no hay frame confirmado.
     *
     * Se construye desde una única instantánea de [FrameDisplayState]: exige `canMark` (en pausa,
     * renderizado == pedido) y que el PTS renderizado sea exactamente el PTS canónico del índice
     * para ese frame. No hay ninguna vía para fabricar una confirmación con PTS inventado. Durante
     * un guardado en curso no hay confirmación: no se puede empezar una geometría que la escritura
     * en vuelo no vería. Durante una reapertura tampoco: el estado vivo está a punto de ser
     * reemplazado por la instantánea persistida.
     *
     * La UI no memoriza esta función solo por `displayState`: [gestureGate] publica estas dos
     * mismas transiciones, porque una reapertura puede terminar sin una nueva salida del visor
     * (conserva el frame ya confirmado) y la caché de la pantalla quedaría con un `null` obsoleto.
     */
    fun currentConfirmation(): AngleFrameConfirmation? {
        if (saveInFlight || reopenInFlight) return null
        val open = opened ?: return null
        val display = viewer.displayState.value
        if (!display.canMark) return null
        val renderedIndex = display.renderedIndex ?: return null
        val renderedPtsUs = display.renderedPtsUs ?: return null
        if (renderedIndex !in 0 until open.index.frameCount) return null
        if (open.index.timeOf(renderedIndex) != renderedPtsUs) return null
        return AngleFrameConfirmation(
            frame = AngleFrameAnchor(
                sourceIdentity = open.sourceIdentity,
                frameIndex = renderedIndex,
                sourcePtsUs = renderedPtsUs,
            ),
            generation = generation,
            paused = true,
        )
    }

    /** Coloca el siguiente punto del borrador (A → B → C) en el frame confirmado. */
    fun placePoint(
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
        selectOnComplete: Boolean = true,
    ): AngleEditorActionResult = applyGesture(confirmation) {
        it.placePoint(point, confirmation, selectOnComplete)
    }

    /** Arrastra una marca de un ángulo existente en el frame confirmado. */
    fun moveMarker(
        angleId: AngleId,
        marker: AngleMarker,
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
    ): AngleEditorActionResult = applyGesture(confirmation) {
        it.moveMarker(angleId, marker, point, confirmation)
    }

    /** Arrastra una marca ya colocada del borrador en el frame confirmado. */
    fun moveDraftMarker(
        marker: AngleMarker,
        point: AnglePoint,
        confirmation: AngleFrameConfirmation,
    ): AngleEditorActionResult = applyGesture(confirmation) {
        it.moveDraftMarker(marker, point, confirmation)
    }

    /** Descarta los puntos parciales del borrador (no toca ángulos ni ancla). */
    fun cancelDraft(): AngleEditorActionResult = mutate { it.cancelDraft() }

    /** Selecciona un ángulo visible, o limpia la selección con `null`. */
    fun select(angleId: AngleId?): AngleEditorActionResult = mutate { it.select(angleId) }

    fun rename(angleId: AngleId, name: String): AngleEditorActionResult = mutate { it.rename(angleId, name) }

    fun setColor(angleId: AngleId, colorArgb: Int): AngleEditorActionResult =
        mutate { it.setColor(angleId, colorArgb) }

    /** Oculta o muestra un ángulo; un ángulo oculto se sigue guardando en el siguiente guardado. */
    fun setVisible(angleId: AngleId, visible: Boolean): AngleEditorActionResult =
        mutate { it.setVisible(angleId, visible) }

    /** Borra un ángulo; el siguiente guardado enviará su marca en `removedMarkIds`. */
    fun deleteAngle(angleId: AngleId): AngleEditorActionResult {
        if (saveInFlight) return AngleEditorActionResult.SaveInFlight
        val editor = _editor.value ?: return AngleEditorActionResult.NoEditor
        // B8: borrar el último ángulo de un análisis YA guardado dejaría la fila persistida sin
        // geometría; la baja del análisis entero pertenece al historial, no a un toque accidental.
        if (savedAnalysisId != null && editor.angles.size == 1 && editor.angles.single().id == angleId) {
            return AngleEditorActionResult.LastAngleProtected
        }
        return publishOutcome(editor.deleteAngle(angleId))
    }

    private fun applyGesture(
        confirmation: AngleFrameConfirmation,
        action: (AngleEditorState) -> AngleEditorOutcome,
    ): AngleEditorActionResult {
        if (saveInFlight) return AngleEditorActionResult.SaveInFlight
        val editor = _editor.value ?: return AngleEditorActionResult.NoEditor
        if (!gestureIsCurrent(confirmation)) return AngleEditorActionResult.FrameNotConfirmed
        return publishOutcome(action(editor))
    }

    private fun mutate(action: (AngleEditorState) -> AngleEditorOutcome): AngleEditorActionResult {
        if (saveInFlight) return AngleEditorActionResult.SaveInFlight
        val editor = _editor.value ?: return AngleEditorActionResult.NoEditor
        return publishOutcome(action(editor))
    }

    /**
     * Un gesto solo se aplica si su frame y generación siguen siendo los que el visor confirma
     * **ahora**: cambiar de frame, reanudar la reproducción o recrear la superficie (re-seek) lo
     * abortan antes de tocar la geometría.
     */
    private fun gestureIsCurrent(confirmation: AngleFrameConfirmation): Boolean {
        if (!confirmation.paused) return false
        val live = currentConfirmation() ?: return false
        return live.generation == confirmation.generation && live.frame == confirmation.frame
    }

    private fun publishOutcome(outcome: AngleEditorOutcome): AngleEditorActionResult =
        when (outcome) {
            is AngleEditorOutcome.Applied -> {
                _editor.value = outcome.state
                AngleEditorActionResult.Applied
            }

            is AngleEditorOutcome.Rejected -> AngleEditorActionResult.Rejected(outcome.reason)
        }

    // --- sesión multi-fotograma ------------------------------------------------------------------

    /**
     * Ejecutor de E/S de un solo hilo de la sesión multi-fotograma.
     *
     * El coordinador exige un ejecutor que **nunca** ejecute en línea: la captura decodifica el vídeo
     * y bloquea. Hay una sola captura en vuelo por sesión, así que un hilo basta y serializa la
     * decodificación con el cierre diferido de la fuente. Se apaga en [close] con `shutdown()`, que
     * deja drenar la cola: el cierre de la fuente encolado antes del apagado se ejecuta igualmente y
     * una captura en curso no se interrumpe a mitad.
     */
    private val multiFrameIo: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "openjump-angle-multi-frame").apply { isDaemon = true }
    }

    /** Puerto del coordinador: la captura se despacha al hilo de E/S, nunca al hilo llamante. */
    private val multiFrameExecutor = AngleDraftExecutor { task -> multiFrameIo.execute(task) }

    /** Sesión viva de esta instancia, o `null` si no se ha abierto (o se descartó). */
    private var multiFrameSession: AngleMultiFrameSession? = null
    private var multiFrameOpenJob: Job? = null

    /** Republica el estado de la sesión en [multiFrameState]; se cancela al descartarla. */
    private var multiFrameBridgeJob: Job? = null

    private val _multiFrameState =
        MutableStateFlow<AngleMultiFrameState>(AngleMultiFrameState.NotOpened)

    /**
     * Estado observable de la sesión multi-fotograma: fotogramas registrados, cuál es el activo, el
     * estado de captura de cada uno, la memoria retenida y el plan de finalización puro.
     *
     * La UI sigue usando el flujo mono-fotograma de [editor] y [saveState]. El guardado V2 de esta
     * sesión está cableado en [saveMultiFrame] y la pantalla de fotogramas ya lo ofrece **detrás del
     * gate de producto** [com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED] (apagado por
     * defecto): con el gate apagado la interfaz no puede escribir V2.
     */
    val multiFrameState: StateFlow<AngleMultiFrameState> = _multiFrameState.asStateFlow()

    /**
     * Single-flight del guardado multi-fotograma.
     *
     * Mientras esté activo se bloquean **todas** las mutaciones de la sesión
     * ([AngleMultiFrameRejection.MultiFrameSaveInFlight]), el descarte de la sesión y la reapertura de
     * un análisis: el guardado está leyendo la geometría y los leases de las imágenes, y cambiarlos a
     * mitad dejaría la petición V2 describiendo un estado que ya no existe.
     */
    private val multiFrameSaveInFlight = AtomicBoolean(false)

    /**
     * Propiedad **exclusiva** de la sesión multi-fotograma: guardado o reapertura, nunca los dos.
     *
     * Es la única autoridad atómica de esa exclusión. Las banderas booleanas no bastaban: la de
     * reapertura se fija dentro de su mutex (después de comprobar la de guardado) y la de guardado
     * después de su propio CAS, así que una reapertura podía pasar el chequeo, el guardado tomar la
     * instantánea y después la reapertura descartar la sesión a mitad de la transacción (TOCTOU).
     * Aquí el reclamo es un `AtomicReference` con CAS: el que pierde se rechaza sin esperar, de modo
     * que ninguna puerta bloquea el hilo de UI de forma indefinida.
     */
    private val multiFrameWriteOwner = AtomicReference<MultiFrameWriteClaim?>(null)

    /**
     * Intento del **borrador** multi-fotograma que se está guardando (0 = clave original).
     *
     * La identidad del borrador no vive aquí: se deriva de la clave base de la sesión, que la ruta del
     * editor acuña una sola vez por análisis y conserva ante recreación y muerte del proceso
     * (`rememberSaveable`, ver [multiFrameSessionKey]). Por eso un reintento del **mismo** borrador
     * —incluso en una instancia nueva del `ViewModel` o tras cerrar el editor— deriva exactamente la
     * misma `sessionKey`, que es lo único que garantiza la idempotencia (nunca una segunda fila del
     * mismo contenido). Un análisis nuevo sobre el mismo vídeo tiene otra clave base y **no** se
     * deduplica por compartir vídeo.
     *
     * Este contador solo avanza cuando la clave ya está ocupada por **otro contenido**
     * ([AngleV2SaveRejection.AlreadyStoredConflict]): ahí hace falta una clave nueva para no perder el
     * borrador. `@Volatile`: lo escribe el hilo que completa el intento (el de aplicación cuando hay
     * dueño) y lo lee el siguiente intento desde el hilo principal.
     */
    @Volatile
    private var multiFrameDraftAttempt: Int = 0

    // Las claves de imagen huérfanas **no** se duplican aquí: viajan en el resultado tipado y, cuando
    // el intento pasa al dueño de aplicación, se registran y emiten desde
    // [AngleMultiFrameSaveOwnership] (informe + log), que sobrevive a esta instancia. Un campo local
    // solo las acumulaba sin que las leyera nadie.

    /** Job del guardado multi-fotograma; se cancela en [close]. */
    private var multiFrameSaveJob: Job? = null

    private val _multiFrameSaveState =
        MutableStateFlow<AngleMultiFrameSaveState>(AngleMultiFrameSaveState.Idle)

    /**
     * Estado observable del guardado multi-fotograma (inactivo, guardando, guardado o rechazo
     * tipado). Solo publica un resultado **después** de que la transacción V2 termine. La pantalla
     * de fotogramas lo observa tal cual: lo muestra y no lo interpreta; la navegación al detalle V2
     * la decide la ruta con el `rootId` real, nunca esta capa.
     */
    val multiFrameSaveState: StateFlow<AngleMultiFrameSaveState> = _multiFrameSaveState.asStateFlow()

    /**
     * Abre (una sola vez por instancia y vídeo) la sesión multi-fotograma sobre el vídeo ya abierto.
     *
     * Copiar, sellar e indexar la fuente es E/S: [openMultiFrameSessionInternal] la ejecuta en
     * [Dispatchers.IO] y no en el hilo de capturas, para no ocuparlo antes de la primera
     * decodificación. Es perezosa a propósito: abrir el editor mono-fotograma no paga ninguna copia
     * nueva, y sin vídeo abierto no se abre nada.
     */
    fun openMultiFrameSession() {
        if (closed) return
        if (multiFrameOpenJob?.isActive == true) return
        multiFrameOpenJob = viewModelScope.launch { openMultiFrameSessionInternal() }
    }

    /**
     * Apertura de la sesión multi-fotograma: registra la fuente sellada y publica su estado.
     *
     * Es idempotente mientras la sesión siga viva. Una sesión ya cerrada (descartada por una
     * reapertura) se suelta y se abre otra copia sellada. Todos los fallos son tipados y no tocan el
     * flujo mono-fotograma ni ninguna fila persistida.
     */
    internal suspend fun openMultiFrameSessionInternal(): AngleMultiFrameState {
        if (closed) return publishMultiFrameState(AngleMultiFrameState.Closed)
        val existing = multiFrameSession
        if (existing != null) {
            if (!existing.isClosed) return _multiFrameState.value
            multiFrameSession = null
            multiFrameBridgeJob?.cancel()
            multiFrameBridgeJob = null
        }
        val open = opened
            ?: return publishMultiFrameState(
                AngleMultiFrameState.Unavailable(AngleMultiFrameUnavailableReason.NoVideoOpen),
            )
        val opener = frozenSourceOpener
            ?: return publishMultiFrameState(
                AngleMultiFrameState.Unavailable(AngleMultiFrameUnavailableReason.OpenerUnavailable),
            )
        val sessionGeneration = generation
        _multiFrameState.value = AngleMultiFrameState.Opening
        val sourceOpen = try {
            withContext(Dispatchers.IO) {
                // `closedSignal` es un StateFlow: su lectura es visible entre hilos sin bloqueos y
                // es la única cancelación real posible mientras el editor tiene vídeo abierto (la
                // generación no cambia con un vídeo ya abierto).
                opener.open(open.request.uri) { closedSignal.value }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return publishMultiFrameState(
                AngleMultiFrameState.Unavailable(
                    AngleMultiFrameUnavailableReason.OpenFailed(failure.javaClass.simpleName),
                ),
            )
        }
        if (closed || generation != sessionGeneration) {
            (sourceOpen as? FrozenFrameSourceOpen.Opened)?.session?.close()
            return publishMultiFrameState(
                if (closed) AngleMultiFrameState.Closed else AngleMultiFrameState.NotOpened,
            )
        }
        val openedSource = when (sourceOpen) {
            is FrozenFrameSourceOpen.Unavailable -> return publishMultiFrameState(
                AngleMultiFrameState.Unavailable(
                    AngleMultiFrameUnavailableReason.SourceRejected(
                        failure = sourceOpen.failure,
                        detail = sourceOpen.detail,
                    ),
                ),
            )

            is FrozenFrameSourceOpen.Opened -> sourceOpen.session
        }
        val session = try {
            AngleMultiFrameSession(
                source = FrozenFrameSourceSessionPort(openedSource),
                generation = sessionGeneration,
                viewerSourceIdentity = open.request.uri,
                defaults = defaults,
                executor = multiFrameExecutor,
            )
        } catch (failure: Exception) {
            // Una fuente abierta que no se puede describir no se deja viva: se cierra y se informa
            // la clase del fallo, nunca un estado «listo» sin fotogramas.
            openedSource.close()
            return publishMultiFrameState(
                AngleMultiFrameState.Unavailable(
                    AngleMultiFrameUnavailableReason.OpenFailed(failure.javaClass.simpleName),
                ),
            )
        }
        multiFrameSession = session
        _multiFrameState.value = AngleMultiFrameState.Ready(session.state.value)
        multiFrameBridgeJob?.cancel()
        multiFrameBridgeJob = viewModelScope.launch {
            session.state.collect { state ->
                // Una sesión descartada no republica nada: su colector puede sobrevivir un instante
                // a la cancelación y no debe pisar el estado de la sesión nueva.
                if (multiFrameSession !== session) return@collect
                _multiFrameState.value = if (state.isClosed) {
                    AngleMultiFrameState.Closed
                } else {
                    AngleMultiFrameState.Ready(state)
                }
            }
        }
        return _multiFrameState.value
    }

    /**
     * Captura el fotograma que el visor confirma **en pausa** ahora mismo y lo deja como activo.
     *
     * La confirmación sale de [currentConfirmation], que ya exige `canMark` (pausa y renderizado ==
     * pedido) y el PTS canónico del índice: sin un frame en pausa confirmado no se captura nada
     * ([AngleMultiFrameRejection.FrameNotPaused]). El ancla la acuña el índice sellado de la fuente,
     * no esta llamada. La captura se despacha al ejecutor: el resultado (imagen retenida o fallo
     * tipado) llega en [multiFrameState]. El flujo mono-fotograma no se toca.
     */
    fun captureMultiFrame(): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        val confirmation = currentConfirmation()
        if (confirmation == null) {
            AngleMultiFrameResult.Rejected(AngleMultiFrameRejection.FrameNotPaused)
        } else {
            session.capturePausedFrame(confirmation)
        }
    }

    /** Reintenta la captura de un fotograma ya registrado (tras un fallo de captura o de memoria). */
    fun retryMultiFrameCapture(frameId: AngleFrameId): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session -> session.captureFrame(frameId) }

    /** Cancela la captura en vuelo de [frameId]; la reserva de memoria se suelta al terminar. */
    fun cancelMultiFrameCapture(frameId: AngleFrameId): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session -> session.cancelCapture(frameId) }

    /** Selecciona el fotograma visible (el único editable). */
    fun selectMultiFrame(frameId: AngleFrameId): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session -> session.selectFrame(frameId) }

    /** Limpia la selección de fotograma; no descarta ni modifica ningún fotograma. */
    fun clearMultiFrameSelection(): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session -> session.selectFrame(null) }

    /**
     * Elimina un fotograma. Con datos (imagen, ángulos o puntos A/B) exige
     * [AngleDiscardConfirmation.CONFIRMED]; la pantalla lo consulta antes con
     * [multiFrameRequiresConfirmation].
     */
    fun removeMultiFrame(
        frameId: AngleFrameId,
        confirmation: AngleDiscardConfirmation,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.removeFrame(frameId, confirmation)
    }

    /** `true` si eliminar [frameId] descartaría datos y por tanto exige confirmación explícita. */
    fun multiFrameRequiresConfirmation(frameId: AngleFrameId): Boolean {
        val session = multiFrameSession ?: return false
        return session.state.value.frame(frameId)?.hasData == true
    }

    /**
     * Pide el lease del bitmap congelado **exacto** de [frameId] (sin copiar, mutar ni recapturar).
     *
     * La pantalla es dueña del lease y debe cerrarlo en el hilo de dibujo al salir de composición;
     * mientras siga abierto, esos píxeles no se reciclan. Es una lectura: se permite también durante
     * un guardado o una reapertura en vuelo.
     */
    fun acquireMultiFrameBitmap(frameId: AngleFrameId): AngleMultiFrameBitmapResult =
        multiFrameBitmap(mutating = false) { session -> session.acquireBitmap(frameId) }

    /**
     * Coloca el siguiente punto del borrador (A → B → C) sobre el bitmap congelado del fotograma.
     *
     * Solo se acepta en el fotograma activo y con su imagen ya capturada: no hay ninguna vía para
     * marcar sobre el vídeo en movimiento.
     */
    fun placeMultiFramePoint(
        frameId: AngleFrameId,
        point: AnglePoint,
        selectOnComplete: Boolean = true,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, confirmation ->
            editor.placePoint(point, confirmation, selectOnComplete)
        }
    }

    /** Arrastra una marca de un ángulo del fotograma congelado. */
    fun moveMultiFrameMarker(
        frameId: AngleFrameId,
        angleId: AngleId,
        marker: AngleMarker,
        point: AnglePoint,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, confirmation ->
            editor.moveMarker(angleId, marker, point, confirmation)
        }
    }

    /** Arrastra una marca ya colocada del borrador (A o B) del fotograma congelado. */
    fun moveMultiFrameDraftMarker(
        frameId: AngleFrameId,
        marker: AngleMarker,
        point: AnglePoint,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, confirmation ->
            editor.moveDraftMarker(marker, point, confirmation)
        }
    }

    /** Descarta los puntos A/B del borrador de un fotograma (no toca ángulos ni la imagen). */
    fun cancelMultiFrameDraft(frameId: AngleFrameId): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session ->
            session.editFrame(frameId) { editor, _ -> editor.cancelDraft() }
        }

    /** Selecciona un ángulo visible del fotograma, o limpia su selección con `null`. */
    fun selectMultiFrameAngle(frameId: AngleFrameId, angleId: AngleId?): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session ->
            session.editFrame(frameId) { editor, _ -> editor.select(angleId) }
        }

    /** Renombra un ángulo del fotograma congelado (límite de nombre compartido con la persistencia). */
    fun renameMultiFrameAngle(
        frameId: AngleFrameId,
        angleId: AngleId,
        name: String,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, _ -> editor.rename(angleId, name) }
    }

    /** Cambia el color de presentación de un ángulo del fotograma congelado. */
    fun setMultiFrameAngleColor(
        frameId: AngleFrameId,
        angleId: AngleId,
        colorArgb: Int,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, _ -> editor.setColor(angleId, colorArgb) }
    }

    /** Oculta o muestra un ángulo del fotograma congelado (oculto se sigue guardando en el plan). */
    fun setMultiFrameAngleVisible(
        frameId: AngleFrameId,
        angleId: AngleId,
        visible: Boolean,
    ): AngleMultiFrameResult = multiFrameAction(mutating = true) { session ->
        session.editFrame(frameId) { editor, _ -> editor.setVisible(angleId, visible) }
    }

    /**
     * Borra un ángulo del fotograma congelado.
     *
     * La protección del «último ángulo de un análisis ya persistido» no aplica aquí: esta sesión no
     * escribe filas, y el plan de finalización informa del fotograma sin ángulo completo
     * ([com.openjump.app.angles.AngleFinalizationBlocker.NoCompleteAngle]) en lugar de bloquear el
     * borrado.
     */
    fun deleteMultiFrameAngle(frameId: AngleFrameId, angleId: AngleId): AngleMultiFrameResult =
        multiFrameAction(mutating = true) { session ->
            session.editFrame(frameId) { editor, _ -> editor.deleteAngle(angleId) }
        }

    // --- guardado multi-fotograma ----------------------------------------------------------------

    /**
     * Guarda el análisis multi-fotograma como raíz V2 nueva: instantánea retenida → anotación fuera
     * de pantalla → PNG acotado → almacén privado → transacción de Room.
     *
     * Single-flight: un segundo toque **no publica nada** mientras el primero está en vuelo, así que
     * no pisa el estado `Saving` con un fallo (el `Saving` pertenece al intento que lo publicó).
     * Mientras dura, todas las mutaciones de la sesión, el descarte y la reapertura quedan
     * bloqueados. El resultado ([multiFrameSaveState]) solo pasa a `Saved`/`AlreadySaved`
     * **después** de que la transacción V2 termine; un rechazo nunca se publica como éxito.
     *
     * La pantalla invoca esto solo con el gate de producto activo
     * ([com.openjump.app.ui.angles.ANGLE_MULTI_FRAME_SAVE_UI_ENABLED]) y con el análisis finalizable;
     * el propio método vuelve a aplicar sus guardas, así que una llamada fuera de ese camino no
     * escribe nada que no esté permitido. El cableado existe para no fingir después una función que
     * no está conectada.
     */
    fun saveMultiFrame() {
        if (closed) return
        // Un segundo toque mientras el guardado está en vuelo no publica un fallo: pisaría `Saving`
        // con [AngleMultiFrameSaveRejection.SaveInFlight] y rehabilitaría salidas de fallo mientras
        // el ganador sigue escribiendo. Es un no-op deliberado.
        if (multiFrameSaveInFlight.get()) return
        multiFrameSaveJob = viewModelScope.launch { saveMultiFrameInternal() }
    }

    /**
     * Implementación `suspend` del guardado multi-fotograma (invocable en JVM con un coordinador
     * falso). No lanza por causas esperables.
     *
     * @return el resultado tipado cuando el guardado termina dentro de esta llamada (camino sin
     *   dueño de aplicación); `null` cuando la instantánea pasó al dueño de aplicación, que informa el
     *   resultado real: en [multiFrameSaveState] mientras esta instancia siga abierta y, si ya se
     *   cerró, en el informe de [AngleMultiFrameSaveOwnership] (log de la aplicación), nunca en un
     *   campo que nadie lea. Nunca devuelve éxito sin transacción.
     */
    internal suspend fun saveMultiFrameInternal(): AngleMultiFrameSaveOutcome? {
        if (closed) {
            return publishMultiFrameSaveOutcome(
                AngleMultiFrameSaveOutcome.Rejected(AngleMultiFrameSaveRejection.SessionNotOpened),
            )
        }
        // Cancelación **antes** de comprometer nada: todavía no hay imágenes ni transacción.
        currentCoroutineContext().ensureActive()
        val session = multiFrameSession?.takeUnless { it.isClosed }
        val open = opened
        val coordinator = multiFrameSaveCoordinator
        val early = when {
            session == null || open == null -> AngleMultiFrameSaveRejection.SessionNotOpened
            coordinator == null -> AngleMultiFrameSaveRejection.SaveNotWired
            saveInFlight || reopenInFlight -> AngleMultiFrameSaveRejection.SaveInFlight
            else -> null
        }
        if (early != null) {
            return publishMultiFrameSaveOutcome(AngleMultiFrameSaveOutcome.Rejected(early))
        }
        // Propiedad exclusiva real: el CAS del reclamo es la autoridad sobre guardado↔reapertura (y
        // sobre guardado↔guardado). Ninguna de las dos puertas espera: la que pierde se rechaza.
        val claim = claimMultiFrameWrite(MultiFrameWriteKind.Save)
            ?: return publishMultiFrameSaveOutcome(
                AngleMultiFrameSaveOutcome.Rejected(AngleMultiFrameSaveRejection.SaveInFlight),
            )
        if (!multiFrameSaveInFlight.compareAndSet(false, true)) {
            releaseMultiFrameWrite(claim)
            return publishMultiFrameSaveOutcome(
                AngleMultiFrameSaveOutcome.Rejected(AngleMultiFrameSaveRejection.SaveInFlight),
            )
        }
        _multiFrameSaveState.value = AngleMultiFrameSaveState.Saving
        var snapshot: AngleMultiFrameSaveSnapshot? = null
        var handedOff = false
        try {
            val captured = requireNotNull(session).acquireSaveSnapshot()
            if (captured is AngleMultiFrameSaveSnapshotResult.Rejected) {
                val outcome = AngleMultiFrameSaveOutcome.Rejected(captured.reason)
                completeMultiFrameSave(claim, outcome)
                return outcome
            }
            snapshot = (captured as AngleMultiFrameSaveSnapshotResult.Captured).snapshot
            val retained = requireNotNull(snapshot)
            val openRequest = requireNotNull(open).request
            // Identidad del borrador: determinista desde la clave base del análisis (estable ante
            // recreación y muerte del proceso), así que un reintento del mismo borrador reutiliza la
            // clave y un análisis nuevo del mismo vídeo no se deduplica contra el anterior.
            val sessionKey = multiFrameDraftSessionKey(openRequest.sessionKey)
            // Último punto cancelable **antes** de publicar: a partir de aquí el guardado tiene
            // dueño y se informa su resultado.
            currentCoroutineContext().ensureActive()
            val ownership = multiFrameSaveOwnership
            if (ownership != null &&
                ownership.start(
                    coordinator = requireNotNull(coordinator),
                    snapshot = retained,
                    sessionKey = sessionKey,
                    athleteId = openRequest.athleteId,
                    shouldAbortBeforePublish = { closed },
                    onFinished = { outcome -> completeMultiFrameSave(claim, outcome) },
                )
            ) {
                // La propiedad de la instantánea (y de sus leases) pasó al dueño: la libera él.
                handedOff = true
                snapshot = null
                return null
            }
            // Sin ámbito de aplicación cableado: el guardado corre aquí, con el mismo resultado
            // tipado y la misma liberación de propiedad.
            val outcome = requireNotNull(coordinator).save(
                snapshot = retained,
                sessionKey = sessionKey,
                athleteId = openRequest.athleteId,
                shouldAbortBeforePublish = { closed },
            )
            completeMultiFrameSave(claim, outcome)
            return outcome
        } catch (cancelled: CancellationException) {
            // Cancelación **antes de la entrega**: aquí sí se sabe que este intento no publicó nada.
            // Dejar `Saving` publicado bloquearía la pantalla de fotogramas para siempre (su botón y
            // sus salidas dependen de ese estado), así que se cierra como **fallo tipado** —nunca como
            // éxito— y la identidad del borrador se conserva para el reintento. Si la instantánea ya
            // pasó al dueño de aplicación, el `Saving` pertenece a ese intento y lo cierra él con su
            // resultado real.
            if (!handedOff) {
                _multiFrameSaveState.value = AngleMultiFrameSaveState.Failed(
                    if (closed) {
                        AngleMultiFrameSaveRejection.SessionClosed
                    } else {
                        AngleMultiFrameSaveRejection.InternalInconsistency(CANCELED_BEFORE_PUBLISH)
                    },
                )
            }
            throw cancelled
        } catch (failure: Exception) {
            val outcome = AngleMultiFrameSaveOutcome.Rejected(
                AngleMultiFrameSaveRejection.InternalInconsistency(failure.javaClass.simpleName),
            )
            completeMultiFrameSave(claim, outcome)
            return outcome
        } finally {
            // Los leases de la instantánea se sueltan también aquí (el coordinador ya la cerró).
            snapshot?.close()
            if (!handedOff) releaseMultiFrameSaveOwnership(claim)
        }
    }

    /**
     * Cierre **único** del guardado: publica el resultado real, renueva o conserva la identidad del
     * borrador y libera la propiedad de la sesión. Idempotente respecto de la liberación.
     */
    private fun completeMultiFrameSave(
        claim: MultiFrameWriteClaim,
        outcome: AngleMultiFrameSaveOutcome,
    ) {
        renewMultiFrameDraftIdentity(outcome)
        publishMultiFrameSaveOutcome(outcome)
        releaseMultiFrameSaveOwnership(claim)
    }

    /**
     * Avanza el intento **solo** cuando la clave del borrador quedó ocupada por otro contenido.
     *
     * Si la fila se guardó (o ya estaba guardada con el mismo contenido) no hay nada que renovar: la
     * identidad derivada de la clave base hace que repetir el guardado del mismo borrador siga siendo
     * idempotente. Si el intento se rechazó sin escribir (presupuesto, render, cancelación…), la
     * identidad se conserva para que el reintento no cree una raíz nueva con el mismo contenido.
     */
    private fun renewMultiFrameDraftIdentity(outcome: AngleMultiFrameSaveOutcome) {
        val keyOccupied = (
            (outcome as? AngleMultiFrameSaveOutcome.Rejected)
                ?.reason as? AngleMultiFrameSaveRejection.StoreRejected
            )?.reason is AngleV2SaveRejection.AlreadyStoredConflict
        if (keyOccupied) multiFrameDraftAttempt += 1
    }

    /**
     * Clave del intento vigente: determinista a partir de la clave base del análisis e independiente
     * de esta instancia, de modo que un reintento (también en un `ViewModel` nuevo) derive la misma
     * clave y un análisis nuevo del mismo vídeo derive otra.
     */
    private fun multiFrameDraftSessionKey(baseSessionKey: String): String =
        multiFrameSessionKey(baseSessionKey, multiFrameDraftAttempt)

    private fun publishMultiFrameSaveOutcome(
        outcome: AngleMultiFrameSaveOutcome,
    ): AngleMultiFrameSaveOutcome {
        // Un rechazo por «ya hay un guardado en vuelo» no es el resultado del intento dueño: nunca
        // pisa `Saving`. Las claves huérfanas no se acumulan aquí: viajan en el resultado tipado y,
        // cuando el intento pasa al dueño de aplicación, se registran y emiten en
        // [AngleMultiFrameSaveOwnership] (informe + log), que sí sobrevive a esta instancia.
        val inFlight = (outcome as? AngleMultiFrameSaveOutcome.Rejected)?.reason ===
            AngleMultiFrameSaveRejection.SaveInFlight
        if (inFlight && multiFrameSaveInFlight.get()) return outcome
        val state = when (outcome) {
            is AngleMultiFrameSaveOutcome.Saved ->
                AngleMultiFrameSaveState.Saved(outcome.rootId, outcome.frameCount)

            is AngleMultiFrameSaveOutcome.AlreadySaved ->
                AngleMultiFrameSaveState.AlreadySaved(outcome.rootId)

            is AngleMultiFrameSaveOutcome.Rejected ->
                AngleMultiFrameSaveState.Failed(outcome.reason)
        }
        if (!closed) _multiFrameSaveState.value = state
        return outcome
    }

    /** Reclama la propiedad exclusiva de la sesión multi-fotograma; `null` si ya la tiene otro. */
    private fun claimMultiFrameWrite(kind: MultiFrameWriteKind): MultiFrameWriteClaim? {
        val claim = MultiFrameWriteClaim(kind)
        return if (multiFrameWriteOwner.compareAndSet(null, claim)) claim else null
    }

    /** Libera **solo** la propiedad reclamada: un dueño nuevo no se pisa. */
    private fun releaseMultiFrameWrite(claim: MultiFrameWriteClaim) {
        multiFrameWriteOwner.compareAndSet(claim, null)
    }

    /** Fin de la exclusión del guardado: suelta el single-flight y la propiedad, siempre una vez. */
    private fun releaseMultiFrameSaveOwnership(claim: MultiFrameWriteClaim) {
        // Seguro incluso si se llama dos veces: el single-flight sigue en pie (nadie más puede
        // haberlo tomado con el reclamo en la mano) y la liberación es un CAS por reclamación.
        multiFrameSaveInFlight.set(false)
        releaseMultiFrameWrite(claim)
    }

    /**
     * Puerta común de la API multi-fotograma: instancia viva, sin operación que vaya a reemplazar el
     * estado y con sesión abierta.
     *
     * [mutating] cierra también la puerta durante un guardado mono-fotograma y durante una
     * reapertura: ninguna de las dos debe convivir con geometría nueva. Pedir píxeles prestados
     * ([acquireMultiFrameBitmap]) no muta nada y sigue permitido para no romper el dibujo de lo ya
     * capturado.
     */
    private fun <T> withMultiFrameSession(
        mutating: Boolean,
        blocked: (AngleMultiFrameRejection) -> T,
        block: (AngleMultiFrameSession) -> T,
    ): T {
        if (closed) return blocked(AngleMultiFrameRejection.SessionNotOpened)
        val session = multiFrameSession?.takeUnless { it.isClosed }
            ?: return blocked(AngleMultiFrameRejection.SessionNotOpened)
        if (mutating) {
            if (reopenInFlight) return blocked(AngleMultiFrameRejection.ReopenInFlight)
            if (saveInFlight) return blocked(AngleMultiFrameRejection.SaveInFlight)
            if (multiFrameSaveInFlight.get()) {
                return blocked(AngleMultiFrameRejection.MultiFrameSaveInFlight)
            }
        }
        return block(session)
    }

    private fun multiFrameAction(
        mutating: Boolean,
        block: (AngleMultiFrameSession) -> AngleMultiFrameResult,
    ): AngleMultiFrameResult = withMultiFrameSession(
        mutating = mutating,
        blocked = { AngleMultiFrameResult.Rejected(it) },
        block = block,
    )

    private fun multiFrameBitmap(
        mutating: Boolean,
        block: (AngleMultiFrameSession) -> AngleMultiFrameBitmapResult,
    ): AngleMultiFrameBitmapResult = withMultiFrameSession(
        mutating = mutating,
        blocked = { AngleMultiFrameBitmapResult.Rejected(it) },
        block = block,
    )

    private fun publishMultiFrameState(state: AngleMultiFrameState): AngleMultiFrameState {
        _multiFrameState.value = state
        return state
    }

    /**
     * Descarta la sesión multi-fotograma y publica [finalState].
     *
     * Idempotente. Invalida las capturas en vuelo (su respuesta se libera, no se adopta), libera las
     * imágenes sin leases y encola el cierre de la fuente en el ejecutor; las imágenes con leases
     * abiertos se liberan cuando el último lease se cierra.
     */
    private fun discardMultiFrameSession(finalState: AngleMultiFrameState) {
        multiFrameOpenJob?.cancel()
        multiFrameOpenJob = null
        multiFrameBridgeJob?.cancel()
        multiFrameBridgeJob = null
        val session = multiFrameSession
        multiFrameSession = null
        session?.close()
        _multiFrameState.value = finalState
    }

    companion object {

        /**
         * Factory de producción: conecta el editor con la única puerta transaccional
         * ([AngleRepository]), el reconciliador de grants, el indexador real y el transporte común
         * del visor sobre una [AngleViewerSession] (nunca `AppSession`).
         *
         * El nombre y color de los ángulos nuevos los aporta la capa de UI ([defaults]): este slice
         * no introduce textos ni navegación.
         */
        fun factory(defaults: AngleEditorDefaults): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AngleEditorViewModel(
                    store = RoomAngleAnalysisStore(app.angleRepository),
                    grants = ReconcilerAngleVideoGrants(app.videoUriGrantReconciler),
                    requestReconcile = app::requestVideoUriReconciliation,
                    indexer = ContextAngleVideoIndexer(app),
                    defaults = defaults,
                    createViewer = { session, scope ->
                        // Sin invalidation externa: el editor de ángulos no tiene trabajo derivado
                        // que cancelar al pedir un frame; los gestos se abortan por confirmación.
                        TransportAngleViewerViewer(
                            ViewerTransport(
                                context = app,
                                scope = scope,
                                session = session,
                                onFrameRequested = {},
                            ),
                        )
                    },
                    // Apertura real de la fuente congelada multi-fotograma: copia al snapshot
                    // privado, sellado e índice. El ViewModel la invoca en Dispatchers.IO.
                    frozenSourceOpener = AngleFrozenSourceOpener { uri, cancellationCheck ->
                        FrozenFrameSources.openFromUri(
                            context = app,
                            uri = Uri.parse(uri),
                            cancellationCheck = cancellationCheck,
                        )
                    },
                    // Guardado multi-fotograma real: la misma tienda privada y el mismo mutex global
                    // que el backup con imágenes; el repositorio V2 es quien publica y verifica los
                    // PNG y abre la transacción. El render usa la densidad real de la pantalla y los
                    // textos de grados ya localizados; nada de esto se ejecuta en el hilo principal.
                    multiFrameSaveCoordinator = AngleMultiFrameSaveCoordinator(
                        repository = app.angleV2SaveRepository,
                        renderer = AndroidAngleMultiFrameImageRenderer(
                            context = app,
                            metrics = AngleAnnotationMetrics(
                                densityScale = app.resources.displayMetrics.density,
                            ),
                            formatLabel = { _, degrees ->
                                val numberFormat = NumberFormat.getNumberInstance(
                                    app.resources.configuration.locales[0],
                                ).apply {
                                    minimumFractionDigits = 1
                                    maximumFractionDigits = 1
                                }
                                app.getString(
                                    R.string.angle_degrees_value,
                                    numberFormat.format(degrees),
                                )
                            },
                        ),
                    ),
                    // Dueño de aplicación: una vez comprometida la publicación, el resultado del
                    // guardado (y sus claves huérfanas) se entrega aunque este ViewModel se
                    // destruya. `viewModelScope` descartaría el valor de un tramo no cancelable.
                    multiFrameSaveOwnership = app.angleMultiFrameSaveOwnership,
                )
            }
        }
    }
}

/** Quién posee la sesión multi-fotograma: un guardado o una reapertura, nunca los dos. */
private enum class MultiFrameWriteKind { Save, Reopen }

/**
 * Reclamo concreto de la propiedad de la sesión multi-fotograma.
 *
 * Es un objeto por reclamación (no un booleano) para que la liberación solo pueda soltar **su**
 * propiedad: `compareAndSet(claim, null)` no pisa a un dueño posterior.
 */
private class MultiFrameWriteClaim(val kind: MultiFrameWriteKind)
