package com.openjump.app.ui.angles

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Token opaco que identifica el snapshot exacto sobre el que se preparó un envío múltiple.
 *
 * Combina la identidad de tabla de la raíz ([rootId]) con la revisión del estado publicado, de modo
 * que una recarga que devolviera un contenido distinto con el mismo id no se toma por el mismo
 * snapshot. No es autoridad de datos: sólo decide si el selector que ya se preparó sigue siendo del
 * contenido visible.
 */
data class AngleV2SavedDetailShareToken(val rootId: Long, val revision: Long)

/** Snapshot cargado (contenido + token) que se está preparando; inmutable. */
private class AngleV2SavedDetailSnapshot(
    val content: AngleV2SavedDetailContent,
    val token: AngleV2SavedDetailShareToken,
)

/**
 * Desenlace de «compartir todas las imágenes».
 *
 * No existe un estado de éxito parcial: [Ready] contiene la secuencia completa y ordenada, y cualquier
 * otro desenlace deja al llamante sin ninguna URI que entregar.
 */
sealed interface AngleV2ShareAllOutcome {

    /** Todas las imágenes verificadas, en el orden del contenido cargado, y el snapshot aún vigente. */
    data class Ready(val uris: List<Uri>, val token: AngleV2SavedDetailShareToken) : AngleV2ShareAllOutcome

    /**
     * Fallo tipado. [token] es el snapshot que se intentó compartir (`null` si no había ninguno
     * cargado); sirve para diagnóstico, nunca para lanzar un envío.
     */
    data class Failed(
        val failure: AngleV2ShareAllFailure,
        val token: AngleV2SavedDetailShareToken?,
    ) : AngleV2ShareAllOutcome

    /** Ya había una preparación múltiple en curso: este toque no inicia nada. */
    data object Ignored : AngleV2ShareAllOutcome
}

/**
 * ViewModel del detalle guardado de un análisis multi-fotograma (V2).
 *
 * Lee la raíz por su **identidad de tabla** con la única consulta transaccional de lectura
 * ([AngleAnalysisV2Dao.aggregateByRootId]) y NO abre vídeo, player, índice de frames ni editor:
 * consultar un resultado guardado inmutable no depende de que el vídeo original siga accesible.
 *
 * Es de sólo lectura también en escritura: no borra, no edita, no recolecta imágenes y no muta el
 * resultado guardado. Las dos únicas E/S son: la lectura verificada de cada PNG
 * ([AngleV2SavedDetailViewModel.image]), que el almacén entrega ya contrastado contra el tamaño y
 * SHA-256 declarados y que se decodifica con presupuesto acotado; y la preparación de una **copia**
 * verificada para compartir ([AngleV2SavedDetailViewModel.shareImage]), que no toca el almacén ni la
 * base. Un fichero ausente o trocado se informa y las mediciones persistidas se siguen mostrando.
 *
 * Compartir **todas** las imágenes ([shareAll]) es la única operación que publica más de una copia:
 * prepara en secuencia la copia verificada de cada fotograma del snapshot cargado, exige que **todas**
 * estén listas y vigentes antes de devolver la secuencia ordenada, y no lanza el selector por sí
 * misma. Un fallo parcial, un cambio de raíz/snapshot o una cancelación no entregan ninguna lista.
 */
class AngleV2SavedDetailViewModel(
    private val dao: AngleAnalysisV2Dao,
    /**
     * Lectura verificada del almacén privado. Bloqueante a propósito: se invoca SIEMPRE dentro de
     * [Dispatchers.IO] desde [image]. No expone escritura, borrado ni recolección.
     */
    private val readImageBytes: (imageKey: String, integrity: AngleImageIntegrity) -> AngleImageReadResult<ByteArray>,
    /**
     * Preparación de la copia compartible a partir de la clave y la integridad **declaradas por el
     * fotograma cargado**. Bloqueante a propósito: se invoca SIEMPRE dentro de [Dispatchers.IO] desde
     * [shareImage]. No expone el almacén ni permite elegir rutas: sólo publica PNG verificados.
     */
    private val prepareShare: (imageKey: String, integrity: AngleImageIntegrity) -> AngleV2FrameShareResult,
    /**
     * Nombre legible del atleta dueño, resuelto **por el id de atleta de la propia raíz** (nunca el
     * atleta seleccionado global). Opcional y con valor por defecto para no romper a los llamantes
     * existentes: por defecto no se publica nombre.
     *
     * Se invoca SIEMPRE dentro de [Dispatchers.IO] desde [read]. `null`/blanco se proyecta como
     * "sin nombre"; un fallo de esta lectura tampoco invalida el contenido guardado.
     */
    private val loadAthleteName: suspend (Long) -> String? = { null },
    /**
     * Lectura OPCIONAL de la identidad completa (nombre + avatar) del dueño, en UNA sola consulta
     * por el id de atleta de la propia raíz. Cuando está configurada, es la única fuente del nombre
     * y del avatar: un `null` o un fallo deja AMBOS campos vacíos y NO se recurre al callback
     * heredado [loadAthleteName] (que se conserva para los llamantes y pruebas anteriores).
     */
    private val loadAthleteIdentity: (suspend (Long) -> AngleV2SavedAthleteIdentity?)? = null,
    private val decodeImage: (AngleImageReadResult<ByteArray>, AngleV2SavedDetailFrame) -> AngleV2SavedImage = ::angleV2SavedImage,
    maxImageCacheKb: Int = defaultAngleImageCacheKb(),
) : ViewModel() {

    private val imageCache = BoundedAngleImageCache(maxCacheKb = maxImageCacheKb)

    private val _state = MutableStateFlow<AngleV2SavedDetailState>(AngleV2SavedDetailState.Loading)
    val state: StateFlow<AngleV2SavedDetailState> = _state.asStateFlow()

    /**
     * `true` mientras se prepara un envío múltiple; la cabecera del detalle lo usa para indicar
     * ocupado y deshabilitar la acción, y se limpia siempre (éxito, error o cancelación).
     */
    private val _shareAllBusy = MutableStateFlow(false)
    val shareAllBusy: StateFlow<Boolean> = _shareAllBusy.asStateFlow()

    /**
     * Puerta de un solo vuelo del envío múltiple: un segundo toque mientras se prepara no inicia otra
     * preparación ni otro selector.
     */
    private val shareAllInFlight = AngleV2ShareSingleFlight()

    private var rootId: Long = 0L
    private var requestId = 0L

    /**
     * Revisión del snapshot publicado: se incrementa con CADA cambio de [state].
     *
     * Es el token que impide reconvertir un envío preparado sobre una raíz hacia otra raíz o hacia
     * una recarga que, con el mismo `rootId`, devuelve un contenido distinto: `rootId` solo no basta.
     */
    private var loadedRevision: Long = 0L

    /**
     * Carga (o recarga) la raíz V2 [rootId]; ids no guardados se tratan como ausentes.
     *
     * Un id de `angle_analyses` (familia legacy) no resuelve aquí: son tablas distintas aunque el
     * número coincida, y esta pantalla no puede mostrar otro análisis por un id suelto.
     */
    fun load(rootId: Long) {
        if (rootId == this.rootId && _state.value is AngleV2SavedDetailState.Loaded) return
        this.rootId = rootId
        if (rootId <= 0L) {
            // Invalida también cualquier lectura pendiente: un `load(0)` no puede dejar que una
            // petición anterior (válida) publique después y muestre otro contenido.
            requestId++
            publish(AngleV2SavedDetailState.Missing)
            return
        }
        read(rootId, silent = false)
    }

    /**
     * Relee la raíz conservando el contenido ya visible. El resultado guardado es inmutable, así
     * que una relectura sólo puede confirmar los datos o detectar que la raíz desapareció; un fallo
     * de lectura no vacía la pantalla ni se presenta como éxito.
     */
    fun refresh() {
        val current = rootId
        if (current <= 0L || _state.value is AngleV2SavedDetailState.Loading) return
        read(current, silent = _state.value is AngleV2SavedDetailState.Loaded)
    }

    fun retry() {
        if (rootId > 0L) load(rootId)
    }

    /** Publica un estado nuevo e invalida cualquier envío múltiple preparado sobre el anterior. */
    private fun publish(next: AngleV2SavedDetailState) {
        loadedRevision++
        _state.value = next
    }

    private fun read(rootId: Long, silent: Boolean) {
        val request = ++requestId
        if (!silent) publish(AngleV2SavedDetailState.Loading)
        viewModelScope.launch {
            val next = try {
                withContext(Dispatchers.IO) {
                    val projected = angleV2SavedDetailState(dao.aggregateByRootId(rootId))
                    if (projected is AngleV2SavedDetailState.Loaded) {
                        // El dueño se resuelve en la misma lectura guardada, antes de publicar: el
                        // nombre y el avatar pertenecen a ESTA raíz y viajan en la misma foto.
                        val identity = loadAthleteIdentityOrNull(projected.content.athleteId)
                        AngleV2SavedDetailState.Loaded(
                            projected.content.copy(
                                athleteName = identity?.name,
                                athleteAvatarKey = identity?.avatarKey,
                            ),
                        )
                    } else {
                        projected
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Lectura fallida: los datos siguen en Room, así que se ofrece reintento explícito.
                AngleV2SavedDetailState.Error
            }
            if (request != requestId) return@launch
            if (silent && next is AngleV2SavedDetailState.Error) return@launch
            publish(next)
        }
    }

    /**
     * Nombre del atleta dueño, o `null` si no hay dueño, el nombre queda en blanco o la consulta
     * falla.
     *
     * Un dueño `null`/no positivo NUNCA se consulta: no hay id que resolver y el atleta
     * seleccionado global no es el dueño de esta raíz. Un fallo de la consulta se traduce a "sin
     * nombre" para no ocultar los datos guardados; la cancelación se propaga.
     */
    private suspend fun loadAthleteNameOrNull(athleteId: Long?): String? {
        if (athleteId == null || athleteId <= 0L) return null
        return try {
            loadAthleteName(athleteId)?.trim()?.takeIf(String::isNotEmpty)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Identidad de presentación del atleta dueño, resuelta UNA sola vez por raíz.
     *
     * Sin dueño (`null`/no positivo) no se consulta nada: el atleta seleccionado global no es el
     * dueño de esta raíz. Con el lector nuevo configurado se usa SÓLO él, y un fallo o un `null`
     * dejan nombre y avatar vacíos (nunca se cae a otra fuente). Sin él se conserva el callback
     * heredado de nombre, sin avatar. La cancelación se propaga; el resto de fallos no ocultan el
     * contenido guardado.
     */
    private suspend fun loadAthleteIdentityOrNull(athleteId: Long?): AngleV2SavedAthleteIdentity? {
        if (athleteId == null || athleteId <= 0L) return null
        val identityLoader = loadAthleteIdentity
        if (identityLoader == null) {
            return AngleV2SavedAthleteIdentity(name = loadAthleteNameOrNull(athleteId), avatarKey = null)
        }
        return try {
            identityLoader(athleteId)?.let { identity ->
                AngleV2SavedAthleteIdentity(
                    name = identity.name?.trim()?.takeIf(String::isNotEmpty),
                    avatarKey = identity.avatarKey?.trim()?.takeIf(String::isNotEmpty),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Lectura fallida: ni nombre ni avatar de otra fuente.
            null
        }
    }

    /**
     * Verifica y decodifica la imagen de [frame] con presupuesto acotado, fuera del hilo principal.
     *
     * Devuelve un estado de fallo tipado en vez de lanzar: la pantalla debe seguir mostrando las
     * mediciones cuando la imagen falta o está corrupta, y nunca sustituirla por una captura falsa.
     */
    suspend fun image(frame: AngleV2SavedDetailFrame): AngleV2SavedImage = withContext(Dispatchers.IO) {
        val cacheKey = imageCacheKey(frame)
        imageCache.get(cacheKey)?.let { cached ->
            val reusable = runCatching { !cached.isRecycled }.getOrDefault(true)
            if (reusable) return@withContext AngleV2SavedImage.Available(cached)
            imageCache.remove(cacheKey)
        }
        val result = try {
            readImageBytes(frame.imageKey, frame.integrity)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withContext AngleV2SavedImage.Unreadable
        }
        val decoded = decodeImage(result, frame)
        if (decoded is AngleV2SavedImage.Available) {
            val sizeKb = runCatching { (decoded.bitmap.allocationByteCount / 1024).coerceAtLeast(1) }.getOrDefault(1)
            imageCache.put(cacheKey, decoded.bitmap, sizeKb)
        }
        decoded
    }

    override fun onCleared() {
        imageCache.clear()
        super.onCleared()
    }

    private fun imageCacheKey(frame: AngleV2SavedDetailFrame): String =
        "${frame.imageKey}:${frame.integrity.sha256Hex}:${frame.imageWidth}x${frame.imageHeight}"

    /**
     * Prepara la copia verificada del PNG de [frame] para compartirla fuera de la app.
     *
     * Sólo se publica la imagen de un fotograma **cargado en esta raíz**: la clave y la integridad se
     * toman del contenido leído, no del objeto recibido, y un fotograma de otra raíz (o una clave
     * ajena) se rechaza en vez de convertir esta pantalla en un lector de claves arbitrarias. Nunca
     * se abre el vídeo, no se toca la base y no se borra la imagen después de entregarla.
     */
    suspend fun shareImage(frame: AngleV2SavedDetailFrame): AngleV2FrameShareResult =
        withContext(Dispatchers.IO) {
            val loaded = (_state.value as? AngleV2SavedDetailState.Loaded)?.content
                ?: return@withContext AngleV2FrameShareResult.Unavailable
            val known = loaded.frames.firstOrNull {
                it.frameId == frame.frameId && it.imageKey == frame.imageKey
            } ?: return@withContext AngleV2FrameShareResult.Unavailable
            // El único contenido que esta acción declara y publica es PNG: una fila que declare otro
            // tipo no se comparte como `image/png` ni se convierte a otro formato.
            if (!known.imageMimeType.trim().equals(AngleV2FrameShare.MIME_TYPE, ignoreCase = true)) {
                return@withContext AngleV2FrameShareResult.Corrupted
            }
            try {
                prepareShare(known.imageKey, known.integrity)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                AngleV2FrameShareResult.Unavailable
            }
        }

    /**
     * Prepara la secuencia completa de imágenes verificadas del análisis cargado para compartirlas.
     *
     * Reglas, todas obligatorias:
     * - El origen es el snapshot **cargado** ([AngleV2SavedDetailState.Loaded]) de esta raíz: no se
     *   aceptan claves ni URI de la UI. Sin snapshot el resultado es
     *   [AngleV2ShareAllFailure.NOT_LOADED]; sin fotogramas, [AngleV2ShareAllFailure.EMPTY].
     * - La preparación es secuencial y fuera del hilo principal: cada copia verificada se publica de
     *   una en una y no se materializan N bitmaps ni N PNG en memoria.
     * - Sólo si **todas** las imágenes quedan verificadas y publicadas se devuelve
     *   [AngleV2ShareAllOutcome.Ready] con la lista ordenada completa; cualquier falta, corrupción,
     *   error, cambio de snapshot o exceso de transporte devuelve un fallo tipado y **ninguna** lista,
     *   así que el llamante no puede lanzar el selector con un conjunto incompleto.
     * - Un segundo toque mientras se prepara no inicia otra preparación ([AngleV2ShareAllOutcome.Ignored]).
     * - El indicador de ocupado se limpia siempre, también al cancelar o fallar.
     * - La cancelación del `Job` llamante se propaga: se comprueba con `ensureActive()` del contexto
     *   de E/S antes y después de cada copia y antes de devolver [AngleV2ShareAllOutcome.Ready]. El
     *   publicador bloqueante no lanza por sí mismo al cancelar, así que sin esta comprobación el
     *   lote podría terminar en `Ready` y abrir el selector; ahora sale `CancellationException` y el
     *   `finally` limpia el indicador sin entregar nada.
     *
     * Las copias ya verificadas que se hayan publicado antes del fallo permanecen en la caché de
     * compartición: no se borran (un receptor podría estar leyéndolas) y no se promete rollback.
     */
    suspend fun shareAll(): AngleV2ShareAllOutcome {
        if (!shareAllInFlight.tryBegin()) return AngleV2ShareAllOutcome.Ignored
        _shareAllBusy.value = true
        try {
            val snapshot = shareSnapshot()
                ?: return AngleV2ShareAllOutcome.Failed(AngleV2ShareAllFailure.NOT_LOADED, token = null)
            val result = withContext(Dispatchers.IO) {
                // El contexto se captura dentro de E/S: sus comprobaciones consultan el `Job` real de
                // esta llamada, así que una cancelación del llamante detiene el lote sin convertirse
                // en fallo y sin llegar a `Ready`.
                val batchContext = currentCoroutineContext()
                prepareAngleV2ShareAll(
                    frames = snapshot.content.frames,
                    isStillCurrent = { isShareSnapshotCurrent(snapshot) },
                    prepareFrame = ::prepareShareAllFrame,
                    uriLength = { uri -> uri.toString().length },
                    checkCancellation = { batchContext.ensureActive() },
                )
            }
            return when (result) {
                is AngleV2ShareAllResult.Ready -> AngleV2ShareAllOutcome.Ready(
                    uris = result.items,
                    token = snapshot.token,
                )

                is AngleV2ShareAllResult.Failed -> AngleV2ShareAllOutcome.Failed(
                    failure = result.failure,
                    token = snapshot.token,
                )
            }
        } finally {
            _shareAllBusy.value = false
            shareAllInFlight.end()
        }
    }

    /**
     * `true` si [token] sigue describiendo el snapshot cargado; la pantalla lo comprueba justo antes
     * de lanzar el selector para no abrirlo sobre un contenido que ya cambió.
     */
    fun isShareTokenCurrent(token: AngleV2SavedDetailShareToken): Boolean {
        val loaded = _state.value as? AngleV2SavedDetailState.Loaded ?: return false
        return loadedRevision == token.revision &&
            loaded.content.rootId == token.rootId &&
            rootId == token.rootId
    }

    /** Snapshot cargado + token que lo identifica, o `null` si no hay contenido cargado. */
    private fun shareSnapshot(): AngleV2SavedDetailSnapshot? {
        val loaded = _state.value as? AngleV2SavedDetailState.Loaded ?: return null
        return AngleV2SavedDetailSnapshot(
            content = loaded.content,
            token = AngleV2SavedDetailShareToken(loaded.content.rootId, loadedRevision),
        )
    }

    /** `true` sólo si el snapshot sigue siendo exactamente el que se está preparando. */
    private fun isShareSnapshotCurrent(snapshot: AngleV2SavedDetailSnapshot): Boolean =
        isShareTokenCurrent(snapshot.token)

    /**
     * Adapta el resultado tipado del stager al contrato del lote. No cambia de tipo, no reintenta y
     * no oculta el fallo; una cancelación de la corrutina se propaga tal cual.
     */
    private fun prepareShareAllFrame(frame: AngleV2SavedDetailFrame): AngleV2ShareAllFrame<Uri> {
        val result = try {
            prepareShare(frame.imageKey, frame.integrity)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AngleV2ShareAllFrame.Unavailable
        }
        return when (result) {
            is AngleV2FrameShareResult.Ready -> AngleV2ShareAllFrame.Ready(result.uri)
            AngleV2FrameShareResult.Missing -> AngleV2ShareAllFrame.Missing
            AngleV2FrameShareResult.Corrupted -> AngleV2ShareAllFrame.Corrupted
            AngleV2FrameShareResult.Unavailable -> AngleV2ShareAllFrame.Unavailable
        }
    }

    companion object {

        internal fun defaultAngleImageCacheKb(): Int {
            val maxHeapKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtLeast(4_096L)
            return minOf(16 * 1024, (maxHeapKb / 12L).toInt()).coerceAtLeast(4 * 1024)
        }

        /** Factory de producción: la misma base Room y el mismo almacén privado de imágenes. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                AngleV2SavedDetailViewModel(
                    dao = app.database.angleAnalysisV2Dao(),
                    readImageBytes = app::readVerifiedAngleImageBytes,
                    prepareShare = app::prepareAngleFrameShare,
                    // Dueño por el athleteId de la raíz; `byId` incluye atletas archivados (la
                    // consulta no filtra por archivedAt), que siguen siendo dueños de lo guardado.
                    // UNA sola consulta devuelve nombre y avatar de la MISMA fila de atleta.
                    loadAthleteIdentity = { athleteId ->
                        app.athleteRepository.byId(athleteId)?.let { athlete ->
                            AngleV2SavedAthleteIdentity(
                                name = athlete.displayName,
                                avatarKey = athlete.avatarKey,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Pure-Kotlin thread-safe LRU bitmap cache bounded by KiB budget.
 * Avoids Android LruCache stubs in JVM unit tests and never recycles bitmaps during eviction so
 * Compose/RenderThread cannot draw a recycled bitmap.
 */
internal class BoundedAngleImageCache(private val maxCacheKb: Int) {
    private data class Entry(val bitmap: Bitmap, val sizeKb: Int)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var currentKb = 0

    fun get(key: String): Bitmap? = synchronized(lock) { map[key]?.bitmap }

    fun remove(key: String) {
        synchronized(lock) {
            map.remove(key)?.let { currentKb -= it.sizeKb }
        }
    }

    fun put(key: String, bitmap: Bitmap, sizeKb: Int) {
        val safeSize = sizeKb.coerceAtLeast(1)
        if (safeSize > maxCacheKb) return
        synchronized(lock) {
            map.put(key, Entry(bitmap, safeSize))?.let { previous ->
                currentKb -= previous.sizeKb
            }
            currentKb += safeSize
            val iterator = map.entries.iterator()
            while (currentKb > maxCacheKb && iterator.hasNext()) {
                val eldest = iterator.next()
                currentKb -= eldest.value.sizeKb
                iterator.remove()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
            currentKb = 0
        }
    }
}
