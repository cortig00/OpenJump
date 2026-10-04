package com.openjump.app.video

import android.net.Uri
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricCalibrator
import com.openjump.app.encoder.PhysicalTimeline
import com.openjump.app.encoder.PlateDetection
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.HorizontalJumpDraft
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.protocol.VideoSource
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.VideoPresentationGeometry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal fun resolveEncoderTiming(
    currentTiming: VideoTimingDecision?,
    source: VideoSource?,
    index: VideoFrameIndex,
): VideoTimingDecision = currentTiming ?: PhysicalTimeline.automaticTimingDecision(source, index)

/** In-memory store shared by setup, capture/import, selector and result. */
object AppSession {

    private val seriesAttemptRange = 1..5

    private val _draft = MutableStateFlow<MeasurementDraft?>(null)
    val draft: StateFlow<MeasurementDraft?> = _draft.asStateFlow()

    private val _encoderDraft = MutableStateFlow<EncoderDraft?>(null)
    val encoderDraft: StateFlow<EncoderDraft?> = _encoderDraft.asStateFlow()

    private val _encoderAnalysis = MutableStateFlow<EncoderAnalysis?>(null)
    val encoderAnalysis: StateFlow<EncoderAnalysis?> = _encoderAnalysis.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private val _frameIndex = MutableStateFlow<VideoFrameIndex?>(null)
    val frameIndex: StateFlow<VideoFrameIndex?> = _frameIndex.asStateFlow()

    private val _currentFrameIndex = MutableStateFlow(0)
    val currentFrameIndex: StateFlow<Int> = _currentFrameIndex.asStateFlow()

    private val _testingParticipantIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingParticipantIds: StateFlow<Set<Long>> = _testingParticipantIds.asStateFlow()

    private val _seriesTargetAttempts = MutableStateFlow(1)
    val seriesTargetAttempts: StateFlow<Int> = _seriesTargetAttempts.asStateFlow()

    private val _seriesAttempt = MutableStateFlow(1)
    val seriesAttempt: StateFlow<Int> = _seriesAttempt.asStateFlow()

    private val _bilateral = MutableStateFlow<BilateralSessionState?>(null)
    /** In-memory only; no partial bilateral session is ever sent to Room. */
    val bilateral: StateFlow<BilateralSessionState?> = _bilateral.asStateFlow()
    private var pendingBilateralFeedback: BilateralFeedback? = null
    private var pendingSeriesFeedback: SeriesFeedback? = null
    @Volatile private var onVideoOwnershipChanged: (() -> Unit)? = null

    fun setVideoOwnershipChangedListener(listener: (() -> Unit)?) {
        onVideoOwnershipChanged = listener
    }

    private fun notifyVideoOwnershipChanged() { onVideoOwnershipChanged?.invoke() }

    fun begin(
        protocolId: ProtocolId,
        setup: ProtocolSetup = ProtocolSetup(),
        athleteId: Long,
        testingSessionId: Long? = null,
        testingParticipantIds: Set<Long> = emptySet(),
        athleteAnthropometrics: AthleteAnthropometrics = AthleteAnthropometrics(),
        targetAttempts: Int = 1,
    ) {
        require(athleteId > 0L) { "Selecciona un atleta antes de comenzar." }
        require(targetAttempts in seriesAttemptRange) { "La serie debe tener entre 1 y 5 saltos." }
        require(protocolId != ProtocolId.ASYMMETRY) {
            "ASYMMETRY se inicia con beginBilateral()."
        }
        val definition = ProtocolCatalog.find(protocolId)
        require(definition.availability == ProtocolAvailability.AVAILABLE) {
            "El protocolo todavía no está disponible."
        }
        ProtocolCatalog.validateSetup(definition, setup)?.let { error(it) }
        pendingBilateralFeedback = null
        pendingSeriesFeedback = null
        _bilateral.value = null
        _draft.value = MeasurementDraft(
            sessionKey = UUID.randomUUID().toString(),
            athleteId = athleteId,
            testingSessionId = testingSessionId,
            athleteAnthropometrics = athleteAnthropometrics,
            protocolId = protocolId,
            setup = setup,
            horizontalJump = if (protocolId == ProtocolId.HORIZONTAL) HorizontalJumpDraft() else null,
        )
        _encoderDraft.value = null
        _encoderAnalysis.value = null
        _videoUri.value = null
        _frameIndex.value = null
        _currentFrameIndex.value = 0
        _testingParticipantIds.value = testingParticipantIds
        _seriesTargetAttempts.value = targetAttempts
        _seriesAttempt.value = 1
        notifyVideoOwnershipChanged()
    }

    fun beginEncoder(
        setup: EncoderSetup,
        defaultPlateDiameterCm: Double = EncoderSettings.DEFAULT_PLATE_DIAMETER_CM,
        athleteId: Long,
        testingSessionId: Long? = null,
        testingParticipantIds: Set<Long> = emptySet(),
    ) {
        require(athleteId > 0L) { "Selecciona un atleta antes de comenzar." }
        require(EncoderSettings.isValidPlateDiameterCm(defaultPlateDiameterCm))
        _seriesTargetAttempts.value = 1
        _seriesAttempt.value = 1
        pendingBilateralFeedback = null
        pendingSeriesFeedback = null
        _bilateral.value = null
        _encoderDraft.value = EncoderDraft(
            sessionKey = UUID.randomUUID().toString(),
            athleteId = athleteId,
            testingSessionId = testingSessionId,
            setup = setup,
            sessionPlateDiameterCm = defaultPlateDiameterCm,
        )
        _draft.value = null
        _encoderAnalysis.value = null
        _videoUri.value = null
        _frameIndex.value = null
        _currentFrameIndex.value = 0
        _testingParticipantIds.value = testingParticipantIds
        notifyVideoOwnershipChanged()
    }

    fun reset() {
        pendingBilateralFeedback = null
        pendingSeriesFeedback = null
        _bilateral.value = null
        _draft.value = null
        _encoderDraft.value = null
        _encoderAnalysis.value = null
        _videoUri.value = null
        _frameIndex.value = null
        _currentFrameIndex.value = 0
        _testingParticipantIds.value = emptySet()
        _seriesTargetAttempts.value = 1
        _seriesAttempt.value = 1
        notifyVideoOwnershipChanged()
    }

    /** Starts an ephemeral bilateral comparison. Each active draft remains UNILATERAL. */
    fun beginBilateral(
        targetAttempts: Int,
        athleteId: Long,
        testingSessionId: Long? = null,
        testingParticipantIds: Set<Long> = emptySet(),
        athleteAnthropometrics: AthleteAnthropometrics = AthleteAnthropometrics(),
    ) {
        require(targetAttempts in seriesAttemptRange) {
            "La comparación debe tener entre 1 y 5 saltos por pierna."
        }
        require(testingSessionId == null) { "ASYMMETRY no está disponible en Testing." }
        require(testingParticipantIds.isEmpty()) { "ASYMMETRY no está disponible en Testing." }
        require(athleteId > 0L) { "Selecciona un atleta antes de comenzar." }
        _encoderDraft.value = null
        _encoderAnalysis.value = null
        _videoUri.value = null
        _frameIndex.value = null
        _currentFrameIndex.value = 0
        _testingParticipantIds.value = testingParticipantIds
        _seriesTargetAttempts.value = targetAttempts
        _seriesAttempt.value = 1
        val rootKey = UUID.randomUUID().toString()
        pendingBilateralFeedback = null
        pendingSeriesFeedback = null
        _bilateral.value = BilateralSessionState(
            sessionKey = rootKey,
            athleteId = athleteId,
            targetAttempts = targetAttempts,
            testingSessionId = testingSessionId,
            athleteAnthropometrics = athleteAnthropometrics,
            currentSide = MeasurementSide.LEFT,
            currentSideAttempt = 1,
        )
        _draft.value = bilateralDraft(rootKey, athleteId, testingSessionId, athleteAnthropometrics, MeasurementSide.LEFT, 1)
        notifyVideoOwnershipChanged()
    }

    /** Switches the active leg without losing partially marked events on either side. */
    fun selectBilateralSide(side: MeasurementSide): Boolean {
        val state = _bilateral.value ?: return false
        if (state.completed || state.currentSide == side || state.completedCount(side) >= state.targetAttempts) return false
        val current = checkNotNull(_draft.value)
        val previousSide = checkNotNull(state.currentSide)
        val next = state.pendingDrafts[side] ?: bilateralDraft(
            state.sessionKey, state.athleteId, state.testingSessionId, state.athleteAnthropometrics,
            side, state.completedCount(side) + 1, current.source, current.videoUri,
        )
        _bilateral.value = state.copy(
            currentSide = side,
            currentSideAttempt = state.completedCount(side) + 1,
            pendingDrafts = state.pendingDrafts + (previousSide to current) - side,
            pendingFrameIndices = state.pendingFrameIndices + (previousSide to _currentFrameIndex.value) - side,
        )
        _draft.value = next
        _seriesAttempt.value = state.completedCount(side) + 1
        setFrameIndex(state.pendingFrameIndices[side] ?: _currentFrameIndex.value)
        return true
    }

    /** A real acceptance publishes one feedback event; reading UI state never creates one. */
    fun takeBilateralFeedback(sessionKey: String): BilateralFeedback? =
        pendingBilateralFeedback?.takeIf { it.sessionKey == sessionKey }?.also { pendingBilateralFeedback = null }

    /** Accepts exactly the currently displayed unilateral result and advances one slot. */
    fun acceptBilateralAttempt(result: ProtocolResult, detectedFps: Int): BilateralAdvance =
        acceptBilateralAttempt(checkNotNull(_draft.value).sessionKey, result, detectedFps)

    fun acceptBilateralAttempt(expectedSessionKey: String, result: ProtocolResult, detectedFps: Int): BilateralAdvance {
        val state = checkNotNull(_bilateral.value) { "No hay una comparación bilateral activa." }
        if (state.completed) return BilateralAdvance.COMPLETED
        if (state.attempts.any { it.draft.sessionKey == expectedSessionKey }) return BilateralAdvance.CONTINUE
        val draft = checkNotNull(_draft.value) { "No hay un intento bilateral activo." }
        check(draft.sessionKey == expectedSessionKey) { "El resultado bilateral ya no corresponde al intento activo." }
        check(draft.isComplete()) { "El intento bilateral está incompleto." }
        check(draft.protocolId == com.openjump.app.protocol.ProtocolId.UNILATERAL)
        check(result.protocolId == com.openjump.app.protocol.ProtocolId.UNILATERAL)
        val side = checkNotNull(state.currentSide)
        val snapshot = BilateralAttemptSnapshot(side, state.currentSideAttempt, draft, result, detectedFps)
        val updated = state.copy(
            attempts = state.attempts + snapshot,
            pendingDrafts = state.pendingDrafts - side,
            pendingFrameIndices = state.pendingFrameIndices - side,
        )
        val count = updated.completedCount(side)
        val other = if (side == MeasurementSide.LEFT) MeasurementSide.RIGHT else MeasurementSide.LEFT
        val isLast = updated.completedCount(other) == state.targetAttempts && count == state.targetAttempts
        if (isLast) {
            pendingBilateralFeedback = BilateralFeedback(state.sessionKey, updated.attempts.size, BilateralFeedbackKind.COMPLETE, side, count, state.targetAttempts)
            _bilateral.value = updated.copy(completed = true, currentSide = null)
            _seriesAttempt.value = state.targetAttempts
            return BilateralAdvance.COMPLETED
        }
        val nextSide = if (count == state.targetAttempts) other else side
        val nextOrdinal = updated.completedCount(nextSide) + 1
        pendingBilateralFeedback = BilateralFeedback(
            state.sessionKey, updated.attempts.size,
            if (count == state.targetAttempts) BilateralFeedbackKind.SIDE_COMPLETE else BilateralFeedbackKind.ATTEMPT,
            side, count, state.targetAttempts,
            nextSide = nextSide.takeIf { it != side },
        )
        _bilateral.value = updated.copy(currentSide = nextSide, currentSideAttempt = nextOrdinal,
            pendingDrafts = updated.pendingDrafts - nextSide, pendingFrameIndices = updated.pendingFrameIndices - nextSide)
        _seriesAttempt.value = nextOrdinal
        _draft.value = updated.pendingDrafts[nextSide] ?: bilateralDraft(
            state.sessionKey, state.athleteId, state.testingSessionId, state.athleteAnthropometrics,
            nextSide, nextOrdinal, draft.source, draft.videoUri,
        )
        setFrameIndex(updated.pendingFrameIndices[nextSide] ?: seriesResumeFrameIndex(draft, _currentFrameIndex.value))
        return BilateralAdvance.CONTINUE
    }

    fun bilateralComparison(): BilateralComparison? {
        val state = _bilateral.value ?: return null
        if (!state.completed) return null
        return BilateralComparison(
            state.sessionKey,
            state.athleteId,
            state.targetAttempts,
            state.attempts.sortedWith(compareBy({ if (it.side == MeasurementSide.LEFT) 0 else 1 }, { it.sideOrdinal })),
            state.testingSessionId,
            state.notes,
        )
    }

    private fun bilateralDraft(
        rootKey: String,
        athleteId: Long,
        testingSessionId: Long?,
        anthropometrics: AthleteAnthropometrics,
        side: MeasurementSide,
        sideOrdinal: Int,
        source: VideoSource? = null,
        videoUri: String? = null,
    ) = MeasurementDraft(
        sessionKey = "$rootKey/$sideOrdinal-${side.storageKey}",
        protocolId = ProtocolId.UNILATERAL,
        athleteId = athleteId,
        testingSessionId = testingSessionId,
        athleteAnthropometrics = anthropometrics,
        setup = ProtocolSetup(side = side),
        source = source,
        videoUri = videoUri,
        // Bilateral notes belong to the comparison root, never to an attempt.
        notes = null,
    )

    /** Updates only the active jump draft note; video and analysis state are untouched. */
    fun updateMeasurementNote(note: String?) {
        _draft.update { it?.copy(notes = note?.take(NoteNormalizer.MAX_LENGTH)) }
    }

    /** Updates only the active Encoder draft note; tracking and analysis are untouched. */
    fun updateEncoderNote(note: String?) {
        _encoderDraft.update { it?.copy(notes = note?.take(NoteNormalizer.MAX_LENGTH)) }
    }

    /** Updates the note owned by a completed bilateral root. */
    fun updateBilateralNote(note: String?) {
        _bilateral.update { it?.copy(notes = note?.take(NoteNormalizer.MAX_LENGTH)) }
    }

    /** Returns the next successful save event only to its matching selector draft. */
    fun takeSeriesFeedback(expectedSessionKey: String): SeriesFeedback? =
        pendingSeriesFeedback?.takeIf { it.sessionKey == expectedSessionKey }?.also { pendingSeriesFeedback = null }

    /** Called only after the last attempt's save succeeds. No event for a single jump. */
    fun completedSeriesFeedback(): SeriesFeedback? {
        val draft = _draft.value ?: return null
        if (_bilateral.value != null || _seriesTargetAttempts.value < 2 ||
            _seriesAttempt.value != _seriesTargetAttempts.value) return null
        return SeriesFeedback(draft.sessionKey, _seriesAttempt.value, _seriesTargetAttempts.value, completed = true)
    }

    /** Starts the next jump on the same indexed video and exact current frame. */
    fun prepareNextSeriesAttempt(): Boolean {
        val current = _draft.value ?: return false
        if (_bilateral.value != null) return false
        if (_seriesAttempt.value >= _seriesTargetAttempts.value) return false
        val resumeFrameIndex = seriesResumeFrameIndex(current, _currentFrameIndex.value)
        _draft.value = current.copy(
            sessionKey = UUID.randomUUID().toString(),
            events = emptyMap(),
            notes = null,
            horizontalJump = if (current.protocolId == ProtocolId.HORIZONTAL) {
                HorizontalJumpDraft()
            } else {
                null
            },
        )
        _seriesAttempt.value += 1
        setFrameIndex(resumeFrameIndex)
        pendingSeriesFeedback = SeriesFeedback(
            _draft.value!!.sessionKey, _seriesAttempt.value - 1, _seriesTargetAttempts.value,
        )
        return true
    }

    /** Adds a source without clearing the selected protocol or its setup. */
    fun attachVideo(uri: Uri, source: VideoSource) {
        check(_draft.value != null || _encoderDraft.value != null) {
            "Selecciona un protocolo o encoder antes de adjuntar el vídeo."
        }
        _videoUri.value = uri
        _frameIndex.value = null
        _currentFrameIndex.value = 0
        _draft.update { current ->
            current?.copy(
                source = source,
                videoUri = uri.toString(),
                events = emptyMap(),
                horizontalJump = if (current.protocolId == ProtocolId.HORIZONTAL) HorizontalJumpDraft() else null,
            )
        }
        _encoderDraft.update { current ->
            current?.copy(
                source = source,
                videoUri = uri.toString(),
                timingDecision = null,
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
        notifyVideoOwnershipChanged()
    }

    fun setVideo(uri: Uri, index: VideoFrameIndex) {
        _videoUri.value = uri
        _frameIndex.value = index
        _draft.update { current -> current?.copy(videoUri = uri.toString()) }
        _encoderDraft.update { current ->
            current?.let { draft ->
                draft.copy(
                    videoUri = uri.toString(),
                    timingDecision = resolveEncoderTiming(draft.timingDecision, draft.source, index),
                )
            }
        }
        notifyVideoOwnershipChanged()
    }

    fun setEncoderTiming(decision: VideoTimingDecision) {
        checkNotNull(_encoderDraft.value)
        _encoderDraft.update {
            it?.copy(
                timingDecision = decision,
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    fun clearEncoderTiming() {
        _encoderDraft.update {
            it?.copy(
                timingDecision = null,
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    fun markCalibrationPoint(point: ImagePoint, visibleFrameIndex: Int) {
        val index = _frameIndex.value ?: return
        if (visibleFrameIndex !in 0 until index.frameCount) return
        _encoderDraft.update { current ->
            current ?: return@update null
            when {
                current.calibrationPointA == null || current.calibrationFrameIndex != visibleFrameIndex -> current.copy(
                    calibrationPointA = point,
                    calibrationPointB = null,
                    calibrationFrameIndex = visibleFrameIndex,
                    calibration = null,
                    automaticPlateCalibration = false,
                    tracking = emptyList(),
                )
                current.calibrationPointB == null -> current.copy(
                    calibrationPointB = point,
                    calibration = null,
                    automaticPlateCalibration = false,
                    tracking = emptyList(),
                )
                else -> current.copy(
                    calibrationPointA = point,
                    calibrationPointB = null,
                    calibrationFrameIndex = visibleFrameIndex,
                    calibration = null,
                    automaticPlateCalibration = false,
                    tracking = emptyList(),
                )
            }
        }
        _encoderAnalysis.value = null
    }

    fun confirmCalibration(referenceLengthM: Double) {
        val current = checkNotNull(_encoderDraft.value)
        val pointA = checkNotNull(current.calibrationPointA) { "Marca el punto A." }
        val pointB = checkNotNull(current.calibrationPointB) { "Marca el punto B." }
        val frame = checkNotNull(current.calibrationFrameIndex)
        val calibration = MetricCalibrator.create(frame, pointA, pointB, referenceLengthM)
        _encoderDraft.update {
            it?.copy(calibration = calibration, automaticPlateCalibration = false, tracking = emptyList())
        }
        _encoderAnalysis.value = null
    }

    fun clearCalibration() {
        _encoderDraft.update {
            it?.copy(
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    fun applyPlateDetection(detection: PlateDetection, visibleFrameIndex: Int) {
        val index = checkNotNull(_frameIndex.value)
        require(visibleFrameIndex in 0 until index.frameCount)
        _encoderDraft.update { current ->
            current ?: return@update null
            val calibration = detectionCalibration(
                detection = detection,
                frameIndex = visibleFrameIndex,
                diameterCm = current.sessionPlateDiameterCm,
            )
            val endpoints = detection.calibrationEndpoints()
            current.copy(
                calibrationPointA = endpoints.first.takeIf { calibration != null },
                calibrationPointB = endpoints.second.takeIf { calibration != null },
                calibrationFrameIndex = visibleFrameIndex.takeIf { calibration != null },
                calibration = calibration,
                automaticPlateCalibration = calibration != null,
                plateDetection = detection,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    /** Commits a human-adjusted circle without inventing detector provenance or confidence. */
    fun applyManualPlateCircle(
        center: ImagePoint,
        diameterPx: Double,
        angleRadians: Double,
        visibleFrameIndex: Int,
    ) {
        require(center.x.isFinite() && center.y.isFinite())
        require(diameterPx.isFinite() && diameterPx > 0.0)
        require(angleRadians.isFinite())
        val index = checkNotNull(_frameIndex.value)
        require(visibleFrameIndex in 0 until index.frameCount)
        val presentation = VideoPresentationGeometry.fromEncoded(index.width, index.height, index.rotationDegrees)
        require(
            isPlateCircleWithinFrame(
                center = center,
                diameterPx = diameterPx,
                angleRadians = angleRadians,
                frameWidth = presentation.width,
                frameHeight = presentation.height,
            ),
        ) { "El círculo de calibración debe estar dentro del frame." }
        val half = diameterPx / 2.0
        val dx = kotlin.math.cos(angleRadians) * half
        val dy = kotlin.math.sin(angleRadians) * half
        val pointA = ImagePoint(center.x - dx, center.y - dy)
        val pointB = ImagePoint(center.x + dx, center.y + dy)
        val draft = checkNotNull(_encoderDraft.value)
        val calibration = MetricCalibrator.create(
            visibleFrameIndex,
            pointA,
            pointB,
            draft.sessionPlateDiameterCm / 100.0,
        )
        _encoderDraft.update {
            it?.copy(
                calibrationPointA = pointA,
                calibrationPointB = pointB,
                calibrationFrameIndex = visibleFrameIndex,
                calibration = calibration,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    fun setSessionPlateDiameterCm(value: Double) {
        require(EncoderSettings.isValidPlateDiameterCm(value)) { "El diámetro del disco no es válido." }
        _encoderDraft.update { current ->
            current ?: return@update null
            val pointA = current.calibrationPointA
            val pointB = current.calibrationPointB
            val calibrationFrame = current.calibrationFrameIndex
            val recalibrated = if (pointA != null && pointB != null && calibrationFrame != null) {
                MetricCalibrator.create(calibrationFrame, pointA, pointB, value / 100.0)
            } else {
                null
            }
            current.copy(
                sessionPlateDiameterCm = value,
                calibration = recalibrated,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    fun clearDetectedPlateCalibration() {
        _encoderDraft.update { current ->
            current?.copy(
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    /** Clears only the measured trajectory; confirmed setup and calibration remain usable. */
    fun clearEncoderTracking() {
        _encoderDraft.update { it?.copy(tracking = emptyList()) }
        _encoderAnalysis.value = null
    }

    /** Target-only correction. It deliberately preserves A/B or automatic calibration. */
    fun reselectEncoderTarget() {
        _encoderDraft.update { it?.copy(tracking = emptyList(), plateDetection = null) }
        _encoderAnalysis.value = null
    }

    /** A/B recalibration invalidates every result that depends on the old scale. */
    fun clearEncoderCalibration() {
        clearCalibration()
    }

    /** Returns to the exact calibration + target selection flow that existed before automation. */
    fun useManualEncoderSelection() {
        _encoderDraft.update { current ->
            current?.copy(
                calibrationPointA = null,
                calibrationPointB = null,
                calibrationFrameIndex = null,
                calibration = null,
                automaticPlateCalibration = false,
                plateDetection = null,
                tracking = emptyList(),
            )
        }
        _encoderAnalysis.value = null
    }

    private fun detectionCalibration(
        detection: PlateDetection,
        frameIndex: Int,
        diameterCm: Double,
    ) = if (detection.autoCalibrationAccepted) {
        val (pointA, pointB) = detection.calibrationEndpoints()
        MetricCalibrator.create(frameIndex, pointA, pointB, diameterCm / 100.0)
    } else {
        null
    }

    fun markHorizontalCalibrationPoint(point: ImagePoint, visibleFrameIndex: Int) {
        val index = _frameIndex.value ?: return
        if (visibleFrameIndex !in 0 until index.frameCount) return
        _draft.update { current ->
            if (current?.protocolId != ProtocolId.HORIZONTAL) return@update current
            current.copy(
                horizontalJump = checkNotNull(current.horizontalJump)
                    .markCalibrationPoint(point, visibleFrameIndex),
                events = current.events - EventKey(EventType.LANDING),
            )
        }
    }

    fun confirmHorizontalCalibration(referenceLengthM: Double) {
        _draft.update { current ->
            check(current?.protocolId == ProtocolId.HORIZONTAL)
            current.copy(
                horizontalJump = checkNotNull(current.horizontalJump).confirmCalibration(referenceLengthM),
                events = current.events - EventKey(EventType.LANDING),
            )
        }
    }

    fun markHorizontalPoint(point: ImagePoint, visibleFrameIndex: Int) {
        val index = _frameIndex.value ?: return
        if (visibleFrameIndex !in 0 until index.frameCount) return
        val ptsUs = index.timeOf(visibleFrameIndex)
        _draft.update { current ->
            if (current?.protocolId != ProtocolId.HORIZONTAL) return@update current
            val previous = checkNotNull(current.horizontalJump)
            val updated = previous.markNextPoint(point, visibleFrameIndex, ptsUs)
            val events = if (updated.landingHeel != null && previous.landingHeel == null) {
                val key = EventKey(EventType.LANDING)
                current.events + (key to eventMark(index, key, visibleFrameIndex, ptsUs))
            } else {
                current.events
            }
            current.copy(horizontalJump = updated, events = events)
        }
    }

    fun clearHorizontalCalibration() {
        _draft.update { current ->
            if (current?.protocolId != ProtocolId.HORIZONTAL) return@update current
            current.copy(
                horizontalJump = checkNotNull(current.horizontalJump).clearCalibration(),
                events = current.events - EventKey(EventType.LANDING),
            )
        }
    }

    fun clearHorizontalStartPoint() {
        _draft.update { current ->
            if (current?.protocolId != ProtocolId.HORIZONTAL) return@update current
            current.copy(
                horizontalJump = checkNotNull(current.horizontalJump).clearStartPoint(),
                events = current.events - EventKey(EventType.LANDING),
            )
        }
    }

    fun clearHorizontalLandingHeel() {
        _draft.update { current ->
            if (current?.protocolId != ProtocolId.HORIZONTAL) return@update current
            current.copy(
                horizontalJump = checkNotNull(current.horizontalJump).clearLandingHeel(),
                events = current.events - EventKey(EventType.LANDING),
            )
        }
    }

    fun setEncoderTracking(results: List<TrackingFrameResult>) {
        check(results.isNotEmpty())
        _encoderDraft.update { it?.copy(tracking = results) }
        _encoderAnalysis.value = null
    }

    fun setEncoderAnalysis(analysis: EncoderAnalysis) {
        checkNotNull(_encoderDraft.value)
        _encoderAnalysis.value = analysis
    }

    fun setFrameIndex(index: Int) {
        val max = (_frameIndex.value?.frameCount ?: 1) - 1
        _currentFrameIndex.value = index.coerceIn(0, max)
    }

    fun step(delta: Int) = setFrameIndex(_currentFrameIndex.value + delta)

    /** Stores the canonical PTS of the frame already confirmed on the SurfaceView. */
    fun markAt(type: EventType, visibleFrameIndex: Int, ordinal: Int = 0) {
        val index = _frameIndex.value ?: return
        if (visibleFrameIndex !in 0 until index.frameCount) return
        val key = EventKey(type, ordinal)
        val mark = eventMark(index, key, visibleFrameIndex, index.timeOf(visibleFrameIndex))
        _draft.update { current ->
            current?.copy(events = current.events + (key to mark))
        }
    }

    private fun eventMark(index: VideoFrameIndex, key: EventKey, frameIndex: Int, ptsUs: Long): EventMark =
        EventMark(
            key = key,
            ptsUs = ptsUs,
            frameIndex = frameIndex,
            previousPtsUs = index.frameTimesUs.getOrNull(frameIndex - 1),
            nextPtsUs = index.frameTimesUs.getOrNull(frameIndex + 1),
        )

    fun jumpTo(type: EventType, ordinal: Int = 0) {
        val time = _draft.value?.events?.get(EventKey(type, ordinal))?.ptsUs ?: return
        val index = _frameIndex.value?.indexOfNearest(time) ?: return
        setFrameIndex(index)
    }

    /** Explicit quick swap: only ownership changes; all video/configuration/analysis stays put. */
    fun setTestingParticipants(athleteIds: Set<Long>) {
        require(athleteIds.all { it > 0L }) { "El roster de testing no es válido." }
        _testingParticipantIds.value = athleteIds
    }

    fun reassignActiveAthlete(
        athleteId: Long,
        athleteAnthropometrics: AthleteAnthropometrics = AthleteAnthropometrics(),
    ) {
        require(athleteId > 0L) { "El atleta no es válido." }
        val allowed = _testingParticipantIds.value
        require(allowed.isEmpty() || athleteId in allowed) {
            "El atleta no pertenece al roster de testing."
        }
        // copy() deliberately preserves UUID, testing context, source URI, setup and analysis.
        _draft.update {
            it?.copy(
                athleteId = athleteId,
                athleteAnthropometrics = athleteAnthropometrics,
            )
        }
        _encoderDraft.update { it?.copy(athleteId = athleteId) }
    }

    /** Snapshot of every live jump/encoder URI owner, including bilateral hidden drafts/results. */
    fun videoUriReferences(): Set<String> = buildSet {
        _videoUri.value?.toString()?.let(::add)
        _draft.value?.videoUri?.let(::add)
        _encoderDraft.value?.videoUri?.let(::add)
        _bilateral.value?.let { state ->
            state.pendingDrafts.values.mapNotNullTo(this) { it.videoUri }
            state.attempts.mapNotNullTo(this) { it.draft.videoUri }
        }
    }

    fun isComplete(): Boolean = _draft.value?.isComplete() == true

}

data class BilateralSessionState(
    val sessionKey: String,
    val athleteId: Long,
    val targetAttempts: Int,
    val testingSessionId: Long?,
    val athleteAnthropometrics: AthleteAnthropometrics,
    val currentSide: MeasurementSide?,
    val currentSideAttempt: Int,
    val attempts: List<BilateralAttemptSnapshot> = emptyList(),
    val completed: Boolean = false,
    val pendingDrafts: Map<MeasurementSide, MeasurementDraft> = emptyMap(),
    val pendingFrameIndices: Map<MeasurementSide, Int> = emptyMap(),
    /** Note belongs to the bilateral root and is not copied to child attempts. */
    val notes: String? = null,
)

fun BilateralSessionState.completedCount(side: MeasurementSide): Int = attempts.count { it.side == side }

enum class BilateralFeedbackKind { ATTEMPT, SIDE_COMPLETE, COMPLETE }

data class BilateralFeedback(
    val sessionKey: String,
    val ordinal: Int,
    val kind: BilateralFeedbackKind,
    val side: MeasurementSide,
    val completedCount: Int,
    val targetAttempts: Int,
    val nextSide: MeasurementSide? = null,
) : java.io.Serializable

enum class BilateralAdvance { CONTINUE, COMPLETED }

/** A successful persisted jump, not an inference from an attempt counter. */
data class SeriesFeedback(
    val sessionKey: String,
    val savedAttempt: Int,
    val targetAttempts: Int,
    val completed: Boolean = false,
) : java.io.Serializable

/** Landing is authoritative for the next jump; the cursor is only a fallback. */
internal fun seriesResumeFrameIndex(draft: MeasurementDraft, cursorFrameIndex: Int): Int =
    draft.events[EventKey(EventType.LANDING)]?.frameIndex
        ?: draft.horizontalJump?.landingHeel?.frameIndex
        ?: cursorFrameIndex
