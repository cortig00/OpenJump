package com.openjump.app.ui.selector

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.GeometricPlateDetector
import com.openjump.app.encoder.PlateDetection
import com.openjump.app.encoder.PlateDetectionMode
import com.openjump.app.encoder.PlateDetectionOutcome
import com.openjump.app.encoder.PlateDetectorConfig
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.protocol.EventType
import com.openjump.app.tracking.DecodedTrackingFrame
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.OfflineVideoTracker
import com.openjump.app.tracking.PyramidalKltMotionTracker
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingPerformanceProfiler
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.AppSession
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.isPlateCircleWithinFrame
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.VideoTrackingFrameSource
import com.openjump.app.video.toPresentation
import com.openjump.app.video.export.TrajectoryVideoExporter
import com.openjump.app.video.export.VideoExportValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.hypot

enum class TrackingPhase { IDLE, INITIALIZING, READY, PROCESSING, COMPLETED, LOST, ERROR }

enum class PlateDetectionPhase {
    IDLE,
    DETECTING_AUTO,
    ASSISTED_REQUIRED,
    DETECTING_ASSISTED,
    REVIEW,
    DETECTED,
    ASSISTED_FAILED,
    MANUAL,
}

data class PlateDetectionRequestIdentity(
    val sessionKey: String,
    val videoUri: String,
    /** Stable URI object identity in production; kept as Any so pure JVM policy tests avoid Android Uri stubs. */
    val videoUriObject: Any,
    val frameIndex: Int,
    val ptsUs: Long,
    val timingDecision: VideoTimingDecision,
    val sessionPlateDiameterCm: Double,
    val sessionGeneration: Long,
    val displayRequestGeneration: Long,
)

internal fun plateDetectionRequestIsCurrent(
    expected: PlateDetectionRequestIdentity,
    current: PlateDetectionRequestIdentity?,
): Boolean = expected == current

internal fun canUseConfirmedPlateCalibration(
    phase: PlateDetectionPhase,
    draft: EncoderDraft?,
): Boolean = draft != null &&
    phase in setOf(PlateDetectionPhase.DETECTED, PlateDetectionPhase.MANUAL) &&
    draft.calibration != null && draft.calibrationPointA != null &&
    draft.calibrationPointB != null && draft.calibrationFrameIndex != null

data class PlateCalibrationProposal(
    val sessionKey: String,
    val videoUri: String,
    val generation: Long,
    val frameIndex: Int,
    val ptsUs: Long,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val originalDetection: PlateDetection?,
    val center: ImagePoint,
    val diameterPx: Double,
    val angleRadians: Double,
    val provenance: String?,
    val adjusted: Boolean,
    val requiresAdjustment: Boolean,
    val requestIdentity: PlateDetectionRequestIdentity? = null,
) {
    val isFinite: Boolean
        get() = center.x.isFinite() && center.y.isFinite() && diameterPx.isFinite() &&
            diameterPx > 0.0 && angleRadians.isFinite() && presentationWidth > 0 && presentationHeight > 0

    val isWithinPresentationBounds: Boolean
        get() = isFinite && isPlateCircleWithinFrame(
            center = center,
            diameterPx = diameterPx,
            angleRadians = angleRadians,
            frameWidth = presentationWidth,
            frameHeight = presentationHeight,
        )
}

data class PlateDetectionDebug(
    val detectionMode: PlateDetectionMode,
    val plateDetected: Boolean,
    val tapX: Double? = null,
    val tapY: Double? = null,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val diameterPx: Double? = null,
    val diameterCm: Double,
    val confidence: Double? = null,
    val perspectiveRatio: Double? = null,
    val cmPerPixel: Double? = null,
    val autoCalibrationAccepted: Boolean = false,
    val fallbackReason: String? = null,
)

data class PlateDetectionUiState(
    val phase: PlateDetectionPhase = PlateDetectionPhase.IDLE,
    val detection: PlateDetection? = null,
    val proposal: PlateCalibrationProposal? = null,
    val message: String? = null,
    val debug: PlateDetectionDebug? = null,
    val manualCalibrationRequested: Boolean = false,
    val confirming: Boolean = false,
) {
    val isBusy: Boolean get() = phase == PlateDetectionPhase.DETECTING_AUTO || phase == PlateDetectionPhase.DETECTING_ASSISTED || confirming
    val acceptsAssistedTap: Boolean get() = phase == PlateDetectionPhase.ASSISTED_REQUIRED || phase == PlateDetectionPhase.ASSISTED_FAILED
    val canConfirm: Boolean get() = phase == PlateDetectionPhase.REVIEW && !confirming && proposal?.let {
        it.isWithinPresentationBounds && (!it.requiresAdjustment || it.adjusted)
    } == true
}

data class TrackingUiState(
    val phase: TrackingPhase = TrackingPhase.IDLE,
    val results: List<TrackingFrameResult> = emptyList(),
    val progress: Float = 0f,
    val message: String? = null,
    val processingPreview: TrackingProcessingPreview? = null,
) {
    val isBusy: Boolean get() = phase == TrackingPhase.INITIALIZING || phase == TrackingPhase.PROCESSING
    val canStart: Boolean get() = phase == TrackingPhase.READY
}

/**
 * Visor frame a frame basado en ExoPlayer sobre SurfaceView.
 *
 * Scrubbing Mode mantiene el codec caliente y optimiza seeks frecuentes. La
 * salida se renderiza directamente por GPU: no hay copias YUV -> RGB ni bitmaps
 * intermedios. [VideoFrameMetadataListener] confirma el PTS que Media3 va a
 * renderizar, de modo que nunca se marca un target todavía obsoleto.
 */
@OptIn(UnstableApi::class)
class FrameSelectorViewModel(private val context: Context) : ViewModel() {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _displayState = MutableStateFlow(FrameDisplayState())
    val displayState: StateFlow<FrameDisplayState> = _displayState.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _trackingState = MutableStateFlow(TrackingUiState())
    val trackingState: StateFlow<TrackingUiState> = _trackingState.asStateFlow()

    private val _plateDetectionState = MutableStateFlow(PlateDetectionUiState())
    val plateDetectionState: StateFlow<PlateDetectionUiState> = _plateDetectionState.asStateFlow()

    private val _exportState = MutableStateFlow(TrajectoryExportUiState())
    val exportState: StateFlow<TrajectoryExportUiState> = _exportState.asStateFlow()

    private val app = context.applicationContext as OpenJumpApp
    private val retainedVideoUri = AppSession.videoUri.value?.toString()
    private val videoUriLease = retainedVideoUri?.let { app.videoUriGrantReconciler.registerOwner(setOf(it)) }
    private val debugLoggingEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val trajectoryExporter = TrajectoryVideoExporter(context)
    private var exportValidationJob: Job? = null
    private var viewerSuspendedForExport = false
    private var viewerSuspendedForResult = false
    private var trackingJob: Job? = null
    private var plateDetectionJob: Job? = null
    private var offlineTracker: OfflineVideoTracker? = null
    private var trackingGeneration = 0L
    private var trackingSessionKey: String? = null
    private var trackingVideoUri: String? = null
    @Volatile private var trackingPreviewVisible = false
    @Volatile private var pendingTrackingPreview: PendingTrackingPreview? = null
    private data class PendingTrackingPreview(val generation: Long, val frameIndex: Int, val ptsUs: Long, val bitmap: Bitmap)

    fun setTrackingPreviewVisible(visible: Boolean) {
        trackingPreviewVisible = visible
        if (!visible) {
            pendingTrackingPreview = null
            _trackingState.update { it.copy(processingPreview = null) }
        }
    }

    private val trackingCompletionCoordinator = EncoderTrackingCompletionCoordinator()
    private var plateDetectionGeneration = 0L
    private var plateSessionGeneration = 0L
    private var plateDisplayRequestGeneration = 0L
    private val plateDetectorConfig = PlateDetectorConfig()
    private val plateDetector = GeometricPlateDetector(plateDetectorConfig)
    private var ptsOffsetUs: Long? = null
    private var calibrationIndex = 0
    private var scrubbingActive = false

    @Volatile
    private var scrubEndRequested = false
    private var scrubReleaseJob: Job? = null

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

    init {
        viewModelScope.launch {
            openVideo()
        }
        viewModelScope.launch {
            var observedSessionKey: String? = null
            var observedVideoUri: String? = null
            var observedTiming: VideoTimingDecision? = null
            AppSession.encoderDraft.collect { draft ->
                val contextChanged = observedSessionKey != null &&
                    (draft?.sessionKey != observedSessionKey || draft?.videoUri != observedVideoUri ||
                        draft?.timingDecision != observedTiming)
                if (contextChanged) {
                    plateSessionGeneration++
                    invalidatePlateWork(resetPendingState = true)
                }
                observedSessionKey = draft?.sessionKey
                observedVideoUri = draft?.videoUri
                observedTiming = draft?.timingDecision
            }
        }
        viewModelScope.launch {
            AppSession.currentFrameIndex.collect { requested ->
                val current = _displayState.value
                if (requested == current.requestedIndex) return@collect

                val mode = if (scrubbingActive && !scrubEndRequested) {
                    ViewerTransportMode.SCRUBBING
                } else {
                    ViewerTransportMode.SEEKING
                }
                requestFrame(requested, mode, updateSession = false)
            }
        }
    }

    private suspend fun openVideo() {
        try {
            val uri = AppSession.videoUri.value
            if (uri == null) {
                _error.value = context.getString(R.string.selector_no_video)
                _loading.value = false
                return
            }

            val index = AppSession.frameIndex.value ?: withContext(Dispatchers.IO) {
                val indexingContext = coroutineContext
                VideoFrameIndexer.indexFromUri(context, uri) { indexingContext.ensureActive() }
            }.also { AppSession.setVideo(uri, it) }

            require(index.frameCount > 0) { "El vídeo no contiene frames analizables." }

            val initialIndex = AppSession.currentFrameIndex.value.coerceIn(0, index.frameCount - 1)
            calibrationIndex = initialIndex
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
        val index = AppSession.frameIndex.value ?: return
        val exoPlayer = _player.value ?: return
        val clamped = requestedIndex.coerceIn(0, index.frameCount - 1)
        exoPlayer.seekTo(index.timeOf(clamped) / 1_000L)
    }

    private fun requestFrame(
        requestedIndex: Int,
        mode: ViewerTransportMode,
        updateSession: Boolean = true,
    ) {
        val index = AppSession.frameIndex.value ?: return
        val clamped = requestedIndex.coerceIn(0, index.frameCount - 1)
        plateDisplayRequestGeneration++
        invalidatePlateWork(resetPendingState = true)
        _player.value?.pause()
        _displayState.update { it.request(clamped, mode) }
        _loading.value = _displayState.value.renderedIndex == null
        if (updateSession) AppSession.setFrameIndex(clamped)
        seekToFrame(clamped)
    }

    private fun onFrameAboutToBeRendered(playerPtsUs: Long) {
        val index: VideoFrameIndex = AppSession.frameIndex.value ?: return
        if (index.frameCount == 0) return

        // ExoPlayer normaliza algunos contenedores para empezar en cero. La
        // primera salida corresponde al seek inicial exacto y permite alinear
        // su timeline con los PTS originales del MediaExtractor.
        val offset = ptsOffsetUs ?: (index.timeOf(calibrationIndex) - playerPtsUs).also {
            ptsOffsetUs = it
        }
        val sourcePtsUs = playerPtsUs + offset
        val renderedIndex = index.indexOfNearest(sourcePtsUs)
        val canonicalPtsUs = index.timeOf(renderedIndex)

        _displayState.update { it.rendered(renderedIndex, canonicalPtsUs) }
        _loading.value = false
        if (scrubEndRequested && _displayState.value.renderedIndex == _displayState.value.requestedIndex) {
            viewModelScope.launch { finishScrubbing() }
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
        val base = if (before.isPlaying) {
            before.renderedIndex ?: AppSession.currentFrameIndex.value
        } else {
            AppSession.currentFrameIndex.value
        }
        beginScrubbing()
        requestFrame(base + delta, ViewerTransportMode.SCRUBBING)
        scrubReleaseJob = viewModelScope.launch {
            delay(STEP_SCRUB_RELEASE_MS)
            endScrubbing()
        }
    }

    fun jumpTo(type: EventType) {
        AppSession.jumpTo(type)
        requestFrame(AppSession.currentFrameIndex.value, ViewerTransportMode.SEEKING)
    }

    /** Requires the final scrub target to render before precision-sensitive actions are enabled. */
    fun endScrubbing() {
        if (!scrubbingActive) return
        scrubReleaseJob?.cancel()
        scrubReleaseJob = null
        scrubEndRequested = true
        val target = AppSession.currentFrameIndex.value
        _displayState.update { it.request(target, ViewerTransportMode.SEEKING) }
        if (_displayState.value.renderedIndex == target) {
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
            if (state.renderedIndex == state.requestedIndex) {
                state.copy(mode = ViewerTransportMode.PAUSED)
            } else {
                state.copy(mode = ViewerTransportMode.SEEKING)
            }
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
            AppSession.setFrameIndex(0)
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
        paused.renderedIndex?.let(AppSession::setFrameIndex)
    }

    /**
     * PlayerView crea una superficie nueva al volver desde Resultados. ExoPlayer
     * puede presentar entonces el siguiente buffer ya decodificado; repetir el
     * seek exacto mantiene superficie, etiqueta y PTS sincronizados.
     */
    fun refreshCurrentFrame() {
        if (viewerSuspendedForExport) return
        if (viewerSuspendedForResult) viewerSuspendedForResult = false
        val requested = AppSession.currentFrameIndex.value
        val index = AppSession.frameIndex.value ?: return
        val exoPlayer = _player.value ?: return
        val hadRenderedFrame = _displayState.value.renderedIndex != null

        if (_displayState.value.isPlaying) return
        _displayState.update { it.request(requested, ViewerTransportMode.SEEKING) }
        _loading.value = _displayState.value.renderedIndex == null

        if (hadRenderedFrame) {
            // Al recrear SurfaceView, un codec pausado puede entregar el buffer
            // siguiente. Reiniciar solo en este borde de lifecycle garantiza
            // el frame exacto sin penalizar la navegación normal.
            exoPlayer.stop()
            exoPlayer.seekTo(index.timeOf(requested) / 1_000L)
            exoPlayer.prepare()
        } else {
            seekToFrame(requested)
        }
    }

    /** A user chooses the reference before any optional detection runs. */
    fun requestPlateTap() {
        if (_plateDetectionState.value.isBusy || _trackingState.value.isBusy) return
        invalidatePlateWork(resetPendingState = false)
        resetTracking()
        AppSession.clearDetectedPlateCalibration()
        _plateDetectionState.value = PlateDetectionUiState(phase = PlateDetectionPhase.ASSISTED_REQUIRED)
    }

    fun detectPlateAutomatically() {
        if (_plateDetectionState.value.phase !in setOf(
                PlateDetectionPhase.IDLE, PlateDetectionPhase.ASSISTED_REQUIRED, PlateDetectionPhase.ASSISTED_FAILED,
            )) return
        runPlateDetection(PlateDetectionMode.AUTO, null)
    }

    /** Manual geometry has the same frame/identity guards as a detector proposal. */
    fun startManualPlateCircle() {
        if (_plateDetectionState.value.isBusy || _trackingState.value.isBusy) return
        val display = _displayState.value
        val frame = display.renderedIndex ?: return
        if (!display.canMark) return
        val index = AppSession.frameIndex.value ?: return
        val identity = currentPlateRequestIdentity(frame) ?: return
        val geometry = com.openjump.app.tracking.VideoPresentationGeometry.fromEncoded(
            index.width, index.height, index.rotationDegrees,
        )
        val existing = _plateDetectionState.value.proposal?.takeIf {
            it.frameIndex == frame && it.ptsUs == display.renderedPtsUs && it.requestIdentity == identity
        }
        invalidatePlateWork(resetPendingState = false)
        resetTracking()
        AppSession.clearDetectedPlateCalibration()
        val proposal = existing?.copy(
            generation = plateDetectionGeneration, originalDetection = null, provenance = "USER_CIRCLE",
            adjusted = false, requiresAdjustment = true,
        ) ?: provisionalProposal(
            center = ImagePoint(geometry.width / 2.0, geometry.height / 2.0),
            generation = plateDetectionGeneration, frameIndex = frame, ptsUs = index.timeOf(frame),
            width = geometry.width, height = geometry.height, requestIdentity = identity,
        )
        _plateDetectionState.value = PlateDetectionUiState(
            phase = PlateDetectionPhase.REVIEW,
            proposal = proposal,
            message = context.getString(R.string.selector_plate_review_body),
        )
    }

    fun detectPlateNear(approximateTap: ImagePoint) {
        if (!_plateDetectionState.value.acceptsAssistedTap) return
        runPlateDetection(PlateDetectionMode.ASSISTED, approximateTap)
    }

    /** Updates only the ephemeral review proposal; no session calibration is changed here. */
    fun updatePlateProposal(center: ImagePoint, diameterPx: Double) {
        val state = _plateDetectionState.value
        val proposal = state.proposal ?: return
        if (state.confirming) return
        if (state.phase !in setOf(
                PlateDetectionPhase.REVIEW,
                PlateDetectionPhase.ASSISTED_FAILED,
                PlateDetectionPhase.ASSISTED_REQUIRED,
            ) || !center.x.isFinite() || !center.y.isFinite() ||
            !diameterPx.isFinite() || diameterPx <= 0.0
        ) return
        val (clampedCenter, clampedDiameter) = boundedPlateProposalGeometry(
            center, diameterPx, proposal.presentationWidth, proposal.presentationHeight,
        ) ?: return
        if (!proposal.adjusted) {
            resetTracking()
            AppSession.clearDetectedPlateCalibration()
        }
        _plateDetectionState.update {
            it.copy(
                phase = PlateDetectionPhase.REVIEW,
                proposal = proposal.copy(center = clampedCenter, diameterPx = clampedDiameter, adjusted = true),
                message = context.getString(R.string.selector_plate_adjusted),
            )
        }
    }

    fun adjustPlateDiameter(deltaPx: Double) {
        val proposal = _plateDetectionState.value.proposal ?: return
        updatePlateProposal(proposal.center, proposal.diameterPx + deltaPx)
    }

    /** Commits only after the exact anchored frame is rendered again and the proposal is valid. */
    fun confirmPlateProposal() {
        val state = _plateDetectionState.value
        val proposal = state.proposal ?: return
        if (!state.canConfirm || _trackingState.value.isBusy || _exportState.value.blocksViewer) return
        val display = _displayState.value
        val uri = AppSession.videoUri.value ?: return
        val index = AppSession.frameIndex.value ?: return
        val draft = AppSession.encoderDraft.value ?: return
        if (proposal.sessionKey != draft.sessionKey || proposal.videoUri != uri.toString() ||
            proposal.generation != plateDetectionGeneration || proposal.frameIndex != display.renderedIndex ||
            proposal.ptsUs != display.renderedPtsUs || !display.canMark ||
            proposal.frameIndex !in 0 until index.frameCount || proposal.ptsUs != index.timeOf(proposal.frameIndex)
        ) {
            invalidatePlateProposal()
            return
        }
        _plateDetectionState.update { it.copy(confirming = true) }
        val generation = proposal.generation
        val requestIdentity = proposal.requestIdentity
        plateDetectionJob?.cancel()
        plateDetectionJob = viewModelScope.launch {
            var source: VideoTrackingFrameSource? = null
            try {
                val activeSource = VideoTrackingFrameSource(context, uri, index)
                source = activeSource
                val decoded = withContext(Dispatchers.IO) { activeSource.frameAt(proposal.frameIndex) }
                if (!isCurrentProposal(proposal, generation, decoded.frameIndex, requestIdentity)) return@launch
                if (proposal.adjusted || proposal.requiresAdjustment) {
                    AppSession.applyManualPlateCircle(
                        center = proposal.center,
                        diameterPx = proposal.diameterPx,
                        angleRadians = proposal.angleRadians,
                        visibleFrameIndex = proposal.frameIndex,
                    )
                } else {
                    val detection = proposal.originalDetection ?: return@launch
                    AppSession.applyPlateDetection(detection, proposal.frameIndex)
                }
                val committedDetection = if (!proposal.adjusted) proposal.originalDetection else null
                _plateDetectionState.value = stateAfterCommit(proposal, committedDetection)
                initializeTrackingTarget(proposal.center, decoded)
            } catch (_: CancellationException) {
                // A newer proposal owns the state.
            } catch (_: Exception) {
                if (isCurrentProposal(proposal, generation, proposal.frameIndex, requestIdentity)) {
                    _plateDetectionState.update {
                        it.copy(
                            phase = PlateDetectionPhase.REVIEW,
                            confirming = false,
                            message = context.getString(R.string.selector_plate_confirm_error),
                        )
                    }
                }
            } finally {
                source?.close()
                if (generation == plateDetectionGeneration) plateDetectionJob = null
            }
        }
    }

    private fun stateAfterCommit(proposal: PlateCalibrationProposal, detection: PlateDetection?): PlateDetectionUiState {
        val draft = AppSession.encoderDraft.value
        return PlateDetectionUiState(
            phase = PlateDetectionPhase.DETECTED,
            detection = detection,
            proposal = proposal,
            message = context.getString(R.string.selector_plate_confirmed),
            debug = PlateDetectionDebug(
                detectionMode = detection?.mode ?: PlateDetectionMode.MANUAL,
                plateDetected = detection != null,
                tapX = proposal.center.x,
                tapY = proposal.center.y,
                centerX = proposal.center.x,
                centerY = proposal.center.y,
                diameterPx = proposal.diameterPx,
                diameterCm = draft?.sessionPlateDiameterCm ?: 45.0,
                confidence = detection?.confidence,
                perspectiveRatio = detection?.perspectiveRatio,
                cmPerPixel = draft?.calibration?.metersPerPixel?.times(100.0),
                autoCalibrationAccepted = detection != null && !proposal.adjusted,
            ),
        )
    }

    private fun isCurrentProposal(
        proposal: PlateCalibrationProposal,
        generation: Long,
        frameIndex: Int,
        requestIdentity: PlateDetectionRequestIdentity?,
    ): Boolean {
        val display = _displayState.value
        val currentIdentity = currentPlateRequestIdentity(frameIndex)
        return generation == plateDetectionGeneration && proposal.generation == generation &&
            proposal.frameIndex == frameIndex && proposal.frameIndex == display.renderedIndex &&
            proposal.ptsUs == display.renderedPtsUs && display.canMark &&
            requestIdentity != null && requestIdentity == currentIdentity &&
            proposal.requestIdentity == requestIdentity
    }

    private fun invalidatePlateWork(resetPendingState: Boolean) {
        plateDetectionGeneration++
        plateDetectionJob?.cancel()
        plateDetectionJob = null
        if (resetPendingState && _plateDetectionState.value.phase in setOf(
                PlateDetectionPhase.DETECTING_AUTO,
                PlateDetectionPhase.ASSISTED_REQUIRED,
                PlateDetectionPhase.DETECTING_ASSISTED,
                PlateDetectionPhase.REVIEW,
                PlateDetectionPhase.ASSISTED_FAILED,
            )
        ) {
            _plateDetectionState.value = PlateDetectionUiState(
                phase = PlateDetectionPhase.IDLE,
                message = context.getString(R.string.selector_plate_review_stale),
            )
        }
    }

    private fun currentPlateRequestIdentity(frameIndex: Int): PlateDetectionRequestIdentity? {
        val draft = AppSession.encoderDraft.value ?: return null
        val uri = AppSession.videoUri.value ?: return null
        val index = AppSession.frameIndex.value ?: return null
        if (frameIndex !in 0 until index.frameCount || draft.timingDecision == null) return null
        return PlateDetectionRequestIdentity(
            sessionKey = draft.sessionKey,
            videoUri = uri.toString(),
            videoUriObject = uri,
            frameIndex = frameIndex,
            ptsUs = index.timeOf(frameIndex),
            timingDecision = draft.timingDecision,
            sessionPlateDiameterCm = draft.sessionPlateDiameterCm,
            sessionGeneration = plateSessionGeneration,
            displayRequestGeneration = plateDisplayRequestGeneration,
        )
    }

    private fun isCurrentRequest(identity: PlateDetectionRequestIdentity): Boolean =
        plateDetectionRequestIsCurrent(identity, currentPlateRequestIdentity(identity.frameIndex)) &&
            _displayState.value.renderedIndex == identity.frameIndex &&
            _displayState.value.renderedPtsUs == identity.ptsUs && _displayState.value.canMark

    private fun invalidatePlateProposal() = invalidatePlateWork(resetPendingState = true)

    fun setSessionPlateDiameterCm(value: Double) {
        if (!com.openjump.app.settings.EncoderSettings.isValidPlateDiameterCm(value)) return
        val before = _plateDetectionState.value
        if (before.confirming || _trackingState.value.isBusy) return
        val phaseBefore = before.phase
        val reviewing = phaseBefore == PlateDetectionPhase.REVIEW && before.proposal?.let {
            it.requestIdentity != null && isCurrentRequest(it.requestIdentity)
        } == true
        if (!reviewing) {
            plateDisplayRequestGeneration++
            invalidatePlateWork(resetPendingState = true)
        }
        AppSession.setSessionPlateDiameterCm(value)
        if (phaseBefore == PlateDetectionPhase.DETECTED ||
            (phaseBefore == PlateDetectionPhase.MANUAL && AppSession.encoderDraft.value?.calibration != null)
        ) {
            resetTracking()
        }
        val draft = AppSession.encoderDraft.value ?: return
        _plateDetectionState.update { state ->
            state.copy(
                proposal = if (reviewing) state.proposal?.withReferenceDiameter(value) else state.proposal,
                debug = state.debug?.copy(
                    diameterCm = draft.sessionPlateDiameterCm,
                    cmPerPixel = draft.calibration?.metersPerPixel?.times(100.0),
                    autoCalibrationAccepted = draft.automaticPlateCalibration,
                ),
            )
        }
    }

    fun confirmManualEncoderCalibration(referenceLengthM: Double) {
        AppSession.confirmCalibration(referenceLengthM)
        val draft = AppSession.encoderDraft.value ?: return
        _plateDetectionState.update { state ->
            state.copy(
                debug = state.debug?.copy(
                    cmPerPixel = draft.calibration?.metersPerPixel?.times(100.0),
                    autoCalibrationAccepted = false,
                ),
            )
        }
    }

    fun requestManualPlateCalibration() {
        if (_plateDetectionState.value.phase !in setOf(PlateDetectionPhase.DETECTED, PlateDetectionPhase.REVIEW)) return
        plateDetectionGeneration++
        plateDetectionJob?.cancel()
        AppSession.clearDetectedPlateCalibration()
        _plateDetectionState.update {
            it.copy(
                phase = PlateDetectionPhase.MANUAL,
                proposal = null,
                message = context.getString(R.string.selector_manual_mark_ab),
                manualCalibrationRequested = true,
                debug = it.debug?.copy(cmPerPixel = null, autoCalibrationAccepted = false),
            )
        }
    }

    fun useManualEncoderSelection() {
        plateDetectionGeneration++
        plateDetectionJob?.cancel()
        plateDetectionJob = null
        resetTracking()
        AppSession.useManualEncoderSelection()
        val diameterCm = AppSession.encoderDraft.value?.sessionPlateDiameterCm ?: 45.0
        _plateDetectionState.value = PlateDetectionUiState(
            phase = PlateDetectionPhase.MANUAL,
            message = context.getString(R.string.selector_manual_existing),
            debug = PlateDetectionDebug(
                detectionMode = PlateDetectionMode.MANUAL,
                plateDetected = false,
                diameterCm = diameterCm,
                fallbackReason = "manual_user_selected",
            ),
        )
    }

    fun resetPlateDetectionAutomation() {
        plateDetectionGeneration++
        plateDetectionJob?.cancel()
        plateDetectionJob = null
        resetTracking()
        _plateDetectionState.value = PlateDetectionUiState()
    }

    private fun runPlateDetection(mode: PlateDetectionMode, approximateTap: ImagePoint?) {
        val display = _displayState.value
        val renderedIndex = display.renderedIndex ?: return
        if (!display.canMark || _trackingState.value.isBusy || _exportState.value.blocksViewer) return
        if (AppSession.encoderDraft.value?.timingDecision == null) return
        val uri = AppSession.videoUri.value ?: return
        val index = AppSession.frameIndex.value ?: return
        val generation = ++plateDetectionGeneration
        val requestIdentity = currentPlateRequestIdentity(renderedIndex) ?: return
        plateDetectionJob?.cancel()
        val provisional = approximateTap?.let {
            provisionalProposal(
                center = it,
                generation = generation,
                frameIndex = renderedIndex,
                ptsUs = index.timeOf(renderedIndex),
                width = if (index.rotationDegrees == 90 || index.rotationDegrees == 270) index.height else index.width,
                height = if (index.rotationDegrees == 90 || index.rotationDegrees == 270) index.width else index.height,
                requestIdentity = requestIdentity,
            )
        }
        _plateDetectionState.value = PlateDetectionUiState(
            phase = if (mode == PlateDetectionMode.AUTO) PlateDetectionPhase.DETECTING_AUTO else PlateDetectionPhase.DETECTING_ASSISTED,
            proposal = provisional,
            message = context.getString(
                if (mode == PlateDetectionMode.AUTO) {
                    R.string.selector_searching_plate
                } else {
                    R.string.selector_refining_plate
                },
            ),
        )
        plateDetectionJob = viewModelScope.launch {
            val source = VideoTrackingFrameSource(context, uri, index)
            val startedNs = System.nanoTime()
            try {
                val decoded = source.frameAt(renderedIndex)
                val outcome = withContext(Dispatchers.Default) {
                    when (mode) {
                        PlateDetectionMode.AUTO -> plateDetector.detectAutomatic(decoded.image)
                        PlateDetectionMode.ASSISTED -> plateDetector.detectAssisted(
                            decoded.image,
                            decoded.toTracking(requireNotNull(approximateTap)),
                        )
                        PlateDetectionMode.MANUAL -> error("Manual mode does not invoke detection.")
                    }
                }
                if (generation != plateDetectionGeneration || !isCurrentRequest(requestIdentity)) return@launch
                handlePlateDetectionOutcome(
                    outcome = outcome,
                    mode = mode,
                    approximateTap = approximateTap,
                    decoded = decoded,
                    frameIndex = renderedIndex,
                    elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0,
                    requestIdentity = requestIdentity,
                )
            } catch (_: CancellationException) {
                // A newer attempt owns the state.
            } catch (error: Exception) {
                if (generation == plateDetectionGeneration) {
                    publishPlateFallback(
                        mode = mode,
                        approximateTap = approximateTap,
                        reason = error.message ?: context.getString(R.string.selector_geometry_error),
                        assistedFailure = mode == PlateDetectionMode.ASSISTED,
                        requestIdentity = requestIdentity,
                        frameIndex = renderedIndex,
                        presentationWidth = if (index.rotationDegrees == 90 || index.rotationDegrees == 270) index.height else index.width,
                        presentationHeight = if (index.rotationDegrees == 90 || index.rotationDegrees == 270) index.width else index.height,
                    )
                }
            } finally {
                source.close()
                if (generation == plateDetectionGeneration) plateDetectionJob = null
            }
        }
    }

    private fun handlePlateDetectionOutcome(
        outcome: PlateDetectionOutcome,
        mode: PlateDetectionMode,
        approximateTap: ImagePoint?,
        decoded: DecodedTrackingFrame,
        frameIndex: Int,
        elapsedMs: Double,
        requestIdentity: PlateDetectionRequestIdentity,
    ) {
        val currentDisplay = _displayState.value
        if (!isCurrentRequest(requestIdentity) || !currentDisplay.canMark || currentDisplay.renderedIndex != frameIndex) {
            _plateDetectionState.value = PlateDetectionUiState()
            return
        }
        if (debugLoggingEnabled) {
            Log.d(
                PLATE_DETECTION_TAG,
                "mode=$mode totalMs=${String.format(Locale.US, "%.1f", elapsedMs)} " +
                    "contourScanMs=${String.format(Locale.US, "%.1f", outcome.diagnostics.contourScanMs)} " +
                    "geometricRefinementMs=${String.format(Locale.US, "%.1f", outcome.diagnostics.geometricRefinementMs)}",
            )
        }
        when (outcome) {
            is PlateDetectionOutcome.Found -> {
                val detection = outcome.detection.toPresentation(
                    decoded,
                    plateDetectorConfig.minimumPerspectiveRatioForCalibration,
                )
                val draft = checkNotNull(AppSession.encoderDraft.value)
                val proposal = PlateCalibrationProposal(
                    sessionKey = draft.sessionKey,
                    videoUri = AppSession.videoUri.value.toString(),
                    generation = plateDetectionGeneration,
                    frameIndex = frameIndex,
                    ptsUs = AppSession.frameIndex.value?.timeOf(frameIndex) ?: 0L,
                    presentationWidth = decoded.presentationWidth,
                    presentationHeight = decoded.presentationHeight,
                    originalDetection = detection,
                    center = detection.center,
                    diameterPx = detection.diameterPx,
                    angleRadians = detection.ellipseAngleRadians,
                    provenance = detection.source.name,
                    adjusted = false,
                    requiresAdjustment = !detection.autoCalibrationAccepted,
                    requestIdentity = requestIdentity,
                )
                val debug = PlateDetectionDebug(
                    detectionMode = mode,
                    plateDetected = true,
                    tapX = approximateTap?.x,
                    tapY = approximateTap?.y,
                    centerX = detection.center.x,
                    centerY = detection.center.y,
                    diameterPx = detection.diameterPx,
                    diameterCm = draft.sessionPlateDiameterCm,
                    confidence = detection.confidence,
                    perspectiveRatio = detection.perspectiveRatio,
                    cmPerPixel = draft.calibration?.metersPerPixel?.times(100.0),
                    autoCalibrationAccepted = detection.autoCalibrationAccepted,
                )
                _plateDetectionState.value = PlateDetectionUiState(
                    phase = PlateDetectionPhase.REVIEW,
                    detection = detection,
                    proposal = proposal,
                    message = context.getString(
                        if (detection.autoCalibrationAccepted) {
                            R.string.selector_plate_review_ready
                        } else {
                            R.string.selector_plate_manual_needed
                        },
                    ),
                    debug = debug,
                )
                if (debugLoggingEnabled) {
                    Log.d(
                        PLATE_DETECTION_TAG,
                        "mode=$mode elapsedMs=${String.format(Locale.US, "%.1f", elapsedMs)} " +
                            "candidates=${outcome.candidateCount} center=${detection.center} " +
                            "diameterPx=${String.format(Locale.US, "%.1f", detection.diameterPx)} " +
                            "confidence=${String.format(Locale.US, "%.3f", detection.confidence)} " +
                            "perspectiveRatio=${String.format(Locale.US, "%.3f", detection.perspectiveRatio)} " +
                            "autoCalibrationAccepted=${detection.autoCalibrationAccepted}",
                    )
                }
                // Detection is review-only. Calibration and tracking start only after confirmation.
            }

            is PlateDetectionOutcome.Ambiguous -> publishPlateFallback(
                mode = mode,
                approximateTap = approximateTap,
                reason = "Hay varios candidatos similares (${outcome.candidateCount}).",
                assistedFailure = mode == PlateDetectionMode.ASSISTED,
                requestIdentity = requestIdentity,
                decoded = decoded,
                frameIndex = frameIndex,
            )

            is PlateDetectionOutcome.NotFound -> publishPlateFallback(
                mode = mode,
                approximateTap = approximateTap,
                reason = outcome.reason,
                assistedFailure = mode == PlateDetectionMode.ASSISTED,
                requestIdentity = requestIdentity,
                decoded = decoded,
                frameIndex = frameIndex,
            )
        }
    }

    private fun publishPlateFallback(
        mode: PlateDetectionMode,
        approximateTap: ImagePoint?,
        reason: String,
        assistedFailure: Boolean,
        requestIdentity: PlateDetectionRequestIdentity,
        decoded: DecodedTrackingFrame? = null,
        frameIndex: Int? = null,
        presentationWidth: Int? = null,
        presentationHeight: Int? = null,
    ) {
        val diameterCm = AppSession.encoderDraft.value?.sessionPlateDiameterCm ?: 45.0
        val proposal = approximateTap?.let {
            provisionalProposal(
                center = it,
                generation = plateDetectionGeneration,
                frameIndex = frameIndex ?: AppSession.currentFrameIndex.value,
                ptsUs = AppSession.frameIndex.value?.timeOf(frameIndex ?: AppSession.currentFrameIndex.value) ?: 0L,
                width = decoded?.presentationWidth ?: presentationWidth ?: 1,
                height = decoded?.presentationHeight ?: presentationHeight ?: 1,
                requestIdentity = requestIdentity,
            )
        }
        _plateDetectionState.value = PlateDetectionUiState(
            phase = if (assistedFailure) PlateDetectionPhase.ASSISTED_FAILED else PlateDetectionPhase.ASSISTED_REQUIRED,
            proposal = proposal,
            message = context.getString(
                if (assistedFailure) {
                    R.string.selector_plate_failed
                } else {
                    R.string.selector_plate_tap_needed
                },
            ),
            debug = PlateDetectionDebug(
                detectionMode = mode,
                plateDetected = false,
                tapX = approximateTap?.x,
                tapY = approximateTap?.y,
                diameterCm = diameterCm,
                fallbackReason = reason,
            ),
        )
        if (debugLoggingEnabled) Log.d(PLATE_DETECTION_TAG, "mode=$mode plateDetected=false")
    }

    private fun provisionalProposal(
        center: ImagePoint,
        generation: Long,
        frameIndex: Int,
        ptsUs: Long,
        width: Int,
        height: Int,
        requestIdentity: PlateDetectionRequestIdentity? = null,
    ): PlateCalibrationProposal {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val (safeCenter, diameter) = boundedPlateProposalGeometry(
            center, minOf(safeWidth, safeHeight) * 0.18, safeWidth, safeHeight,
        ) ?: (ImagePoint(safeWidth / 2.0, safeHeight / 2.0) to 8.0)
        val draft = AppSession.encoderDraft.value
        return PlateCalibrationProposal(
            sessionKey = draft?.sessionKey.orEmpty(),
            videoUri = AppSession.videoUri.value.toString(),
            generation = generation,
            frameIndex = frameIndex,
            ptsUs = ptsUs,
            presentationWidth = safeWidth,
            presentationHeight = safeHeight,
            originalDetection = null,
            center = safeCenter,
            diameterPx = diameter,
            angleRadians = 0.0,
            provenance = "ASSISTED_TAP",
            adjusted = false,
            requiresAdjustment = true,
            requestIdentity = requestIdentity,
        )
    }

    /**
     * Initializes from the exact frame confirmed on the SurfaceView. [point] is expressed in
     * full-resolution presentation coordinates, never in Compose or reduced tracking pixels.
     */
    fun selectTrackingTarget(point: ImagePoint) {
        initializeTrackingTarget(point, null)
    }

    private fun initializeTrackingTarget(point: ImagePoint, decodedSeedFrame: DecodedTrackingFrame?) {
        val display = _displayState.value
        val renderedIndex = display.renderedIndex ?: return
        if (!display.canMark || _trackingState.value.isBusy || _exportState.value.blocksViewer) return
        val uri = AppSession.videoUri.value ?: return
        val index = AppSession.frameIndex.value ?: return

        disposeOfflineTracker()
        val generation = ++trackingGeneration
        trackingSessionKey = AppSession.encoderDraft.value?.sessionKey
        trackingVideoUri = AppSession.videoUri.value?.toString()
        _trackingState.value = TrackingUiState(phase = TrackingPhase.INITIALIZING)
        trackingJob = viewModelScope.launch {
            try {
                val performanceProfiler = TrackingPerformanceProfiler()
                val runner = OfflineVideoTracker(
                    source = VideoTrackingFrameSource(
                        context = context,
                        uri = uri,
                        index = index,
                        performanceProfiler = performanceProfiler,
                        previewObserver = { frame, pts, borrowed, rotation ->
                            val shouldCopy = (frame - renderedIndex) % PROGRESS_UPDATE_INTERVAL == 0 || frame == index.frameCount - 1
                            if (shouldCopy && trackingPreviewVisible && generation == trackingGeneration &&
                                _trackingState.value.phase == TrackingPhase.PROCESSING
                            ) {
                                val owned = copyTrackingPreview(borrowed, rotation)
                                if (trackingPreviewVisible && generation == trackingGeneration &&
                                    _trackingState.value.phase == TrackingPhase.PROCESSING
                                ) {
                                    pendingTrackingPreview = PendingTrackingPreview(generation, frame, pts, owned)
                                } else owned.recycle() // Never published/drawn.
                            }
                        },
                    ),
                    tracker = PyramidalKltMotionTracker(performanceProfiler = performanceProfiler),
                    performanceProfiler = performanceProfiler,
                )
                offlineTracker = runner
                val initial = withContext(Dispatchers.Default) {
                    if (decodedSeedFrame != null) {
                        check(decodedSeedFrame.frameIndex == renderedIndex)
                        runner.initialize(decodedSeedFrame, point)
                    } else {
                        runner.initialize(renderedIndex, point)
                    }
                }
                if (generation != trackingGeneration) return@launch
                val lost = initial.result.sample.status == TrackingStatus.LOST
                if (lost) {
                    // A seed without any valid point is recoverable, not a completed/lost
                    // trajectory: keep step 3 and its review actions unavailable.
                    val results = runner.results
                    runner.close()
                    if (offlineTracker === runner) offlineTracker = null
                    trackingJob = null
                    _trackingState.value = TrackingUiState(
                        phase = TrackingPhase.ERROR,
                        results = results,
                        message = context.getString(R.string.selector_tracking_no_valid_point),
                    )
                } else {
                    _trackingState.value = TrackingUiState(
                        phase = TrackingPhase.READY,
                        results = runner.results,
                        message = context.getString(R.string.selector_tracking_initialized),
                    )
                }
            } catch (_: CancellationException) {
                // A new selection/reset owns the next state.
            } catch (error: Exception) {
                if (generation == trackingGeneration) {
                    offlineTracker?.close()
                    offlineTracker = null
                    trackingJob = null
                    _trackingState.value = TrackingUiState(
                        phase = TrackingPhase.ERROR,
                        message = context.getString(R.string.selector_tracking_init_error),
                    )
                }
            }
        }
    }

    fun startTracking() {
        val runner = offlineTracker ?: return
        if (!canUseConfirmedPlateCalibration(_plateDetectionState.value.phase, AppSession.encoderDraft.value)) return
        if (!_trackingState.value.canStart) return
        val generation = trackingGeneration
        trackingJob = viewModelScope.launch {
            pendingTrackingPreview = null
            pausePlayback()
            _trackingState.update { it.copy(phase = TrackingPhase.PROCESSING, progress = 0f, message = null, processingPreview = null) }
            try {
                val finalResults = withContext(Dispatchers.Default) {
                    runner.trackRemaining { result, processed, total ->
                        val shouldPublish = processed == total || processed % PROGRESS_UPDATE_INTERVAL == 0 ||
                            result.result.sample.status == TrackingStatus.LOST
                        if (shouldPublish && generation == trackingGeneration) {
                            val results = runner.results
                            val pending = pendingTrackingPreview?.takeIf {
                                it.generation == generation && it.frameIndex == result.frameIndex && it.ptsUs == result.result.sample.timestampUs
                            }
                            _trackingState.update { state ->
                                if (generation != trackingGeneration || state.phase != TrackingPhase.PROCESSING) state else state.copy(
                                    results = results,
                                    progress = if (total == 0) 1f else processed.toFloat() / total,
                                    processingPreview = if (!trackingPreviewVisible) null else pending?.let {
                                        TrackingProcessingPreview(it.bitmap, result)
                                    } ?: state.processingPreview,
                                )
                            }
                        }
                    }
                }
                if (generation != trackingGeneration) return@launch
                pendingTrackingPreview = null
                if (debugLoggingEnabled) Log.d(TRACKING_PERFORMANCE_TAG, runner.performanceSummary())
                runner.close()
                if (offlineTracker === runner) offlineTracker = null
                trackingJob = null
                val terminalPhase = if (finalResults.lastOrNull()?.result?.sample?.status == TrackingStatus.LOST) {
                    TrackingPhase.LOST
                } else {
                    TrackingPhase.COMPLETED
                }
                val message = if (terminalPhase == TrackingPhase.LOST) {
                    context.getString(
                        R.string.selector_tracking_lost,
                        finalResults.last().frameIndex + 1,
                    )
                } else {
                    context.getString(R.string.selector_trajectory_complete, finalResults.size)
                }
                val currentSessionKey = AppSession.encoderDraft.value?.sessionKey
                val currentVideoUri = AppSession.videoUri.value?.toString()
                val committed = trackingCompletionCoordinator.complete(
                    sessionKey = trackingSessionKey ?: currentSessionKey.orEmpty(),
                    expectedGeneration = generation,
                    currentGeneration = trackingGeneration,
                    phase = terminalPhase,
                    results = finalResults,
                    calibrationConfirmed = canUseConfirmedPlateCalibration(
                        _plateDetectionState.value.phase,
                        AppSession.encoderDraft.value,
                    ) && currentSessionKey == trackingSessionKey && currentVideoUri == trackingVideoUri,
                    commit = AppSession::setEncoderTracking,
                    publish = { phase ->
                        _trackingState.value = TrackingUiState(
                            phase = phase,
                            results = finalResults,
                            progress = 1f,
                            message = message,
                        )
                    },
                    currentSessionKey = currentSessionKey.orEmpty(),
                )
                if (!committed && generation == trackingGeneration) {
                    _trackingState.value = if (finalResults.none { it.result.sample.point != null }) {
                        TrackingUiState(
                            phase = TrackingPhase.ERROR,
                            results = finalResults,
                            message = context.getString(R.string.selector_tracking_no_valid_point),
                        )
                    } else {
                        TrackingUiState()
                    }
                }
            } catch (_: CancellationException) {
                // Reset/re-selection owns the next state.
            } catch (error: Exception) {
                if (generation == trackingGeneration) {
                    offlineTracker?.close()
                    offlineTracker = null
                    trackingJob = null
                    pendingTrackingPreview = null
                    _trackingState.value = _trackingState.value.copy(
                        phase = TrackingPhase.ERROR,
                        processingPreview = null,
                        message = context.getString(R.string.selector_tracking_process_error),
                    )
                }
            }
        }
    }

    fun markCalibrationPoint(point: ImagePoint): Boolean {
        val display = _displayState.value
        val renderedIndex = display.renderedIndex ?: return false
        if (!display.canMark || _trackingState.value.isBusy || _exportState.value.blocksViewer) return false
        AppSession.markCalibrationPoint(point, renderedIndex)
        return true
    }

    fun markHorizontalCalibrationPoint(point: ImagePoint): Boolean {
        val display = _displayState.value
        val renderedIndex = display.renderedIndex ?: return false
        if (!display.canMark || _trackingState.value.isBusy || _exportState.value.blocksViewer) return false
        AppSession.markHorizontalCalibrationPoint(point, renderedIndex)
        return true
    }

    fun markHorizontalPoint(point: ImagePoint): Boolean {
        val display = _displayState.value
        val renderedIndex = display.renderedIndex ?: return false
        if (!display.canMark || _trackingState.value.isBusy || _exportState.value.blocksViewer) return false
        AppSession.markHorizontalPoint(point, renderedIndex)
        return true
    }

    fun jumpToLanding() {
        jumpTo(EventType.LANDING)
    }

    fun exportTrajectoryVideo() {
        val tracking = _trackingState.value
        if (tracking.phase !in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST) || tracking.results.isEmpty()) return
        if (!_exportState.value.canExport || trajectoryExporter.isExporting) return
        val uri = AppSession.videoUri.value ?: return
        val index = AppSession.frameIndex.value ?: return

        _exportState.value.file?.delete()
        suspendViewerForExport()
        _exportState.value = TrajectoryExportUiState(
            phase = TrajectoryExportPhase.EXPORTING,
            progressPercent = 0,
            message = context.getString(R.string.selector_preparing_encoder),
        )
        try {
            trajectoryExporter.start(
                sourceUri = uri,
                sourceIndex = index,
                trackingResults = tracking.results,
                listener = object : TrajectoryVideoExporter.Listener {
                    override fun onProgress(percent: Int?) {
                        _exportState.update {
                            it.copy(
                                progressPercent = percent,
                                message = percent?.let { value ->
                                    context.getString(R.string.selector_encoding_progress, value)
                                } ?: context.getString(R.string.selector_encoding),
                            )
                        }
                    }

                    override fun onCompleted(file: File, result: androidx.media3.transformer.ExportResult) {
                        validateExport(file, index)
                    }

                    override fun onCancelled() {
                        _exportState.value = TrajectoryExportUiState(
                            message = context.getString(R.string.selector_export_cancelled),
                        )
                        resumeViewerAfterExport()
                    }

                    override fun onError(message: String, cause: Throwable?) {
                        _exportState.value = TrajectoryExportUiState(
                            phase = TrajectoryExportPhase.ERROR,
                            message = context.getString(R.string.selector_export_start_error),
                        )
                        resumeViewerAfterExport()
                    }
                },
            )
        } catch (error: Exception) {
            _exportState.value = TrajectoryExportUiState(
                phase = TrajectoryExportPhase.ERROR,
                message = context.getString(R.string.selector_export_start_error),
            )
            resumeViewerAfterExport()
        }
    }

    private fun validateExport(file: File, sourceIndex: VideoFrameIndex) {
        _exportState.value = TrajectoryExportUiState(
            phase = TrajectoryExportPhase.VALIDATING,
            progressPercent = 100,
            message = context.getString(R.string.selector_export_validating),
        )
        exportValidationJob = viewModelScope.launch {
            try {
                val validation = withContext(Dispatchers.IO) {
                    val indexingContext = coroutineContext
                    val outputIndex = VideoFrameIndexer.indexFromPath(file.absolutePath) {
                        indexingContext.ensureActive()
                    }
                    VideoExportValidator.validate(sourceIndex, outputIndex)
                }
                val sizeMb = file.length().toDouble() / (1024.0 * 1024.0)
                _exportState.value = TrajectoryExportUiState(
                    phase = TrajectoryExportPhase.READY,
                    file = file,
                    message = context.getString(
                        R.string.selector_export_verified,
                        validation.presentationGeometry.width,
                        validation.presentationGeometry.height,
                        validation.detectedFps,
                        sizeMb,
                    ),
                )
            } catch (_: CancellationException) {
                file.delete()
            } catch (error: Exception) {
                file.delete()
                _exportState.value = TrajectoryExportUiState(
                    phase = TrajectoryExportPhase.ERROR,
                    message = context.getString(R.string.selector_export_invalid_copy),
                )
            } finally {
                exportValidationJob = null
                resumeViewerAfterExport()
            }
        }
    }

    fun cancelTrajectoryExport() {
        exportValidationJob?.cancel()
        exportValidationJob = null
        if (trajectoryExporter.isExporting) {
            trajectoryExporter.cancel()
            return
        }
        _exportState.value.file?.delete()
        _exportState.value = TrajectoryExportUiState(
            message = context.getString(R.string.selector_export_cancelled),
        )
        resumeViewerAfterExport()
    }

    fun saveTrajectoryExport(destination: Uri) {
        val file = _exportState.value.file?.takeIf(File::isFile) ?: return
        if (_exportState.value.phase !in setOf(TrajectoryExportPhase.READY, TrajectoryExportPhase.SAVED)) return
        _exportState.update {
            it.copy(
                phase = TrajectoryExportPhase.SAVING,
                message = context.getString(R.string.selector_saving_mp4),
            )
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output) { context.getString(R.string.selector_destination_error) }
                        file.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                }
                _exportState.update {
                    it.copy(
                        phase = TrajectoryExportPhase.SAVED,
                        savedUri = destination,
                        message = context.getString(R.string.selector_video_saved),
                    )
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(destination, null, null) }
                }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { context.contentResolver.delete(destination, null, null) }
                }
                _exportState.update {
                    it.copy(
                        phase = TrajectoryExportPhase.READY,
                        message = context.getString(R.string.selector_save_error),
                    )
                }
            }
        }
    }

    fun createTrajectoryShareIntent(): Intent? {
        val file = _exportState.value.file?.takeIf(File::isFile) ?: return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(
                context.getString(R.string.selector_openjump_video),
                uri,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun suspendViewerForResult() {
        if (viewerSuspendedForResult) return
        pausePlayback()
        _player.value?.stop()
        viewerSuspendedForResult = true
    }

    private fun suspendViewerForExport() {
        if (viewerSuspendedForExport) return
        pausePlayback()
        _player.value?.stop()
        viewerSuspendedForExport = true
    }

    private fun resumeViewerAfterExport() {
        if (!viewerSuspendedForExport) return
        viewerSuspendedForExport = false
        val index = AppSession.frameIndex.value ?: return
        val requested = AppSession.currentFrameIndex.value.coerceIn(0, index.frameCount - 1)
        val exoPlayer = _player.value ?: return
        calibrationIndex = requested
        ptsOffsetUs = null
        _displayState.update { it.request(requested, ViewerTransportMode.SEEKING) }
        _loading.value = true
        exoPlayer.seekTo(index.timeOf(requested) / 1_000L)
        exoPlayer.prepare()
    }

    /** The terminal worker already committed the canonical snapshot; this only validates it. */
    fun commitEncoderTracking(): Boolean {
        val state = _trackingState.value
        val canonical = AppSession.encoderDraft.value?.tracking
        return canUseConfirmedPlateCalibration(_plateDetectionState.value.phase, AppSession.encoderDraft.value) &&
            state.results.isNotEmpty() && state.phase in setOf(TrackingPhase.COMPLETED, TrackingPhase.LOST) &&
            canonical == state.results && state.results.any { it.result.sample.point != null }
    }

    fun reselectEncoderTarget() {
        resetTracking()
        AppSession.reselectEncoderTarget()
        _plateDetectionState.value = PlateDetectionUiState(
            phase = PlateDetectionPhase.MANUAL,
            message = context.getString(R.string.selector_manual_existing),
            debug = PlateDetectionDebug(
                detectionMode = PlateDetectionMode.MANUAL,
                plateDetected = false,
                diameterCm = AppSession.encoderDraft.value?.sessionPlateDiameterCm ?: 45.0,
                fallbackReason = "manual_user_selected",
            ),
        )
    }

    fun recalibrateEncoder() {
        resetTracking()
        AppSession.clearEncoderCalibration()
        _plateDetectionState.value = PlateDetectionUiState(
            phase = PlateDetectionPhase.MANUAL,
            message = context.getString(R.string.selector_manual_mark_ab),
        )
    }

    fun resetTracking() {
        cancelTrajectoryExport()
        trackingGeneration++
        pendingTrackingPreview = null
        disposeOfflineTracker()
        AppSession.clearEncoderTracking()
        _trackingState.value = TrackingUiState()
    }

    private fun disposeOfflineTracker() {
        val job = trackingJob
        val runner = offlineTracker
        trackingJob = null
        offlineTracker = null
        if (job?.isActive == true) {
            job.cancel()
            job.invokeOnCompletion { runner?.close() }
        } else {
            runner?.close()
        }
    }

    /** Marca únicamente el frame que ExoPlayer ya confirmó en la superficie. */
    fun mark(type: EventType): Boolean {
        val state = _displayState.value
        val renderedIndex = state.renderedIndex ?: return false
        if (!state.canMark || _exportState.value.blocksViewer) return false
        AppSession.markAt(type, renderedIndex)
        return true
    }

    override fun onCleared() {
        scrubReleaseJob?.cancel()
        plateDetectionJob?.cancel()
        exportValidationJob?.cancel()
        trajectoryExporter.cancel(notifyListener = false)
        _exportState.value.file?.delete()
        trackingPreviewVisible = false
        pendingTrackingPreview = null
        disposeOfflineTracker()
        _trackingState.value = TrackingUiState()
        _player.value?.let { exoPlayer ->
            exoPlayer.clearVideoFrameMetadataListener(frameMetadataListener)
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
        _player.value = null
        val jobsToFinish = viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            jobsToFinish.joinAll()
            videoUriLease?.close()
            app.requestVideoUriReconciliation()
        }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 5
        private const val STEP_SCRUB_RELEASE_MS = 300L
        private const val TRACKING_PERFORMANCE_TAG = "OpenJumpTrackingPerf"
        private const val PLATE_DETECTION_TAG = "OpenJumpPlateDetection"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val applicationContext =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Context).applicationContext
                FrameSelectorViewModel(applicationContext)
            }
        }
    }
}
