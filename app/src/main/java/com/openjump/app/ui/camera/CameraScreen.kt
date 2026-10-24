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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsStartWidth
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openjump.app.R
import com.openjump.app.OpenJumpApp
import com.openjump.app.camera.CameraFinalizingExitPolicy
import com.openjump.app.camera.CameraProductProfile
import com.openjump.app.camera.CameraProfilePolicy
import com.openjump.app.camera.CameraResolutionPreference
import com.openjump.app.camera.JumpCameraController
import com.openjump.app.camera.JumpCameraState
import com.openjump.app.camera.RecordingCadencePolicy
import com.openjump.app.data.anthropometricsSnapshotFor
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.RecordingFpsPolicy
import com.openjump.app.ui.components.JumpFlowStep
import com.openjump.app.ui.components.QuickAthletePicker
import com.openjump.app.ui.components.VideoPreparationOverlay
import com.openjump.app.ui.labs.ExperimentalBadge
import com.openjump.app.video.AppSession
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    onSaved: (Uri, Long?) -> Unit,
    onBack: () -> Unit,
    captureGuidance: String,
    jumpFlowStep: JumpFlowStep? = null,
    onImportVideo: (Uri) -> Unit = {},
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
    // Saved FPS defaults live in Settings, independently per recording use.
    // Encoder capture reads the Encoder default; Jump (or no draft) reads the Jumps default.
    val recordingUse = remember(activeEncoderDraft) {
        RecordingFpsPolicy.useFor(activeEncoderDraft != null)
    }
    var availableProfiles by remember { mutableStateOf(emptyList<CameraProductProfile>()) }
    var unavailableFps by remember { mutableStateOf(emptySet<Int>()) }
    // Transient per-recording/visit override seeded from the saved default.
    // Changing it here must never write back to Settings; defaults change only via Settings.
    var selectedFps by rememberCameraFpsSelection(settings, recordingUse)
    // Transient per-visit resolution, always AUTO on entry; never persisted.
    var selectedResolution by rememberCameraResolutionSelection(recordingUse)
    var activeProfile by remember { mutableStateOf<CameraProductProfile?>(null) }
    val mode = activeProfile
    val hasHighSpeed = remember(availableProfiles) { availableProfiles.any { it.fps >= 120 } }

    // Alternativa a la camara: importar un video sin salir del estado de error.
    // Ver F-032.
    val importVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onImportVideo(uri) }

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
    // Hidden on every visit: no auto-popup, timer or preference writes.
    var showCaptureHint by rememberCameraInformationVisibility()
    val isJumpCamera = activeDraft != null && activeEncoderDraft == null

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
        selectedResolution,
        recordingUse,
        lifecycleStarted,
        lifecycleOwner,
        viewportOrientation,
    ) {
        if (permissionsGranted && lifecycleStarted) {
            recorder.configure(lifecycleOwner, selectedFps, selectedResolution)
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
    LaunchedEffect(takeActive) {
        if (takeActive) showCaptureHint = false
    }
    val view = LocalView.current
    // Camera chrome is intentionally dark in both themes. Keep system icons readable,
    // and restore the current app theme when leaving (including theme changes in-place).
    val appBarColor by rememberUpdatedState(MaterialTheme.colorScheme.background.toArgb())
    val appBarIconsLight by rememberUpdatedState(!com.openjump.app.ui.theme.OpenJumpTheme.isDark)
    DisposableEffect(activity, view) {
        val window = activity?.window
        val previousContrast = if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        onDispose {
            window?.let {
                @Suppress("DEPRECATION")
                it.statusBarColor = appBarColor
                @Suppress("DEPRECATION")
                it.navigationBarColor = appBarColor
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = appBarIconsLight
                    isAppearanceLightNavigationBars = appBarIconsLight
                }
                if (Build.VERSION.SDK_INT >= 29 && previousContrast != null) it.isNavigationBarContrastEnforced = previousContrast
            }
        }
    }
    SideEffect {
        activity?.window?.let {
            @Suppress("DEPRECATION")
            it.statusBarColor = CameraChrome.surface.toArgb()
            @Suppress("DEPRECATION")
            it.navigationBarColor = CameraChrome.surface.toArgb()
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            if (Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
        }
    }
    DisposableEffect(view, takeActive) {
        view.keepScreenOn = takeActive
        onDispose { view.keepScreenOn = false }
    }

    val recordingTime = CameraRecordingClock.format(recordedDurationNanos)
    val hud = CameraHudPolicy.forState(pipelineState)
    val recordControl = CameraRecordControlPolicy.forState(pipelineState)
    // ERROR recovery: quick FPS/resolution controls stay visible (no REC, no active
    // caption) so a manual impossible tuple can be changed to Auto/supported before
    // retry. BLOCKED without camera permission stays hidden via this guard; an
    // already-Auto request with a fully blocked catalog still goes through retry
    // without silent FPS/resolution switches or cache resets.
    val showCaptureControls = hud.showFps && permissionsGranted
    val errorRecoveryActiveProfile = if (pipelineState == JumpCameraState.ERROR) null else mode
    // Scoped quality controls: FPS choices are valid only with the selected resolution,
    // resolution choices only with the exact selected FPS (Auto FPS keeps 120/60/240/30
    // order inside the chosen resolution via policy). Impossible tuples stay visible but
    // disabled so a manual request never silently switches FPS or resolution; Auto is
    // always enabled as recovery. Usable profiles come from the controller snapshot,
    // already filtered by the process-session incompatibility cache.
    val fpsChoices = remember { CameraSettings.PRODUCT_FPS.sortedDescending() }
    val fpsUnavailableForResolution = remember(availableProfiles, selectedResolution) {
        fpsChoices.filter { fps ->
            CameraProfilePolicy.candidates(availableProfiles, fps, selectedResolution).isEmpty()
        }.toSet()
    }
    val isResolutionEnabled: (CameraResolutionPreference) -> Boolean = remember(availableProfiles, selectedFps) {
        { resolution ->
            if (resolution == CameraResolutionPreference.AUTO) true
            else CameraProfilePolicy.candidates(availableProfiles, selectedFps, resolution).isNotEmpty()
        }
    }
    // REC requires READY plus a bound profile still compatible with the current request.
    // Between a manual change and the rebind there is one frame with the old profile:
    // starting then would record the old tuple. Auto accepts any FPS in the resolution.
    val isActiveCompatible = remember(mode, selectedFps, selectedResolution) {
        CameraProfilePolicy.isCompatible(mode, selectedFps, selectedResolution)
    }
    val canRecord = recordControl.canRecord && isActiveCompatible
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
        // Temporary per-visit override only: keep the saved Settings default untouched.
        // Never silently change resolution: an impossible tuple stays disabled in the UI.
        selectedFps = value
        continuityWarning = null
    }

    fun selectResolution(value: CameraResolutionPreference) {
        // Transient per-visit override only: never persisted, never touches Settings.
        // Never silently change FPS: an impossible tuple stays disabled in the UI.
        selectedResolution = value
        continuityWarning = null
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
        // Re-read the current request at click time: the captured canRecord may predate
        // the latest selector change by one frame and still reference the old tuple.
        val compatibleNow = CameraProfilePolicy.isCompatible(mode, selectedFps, selectedResolution)
        when {
            canStop -> {
                recordingStoppedAtElapsedMs.value = SystemClock.elapsedRealtime()
                recorder.stopRecording()
            }
            canRecord && compatibleNow -> {
                recordingStoppedAtElapsedMs.value = null
                activity?.applyRequestedOrientation(CameraOrientationMode.LOCKED)
                recorder.startRecording()
            }
        }
    }

    val blockingTitle = when {
        error != null -> stringResource(R.string.camera_preview_error)
        !permissionsGranted -> stringResource(R.string.camera_state_no_permission)
        availableProfiles.isEmpty() && pipelineState in setOf(JumpCameraState.BLOCKED, JumpCameraState.ERROR) ->
            stringResource(R.string.camera_modes_query_empty)
        else -> null
    }
    // El mensaje crudo de la cámara se conserva solo tras "Detalle técnico": nunca
    // como texto principal (evita jerga tipo "CameraX" en pantalla). Ver F-001.
    val blockingDetail = error
    val informationDescription = if (isJumpCamera) R.string.jump_recording_guide_open else R.string.camera_capture_tips
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Full-frame FIT preview and CameraX are unchanged; only presentation slots move.
        AndroidView(factory = { recorder.previewView }, modifier = Modifier.fillMaxSize())
        // System-bar/cutout scrims only, not a toolbar/panel over the captured frame.
        Surface(Modifier.align(Alignment.CenterStart).fillMaxHeight()
            .windowInsetsStartWidth(WindowInsets.safeDrawing), color = CameraChrome.surface) {}
        Surface(Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            .windowInsetsEndWidth(WindowInsets.safeDrawing), color = CameraChrome.surface) {}
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars), color = CameraChrome.surface) {}
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars), color = CameraChrome.surface) {}
        CameraViewfinderHud(
            showControls = showCaptureControls,
            onBack = ::leaveCamera,
            onInformation = { showCaptureHint = !showCaptureHint },
            informationDescriptionRes = informationDescription,
            informationExpanded = showCaptureHint,
            controls = { includeInformation ->
                CameraCaptureControlsGroup(
                    selectedFps = selectedFps,
                    activeFps = errorRecoveryActiveProfile?.fps,
                    fpsChoices = fpsChoices,
                    previewUnavailableFps = unavailableFps,
                    scopedUnavailableFps = fpsUnavailableForResolution,
                    selectedResolution = selectedResolution,
                    isResolutionEnabled = isResolutionEnabled,
                    activeProfile = errorRecoveryActiveProfile,
                    onFpsSelect = ::selectFps,
                    onResolutionSelect = ::selectResolution,
                    onHintClick = { showCaptureHint = !showCaptureHint },
                    hintDescriptionRes = informationDescription,
                    includeInformationButton = includeInformation,
                    informationExpanded = showCaptureHint,
                )
            },
            information = if (shouldShowCameraInformation(showCaptureHint, permissionsGranted, pipelineState)) ({
                CameraHintOverlay(
                    guidance = captureGuidance, progress = progressText, warnings = contextWarnings,
                    profile = errorRecoveryActiveProfile, onDismiss = { showCaptureHint = false },
                ) {
                    if (isJumpCamera) {
                        Text(stringResource(R.string.jump_recording_guide_body), style = MaterialTheme.typography.bodySmall)
                        listOf(R.string.jump_recording_guide_stable, R.string.jump_recording_guide_framing,
                            R.string.jump_recording_guide_headroom, R.string.jump_recording_guide_lighting,
                        ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    }
                    CameraStepBadge(jumpFlowStep)
                    if (activeEncoderDraft != null) ExperimentalBadge()
                    // Preserve existing testing-session athlete switching, now on demand.
                    if (showQuickAthletePicker) {
                        QuickAthletePicker(
                            athletes = activeAthletes,
                            currentAthleteId = activeDraft?.athleteId ?: activeEncoderDraft?.athleteId,
                            allowedAthleteIds = roster, enabled = true, singleLine = true,
                            onAthleteSelected = { athleteId ->
                                pickerScope.launch {
                                    val testingId = activeDraft?.testingSessionId ?: activeEncoderDraft?.testingSessionId
                                    if (testingId != null && app.testingRepository.setCurrentAthlete(testingId, athleteId)) {
                                        AppSession.reassignActiveAthlete(athleteId,
                                            activeAthletes.anthropometricsSnapshotFor(athleteId))
                                    }
                                }
                            },
                        )
                    }
                }
            }) else null,
            record = if (hud.showRecordControl) ({
                CameraRecordButton(canStop, canRecord, ::toggleRecording, Modifier.fillMaxSize())
            }) else null,
            status = if (hud.showStatus) ({ CameraCaptureStatus(pipelineState, recordingTime) }) else null,
            blockingError = blockingTitle?.let { title ->
                {
                    if (landscape) LandscapeBlockingError(
                        title, blockingDetail, permissionsGranted,
                        onRetry = {
                            error = null
                            if (permissionsGranted) recorder.retry(lifecycleOwner, selectedFps, selectedResolution)
                            else requestRequiredPermissions()
                        },
                        onImportVideo = {
                            importVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                    ) else PortraitBlockingError(
                        title, blockingDetail, permissionsGranted,
                        onRetry = {
                            error = null
                            if (permissionsGranted) recorder.retry(lifecycleOwner, selectedFps, selectedResolution)
                            else requestRequiredPermissions()
                        },
                        onImportVideo = {
                            importVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                    )
                }
            },
        )
        VideoPreparationOverlay(active = pipelineState == JumpCameraState.FINALIZING)
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
internal fun CameraBackControl(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp).testTag("camera-back-control"),
        shape = CircleShape,
        color = CameraChrome.surface,
        contentColor = CameraChrome.content,
        border = BorderStroke(1.dp, CameraChrome.outline),
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
private fun LandscapeBlockingError(
    title: String,
    detail: String?,
    permissionsGranted: Boolean,
    onRetry: () -> Unit,
    onImportVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    TextButton(onClick = { showDetail = !showDetail }) {
                        Text(stringResource(R.string.camera_preview_error_detail), maxLines = 1)
                    }
                    if (showDetail) {
                        Text(
                            text = detail,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Button(onClick = onRetry) {
                Text(
                    stringResource(
                        if (permissionsGranted) R.string.camera_retry
                        else R.string.camera_retry_permission,
                    ),
                    maxLines = 1,
                )
            }
            TextButton(onClick = onImportVideo) {
                Text(stringResource(R.string.common_import_video), maxLines = 1)
            }
        }
    }
}

@Composable
private fun PortraitBlockingError(
    title: String,
    detail: String?,
    permissionsGranted: Boolean,
    onRetry: () -> Unit,
    onImportVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }
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
                text = title,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                TextButton(onClick = { showDetail = !showDetail }) {
                    Text(stringResource(R.string.camera_preview_error_detail), maxLines = 1)
                }
                if (showDetail) {
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(onClick = onRetry) {
                Text(
                    stringResource(
                        if (permissionsGranted) R.string.camera_retry
                        else R.string.camera_retry_permission,
                    ),
                    maxLines = 1,
                )
            }
            TextButton(onClick = onImportVideo) {
                Text(stringResource(R.string.common_import_video), maxLines = 1)
            }
        }
    }
}

@Composable
internal fun CameraRecordButton(
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
    Surface(
        modifier = modifier.size(88.dp)
            .clickable(enabled = canStop || canRecord, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = CameraChrome.surface,
        border = BorderStroke(3.dp, CameraChrome.content.copy(alpha = if (canStop || canRecord) 0.92f else 0.4f)),
    ) {
        Box(Modifier.padding(6.dp).background(
            CameraChrome.recording.copy(alpha = if (canStop || canRecord) 1f else 0.38f), CircleShape,
        ), contentAlignment = Alignment.Center) {
            when {
                canStop -> Box(Modifier.size(24.dp).background(CameraChrome.content, ShapeTokens.extraSmall))
                canRecord -> Box(Modifier.size(8.dp).background(CameraChrome.content, CircleShape))
                else -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp), color = CameraChrome.content, strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * F033 production information gate: the on-demand tips panel is available only when the
 * user asked for it, permission-gated quick controls are visible, and no take is active.
 * READY and ERROR expose it (both keep quick controls); STARTING/RECORDING/FINALIZING hide
 * it and force-close any open panel; BLOCKED/BINDING have no quick controls so it stays hidden.
 */
internal fun shouldShowCameraInformation(
    showCaptureHint: Boolean,
    permissionsGranted: Boolean,
    state: JumpCameraState,
): Boolean {
    if (!showCaptureHint || !permissionsGranted) return false
    if (state == JumpCameraState.STARTING ||
        state == JumpCameraState.RECORDING ||
        state == JumpCameraState.FINALIZING
    ) return false
    return CameraHudPolicy.forState(state).showFps
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
