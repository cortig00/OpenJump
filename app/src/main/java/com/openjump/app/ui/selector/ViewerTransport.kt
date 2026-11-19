package com.openjump.app.ui.selector

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.openjump.app.R
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.ViewerFrameAlignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transporte reutilizable del visor frame a frame: ExoPlayer sobre SurfaceView, alineación
 * PTS -> frame canónico, scrubbing, salto, reproducción y suspensión/reanudación.
 *
 * Extraído del selector sin cambios de semántica para poder compartirlo con el editor de
 * ángulos:
 * - Los flujos son los mismos objetos (`player`, `displayState`, `loading`, `error`) y se
 *   exponen tal cual; no hay estado duplicado.
 * - El orden de las operaciones de cada método es el original, incluidos los efectos sobre
 *   la sesión ([ViewerSessionPort]) y el callback de invalidación, que se invoca solamente
 *   desde [requestFrame], donde el selector invalidaba su trabajo de placas.
 * - Los collectors siguen lanzándose en el scope del llamador (en el selector,
 *   `viewModelScope`), de modo que despachador, orden y cancelación no cambian.
 *
 * Scrubbing Mode mantiene el codec caliente y optimiza seeks frecuentes. La salida se
 * renderiza directamente por GPU: no hay copias YUV -> RGB ni bitmaps intermedios.
 * [VideoFrameMetadataListener] confirma el PTS que Media3 va a renderizar, de modo que
 * nunca se marca un target todavía obsoleto.
 */
@OptIn(UnstableApi::class)
class ViewerTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: ViewerSessionPort,
    /**
     * Único punto de invalidación externa: se ejecuta dentro de [requestFrame], después de
     * acotar el índice y antes de pausar el player, que es donde el selector invalidaba su
     * trabajo de placas. [endScrubbing], [refreshCurrentFrame] y la reanudación tras exportación
     * no lo invocan, igual que antes de la extracción.
     */
    private val onFrameRequested: () -> Unit,
) {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _displayState = MutableStateFlow(FrameDisplayState())
    val displayState: StateFlow<FrameDisplayState> = _displayState.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val frameAlignment = ViewerFrameAlignment()
    private var scrubbingActive = false
    @Volatile
    private var scrubEndRequested = false
    private var scrubReleaseJob: Job? = null

    private var viewerSuspendedForExport = false
    private var viewerSuspendedForResult = false

    private val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        onFrameAboutToBeRendered(presentationTimeUs)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            _error.value = context.getString(R.string.selector_error_show_video)
            _loading.value = false
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) pausePlayback()
        }
    }

    /** Lectura agregada para consumidores que necesitan display/carga/error de una vez. */
    val state: ViewerTransportState
        get() = ViewerTransportState(
            display = _displayState.value,
            loading = _loading.value,
            error = _error.value,
        )

    /**
     * Frame confirmado por [FrameDisplayState.canMark]; null mientras Media3 no confirmó el
     * PTS del frame solicitado en pausa. No demuestra presentación física en la superficie.
     */
    val confirmedFrameIndex: Int?
        get() = state.confirmedFrameIndex

    /** Abre el vídeo de la sesión en el player. Debe llamarse antes que [startSessionCollection]. */
    fun start() {
        scope.launch {
            openVideo()
        }
    }

    /**
     * Refleja el frame pedido por la sesión. El selector lo arranca después de sus propios
     * collectors de placas para conservar el orden de lanzamiento previo a la extracción.
     */
    fun startSessionCollection() {
        scope.launch {
            session.currentFrameIndexFlow.collect { requested ->
                val current = _displayState.value
                if (viewerSessionRequestIsRedundant(requested, current)) return@collect
                val mode = viewerRequestMode(scrubbingActive, scrubEndRequested)
                requestFrame(requested, mode, updateSession = false)
            }
        }
    }

    private suspend fun openVideo() {
        try {
            val uri = session.videoUri
            if (uri == null) {
                _error.value = context.getString(R.string.selector_no_video)
                _loading.value = false
                return
            }

            val index = session.frameIndex ?: withContext(Dispatchers.IO) {
                val indexingContext = coroutineContext
                VideoFrameIndexer.indexFromUri(context, uri) { indexingContext.ensureActive() }
            }.also { session.setVideo(uri, it) }

            require(index.frameCount > 0) { "El vídeo no contiene frames analizables." }

            val initialIndex = session.currentFrameIndex.coerceIn(0, index.frameCount - 1)
            frameAlignment.rebase(initialIndex)
            _displayState.value = FrameDisplayState(
                requestedIndex = initialIndex,
                mode = ViewerTransportMode.SEEKING,
            )

            val exoPlayer = ExoPlayer.Builder(context)
                .setSeekParameters(SeekParameters.EXACT)
                .build()
                .apply {
                    addListener(playerListener)
                    setVideoFrameMetadataListener(frameMetadataListener)
                    volume = 0f
                    playWhenReady = false
                    setMediaItem(MediaItem.fromUri(uri))
                    seekTo(index.timeOf(initialIndex) / 1_000L)
                    prepare()
                }

            _player.value = exoPlayer
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _error.value = context.getString(R.string.selector_error_analyze_video)
            _loading.value = false
        }
    }

    private fun seekToFrame(requestedIndex: Int) {
        val index = session.frameIndex ?: return
        val exoPlayer = _player.value ?: return
        val clamped = requestedIndex.coerceIn(0, index.frameCount - 1)
        exoPlayer.seekTo(index.timeOf(clamped) / 1_000L)
    }

    /** Pide un frame: acota, invalida trabajo externo, pausa, actualiza estado y busca. */
    fun requestFrame(
        requestedIndex: Int,
        mode: ViewerTransportMode,
        updateSession: Boolean = true,
    ) {
        val index = session.frameIndex ?: return
        val clamped = requestedIndex.coerceIn(0, index.frameCount - 1)
        onFrameRequested()
        _player.value?.pause()
        _displayState.update { it.request(clamped, mode) }
        _loading.value = viewerLoadingAfterRequest(_displayState.value)
        if (updateSession) session.setFrameIndex(clamped)
        seekToFrame(clamped)
    }

    /**
     * Publica la salida real del player. **No** llama a la API de ExoPlayer: Media3 invoca este
     * callback desde el hilo de reproducción (`ExoPlayer:Playback`) dentro de
     * `MediaCodecVideoRenderer.render()`, mientras que `seekTo`/`pause`/`prepare`/
     * `setScrubbingModeEnabled` exigen el hilo de aplicación y lanzan `IllegalStateException` en
     * otro hilo (`verifyApplicationThread`). Por eso aquí solo se publica estado; cualquier efecto
     * sobre el player se encola en [scope] (hilo de aplicación en ambos consumidores).
     *
     * Corolario: un callback atrasado puede dejar asentado un frame distinto del pedido. Ese caso
     * se corrige pidiendo el frame de nuevo desde la aplicación ([requestFrame]); repetir el seek
     * desde este callback es imposible sin cambiar de hilo y especulativo sobre el estado.
     */
    private fun onFrameAboutToBeRendered(playerPtsUs: Long) {
        val index: VideoFrameIndex = session.frameIndex ?: return
        if (index.frameCount == 0) return

        // Ver ViewerFrameAlignment: el desfase se fija con la primera salida, que se
        // asume correspondiente al seek inicial exacto, y se reutiliza después.
        val aligned = frameAlignment.align(index, playerPtsUs)

        _displayState.update { it.rendered(aligned.renderedIndex, aligned.canonicalPtsUs) }
        _loading.value = false
        if (scrubEndRequested && _displayState.value.renderedIndex == _displayState.value.requestedIndex) {
            scope.launch { finishScrubbing() }
        }
        if (_displayState.value.canMark) _error.value = null
    }

    /** Starts a short Media3 scrubbing interaction. Playback is always paused first. */
    fun beginScrubbing() {
        val exoPlayer = _player.value ?: return
        scrubReleaseJob?.cancel()
        scrubEndRequested = false
        exoPlayer.pause()
        if (!scrubbingActive) {
            exoPlayer.setScrubbingModeEnabled(true)
            scrubbingActive = true
        }
        _displayState.update {
            it.request(it.requestedIndex, ViewerTransportMode.SCRUBBING)
        }
    }

    /** Seeks to an indexed PTS while Media3 coalesces intermediate scrub requests. */
    fun scrubToFrame(frameIndex: Int) {
        beginScrubbing()
        requestFrame(frameIndex, ViewerTransportMode.SCRUBBING)
    }

    /** Performs an exact frame step; a burst shares one short scrubbing session. */
    fun stepFrames(delta: Int) {
        if (delta == 0) return
        val before = _displayState.value
        val base = viewerStepBaseFrame(before, session.currentFrameIndex)
        beginScrubbing()
        requestFrame(base + delta, ViewerTransportMode.SCRUBBING)
        scrubReleaseJob = scope.launch {
            delay(STEP_SCRUB_RELEASE_MS)
            endScrubbing()
        }
    }

    /** Requires the final scrub target to render before precision-sensitive actions are enabled. */
    fun endScrubbing() {
        if (!scrubbingActive) return
        scrubReleaseJob?.cancel()
        scrubReleaseJob = null
        scrubEndRequested = true
        val target = session.currentFrameIndex
        _displayState.update { it.request(target, ViewerTransportMode.SEEKING) }
        if (viewerScrubEndSettled(_displayState.value.renderedIndex, target)) {
            finishScrubbing()
        } else {
            seekToFrame(target)
        }
    }

    private fun finishScrubbing() {
        if (scrubbingActive) _player.value?.setScrubbingModeEnabled(false)
        scrubbingActive = false
        scrubEndRequested = false
        _displayState.update { state ->
            state.copy(mode = viewerScrubSettledMode(state.renderedIndex, state.requestedIndex))
        }
    }

    fun togglePlayback() {
        val exoPlayer = _player.value ?: return
        if (exoPlayer.playWhenReady || _displayState.value.isPlaying) {
            pausePlayback()
            return
        }

        scrubReleaseJob?.cancel()
        scrubReleaseJob = null
        finishScrubbing()
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekToDefaultPosition()
            _displayState.update { it.request(0, ViewerTransportMode.PLAYING) }
            session.setFrameIndex(0)
        } else {
            _displayState.update { it.playing() }
        }
        _loading.value = false
        exoPlayer.play()
    }

    fun pausePlayback() {
        val exoPlayer = _player.value ?: return
        scrubReleaseJob?.cancel()
        scrubReleaseJob = null
        if (scrubbingActive) finishScrubbing()
        exoPlayer.pause()
        val paused = _displayState.value.pauseAtRendered()
        _displayState.value = paused
        paused.renderedIndex?.let(session::setFrameIndex)
    }

    /**
     * PlayerView crea una superficie nueva al volver desde Resultados. ExoPlayer
     * puede presentar entonces el siguiente buffer ya decodificado; repetir el
     * seek exacto mantiene superficie, etiqueta y PTS sincronizados.
     */
    fun refreshCurrentFrame() {
        if (viewerSuspendedForExport) return
        if (viewerSuspendedForResult) viewerSuspendedForResult = false
        val requested = session.currentFrameIndex
        val index = session.frameIndex ?: return
        val exoPlayer = _player.value ?: return

        if (_displayState.value.isPlaying) return
        _displayState.update { it.request(requested, ViewerTransportMode.SEEKING) }
        _loading.value = viewerLoadingAfterRequest(_displayState.value)

        // Una superficie nueva puede llegar DESPUES del seek inicial (arranque en frio) o al
        // volver de otra pantalla. Un seek a la misma posicion es un no-op para ExoPlayer y deja
        // el visor negro hasta que el usuario avanza un fotograma. Reiniciar la fuente en este
        // borde garantiza que la superficie recien creada reciba el frame solicitado; no se hace
        // durante la reproduccion (guard anterior) y el coste queda acotado a crear la superficie.
        exoPlayer.stop()
        exoPlayer.seekTo(index.timeOf(requested) / 1_000L)
        exoPlayer.prepare()
    }

    /**
     * Reposiciona el visor en una reapertura **sin falsear la confirmación**.
     *
     * A diferencia de [requestFrame], no invalida una confirmación vigente: si el transporte ya
     * confirma en pausa exactamente ese frame y el player sigue preparado sobre la misma fuente, no
     * toca el player. Un `seekTo` a la posición actual es un no-op para ExoPlayer que jamás emitiría
     * una salida nueva, así que pedirlo dejaría el visor en `SEEKING` con los gestos deshabilitados
     * para siempre (típicamente al reabrir un análisis anclado en el frame 0 o en el frame que ya se
     * estaba viendo). La confirmación conservada es la real: el frame ya se presentó y su PTS no se
     * reescribe.
     *
     * En cualquier otro caso —otro frame, sin confirmación, player detenido o suspendido— garantiza
     * una salida real reiniciando la fuente, igual que [refreshCurrentFrame]: el frame pedido puede
     * coincidir con la posición actual sin estar confirmado, y solo una salida real del player
     * habilita los gestos.
     */
    fun requestFrameForReopen(requestedIndex: Int) {
        val index = session.frameIndex ?: return
        val clamped = requestedIndex.coerceIn(0, index.frameCount - 1)
        val exoPlayer = _player.value
        val playerUsable = exoPlayer != null &&
            !viewerSuspendedForExport &&
            !viewerSuspendedForResult &&
            exoPlayer.playbackState != Player.STATE_IDLE &&
            !exoPlayer.playWhenReady
        if (viewerReopenKeepsConfirmation(_displayState.value, clamped, playerUsable)) return
        if (exoPlayer == null) return

        onFrameRequested()
        exoPlayer.pause()
        _displayState.update { it.request(clamped, ViewerTransportMode.SEEKING) }
        _loading.value = viewerLoadingAfterRequest(_displayState.value)

        // Reiniciar la fuente garantiza una salida nueva incluso si la posición no cambia; no se
        // reancla `ViewerFrameAlignment` porque el vídeo y su índice siguen siendo los mismos.
        exoPlayer.stop()
        exoPlayer.seekTo(index.timeOf(clamped) / 1_000L)
        exoPlayer.prepare()
    }

    fun suspendViewerForResult() {
        if (viewerSuspendedForResult) return
        pausePlayback()
        _player.value?.stop()
        viewerSuspendedForResult = true
    }

    /** Suspende el visor antes de exportar; comparte el estado de suspensión con [refreshCurrentFrame]. */
    fun suspendViewerForExport() {
        if (viewerSuspendedForExport) return
        pausePlayback()
        _player.value?.stop()
        viewerSuspendedForExport = true
    }

    /** Reancla la alineación y repite el seek exacto al volver de una exportación. */
    fun resumeViewerAfterExport() {
        if (!viewerSuspendedForExport) return
        viewerSuspendedForExport = false
        val index = session.frameIndex ?: return
        val requested = session.currentFrameIndex.coerceIn(0, index.frameCount - 1)
        val exoPlayer = _player.value ?: return
        frameAlignment.rebase(requested)
        _displayState.update { it.request(requested, ViewerTransportMode.SEEKING) }
        _loading.value = true
        exoPlayer.seekTo(index.timeOf(requested) / 1_000L)
        exoPlayer.prepare()
    }

    /** Cancela el cierre diferido del scrub; el ViewModel lo hacía como primera línea de onCleared. */
    fun cancelPendingScrubRelease() {
        scrubReleaseJob?.cancel()
    }

    /** Suelta el player en el mismo punto y orden que el onCleared original. */
    fun release() {
        _player.value?.let { exoPlayer ->
            exoPlayer.clearVideoFrameMetadataListener(frameMetadataListener)
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
        _player.value = null
    }

    companion object {
        internal const val STEP_SCRUB_RELEASE_MS = 300L
    }
}
