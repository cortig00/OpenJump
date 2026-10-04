package com.openjump.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.openjump.app.R
import com.openjump.app.video.RecordedVideoPublisher
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CameraX-only runtime route for preview, recording, readiness, and publication. */
class JumpCameraController(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: JumpCameraState)
        fun onProfilesChanged(profiles: List<CameraProductProfile>) = Unit
        fun onUnavailableFpsChanged(fps: Set<Int>) = Unit
        fun onProfileChanged(profile: CameraProductProfile?) = Unit
        fun onStarted() = Unit
        /** CameraX recording time for the on-screen counter; output timing still comes from PTS. */
        fun onRecordingDuration(durationNanos: Long) = Unit
        fun onSaved(uri: Uri)
        fun onWarning(requestedFps: Int, observedFps: Double, gapCount: Int) = Unit
        fun onError(message: String)
    }

    val previewView: PreviewView = PreviewView(context).apply {
        // TextureView/COMPATIBLE makes STREAMING a meaningful visible-preview signal on OEM HS HALs.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // Keep the full uncropped frame; the landscape UI reserves a left control rail
        // sized so the remaining preview is close to the camera's 16:9 aspect ratio.
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fallbackGeneration = AtomicLong(0)
    private val machine = JumpCameraStateMachine()
    private val readiness = PreviewReadinessCoordinator<CameraProductProfile>()
    private val previewTimeoutHandler = Handler(Looper.getMainLooper())
    private var previewObserver: Observer<PreviewView.StreamState>? = null
    private var previewTimeout: Runnable? = null
    private var savedDelivered = false
    private var readinessGeneration = 0L
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var discovery: CameraXProfileCatalog.Discovery? = null
    private var activeProfile: CameraProductProfile? = null
    private var recording: Recording? = null
    private var recordingFile: File? = null
    private var finalizing = false
    private var finalizeStarted = false
    private var backgrounded = false
    private var parkedResult: PublishResult? = null
    /** Private cache file backing a parked private fallback (recordingFile is cleared on park). */
    private var parkedPrivateFile: File? = null
    private var parkedError: String? = null
    /** Generation-scoped discard consent: only the consented stale token may delete its own URI. */
    private var explicitDiscardToken: Long? = null
    private var disposed = false

    private companion object {
        const val PREVIEW_START_TIMEOUT_MILLIS = 1_500L
    }

    fun configure(owner: LifecycleOwner, preferredFps: Int) {
        if (disposed || machine.state in setOf(
                JumpCameraState.STARTING,
                JumpCameraState.RECORDING,
                JumpCameraState.FINALIZING,
            )) return
        this.owner = owner
        cancelPreviewReadiness()
        cancelRecording(deleteFile = true)
        // A new configure invalidates the old graph before any asynchronous
        // discovery/binding callback can fail and otherwise leave its mode visible.
        currentRecorder = null
        currentVideoCapture = null
        activeProfile = null
        listener.onProfileChanged(null)
        val token = machine.beginBinding()
        notifyState(JumpCameraState.BINDING)
        unbindSafely()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!machine.isCurrent(token)) return@addListener
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                fail(token, context.getString(R.string.camera_query_error, context.getString(R.string.camera_backend_camerax)))
                return@addListener
            }
            provider = cameraProvider
            val found = runCatching { CameraXProfileCatalog.discover(cameraProvider) }.getOrNull()
            if (found == null) {
                fail(token, context.getString(R.string.camera_query_error, context.getString(R.string.camera_backend_camerax)))
                return@addListener
            }
            val qualified = qualifyProfiles(found)
            discovery = found.copy(profiles = qualified)
            listener.onProfilesChanged(qualified)
            listener.onUnavailableFpsChanged(CameraPreviewIncompatibilityCache.unavailableFps(qualified))
            val candidates = candidateOrder(qualified, preferredFps)
            readinessGeneration = readiness.begin(candidates)
            awaitPreviewIdleThenBind(token, readinessGeneration, candidates, 0)
        }, mainExecutor)
    }

    fun retry(owner: LifecycleOwner, preferredFps: Int) = configure(owner, preferredFps)

    fun startRecording() {
        if (disposed || machine.state != JumpCameraState.READY || finalizing) return
        val token = machine.generation
        val profile = activeProfile ?: run {
            fail(token, context.getString(R.string.camera_record_start_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        if (!machine.beginRecording(token)) {
            fail(token, context.getString(R.string.camera_record_start_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        notifyState(JumpCameraState.STARTING)
        val file = File(
            context.cacheDir,
            "camera_recordings/jump_${System.currentTimeMillis()}_${fallbackGeneration.incrementAndGet()}.mp4",
        )
        file.parentFile?.mkdirs()
        recordingFile = file
        savedDelivered = false
        val recorder = currentRecorder ?: run {
            fail(token, context.getString(R.string.camera_recorder_prepare_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        val videoCapture = currentVideoCapture ?: run {
            fail(token, context.getString(R.string.camera_recorder_prepare_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        // Capture the visible display orientation once, before this PendingRecording is created.
        previewView.display?.rotation?.let(videoCapture::setTargetRotation)
        val pending: PendingRecording = runCatching {
            // PendingRecording defaults to video-only; do not request RECORD_AUDIO.
            recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
        }.getOrElse {
            fail(token, context.getString(R.string.camera_recorder_prepare_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        recording = runCatching {
            pending.start(mainExecutor) { event -> handleEvent(token, profile, event) }
        }.getOrElse {
            fail(token, context.getString(R.string.camera_record_start_error, context.getString(R.string.camera_backend_camerax)))
            null
        }
    }

    fun stopRecording() {
        if (disposed || finalizing) return
        val token = machine.generation
        val active = recording ?: run {
            fail(token, context.getString(R.string.camera_record_invalid))
            return
        }
        val profile = activeProfile ?: run {
            fail(token, context.getString(R.string.camera_record_invalid))
            return
        }
        if (machine.state != JumpCameraState.RECORDING && machine.state != JumpCameraState.STARTING) return
        finalizing = true
        finalizeStarted = false
        if (!machine.beginFinalize(token)) {
            fail(token, context.getString(R.string.camera_record_invalid))
            return
        }
        notifyState(JumpCameraState.FINALIZING)
        runCatching { active.stop() }.onFailure {
            // CameraX normally emits Finalize; this path is still terminal and idempotent.
            finalizeFile(token, profile, VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR)
        }
    }

    /**
     * Conservative exit: a take already stopped (FINALIZING) is never discarded silently.
     * Voluntary exit must confirm explicitly via [discardFinalizingAfterConfirm]; an unexpected
     * background transition preserves the in-flight publication and parks its outcome for
     * delivery on return instead of navigating while the screen is absent.
     */
    fun pause() {
        if (disposed) return
        if (CameraFinalizingExitPolicy.mustPreserveOnBackground(machine.state)) {
            preserveFinalizingOnBackground()
            return
        }
        cancelPreviewReadiness()
        machine.cancel()
        backgrounded = false
        // A parked private fallback has no consumer once the screen leaves: delete it so no
        // cache orphan remains. A parked consolidated URI stays in the gallery (explicit
        // confirmation is required to remove it). explicitDiscardToken is preserved so an
        // in-flight finalizer still observes the discard consent.
        runCatching { parkedPrivateFile?.delete() }
        parkedPrivateFile = null
        parkedResult = null
        parkedError = null
        finalizing = false
        finalizeStarted = false
        recording?.let { runCatching { it.close() } }
        recording = null
        runCatching { recordingFile?.delete() }
        recordingFile = null
        activeProfile = null
        listener.onProfileChanged(null)
        notifyState(JumpCameraState.BLOCKED)
    }

    /** Detaches preview observers while keeping the stopped take publishable. Main thread only. */
    private fun preserveFinalizingOnBackground() {
        backgrounded = true
        clearPreviewReadiness()
    }

    /**
     * Delivers a publication parked while backgrounded. Must be called when the screen is
     * foreground again so navigation callbacks never fire while the screen is absent.
     * Returns true when a parked outcome was delivered.
     */
    fun deliverParkedOutcomeOnReturn(): Boolean {
        if (disposed) return false
        backgrounded = false
        val token = machine.generation
        val result = parkedResult
        if (result != null) {
            if (!machine.isCurrent(token) || machine.state != JumpCameraState.FINALIZING) {
                // No consumer can show this take: the gallery entry stays (removing it needs
                // explicit confirmation) but a private fallback is deleted so no orphan remains.
                runCatching { parkedPrivateFile?.delete() }
                parkedPrivateFile = null
                parkedResult = null
                return false
            }
            parkedResult = null
            parkedError = null
            // Ownership of the private file transfers with the delivered Uri; it must stay.
            parkedPrivateFile = null
            emitSavedResult(token, result)
            return true
        }
        val error = parkedError
        if (error != null) {
            if (!machine.isCurrent(token)) {
                parkedError = null
                return false
            }
            parkedError = null
            finalizing = false
            recordingFile = null
            val terminalized = machine.terminal(token, false) || machine.error(token)
            if (!terminalized && machine.state == JumpCameraState.ERROR) return true
            notifyState(JumpCameraState.ERROR)
            listener.onError(error)
            return true
        }
        return false
    }

    /**
     * Explicit user-confirmed discard of a FINALIZING take. This is the only path allowed to
     * remove an already-consolidated MediaStore URI, because the confirmation dialog is the
     * required notice. Publisher pending rows roll back via shouldCancel; no orphan files remain.
     */
    fun discardFinalizingAfterConfirm() {
        if (disposed) return
        if (machine.state != JumpCameraState.FINALIZING && parkedResult == null && parkedError == null) return
        // Capture the consented generation before cancel invalidates it: only this take's
        // stale finalizer may consume the confirmation.
        val consentedGeneration = machine.generation
        // Capture this before clearing: a parked outcome means its finalizer already finished,
        // so no stale callback remains that needs the consent signal. A delivered take
        // (savedDelivered with preview still FINALIZING) also leaves no stale publisher.
        val hadParkedOutcome = parkedResult != null || parkedError != null
        parkedResult?.let { parked ->
            if (!parked.privateSource) {
                runCatching { context.contentResolver.delete(parked.uri, null, null) }
            }
        }
        // recordingFile was already cleared when the outcome parked; the parked private
        // fallback must be deleted here or its cache file orphans.
        runCatching { parkedPrivateFile?.delete() }
        parkedPrivateFile = null
        parkedResult = null
        parkedError = null
        backgrounded = false
        val consent = CameraFinalizingExitPolicy.consentTokenForDiscard(
            generation = consentedGeneration,
            finalizeStarted = finalizeStarted,
            hadParkedOutcome = hadParkedOutcome,
            savedDelivered = savedDelivered,
        )
        machine.cancel()
        cancelPreviewReadiness()
        finalizing = false
        // Retain consent only for the consented in-flight generation; a delivered or parked
        // take clears it so the next take never inherits this confirmation.
        explicitDiscardToken = consent
        finalizeStarted = false
        recording?.let { runCatching { it.close() } }
        recording = null
        runCatching { recordingFile?.delete() }
        recordingFile = null
        activeProfile = null
        listener.onProfileChanged(null)
        notifyState(JumpCameraState.BLOCKED)
    }

    fun resume(owner: LifecycleOwner, preferredFps: Int) = configure(owner, preferredFps)

    fun dispose() {
        if (disposed) return
        disposed = true
        clearPreviewReadiness()
        readiness.dispose()
        machine.dispose()
        recording?.let { runCatching { it.close() } }
        recording = null
        // Parked outcomes have no consumer after dispose: a consolidated MediaStore URI stays
        // in the gallery (never deleted without explicit confirmation) while a parked private
        // file is deleted so no cache orphan remains.
        runCatching { parkedPrivateFile?.delete() }
        parkedPrivateFile = null
        parkedResult = null
        parkedError = null
        // A FINALIZING take owns its private file until the finalizer parks or cleans it up:
        // deleting here would race the in-flight copy and orphan/void the parked fallback.
        // A consolidated MediaStore URI is never deleted without explicit user confirmation.
        val preserveFile = finalizeStarted
        if (!preserveFile) runCatching { recordingFile?.delete() }
        recordingFile = null
        unbindSafely()
        provider = null
        // Keep the small finalizer alive long enough to roll back a URI created just before dispose.
    }

    private var currentRecorder: Recorder? = null
    private var currentVideoCapture: VideoCapture<Recorder>? = null

    private fun qualifyProfiles(discovery: CameraXProfileCatalog.Discovery): List<CameraProductProfile> =
        discovery.profiles.filter { profile ->
            runCatching {
                val graph = buildGraph(profile, discovery.cameraInfo)
                discovery.cameraInfo.isSessionConfigSupported(graph.sessionConfig) &&
                    discovery.cameraInfo.getSupportedFrameRateRanges(graph.sessionConfig)
                        .contains(graph.range)
            }.getOrDefault(false)
        }

    private fun candidateOrder(
        profiles: List<CameraProductProfile>,
        preferredFps: Int,
    ): List<CameraProductProfile> = CameraProfilePolicy.candidates(
        CameraPreviewIncompatibilityCache.filter(profiles),
        preferredFps,
    )

    private fun bindFirstSupported(
        token: Long,
        candidates: List<CameraProductProfile>,
        index: Int,
    ) {
        if (!machine.isCurrent(token)) return
        if (index >= candidates.size) {
            fail(token, context.getString(R.string.camera_mode_no_longer_available))
            return
        }
        val found = discovery ?: run {
            fail(token, context.getString(R.string.camera_query_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        val candidate = candidates[index]
        val graph = runCatching { buildGraph(candidate, found.cameraInfo) }.getOrNull()
        val graphSupported = graph != null && runCatching {
            found.cameraInfo.isSessionConfigSupported(graph.sessionConfig) &&
                found.cameraInfo.getSupportedFrameRateRanges(graph.sessionConfig)
                    .contains(graph.range)
        }.getOrDefault(false)
        if (!graphSupported) {
            if (!readiness.skipCandidate(readinessGeneration, index)) {
                fail(token, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
                return
            }
            bindFirstSupported(token, candidates, index + 1)
            return
        }
        val candidateToken = readiness.bindCandidate(readinessGeneration, index) ?: run {
            fail(token, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        val bound = runCatching {
            found.cameraInfo // The same rear CameraInfo used for capability checks and binding.
            provider?.bindToLifecycle(requireNotNull(owner), found.selector, graph.sessionConfig)
        }.getOrNull()
        if (bound == null) {
            // CameraX may have partially installed a graph before throwing. Reset it before retry.
            val decision = readiness.onBindFailure(readinessGeneration, candidateToken)
            clearPreviewReadiness()
            unbindSafely()
            currentRecorder = null
            currentVideoCapture = null
            activeProfile = null
            listener.onProfileChanged(null)
            if (decision is PreviewReadinessCoordinator.Decision.Ignored) {
                fail(token, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            } else {
                advanceAfterPreviewFailure(token, candidates, decision)
            }
            return
        }
        currentRecorder = graph.recorder
        currentVideoCapture = graph.videoCapture
        activeProfile = candidate
        listener.onProfileChanged(candidate)
        if (machine.bound(token)) {
            awaitPreviewStream(token, readinessGeneration, candidateToken, candidates, candidate)
        } else {
            clearPreviewReadiness()
            unbindSafely()
            currentRecorder = null
            currentVideoCapture = null
            activeProfile = null
            listener.onProfileChanged(null)
            fail(token, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
        }
    }

    private fun awaitPreviewIdleThenBind(
        machineToken: Long,
        generation: Long,
        candidates: List<CameraProductProfile>,
        index: Int,
    ) {
        if (!machine.isCurrent(machineToken)) return
        if (!readiness.requireIdle(generation)) {
            fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            return
        }
        lateinit var observer: Observer<PreviewView.StreamState>
        val timeout = Runnable {
            if (previewObserver !== observer || !machine.isCurrent(machineToken)) return@Runnable
            val decision = readiness.onIdleTimeout(generation)
            clearPreviewReadiness()
            if (decision is PreviewReadinessCoordinator.Decision.Error) {
                fail(
                    machineToken,
                    context.getString(
                        R.string.camera_preview_error,
                        context.getString(R.string.camera_backend_camerax),
                    ),
                )
            } else if (decision is PreviewReadinessCoordinator.Decision.Ignored) {
                fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            }
        }
        observer = Observer { streamState ->
            if (previewObserver !== observer || !machine.isCurrent(machineToken)) return@Observer
            if (streamState != PreviewView.StreamState.IDLE) return@Observer
            if (!readiness.onIdle(generation)) {
                clearPreviewReadiness()
                fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
                return@Observer
            }
            clearPreviewReadiness()
            bindFirstSupported(machineToken, candidates, index)
        }
        previewTimeout = timeout
        previewObserver = observer
        previewView.previewStreamState.observeForever(observer)
        if (previewObserver === observer) previewTimeoutHandler.postDelayed(timeout, PREVIEW_START_TIMEOUT_MILLIS)
    }

    private fun awaitPreviewStream(
        machineToken: Long,
        generation: Long,
        candidateToken: Long,
        candidates: List<CameraProductProfile>,
        profile: CameraProductProfile,
    ) {
        if (!machine.isCurrent(machineToken)) return
        lateinit var observer: Observer<PreviewView.StreamState>
        val timeout = Runnable {
            if (previewObserver !== observer || !machine.isCurrent(machineToken)) return@Runnable
            val decision = readiness.onTimeout(generation, candidateToken)
            if (decision !is PreviewReadinessCoordinator.Decision.Ignored &&
                CameraPreviewIncompatibilityCache.reject(profile)
            ) {
                discovery?.profiles?.let {
                    listener.onUnavailableFpsChanged(CameraPreviewIncompatibilityCache.unavailableFps(it))
                }
            }
            clearPreviewReadiness()
            unbindSafely()
            currentRecorder = null
            currentVideoCapture = null
            activeProfile = null
            listener.onProfileChanged(null)
            if (decision is PreviewReadinessCoordinator.Decision.Ignored) {
                fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            } else {
                advanceAfterPreviewFailure(machineToken, candidates, decision)
            }
        }
        observer = Observer { streamState ->
            if (previewObserver !== observer || !machine.isCurrent(machineToken)) return@Observer
            if (streamState != PreviewView.StreamState.STREAMING) return@Observer
            if (!readiness.onStreaming(generation, candidateToken)) {
                clearPreviewReadiness()
                unbindSafely()
                currentRecorder = null
                currentVideoCapture = null
                activeProfile = null
                listener.onProfileChanged(null)
                fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
                return@Observer
            }
            clearPreviewReadiness()
            if (machine.streaming(machineToken)) {
                notifyState(JumpCameraState.READY)
            } else {
                fail(machineToken, context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
            }
        }
        previewTimeout = timeout
        previewObserver = observer
        previewView.previewStreamState.observeForever(observer)
        // observeForever can synchronously deliver an already-streaming value.
        if (previewObserver === observer && readiness.state == PreviewReadinessCoordinator.State.WAITING_FOR_STREAM) {
            previewTimeoutHandler.postDelayed(timeout, PREVIEW_START_TIMEOUT_MILLIS)
        }
    }

    private fun advanceAfterPreviewFailure(
        machineToken: Long,
        candidates: List<CameraProductProfile>,
        decision: PreviewReadinessCoordinator.Decision<CameraProductProfile>,
    ) {
        if (!machine.isCurrent(machineToken)) return
        when (decision) {
            is PreviewReadinessCoordinator.Decision.Retry -> awaitPreviewIdleThenBind(
                machineToken,
                readinessGeneration,
                candidates,
                decision.index,
            )
            PreviewReadinessCoordinator.Decision.Error -> fail(
                machineToken,
                context.getString(
                    R.string.camera_preview_error,
                    context.getString(R.string.camera_backend_camerax),
                ),
            )
            PreviewReadinessCoordinator.Decision.Ignored -> fail(
                machineToken,
                context.getString(
                    R.string.camera_preview_error,
                    context.getString(R.string.camera_backend_camerax),
                ),
            )
        }
    }

    private data class Graph(
        val recorder: Recorder,
        val videoCapture: VideoCapture<Recorder>,
        val sessionConfig: SessionConfig,
        val range: Range<Int>,
        val constraints: CameraXGraphConstraints,
    ) {
        init {
            require(!constraints.allowsUseCaseTargetFrameRate)
            require(constraints.usesQualitySelectorForVideo)
        }
    }

    private data class PublishResult(
        val uri: Uri,
        val privateSource: Boolean,
        val requestedFps: Int,
        val observedFps: Double,
        val gapCount: Int,
    )

    @SuppressLint("RestrictedApi")
    private fun buildGraph(profile: CameraProductProfile, cameraInfo: androidx.camera.core.CameraInfo): Graph =
        when (profile.sessionKind) {
            CameraSessionKind.NORMAL -> buildNormalGraph(profile)
            CameraSessionKind.HIGH_SPEED -> buildHighSpeedGraph(profile)
        }

    private fun buildRecorder(profile: CameraProductProfile): Recorder {
        val quality = when (profile.quality) {
            "HD" -> Quality.HD
            "FHD" -> Quality.FHD
            else -> throw IllegalArgumentException()
        }
        return Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .setExecutor(mainExecutor)
            .build()
    }

    /** Normal sessions keep the requested preview size; their only FPS constraint is SessionConfig. */
    @SuppressLint("RestrictedApi")
    private fun buildNormalGraph(profile: CameraProductProfile): Graph {
        val range = Range(profile.fps, profile.fps)
        val recorder = buildRecorder(profile)
        val video = VideoCapture.Builder(recorder).build()
        val preview = Preview.Builder()
            .setTargetResolution(Size(profile.width, profile.height))
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        return Graph(
            recorder = recorder,
            videoCapture = video,
            sessionConfig = SessionConfig(listOf(preview, video), null, emptyList(), range),
            range = range,
            constraints = CameraXGraphPolicy.NORMAL,
        )
    }

    /**
     * HS restrictions are deliberate: no target FPS/resolution/aspect on either use case.
     * QualitySelector is the sole VideoCapture quality input and the HS SessionConfig owns FPS.
     */
    private fun buildHighSpeedGraph(profile: CameraProductProfile): Graph {
        val range = Range(profile.fps, profile.fps)
        val recorder = buildRecorder(profile)
        val video = VideoCapture.Builder(recorder).build()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        return Graph(
            recorder = recorder,
            videoCapture = video,
            sessionConfig = HighSpeedVideoSessionConfig(video, preview, range, false),
            range = range,
            constraints = CameraXGraphPolicy.HIGH_SPEED,
        )
    }

    private fun handleEvent(
        token: Long,
        profile: CameraProductProfile,
        event: VideoRecordEvent,
    ) {
        if (!machine.isCurrent(token) || disposed) return
        when (event) {
            is VideoRecordEvent.Start -> {
                when {
                    machine.started(token) -> {
                        notifyState(JumpCameraState.RECORDING)
                        listener.onStarted()
                    }
                    machine.state in setOf(
                        JumpCameraState.FINALIZING,
                        JumpCameraState.READY,
                        JumpCameraState.ERROR,
                    ) -> Unit // Stop/finalization won the race; this Start is obsolete.
                    else -> fail(token, context.getString(R.string.camera_record_start_error, context.getString(R.string.camera_backend_camerax)))
                }
            }
            is VideoRecordEvent.Status -> {
                if (machine.state == JumpCameraState.RECORDING) {
                    listener.onRecordingDuration(event.recordingStats.recordedDurationNanos)
                }
            }
            is VideoRecordEvent.Finalize -> {
                if (machine.state in setOf(JumpCameraState.STARTING, JumpCameraState.RECORDING)) {
                    finalizing = true
                    if (!machine.beginFinalize(token)) {
                        fail(token, context.getString(R.string.camera_record_invalid))
                        return
                    }
                    notifyState(JumpCameraState.FINALIZING)
                }
                if (machine.state == JumpCameraState.FINALIZING) {
                    finalizeFile(token, profile, event.error)
                }
            }
        }
    }

    private fun finalizeFile(token: Long, profile: CameraProductProfile, error: Int) {
        if (!machine.isCurrent(token) || disposed || finalizeStarted) return
        finalizeStarted = true
        if (!finalizing) finalizing = true
        val source = recordingFile
        recording?.let { runCatching { it.close() } }
        recording = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (source == null) return@withContext null
                val index = runCatching { VideoFrameIndexer.indexFromPath(source.absolutePath) }.getOrNull()
                    ?: return@withContext null
                if (!RecordingOutputPolicy.isPublishable(
                        finalizeSucceeded = error == VideoRecordEvent.Finalize.ERROR_NONE,
                        sourceExists = source.isFile,
                        sourceBytes = source.length(),
                        frameCount = index.frameCount,
                    )
                ) return@withContext null
                val observedFps = RecordingCadencePolicy.observedFps(
                    index.temporalContinuity.medianDeltaUs,
                )
                val uri = RecordedVideoPublisher.publish(
                    context,
                    source,
                    shouldCancel = { !machine.isCurrent(token) || disposed },
                )
                if (uri != null) {
                    PublishResult(
                        uri,
                        privateSource = false,
                        requestedFps = profile.fps,
                        observedFps = observedFps,
                        gapCount = index.temporalContinuity.gapCount,
                    )
                } else if (machine.isCurrent(token) && !disposed) {
                    // Gallery failure must not discard a valid private recording.
                    PublishResult(
                        Uri.fromFile(source),
                        privateSource = true,
                        requestedFps = profile.fps,
                        observedFps = observedFps,
                        gapCount = index.temporalContinuity.gapCount,
                    )
                } else null
            }
            if (!machine.isCurrent(token) || disposed) {
                handleStalePublishResult(token, source, result)
                return@launch
            }
            if (backgrounded) {
                // The screen is absent: park instead of navigating so the take stays visible on return.
                if (result != null) {
                    parkedResult = result
                    if (result.privateSource) parkedPrivateFile = source
                } else {
                    // No publishable output: the private source would otherwise orphan once
                    // recordingFile is cleared below.
                    runCatching { source?.delete() }
                    parkedError = context.getString(R.string.camera_record_invalid)
                }
                recordingFile = null
                return@launch
            }
            finalizing = false
            recordingFile = null
            if (result != null) {
                emitSavedResult(token, result)
            } else {
                runCatching { source?.delete() }
                val terminalized = machine.terminal(token, false) || machine.error(token)
                if (!terminalized && machine.state == JumpCameraState.ERROR) return@launch
                notifyState(JumpCameraState.ERROR)
                listener.onError(context.getString(R.string.camera_record_invalid))
            }
        }
    }

    /**
     * Stale finalize ownership: never delete a consolidated MediaStore URI without explicit
     * user confirmation scoped to its own generation. Pending rows already rolled back inside
     * the publisher; private files are cleaned so no orphans remain, while preserved takes
     * park for delivery on return. Only the consented token may take the discard path, so a
     * stale result never removes another take's gallery entry.
     */
    private fun handleStalePublishResult(staleToken: Long, source: File?, result: PublishResult?) {
        when (CameraFinalizingExitPolicy.dispositionForStaleResult(
            hasResult = result != null,
            privateSource = result?.privateSource ?: false,
            explicitDiscardToken = explicitDiscardToken,
            staleToken = staleToken,
            disposed = disposed,
        )) {
            CameraFinalizingExitPolicy.StalePublishDisposition.EXPLICIT_DISCARD -> {
                result?.takeUnless { it.privateSource }?.let {
                    runCatching { context.contentResolver.delete(it.uri, null, null) }
                }
                runCatching { source?.delete() }
                runCatching { parkedPrivateFile?.delete() }
                parkedPrivateFile = null
                recordingFile = null
                parkedResult = null
                parkedError = null
                // Consume only the matching consent; another generation's token stays untouched.
                if (explicitDiscardToken != null && explicitDiscardToken == staleToken) {
                    explicitDiscardToken = null
                }
                finalizing = false
                finalizeStarted = false
                backgrounded = false
            }
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_PRIVATE,
            -> {
                // Publisher already removed the private source on MediaStore success; a private
                // fallback file must be kept because the parked Uri points to it.
                recordingFile = null
                parkedResult = result
                if (result?.privateSource == true) parkedPrivateFile = source
            }
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_ERROR -> {
                parkedError = context.getString(R.string.camera_record_invalid)
                runCatching { source?.delete() }
                recordingFile = null
            }
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY -> {
                runCatching { source?.delete() }
                recordingFile = null
            }
        }
    }

    /** Shared foreground/return delivery: warn on degraded cadence, then hand off before preview recovery. */
    private fun emitSavedResult(token: Long, result: PublishResult) {
        finalizing = false
        recordingFile = null
        if (!savedDelivered) {
            savedDelivered = true
            if (RecordingCadencePolicy.isDegraded(
                    result.requestedFps,
                    medianDeltaUs = if (result.observedFps > 0.0) {
                        (1_000_000.0 / result.observedFps).toLong()
                    } else 0L,
                    gapCount = result.gapCount,
                )
            ) {
                listener.onWarning(result.requestedFps, result.observedFps, result.gapCount)
            }
            // A valid publication is delivered before preview recovery so a
            // preview-only failure can never discard the saved outcome.
            listener.onSaved(result.uri)
        }
        awaitPreviewAfterFinalize(token)
    }

    /**
     * Finalize stops the recorder but should not claim READY until the still-current
     * preview graph is visible again. This never rebinds while handling REC.
     */
    private fun awaitPreviewAfterFinalize(token: Long) {
        if (!machine.isCurrent(token) || disposed) return
        val initialDecision = PreviewRecoveryPolicy.afterFinalize(
            previewCompatible = previewView.implementationMode == PreviewView.ImplementationMode.COMPATIBLE,
            streaming = previewView.previewStreamState.value == PreviewView.StreamState.STREAMING,
        )
        when (initialDecision) {
            PreviewRecoveryPolicy.Decision.READY -> {
                completeSavedPreview(token)
                return
            }
            PreviewRecoveryPolicy.Decision.SAVED_WITH_PREVIEW_ERROR -> {
                reportSavedPreviewFailure(token)
                return
            }
            PreviewRecoveryPolicy.Decision.WAIT_FOR_STREAM -> Unit
        }
        lateinit var observer: Observer<PreviewView.StreamState>
        val timeout = Runnable {
            if (previewObserver !== observer || !machine.isCurrent(token)) return@Runnable
            clearPreviewReadiness()
            val outcome = PreviewRecoveryPolicy.afterTimeout(savedDelivered)
            if (!outcome.ready) reportSavedPreviewFailure(token)
        }
        observer = Observer { streamState ->
            if (previewObserver !== observer || !machine.isCurrent(token)) return@Observer
            if (streamState != PreviewView.StreamState.STREAMING) return@Observer
            val decision = PreviewRecoveryPolicy.afterRecovery(
                previewCompatible = previewView.implementationMode == PreviewView.ImplementationMode.COMPATIBLE,
                streaming = true,
            )
            clearPreviewReadiness()
            if (decision == PreviewRecoveryPolicy.Decision.READY) {
                completeSavedPreview(token)
            } else {
                reportSavedPreviewFailure(token)
            }
        }
        previewTimeout = timeout
        previewObserver = observer
        previewView.previewStreamState.observeForever(observer)
        if (previewObserver === observer) {
            previewTimeoutHandler.postDelayed(timeout, PREVIEW_START_TIMEOUT_MILLIS)
        }
    }

    private fun completeSavedPreview(token: Long) {
        if (!machine.isCurrent(token) || disposed) return
        if (previewView.implementationMode != PreviewView.ImplementationMode.COMPATIBLE ||
            previewView.previewStreamState.value != PreviewView.StreamState.STREAMING
        ) {
            reportSavedPreviewFailure(token)
            return
        }
        if (!machine.terminal(token, true)) {
            reportSavedPreviewFailure(token)
            return
        }
        notifyState(JumpCameraState.READY)
    }

    private fun reportSavedPreviewFailure(token: Long) {
        if (!machine.isCurrent(token) || disposed) return
        val terminalized = machine.terminal(token, false)
        if (!terminalized && machine.state == JumpCameraState.ERROR) return
        // A rejected transition is still surfaced explicitly unless this terminal
        // error was already reported by an earlier callback.
        notifyState(JumpCameraState.ERROR)
        listener.onError(context.getString(R.string.camera_preview_error, context.getString(R.string.camera_backend_camerax)))
    }

    private fun cancelRecording(deleteFile: Boolean) {
        if (recording == null && recordingFile == null) return
        machine.cancel()
        runCatching { recording?.close() }
        recording = null
        if (deleteFile) runCatching { recordingFile?.delete() }
        recordingFile = null
        finalizing = false
        finalizeStarted = false
    }

    private fun fail(token: Long, message: String) {
        if (!machine.isCurrent(token)) return
        // The in-flight finalizer owns its file and outcome; a concurrent failure must not
        // race its copy/rollback or silently terminalize a take that is already stopped.
        if (machine.state == JumpCameraState.FINALIZING && finalizeStarted) return
        cancelPreviewReadiness()
        recording?.let { runCatching { it.close() } }
        recording = null
        runCatching { recordingFile?.delete() }
        recordingFile = null
        finalizing = false
        finalizeStarted = false
        val terminalized = machine.terminal(token, false) || machine.error(token)
        if (!terminalized && machine.state == JumpCameraState.ERROR) return
        // The callback was current but the state machine rejected terminalization;
        // still expose an explicit error instead of retaining BINDING.
        notifyState(JumpCameraState.ERROR)
        listener.onError(message)
    }

    private fun clearPreviewReadiness() {
        previewTimeout?.let(previewTimeoutHandler::removeCallbacks)
        previewTimeout = null
        val observer = previewObserver ?: return
        previewObserver = null
        val remove = Runnable { runCatching { previewView.previewStreamState.removeObserver(observer) } }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else mainExecutor.execute(remove)
    }

    private fun cancelPreviewReadiness() {
        clearPreviewReadiness()
        readiness.cancel()
    }

    private fun unbindSafely() {
        runCatching { provider?.unbindAll() }
    }

    private fun notifyState(state: JumpCameraState) = listener.onStateChanged(state)

    private fun requireNotNull(value: LifecycleOwner?): LifecycleOwner =
        value ?: throw IllegalStateException()
}
