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
    var chosen by rememberSaveable(draft.sessionKey) { mutableStateOf(plate.phase != PlateDetectionPhase.IDLE) }
    var moments by rememberSaveable(draft.sessionKey) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var renderedView by remember { mutableStateOf<PlayerView?>(null) }
    var precisionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var precisionProposal by remember { mutableStateOf<PlateCalibrationProposal?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var precisionFailure by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(precisionBitmap) {
        val owned = precisionBitmap
        onDispose { owned?.recycle() }
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
        selectingTarget -> R.string.selector_select_track_target
        !chosen -> R.string.encoder_reference_choose_hint
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
                    onOtherReference = viewModel::useManualEncoderSelection,
                    onGeometry = viewModel::updatePlateProposal,
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
                        if (reviewing || draft.calibration != null) viewModel.requestPlateTap()
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
                Box(modifier.background(Color.Black).clipToBounds().onSizeChanged { viewport = it }.testTag("reference-video")) {
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
                                        override fun surfaceCreated(holder: SurfaceHolder) { pv.post { viewModel.refreshCurrentFrame() } }
                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
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
                            plateDetection = plate.detection,
                            selectionEnabled = editable && chosen && (plate.acceptsAssistedTap || selectingTarget),
                            onTargetSelected = if (selectingTarget) viewModel::selectTrackingTarget else viewModel::detectPlateNear,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (manualAB) MetricCalibrationOverlay(
                            m, draft.calibrationPointA, draft.calibrationPointB, editable,
                            { viewModel.markCalibrationPoint(it) }, Modifier.fillMaxSize(),
                        )
                    }
                    if (loading && !preparingFirstRecording) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    VideoPreparationOverlay(active = preparingFirstRecording, delayMillis = preparationFeedbackDelayMs)
                    Column(Modifier.align(Alignment.BottomStart).padding(Spacing.sm)) {
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
            if (!manualAB) Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    if (plate.phase == PlateDetectionPhase.MANUAL) Text(stringResource(R.string.encoder_reference_other),
                        style = MaterialTheme.typography.titleSmall)
                    else PlateDiameterSummary(draft, enabled = !plate.isBusy && !tracking.isBusy && draft.calibration == null,
                        onChange = onDiameter, valueStyle = OpenJumpTypes.MetricValue)
            }
            when {
                tracking.isBusy -> {
                    Text(stringResource(R.string.encoder_reference_processing))
                    LinearProgressIndicator(progress = { tracking.progress }, modifier = Modifier.fillMaxWidth())
                }
                tracking.phase == TrackingPhase.READY -> Text(stringResource(R.string.encoder_reference_ready))
                tracking.phase in setOf(TrackingPhase.ERROR, TrackingPhase.LOST) -> {
                    Text(tracking.message ?: stringResource(R.string.encoder_reference_uncertain), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_retry)) }
                }
                manualAB -> manualControls()
                !chosen -> Text(stringResource(R.string.encoder_reference_choose_hint), style = MaterialTheme.typography.bodyMedium)
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
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (review) TextButton(onClick = onRetry, enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.encoder_reference_retry))
                    }
                    TextButton(onClick = onMoment, enabled = editable || !displayEditingBlocked(plate, tracking),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.encoder_reference_moment))
                    }
                    if (draft.calibration == null && !manualAB) TextButton(onClick = onOtherReference, enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.encoder_reference_other))
                    }
                }
                if (showMoments) temporalControls()
            }
        }
        val primaryLabel = when {
            tracking.isBusy -> R.string.selector_cancel_tracking
            tracking.phase == TrackingPhase.READY -> R.string.encoder_reference_follow
            !chosen -> R.string.encoder_reference_choose_video
            review -> R.string.encoder_reference_confirm
            else -> null
        }
        primaryLabel?.let { label ->
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = when {
                    tracking.isBusy -> onCancel
                    tracking.phase == TrackingPhase.READY -> onStart
                    !chosen -> onChoose
                    else -> onConfirm
                },
                enabled = tracking.isBusy || tracking.canStart || if (!chosen) editable else canConfirm,
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
            TextButton(onClick = onEnlarge, enabled = editable,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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
