package com.openjump.app.ui.selector

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.openjump.app.R
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.encoder.encoderToolButtonColors
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.VideoPreparationOverlay
import com.openjump.app.ui.components.VideoSourceInfoRow
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import com.openjump.app.video.AppSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Encoder-only preparation. No scrolling page can separate the disk from its CTA. */
@OptIn(UnstableApi::class)
@Composable
internal fun EncoderReferenceScreen(
    viewModel: FrameSelectorViewModel,
    draft: EncoderDraft,
    athleteName: String?,
    onBack: () -> Unit,
    preparingFirstRecording: Boolean,
    preparationFeedbackDelayMs: Long,
) {
    val player by viewModel.player.collectAsState()
    val index by AppSession.frameIndex.collectAsState()
    val display by viewModel.displayState.collectAsState()
    val plate by viewModel.plateDetectionState.collectAsState()
    val tracking by viewModel.trackingState.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val liveVideoUri by AppSession.videoUri.collectAsState()
    var chosen by rememberSaveable(draft.sessionKey) { mutableStateOf(plate.phase != PlateDetectionPhase.IDLE) }
    var moments by rememberSaveable(draft.sessionKey) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var renderedView by remember { mutableStateOf<PlayerView?>(null) }
    var precisionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var precisionProposal by remember { mutableStateOf<PlateCalibrationProposal?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var precisionFailure by remember { mutableStateOf(false) }
    var abBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var abAnchor by remember { mutableStateOf<AbEditorAnchor?>(null) }
    var abCaptureJob by remember { mutableStateOf<Job?>(null) }
    var abBusy by remember { mutableStateOf(false) }
    var abFailure by remember { mutableStateOf(false) }
    // AB-local native-buffer generation: bumped on every SurfaceHolder callback
    // (created/changed/destroyed) without altering the existing refresh behavior.
    // Pinned at capture click and re-checked before/after the copy so a relayout
    // that empties the native buffer cannot be published as a valid snapshot.
    val abSurfaceGeneration = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(precisionBitmap) {
        val owned = precisionBitmap
        onDispose { owned?.recycle() }
    }
    DisposableEffect(abBitmap) {
        val owned = abBitmap
        onDispose { owned?.recycle() }
    }
    val keepScreenOnView = LocalView.current
    val trackingActive = tracking.isBusy
    DisposableEffect(keepScreenOnView, trackingActive) {
        keepScreenOnView.keepScreenOn = trackingActive
        onDispose { keepScreenOnView.keepScreenOn = false }
    }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life, viewModel) {
        viewModel.setTrackingPreviewVisible(life.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.setTrackingPreviewVisible(true)
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.setTrackingPreviewVisible(false)
                captureJob?.cancel()
                precisionBitmap = null
                precisionProposal = null
                abCaptureJob?.cancel()
                abBitmap = null
                abAnchor = null
                abBusy = false
                viewModel.pausePlayback()
            }
        }
        life.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setTrackingPreviewVisible(false)
            life.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(chosen, display.canMark, plate.phase, draft.timingDecision) {
        if (chosen && display.canMark && plate.phase == PlateDetectionPhase.IDLE && draft.timingDecision != null) {
            viewModel.requestPlateTap()
        }
    }
    val geometry = index?.let { VideoPresentationGeometry.fromEncoded(it.width, it.height, it.rotationDegrees) }
    // Drift guard: an open A/B editor is anchored to one exact frame. Any seek
    // (including seek-away-and-back, which already closed the dialog here),
    // source/timing/geometry/pair change, calibration confirmation, or
    // lifecycle/canMark loss invalidates instead of publishing stale points;
    // the anchored snapshot is recycled by its DisposableEffect. The anchor is
    // pinned at capture click (before launch), so this same guard also cancels
    // a pending capture whose frame moved mid-copy.
    LaunchedEffect(
        display.renderedIndex, display.renderedPtsUs, display.requestedIndex, display.canMark,
        plate.phase, draft.sessionKey, draft.timingDecision, draft.calibration,
        draft.calibrationPointA, draft.calibrationPointB, draft.source, draft.videoUri,
        liveVideoUri, geometry?.width, geometry?.height,
    ) {
        val anchored = abAnchor
        if (anchored != null) {
            val canonical = try { index?.timeOf(anchored.renderedIndex) } catch (_: Exception) { null }
            val stillValid = isAbAnchorStillValid(
                anchor = anchored,
                sessionKey = draft.sessionKey,
                timing = draft.timingDecision,
                calibrationPresent = draft.calibration != null,
                liveA = draft.calibrationPointA,
                liveB = draft.calibrationPointB,
                videoUri = liveVideoUri?.toString(),
                source = draft.source,
                renderedIndex = display.renderedIndex,
                renderedPtsUs = display.renderedPtsUs,
                requestedIndex = display.requestedIndex,
                canMark = display.canMark,
                phaseIsManual = plate.phase == PlateDetectionPhase.MANUAL,
                videoWidth = geometry?.width?.toDouble(),
                videoHeight = geometry?.height?.toDouble(),
                canonicalPtsUs = canonical,
            )
            if (!stillValid) {
                // An invalid anchor also stops a pending AB capture: without this,
                // a seek-away-and-back could land on matching values again while
                // the native buffer was disturbed mid-copy, laundering the pending
                // capture. The stale job's finally only clears abCaptureJob/abBusy
                // while it is still the current job, so it can never clear a newer
                // capture. Silent, like the existing open-editor drift close.
                abCaptureJob?.cancel()
                abCaptureJob = null
                abBusy = false
                abBitmap = null
                abAnchor = null
            }
        }
    }
    val mapper = remember(geometry, viewport) {
        if (geometry != null && viewport.width > 0 && viewport.height > 0)
            VideoCoordinateMapper(geometry, ViewportSize(viewport.width.toDouble(), viewport.height.toDouble())) else null
    }
    val editable = display.canMark && !plate.isBusy && !tracking.isBusy && draft.timingDecision != null
    val reviewing = plate.phase == PlateDetectionPhase.REVIEW
    val minimumTouchDiameter = with(LocalDensity.current) { 48.dp.toPx() }
    val smallReference = reviewing && plate.proposal?.let { p ->
        mapper?.let { p.diameterPx * it.contentRect.width / (p.presentationWidth - 1.0).coerceAtLeast(1.0) < minimumTouchDiameter }
    } == true
    val manualAB = plate.phase == PlateDetectionPhase.MANUAL && draft.calibration == null
    val selectingTarget = draft.calibration != null && tracking.phase in setOf(
        TrackingPhase.IDLE, TrackingPhase.READY, TrackingPhase.ERROR, TrackingPhase.LOST,
    ) && !reviewing
    val processingPreview = tracking.processingPreview.takeIf { tracking.phase == TrackingPhase.PROCESSING }
    val current = processingPreview?.result ?: visibleTrackingResult(tracking.results, display.renderedIndex, display.renderedPtsUs)
    val instruction = when {
        tracking.isBusy -> R.string.encoder_reference_processing
        tracking.phase == TrackingPhase.READY -> R.string.encoder_reference_ready
        manualAB -> if (draft.calibrationPointA == null) R.string.selector_touch_reference_a else R.string.selector_touch_reference_b
        selectingTarget -> R.string.encoder_reference_target_instruction
        // Initial overlay must not name a plate before a method is chosen.
        !chosen -> R.string.encoder_reference_method_title
        plate.isBusy -> R.string.encoder_reference_searching
        reviewing -> if (plate.proposal?.originalDetection == null && plate.proposal?.adjusted == false)
            R.string.encoder_reference_manual_hint else R.string.encoder_reference_review_hint
        else -> R.string.encoder_reference_tap
    }
    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.encoder_reference_title),
                subtitle = listOfNotNull(stringResource(draft.setup.exercise.titleResource()), athleteName).joinToString(" · "),
                onNavigationClick = { viewModel.pausePlayback(); onBack() },
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth > maxHeight || maxWidth >= 840.dp
            val paneWidth = (maxWidth * 0.42f).coerceIn(192.dp, 400.dp)
            // Stable within a stage: opening fine controls scrolls tools, not the video/frame.
            val detailed = chosen || reviewing || manualAB || moments
            val paneHeight = if (detailed) maxHeight * 0.48f else (maxHeight * 0.42f).coerceAtMost(288.dp)
            val pane: @Composable (Modifier) -> Unit = { modifier ->
                EncoderReferencePane(
                    modifier = modifier,
                    smallReference = smallReference,
                    draft = draft, plate = plate, tracking = tracking,
                    chosen = chosen, editable = editable,
                    canConfirm = plate.canConfirm && editable && mapper?.let { m ->
                        plate.proposal?.let { m.isPlateCircleVisible(it.center, it.diameterPx, it.angleRadians) }
                    } == true,
                    onChoose = { chosen = true; viewModel.requestPlateTap() },
                    onConfirm = viewModel::confirmPlateProposal,
                    onDiameter = viewModel::setSessionPlateDiameterCm,
                    onAuto = viewModel::detectPlateAutomatically,
                    onManual = viewModel::startManualPlateCircle,
                    onRetry = { viewModel.requestPlateTap() },
                    onOtherReference = { chosen = true; viewModel.useManualEncoderSelection() },
                    onGeometry = viewModel::updatePlateProposal,
                    onEnlargeAb = {
                        val sv = surface
                        val pv = renderedView
                        val m = mapper
                        val frame = index
                        val renderedIndex = display.renderedIndex
                        val liveDraft = AppSession.encoderDraft.value
                        // AMPLIAR is offered from the start of the manual route,
                        // even with no points yet; capture reuses the bounded plate
                        // snapshot helper (ownership/recycle identical, no new file).
                        // Entry pins every immutable at click time: session, source,
                        // URI, index, actual rendered PTS (+ canonical), requested
                        // index, timing, pair, geometry, calibration-null, canMark.
                        val clickUri = AppSession.videoUri.value?.toString()
                        val clickRenderedPts = display.renderedPtsUs
                        val clickRequested = display.requestedIndex
                        val clickCanMark = display.canMark
                        val clickTiming = liveDraft?.timingDecision
                        val clickLiveA = liveDraft?.calibrationPointA
                        val clickLiveB = liveDraft?.calibrationPointB
                        val clickSource = liveDraft?.source
                        val clickDraftUri = liveDraft?.videoUri
                        val clickGeoW = geometry?.width?.toDouble()
                        val clickGeoH = geometry?.height?.toDouble()
                        val clickCanonical = if (frame != null && renderedIndex != null) {
                            try { frame.timeOf(renderedIndex) } catch (_: Exception) { null }
                        } else null
                        // Native-copy race pins: surface + viewport dimensions and
                        // window origins, plus the AB-local surface generation. All
                        // are re-verified after the frame waits and after the copy;
                        // any drift recycles and reports the existing AB failure.
                        // No brightness check here: a valid black frame must work.
                        val clickSvW = sv?.width
                        val clickSvH = sv?.height
                        val clickPvW = pv?.width
                        val clickPvH = pv?.height
                        val clickSvOrigin = sv?.let { IntArray(2).also(it::getLocationInWindow) }
                        val clickPvOrigin = pv?.let { IntArray(2).also(it::getLocationInWindow) }
                        val clickGeneration = abSurfaceGeneration.intValue
                        if (abCaptureJob?.isActive != true && abAnchor == null && sv != null && pv != null && m != null &&
                            frame != null && renderedIndex != null && liveDraft != null &&
                            geometry != null && clickTiming != null && clickRenderedPts != null &&
                            clickGeoW != null && clickGeoH != null &&
                            liveDraft.calibration == null &&
                            clickCanMark && clickRequested == renderedIndex &&
                            clickRenderedPts == clickCanonical &&
                            viewModel.plateDetectionState.value.phase == PlateDetectionPhase.MANUAL
                        ) {
                            // Immutable click snapshot: completion must equal both this
                            // and live state, closing the setVideo-during-capture window.
                            val clickSession = liveDraft.sessionKey
                            // Pin the anchor BEFORE launch, not only on completion, so
                            // the drift guard above cancels this pending capture when
                            // any seek/source/timing/pair/geometry change lands first;
                            // completion below requires this same anchor instance.
                            val clickAnchor = AbEditorAnchor(
                                sessionKey = clickSession,
                                videoUri = clickUri,
                                renderedIndex = renderedIndex,
                                renderedPtsUs = clickRenderedPts,
                                videoWidth = clickGeoW,
                                videoHeight = clickGeoH,
                                liveA = clickLiveA,
                                liveB = clickLiveB,
                                requestedIndex = clickRequested,
                                timing = clickTiming,
                                source = clickSource,
                            )
                            abAnchor = clickAnchor
                            abBusy = true
                            abCaptureJob = scope.launch {
                                try {
                                    // Settle the native buffer across TWO Compose frame
                                    // boundaries before PixelCopy: a SurfaceView relayout
                                    // racing the tap leaves an empty buffer that still
                                    // reports SUCCESS (the unproven black-capture race).
                                    // No production brightness check: a valid black frame
                                    // must keep working. Without a frame clock there is no
                                    // safe settle, so fail honestly instead of copying.
                                    val hasFrameClock = coroutineContext[MonotonicFrameClock] != null
                                    if (hasFrameClock) {
                                        withFrameNanos { }
                                        withFrameNanos { }
                                    }
                                    // Pinned native geometry must be unchanged after the
                                    // waits AND after the copy; anything that moved (dims,
                                    // origins, surface generation, or a drifted anchor)
                                    // aborts before any publish. No decoder re-fetch here.
                                    fun surfacesPinned(): Boolean =
                                        hasFrameClock &&
                                        sv.width == clickSvW && sv.height == clickSvH &&
                                        pv.width == clickPvW && pv.height == clickPvH &&
                                        sv.holder.surface.isValid &&
                                        abSurfaceGeneration.intValue == clickGeneration &&
                                        clickSvOrigin != null &&
                                        IntArray(2).also(sv::getLocationInWindow).contentEquals(clickSvOrigin) &&
                                        clickPvOrigin != null &&
                                        IntArray(2).also(pv::getLocationInWindow).contentEquals(clickPvOrigin) &&
                                        abAnchor == clickAnchor
                                    val snapshot = if (surfacesPinned()) capturePlateSnapshot(sv, pv, m) else null
                                    val live = viewModel.plateDetectionState.value
                                    val nowDraft = AppSession.encoderDraft.value
                                    val nowDisplay = viewModel.displayState.value
                                    val nowUri = AppSession.videoUri.value?.toString()
                                    val nowFrame = AppSession.frameIndex.value
                                    val nowGeo = nowFrame?.let {
                                        try {
                                            VideoPresentationGeometry.fromEncoded(it.width, it.height, it.rotationDegrees)
                                        } catch (_: Exception) { null }
                                    }
                                    val nowCanonical = if (nowFrame != null) {
                                        try { nowFrame.timeOf(renderedIndex) } catch (_: Exception) { null }
                                    } else null
                                    if (snapshot != null && surfacesPinned() &&
                                        live.phase == PlateDetectionPhase.MANUAL &&
                                        nowDraft?.sessionKey == clickSession &&
                                        nowDraft?.calibration == null &&
                                        nowDraft?.timingDecision == clickTiming &&
                                        nowDraft?.calibrationPointA == clickLiveA &&
                                        nowDraft?.calibrationPointB == clickLiveB &&
                                        nowDraft?.source == clickSource &&
                                        nowDraft?.videoUri == clickDraftUri &&
                                        nowUri == clickUri &&
                                        nowDisplay.renderedIndex == renderedIndex &&
                                        nowDisplay.renderedPtsUs == clickRenderedPts &&
                                        nowDisplay.requestedIndex == clickRequested &&
                                        nowDisplay.canMark &&
                                        nowGeo?.width?.toDouble() == clickGeoW &&
                                        nowGeo?.height?.toDouble() == clickGeoH &&
                                        nowCanonical == clickRenderedPts &&
                                        life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        // Still the click-time anchor: publish requires
                                        // identity with it, never a drifted replacement.
                                        abAnchor = clickAnchor
                                        abBitmap = snapshot
                                    } else {
                                        snapshot?.recycle()
                                        // The click-pinned anchor is dropped on any drift:
                                        // never publish stale points from a disturbed copy.
                                        abAnchor = null
                                        // Capture ran but the frame moved on, the surface
                                        // relaid out mid-copy, or the surface gave nothing:
                                        // honest failure, never a silent no-op on AB route.
                                        if (life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) abFailure = true
                                    }
                                } finally {
                                    // A stale (drift-cancelled) job must not clear a newer
                                    // capture: only the current job nulls job/busy state.
                                    if (abCaptureJob === coroutineContext[Job]) {
                                        abCaptureJob = null
                                        abBusy = false
                                    }
                                }
                            }
                        } else if (abCaptureJob?.isActive != true &&
                            life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            abFailure = true
                        }
                    },
                    abEnlargeReady = editable && surface != null && renderedView != null && mapper != null &&
                        geometry != null && index != null && display.renderedIndex != null,
                    abBusy = abBusy,
                    onEnlarge = {
                        val sv = surface
                        val pv = renderedView
                        val m = mapper
                        val anchored = viewModel.plateDetectionState.value.proposal
                        if (captureJob?.isActive != true && sv != null && pv != null && m != null && anchored != null) {
                            captureJob = scope.launch {
                                try {
                                    val snapshot = capturePlateSnapshot(sv, pv, m)
                                    val live = viewModel.plateDetectionState.value
                                    if (live.phase == PlateDetectionPhase.REVIEW && live.proposal?.requestIdentity == anchored.requestIdentity &&
                                        live.proposal?.generation == anchored.generation && viewModel.displayState.value.canMark &&
                                        life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        if (snapshot != null) { precisionProposal = live.proposal; precisionBitmap = snapshot }
                                        else precisionFailure = true
                                    } else snapshot?.recycle()
                                } finally { captureJob = null }
                            }
                        }
                    },
                    onStart = viewModel::startTracking,
                    onCancel = viewModel::resetTracking,
                    onMoment = {
                        if (reviewing) viewModel.requestPlateTap()
                        else if (draft.calibration != null) {
                            // Preserve the chosen scale method: MANUAL stays manual,
                            // plate stays plate. Both clear the confirmed scale and
                            // tracking via existing callbacks; no new capability and
                            // no bypass of confirmed-frame/reset guards.
                            if (plate.phase == PlateDetectionPhase.MANUAL) viewModel.useManualEncoderSelection()
                            else viewModel.requestPlateTap()
                        }
                        moments = !moments
                    },
                    showMoments = moments,
                    temporalControls = {
                        val activeIndex = index
                        if (activeIndex != null) {
                            val timeline = remember(activeIndex) { PtsTimelineMapper(activeIndex.frameTimesUs) }
                            SportsTimeline(
                                mapper = timeline, selectedIndex = display.requestedIndex,
                                selectedTimeLabel = Formatting.usToClock(activeIndex.timeOf(display.requestedIndex)),
                                enabled = !plate.isBusy && !tracking.isBusy,
                                onPreviewFrame = {}, onScrubStart = viewModel::beginScrubbing,
                                onScrubToFrame = viewModel::scrubToFrame, onScrubEnd = viewModel::endScrubbing,
                                markers = emptyList(), modifier = Modifier.fillMaxWidth(),
                            )
                            VideoTransportControls(
                                isPlaying = display.isPlaying, enabled = !plate.isBusy && !tracking.isBusy,
                                onPreviousFrame = { viewModel.stepFrames(-1) }, onTogglePlayback = viewModel::togglePlayback,
                                onNextFrame = { viewModel.stepFrames(1) },
                            )
                        }
                    },
                    manualControls = {
                        ManualEncoderCalibration(draft, stringResource(R.string.encoder_reference_other), viewModel::confirmManualEncoderCalibration)
                    },
                )
            }
            val video: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    // Shared source row sits below the viewport, never over the
                    // video; the viewport keeps its size callback and FIT render.
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds().onSizeChanged { viewport = it }.testTag("reference-video")) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setKeepContentOnPlayerReset(true)
                                this.player = player
                                val pv = this
                                renderedView = pv
                                (videoSurfaceView as? SurfaceView)?.let { sv ->
                                    surface = sv
                                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) { abSurfaceGeneration.intValue++; pv.post { viewModel.refreshCurrentFrame() } }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { abSurfaceGeneration.intValue++ }
                                        override fun surfaceDestroyed(holder: SurfaceHolder) { abSurfaceGeneration.intValue++ }
                                    })
                                }
                            }
                        },
                        update = { pv ->
                            if (pv.player !== player) { pv.player = player; pv.post { viewModel.refreshCurrentFrame() } }
                        },
                        onRelease = { pv -> pv.player = null; surface = null; renderedView = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                    mapper?.let { m ->
                        processingPreview?.let { preview ->
                            val image = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
                            Canvas(Modifier.fillMaxSize().testTag("tracking-preview")) {
                                drawImage(image,
                                    dstOffset = IntOffset(m.contentRect.left.roundToInt(), m.contentRect.top.roundToInt()),
                                    dstSize = IntSize(m.contentRect.width.roundToInt(), m.contentRect.height.roundToInt()),
                                )
                            }
                        }
                        if (reviewing) plate.proposal?.let { proposal ->
                            PlateCalibrationOverlay(m, proposal, viewModel::updatePlateProposal, editable, Modifier.fillMaxSize())
                        }
                        if (!reviewing) TrackingOverlay(
                            mapper = m, current = current, recent = emptyList(), trackingPhase = tracking.phase,
                            // Once scale is confirmed the reference oval would compete with the
                            // followed ROI: hide it and keep the ROI primary. Scale geometry
                            // stays in the draft/technical details; no data change.
                            plateDetection = if (draft.calibration != null) null else plate.detection,
                            selectionEnabled = editable && chosen && (plate.acceptsAssistedTap || selectingTarget),
                            onTargetSelected = if (selectingTarget) viewModel::selectTrackingTarget else viewModel::detectPlateNear,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (manualAB) MetricCalibrationOverlay(
                            m, draft.calibrationPointA, draft.calibrationPointB, editable,
                            { viewModel.markCalibrationPoint(it) }, Modifier.fillMaxSize(),
                            encoderEmphasis = true,
                            contentLabel = stringResource(R.string.encoder_reference_ab_overlay_description),
                        )
                    }
                    if (loading && !preparingFirstRecording) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    VideoPreparationOverlay(active = preparingFirstRecording, delayMillis = preparationFeedbackDelayMs)
                    // El aviso se mantiene dentro del área visible del vídeo: el
                    // contentRect del mapper marca dónde empieza/acaba la imagen, así
                    // que no cruza el letterbox negro. Ver F-017.
                    val contentLeftDp = with(LocalDensity.current) {
                        (mapper?.contentRect?.left ?: 0.0).toFloat().toDp()
                    }
                    val contentWidthDp = with(LocalDensity.current) {
                        (mapper?.contentRect?.width ?: 0.0).toFloat().toDp()
                    }
                    val inVideoInset = mapper != null && contentWidthDp > Spacing.xxl
                    Column(
                        Modifier.align(Alignment.BottomStart)
                            .padding(
                                start = if (inVideoInset) contentLeftDp + Spacing.sm else Spacing.sm,
                                end = Spacing.sm,
                                bottom = Spacing.sm,
                            )
                            .then(
                                if (inVideoInset) Modifier.widthIn(max = contentWidthDp - Spacing.md)
                                else Modifier,
                            ),
                    ) {
                        error?.let { Text(it, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(Spacing.sm)) }
                        if (index != null && !loading && chosen && tracking.phase != TrackingPhase.READY) Text(
                            stringResource(instruction),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.8f), ShapeTokens.small)
                                .padding(Spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    }
                    VideoSourceInfoRow(
                        videoUri = liveVideoUri?.toString(),
                        index = index,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxHeight())
                pane(Modifier.width(paneWidth).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxWidth())
                pane(Modifier.fillMaxWidth().height(paneHeight))
            }
        }
    }
    // A/B enlarge dialog: works from empty, A-only and A+B. Cancel (top-bar or
    // dialog dismiss) drops the local copy; only Volver publishes, and only via
    // the existing mark/reset callbacks below. Never confirms scale, never
    // touches the distance input, never writes Room. Reuses the honest
    // precision-unavailable dialog on drift/failure (tiny justified reuse of the
    // plate path's failure copy; the plate editor behavior itself is untouched).
    val abShot = abBitmap
    val abAnchored = abAnchor
    if (abShot != null && abAnchored != null) EncoderAbPrecisionEditor(
        bitmap = abShot,
        anchor = abAnchored,
        initialA = draft.calibrationPointA,
        initialB = draft.calibrationPointB,
        onDismiss = { abBitmap = null; abAnchor = null },
        onReturn = { a, b ->
            val live = viewModel.plateDetectionState.value
            val nowDraft = AppSession.encoderDraft.value
            val nowDisplay = viewModel.displayState.value
            val nowFrame = AppSession.frameIndex.value
            val nowGeo = nowFrame?.let {
                try { VideoPresentationGeometry.fromEncoded(it.width, it.height, it.rotationDegrees) } catch (_: Exception) { null }
            }
            val nowCanonical = if (nowFrame != null) {
                try { nowFrame.timeOf(abAnchored.renderedIndex) } catch (_: Exception) { null }
            } else null
            // Never clear a confirmed scale: calibration must still be null,
            // and every pinned immutable (URI/source/index/actual PTS/timing/
            // pair/geometry) must still match. Drift rejects honestly.
            val stillAnchored = isAbAnchorStillValid(
                anchor = abAnchored,
                sessionKey = nowDraft?.sessionKey,
                timing = nowDraft?.timingDecision,
                calibrationPresent = nowDraft?.calibration != null,
                liveA = nowDraft?.calibrationPointA,
                liveB = nowDraft?.calibrationPointB,
                videoUri = AppSession.videoUri.value?.toString(),
                source = nowDraft?.source,
                renderedIndex = nowDisplay.renderedIndex,
                renderedPtsUs = nowDisplay.renderedPtsUs,
                requestedIndex = nowDisplay.requestedIndex,
                canMark = nowDisplay.canMark,
                phaseIsManual = live.phase == PlateDetectionPhase.MANUAL,
                videoWidth = nowGeo?.width?.toDouble(),
                videoHeight = nowGeo?.height?.toDouble(),
                canonicalPtsUs = nowCanonical,
            )
            abBitmap = null
            abAnchor = null
            if (!stillAnchored || !life.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                abFailure = true
            } else {
                val plan = planAbPublish(nowDraft?.calibrationPointA, nowDraft?.calibrationPointB, a, b)
                if (plan.resetFirst) viewModel.useManualEncoderSelection()
                for (point in plan.marks) {
                    if (!viewModel.markCalibrationPoint(point)) {
                        abFailure = true
                        break
                    }
                }
            }
        },
    )
    val snapshot = precisionBitmap
    val anchored = precisionProposal
    if (snapshot != null && anchored != null) PlateReferencePrecisionEditor(
        snapshot, anchored,
        onDismiss = { precisionBitmap = null; precisionProposal = null },
        onApply = { center, diameter ->
            val live = viewModel.plateDetectionState.value.proposal
            if (live?.requestIdentity == anchored.requestIdentity && live?.generation == anchored.generation) {
                viewModel.updatePlateProposal(center, diameter)
            }
        },
    )
    if (abFailure) AlertDialog(
        onDismissRequest = { abFailure = false },
        title = { Text(stringResource(R.string.encoder_reference_enlarge)) },
        text = { Text(stringResource(R.string.encoder_reference_ab_unavailable)) },
        confirmButton = { TextButton(onClick = { abFailure = false }) { Text(stringResource(R.string.common_back)) } },
    )
    if (precisionFailure) AlertDialog(
        onDismissRequest = { precisionFailure = false },
        title = { Text(stringResource(R.string.encoder_reference_enlarge)) },
        text = { Text(stringResource(R.string.encoder_reference_precision_unavailable)) },
        confirmButton = { TextButton(onClick = { precisionFailure = false }) { Text(stringResource(R.string.common_back)) } },
    )
}

/** Kept stateless so the actual fixed actions can be tested independently of video decoding. */
@kotlin.OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun EncoderReferencePane(
    modifier: Modifier,
    draft: EncoderDraft,
    plate: PlateDetectionUiState,
    tracking: TrackingUiState,
    chosen: Boolean,
    editable: Boolean,
    canConfirm: Boolean,
    onChoose: () -> Unit,
    onConfirm: () -> Unit,
    onDiameter: (Double) -> Unit,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onRetry: () -> Unit,
    onOtherReference: () -> Unit,
    onGeometry: (ImagePoint, Double) -> Unit,
    onEnlarge: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onMoment: () -> Unit,
    showMoments: Boolean,
    temporalControls: @Composable () -> Unit = {},
    manualControls: @Composable () -> Unit = {},
    smallReference: Boolean = false,
    onEnlargeAb: () -> Unit = {},
    abEnlargeReady: Boolean = false,
    abBusy: Boolean = false,
) {
    val review = plate.phase == PlateDetectionPhase.REVIEW
    val manualAB = plate.phase == PlateDetectionPhase.MANUAL && draft.calibration == null
    var fine by remember { mutableStateOf(false) }
    val fineRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(fine) { if (fine) fineRequester.bringIntoView() }
    val step = resolveEncoderProgressStep(draft.calibration != null, tracking.phase, tracking.results.any { it.result.sample.point != null })
    val stepTitle = stringResource(when (step) {
        EncoderProgressStep.CALIBRATE -> R.string.selector_encoder_progress_calibrate
        EncoderProgressStep.TRACK -> R.string.selector_encoder_progress_track
        EncoderProgressStep.REVIEW -> R.string.selector_encoder_progress_review
    })
    val stepDescription = stringResource(R.string.selector_encoder_progress_accessibility, step.number, ENCODER_STEP_COUNT, stepTitle)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md).testTag("reference-pane")) {
        // Only the primary action is pinned. Even the summary may scroll on a compact side pane.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.selector_encoder_progress, step.number, ENCODER_STEP_COUNT, stepTitle),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = stepDescription })
            // Diameter only for the plate method before scale confirmation;
            // hidden once calibrated and never on initial choice or A/B.
            if (chosen && !manualAB && draft.calibration == null) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    PlateDiameterSummary(draft, enabled = !plate.isBusy && !tracking.isBusy && draft.calibration == null,
                        onChange = onDiameter, valueStyle = OpenJumpTypes.MetricValue)
            }
            when {
                tracking.phase == TrackingPhase.INITIALIZING -> {
                    // Preparing the selected target: no percent/ETA yet, plain
                    // actionable title plus an indeterminate bar while the
                    // decoder spins up. No cause invented, no confidence claim.
                    Text(stringResource(R.string.encoder_reference_processing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                tracking.phase == TrackingPhase.PROCESSING -> {
                    // Following the movement: real percent, ETA and cancel stay.
                    // The paired frame/PTS preview renders under the video while
                    // this phase is active (see processingPreview above).
                    if (tracking.progress <= 0f) {
                        Text(stringResource(R.string.encoder_reference_processing))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val percent = (tracking.progress * 100f).roundToInt().coerceIn(0, 100)
                        Text(stringResource(R.string.encoder_reference_processing_percent, percent))
                        // Material3 dibuja por defecto un "stop indicator" (punto) al final de
                        // la pista; en la auditoría se percibía como un artefacto. Se elimina
                        // para dejar solo pista + relleno. Ver F-018.
                        LinearProgressIndicator(
                            progress = { tracking.progress },
                            modifier = Modifier.fillMaxWidth(),
                            drawStopIndicator = {},
                        )
                    val remainingMs = tracking.estimatedRemainingMs
                    Text(
                        text = if (remainingMs == null) {
                            stringResource(R.string.encoder_reference_processing_estimating)
                        } else if (remainingMs < 60_000L) {
                            stringResource(
                                R.string.encoder_reference_processing_seconds,
                                ((remainingMs + 999L) / 1_000L).toInt().coerceAtLeast(1),
                            )
                        } else {
                            stringResource(
                                R.string.encoder_reference_processing_minutes,
                                ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                tracking.phase == TrackingPhase.READY -> Text(stringResource(R.string.encoder_reference_ready))
                tracking.phase in setOf(TrackingPhase.ERROR, TrackingPhase.LOST) -> {
                    // resetTracking only clears state for a fresh zone pick; it does
                    // not retry automatically. Keep the real failure message and
                    // label the action as a new selection, not a retry.
                    Text(tracking.message ?: stringResource(R.string.encoder_reference_uncertain), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_new_selection))
                    }
                }
                manualAB -> {
                    manualControls()
                    // Enlarge from the start of the manual route, even with no
                    // points yet: zoom/pan editing of the A/B pair happens on an
                    // anchored snapshot, never on live scale state.
                    if (abBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(
                        onClick = onEnlargeAb,
                        enabled = editable && abEnlargeReady && !abBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reference-ab-enlarge"),
                    ) {
                        Text(stringResource(R.string.encoder_reference_enlarge))
                    }
                    // Pre-calibration escape: the manual form must not trap a novice.
                    // Reuses the existing plate route (requestPlateTap), no new capability.
                    TextButton(
                        onClick = onChoose,
                        enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reference-method-switch-plate"),
                    ) {
                        Text(stringResource(R.string.encoder_reference_method_plate))
                    }
                }
                !chosen -> {
                    Text(
                        stringResource(R.string.encoder_reference_method_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilledTonalButton(onClick = onChoose, enabled = editable, colors = encoderToolButtonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reference-method-plate")) {
                            Text(stringResource(R.string.encoder_reference_method_plate))
                        }
                        FilledTonalButton(onClick = onOtherReference, enabled = editable, colors = encoderToolButtonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reference-method-distance")) {
                            Text(stringResource(R.string.encoder_reference_method_distance))
                        }
                    }
                    Text(
                        stringResource(R.string.encoder_reference_method_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                plate.isBusy -> {
                    Text(stringResource(R.string.encoder_reference_searching))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                review -> {
                    val p = plate.proposal
                    val warning = when {
                        p?.isWithinPresentationBounds == false -> R.string.encoder_reference_bounds
                        p?.originalDetection?.let { it.perspectiveRatio > 0.0 && !it.autoCalibrationAccepted } == true -> R.string.encoder_reference_oblique
                        p?.requiresAdjustment == true && !p.adjusted -> R.string.encoder_reference_uncertain
                        else -> null
                    }
                    val notice = warning ?: if (smallReference) R.string.encoder_reference_small else null
                    notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                        color = if (warning != null) OpenJumpTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    ReferenceToolActions(fine, editable, { fine = !fine }, onEnlarge)
                    if (fine && p != null) PlateReferenceFineControls(p, editable, onGeometry, Modifier.bringIntoViewRequester(fineRequester))
                }
                plate.acceptsAssistedTap -> {
                    if (plate.phase == PlateDetectionPhase.ASSISTED_FAILED) Text(
                        stringResource(R.string.encoder_reference_uncertain), style = MaterialTheme.typography.bodySmall,
                        color = OpenJumpTheme.colors.warning,
                    )
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FilledTonalButton(onClick = onAuto, enabled = editable, colors = encoderToolButtonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.encoder_reference_auto))
                        }
                        FilledTonalButton(onClick = onManual, enabled = editable, colors = encoderToolButtonColors(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.encoder_reference_manual))
                        }
                    }
                }
            }
            if (chosen && !plate.isBusy && !tracking.isBusy) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Consequence first: changing the moment clears the confirmed scale
                // via the existing callbacks. Shown BEFORE the actionable moment
                // button while calibration still exists (the post-click
                // showMoments+calibration!=null state is unreachable because the
                // callbacks clear calibration). No new dialog or guard.
                if (draft.calibration != null) {
                    Text(
                        stringResource(R.string.selector_change_scale_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenJumpTheme.colors.warning,
                    )
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (review) TextButton(onClick = onRetry, enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.encoder_reference_retry))
                    }
                    TextButton(onClick = onMoment, enabled = editable || !displayEditingBlocked(plate, tracking),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.encoder_reference_moment))
                    }
                    if (draft.calibration == null && !manualAB) TextButton(onClick = onOtherReference, enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.encoder_reference_other))
                    }
                }
                if (showMoments) {
                    temporalControls()
                }
            }
        }
        val primaryLabel = when {
            tracking.isBusy -> R.string.selector_cancel_tracking
            tracking.phase == TrackingPhase.READY -> R.string.encoder_reference_follow
            review -> R.string.encoder_reference_confirm
            else -> null
        }
        primaryLabel?.let { label ->
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = when {
                    tracking.isBusy -> onCancel
                    tracking.phase == TrackingPhase.READY -> onStart
                    else -> onConfirm
                },
                enabled = tracking.isBusy || tracking.canStart || canConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("reference-primary"),
            ) { Text(stringResource(label)) }
        }
    }
}

private fun displayEditingBlocked(plate: PlateDetectionUiState, tracking: TrackingUiState) = plate.isBusy || tracking.isBusy

@Composable
private fun ReferenceToolActions(fine: Boolean, editable: Boolean, onFine: () -> Unit, onEnlarge: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 280.dp || (LocalDensity.current.fontScale > 1.3f && maxWidth < 340.dp)
        val adjust: @Composable (Modifier) -> Unit = { modifier ->
            FilledTonalButton(onClick = onFine, enabled = editable, colors = encoderToolButtonColors(fine),
                modifier = modifier.heightIn(min = 48.dp).semantics { selected = fine }) {
                Text(stringResource(R.string.encoder_reference_adjust))
            }
        }
        val enlarge: @Composable (Modifier) -> Unit = { modifier ->
            // Mismo nivel y mismo contenedor que Adjust: antes era solo texto y
            // parecia una jerarquia distinta. Ver F-023.
            FilledTonalButton(onClick = onEnlarge, enabled = editable, colors = encoderToolButtonColors(),
                modifier = modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.encoder_reference_enlarge))
            }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            adjust(Modifier.fillMaxWidth()); enlarge(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            adjust(Modifier.weight(1f)); enlarge(Modifier.weight(1f))
        }
    }
}
