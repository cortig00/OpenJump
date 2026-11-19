package com.openjump.app.ui.angles

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerSessionPort
import com.openjump.app.ui.selector.ViewerTransport
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Petición de apertura del editor de ángulos: un vídeo concreto de un atleta y una clave de sesión
 * estable que hace idempotente el guardado.
 *
 * La URI viaja como `String` a propósito: la capa de decisión del editor (y sus pruebas JVM) no
 * necesita un [android.net.Uri] real; el transporte la materializa solo al abrir el player. Un
 * ViewModel se abre sobre **un** vídeo: abrir otro exige un ViewModel (y por tanto una generación)
 * nuevos, igual que [com.openjump.app.angles.AngleEditorState] exige un estado nuevo por fuente.
 */
internal data class AngleEditorRequest(
    val uri: String,
    val athleteId: Long,
    val sessionKey: String,
    val initialFrameIndex: Int = 0,
) {
    init {
        require(uri.isNotBlank()) { "La URI del editor de ángulos no puede estar vacía." }
        require(athleteId > 0L) { "El athleteId del editor de ángulos debe ser > 0, era $athleteId." }
        require(sessionKey.isNotBlank()) { "El sessionKey del editor de ángulos no puede estar vacío." }
        require(initialFrameIndex >= 0) { "initialFrameIndex debe ser >= 0, era $initialFrameIndex." }
    }
}

/**
 * `sessionKey` de recuperación para «guardar como nuevo»: deriva una clave **distinta** de la
 * original, de modo que la raíz existente (borrada en otra pantalla o con contenido no verificado)
 * nunca se sobrescribe ni se recrea por accidente.
 *
 * El sufijo depende solo de [attempt], así que reintentar el mismo intento produce exactamente la
 * misma clave y el guardado sigue siendo idempotente. El separador `#` no aparece en las claves de
 * sesión que genera la app (URI + frame) y la clave derivada conserva el prefijo original para que
 * siga siendo reconocible en la base de datos.
 */
internal fun recoverySessionKey(baseSessionKey: String, attempt: Int): String {
    require(baseSessionKey.isNotBlank()) {
        "La clave de sesión base no puede estar vacía."
    }
    require(attempt >= 0) { "El intento de recuperación debe ser >= 0, era $attempt." }
    return "$baseSessionKey#copy-${attempt + 1}"
}

/**
 * Sesión local del editor de ángulos: implementa [ViewerSessionPort] **sin** tocar `AppSession`.
 *
 * El editor no comparte la política de placas del selector ni su estado global: guarda su vídeo, su
 * índice y su frame pedido, de modo que abrir o cerrar un análisis de ángulos no altera el selector
 * ni la selección de atleta. La URI se guarda como `String` y solo se materializa en [videoUri],
 * que únicamente consulta el transporte al abrir el player.
 */
internal class AngleViewerSession : ViewerSessionPort {

    private var videoUriString: String? = null

    @Volatile
    private var videoFrameIndex: VideoFrameIndex? = null

    private val requestedFrame = MutableStateFlow(0)

    override val videoUri: Uri?
        get() = videoUriString?.let(Uri::parse)

    override val frameIndex: VideoFrameIndex?
        get() = videoFrameIndex

    override val currentFrameIndex: Int
        get() = requestedFrame.value

    override val currentFrameIndexFlow: StateFlow<Int>
        get() = requestedFrame

    override fun setVideo(uri: Uri, index: VideoFrameIndex) {
        videoUriString = uri.toString()
        videoFrameIndex = index
    }

    override fun setFrameIndex(index: Int) {
        requestedFrame.value = index
    }

    /**
     * Prepara la fuente **ya indexada** por el ViewModel: el transporte la reutiliza en vez de
     * volver a abrir el vídeo, así que existe un único índice real del que salen el frame canónico y
     * sus PTS. Debe llamarse antes de [AngleViewerViewer.start].
     */
    internal fun prepare(videoUri: String, index: VideoFrameIndex, initialFrameIndex: Int) {
        videoUriString = videoUri
        videoFrameIndex = index
        requestedFrame.value = initialFrameIndex
    }
}

/**
 * Frontera mínima sobre el transporte reutilizable del visor ([ViewerTransport]).
 *
 * Existe para que el ViewModel del editor sea verificable en JVM sin Context, ExoPlayer ni
 * superficie, y para que el editor no dependa de la API completa del transporte del selector.
 * Expone los mismos flujos que el transporte (no los duplica) y su ciclo de vida.
 */
internal interface AngleViewerViewer {

    val player: StateFlow<ExoPlayer?>

    val displayState: StateFlow<FrameDisplayState>

    val loading: StateFlow<Boolean>

    val error: StateFlow<String?>

    /** Abre el vídeo de la sesión; debe llamarse antes que [startSessionCollection]. */
    fun start()

    /** Refleja los cambios de frame de la sesión (scrubbing, salto, reanudación). */
    fun startSessionCollection()

    /**
     * Pide un frame **explícitamente**, sin pasar por [ViewerSessionPort.currentFrameIndexFlow].
     *
     * Existe para la reapertura: `setFrameIndex` es un `StateFlow` que deduplica valores iguales, así
     * que pedir el frame que la sesión ya tiene no emite nada y el transporte nunca buscaría. Este
     * método repite el seek exacto aunque el frame pedido no cambie, sin inventar PTS ni frame: el
     * transporte lo acota al índice real y solo lo confirmará con la salida real del player.
     */
    fun seekToFrame(frameIndex: Int)

    /**
     * Reposiciona el visor al frame de un análisis reabierto **sin invalidar** una confirmación
     * vigente del mismo frame y, si no la hay, exigiendo una salida real del player.
     *
     * [seekToFrame] no sirve aquí: pedir el frame que ya está confirmado deja el transporte en
     * `SEEKING` sin que ExoPlayer emita una salida nueva (el seek a la misma posición es un no-op),
     * y el editor se queda sin gestos. Tampoco basta con no pedir nada: si el frame pedido no está
     * confirmado hay que provocar el render.
     */
    fun reopenFrame(frameIndex: Int)

    /** Repite el seek exacto tras crear una superficie nueva. */
    fun refreshCurrentFrame()

    /** Paso relativo exacto de fotograma con el player existente (scrubbing corto compartido). */
    fun stepFrames(delta: Int)

    /** Reproduce o pausa con el player existente. */
    fun togglePlayback()

    /** Pausa el player existente y publica el frame renderizado como actual. */
    fun pausePlayback()

    /** Inicia una interacción de scrubbing (arrastre) sobre el player existente. */
    fun beginScrubbing()

    /** Busca un frame ya indexado durante el scrubbing. */
    fun scrubToFrame(frameIndex: Int)

    /** Cierra el scrubbing y exige el render del target antes de permitir gestos. */
    fun endScrubbing()

    /** Cancela el cierre diferido del scrub antes de liberar. */
    fun cancelPendingScrubRelease()

    /** Suelta el player. */
    fun release()
}

/** Adaptador de producción: el transporte común del visor, sin semántica añadida. */
internal class TransportAngleViewerViewer(
    private val transport: ViewerTransport,
) : AngleViewerViewer {

    override val player: StateFlow<ExoPlayer?> get() = transport.player

    override val displayState: StateFlow<FrameDisplayState> get() = transport.displayState

    override val loading: StateFlow<Boolean> get() = transport.loading

    override val error: StateFlow<String?> get() = transport.error

    override fun start() = transport.start()

    override fun startSessionCollection() = transport.startSessionCollection()

    override fun seekToFrame(frameIndex: Int) = transport.requestFrame(
        requestedIndex = frameIndex,
        mode = ViewerTransportMode.SEEKING,
        updateSession = false,
    )

    override fun reopenFrame(frameIndex: Int) = transport.requestFrameForReopen(frameIndex)

    override fun refreshCurrentFrame() = transport.refreshCurrentFrame()

    override fun stepFrames(delta: Int) = transport.stepFrames(delta)

    override fun togglePlayback() = transport.togglePlayback()

    override fun pausePlayback() = transport.pausePlayback()

    override fun beginScrubbing() = transport.beginScrubbing()

    override fun scrubToFrame(frameIndex: Int) = transport.scrubToFrame(frameIndex)

    override fun endScrubbing() = transport.endScrubbing()

    override fun cancelPendingScrubRelease() = transport.cancelPendingScrubRelease()

    override fun release() = transport.release()
}

/**
 * Por qué el editor no puede abrir el vídeo. No incluye "el análisis no existe": eso pertenece al
 * repositorio y nunca debe borrar ni reescribir datos guardados.
 */
internal enum class AngleVideoUnavailableReason {
    /** La URI ya no es accesible (permiso revocado, fichero borrado, proveedor cerrado). */
    URI_UNAVAILABLE,

    /** El vídeo se abrió pero no se pudo indexar (contenedor dañado, sin track de vídeo). */
    INDEX_FAILED,
}

/** Fallo tipado de la frontera de indexación; el ViewModel no interpreta mensajes de texto. */
internal class AngleVideoUnavailableException(
    val reason: AngleVideoUnavailableReason,
    cause: Throwable? = null,
) : Exception(cause)

/** Frontera de indexación: suspend para que el adaptador despache a IO sin que el ViewModel lo haga. */
internal fun interface AngleVideoIndexer {
    suspend fun index(uri: String): VideoFrameIndex
}

/**
 * Clasifica el fallo de indexación a partir del tipo de excepción, nunca de su mensaje.
 *
 * Una URI que ya no se puede abrir ([SecurityException], [FileNotFoundException]) es
 * [AngleVideoUnavailableReason.URI_UNAVAILABLE]. Cualquier otro fallo —en particular un
 * [IllegalArgumentException] de las comprobaciones del indexador o del propio contrato de
 * [VideoFrameIndex]— es [AngleVideoUnavailableReason.INDEX_FAILED]: un vídeo que no cumple el
 * contrato no se disfraza de permiso revocado.
 */
internal fun angleVideoUnavailableReasonFor(failure: Throwable): AngleVideoUnavailableReason =
    when (failure) {
        is SecurityException, is FileNotFoundException -> AngleVideoUnavailableReason.URI_UNAVAILABLE
        is IllegalArgumentException -> AngleVideoUnavailableReason.INDEX_FAILED
        else -> AngleVideoUnavailableReason.INDEX_FAILED
    }

/**
 * Adaptador de producción: indexa con [VideoFrameIndexer] en IO y traduce los fallos de plataforma
 * a [AngleVideoUnavailableException], distinguiendo URI inaccesible de vídeo no indexable.
 */
internal class ContextAngleVideoIndexer(
    private val context: Context,
) : AngleVideoIndexer {

    override suspend fun index(uri: String): VideoFrameIndex = withContext(Dispatchers.IO) {
        val indexingContext = coroutineContext
        try {
            VideoFrameIndexer.indexFromUri(context, Uri.parse(uri)) {
                indexingContext.ensureActive()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AngleVideoUnavailableException(angleVideoUnavailableReasonFor(failure), failure)
        }
    }
}
