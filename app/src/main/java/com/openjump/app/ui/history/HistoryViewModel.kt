package com.openjump.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AngleRepository
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AngleV2HistoryPage
import com.openjump.app.data.AthleteHistoryCursor
import com.openjump.app.data.AthleteHistoryPage
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.EncoderRepository
import com.openjump.app.data.GroupEntity
import com.openjump.app.data.GroupRepository
import com.openjump.app.data.HistoryQuery
import com.openjump.app.data.JumpRepository
import com.openjump.app.data.angles.AngleHistoryObservation
import com.openjump.app.data.angles.AngleMediaCoordination
import com.openjump.app.data.angles.AngleV2DeletePreparation
import com.openjump.app.data.angles.AngleV2DeleteProof
import com.openjump.app.data.angles.AngleV2DeleteRun
import com.openjump.app.data.angles.observeAngleHistoryAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which persisted measurement roots participate in a history query. */
enum class HistoryFamily { ALL, JUMP, ENCODER, ANGLE }

data class HistoryFilters(
    val search: String = "",
    val family: HistoryFamily = HistoryFamily.ALL,
    val athleteId: Long? = null,
    val unassignedOnly: Boolean = false,
    val groupId: Long? = null,
    val protocolOrExercise: String = "",
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val metricMin: Double? = null,
    val metricMax: Double? = null,
) {
    fun toQuery() = HistoryQuery(
        search = search.trim().ifBlank { null },
        athleteId = athleteId,
        unassignedOnly = unassignedOnly,
        groupId = groupId,
        protocolOrExercise = protocolOrExercise.trim().ifBlank { null },
        dateFrom = dateFrom,
        dateTo = dateTo,
        metricMin = metricMin,
        metricMax = metricMax,
    )

    val activeCount: Int get() = listOf(
        search.isNotBlank(), family != HistoryFamily.ALL, athleteId != null || unassignedOnly, groupId != null,
        protocolOrExercise.isNotBlank(), dateFrom != null, dateTo != null,
        metricMin != null, metricMax != null,
    ).count { it }
}

data class HistoryScreenState(
    val filters: HistoryFilters = HistoryFilters(),
    val items: List<MeasurementHistoryItem> = emptyList(),
    /**
     * Hay una lectura en curso. El estado INICIAL de un ViewModel nuevo ya vale `true`: la pantalla
     * se compone antes de que `ON_RESUME` dispare la primera lectura, y con `false` ese primer frame
     * publicaría «No results» sobre una consulta que todavía no ha respondido. Sin filas y con
     * `loading` el espacio queda vacío hasta que la lectura decida ([showInitialLoader]).
     */
    val loading: Boolean = true,
    val error: Boolean = false,
    val canLoadMore: Boolean = true,
    /**
     * Lectura en frío o de otro filtro que ya pasó el retardo visible: es la ÚNICA causa de la
     * etiqueta de carga de la lista. Un refresco del mismo filtro no la enciende.
     */
    val showInitialLoader: Boolean = false,
    /** Refresco/reconciliación en vuelo con las filas ya publicadas todavía visibles. */
    val refreshing: Boolean = false,
)

/**
 * Intención explícita de una lectura de HISTORY.
 *
 * El tipo decide qué se conserva de la lista ya publicada y qué cursor usa la consulta:
 * - [INITIAL]: consulta nueva (arranque en frío o filtro distinto). Las filas publicadas son de
 *   OTRA consulta, así que se sueltan antes de leer y el resultado sustituye por completo.
 * - [REFRESH]: MISMA consulta (vuelta desde un detalle, guardado, borrado, reintento). Las filas
 *   publicadas se mantienen en pantalla y el resultado se reconcilia desde el principio cubriendo
 *   la profundidad ya cargada, sin reiniciar el cursor hasta publicar.
 * - [APPEND]: página siguiente con el cursor pendiente. Nunca se infiere del tamaño de la lista.
 */
internal enum class HistoryLoadKind { INITIAL, REFRESH, APPEND }

class HistoryViewModel(
    private val repository: JumpRepository,
    private val encoderRepository: EncoderRepository,
    private val athleteRepository: AthleteRepository,
    private val groupRepository: GroupRepository,
    private val angleRepository: AngleRepository,
    /** Reconciliador de grants existente; sólo se invoca tras borrar un análisis de ángulos. */
    private val reconcileVideoUriGrants: suspend () -> Unit = {},
    /**
     * Lector de la página V2 que devuelve la página Y la generación de restauración de la MISMA
     * sección crítica. En producción es `null` y se usa
     * [observeAngleHistoryAtomically] sobre el repositorio real; los tests inyectan uno controlado.
     */
    private val angleV2HistoryReader: (suspend (HistoryQuery, AthleteHistoryCursor?, Int) -> AngleHistoryObservation<List<AngleV2HistoryPage>>)? = null,
    /**
     * Preparación de la prueba de borrado V2 (`AngleV2DeleteRepository.prepare`). Sin costura (o sin
     * [angleV2Start]) no existe camino de borrado V2 y la acción no se cablea.
     */
    private val angleV2Prepare: (suspend (Long, Long) -> AngleV2DeletePreparation)? = null,
    /** Arranque con dueño de aplicación (`AngleV2DeleteOwnership.start`). */
    private val angleV2Start: ((AngleV2DeleteProof, (AngleV2DeleteRun) -> Unit) -> Boolean)? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryScreenState())
    val state: StateFlow<HistoryScreenState> = _state.asStateFlow()
    private val _athletes = MutableStateFlow<List<AthleteEntity>>(emptyList())
    val athletes: StateFlow<List<AthleteEntity>> = _athletes.asStateFlow()
    private val _groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val groups: StateFlow<List<GroupEntity>> = _groups.asStateFlow()
    private var cursor: AthleteHistoryCursor? = null
    private var requestId = 0L
    // Trabajo de la lectura de página vigente: una petición nueva cancela el anterior, así que una
    // respuesta superseded no sigue ocupando IO ni puede publicar (el `requestId` sigue siendo la
    // segunda barrera, para la carrera entre comprobación y publicación).
    private var pageJob: Job? = null
    /**
     * Generación de restauración ([AngleMediaCoordination.restoreEpoch]) con la que se leyó la lista
     * publicada. Es estado EN MEMORIA: no hay caché nueva, ni observador, ni escritor. Sirve para
     * fallar cerrado cuando una restauración real recicla ids y una fila retenida pasaría a describir
     * otra raíz, y para no concatenar una página de otra generación.
     */
    private var publishedRestoreEpoch: Long = AngleMediaCoordination.restoreEpoch.current()
    // Filtros y profundidad de la última publicación correcta: definen «mismo filtro» y cuánta
    // profundidad debe cubrir un refresco para no perder el ancla de scroll.
    private var publishedFilters: HistoryFilters? = null
    private var publishedDepth = 0
    // Última intención fallida: el reintento repite EXACTAMENTE esa (primera página o cursor).
    private var failedKind: HistoryLoadKind? = null
    private var initialLoaderJob: Job? = null
    // F035 + Ángulos: objetivos (familia + id) con un borrado en vuelo, más el último objetivo
    // fallido para el diálogo de reintento. La lista nunca se muta de forma optimista.
    private val deleteGate = HistoryDeleteGate()
    // Sólo en memoria: la clave restaurada de un diálogo no autoriza un borrado por sí sola.
    private var preparedLegacyDelete: Pair<HistoryDeleteTarget, Long>? = null
    private var failedLegacyDeleteEpoch: Long? = null
    private val _deletingTargets = MutableStateFlow<Set<HistoryDeleteTarget>>(emptySet())
    val deletingTargets: StateFlow<Set<HistoryDeleteTarget>> = _deletingTargets.asStateFlow()
    private val _deleteFailedTarget = MutableStateFlow<HistoryDeleteTarget?>(null)
    val deleteFailedTarget: StateFlow<HistoryDeleteTarget?> = _deleteFailedTarget.asStateFlow()

    /**
     * Borrado V2 confirmado (familia [HistoryDeleteFamily.ANGLE_V2]), o `null` si no está cableado.
     *
     * Sólo existe con las DOS piezas de producción —preparación de la prueba exacta y arranque con
     * dueño de aplicación—, así que la interfaz no ofrece la acción V2 cuando falta alguna. La prueba
     * de confirmación vive en este controlador y NUNCA en `SavedStateHandle`.
     */
    internal val angleV2Deletion: HistoryV2DeletionController? =
        if (angleV2Prepare != null && angleV2Start != null) {
            HistoryV2DeletionController(
                scope = viewModelScope,
                prepare = angleV2Prepare,
                startDelete = angleV2Start,
                onRefresh = { refresh() },
            )
        } else {
            null
        }

    private val noV2Confirmation = MutableStateFlow<HistoryV2DeleteConfirmation?>(null)
    private val noV2Notice = MutableStateFlow<HistoryV2DeleteNotice?>(null)

    /** Confirmación V2 preparada (cuenta real de fotogramas), o `null` si no hay ninguna. */
    internal val v2DeleteConfirmation: StateFlow<HistoryV2DeleteConfirmation?> =
        angleV2Deletion?.confirmation ?: noV2Confirmation

    /** Aviso posterior a un intento V2 (stale, reintento o limpieza incompleta). */
    internal val v2DeleteNotice: StateFlow<HistoryV2DeleteNotice?> =
        angleV2Deletion?.notice ?: noV2Notice

    /** `true` si la acción de borrado V2 puede ofrecerse: prueba + dueño de aplicación cableados. */
    internal val angleV2DeleteAvailable: Boolean get() = angleV2Deletion != null

    init {
        // Lists are small roster metadata; measurement roots remain strictly query-backed.
        viewModelScope.launch { athleteRepository.all().collect { _athletes.value = it } }
        viewModelScope.launch { groupRepository.all().collect { _groups.value = it } }
    }

    /**
     * Aplica filtros. Si la consulta efectiva es la misma que la ya publicada, se comporta como un
     * refresco silencioso (no suelta filas ni reinicia el scroll); si cambia, arranca una consulta
     * nueva y las filas de la anterior desaparecen para no mostrar nunca resultados de otra query.
     */
    fun setFilters(filters: HistoryFilters) {
        if (isSamePublishedQuery(filters)) {
            _state.value = _state.value.copy(filters = filters)
            refresh()
            return
        }
        resetAndLoad(filters)
    }

    fun clearFilters() = setFilters(HistoryFilters())

    /**
     * Página siguiente de la MISMA consulta publicada. No se infiere de `items.isNotEmpty()`: usa
     * el cursor pendiente, que sólo avanza cuando una página se publica.
     */
    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.refreshing || !snapshot.canLoadMore) return
        if (snapshot.items.isEmpty() || !isSamePublishedQuery(snapshot.filters)) return
        startLoad(HistoryLoadKind.APPEND, snapshot.filters)
    }

    /**
     * Relee las filas persistidas al volver de un detalle/guardado o tras una mutación, sin TTL y
     * sin saltarse la consulta real. Con el mismo filtro conserva la lista visible.
     */
    fun refresh() {
        val filters = _state.value.filters
        if (isSamePublishedQuery(filters)) startLoad(HistoryLoadKind.REFRESH, filters) else resetAndLoad(filters)
    }

    /**
     * Reintento explícito de la ÚLTIMA intención fallida: una primera página se relee desde el
     * principio y un append fallido reusa el cursor pendiente (que nunca avanzó).
     */
    fun retry() {
        val filters = _state.value.filters
        val kind = failedKind ?: HistoryLoadKind.INITIAL
        // Fallo cerrado por generación: ninguna intención exacta sobre una lista leída en otra
        // generación es válida (un append concatenaría filas que ya no describen lo mismo). Se
        // revalida desde la primera página —refresco del mismo filtro o consulta nueva—, que sí
        // puede republicar en la generación vigente.
        if (kind != HistoryLoadKind.INITIAL && !retainedRowsAreCurrent()) {
            if (isSamePublishedQuery(filters)) {
                startLoad(HistoryLoadKind.REFRESH, filters)
            } else {
                resetAndLoad(filters)
            }
            return
        }
        when (kind) {
            HistoryLoadKind.APPEND -> if (!_state.value.items.isEmpty()) {
                startLoad(HistoryLoadKind.APPEND, filters)
            } else {
                resetAndLoad(filters)
            }
            HistoryLoadKind.REFRESH -> startLoad(HistoryLoadKind.REFRESH, filters)
            HistoryLoadKind.INITIAL -> resetAndLoad(filters)
        }
    }

    /** Filtro distinto o arranque: nada de la consulta anterior puede quedar en pantalla. */
    private fun resetAndLoad(filters: HistoryFilters) {
        cursor = null
        publishedFilters = null
        publishedDepth = 0
        failedKind = null
        initialLoaderJob?.cancel()
        // Lectura nueva desde cero: tampoco se arrastra un `pageJob` de la consulta anterior.
        pageJob?.cancel()
        _state.value = HistoryScreenState(filters = filters, loading = true)
        startLoad(HistoryLoadKind.INITIAL, filters)
    }

    /**
     * ¿Siguen siendo válidas las acciones de las filas publicadas?
     *
     * `true` mientras la generación de restauración vigente sea la que se leyó al publicar la lista.
     * La comprobación es una lectura atómica en memoria: no consulta la base ni añade escritores.
     */
    internal fun retainedRowsAreCurrent(): Boolean =
        publishedFilters != null && publishedRestoreEpoch == AngleMediaCoordination.restoreEpoch.current()

    /**
     * Puerta única de las acciones de una fila publicada (abrir detalle, borrar).
     *
     * Fallo cerrado: si una restauración cambió la identidad del agregado desde la lectura, la acción
     * se rechaza (el id ya no identifica lo que el usuario vio) y se pide una relectura desde la
     * primera página, que sí puede revalidar. Las filas cacheadas pueden seguir en pantalla; sólo una
     * lista realmente obsoleta queda bloqueada.
     */
    internal fun allowRetainedRowAction(): Boolean {
        if (retainedRowsAreCurrent()) return true
        refresh()
        return false
    }

    private fun isSamePublishedQuery(filters: HistoryFilters): Boolean {
        val published = publishedFilters ?: return false
        return sameHistoryQuery(published, filters)
    }

    internal fun prepareLegacyDelete(target: HistoryDeleteTarget): Boolean {
        if (!historyLegacyDeleteSupports(target.family) || !allowRetainedRowAction()) return false
        val epoch = publishedRestoreEpoch ?: return false
        preparedLegacyDelete = target to epoch
        return true
    }

    internal fun cancelLegacyDelete() {
        preparedLegacyDelete = null
    }

    internal fun confirmLegacyDelete(target: HistoryDeleteTarget) {
        val prepared = preparedLegacyDelete ?: return
        cancelLegacyDelete()
        if (prepared.first == target) deleteRow(target, prepared.second)
    }

    internal fun retryLegacyDelete(target: HistoryDeleteTarget) {
        val epoch = failedLegacyDeleteEpoch ?: return
        if (_deleteFailedTarget.value == target) deleteRow(target, epoch)
    }

    /**
     * F035 + Ángulos: borra UNA raíz confirmada desde HISTORY.
     *
     * Es el único camino de borrado de la pantalla: no hay una ruta paralela por familia. La
     * familia del [target] decide qué repositorio exclusivo toca la raíz —`encoder_sessions` o
     * `angle_analyses`— y el id nunca se interpreta contra la otra tabla. Un análisis legacy se
     * borra sólo como raíz: sus marcas caen por `ON DELETE CASCADE` y ningún vídeo se elimina.
     *
     * **Ninguna raíz V2 pasa por aquí.** `HistoryDeleteFamily.ANGLE` es siempre `angle_analyses` y
     * `HistoryDeleteFamily.ANGLE_V2` falla cerrado en el guard de entrada: un id V2 ejecutado contra
     * `angle_analyses` borraría OTRA fila. El borrado V2 tiene su propio camino
     * ([requestV2Delete], prueba exacta + dueño de aplicación) y no comparte este `Long` ni este
     * gate serializable.
     *
     * Guardas: un segundo toque del mismo objetivo mientras el primero está en vuelo se ignora
     * ([deleteGate]); el fallo (`false` por fila ausente, o excepción) conserva la fila y abre el
     * diálogo de reintento; sólo el éxito refresca. Cancelar no llama aquí y no tiene efecto.
     */
    private fun deleteRow(target: HistoryDeleteTarget, confirmedEpoch: Long) {
        // Fallo cerrado: sin prueba exacta no hay autoridad V2. No se reinterpreta el id.
        if (!historyLegacyDeleteSupports(target.family)) return
        // La generación del diálogo importa incluso si la lista YA se refrescó tras restaurar.
        if (publishedRestoreEpoch != confirmedEpoch || !retainedRowsAreCurrent()) {
            acknowledgeDeleteFailure()
            refresh()
            return
        }
        if (!deleteGate.begin(target)) return
        _deletingTargets.value = deleteGate.snapshot()
        if (_deleteFailedTarget.value == target) acknowledgeDeleteFailure()
        viewModelScope.launch {
            val deleted = try {
                withContext(Dispatchers.IO) {
                    if (AngleMediaCoordination.restoreEpoch.current() != confirmedEpoch) return@withContext false
                    when (target.family) {
                        HistoryDeleteFamily.ENCODER -> encoderRepository.deleteSession(target.id)
                        HistoryDeleteFamily.ANGLE -> angleRepository.delete(target.id)
                        // Inalcanzable: el guard de entrada ya descartó la familia V2. Si alguien
                        // añadiera una llamada aquí, debe fallar en vez de degradar a borrado por id.
                        HistoryDeleteFamily.ANGLE_V2 ->
                            error("El borrado V2 necesita una prueba exacta; no se ejecuta por id.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            deleteGate.end(target)
            _deletingTargets.value = deleteGate.snapshot()
            if (!deleted) {
                if (AngleMediaCoordination.restoreEpoch.current() == confirmedEpoch) {
                    failedLegacyDeleteEpoch = confirmedEpoch
                    _deleteFailedTarget.value = target
                } else {
                    refresh()
                }
                return@launch
            }
            // Un análisis de ángulos es dueño de su URI de vídeo como una sesión de encoder: al
            // desaparecer la raíz, el grant persistido puede quedar huérfano y se reconcilia con el
            // reconciliador existente (misma consulta de referencias guardadas).
            if (target.family == HistoryDeleteFamily.ANGLE) reconcileAfterAngleDelete()
            refresh()
        }
    }

    /**
     * Mismo contrato que [JumpRepository] y [EncoderRepository]: una limpieza opcional de grants que
     * falla no revierte ni oculta un borrado ya confirmado en Room.
     */
    private suspend fun reconcileAfterAngleDelete() {
        try {
            reconcileVideoUriGrants()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // El borrado de Room sigue siendo válido aunque el proveedor de permisos falle.
        }
    }

    fun acknowledgeDeleteFailure() {
        failedLegacyDeleteEpoch = null
        _deleteFailedTarget.value = null
    }

    /**
     * Pide el borrado V2 de una fila multi-fotograma: prepara la prueba exacta con la generación que
     * la fila observó al leerse.
     *
     * Fallo cerrado: sin controlador cableado o sin [AngleAnalysisHistoryEntry.restoreToken] no se
     * hace nada. La confirmación explícita la publica el controlador ([v2DeleteConfirmation]) y la
     * ejecución NO pasa por `viewModelScope`.
     */
    internal fun requestV2Delete(entry: AngleAnalysisHistoryEntry) {
        angleV2Deletion?.requestDelete(rootId = entry.id, observedRestoreToken = entry.restoreToken)
    }

    /** Confirma la prueba V2 preparada (doble toque = una sola mutación, ver el controlador). */
    internal fun confirmV2Delete() {
        angleV2Deletion?.confirm()
    }

    /** Cancela la confirmación V2: cero filas y cero ficheros, sólo se olvida la prueba. */
    internal fun cancelV2Delete() {
        angleV2Deletion?.cancel()
    }

    /** Reintento explícito: reutiliza la MISMA prueba si sigue en memoria. */
    internal fun retryV2Delete() {
        angleV2Deletion?.retry()
    }

    internal fun dismissV2DeleteNotice() {
        angleV2Deletion?.dismissNotice()
    }

    /**
     * Lanza UNA lectura con intención explícita y valores inmutables por petición (filtros, cursor,
     * profundidad y límites). Una petición nueva supersede a la anterior por `requestId`, así que
     * una respuesta vieja nunca publica sobre un estado más reciente.
     */
    private fun startLoad(kind: HistoryLoadKind, filters: HistoryFilters) {
        requestId++
        val id = requestId
        // La lectura anterior se cancela en cuanto hay una generación nueva. La comprobación por
        // `requestId` antes de publicar se mantiene: la cancelación libera la IO, no es la única
        // barrera contra una respuesta vieja.
        pageJob?.cancel()
        initialLoaderJob?.cancel()
        // Lista retenida, profundidad, cursor y generación se capturan AHORA: nada de lo que ocurra
        // durante la IO los cambia.
        val priorItems = _state.value.items
        val priorDepth = publishedDepth
        val priorFirstKey = priorItems.firstOrNull()?.historyRowKey()
        // Ventana de lectura de un refresco: la profundidad ya publicada MÁS un lookahead corto, con
        // suelo de una página. Sin tope absoluto: truncar en un máximo fijo (240) perdía páginas
        // profundas ya cargadas. Y no se publica la ventana entera: eso haría crecer la lista en
        // cinco filas en CADA vuelta sin que el usuario pidiera nada.
        val readLimit = if (kind == HistoryLoadKind.REFRESH) {
            maxOf(PAGE_SIZE, priorDepth) + REFRESH_LOOKAHEAD
        } else {
            PAGE_SIZE
        }
        val batch = maxOf(BATCH_SIZE, readLimit)
        val requestCursor = if (kind == HistoryLoadKind.APPEND) cursor else null
        val epochAtStart = AngleMediaCoordination.restoreEpoch.current()
        failedKind = null
        when (kind) {
            HistoryLoadKind.APPEND -> {
                // Un append tambián supersede un refresco en vuelo: no deja su bandera encendida.
                _state.value = _state.value.copy(
                    loading = true,
                    refreshing = false,
                    error = false,
                    showInitialLoader = false,
                )
            }
            HistoryLoadKind.REFRESH -> {
                // Silencioso: las filas publicadas siguen en pantalla y sin etiqueta de carga. Se
                // apaga EXPLÍCITAMENTE el `loading` de un append que este refresco supersede; si no,
                // el pie mostraría «Loading…» durante un refresco que no debe mostrar nada.
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = true,
                    error = false,
                    showInitialLoader = false,
                )
            }
            HistoryLoadKind.INITIAL -> {
                _state.value = _state.value.copy(loading = true, error = false, showInitialLoader = false)
                showInitialLoaderAfterDelay(id)
            }
        }
        pageJob = loadPage(
            id = id,
            kind = kind,
            filters = filters,
            requestCursor = requestCursor,
            readLimit = readLimit,
            batch = batch,
            priorItems = priorItems,
            priorDepth = priorDepth,
            priorFirstKey = priorFirstKey,
            epochAtStart = epochAtStart,
        )
    }

    /**
     * Loader de arranque/filtro sólo si la consulta sigue en vuelo tras el retardo visible: una
     * respuesta rápida nunca hace parpadear la etiqueta ni muestra «No results» antes de tiempo.
     */
    private fun showInitialLoaderAfterDelay(id: Long) {
        initialLoaderJob?.cancel()
        initialLoaderJob = viewModelScope.launch {
            delay(INITIAL_LOADER_DELAY_MS)
            if (id == requestId && _state.value.loading && _state.value.items.isEmpty()) {
                _state.value = _state.value.copy(showInitialLoader = true)
            }
        }
    }

    private fun loadPage(
        id: Long,
        kind: HistoryLoadKind,
        filters: HistoryFilters,
        requestCursor: AthleteHistoryCursor?,
        readLimit: Int,
        batch: Int,
        priorItems: List<MeasurementHistoryItem>,
        priorDepth: Int,
        priorFirstKey: String?,
        epochAtStart: Long,
    ): Job = viewModelScope.launch {
        val query = filters.toQuery()
        // Cada familia se consulta sólo cuando puede aportar filas: Ángulos no tiene protocolo ni
        // métrica, así que con esos filtros activos se excluye en vez de ignorarlos.
        val includeJumps = filters.family == HistoryFamily.ALL || filters.family == HistoryFamily.JUMP
        val includeEncoder = filters.family == HistoryFamily.ALL || filters.family == HistoryFamily.ENCODER
        val includeAngles = historyIncludesAngles(filters)
        try {
            val (jump, encoder, angles) = coroutineScope {
                val jumpPage = if (includeJumps) {
                    async { repository.historyPage(query, requestCursor, batch) }
                } else null
                val encoderPage = if (includeEncoder) {
                    async { encoderRepository.historyPage(query, requestCursor, batch) }
                } else null
                // Ángulos es UNA familia visual con DOS fuentes persistidas: la legacy
                // (`angle_analyses`) y la multi-fotograma (`angle_analysis_v2_roots`). Se consultan
                // por separado porque sus ids son espacios distintos —pueden coincidir— y comparten
                // el mismo cursor global; la fuente viaja en cada entrada y es la que hace única su
                // clave de lista y su `kind`. Se piden juntas para que el merge reparta la página.
                val anglePage = if (includeAngles) {
                    async { repository.angleHistoryPage(query, requestCursor, batch) }
                } else null
                val angleMultiFrameObservation = if (includeAngles) {
                    async { readAngleMultiFramePage(query, requestCursor, batch) }
                } else null
                Triple(
                    jumpPage?.await().orEmpty(),
                    encoderPage?.await().orEmpty(),
                    anglePage?.await().orEmpty().map { it.toAngleEntry() } +
                        angleMultiFrameObservation?.await()?.let { observation ->
                            observation.value.map { it.toMultiFrameAngleEntry(observation.restoreToken) }
                        }.orEmpty(),
                )
            }
            if (id != requestId) return@launch
            // Una restauración durante la IO puede haber mezclado identidades de antes y de después
            // del reemplazo: la página no se publica. Un append además exige que la lista publicada
            // sea de la MISMA generación que la página leída.
            if (!readIsFromOneGeneration(kind, epochAtStart)) {
                failRetainedLoad(kind)
                return@launch
            }
            // El merge ya está acotado por `readLimit` (la ventana de lectura).
            val window = mergeMeasurementHistory(
                jumps = jump.map { it.toRecentMeasurement() },
                encoderSessions = encoder.map { it.toRecentEncoderSession() },
                limit = readLimit,
                angleAnalyses = angles,
            )
            // Qué se PUBLICA. Un append concatena la página con la lista retenida (nunca mezcla
            // páginas de otra generación: eso ya lo cerró la comprobación de arriba). Una lectura
            // desde el principio (frío o refresco) reconcilia y publica la MISMA profundidad ya
            // cargada: la ventana sólo sirve para decidir si hay continuación.
            val visible: List<MeasurementHistoryItem>
            val canLoadMore: Boolean
            if (kind == HistoryLoadKind.APPEND) {
                visible = priorItems + window
                canLoadMore = historyCanLoadMore(window.size, readLimit)
            } else {
                // Suelo de una página, techo en la profundidad previa: quien había cargado 90 filas
                // ve 90 (o menos si la consulta ya no las tiene), no 95. Si la fila que encabezaba
                // lo publicado sigue existiendo, se admiten como máximo [REFRESH_LOOKAHEAD] filas
                // realmente insertadas por encima, para no empujar fuera del ancla a quien estaba al
                // final; nunca un crecimiento fijo por vuelta.
                val target = maxOf(PAGE_SIZE, priorDepth)
                val shift = if (priorFirstKey == null) {
                    0
                } else {
                    val position = window.indexOfFirst { it.historyRowKey() == priorFirstKey }
                    if (position <= 0) 0 else minOf(position, REFRESH_LOOKAHEAD)
                }
                visible = window.take(minOf(window.size, target + shift))
                // La continuación la decide el lookahead NO publicado: o quedaron filas de la
                // ventana sin mostrar, o la ventana vino llena y por tanto puede haber más.
                canLoadMore = window.size > visible.size || window.size == readLimit
            }
            // El cursor se toma de la última fila VISIBLE, no del final de la ventana: la página
            // siguiente continúa exactamente donde termina lo publicado, sin saltarse ni duplicar el
            // lookahead. Se publica junto a las filas, en la misma asignación, sin ventana en la que
            // no coincidan.
            val nextCursor = visible.lastOrNull()?.let {
                AthleteHistoryCursor(it.dateTime, it.id, it.kindKey())
            }
            cursor = if (kind == HistoryLoadKind.APPEND) nextCursor ?: cursor else nextCursor
            publishedFilters = filters
            publishedDepth = visible.size
            publishedRestoreEpoch = epochAtStart
            failedKind = null
            _state.value = _state.value.copy(
                filters = filters,
                items = visible,
                loading = false,
                error = false,
                canLoadMore = canLoadMore,
                showInitialLoader = false,
                refreshing = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (id != requestId) return@launch
            failRetainedLoad(kind)
        }
    }

    /**
     * ¿La lectura completa sigue siendo de UNA sola generación de restauración?
     *
     * La generación se lee al empezar y al terminar: si cambió durante la IO, la página puede mezclar
     * filas de antes y de después del reemplazo, así que no se publica. Un append exige además que la
     * lista publicada sea de la MISMA generación que la página: concatenar una página nueva sobre
     * filas de otra generación publicaría una lista internamente incoherente.
     */
    private fun readIsFromOneGeneration(kind: HistoryLoadKind, epochAtStart: Long): Boolean {
        if (AngleMediaCoordination.restoreEpoch.current() != epochAtStart) return false
        return kind != HistoryLoadKind.APPEND || publishedRestoreEpoch == epochAtStart
    }

    /**
     * Fallo de [kind] (IO o generación mezclada): conserva las filas y el cursor publicados y deja el
     * reintento de la intención exacta. Ninguna rama toca lo visible ni el cursor pendiente.
     */
    private fun failRetainedLoad(kind: HistoryLoadKind) {
        failedKind = kind
        _state.value = _state.value.copy(
            loading = false,
            error = true,
            showInitialLoader = false,
            refreshing = false,
        )
    }

    /**
     * Lee la página V2 **dentro** de la sección crítica que comparte con guardado y backup/restore.
     *
     * El token viaja con los datos ([AngleHistoryObservation]): no se consulta la generación después
     * de la query, porque entonces una restauración intercalada aceptaría una página de antes del
     * reemplazo con un token de después. Las filas resultantes llevan ese token.
     */
    private suspend fun readAngleMultiFramePage(
        query: HistoryQuery,
        cursor: AthleteHistoryCursor?,
        limit: Int,
    ): AngleHistoryObservation<List<AngleV2HistoryPage>> {
        val injected = angleV2HistoryReader
        if (injected != null) return injected(query, cursor, limit)
        return observeAngleHistoryAtomically { repository.angleV2HistoryPage(query, cursor, limit) }
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val BATCH_SIZE = 40

        /**
         * Filas extra que se leen por encima de la profundidad ya cargada en un refresco. No se
         * publican: sirven para decidir si hay continuación y para admitir unas pocas inserciones
         * reales por encima del ancla. Es un margen, no un crecimiento por vuelta.
         */
        private const val REFRESH_LOOKAHEAD = 5

        /** Retardo visible antes de mostrar la etiqueta de carga en frío o con otro filtro. */
        private const val INITIAL_LOADER_DELAY_MS = 200L
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                HistoryViewModel(
                    repository = app.repository,
                    encoderRepository = app.encoderRepository,
                    athleteRepository = app.athleteRepository,
                    groupRepository = app.groupRepository,
                    angleRepository = app.angleRepository,
                    reconcileVideoUriGrants = app::reconcileVideoUriGrants,
                    // Composición del borrado V2: la preparación es de sólo lectura y la ejecución
                    // corre en el dueño de aplicación (`angleV2DeleteScope`), nunca en viewModelScope.
                    angleV2Prepare = app.angleV2DeleteRepository::prepare,
                    angleV2Start = { proof, onFinished ->
                        app.angleV2DeleteOwnership.start(
                            repository = app.angleV2DeleteRepository,
                            proof = proof,
                            onFinished = onFinished,
                        )
                    },
                )
            }
        }
    }
}

private fun AthleteHistoryPage.toRecentMeasurement() = com.openjump.app.data.RecentMeasurement(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    protocolId = protocolId.orEmpty(), primaryMetricKey = primaryMetricKey.orEmpty(),
    primaryMetricValue = primaryMetricValue ?: 0.0, primaryMetricUnit = primaryMetricUnit.orEmpty(), side = side,
    hasNotes = hasNotes,
)

private fun AthleteHistoryPage.toRecentEncoderSession() = com.openjump.app.data.RecentEncoderSession(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    exercise = exercise.orEmpty(), loadKg = loadKg ?: 0.0, validRepetitions = validRepetitions ?: 0,
    bestMcv = bestMcv, hasNotes = hasNotes,
)

private fun com.openjump.app.data.AngleHistoryPage.toAngleEntry() = AngleAnalysisHistoryEntry(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    markCount = markCount, videoUri = videoUri,
)

/**
 * Raíz multi-fotograma: fuente explícita `MULTI_FRAME` y SIN `videoUri` (una raíz V2 no persiste el
 * vídeo). Su `id` es de otra tabla que el de [toAngleEntry], aunque el número coincida.
 *
 * [restoreToken] se copia de la observación que leyó la página: es la generación con la que se
 * preparará la prueba de borrado V2, de modo que una restauración posterior invalida la confirmación
 * (`Stale`) en vez de borrar la raíz que ocupe el mismo número.
 */
private fun com.openjump.app.data.AngleV2HistoryPage.toMultiFrameAngleEntry(
    restoreToken: Long,
) = AngleAnalysisHistoryEntry(
    id = id, dateTime = dateTime, athleteId = athleteId, athleteName = athleteName,
    markCount = markCount, videoUri = null, source = AngleHistorySource.MULTI_FRAME,
    restoreToken = restoreToken,
)

/**
 * ¿Puede la familia Ángulos aportar filas a esta consulta?
 *
 * Cubre las DOS fuentes de la familia (legacy y multi-fotograma): ninguna de ellas tiene protocolo,
 * ejercicio ni métrica primaria. Cuando el usuario filtra por serie o por valor, una fila de ángulos
 * no puede cumplir el filtro, así que se excluye (no se ignora el filtro para esa familia). También
 * queda fuera cuando la familia pedida es Saltos o Encoder.
 */
internal fun historyIncludesAngles(filters: HistoryFilters): Boolean =
    (filters.family == HistoryFamily.ALL || filters.family == HistoryFamily.ANGLE) &&
        filters.protocolOrExercise.isBlank() &&
        filters.metricMin == null &&
        filters.metricMax == null

internal fun historyCanLoadMore(emittedSize: Int, pageSize: Int): Boolean = emittedSize == pageSize

/**
 * ¿Es la MISMA consulta? La familia decide qué fuentes participan y no viaja en [HistoryQuery]; el
 * texto se compara ya normalizado por `toQuery()` (un espacio extra no cambia la query efectiva).
 */
internal fun sameHistoryQuery(a: HistoryFilters, b: HistoryFilters): Boolean =
    a.family == b.family && a.toQuery() == b.toQuery()
