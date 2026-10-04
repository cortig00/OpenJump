package com.openjump.app.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openjump.app.R
import com.openjump.app.OpenJumpApp
import com.openjump.app.camera.CameraFinalizingExitPolicy
import com.openjump.app.camera.CameraProductProfile
import com.openjump.app.camera.JumpCameraController
import com.openjump.app.camera.JumpCameraState
import com.openjump.app.camera.RecordingCadencePolicy
import com.openjump.app.data.anthropometricsSnapshotFor
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.QuickAthletePicker
import com.openjump.app.ui.components.VideoPreparationOverlay
import com.openjump.app.ui.labs.ExperimentalBadge
import com.openjump.app.video.AppSession
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    onSaved: (Uri, Long?) -> Unit,
    onBack: () -> Unit,
    captureGuidance: String,
    jumpFlowStep: JumpFlowStep? = null,
) {
    val context = LocalContext.current
    val viewportOrientation = LocalConfiguration.current.orientation
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as OpenJumpApp
    val activeAthletes by app.athleteRepository.active().collectAsState(initial = emptyList())
    val activeDraft by AppSession.draft.collectAsState()
    val bilateralSession by AppSession.bilateral.collectAsState()
    val activeEncoderDraft by AppSession.encoderDraft.collectAsState()
    val seriesTargetAttempts by AppSession.seriesTargetAttempts.collectAsState()
    val pickerScope = rememberCoroutineScope()
    val roster by AppSession.testingParticipantIds.collectAsState()
    val settings = remember { CameraSettings(context) }
    var availableProfiles by remember { mutableStateOf(emptyList<CameraProductProfile>()) }
    var unavailableFps by remember { mutableStateOf(emptySet<Int>()) }
    val availableFps = remember(availableProfiles) {
        availableProfiles.map { it.fps }.filter { it in CameraSettings.PRODUCT_FPS }.distinct().sortedDescending()
    }
    var selectedFps by remember {
        mutableStateOf(CameraSettings.normalizePreferredFps(settings.preferredFps, emptyList()))
    }
    var activeProfile by remember { mutableStateOf<CameraProductProfile?>(null) }
    val mode = activeProfile
    val hasHighSpeed = remember(availableProfiles) { availableProfiles.any { it.fps >= 120 } }

    val storagePermissionRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val requiredPermissions = remember(storagePermissionRequired) {
        buildList {
            add(Manifest.permission.CAMERA)
            if (storagePermissionRequired) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    val initiallyGranted = remember(requiredPermissions) {
        requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    var permissionsGranted by remember { mutableStateOf(initiallyGranted) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var pipelineState by remember { mutableStateOf(if (initiallyGranted) JumpCameraState.BINDING else JumpCameraState.BLOCKED) }
    var error by remember { mutableStateOf<String?>(null) }
    var continuityWarning by remember { mutableStateOf<String?>(null) }
    var recordedDurationNanos by remember { mutableStateOf(0L) }
    // UI-only timestamp: the selector can honor the same feedback threshold after navigation.
    val recordingStoppedAtElapsedMs = remember { mutableStateOf<Long?>(null) }
    var showFinalizingDiscardDialog by remember { mutableStateOf(false) }
    var showRecordingGuide by remember { mutableStateOf(false) }
    var showCaptureHint by remember { mutableStateOf(false) }
    val isJumpCamera = activeDraft != null && activeEncoderDraft == null
    LaunchedEffect(pipelineState, isJumpCamera) {
        showCaptureHint = !isJumpCamera && pipelineState == JumpCameraState.READY
    }
    LaunchedEffect(showCaptureHint, pipelineState) {
        if (showCaptureHint && pipelineState == JumpCameraState.READY) {
            delay(6_000L)
            showCaptureHint = false
        }
    }
    LaunchedEffect(permissionsGranted, pipelineState, isJumpCamera) {
        if (
            shouldOfferJumpRecordingGuide(
                isJump = isJumpCamera,
                permissionsGranted = permissionsGranted,
                state = pipelineState,
                seen = settings.hasSeenJumpCameraGuide,
            )
        ) {
            showRecordingGuide = true
        }
    }

    SideEffect {
        activity?.applyRequestedOrientation(
            CameraOrientationPolicy.forState(pipelineState),
        )
    }

    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnBack by rememberUpdatedState(onBack)
    val recorder = remember {
        JumpCameraController(
            context,
            object : JumpCameraController.Listener {
                override fun onStateChanged(state: JumpCameraState) {
                    pipelineState = state
                    if (state == JumpCameraState.STARTING || state == JumpCameraState.READY) {
                        recordedDurationNanos = 0L
                    }
                    if (RecordingCadencePolicy.shouldClearWarning(state)) continuityWarning = null
                    // A rebind supersedes any previous preview failure: drop the stale
                    // message while BINDING/READY negotiate the new graph. Never clear on
                    // ERROR itself: onError arrives after onStateChanged(ERROR), so that
                    // would wipe the fresh failure.
                    if (shouldClearStaleCameraError(state)) error = null
                }
                override fun onProfilesChanged(profiles: List<CameraProductProfile>) {
                    availableProfiles = profiles
                }
                override fun onUnavailableFpsChanged(fps: Set<Int>) {
                    unavailableFps = fps
                }
                override fun onProfileChanged(profile: CameraProductProfile?) {
                    activeProfile = profile
                }
                override fun onStarted() { pipelineState = JumpCameraState.RECORDING }
                override fun onRecordingDuration(durationNanos: Long) {
                    recordedDurationNanos = durationNanos
                }
                override fun onSaved(uri: Uri) = currentOnSaved(uri, recordingStoppedAtElapsedMs.value)
                override fun onWarning(requestedFps: Int, observedFps: Double, gapCount: Int) {
                    continuityWarning = context.getString(
                        R.string.camera_cadence_degraded,
                        requestedFps,
                        observedFps,
                        gapCount,
                    )
                }
                override fun onError(message: String) { error = message }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val allGranted = requiredPermissions.all { permission ->
            grants[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        permissionsGranted = allGranted
        if (!allGranted) recorder.pause()
    }

    fun requestRequiredPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    LaunchedEffect(requiredPermissions) {
        if (!permissionsGranted) requestRequiredPermissions()
    }

    // System Back while FINALIZING needs the same explicit confirmation as the button:
    // the recording is already stopped, so leaving must not silently discard the take.
    // ON_STOP preserves the in-flight publication inside the controller and parks its outcome,
    // so navigation callbacks only fire while this screen is present.
    BackHandler(enabled = pipelineState == JumpCameraState.FINALIZING) {
        showFinalizingDiscardDialog = true
    }

    LaunchedEffect(lifecycleStarted, pipelineState) {
        if (lifecycleStarted && pipelineState == JumpCameraState.FINALIZING) {
            recorder.deliverParkedOutcomeOnReturn()
        }
    }

    // configChanges preserves this screen; a quarter-turn must still rebind CameraX so its
    // attached Preview stream is negotiated for the new viewport aspect ratio.
    LaunchedEffect(
        permissionsGranted,
        selectedFps,
        lifecycleStarted,
        lifecycleOwner,
        viewportOrientation,
    ) {
        if (permissionsGranted && lifecycleStarted) {
            recorder.configure(lifecycleOwner, selectedFps)
        } else if (!permissionsGranted) {
            recorder.pause()
        }
    }

    DisposableEffect(lifecycleOwner, recorder, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleStarted = true
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    recorder.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recorder.dispose()
            activity?.applyRequestedOrientation(CameraOrientationPolicy.afterDispose())
        }
    }

    val takeActive = pipelineState in setOf(
        JumpCameraState.STARTING,
        JumpCameraState.RECORDING,
        JumpCameraState.FINALIZING,
    )
    val view = LocalView.current
    DisposableEffect(view, takeActive) {
        view.keepScreenOn = takeActive
        onDispose { view.keepScreenOn = false }
    }

    val recordingTime = CameraRecordingClock.format(recordedDurationNanos)
    val hud = CameraHudPolicy.forState(pipelineState)
    val recordControl = CameraRecordControlPolicy.forState(pipelineState)
    val canRecord = recordControl.canRecord
    val canStop = recordControl.canStop
    val rejectedFpsText = unavailableFps.sorted().joinToString(", ")
    val runtimePreviewWarning = when {
        rejectedFpsText.isEmpty() -> null
        selectedFps == CameraSettings.AUTO && mode != null && mode.fps !in unavailableFps ->
            stringResource(R.string.camera_preview_runtime_auto_fallback, rejectedFpsText, mode.fps)
        selectedFps in unavailableFps ->
            stringResource(R.string.camera_preview_runtime_unavailable, rejectedFpsText)
        else -> null
    }
    val contextWarnings = cameraContextWarnings(
        runtimePreviewWarning, continuityWarning, mode, selectedFps, hasHighSpeed,
    )
    val showQuickAthletePicker = roster.isNotEmpty() &&
        (activeDraft?.testingSessionId != null || activeEncoderDraft?.testingSessionId != null) &&
        activeAthletes.any { athlete ->
            athlete.archivedAt == null && athlete.id in roster
        }
    val progressText = if (activeDraft != null) {
        val targetAttempts = bilateralSession?.targetAttempts ?: seriesTargetAttempts
        val resource = when (CameraRecordControlPolicy.guidancePhase(pipelineState)) {
            CameraRecordGuidancePhase.BEFORE_RECORDING -> if (bilateralSession != null) {
                R.plurals.camera_bilateral_series_before_recording
            } else {
                R.plurals.camera_series_before_recording
            }
            CameraRecordGuidancePhase.RECORDING -> if (bilateralSession != null) {
                R.plurals.camera_bilateral_series_recording
            } else {
                R.plurals.camera_series_recording
            }
        }
        pluralStringResource(resource, targetAttempts, targetAttempts)
    } else {
        null
    }

    fun selectFps(value: Int) {
        selectedFps = value
        continuityWarning = null
        settings.preferredFps = value
    }

    fun leaveCamera() {
        if (CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(pipelineState)) {
            showFinalizingDiscardDialog = true
            return
        }
        recorder.pause()
        currentOnBack()
    }

    fun toggleRecording() {
        when {
            canStop -> {
                recordingStoppedAtElapsedMs.value = SystemClock.elapsedRealtime()
                recorder.stopRecording()
            }
            canRecord -> {
                recordingStoppedAtElapsedMs.value = null
                activity?.applyRequestedOrientation(CameraOrientationMode.LOCKED)
                recorder.startRecording()
            }
        }
    }

    val previewContent: @Composable (Modifier, Boolean, Int, Int) -> Unit =
        { modifier, padForStatusBar, topStartOffsetDp, topEndOffsetDp ->
        Box(modifier = modifier) {
            AndroidView(
                factory = { recorder.previewView },
                modifier = Modifier.fillMaxSize(),
            )
            VideoPreparationOverlay(active = pipelineState == JumpCameraState.FINALIZING)
            if (showQuickAthletePicker && !takeActive) {
                QuickAthletePicker(
                    athletes = activeAthletes,
                    currentAthleteId = activeDraft?.athleteId ?: activeEncoderDraft?.athleteId,
                    allowedAthleteIds = roster,
                    enabled = true,
                    singleLine = true,
                    onAthleteSelected = { athleteId ->
                        pickerScope.launch {
                            val testingId = activeDraft?.testingSessionId ?: activeEncoderDraft?.testingSessionId
                            if (testingId != null && app.testingRepository.setCurrentAthlete(testingId, athleteId)) {
                                AppSession.reassignActiveAthlete(
                                    athleteId,
                                    activeAthletes.anthropometricsSnapshotFor(athleteId),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .then(if (padForStatusBar) Modifier.statusBarsPadding() else Modifier)
                        .padding(
                            start = Spacing.sm,
                            end = Spacing.sm,
                            top = if (topStartOffsetDp == 0) Spacing.sm else topStartOffsetDp.dp,
                            bottom = Spacing.sm,
                        ),
                )
            }

            if (activeEncoderDraft != null && !takeActive) {
                ExperimentalBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .then(if (padForStatusBar) Modifier.statusBarsPadding() else Modifier)
                        .padding(
                            start = Spacing.sm,
                            end = Spacing.sm,
                            top = if (topEndOffsetDp == 0) Spacing.sm else topEndOffsetDp.dp,
                            bottom = Spacing.sm,
                        ),
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Box(modifier = Modifier.fillMaxSize()) {
                previewContent(
                    Modifier.fillMaxSize(),
                    true,
                    if (showQuickAthletePicker) 80 else 0,
                    if (activeEncoderDraft != null) 80 else 0,
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                ) {}

                CameraBackControl(
                    onBack = ::leaveCamera,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                        .padding(start = Spacing.sm, top = Spacing.sm),
                )
                if (hud.showStep) {
                    CameraStepBadge(
                        step = jumpFlowStep,
                        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                            .padding(start = 64.dp, top = Spacing.sm),
                    )
                }
                if (hud.showStatus) {
                    CameraCaptureStatus(
                        state = pipelineState,
                        recordingTime = recordingTime,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                            .padding(top = Spacing.sm),
                    )
                }
                if (hud.showRecordControl) {
                    LandscapeRecordControl(
                        canStop = canStop,
                        canRecord = canRecord,
                        onClick = ::toggleRecording,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacing.xl),
                    )
                }
                if (hud.showFps) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                            .padding(start = Spacing.lg, bottom = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CameraQuickFpsSelector(
                            selectedFps, mode?.fps, availableFps, unavailableFps, ::selectFps,
                        )
                        CameraHintButton(
                            onClick = {
                                if (isJumpCamera) showRecordingGuide = true
                                else showCaptureHint = !showCaptureHint
                            },
                            descriptionRes = if (isJumpCamera) R.string.jump_recording_guide_open
                                else R.string.camera_capture_tips,
                        )
                    }
                }
                if (hud.showGuidance && (if (isJumpCamera) contextWarnings.isNotEmpty() else showCaptureHint)) {
                    CameraHintOverlay(
                        guidance = if (isJumpCamera) "" else captureGuidance,
                        progress = if (isJumpCamera) null else progressText,
                        warnings = contextWarnings,
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                            .padding(start = Spacing.lg, bottom = 80.dp).widthIn(max = 320.dp),
                    )
                }
                val blockingMessage = error ?: when {
                    !permissionsGranted -> stringResource(R.string.camera_state_no_permission)
                    availableProfiles.isEmpty() && pipelineState in setOf(
                        JumpCameraState.BLOCKED, JumpCameraState.ERROR,
                    ) -> stringResource(R.string.camera_modes_query_empty)
                    else -> null
                }
                blockingMessage?.let { message ->
                    LandscapeBlockingError(
                        message = message,
                        permissionsGranted = permissionsGranted,
                        onRetry = {
                            error = null
                            if (permissionsGranted) recorder.retry(lifecycleOwner, selectedFps)
                            else requestRequiredPermissions()
                        },
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.48f),
                    )
                }
            }
        } else {
            val portraitMetrics = CameraPortraitOverlayPolicy.calculate(
                widthDp = maxWidth.value,
                heightDp = maxHeight.value,
                fontScale = LocalDensity.current.fontScale,
                hasQuickAthletePicker = showQuickAthletePicker,
                hasExperimentalBadge = activeEncoderDraft != null,
            )
            val blockingMessage = error ?: when {
                !permissionsGranted -> stringResource(R.string.camera_state_no_permission)
                availableProfiles.isEmpty() && pipelineState in setOf(
                    JumpCameraState.BLOCKED, JumpCameraState.ERROR,
                ) -> stringResource(R.string.camera_modes_query_empty)
                else -> null
            }

            Box(modifier = Modifier.fillMaxSize()) {
                previewContent(
                    Modifier.fillMaxSize(),
                    true,
                    if (showQuickAthletePicker) portraitMetrics.quickAthleteTopOffsetDp.toInt() else 0,
                    if (activeEncoderDraft != null) {
                        portraitMetrics.experimentalBadgeTopOffsetDp.toInt()
                    } else {
                        0
                    },
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {}

                CameraBackControl(
                    onBack = ::leaveCamera,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                        .padding(start = Spacing.sm, top = Spacing.xs),
                )
                if (hud.showStep) {
                    CameraStepBadge(
                        step = jumpFlowStep,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                            .padding(end = portraitMetrics.topOverlayEndPaddingDp.dp, top = Spacing.sm)
                            .widthIn(max = portraitMetrics.topOverlayWidthDp.dp),
                    )
                }
                if (hud.showStatus) {
                    CameraCaptureStatus(
                        state = pipelineState,
                        recordingTime = recordingTime,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                            .padding(top = 64.dp),
                    )
                }

                blockingMessage?.let { message ->
                    PortraitBlockingError(
                        message = message,
                        permissionsGranted = permissionsGranted,
                        onRetry = {
                            error = null
                            if (permissionsGranted) recorder.retry(lifecycleOwner, selectedFps)
                            else requestRequiredPermissions()
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .zIndex(1f)
                            .width(portraitMetrics.bottomOverlayWidthDp.dp)
                            .padding(horizontal = portraitMetrics.horizontalPaddingDp.dp),
                    )
                }

                if (hud.showGuidance && (if (isJumpCamera) contextWarnings.isNotEmpty() else showCaptureHint)) {
                    CameraHintOverlay(
                        guidance = if (isJumpCamera) "" else captureGuidance,
                        progress = if (isJumpCamera) null else progressText,
                        warnings = contextWarnings,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(horizontal = portraitMetrics.horizontalPaddingDp.dp)
                            .padding(bottom = 168.dp)
                            .widthIn(max = portraitMetrics.bottomOverlayWidthDp.dp),
                    )
                }
                if (hud.showFps) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(bottom = if (portraitMetrics.compactControls) 88.dp else 100.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CameraQuickFpsSelector(
                            selectedFps, mode?.fps, availableFps, unavailableFps, ::selectFps,
                        )
                        CameraHintButton(
                            onClick = {
                                if (isJumpCamera) showRecordingGuide = true
                                else showCaptureHint = !showCaptureHint
                            },
                            descriptionRes = if (isJumpCamera) R.string.jump_recording_guide_open
                                else R.string.camera_capture_tips,
                        )
                    }
                }
                if (hud.showRecordControl) {
                    CameraRecordButton(
                        canStop = canStop,
                        canRecord = canRecord,
                        onClick = ::toggleRecording,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(bottom = Spacing.sm)
                            .size(portraitMetrics.recordButtonSizeDp.dp),
                    )
                }
            }
        }
    }

    if (showRecordingGuide && isJumpCamera && !takeActive) {
        JumpRecordingGuideDialog {
            settings.hasSeenJumpCameraGuide = true
            showRecordingGuide = false
        }
    }

    if (showFinalizingDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showFinalizingDiscardDialog = false },
            title = { Text(stringResource(R.string.camera_finalizing_discard_title)) },
            text = { Text(stringResource(R.string.camera_finalizing_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinalizingDiscardDialog = false
                        recorder.discardFinalizingAfterConfirm()
                        currentOnBack()
                    },
                ) {
                    Text(
                        stringResource(R.string.camera_finalizing_discard_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalizingDiscardDialog = false }) {
                    Text(stringResource(R.string.camera_finalizing_discard_wait))
                }
            },
        )
    }
}

@Composable
private fun CameraBackControl(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.common_back),
            )
        }
    }
}

@Composable
private fun CameraStepBadge(step: JumpFlowStep?, modifier: Modifier = Modifier) {
    step ?: return
    Surface(
        modifier = modifier,
        shape = ShapeTokens.full,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
    ) {
        Text(
            text = stringResource(
                R.string.camera_portrait_step,
                step.ordinal + 1,
                JumpFlowStep.entries.size,
                stringResource(R.string.jump_flow_video),
            ),
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun cameraContextWarnings(
    runtimeWarning: String?,
    continuityWarning: String?,
    mode: CameraProductProfile?,
    selectedFps: Int,
    hasHighSpeed: Boolean,
): List<String> {
    val fallback = when {
        runtimeWarning != null -> null
        selectedFps == CameraSettings.AUTO && mode?.fps == 30 ->
            stringResource(R.string.camera_fps_auto_fallback, 30)
        selectedFps != CameraSettings.AUTO && mode != null && mode.fps != selectedFps ->
            stringResource(R.string.camera_fps_fallback, selectedFps, mode.fps)
        mode?.fps == 60 && !hasHighSpeed -> stringResource(R.string.camera_standard_only)
        else -> null
    }
    val highSpeed = mode?.sessionKind?.takeIf {
        it == com.openjump.app.camera.CameraSessionKind.HIGH_SPEED
    }?.let { stringResource(R.string.camera_high_speed) }
    return listOfNotNull(runtimeWarning, continuityWarning, fallback, highSpeed)
}

@Composable
private fun CameraCaptureStatus(
    state: JumpCameraState,
    recordingTime: String,
    modifier: Modifier = Modifier,
) {
    val isRecording = state == JumpCameraState.RECORDING
    Surface(
        modifier = modifier,
        shape = ShapeTokens.full,
        color = if (isRecording) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (isRecording) {
                Text(
                    "●",
                    modifier = Modifier.clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                text = cameraStateText(state),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (isRecording) {
                Text(
                    text = recordingTime,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LandscapeRecordControl(
    canStop: Boolean,
    canRecord: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(88.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CameraRecordButton(
                canStop = canStop,
                canRecord = canRecord,
                onClick = onClick,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun LandscapeBlockingError(
    message: String,
    permissionsGranted: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onRetry) {
                Text(
                    stringResource(
                        if (permissionsGranted) R.string.camera_retry
                        else R.string.camera_retry_permission,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PortraitBlockingError(
    message: String,
    permissionsGranted: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onRetry) {
                Text(
                    stringResource(
                        if (permissionsGranted) R.string.camera_retry
                        else R.string.camera_retry_permission,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CameraRecordButton(
    canStop: Boolean,
    canRecord: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        when {
            canStop -> R.string.camera_stop_recording
            canRecord -> R.string.camera_start_recording
            else -> R.string.camera_preparing
        },
    )
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.semantics {
            contentDescription = description
            if (!canStop && !canRecord) disabled()
        },
        containerColor = when {
            canStop -> MaterialTheme.colorScheme.error
            canRecord -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            if (canStop) "■" else if (canRecord) "●" else "…",
            color = if (canStop || canRecord) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

/**
 * A rebind supersedes a previous preview failure: the stale message must not
 * linger next to the BINDING spinner as if it were current. READY clears for
 * the same reason. Every other state (notably ERROR, whose onError callback
 * lands after onStateChanged) keeps the fresh message intact.
 */
internal fun shouldClearStaleCameraError(state: JumpCameraState): Boolean =
    state == JumpCameraState.BINDING || state == JumpCameraState.READY

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.applyRequestedOrientation(mode: CameraOrientationMode) {
    val requested = when (mode) {
        CameraOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        CameraOrientationMode.FULL_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        CameraOrientationMode.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }
    if (requestedOrientation != requested) requestedOrientation = requested
}

@Composable
private fun cameraStateText(state: JumpCameraState): String = when (state) {
    JumpCameraState.BLOCKED -> stringResource(R.string.camera_state_no_permission)
    JumpCameraState.BINDING,
    JumpCameraState.STARTING,
    -> stringResource(R.string.camera_state_starting)
    JumpCameraState.FINALIZING -> stringResource(R.string.camera_state_finalizing)
    JumpCameraState.READY -> stringResource(R.string.camera_state_ready)
    JumpCameraState.RECORDING -> stringResource(R.string.camera_state_recording)
    JumpCameraState.ERROR -> stringResource(R.string.camera_state_error)
}
