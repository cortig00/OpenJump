package com.openjump.app.ui.selector

import android.content.Intent
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import com.openjump.app.R
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.anthropometricsSnapshotFor
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.HorizontalJumpStage
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.JumpFlowProgress
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.ProtocolFeedbackBanner
import com.openjump.app.data.PersonalRecord
import com.openjump.app.ui.athletes.PersonalRecordFeedbackBanner
import com.openjump.app.ui.components.ProtocolFeedbackLevel
import com.openjump.app.ui.components.QuickAthletePicker
import com.openjump.app.ui.components.VideoPreparationOverlay
import com.openjump.app.ui.components.remainingVideoPreparationFeedbackDelayMs
import com.openjump.app.ui.labs.ExperimentalBadge
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.AppSession
import com.openjump.app.video.BilateralFeedback
import com.openjump.app.video.BilateralFeedbackKind
import com.openjump.app.video.SeriesFeedback
import com.openjump.app.video.completedCount
import com.openjump.app.video.VideoFrameIndex
import kotlin.math.roundToInt

private data class VisibleProtocolFeedback(
    val event: java.io.Serializable,
    val expiresAtElapsedMs: Long,
) : java.io.Serializable

internal fun shouldDelayInitialRecordingSpinner(source: VideoSource?, hasCachedIndex: Boolean): Boolean =
    source == VideoSource.RECORDED && !hasCachedIndex

@Composable
private fun bilateralFeedbackText(feedback: BilateralFeedback): Pair<String, String> {
    val side = stringResource(if (feedback.side == MeasurementSide.LEFT) R.string.protocol_side_left else R.string.protocol_side_right)
    val next = feedback.nextSide?.let {
        stringResource(if (it == MeasurementSide.LEFT) R.string.protocol_side_left else R.string.protocol_side_right)
    }
    val count = feedback.completedCount
    val target = feedback.targetAttempts
    return when (feedback.kind) {
        BilateralFeedbackKind.ATTEMPT -> stringResource(R.string.bilateral_attempt_saved, side, count, target) to
            stringResource(R.string.bilateral_attempt_saved_accessibility, side, count, target)
        BilateralFeedbackKind.SIDE_COMPLETE -> when {
            target == 1 && next != null -> stringResource(R.string.bilateral_side_finished_next_single, side, next) to
                stringResource(R.string.bilateral_side_finished_single_accessibility, side, next)
            next != null -> stringResource(R.string.bilateral_side_finished_next, side, count, target, next) to
                stringResource(R.string.bilateral_side_finished_accessibility, side, count, target, next)
            target == 1 -> stringResource(R.string.bilateral_side_finished_single, side) to side
            else -> stringResource(R.string.bilateral_side_finished, side, count, target) to side
        }
        BilateralFeedbackKind.COMPLETE -> stringResource(R.string.bilateral_complete_feedback) to
            stringResource(R.string.bilateral_complete_feedback)
    }
}

/**
 * Pantalla de análisis manual: navegación frame a frame (PTS reales) con
 * barra de navegación (seekbar) y el flujo de marcadores.
 *
 * El frame se muestra en un PlayerView con SurfaceView y ajuste FIT/CROP:
 * Media3 aplica rotación/aspecto y el codec renderiza directamente por GPU. La
 * etiqueta muestra el frame en base 1 ("Frame 1" para el índice 0).
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun FrameSelectorScreen(
    onBack: () -> Unit,
    onCompute: () -> Unit,
    recordFeedback: List<PersonalRecord> = emptyList(),
    onRecordFeedbackConsumed: () -> Unit = {},
    recordedVideoStopElapsedMs: Long? = null,
    viewModel: FrameSelectorViewModel = viewModel(factory = FrameSelectorViewModel.Factory),
) {
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val player by viewModel.player.collectAsState()
    val displayState by viewModel.displayState.collectAsState()
    val trackingState by viewModel.trackingState.collectAsState()
    val plateDetectionState by viewModel.plateDetectionState.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val androidContext = LocalContext.current
    val app = androidContext.applicationContext as OpenJumpApp
    val activeAthletes by app.athleteRepository.active().collectAsState(initial = emptyList())
    val roster by AppSession.testingParticipantIds.collectAsState()
    val shareTrajectoryTitle = stringResource(R.string.selector_share_trajectory)
    val localizedEventLabels = EventType.entries.associateWith { stringResource(it.titleResource()) }
    val saveExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destination ->
        destination?.let(viewModel::saveTrajectoryExport)
    }

    val index by AppSession.frameIndex.collectAsState()
    val videoUri by AppSession.videoUri.collectAsState()
    val currentIdx by AppSession.currentFrameIndex.collectAsState()
    val draft by AppSession.draft.collectAsState()
    val bilateralSession by AppSession.bilateral.collectAsState()
    val encoderDraft by AppSession.encoderDraft.collectAsState()
    val seriesAttempt by AppSession.seriesAttempt.collectAsState()
    val seriesTargetAttempts by AppSession.seriesTargetAttempts.collectAsState()
    // Snapshot at entry: indexing may finish before the first frame is rendered.
    // Reusing a video keeps AppSession.frameIndex, so later visits retain the usual UI.
    val firstRecordedPreparation = remember(videoUri) {
        shouldDelayInitialRecordingSpinner(
            source = draft?.source ?: encoderDraft?.source,
            hasCachedIndex = index != null,
        )
    }
    val preparationFeedbackDelayMs = remember(videoUri) {
        remainingVideoPreparationFeedbackDelayMs(recordedVideoStopElapsedMs, SystemClock.elapsedRealtime())
    }
    var firstPreparationComplete by remember(videoUri) { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (!loading) firstPreparationComplete = true
    }
    val preparingFirstRecording = loading && firstRecordedPreparation && !firstPreparationComplete
    val bilateralProgressText = bilateralSession?.currentSide?.let { side ->
        val sideName = stringResource(if (side == MeasurementSide.LEFT) R.string.protocol_side_left else R.string.protocol_side_right)
        if (bilateralSession!!.targetAttempts == 1) sideName else stringResource(
            R.string.bilateral_progress, sideName, bilateralSession!!.currentSideAttempt, bilateralSession!!.targetAttempts,
        )
    }
    // One consumed event, with a real expiry: rotation preserves only its remaining
    // lifetime. A recomposition cannot synthesize an event from progress counters.
    var visibleFeedback by rememberSaveable(bilateralSession?.sessionKey ?: draft?.sessionKey) {
        mutableStateOf<VisibleProtocolFeedback?>(null)
    }
    fun showFeedback(event: java.io.Serializable) {
        visibleFeedback = VisibleProtocolFeedback(
            event, SystemClock.elapsedRealtime() +
                if (event is BilateralFeedback && event.kind == BilateralFeedbackKind.SIDE_COMPLETE) 3200L else 2200L,
        )
    }
    val visible = visibleFeedback?.takeIf { it.expiresAtElapsedMs > SystemClock.elapsedRealtime() }
    val feedback = visible?.event
    LaunchedEffect(visibleFeedback?.event) {
        visibleFeedback?.let { shown ->
            delay((shown.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (visibleFeedback == shown) visibleFeedback = null
        }
    }
    var shownRecords by remember { mutableStateOf<List<PersonalRecord>>(emptyList()) }
    LaunchedEffect(recordFeedback) {
        if (recordFeedback.isNotEmpty()) {
            shownRecords = recordFeedback
            onRecordFeedbackConsumed()
            delay(4_000L)
            if (shownRecords == recordFeedback) shownRecords = emptyList()
        }
    }
    val feedbackText = when (feedback) {
        is BilateralFeedback -> bilateralFeedbackText(feedback)
        is SeriesFeedback -> stringResource(R.string.series_attempt_saved, feedback.savedAttempt, feedback.targetAttempts).let { it to it }
        else -> null
    }
    val seriesPresentation = remember(seriesAttempt, seriesTargetAttempts, bilateralProgressText) {
        seriesPresentationFor(seriesAttempt, seriesTargetAttempts).copy(
            showProgress = bilateralSession != null || seriesPresentationFor(seriesAttempt, seriesTargetAttempts).showProgress,
            progressResource = R.string.camera_jump_progress.takeIf { bilateralSession != null } ?: seriesPresentationFor(seriesAttempt, seriesTargetAttempts).progressResource,
            progressArgs = if (bilateralSession != null) emptyList() else seriesPresentationFor(seriesAttempt, seriesTargetAttempts).progressArgs,
            progressTextOverride = bilateralProgressText,
        )
    }
    val isEncoder = encoderDraft != null
    // Encoder preparation has a fixed viewport/action pane. The shared selector
    // remains unchanged for jumps and for canonical trajectory review.
    if (encoderDraft != null && !EncoderReviewPolicy.resolve(
            EncoderReviewInput(trackingState.phase, trackingState.results, encoderDraft!!.tracking, exportState.blocksViewer),
        ).visible
    ) {
        EncoderReferenceScreen(
            viewModel = viewModel,
            draft = requireNotNull(encoderDraft),
            athleteName = activeAthletes.firstOrNull { it.id == encoderDraft?.athleteId }?.displayName,
            onBack = onBack,
            preparingFirstRecording = preparingFirstRecording,
            preparationFeedbackDelayMs = preparationFeedbackDelayMs,
        )
        return
    }
    val isHorizontal = draft?.protocolId == ProtocolId.HORIZONTAL
    val pickerScope = rememberCoroutineScope()
    // Claim-once guard shared by encoder/jump/horizontal result navigation. The
    // synchronous check drops a repeated onCompute callback before recomposition
    // disables the button; the remember keys reset it for a new session and the
    // ON_RESUME observer below rearms it on Correct-return (same composition,
    // same session keys).
    var resultNavigationInFlight by remember(encoderDraft?.sessionKey, draft?.sessionKey) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // If navigation creates this destination after ON_RESUME, the observer below
    // misses that edge. Consume once on entry as well, but never while hidden.
    fun consumeFeedback() {
        val event = bilateralSession?.sessionKey?.let(AppSession::takeBilateralFeedback)
            ?: draft?.sessionKey?.let(AppSession::takeSeriesFeedback)
        event?.let(::showFeedback)
    }
    LaunchedEffect(lifecycleOwner, bilateralSession?.sessionKey, draft?.sessionKey) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) consumeFeedback()
    }
    DisposableEffect(lifecycleOwner, viewModel, bilateralSession?.sessionKey, draft?.sessionKey) {
        // Pause only on background: resume stays manual and export/tracking jobs
        // are untouched, so this never interferes with them or with navigation.
        // ON_RESUME rearms the claim-once guard above: Correct pops back to this
        // same composition with identical session keys, so without this Compute
        // would stay disabled forever. Resume only fires when this entry becomes
        // current again, and it cannot interleave the synchronous
        // claim-then-navigate tap handler, so it never re-arms before the push.
        val observer = selectorResultNavigationObserver(
            onBackground = viewModel::pausePlayback,
            onSelectorResumed = {
                if (resultNavigationInFlight) resultNavigationInFlight = false
                consumeFeedback()
            },
        )
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var confirmTargetReselection by remember { mutableStateOf(false) }
    var confirmCalibrationReset by remember { mutableStateOf(false) }
    val horizontalDraft = draft?.horizontalJump
    val horizontalPanelRequester = remember { BringIntoViewRequester() }
    var accessibleCursor by remember(encoderDraft?.sessionKey, draft?.sessionKey, horizontalDraft?.stage, index) { mutableStateOf<ImagePoint?>(null) }
    LaunchedEffect(horizontalDraft?.stage) {
        if (isHorizontal && horizontalDraft != null) horizontalPanelRequester.bringIntoView()
    }

    LaunchedEffect(encoderDraft?.timingDecision, encoderDraft?.calibration, plateDetectionState.phase, trackingState.phase) {
        accessibleCursor = null
    }
    val requiredEvents = draft?.definition?.requiredEvents.orEmpty()
    val completedEvents = requiredEvents.count { EventKey(it) in draft?.events.orEmpty() }
    val frameCount = index?.frameCount ?: 0
    val hasVideoIndex = frameCount > 0
    val testingPickerVisible = roster.isNotEmpty() &&
        (draft?.testingSessionId != null || encoderDraft?.testingSessionId != null)
    val terminalErrorWithoutIndex = error != null && !hasVideoIndex && player == null
    val athleteName = activeAthletes.firstOrNull {
        it.id == (draft?.athleteId ?: encoderDraft?.athleteId)
    }?.displayName
    val isTemporalJump = draft != null && !isHorizontal && requiredEvents.isNotEmpty()
    val renderedIdx = displayState.renderedIndex
    var videoViewport by remember { mutableStateOf(IntSize.Zero) }
    var scaleMode by remember(index) { mutableStateOf(VideoScaleMode.FIT) }
    var cropAlignment by remember(index) { mutableStateOf(CropAlignment.Centered) }
    var cropZoom by remember(index) { mutableStateOf(MIN_CROP_ZOOM) }
    var isCropFraming by remember(index) { mutableStateOf(false) }
    val reviewActive = plateDetectionState.phase == PlateDetectionPhase.REVIEW
    // Review uses a synchronous effective viewport so the PlayerView and mapper
    // cannot expose one composition of the previous CROP transform. The user's
    // framing state remains intact and is used again after review.
    val effectiveScaleMode = if (reviewActive) VideoScaleMode.FIT else scaleMode
    val effectiveCropZoom = if (reviewActive) MIN_CROP_ZOOM else cropZoom
    val effectiveCropAlignment = if (reviewActive) CropAlignment.Centered else cropAlignment
    val effectiveIsCropFraming = isCropFraming && !reviewActive
    val plateProposalVisible = plateDetectionState.proposal != null &&
        plateDetectionState.phase !in setOf(PlateDetectionPhase.IDLE, PlateDetectionPhase.MANUAL, PlateDetectionPhase.DETECTED)
    val framingEnabled = !loading && !trackingState.isBusy &&
        !plateDetectionState.isBusy && !exportState.blocksViewer && !reviewActive
    val navigationEnabled = player != null && renderedIdx != null && !trackingState.isBusy &&
        !plateDetectionState.isBusy && !exportState.blocksViewer && !effectiveIsCropFraming && !reviewActive
    val markingEnabled = displayState.canMark && !trackingState.isBusy &&
        !plateDetectionState.isBusy && !exportState.blocksViewer && !effectiveIsCropFraming
    val markingAction = JumpMarkingActionPolicy.resolve(
        draft = draft,
        markingEnabled = markingEnabled,
        isPlaying = displayState.isPlaying,
        isCropFraming = effectiveIsCropFraming,
    )
    // While result navigation is in flight the jump Compute button mirrors the
    // encoder guard and renders disabled; the synchronous claim in onCompute
    // already drops a second tap before this recomposes.
    val effectiveMarkingAction = withResultNavigationInFlight(markingAction, resultNavigationInFlight)
    val coordinateMapper = remember(index, videoViewport, effectiveScaleMode, effectiveCropAlignment, effectiveCropZoom) {
        val frameIndex = index
        if (frameIndex != null && frameIndex.width > 0 && frameIndex.height > 0 &&
            videoViewport.width > 0 && videoViewport.height > 0
        ) {
            VideoCoordinateMapper(
                video = VideoPresentationGeometry.fromEncoded(
                    frameIndex.width,
                    frameIndex.height,
                    frameIndex.rotationDegrees,
                ),
                viewport = ViewportSize(videoViewport.width.toDouble(), videoViewport.height.toDouble()),
                scaleMode = effectiveScaleMode,
                cropAlignment = effectiveCropAlignment,
                zoom = effectiveCropZoom,
            )
        } else {
            null
        }
    }
    val reviewMapperReady = isPlateReviewMapperReady(plateDetectionState.phase, coordinateMapper)
    val plateProposalVisibleInViewport = coordinateMapper?.let { mapper ->
        plateDetectionState.proposal?.let { proposal ->
            mapper.isPlateCircleVisible(proposal.center, proposal.diameterPx, proposal.angleRadians)
        }
    } == true
    val reviewPolicy = EncoderReviewPolicy.resolve(
        EncoderReviewInput(
            phase = trackingState.phase,
            results = trackingState.results,
            canonicalResults = encoderDraft?.tracking.orEmpty(),
            exportBlocksViewer = exportState.blocksViewer,
        ),
    )
    val encoderPanelPolicy = encoderDraft?.let {
        resolveEncoderPanelPolicy(
            hasCalibration = it.calibration != null,
            platePhase = plateDetectionState.phase,
            trackingPhase = trackingState.phase,
            reviewVisible = reviewPolicy.visible,
        )
    }
    val targetSelectionEnabled = markingEnabled && navigationEnabled &&
        encoderPanelPolicy?.mode == EncoderPanelMode.MANUAL_TARGET_SELECTION && !reviewPolicy.visible
    val encoderStepPresentation = encoderDraft?.let {
        encoderStepPresentationFor(
            hasCalibration = it.calibration != null,
            trackingPhase = trackingState.phase,
            hasUsablePoint = reviewPolicy.visible,
            fontScale = LocalDensity.current.fontScale,
        )
    }
    val currentTrackingResult = remember(trackingState.results, renderedIdx) {
        renderedIdx?.let { visible ->
            trackingState.results.asReversed().firstOrNull { it.frameIndex == visible }
        }
    }
    val recentTrackingResults = remember(trackingState.results, renderedIdx) {
        renderedIdx?.let { visible ->
            trackingState.results.asReversed().asSequence()
                .filter { it.frameIndex <= visible && it.result.sample.point != null }
                .take(60)
                .toList()
                .asReversed()
        }.orEmpty()
    }

    // Durante el drag Media3 conserva el último seek solicitado; al soltar se
    // exige el target exacto final. Los marcadores siguen deshabilitados hasta
    // confirmar ese mismo frame.
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val labelIndex = dragIndex ?: renderedIdx ?: currentIdx
    val displayIndex = if (hasVideoIndex) labelIndex + 1 else 0 // base 1
    val timelineMapper = remember(index) { index?.let { PtsTimelineMapper(it.frameTimesUs) } }
    val markerColor = MaterialTheme.colorScheme.primary
    val timelineMarkers = remember(draft?.events, markerColor, localizedEventLabels) {
        draft?.events?.values.orEmpty()
            .sortedBy { it.ptsUs }
            .map { mark -> TimelineMarker(mark.ptsUs, localizedEventLabels.getValue(mark.key.type), markerColor) }
    }
    val presentationAspectRatio = remember(index) {
        index?.takeIf { it.width > 0 && it.height > 0 }?.let { frameIndex ->
            val geometry = VideoPresentationGeometry.fromEncoded(
                frameIndex.width,
                frameIndex.height,
                frameIndex.rotationDegrees,
            )
            (geometry.width / geometry.height).toFloat().coerceIn(0.75f, 16f / 9f)
        } ?: (16f / 9f)
    }

    fun timeLabel(i: Int): String =
        if (i in 0 until frameCount) index?.timeOf(i)?.let(Formatting::usToClockMs) ?: "--" else "--"

    val screenScrollState = rememberScrollState()
    val jumpTitleResource = draft?.protocolId?.titleResource()
    val encoderTitleResource = encoderDraft?.setup?.exercise?.titleResource()
    val screenTitle = when {
        bilateralSession != null -> stringResource(R.string.protocol_bilateral_title)
        jumpTitleResource != null -> stringResource(jumpTitleResource)
        encoderTitleResource != null -> stringResource(encoderTitleResource)
        else -> stringResource(R.string.selector_review_video_title)
    }
    val screenSubtitle = when {
        // Bilateral progress already contains the side and attempt context; do
        // not prepend the athlete a second time in Testing.
        bilateralSession != null && testingPickerVisible -> bilateralProgressText
        testingPickerVisible && seriesPresentation.showProgress -> stringResource(
            R.string.selector_series_context,
            seriesAttempt,
            seriesTargetAttempts,
        )
        testingPickerVisible -> null
        bilateralSession != null && athleteName != null -> stringResource(
            R.string.bilateral_athlete_context, athleteName, bilateralProgressText.orEmpty(),
        )
        // Keep bilateral context useful even if the athlete is no longer in the
        // active roster.
        bilateralSession != null -> bilateralProgressText
        seriesPresentation.showProgress && athleteName != null -> stringResource(
            R.string.selector_series_subtitle,
            athleteName,
            seriesAttempt,
            seriesTargetAttempts,
        )
        seriesPresentation.showProgress -> stringResource(
            R.string.selector_series_context,
            seriesAttempt,
            seriesTargetAttempts,
        )
        else -> athleteName
    }
    val navigateBack = {
        if (exportState.blocksViewer) viewModel.cancelTrajectoryExport()
        viewModel.pausePlayback()
        onBack()
    }
    if (confirmTargetReselection) {
        AlertDialog(
            onDismissRequest = { confirmTargetReselection = false },
            title = { Text(stringResource(R.string.selector_change_target_title)) },
            text = { Text(stringResource(R.string.selector_change_target_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTargetReselection = false
                    viewModel.reselectEncoderTarget()
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTargetReselection = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (confirmCalibrationReset) {
        AlertDialog(
            onDismissRequest = { confirmCalibrationReset = false },
            title = { Text(stringResource(R.string.selector_change_scale_title)) },
            text = { Text(stringResource(R.string.selector_change_scale_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCalibrationReset = false
                    viewModel.recalibrateEncoder()
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCalibrationReset = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OpenJumpTopAppBar(
                title = screenTitle,
                subtitle = screenSubtitle,
                onNavigationClick = navigateBack,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    when {
                        encoderStepPresentation != null -> {
                            val step = encoderStepPresentation.step
                            val label = stringResource(
                                when (step) {
                                    EncoderProgressStep.CALIBRATE -> R.string.selector_encoder_progress_calibrate
                                    EncoderProgressStep.TRACK -> R.string.selector_encoder_progress_track
                                    EncoderProgressStep.REVIEW -> R.string.selector_encoder_progress_review
                                },
                            )
                            val progressText = if (encoderStepPresentation.compact) {
                                stringResource(
                                    R.string.selector_encoder_progress_compact,
                                    step.number,
                                    ENCODER_STEP_COUNT,
                                )
                            } else {
                                stringResource(
                                    R.string.selector_encoder_progress,
                                    step.number,
                                    ENCODER_STEP_COUNT,
                                    label,
                                )
                            }
                            val progressDescription = stringResource(
                                R.string.selector_encoder_progress_accessibility,
                                step.number,
                                ENCODER_STEP_COUNT,
                                label,
                            )
                            Text(
                                progressText,
                                modifier = Modifier
                                    .padding(end = Spacing.sm)
                                    .semantics {
                                        contentDescription = progressDescription
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        isTemporalJump || isHorizontal -> {
                            Text(
                                stringResource(
                                    R.string.selector_mark_flow_progress,
                                    3,
                                    4,
                                    stringResource(R.string.jump_flow_mark),
                                ),
                                modifier = Modifier.padding(end = Spacing.sm),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isEncoder && reviewPolicy.visible) {
                Button(
                    onClick = {
                        if (!resultNavigationInFlight && viewModel.commitEncoderTracking()) {
                            resultNavigationInFlight = true
                            viewModel.suspendViewerForResult()
                            onCompute()
                        }
                    },
                    enabled = reviewPolicy.canAnalyze && !resultNavigationInFlight,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs).heightIn(min = 54.dp),
                ) { Text(stringResource(R.string.selector_analyze_trajectory)) }
            } else if (!terminalErrorWithoutIndex) {
                JumpMarkingActionBar(
                    action = effectiveMarkingAction,
                    seriesPresentation = seriesPresentation,
                    onMark = viewModel::mark,
                    onCompute = {
                        if (!resultNavigationInFlight) {
                            resultNavigationInFlight = true
                            viewModel.pausePlayback()
                            onCompute()
                        }
                    },
                    contextText = screenSubtitle,
                )
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(screenScrollState, enabled = !effectiveIsCropFraming)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            if (testingPickerVisible) {
                QuickAthletePicker(
                    athletes = activeAthletes,
                    currentAthleteId = draft?.athleteId ?: encoderDraft?.athleteId,
                    allowedAthleteIds = roster,
                    enabled = true,
                    singleLine = true,
                    onAthleteSelected = { athleteId ->
                        pickerScope.launch {
                            val testingId = draft?.testingSessionId ?: encoderDraft?.testingSessionId
                            if (testingId != null && app.testingRepository.setCurrentAthlete(testingId, athleteId)) {
                                AppSession.reassignActiveAthlete(
                                    athleteId,
                                    activeAthletes.anthropometricsSnapshotFor(athleteId),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                )
            }
            bilateralSession?.let { session ->
                val currentSide = session.currentSide
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MeasurementSide.entries.forEach { side ->
                        val count = session.completedCount(side)
                        val sideName = stringResource(if (side == MeasurementSide.LEFT) R.string.protocol_side_left else R.string.protocol_side_right)
                        val label = when {
                            count == session.targetAttempts && session.targetAttempts == 1 -> stringResource(R.string.bilateral_side_complete_single, sideName)
                            count == session.targetAttempts -> stringResource(R.string.bilateral_side_complete_count, sideName, count, session.targetAttempts)
                            session.targetAttempts == 1 -> sideName
                            else -> stringResource(R.string.bilateral_side_count, sideName, count, session.targetAttempts)
                        }
                        val description = if (currentSide == side) stringResource(R.string.bilateral_side_active_accessibility, label) else label
                        FilterChip(
                            selected = currentSide == side,
                            onClick = { AppSession.selectBilateralSide(side) },
                            enabled = count < session.targetAttempts,
                            label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f).heightIn(min = Spacing.xxxl).semantics {
                                contentDescription = description
                            },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }
            if (draft != null && encoderDraft == null) {
                shownRecords.forEach { record ->
                    PersonalRecordFeedbackBanner(record)
                    Spacer(Modifier.height(Spacing.xs))
                }
                ProtocolFeedbackBanner(
                    text = feedbackText?.first,
                    accessibilityText = feedbackText?.second,
                    level = if (feedback is BilateralFeedback && feedback.kind == BilateralFeedbackKind.SIDE_COMPLETE)
                        ProtocolFeedbackLevel.MILESTONE else ProtocolFeedbackLevel.ATTEMPT,
                )
                if (feedbackText != null) Spacer(Modifier.height(Spacing.xs))
            }
            if (draft != null && encoderDraft == null && isHorizontal) {
                Spacer(Modifier.height(Spacing.sm))
            }
            encoderDraft?.let {
                ExperimentalBadge()
                Spacer(Modifier.height(Spacing.sm))
            }

            // ── Frame actual: ExoPlayer renderiza directo a SurfaceView/GPU ─────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(presentationAspectRatio)
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged { videoViewport = it },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { androidContext ->
                    PlayerView(androidContext).apply {
                        val playerView = this
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                        (videoSurfaceView as? SurfaceView)?.holder?.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    // Ejecutar tras el callback interno de PlayerView,
                                    // cuando ExoPlayer ya posee la nueva superficie.
                                    playerView.post { viewModel.refreshCurrentFrame() }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            },
                        )
                    }
                },
                update = { playerView ->
                    playerView.resizeMode = when (effectiveScaleMode) {
                        VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        VideoScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    val contentFrame = playerView.findViewById<View>(Media3UiR.id.exo_content_frame)
                    val translation = coordinateMapper?.contentTranslation
                    contentFrame?.apply {
                        scaleX = coordinateMapper?.nativeScale?.toFloat() ?: 1f
                        scaleY = coordinateMapper?.nativeScale?.toFloat() ?: 1f
                        translationX = translation?.x?.toFloat() ?: 0f
                        translationY = translation?.y?.toFloat() ?: 0f
                    }
                    if (playerView.player !== player) {
                        playerView.player = player
                        playerView.post { viewModel.refreshCurrentFrame() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            coordinateMapper?.let { mapper ->
                // Visual target tracking belongs to the linear encoder flow only.
                // Jump protocols use event marks or their dedicated horizontal-point overlay.
                if (isEncoder || isHorizontal) {
                    accessibleCursor?.let { cursor ->
                        AccessibleVideoPointOverlay(mapper, cursor)
                    }
                    val assistedPlateTap = plateDetectionState.acceptsAssistedTap
                    val manualTargetTap = targetSelectionEnabled
                    if (plateProposalVisible) {
                        plateDetectionState.proposal?.let { proposal ->
                            PlateCalibrationOverlay(
                                mapper = mapper,
                                proposal = proposal,
                                onGeometryChanged = viewModel::updatePlateProposal,
                                interactive = reviewActive && !plateDetectionState.confirming,
                                modifier = Modifier.fillMaxSize().zIndex(2f),
                            )
                        }
                    }
                    TrackingOverlay(
                        mapper = mapper,
                        current = currentTrackingResult,
                        recent = recentTrackingResults,
                        trackingPhase = trackingState.phase,
                        plateDetection = plateDetectionState.detection.takeUnless { reviewActive },
                        selectionEnabled = markingEnabled && navigationEnabled &&
                            !reviewActive && !reviewPolicy.visible && (assistedPlateTap || manualTargetTap),
                        onTargetSelected = if (assistedPlateTap) viewModel::detectPlateNear else viewModel::selectTrackingTarget,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val manualPlateCalibration = isEncoder && encoderDraft?.timingDecision != null &&
                    encoderDraft?.calibration == null &&
                    (plateDetectionState.phase == PlateDetectionPhase.MANUAL ||
                        (plateDetectionState.phase == PlateDetectionPhase.DETECTED &&
                            (plateDetectionState.detection?.autoCalibrationAccepted != true ||
                                plateDetectionState.manualCalibrationRequested)))
                if (manualPlateCalibration) {
                    MetricCalibrationOverlay(
                        mapper = mapper,
                        pointA = encoderDraft?.calibrationPointA,
                        pointB = encoderDraft?.calibrationPointB,
                        enabled = markingEnabled,
                        onPoint = viewModel::markCalibrationPoint,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isHorizontal && horizontalDraft != null) {
                    if (horizontalDraft.calibration == null) {
                        MetricCalibrationOverlay(
                            mapper = mapper,
                            pointA = horizontalDraft.calibrationPointA,
                            pointB = horizontalDraft.calibrationPointB,
                            enabled = markingEnabled,
                            onPoint = viewModel::markHorizontalCalibrationPoint,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        HorizontalJumpOverlay(
                            mapper = mapper,
                            draft = horizontalDraft,
                            enabled = markingEnabled,
                            onPoint = viewModel::markHorizontalPoint,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (isCropFraming && framingEnabled) {
                    CropFramingOverlay(
                        onGesture = { centroid, zoomChange, panDelta ->
                            val next = mapper.stateAfterGesture(
                                centroid = ImagePoint(centroid.x.toDouble(), centroid.y.toDouble()),
                                zoomChange = zoomChange.toDouble(),
                                panDeltaX = panDelta.x.toDouble(),
                                panDeltaY = panDelta.y.toDouble(),
                            )
                            cropZoom = next.zoom
                            cropAlignment = next.alignment
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(1f),
                    )
                }
            }
            if (loading && !preparingFirstRecording) {
                CircularProgressIndicator(color = Color.White)
            } else if (terminalErrorWithoutIndex) {
                Text(
                    error.orEmpty(),
                    modifier = Modifier
                        .padding(Spacing.lg)
                        .background(Color.Black.copy(alpha = 0.72f), ShapeTokens.medium)
                        .padding(Spacing.md),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            VideoPreparationOverlay(active = preparingFirstRecording, delayMillis = preparationFeedbackDelayMs)
            if (!terminalErrorWithoutIndex) Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .zIndex(2f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isCropFraming && framingEnabled -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        VideoOverlayButton(
                            text = "−",
                            contentDescription = stringResource(R.string.selector_zoom_out_description),
                            enabled = cropZoom > MIN_CROP_ZOOM,
                            onClick = {
                                coordinateMapper?.let { mapper ->
                                    val next = mapper.stateAfterGesture(
                                        centroid = ImagePoint(mapper.viewport.width / 2.0, mapper.viewport.height / 2.0),
                                        zoomChange = 0.8,
                                    )
                                    cropZoom = next.zoom
                                    cropAlignment = next.alignment
                                }
                            },
                            compactSymbol = true,
                        )
                        VideoOverlayButton(
                            text = "+",
                            contentDescription = stringResource(R.string.selector_zoom_in_description),
                            enabled = cropZoom < MAX_CROP_ZOOM,
                            onClick = {
                                coordinateMapper?.let { mapper ->
                                    val next = mapper.stateAfterGesture(
                                        centroid = ImagePoint(mapper.viewport.width / 2.0, mapper.viewport.height / 2.0),
                                        zoomChange = 1.25,
                                    )
                                    cropZoom = next.zoom
                                    cropAlignment = next.alignment
                                }
                            },
                            compactSymbol = true,
                        )
                        VideoOverlayButton(
                            text = "↺",
                            contentDescription = stringResource(R.string.selector_reset_zoom_description),
                            enabled = cropZoom != MIN_CROP_ZOOM || cropAlignment != CropAlignment.Centered,
                            onClick = {
                                cropZoom = MIN_CROP_ZOOM
                                cropAlignment = CropAlignment.Centered
                            },
                            compactSymbol = true,
                        )
                        VideoOverlayButton(
                            text = "⌾",
                            contentDescription = stringResource(R.string.selector_center_crop_description),
                            enabled = cropAlignment != CropAlignment.Centered,
                            onClick = { cropAlignment = CropAlignment.Centered },
                            compactSymbol = true,
                        )
                        VideoOverlayButton(
                            text = "✓",
                            contentDescription = stringResource(R.string.selector_done_crop_description),
                            onClick = { isCropFraming = false },
                            compactSymbol = true,
                        )
                    }

                    effectiveScaleMode == VideoScaleMode.CROP -> {
                        VideoOverlayButton(
                            text = stringResource(R.string.selector_frame_crop),
                            contentDescription = stringResource(R.string.selector_adjust_crop_description),
                            enabled = coordinateMapper != null && framingEnabled,
                            onClick = {
                                viewModel.pausePlayback()
                                isCropFraming = true
                            },
                        )
                        VideoOverlayButton(
                            text = "CROP",
                            contentDescription = stringResource(R.string.selector_crop_to_fit_description),
                            enabled = framingEnabled,
                            onClick = {
                                scaleMode = VideoScaleMode.FIT
                                cropZoom = MIN_CROP_ZOOM
                                cropAlignment = CropAlignment.Centered
                                isCropFraming = false
                            },
                        )
                    }

                    else -> VideoOverlayButton(
                        text = "FIT",
                        contentDescription = stringResource(R.string.selector_fit_to_crop_description),
                        enabled = framingEnabled,
                        onClick = {
                            scaleMode = VideoScaleMode.CROP
                            cropZoom = MIN_CROP_ZOOM
                            cropAlignment = CropAlignment.Centered
                        },
                    )
                }
            }
            if (displayState.isSeeking && renderedIdx != null) {
                Text(
                    stringResource(R.string.selector_seeking_frame, currentIdx + 1),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (hasVideoIndex && !isCropFraming) {
                VideoInfoChip(
                    text = stringResource(
                        R.string.selector_frame_readout,
                        when {
                            dragIndex != null -> stringResource(R.string.selector_target_frame, displayIndex, frameCount)
                            displayState.isPlaying -> stringResource(R.string.selector_playing_frame, displayIndex, frameCount)
                            displayState.isSeeking -> stringResource(R.string.selector_visible_frame, displayIndex, frameCount)
                            else -> stringResource(R.string.selector_frame, displayIndex, frameCount)
                        },
                        timeLabel(displayIndex - 1),
                        stringResource(R.string.selector_fps, index?.detectedFps ?: "--"),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .zIndex(2f),
                )
            }
        }
        if (isCropFraming && framingEnabled) {
            Text(
                text = stringResource(R.string.selector_drag_or_pinch_to_frame),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }

        error?.takeUnless { terminalErrorWithoutIndex }?.let {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        index?.temporalContinuity?.takeIf { it.hasGaps }?.let { continuity ->
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    stringResource(
                        R.string.selector_video_gaps,
                        continuity.gapCount,
                        continuity.largestGapUs / 1_000L,
                    ),
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (!terminalErrorWithoutIndex) {
            // ── Timeline temporal por PTS ───────────────────────────────────────
            if (frameCount > 1 && timelineMapper != null) {
            SportsTimeline(
                mapper = timelineMapper,
                selectedIndex = labelIndex,
                selectedTimeLabel = timeLabel(labelIndex),
                enabled = navigationEnabled,
                markers = timelineMarkers,
                onPreviewFrame = { dragIndex = it },
                onScrubStart = viewModel::beginScrubbing,
                onScrubToFrame = viewModel::scrubToFrame,
                onScrubEnd = viewModel::endScrubbing,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.selector_timeline_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.selector_timeline_end), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (hasVideoIndex) {
            Spacer(Modifier.height(8.dp))

            VideoTransportControls(
                isPlaying = displayState.isPlaying,
                enabled = navigationEnabled,
                onPreviousFrame = { viewModel.stepFrames(-1) },
                onTogglePlayback = viewModel::togglePlayback,
                onNextFrame = { viewModel.stepFrames(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.selector_hold_frame_step),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.md))
        }

            // ── Flujo de eventos definido por el protocolo ─────────────────────
            if (requiredEvents.isNotEmpty()) {
                EventSection(
                    requiredEvents = requiredEvents,
                    events = draft?.events.orEmpty(),
                    completedEvents = completedEvents,
                    labels = localizedEventLabels,
                    canJump = navigationEnabled,
                    canReplace = markingEnabled,
                    onJump = viewModel::jumpTo,
                    onReplace = viewModel::mark,
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            when {
            isEncoder && encoderDraft != null -> EncoderControls(
                draft = requireNotNull(encoderDraft),
                index = index,
                state = trackingState,
                plateState = plateDetectionState,
                current = currentTrackingResult,
                onStart = viewModel::startTracking,
                onConfirmProposal = viewModel::confirmPlateProposal,
                onAdjustDiameter = viewModel::adjustPlateDiameter,
                onResetTracking = viewModel::resetTracking,
                onPlateDiameterChange = viewModel::setSessionPlateDiameterCm,
                onConfirmManualCalibration = viewModel::confirmManualEncoderCalibration,
                onUseManualSelection = viewModel::useManualEncoderSelection,
                onManualCalibration = viewModel::requestManualPlateCalibration,
                onResetAutomation = viewModel::resetPlateDetectionAutomation,
                onRequestTargetReselection = { confirmTargetReselection = true },
                onRequestRecalibration = { confirmCalibrationReset = true },
                accessibleCursor = accessibleCursor,
                onAccessibleCursorChanged = { accessibleCursor = it },
                markingEnabled = markingEnabled,
                targetSelectionEnabled = targetSelectionEnabled,
                panelPolicy = requireNotNull(encoderPanelPolicy),
                plateProposalVisibleInViewport = plateProposalVisibleInViewport,
                reviewMapperReady = reviewMapperReady,
                navigationEnabled = navigationEnabled,
                onAccessibleTarget = viewModel::selectTrackingTarget,
                onAccessiblePlate = viewModel::detectPlateNear,
                onUpdateProposal = viewModel::updatePlateProposal,
                onAccessibleCalibrationPoint = viewModel::markCalibrationPoint,
                exportState = exportState,
                onExport = viewModel::exportTrajectoryVideo,
                onCancelExport = viewModel::cancelTrajectoryExport,
                onSaveExport = {
                    val suggestedName = exportState.file?.name ?: androidContext.getString(R.string.selector_export_filename)
                    saveExportLauncher.launch(suggestedName)
                },
                onShareExport = {
                    viewModel.createTrajectoryShareIntent()?.let { shareIntent ->
                        androidContext.startActivity(
                            Intent.createChooser(shareIntent, shareTrajectoryTitle),
                        )
                    }
                },
            )
            isHorizontal && horizontalDraft != null -> {
                HorizontalJumpControls(
                    draft = horizontalDraft,
                    seriesPresentation = seriesPresentation,
                    markingEnabled = markingEnabled,
                    onConfirmCalibration = AppSession::confirmHorizontalCalibration,
                    onRecalibrate = AppSession::clearHorizontalCalibration,
                    onClearStart = AppSession::clearHorizontalStartPoint,
                    onClearLanding = AppSession::clearHorizontalLandingHeel,
                    onJumpToLanding = viewModel::jumpToLanding,
                    onCompute = {
                        if (!resultNavigationInFlight) {
                            resultNavigationInFlight = true
                            viewModel.pausePlayback()
                            onCompute()
                        }
                    },
                    modifier = Modifier.bringIntoViewRequester(horizontalPanelRequester),
                )
                AccessibleVideoPointControls(
                    index = index,
                    initial = when (horizontalDraft.stage) {
                        HorizontalJumpStage.CALIBRATION -> horizontalDraft.calibrationPointB ?: horizontalDraft.calibrationPointA
                        HorizontalJumpStage.START_POINT -> horizontalDraft.startPoint?.point
                        HorizontalJumpStage.LANDING_HEEL, HorizontalJumpStage.COMPLETE -> horizontalDraft.landingHeel?.point
                    },
                    cursor = accessibleCursor,
                    enabled = markingEnabled && navigationEnabled,
                    onCursorChanged = { accessibleCursor = it },
                    onConfirm = { point ->
                        when (horizontalDraft.stage) {
                            HorizontalJumpStage.CALIBRATION -> viewModel.markHorizontalCalibrationPoint(point)
                            HorizontalJumpStage.START_POINT,
                            HorizontalJumpStage.LANDING_HEEL -> viewModel.markHorizontalPoint(point)
                            HorizontalJumpStage.COMPLETE -> false
                        }
                    },
                )
            }
            else -> {
                // Plain jump protocols mark events only (no visual tracking UI).
            }
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}
}

/**
 * Lifecycle observer for the selector entry: pauses playback when backgrounded
 * and rearms the result-navigation claim-once guard when the entry becomes
 * current again (Correct-return pops back to the same composition with
 * identical session keys). Other events are ignored; export/tracking jobs are
 * never touched. Factored out so the real observer wiring is unit-testable.
 */
internal fun selectorResultNavigationObserver(
    onBackground: () -> Unit,
    onSelectorResumed: () -> Unit,
): LifecycleEventObserver = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_STOP) {
        onBackground()
    } else if (event == Lifecycle.Event.ON_RESUME) {
        onSelectorResumed()
    }
}

/**
 * Mirrors the encoder in-flight guard for the temporal-jump Compute action:
 * while result navigation is in flight the button renders disabled so a second
 * tap cannot queue another `result` destination. Other actions pass through
 * unchanged. The synchronous `if (!inFlight) { inFlight = true; ... }` claim in
 * the screen's `onCompute` lambdas already drops a repeated callback before
 * this recomposes; this helper only drives the disabled visual state.
 */
internal fun withResultNavigationInFlight(
    action: JumpMarkingAction,
    resultNavigationInFlight: Boolean,
): JumpMarkingAction {
    if (action is JumpMarkingAction.Compute && resultNavigationInFlight) {
        return action.copy(enabled = false)
    }
    return action
}

@Composable
private fun VideoOverlayButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compactSymbol: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black.copy(alpha = 0.78f),
            contentColor = Color.White,
            disabledContainerColor = Color.Black.copy(alpha = 0.48f),
            disabledContentColor = Color.White.copy(alpha = 0.62f),
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.9f else 0.45f)),
        contentPadding = if (compactSymbol) PaddingValues(0.dp) else {
            PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        },
        modifier = (if (compactSymbol) Modifier.size(48.dp) else Modifier.heightIn(min = 48.dp))
            .semantics { this.contentDescription = contentDescription },
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Compact monospace caption chip rendered over the video surface. */
@Composable
private fun VideoInfoChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), ShapeTokens.extraSmall)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun EncoderControls(
    draft: EncoderDraft,
    index: VideoFrameIndex?,
    state: TrackingUiState,
    plateState: PlateDetectionUiState,
    current: TrackingFrameResult?,
    onStart: () -> Unit,
    onConfirmProposal: () -> Unit,
    onAdjustDiameter: (Double) -> Unit,
    onResetTracking: () -> Unit,
    onPlateDiameterChange: (Double) -> Unit,
    onConfirmManualCalibration: (Double) -> Unit,
    onUseManualSelection: () -> Unit,
    onManualCalibration: () -> Unit,
    onResetAutomation: () -> Unit,
    onRequestTargetReselection: () -> Unit,
    onRequestRecalibration: () -> Unit,
    accessibleCursor: ImagePoint?,
    onAccessibleCursorChanged: (ImagePoint?) -> Unit,
    markingEnabled: Boolean,
    targetSelectionEnabled: Boolean,
    panelPolicy: EncoderPanelPolicy,
    plateProposalVisibleInViewport: Boolean,
    reviewMapperReady: Boolean,
    navigationEnabled: Boolean,
    onAccessibleTarget: (ImagePoint) -> Unit,
    onAccessiblePlate: (ImagePoint) -> Unit,
    onUpdateProposal: (ImagePoint, Double) -> Unit,
    onAccessibleCalibrationPoint: (ImagePoint) -> Boolean,
    exportState: TrajectoryExportUiState,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onSaveExport: () -> Unit,
    onShareExport: () -> Unit,
) {
    val policy = panelPolicy
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.surface),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (policy.showsPlateDiameter) {
                PlateDiameterSummary(
                    draft = draft,
                    enabled = !plateState.isBusy,
                    onChange = onPlateDiameterChange,
                )
            }

            when (policy.mode) {
                EncoderPanelMode.PLATE_DETECTING -> {
                    Text(stringResource(R.string.selector_detecting_plate), fontWeight = FontWeight.SemiBold)
                    Text(
                        plateState.message ?: stringResource(R.string.selector_detecting_plate_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }

                EncoderPanelMode.PLATE_ASSISTED, EncoderPanelMode.PLATE_BUSY -> {
                    Text(stringResource(R.string.selector_tap_plate), fontWeight = FontWeight.SemiBold)
                    Text(
                        plateState.message ?: stringResource(R.string.selector_tap_plate_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plateState.isBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.xs))
                    } else {
                        AccessibleVideoPointControls(
                            index = index,
                            initial = plateState.proposal?.center,
                            cursor = accessibleCursor,
                            enabled = markingEnabled && navigationEnabled,
                            onCursorChanged = onAccessibleCursorChanged,
                            onConfirm = { point -> onAccessiblePlate(point); true },
                        )
                    }
                    TextButton(onClick = onUseManualSelection, enabled = !plateState.isBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_use_manual))
                    }
                }

                EncoderPanelMode.PLATE_REVIEW -> {
                    Text(stringResource(R.string.selector_plate_review_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        plateState.message ?: stringResource(R.string.selector_plate_review_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AccessibleVideoPointControls(
                        index = index,
                        initial = plateState.proposal?.center,
                        cursor = accessibleCursor,
                        enabled = !plateState.confirming && markingEnabled && navigationEnabled,
                        onCursorChanged = onAccessibleCursorChanged,
                        onConfirm = { point ->
                            plateState.proposal?.let { proposal ->
                                onUpdateProposal(point, proposal.diameterPx)
                                true
                            } ?: false
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(
                            onClick = { onAdjustDiameter(-2.0) },
                            enabled = plateState.proposal != null && !plateState.confirming,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.selector_plate_fine_minus)) }
                        OutlinedButton(
                            onClick = { onAdjustDiameter(2.0) },
                            enabled = plateState.proposal != null && !plateState.confirming,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.selector_plate_fine_plus)) }
                    }
                    Button(
                        onClick = onConfirmProposal,
                        enabled = plateState.canConfirm && reviewMapperReady && plateProposalVisibleInViewport && markingEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.selector_plate_confirm)) }
                    TextButton(onClick = onResetAutomation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_plate_retry))
                    }
                    TextButton(onClick = onManualCalibration, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_calibrate_manually))
                    }
                }

                EncoderPanelMode.PLATE_ASSISTED_FAILED -> {
                    Text(stringResource(R.string.selector_plate_failed), color = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(R.string.selector_plate_failed_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AccessibleVideoPointControls(
                        index = index,
                        initial = plateState.proposal?.center,
                        cursor = accessibleCursor,
                        enabled = markingEnabled && navigationEnabled,
                        onCursorChanged = onAccessibleCursorChanged,
                        onConfirm = { point -> onAccessiblePlate(point); true },
                    )
                    TextButton(onClick = onUseManualSelection, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.selector_use_manual))
                    }
                }

                EncoderPanelMode.MANUAL_CALIBRATION -> {
                    ManualEncoderCalibration(
                        draft = draft,
                        title = stringResource(R.string.selector_manual_ab_title),
                        onConfirm = onConfirmManualCalibration,
                    )
                    AccessibleVideoPointControls(
                        index = index,
                        initial = draft.calibrationPointB ?: draft.calibrationPointA,
                        cursor = accessibleCursor,
                        enabled = markingEnabled && navigationEnabled,
                        onCursorChanged = onAccessibleCursorChanged,
                        onConfirm = { onAccessibleCalibrationPoint(it) },
                    )
                }

                EncoderPanelMode.MANUAL_TARGET_SELECTION -> {
                    Text(stringResource(R.string.selector_select_track_target), fontWeight = FontWeight.SemiBold)
                    AccessibleVideoPointControls(
                        index = index,
                        initial = current?.result?.sample?.point,
                        cursor = accessibleCursor,
                        enabled = targetSelectionEnabled,
                        onCursorChanged = onAccessibleCursorChanged,
                        onConfirm = { point -> onAccessibleTarget(point); true },
                    )
                }

                EncoderPanelMode.NEEDS_MANUAL_CALIBRATION -> {
                    Text(stringResource(R.string.selector_plate_identified), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        plateState.message ?: stringResource(R.string.selector_plate_perspective),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ManualEncoderCalibration(
                        draft = draft,
                        title = stringResource(R.string.selector_manual_scale_title),
                        onConfirm = onConfirmManualCalibration,
                    )
                    AccessibleVideoPointControls(
                        index = index,
                        initial = draft.calibrationPointB ?: draft.calibrationPointA,
                        cursor = accessibleCursor,
                        enabled = markingEnabled && navigationEnabled,
                        onCursorChanged = onAccessibleCursorChanged,
                        onConfirm = { onAccessibleCalibrationPoint(it) },
                    )
                }

                EncoderPanelMode.TRACKING_INITIALIZING -> {
                    TrackingStatusContent(state)
                }

                EncoderPanelMode.TRACKING_READY -> {
                    TrackingStatusContent(state)
                    Button(
                        onClick = onStart,
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.selector_track_from_here)) }
                    OutlinedButton(
                        onClick = onResetTracking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.selector_new_selection)) }
                }

                EncoderPanelMode.TRACKING_PROCESSING -> {
                    TrackingStatusContent(state)
                    OutlinedButton(
                        onClick = onResetTracking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.selector_cancel_tracking)) }
                }

                EncoderPanelMode.TRACKING_RECOVERY -> {
                    TrackingStatusContent(state)
                    OutlinedButton(
                        onClick = onResetTracking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.selector_new_selection)) }
                }

                EncoderPanelMode.REVIEW -> {
                    EncoderCompletedActions(
                        state, exportState, onExport, onCancelExport, onSaveExport, onShareExport,
                        onRequestTargetReselection, onRequestRecalibration,
                    )
                    TechnicalDetailsDisclosure(state, plateState, current)
                }
            }
        }
    }
}

@Composable
internal fun PlateDiameterSummary(
    draft: EncoderDraft,
    enabled: Boolean,
    onChange: (Double) -> Unit,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val quantity = MeasurementQuantity.SHORT_LENGTH_CM
    val displayUnit = MeasurementFormatting.unit(quantity, unitSystem)
    val displayValue = MeasurementFormatting.formatInputValue(
        draft.sessionPlateDiameterCm,
        quantity,
        unitSystem,
        locale,
    )
    var dialogOpen by remember(draft.sessionKey) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.selector_plate_diameter_summary), style = MaterialTheme.typography.labelMedium)
            Text(
                "$displayValue ${displayUnit.symbol}",
                style = valueStyle,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(
            onClick = { dialogOpen = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.selector_plate_diameter_change)) }
    }
    if (dialogOpen) {
        var input by rememberSaveable(draft.sessionKey, stateSaver = UnitAwareNumericInputState.Saver) {
            mutableStateOf(UnitAwareNumericInputState.initial(displayValue, unitSystem, draft.sessionPlateDiameterCm))
        }
        LaunchedEffect(unitSystem) {
            input = UnitAwareNumericInputState.rebase(input, unitSystem, quantity, locale)
        }
        val text = input.text
        val value = UnitAwareNumericInputState.canonical(input, quantity, locale, unitSystem)
        val inputInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(input, quantity, locale)
        val valid = !input.requiresReview && !inputInvalid && value?.let(EncoderSettings::isValidPlateDiameterCm) == true
        val changed = value != null && value != draft.sessionPlateDiameterCm
        val rangeDecimals = MeasurementFormatting.unit(quantity, unitSystem).decimals
        val minimum = MeasurementFormatting.format(
            EncoderSettings.MINIMUM_PLATE_DIAMETER_CM,
            quantity,
            unitSystem,
            locale,
            decimals = rangeDecimals,
        )
        val maximum = MeasurementFormatting.format(
            EncoderSettings.MAXIMUM_PLATE_DIAMETER_CM,
            quantity,
            unitSystem,
            locale,
            decimals = rangeDecimals,
        )
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.selector_plate_diameter_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.selector_plate_diameter_dialog_body))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value -> input = UnitAwareNumericInputState.edited(value, unitSystem, quantity, locale) },
                        label = { Text(stringResource(R.string.selector_plate_diameter)) },
                        suffix = { Text(displayUnit.symbol) },
                        singleLine = true,
                        isError = input.requiresReview || inputInvalid || text.isNotBlank() && !valid,
                        supportingText = when {
                            input.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                            inputInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                            text.isNotBlank() && !valid -> { { Text(stringResource(R.string.settings_plate_range_error_values, minimum, maximum)) } }
                            else -> null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(requireNotNull(value))
                        dialogOpen = false
                    },
                    enabled = valid && changed,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.common_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
internal fun ManualEncoderCalibration(
    draft: EncoderDraft,
    title: String,
    onConfirm: (Double) -> Unit,
) {
    val unitSystem = LocalUnitSystem.current
    val locale = currentAppLocale()
    val quantity = MeasurementQuantity.DISTANCE_M
    val displayUnit = MeasurementFormatting.unit(quantity, unitSystem)
    val defaultLengthM = 0.45
    val defaultLengthText = MeasurementFormatting.formatInputValue(
        defaultLengthM,
        quantity,
        unitSystem,
        locale,
    )
    var lengthInput by rememberSaveable(draft.sessionKey, title, stateSaver = UnitAwareNumericInputState.Saver) {
        mutableStateOf(UnitAwareNumericInputState.initial(defaultLengthText, unitSystem, defaultLengthM))
    }
    LaunchedEffect(unitSystem) {
        lengthInput = UnitAwareNumericInputState.rebase(lengthInput, unitSystem, quantity, locale)
    }
    var calibrationError by remember { mutableStateOf<String?>(null) }
    val lengthText = lengthInput.text
    val length = UnitAwareNumericInputState.canonical(lengthInput, quantity, locale, unitSystem)
    val lengthInvalid = UnitAwareNumericInputState.hasInvalidCurrentValue(lengthInput, quantity, locale)
    Text(title, fontWeight = FontWeight.SemiBold)
    Text(
        when {
            draft.calibrationPointA == null -> stringResource(R.string.selector_touch_reference_a)
            draft.calibrationPointB == null -> stringResource(R.string.selector_touch_reference_b)
            else -> stringResource(R.string.selector_enter_reference_length)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f
        val field: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = lengthText,
                onValueChange = { value -> lengthInput = UnitAwareNumericInputState.edited(value, unitSystem, quantity, locale); calibrationError = null },
                label = { Text(stringResource(R.string.selector_length)) },
                suffix = { Text(displayUnit.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = modifier,
                isError = lengthInput.requiresReview || lengthInvalid,
                supportingText = when {
                    lengthInput.requiresReview -> { { Text(stringResource(R.string.unit_input_review_required)) } }
                    lengthInvalid -> { { Text(stringResource(R.string.unit_input_invalid)) } }
                    else -> null
                },
            )
        }
        val confirm: @Composable (Modifier) -> Unit = { modifier ->
            Button(
                onClick = {
                    try {
                        onConfirm(requireNotNull(length))
                    } catch (error: Exception) {
                        calibrationError = error.message
                    }
                },
                enabled = draft.calibrationPointA != null && draft.calibrationPointB != null && length != null && length > 0.0,
                modifier = modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.common_confirm)) }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            field(Modifier.fillMaxWidth()); confirm(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            field(Modifier.weight(1f)); confirm(Modifier)
        }
    }
    calibrationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun TechnicalDetailsDisclosure(
    tracking: TrackingUiState,
    plate: PlateDetectionUiState,
    current: TrackingFrameResult?,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (expanded) R.string.selector_hide_technical_details else R.string.selector_show_technical_details))
    }
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ShapeTokens.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                plate.detection?.let { detection ->
                    Text(
                        stringResource(
                            R.string.selector_technical_plate,
                            detection.center.x,
                            detection.center.y,
                            detection.diameterPx,
                            (detection.confidence * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                current?.let { frame ->
                    val point = frame.result.sample.point
                    Text(
                        stringResource(
                            R.string.selector_technical_sample,
                            Formatting.usToClock(frame.result.sample.timestampUs),
                            point?.x ?: Double.NaN,
                            point?.y ?: Double.NaN,
                            (frame.result.sample.confidence * 100).roundToInt(),
                            frame.result.diagnostics.inlierFeatures,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (current == null && plate.detection == null) {
                    Text(stringResource(R.string.selector_technical_empty), style = MaterialTheme.typography.bodySmall)
                }
                if (tracking.results.isNotEmpty()) {
                    Text(
                        stringResource(R.string.selector_technical_samples, tracking.results.size),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EncoderCompletedActions(
    state: TrackingUiState,
    exportState: TrajectoryExportUiState,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onSaveExport: () -> Unit,
    onShareExport: () -> Unit,
    onRequestTargetReselection: () -> Unit,
    onRequestRecalibration: () -> Unit,
) {
    if (state.phase != TrackingPhase.COMPLETED && state.phase != TrackingPhase.LOST) return
    Text(
        state.message.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.selector_review_instruction),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = onRequestTargetReselection, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.selector_change_target))
        }
        TextButton(onClick = onRequestRecalibration, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.selector_change_scale))
        }
    }
    TrajectoryExportControls(
        state = exportState,
        hasTrajectory = state.results.any { it.result.sample.point != null },
        onExport = onExport,
        onCancel = onCancelExport,
        onSave = onSaveExport,
        onShare = onShareExport,
    )
}

@Composable
private fun TrajectoryExportControls(
    state: TrajectoryExportUiState,
    hasTrajectory: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    when (state.phase) {
        TrajectoryExportPhase.IDLE -> {
            OutlinedButton(
                onClick = onExport,
                enabled = hasTrajectory,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.selector_export_trajectory))
            }
        }

        TrajectoryExportPhase.ERROR -> {
            state.message?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onExport,
                enabled = hasTrajectory,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.selector_export_trajectory))
            }
        }

        TrajectoryExportPhase.EXPORTING, TrajectoryExportPhase.VALIDATING -> {
            Text(
                state.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val progress = state.progressPercent
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.selector_cancel_export))
            }
        }

        TrajectoryExportPhase.READY,
        TrajectoryExportPhase.SAVING,
        TrajectoryExportPhase.SAVED,
        -> {
            Text(
                state.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = state.phase != TrajectoryExportPhase.SAVING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.phase == TrajectoryExportPhase.SAVING) {
                                R.string.common_saving
                            } else {
                                R.string.selector_save_mp4
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onShare,
                    enabled = state.canUseFile && state.phase != TrajectoryExportPhase.SAVING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.common_share)) }
            }
            TextButton(
                onClick = onExport,
                enabled = state.phase != TrajectoryExportPhase.SAVING,
            ) { Text(stringResource(R.string.selector_regenerate)) }
        }
    }
}

@Composable
private fun TrackingStatusContent(state: TrackingUiState) {
    Text(stringResource(R.string.selector_tracking_title), fontWeight = FontWeight.SemiBold)
    Text(
        when (state.phase) {
            TrackingPhase.IDLE -> stringResource(R.string.selector_tracking_idle)
            TrackingPhase.INITIALIZING -> stringResource(R.string.selector_tracking_initializing)
            TrackingPhase.READY -> state.message ?: stringResource(R.string.selector_tracking_ready)
            TrackingPhase.PROCESSING -> stringResource(
                R.string.selector_tracking_progress,
                (state.progress * 100).roundToInt(),
                state.results.size,
            )
            TrackingPhase.COMPLETED, TrackingPhase.LOST, TrackingPhase.ERROR -> state.message.orEmpty()
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.phase == TrackingPhase.PROCESSING) {
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        )
    }
}

private enum class EventRowState { MARKED, NEXT, PENDING }

@Composable
private fun EventSection(
    requiredEvents: List<EventType>,
    events: Map<EventKey, com.openjump.app.protocol.EventMark>,
    completedEvents: Int,
    labels: Map<EventType, String>,
    canJump: Boolean,
    canReplace: Boolean,
    onJump: (EventType) -> Unit,
    onReplace: (EventType) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.medium,
    ) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.selector_events_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.selector_events_count, completedEvents, requiredEvents.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            requiredEvents.forEachIndexed { index, type ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val mark = events[EventKey(type)]
                val state = when {
                    mark != null -> EventRowState.MARKED
                    index == completedEvents -> EventRowState.NEXT
                    else -> EventRowState.PENDING
                }
                EventRow(
                    ordinal = index + 1,
                    label = labels.getValue(type),
                    state = state,
                    timeUs = mark?.ptsUs,
                    canJump = canJump,
                    canReplace = canReplace,
                    onJump = { onJump(type) },
                    onReplace = { onReplace(type) },
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    ordinal: Int,
    label: String,
    state: EventRowState,
    timeUs: Long?,
    canJump: Boolean,
    canReplace: Boolean,
    onJump: () -> Unit,
    onReplace: () -> Unit,
) {
    val timestamp = timeUs?.let(Formatting::usToClockMs)
    val rowDescription = timestamp?.let {
        stringResource(R.string.selector_event_go_description, label, it)
    }
    val editDescription = stringResource(R.string.selector_event_change_description, label)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state == EventRowState.MARKED) "✓" else ordinal.toString(),
            modifier = Modifier.padding(horizontal = Spacing.xs),
            color = if (state == EventRowState.MARKED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.Bold,
        )
        if (state == EventRowState.MARKED && timestamp != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = canJump, onClick = onJump)
                    .semantics {
                        contentDescription = rowDescription.orEmpty()
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.selector_event_marked, timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.selector_event_go),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(
                onClick = onReplace,
                enabled = canReplace,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = editDescription },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        if (state == EventRowState.NEXT) {
                            R.string.selector_event_next
                        } else {
                            R.string.selector_event_pending
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == EventRowState.NEXT) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (state == EventRowState.NEXT) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
