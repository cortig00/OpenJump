package com.openjump.app.data.backup

import com.openjump.app.data.*
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.PhysicalTimeline
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.SpatialMarkType
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.TrackingStatus
import kotlinx.serialization.Serializable
import kotlin.math.abs

const val MANUAL_BACKUP_CONTRACT = "openjump-manual-backup"
const val MANUAL_BACKUP_FORMAT_VERSION = 1
const val MANUAL_BACKUP_ROOM_VERSION = 12

/**
 * Version de esquema en la que nacen las tablas F-V-P. Se fija aqui (no en
 * [MANUAL_BACKUP_ROOM_VERSION]) porque el esquema actual sube con cada carril aditivo: el gate de
 * filas F-V-P debe seguir aceptando un documento Room 11 legitimo, que ya puede traerlas.
 */
const val MANUAL_BACKUP_FVP_INTRODUCED_ROOM_VERSION = 11
private const val LEGACY_MANUAL_BACKUP_ROOM_VERSION = 9
private const val LEGACY_MANUAL_BACKUP_ROOM_VERSION_10 = 10

/** Cota de las claves opacas de procedencia (contrato B0 §2): `<= 128` caracteres. */
private const val MANUAL_BACKUP_MAX_PROVENANCE_KEY_CHARS = 128

/** Cota propia de `warningsCsv`; complementa (no sustituye) el presupuesto de texto del preflight. */
internal const val MANUAL_BACKUP_MAX_WARNINGS_CSV_CHARS = 1_024
const val MANUAL_BACKUP_MAX_BYTES = 8L * 1024L * 1024L
internal const val MANUAL_BACKUP_MAX_JSON_VALUES = 1_500_000
internal const val MANUAL_BACKUP_MAX_ARRAY_ITEMS = 50_000
internal const val MANUAL_BACKUP_MAX_STRING_CHARS = 4_096
internal const val MANUAL_BACKUP_MAX_TOTAL_STRING_CHARS = 1_000_000L
internal const val MANUAL_BACKUP_MAX_NUMBER_CHARS = 128
internal const val MANUAL_BACKUP_MAX_NESTING = 16

typealias ManualBackupSnapshot = ManualBackupDocument

@Serializable
data class ManualBackupDocument(
    val contract: String,
    val formatVersion: Int,
    val sourceRoomSchemaVersion: Int,
    val createdAtEpochMillis: Long,
    val mediaIncluded: Boolean,
    val preferencesIncluded: Boolean,
    val tables: ManualBackupTables,
)

/**
 * Tablas del backup. Las colecciones F-V-P llevan `emptyList()` por defecto para que un backup
 * creado antes de la integración F-V-P (Room 9/10/11 sin esas claves) siga restaurando; el
 * contrato no se relaja porque [ManualBackupDocument.validate] sigue rechazando payloads
 * malformados (referencias huérfanas, ids duplicados, valores no finitos, claves en blanco...).
 */
@Serializable
data class ManualBackupTables(
    val athletes: List<BackupAthlete>,
    val athleteGroups: List<BackupGroup>,
    val athleteGroupCrossRef: List<BackupMembership>,
    val testingSessions: List<BackupTestingSession>,
    val testingParticipants: List<BackupTestingParticipant>,
    val assessments: List<BackupAssessment>,
    val attempts: List<BackupAttempt>,
    val attemptEvents: List<BackupAttemptEvent>,
    val attemptMetrics: List<BackupAttemptMetric>,
    val attemptCalibrations: List<BackupAttemptCalibration>,
    val attemptSpatialMarks: List<BackupAttemptSpatialMark>,
    val encoderSessions: List<BackupEncoderSession>,
    val encoderSamples: List<BackupEncoderSample>,
    val encoderRepetitions: List<BackupEncoderRepetition>,
    val encoderRepMetrics: List<BackupEncoderRepMetric>,
    val fvpAssessments: List<BackupFvpAssessment> = emptyList(),
    val fvpConditions: List<BackupFvpCondition> = emptyList(),
    val fvpAttempts: List<BackupFvpAttempt> = emptyList(),
)

@Serializable
data class BackupAthlete(val id: Long, val displayName: String, val birthDate: Long?, val sex: String?, val notes: String?, val createdAt: Long, val updatedAt: Long, val archivedAt: Long?, val avatarKey: String?, val weightKg: Double?, val heightCm: Double?)
@Serializable
data class BackupGroup(val id: Long, val name: String, val notes: String?, val createdAt: Long, val updatedAt: Long, val archivedAt: Long?)
@Serializable
data class BackupMembership(val groupId: Long, val athleteId: Long)
@Serializable
data class BackupTestingSession(val id: Long, val groupId: Long?, val groupNameSnapshot: String?, val family: String, val protocolId: String?, val exercise: String?, val side: String?, val dropHeightCm: Double?, val loadKg: Double?, val plateDiameterCm: Double?, val targetAttempts: Int, val status: String, val currentOrdinal: Int, val createdAt: Long, val updatedAt: Long, val completedAt: Long?)
@Serializable
data class BackupTestingParticipant(val sessionId: Long, val athleteId: Long, val ordinal: Int, val skippedAt: Long?)
@Serializable
data class BackupAssessment(val id: Long, val sessionKey: String, val athleteId: Long?, val testingSessionId: Long?, val protocolId: String, val dateTime: Long, val primaryMetricKey: String, val primaryMetricValue: Double, val primaryMetricUnit: String, val notes: String? = null)
@Serializable
data class BackupAttempt(val id: Long, val assessmentId: Long, val ordinal: Int, val side: String?, val source: String, val detectedFps: Int, val dropHeightCm: Double?, val distanceCm: Double?)
@Serializable
data class BackupAttemptEvent(val attemptId: Long, val type: String, val ordinal: Int, val ptsUs: Long, val frameIndex: Int? = null, val previousPtsUs: Long? = null, val nextPtsUs: Long? = null)
@Serializable
data class BackupAttemptMetric(val attemptId: Long, val key: String, val ordinal: Int, val value: Double, val unit: String)
@Serializable
data class BackupAttemptCalibration(val attemptId: Long, val frameIndex: Int, val pointAX: Double, val pointAY: Double, val pointBX: Double, val pointBY: Double, val referenceLengthM: Double, val metersPerPixel: Double)
@Serializable
data class BackupAttemptSpatialMark(val attemptId: Long, val type: String, val frameIndex: Int, val ptsUs: Long, val pointX: Double, val pointY: Double)
@Serializable
data class BackupEncoderSession(val id: Long, val sessionKey: String, val athleteId: Long?, val testingSessionId: Long?, val exercise: String, val loadKg: Double, val dateTime: Long, val source: String, val observedFps: Double?, val nominalFps: Double?, val captureFps: Double?, val timingMode: String, val slowMotionFactor: Double, val calibrationFrameIndex: Int, val calibrationAX: Double, val calibrationAY: Double, val calibrationBX: Double, val calibrationBY: Double, val referenceLengthM: Double, val metersPerPixel: Double, val analysisVersion: Int, val minimumVelocityMps: Double, val noiseMultiplier: Double, val exitRatio: Double, val minimumDirectionHoldUs: Long, val stationaryDurationUs: Long, val pauseDurationUs: Long, val minimumPhaseDurationUs: Long, val minimumPhaseDisplacementM: Double, val romOutlierFraction: Double, val minimumMedianConfidence: Double, val maximumUncertainFraction: Double, val smoothingWindowSamples: Int, val velocityEnterMps: Double?, val velocityExitMps: Double?, val totalRepetitions: Int, val validRepetitions: Int, val bestMcv: Double?, val setVelocityLoss: Double?, val notes: String? = null)
@Serializable
data class BackupEncoderSample(val sessionId: Long, val ordinal: Int, val frameIndex: Int, val sourcePtsUs: Long, val physicalTimeUs: Long?, val rawPixelX: Double?, val rawPixelY: Double?, val rawMetricX: Double?, val rawMetricY: Double?, val smoothedMetricX: Double?, val smoothedMetricY: Double?, val velocityXMps: Double?, val velocityYMps: Double?, val confidence: Double, val trackingStatus: String, val observationKind: String, val fitResidualM: Double?, val gapBeforeUs: Long?)
@Serializable
data class BackupEncoderRepetition(val id: Long, val sessionId: Long, val ordinal: Int, val startSample: Int, val endSample: Int, val eccentricStartSample: Int, val eccentricEndSample: Int, val concentricStartSample: Int, val concentricEndSample: Int, val quality: String, val reasons: String)
@Serializable
data class BackupEncoderRepMetric(val repetitionId: Long, val key: String, val value: Double?, val unit: String, val validity: String, val reason: String?)

/**
 * Raíz F-V-P; espeja TODAS las columnas de `fvp_assessments` (incluidas las nullable y la
 * procedencia aditiva de Room 12). Los defaults de las columnas de procedencia existen SOLO para
 * poder leer documentos 9/10/11 (que no traian esas claves); un documento 12 las lleva siempre
 * porque el codec serializa con `encodeDefaults=true`. No son un relleno: UNKNOWN/NULL = desconocido.
 */
@Serializable
data class BackupFvpAssessment(
    val id: Long,
    val sessionKey: String,
    val athleteId: Long?,
    val testingSessionId: Long?,
    val protocolId: String,
    val dateTime: Long,
    val bodyMassKg: Double,
    val bodyMassSource: String,
    val pushOffMode: String,
    val pushOffM: Double,
    val pushOffSource: String,
    val pushOffLandmark: String?,
    val pushOffLandmarkHeightCm: Double?,
    val pushOffProtocol: String?,
    val pushOffMeasuredAt: Long?,
    val pushOffCrouchCm: Double?,
    val pushOffExtendedCm: Double?,
    val sourceFps: Double?,
    val algorithmVersion: String,
    val methodVersion: String,
    val notes: String?,
    val f0N: Double,
    val slopeFv: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double?,
    val pmaxW: Double?,
    val r2: Double?,
    val conditionCount: Int,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val f0RelNPerKg: Double?,
    val pmaxRelWPerKg: Double?,
    val warningsCsv: String,
    // Procedencia declarada (Room 12, B0): se copia 1:1, nunca se deriva.
    val acquiredAt: Long? = null,
    val acquisitionContextKind: String = "UNKNOWN",
    val acquisitionContextKey: String? = null,
    val bodyMassMeasurement: String = "UNKNOWN",
    val bodyMassMeasuredAt: Long? = null,
)

/**
 * Condición de carga; espeja TODAS las columnas de `fvp_conditions` (defaults = lectura de
 * documentos 9/10/11 sin las claves de procedencia).
 */
@Serializable
data class BackupFvpCondition(
    val id: Long,
    val assessmentId: Long,
    val ordinal: Int,
    val conditionKey: String,
    val addedLoadKg: Double,
    val totalMassKg: Double,
    val pushOffM: Double,
    val selectedHeightM: Double,
    val selectedAttemptOrdinal: Int?,
    // Procedencia declarada (Room 12, B0): se copia 1:1, nunca se deriva.
    val addedLoadSource: String = "UNKNOWN",
    val addedLoadMeasuredAt: Long? = null,
)

/**
 * Intento F-V-P; espeja TODAS las columnas de `fvp_attempts` (defaults = lectura de documentos
 * 9/10/11 sin las claves de procedencia).
 */
@Serializable
data class BackupFvpAttempt(
    val id: Long,
    val conditionId: Long,
    val ordinal: Int,
    val heightM: Double,
    val method: String,
    val flightTimeS: Double?,
    val sourceFps: Double?,
    val sourceAssessmentId: Long?,
    val sourceAttemptOrdinal: Int?,
    val sourceProtocolId: String?,
    val valid: Boolean,
    val exclusionReason: String?,
    val notes: String?,
    // Origen, reutilización y snapshots temporales (Room 12, B0): se copian 1:1, nunca se derivan.
    val sourceRecordedAt: Long? = null,
    val sourceAthleteId: Long? = null,
    val acquiredAt: Long? = null,
    val reuseConfirmedAt: Long? = null,
    val reuseConfirmationVersion: String? = null,
    val takeoffPtsUs: Long? = null,
    val takeoffPreviousPtsUs: Long? = null,
    val takeoffNextPtsUs: Long? = null,
    val landingPtsUs: Long? = null,
    val landingPreviousPtsUs: Long? = null,
    val landingNextPtsUs: Long? = null,
    val timeMapping: String = "UNKNOWN",
    val timeMappingEvidenceKey: String? = null,
)

data class ManualBackupSummary(val createdAtEpochMillis: Long, val formatVersion: Int, val athleteCount: Int, val groupCount: Int, val testingSessionCount: Int, val assessmentCount: Int, val encoderSessionCount: Int, val fvpAssessmentCount: Int = 0) {
    companion object { fun from(document: ManualBackupDocument) = with(document.tables) { ManualBackupSummary(document.createdAtEpochMillis, document.formatVersion, athletes.size, athleteGroups.size, testingSessions.size, assessments.size, encoderSessions.size, fvpAssessments.size) } }
}

internal fun BackupAthlete.entity() = AthleteEntity(id, displayName, birthDate, sex, notes, createdAt, updatedAt, archivedAt, avatarKey, weightKg, heightCm)
internal fun BackupGroup.entity() = GroupEntity(id, name, notes, createdAt, updatedAt, archivedAt)
internal fun BackupMembership.entity() = AthleteGroupCrossRef(groupId, athleteId)
internal fun BackupTestingSession.entity() = TestingSessionEntity(id, groupId, groupNameSnapshot, family, protocolId, exercise, side, dropHeightCm, loadKg, plateDiameterCm, targetAttempts, status, currentOrdinal, createdAt, updatedAt, completedAt)
internal fun BackupTestingParticipant.entity() = TestingParticipantEntity(sessionId, athleteId, ordinal, skippedAt)
internal fun BackupAssessment.entity() = AssessmentEntity(id, sessionKey, athleteId, testingSessionId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes)
internal fun BackupAttempt.entity() = AttemptEntity(id, assessmentId, ordinal, side, source, null, detectedFps, dropHeightCm, distanceCm)
internal fun BackupAttemptEvent.entity() = AttemptEventEntity(attemptId, type, ordinal, ptsUs, frameIndex, previousPtsUs, nextPtsUs)
internal fun BackupAttemptMetric.entity() = AttemptMetricEntity(attemptId, key, ordinal, value, unit)
internal fun BackupAttemptCalibration.entity() = AttemptCalibrationEntity(attemptId, frameIndex, pointAX, pointAY, pointBX, pointBY, referenceLengthM, metersPerPixel)
internal fun BackupAttemptSpatialMark.entity() = AttemptSpatialMarkEntity(attemptId, type, frameIndex, ptsUs, pointX, pointY)
internal fun BackupEncoderSession.entity() = EncoderSessionEntity(id = id, sessionKey = sessionKey, athleteId = athleteId, testingSessionId = testingSessionId, exercise = exercise, loadKg = loadKg, dateTime = dateTime, source = source, videoUri = null, observedFps = observedFps, nominalFps = nominalFps, captureFps = captureFps, timingMode = timingMode, slowMotionFactor = slowMotionFactor, calibrationFrameIndex = calibrationFrameIndex, calibrationAX = calibrationAX, calibrationAY = calibrationAY, calibrationBX = calibrationBX, calibrationBY = calibrationBY, referenceLengthM = referenceLengthM, metersPerPixel = metersPerPixel, analysisVersion = analysisVersion, minimumVelocityMps = minimumVelocityMps, noiseMultiplier = noiseMultiplier, exitRatio = exitRatio, minimumDirectionHoldUs = minimumDirectionHoldUs, stationaryDurationUs = stationaryDurationUs, pauseDurationUs = pauseDurationUs, minimumPhaseDurationUs = minimumPhaseDurationUs, minimumPhaseDisplacementM = minimumPhaseDisplacementM, romOutlierFraction = romOutlierFraction, minimumMedianConfidence = minimumMedianConfidence, maximumUncertainFraction = maximumUncertainFraction, smoothingWindowSamples = smoothingWindowSamples, velocityEnterMps = velocityEnterMps, velocityExitMps = velocityExitMps, totalRepetitions = totalRepetitions, validRepetitions = validRepetitions, bestMcv = bestMcv, setVelocityLoss = setVelocityLoss, notes = notes)
internal fun BackupEncoderSample.entity() = EncoderSampleEntity(sessionId, ordinal, frameIndex, sourcePtsUs, physicalTimeUs, rawPixelX, rawPixelY, rawMetricX, rawMetricY, smoothedMetricX, smoothedMetricY, velocityXMps, velocityYMps, confidence, trackingStatus, observationKind, fitResidualM, gapBeforeUs)
internal fun BackupEncoderRepetition.entity() = EncoderRepetitionEntity(id, sessionId, ordinal, startSample, endSample, eccentricStartSample, eccentricEndSample, concentricStartSample, concentricEndSample, quality, reasons)
internal fun BackupEncoderRepMetric.entity() = EncoderRepMetricEntity(repetitionId, key, value, unit, validity, reason)
internal fun BackupFvpAssessment.entity() = FvpAssessmentEntity(
    id = id, sessionKey = sessionKey, athleteId = athleteId, testingSessionId = testingSessionId,
    protocolId = protocolId, dateTime = dateTime, bodyMassKg = bodyMassKg, bodyMassSource = bodyMassSource,
    pushOffMode = pushOffMode, pushOffM = pushOffM, pushOffSource = pushOffSource,
    pushOffLandmark = pushOffLandmark, pushOffLandmarkHeightCm = pushOffLandmarkHeightCm,
    pushOffProtocol = pushOffProtocol, pushOffMeasuredAt = pushOffMeasuredAt,
    pushOffCrouchCm = pushOffCrouchCm, pushOffExtendedCm = pushOffExtendedCm, sourceFps = sourceFps,
    algorithmVersion = algorithmVersion, methodVersion = methodVersion, notes = notes, f0N = f0N,
    slopeFv = slopeFv, sfvNsPerM = sfvNsPerM, v0Mps = v0Mps, pmaxW = pmaxW, r2 = r2,
    conditionCount = conditionCount, vMinMps = vMinMps, vMaxMps = vMaxMps, fMinN = fMinN, fMaxN = fMaxN,
    f0RelNPerKg = f0RelNPerKg, pmaxRelWPerKg = pmaxRelWPerKg, warningsCsv = warningsCsv,
    // Copia literal de la procedencia: un documento 9/10/11 escribe UNKNOWN/NULL (el formato no
    // las tenia y no se inventan) y uno 12 conserva lo que traiga.
    acquiredAt = acquiredAt, acquisitionContextKind = acquisitionContextKind,
    acquisitionContextKey = acquisitionContextKey, bodyMassMeasurement = bodyMassMeasurement,
    bodyMassMeasuredAt = bodyMassMeasuredAt,
)
internal fun BackupFvpCondition.entity() = FvpConditionEntity(
    id = id, assessmentId = assessmentId, ordinal = ordinal, conditionKey = conditionKey,
    addedLoadKg = addedLoadKg, totalMassKg = totalMassKg, pushOffM = pushOffM,
    selectedHeightM = selectedHeightM, selectedAttemptOrdinal = selectedAttemptOrdinal,
    addedLoadSource = addedLoadSource, addedLoadMeasuredAt = addedLoadMeasuredAt,
)
internal fun BackupFvpAttempt.entity() = FvpAttemptEntity(
    id = id, conditionId = conditionId, ordinal = ordinal, heightM = heightM, method = method,
    flightTimeS = flightTimeS, sourceFps = sourceFps, sourceAssessmentId = sourceAssessmentId,
    sourceAttemptOrdinal = sourceAttemptOrdinal, sourceProtocolId = sourceProtocolId, valid = valid,
    exclusionReason = exclusionReason, notes = notes,
    sourceRecordedAt = sourceRecordedAt, sourceAthleteId = sourceAthleteId, acquiredAt = acquiredAt,
    reuseConfirmedAt = reuseConfirmedAt, reuseConfirmationVersion = reuseConfirmationVersion,
    takeoffPtsUs = takeoffPtsUs, takeoffPreviousPtsUs = takeoffPreviousPtsUs,
    takeoffNextPtsUs = takeoffNextPtsUs, landingPtsUs = landingPtsUs,
    landingPreviousPtsUs = landingPreviousPtsUs, landingNextPtsUs = landingNextPtsUs,
    timeMapping = timeMapping, timeMappingEvidenceKey = timeMappingEvidenceKey,
)

private fun finite(value: Double?, field: String) { require(value == null || value.isFinite()) { "$field must be finite" } }

fun ManualBackupDocument.validate(): ManualBackupDocument {
    ManualBackupPreflight.checkDocument(this)
    require(contract == MANUAL_BACKUP_CONTRACT) { "Not an OpenJump manual backup" }
    require(formatVersion == MANUAL_BACKUP_FORMAT_VERSION) { "Unsupported manual backup version" }
    require(
        sourceRoomSchemaVersion == MANUAL_BACKUP_ROOM_VERSION ||
            sourceRoomSchemaVersion == MANUAL_BACKUP_FVP_INTRODUCED_ROOM_VERSION ||
            sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION ||
            sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION_10,
    ) { "Unsupported Room schema version" }
    require(!mediaIncluded && !preferencesIncluded) { "Media or preferences are not supported" }
    val t = tables
    require(t.allLists().any { it.isNotEmpty() }) { "Backup contains no restorable data" }
    val athleteIds = unique(t.athletes.map { it.id }, "athlete id")
    val groupIds = unique(t.athleteGroups.map { it.id }, "group id")
    val testingIds = unique(t.testingSessions.map { it.id }, "testing session id")
    val assessmentIds = unique(t.assessments.map { it.id }, "assessment id")
    val attemptIds = unique(t.attempts.map { it.id }, "attempt id")
    val encoderIds = unique(t.encoderSessions.map { it.id }, "encoder session id")
    val repetitionIds = unique(t.encoderRepetitions.map { it.id }, "repetition id")
    val fvpAssessmentIds = unique(t.fvpAssessments.map { it.id }, "fvp assessment id")
    val fvpConditionIds = unique(t.fvpConditions.map { it.id }, "fvp condition id")
    val fvpAttemptIds = unique(t.fvpAttempts.map { it.id }, "fvp attempt id")
    listOf(
        athleteIds to "athlete id",
        groupIds to "group id",
        testingIds to "testing session id",
        assessmentIds to "assessment id",
        attemptIds to "attempt id",
        encoderIds to "encoder session id",
        repetitionIds to "repetition id",
        fvpAssessmentIds to "fvp assessment id",
        fvpConditionIds to "fvp condition id",
        fvpAttemptIds to "fvp attempt id",
    ).forEach { (ids, field) -> ids.forEach { require(it > 0L) { "$field must be positive" } } }
    unique(t.assessments.map { it.sessionKey }, "assessment sessionKey")
    unique(t.encoderSessions.map { it.sessionKey }, "encoder sessionKey")
    t.athletes.forEach { require(it.displayName.isNotBlank()); finite(it.weightKg,"weightKg"); finite(it.heightCm,"heightCm") }
    t.athleteGroupCrossRef.forEach { require(it.groupId in groupIds && it.athleteId in athleteIds) }
    t.testingSessions.forEach { s ->
        require(s.groupId == null || s.groupId in groupIds)
        require(s.family in setOf("JUMP", "ENCODER"))
        require(s.status in setOf("ACTIVE", "COMPLETED", "CANCELLED"))
        require(s.targetAttempts >= 1)
        require(s.currentOrdinal >= 0)
        require(s.side == null || MeasurementSide.fromStorageKey(s.side) != null)
        finite(s.dropHeightCm, "dropHeightCm")
        finite(s.loadKg, "loadKg")
        finite(s.plateDiameterCm, "plateDiameterCm")
        if (s.family == "JUMP") {
            require(s.protocolId != null && ProtocolId.fromStorageKey(s.protocolId) != null)
            require(s.exercise == null)
        } else {
            require(s.protocolId == null)
            require(s.exercise != null && runCatching { EncoderExercise.valueOf(s.exercise) }.isSuccess)
        }
    }
    val participantsBySession = t.testingParticipants.groupBy { it.sessionId }
    unique(t.testingParticipants.map { it.sessionId to it.athleteId }, "testing participant")
    t.testingParticipants.forEach { p -> require(p.sessionId in testingIds && p.athleteId in athleteIds); require(p.ordinal >= 0) }
    t.testingParticipants.groupBy { it.sessionId }.forEach { (_, rows) -> unique(rows.map { it.ordinal }, "testing participant ordinal") }
    t.testingSessions.forEach { session ->
        val participantOrdinals = participantsBySession[session.id].orEmpty().map { it.ordinal }
        require(participantOrdinals.isNotEmpty())
        // Permanent athlete deletion can legitimately leave gaps in the frozen roster. Only an
        // active session needs its cursor to resolve to a participant; terminal sessions retain
        // their historical cursor even when that athlete is later deleted.
        if (session.status == "ACTIVE") require(session.currentOrdinal in participantOrdinals)
    }
    t.assessments.forEach { a ->
        require(a.sessionKey.isNotBlank())
        require(a.athleteId == null || a.athleteId in athleteIds)
        require(a.testingSessionId == null || a.testingSessionId in testingIds)
        require(ProtocolId.fromStorageKey(a.protocolId) != null)
        require(MetricKey.fromStorageKey(a.primaryMetricKey) != null)
        require(MetricUnit.fromStorageKey(a.primaryMetricUnit) != null)
        require(a.notes == NoteNormalizer.normalize(a.notes))
        if (sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION) require(a.notes == null)
        finite(a.primaryMetricValue, "primaryMetricValue")
    }
    val attemptsByAssessment = t.attempts.groupBy { it.assessmentId }
    t.assessments.forEach { require(attemptsByAssessment[it.id].orEmpty().isNotEmpty()) }
    val assessmentById = t.assessments.associateBy { it.id }
    val testingById = t.testingSessions.associateBy { it.id }
    t.assessments.forEach { assessment ->
        assessment.testingSessionId?.let { testingId ->
            val session = requireNotNull(testingById[testingId])
            require(session.family == "JUMP")
            require(assessment.athleteId != null)
            require(participantsBySession[testingId].orEmpty().any { it.athleteId == assessment.athleteId })
            require(session.protocolId == assessment.protocolId)
            val attempts = attemptsByAssessment[assessment.id].orEmpty()
            require(attempts.size == 1)
            val attempt = attempts.single()
            require(attempt.side == session.side)
            require(attempt.dropHeightCm == session.dropHeightCm)
        }
    }
    t.attempts.forEach { a ->
        require(a.assessmentId in assessmentIds)
        require(a.ordinal >= 0)
        require(a.side == null || MeasurementSide.fromStorageKey(a.side) != null)
        require(a.source in VideoSource.entries.map { it.storageKey })
        require(a.detectedFps >= 0)
        require(a.dropHeightCm == null || a.dropHeightCm.isFinite())
        require(a.distanceCm == null || a.distanceCm.isFinite())
    }
    attemptsByAssessment.forEach { (assessmentId, rows) ->
        require(rows.map { it.ordinal }.sorted() == rows.indices.toList())
        val protocol = requireNotNull(assessmentById[assessmentId]).protocolId
        if (protocol == ProtocolId.ASYMMETRY.storageKey) {
            require(rows.size in 2..10 && rows.size % 2 == 0)
            val half = rows.size / 2
            rows.sortedBy { it.ordinal }.forEachIndexed { index, row -> require(row.side == if (index < half) "LEFT" else "RIGHT") }
        }
    }
    unique(t.attemptEvents.map { Triple(it.attemptId, it.type, it.ordinal) }, "attempt event key")
    unique(t.attemptMetrics.map { Triple(it.attemptId, it.key, it.ordinal) }, "attempt metric key")
    unique(t.attemptCalibrations.map { it.attemptId }, "attempt calibration key")
    unique(t.attemptSpatialMarks.map { it.attemptId to it.type }, "attempt spatial mark key")
    t.attemptEvents.forEach { e ->
        require(e.attemptId in attemptIds && EventType.fromStorageKey(e.type) != null)
        require(e.ordinal >= 0 && e.ptsUs >= 0)
        require(e.frameIndex == null || e.frameIndex >= 0)
        if (e.frameIndex == null) {
            require(e.previousPtsUs == null && e.nextPtsUs == null) {
                "PTS neighborhood requires a frameIndex."
            }
        }
        if (sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION) {
            require(e.frameIndex == null && e.previousPtsUs == null && e.nextPtsUs == null) {
                "Room 9 backup cannot contain Room 10 event fields."
            }
        }
        require(e.previousPtsUs == null || e.previousPtsUs < e.ptsUs)
        require(e.nextPtsUs == null || e.ptsUs < e.nextPtsUs)
    }
    t.attemptMetrics.forEach { m -> require(m.attemptId in attemptIds && MetricKey.fromStorageKey(m.key) != null && MetricUnit.fromStorageKey(m.unit) != null && m.ordinal >= 0); finite(m.value,"attempt metric") }
    t.attemptCalibrations.forEach { c ->
        require(c.attemptId in attemptIds)
        require(c.frameIndex >= 0)
        listOf(c.pointAX, c.pointAY, c.pointBX, c.pointBY, c.referenceLengthM, c.metersPerPixel).forEach { require(it.isFinite()) }
    }
    t.attemptSpatialMarks.forEach { m ->
        require(m.attemptId in attemptIds && SpatialMarkType.fromStorageKey(m.type) != null)
        require(m.frameIndex >= 0 && m.ptsUs >= 0)
        listOf(m.pointX, m.pointY).forEach { require(it.isFinite()) }
    }
    val eventsByAttempt = t.attemptEvents.groupBy { it.attemptId }
    val calibrationsByAttempt = t.attemptCalibrations.associateBy { it.attemptId }
    val spatialMarksByAttempt = t.attemptSpatialMarks.groupBy { it.attemptId }
    t.attempts.forEach { attempt ->
        val protocol = requireNotNull(assessmentById[attempt.assessmentId]).protocolId
        when (protocol) {
            ProtocolId.HORIZONTAL.storageKey -> {
                require(calibrationsByAttempt[attempt.id] != null)
                val marks = spatialMarksByAttempt[attempt.id].orEmpty().associateBy { it.type }
                val start = requireNotNull(marks[SpatialMarkType.START_POINT.storageKey])
                val landing = requireNotNull(marks[SpatialMarkType.LANDING_HEEL.storageKey])
                require(landing.ptsUs > start.ptsUs)
            }
            ProtocolId.ASYMMETRY.storageKey -> {
                // Bilateral children are unilateral attempts. Never sort by event type text:
                // alphabetical order would put LANDING before MOVEMENT_START. The movement start
                // is optional; when present it must still precede takeoff.
                val eventsByKey = eventsByAttempt[attempt.id].orEmpty()
                    .associateBy { it.type to it.ordinal }
                val takeoff = requireNotNull(eventsByKey["TAKEOFF" to 0]) {
                    "Missing bilateral event TAKEOFF for attempt ${attempt.id}."
                }
                val landing = requireNotNull(eventsByKey["LANDING" to 0]) {
                    "Missing bilateral event LANDING for attempt ${attempt.id}."
                }
                require(landing.ptsUs > takeoff.ptsUs)
                eventsByKey["MOVEMENT_START" to 0]?.let { start ->
                    require(start.ptsUs < takeoff.ptsUs) {
                        "Bilateral MOVEMENT_START must precede TAKEOFF for attempt ${attempt.id}."
                    }
                }
            }
            else -> {
                val definition = requireNotNull(ProtocolCatalog.find(protocol))
                val byKey = eventsByAttempt[attempt.id].orEmpty()
                    .associateBy { it.type to it.ordinal }
                // Mandatory events are always required; an optional movement start is validated
                // for real chronology when the attempt actually carries one.
                val mandatory = definition.mandatoryEvents.map { event ->
                    requireNotNull(byKey[event.storageKey to 0]) {
                        "Missing mandatory event ${event.storageKey} for attempt ${attempt.id}."
                    }
                }
                require(mandatory.zipWithNext().all { (first, second) -> second.ptsUs > first.ptsUs })
                byKey[EventType.MOVEMENT_START.storageKey to 0]?.let { start ->
                    if (EventType.MOVEMENT_START in definition.optionalEvents) {
                        val takeoff = requireNotNull(byKey[EventType.TAKEOFF.storageKey to 0])
                        require(start.ptsUs < takeoff.ptsUs) {
                            "MOVEMENT_START must precede TAKEOFF for attempt ${attempt.id}."
                        }
                    }
                }
            }
        }
    }
    t.encoderSessions.forEach { s ->
        require(s.sessionKey.isNotBlank())
        require(s.notes == NoteNormalizer.normalize(s.notes))
        if (sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION) require(s.notes == null)
        require(s.athleteId == null || s.athleteId in athleteIds)
        require(s.testingSessionId == null || s.testingSessionId in testingIds)
        require(runCatching { EncoderExercise.valueOf(s.exercise) }.isSuccess)
        require(s.source in VideoSource.entries.map { it.storageKey })
        require(s.timingMode in setOf("REAL_TIME", "SLOW_MOTION", "UNKNOWN"))
        require(s.slowMotionFactor.isFinite() && s.slowMotionFactor > 0)
        when (s.timingMode) {
            "REAL_TIME" -> require(s.slowMotionFactor == 1.0)
            "SLOW_MOTION" -> require(s.slowMotionFactor > 1.0)
            "UNKNOWN" -> Unit
        }
        require(s.calibrationFrameIndex >= 0 && s.smoothingWindowSamples > 0)
        finite(s.loadKg, "loadKg")
        finite(s.observedFps, "observedFps")
        finite(s.nominalFps, "nominalFps")
        finite(s.captureFps, "captureFps")
        s.testingSessionId?.let { id ->
            val testing = requireNotNull(testingById[id])
            require(testing.family == "ENCODER")
            require(s.athleteId != null)
            require(participantsBySession[id].orEmpty().any { it.athleteId == s.athleteId })
            require(testing.exercise == s.exercise)
            require(testing.loadKg != null && abs(testing.loadKg - s.loadKg) < 1e-6)
        }
        listOf(s.calibrationAX,s.calibrationAY,s.calibrationBX,s.calibrationBY,s.referenceLengthM,s.metersPerPixel,s.minimumVelocityMps,s.noiseMultiplier,s.exitRatio,s.minimumPhaseDisplacementM,s.romOutlierFraction,s.minimumMedianConfidence,s.maximumUncertainFraction,s.velocityEnterMps,s.velocityExitMps).forEach { finite(it,"encoder session") }
    }
    val samplesBySession = t.encoderSamples.groupBy { it.sessionId }
    t.encoderSamples.groupBy { it.sessionId }.forEach { (id, rows) ->
        require(id in encoderIds)
        val ordered = rows.sortedBy { it.ordinal }
        require(ordered.map { it.ordinal } == ordered.indices.toList())
        require(ordered.zipWithNext().all { it.first.sourcePtsUs < it.second.sourcePtsUs })
        ordered.forEach { s ->
            require(s.frameIndex >= 0 && s.sourcePtsUs >= 0)
            require(s.physicalTimeUs == null || s.physicalTimeUs >= 0)
            require(s.gapBeforeUs == null || s.gapBeforeUs >= 0)
            require(s.trackingStatus in TrackingStatus.entries.map { it.name })
            require(s.observationKind in setOf("MEASURED", "MISSING"))
            require(s.confidence in 0.0..1.0)
            listOf(s.rawPixelX, s.rawPixelY, s.rawMetricX, s.rawMetricY, s.smoothedMetricX, s.smoothedMetricY, s.velocityXMps, s.velocityYMps, s.confidence, s.fitResidualM).forEach { finite(it, "encoder sample") }
        }
        val session = requireNotNull(t.encoderSessions.firstOrNull { it.id == id })
        val mode = runCatching { VideoTimingMode.valueOf(session.timingMode) }.getOrElse { error("Unknown encoder timing mode") }
        val timeline = PhysicalTimeline.resolve(
            ordered.map { it.sourcePtsUs },
            VideoTimingDecision(mode, session.slowMotionFactor),
        )
        ordered.forEachIndexed { index, sample ->
            if (mode == VideoTimingMode.UNKNOWN) {
                require(sample.physicalTimeUs == null)
            } else {
                require(sample.physicalTimeUs == timeline.timesUs[index])
            }
        }
    }
    unique(t.encoderSamples.map { it.sessionId to it.ordinal }, "encoder sample key")
    unique(t.encoderRepetitions.map { it.sessionId to it.ordinal }, "encoder repetition key")
    unique(t.encoderRepMetrics.map { it.repetitionId to it.key }, "encoder metric key")
    t.encoderRepetitions.forEach { r ->
        require(r.sessionId in encoderIds && r.ordinal >= 0 && r.quality in RepetitionQuality.entries.map { it.name })
        r.reasons.split(',').filter(String::isNotBlank).forEach { reason -> require(runCatching { QualityReason.valueOf(reason) }.isSuccess) }
        val n = samplesBySession[r.sessionId].orEmpty().size
        val indexes = listOf(r.startSample, r.endSample, r.eccentricStartSample, r.eccentricEndSample, r.concentricStartSample, r.concentricEndSample)
        indexes.forEach { require(it in 0 until n) }
        require(r.startSample <= r.endSample)
        require(r.eccentricStartSample <= r.eccentricEndSample)
        require(r.concentricStartSample <= r.concentricEndSample)
        require(r.eccentricStartSample >= r.startSample && r.eccentricEndSample <= r.endSample)
        require(r.concentricStartSample >= r.startSample && r.concentricEndSample <= r.endSample)
        val exercise = EncoderExercise.valueOf(requireNotNull(t.encoderSessions.firstOrNull { it.id == r.sessionId }).exercise)
        if (exercise.eccentricFirst) {
            require(r.eccentricEndSample < r.concentricStartSample)
        } else {
            require(r.concentricEndSample < r.eccentricStartSample)
        }
    }
    t.encoderRepetitions.groupBy { it.sessionId }.forEach { (_, rows) -> require(rows.map { it.ordinal }.sorted() == rows.indices.toList()) }
    t.encoderRepMetrics.forEach { m ->
        require(m.repetitionId in repetitionIds)
        require(runCatching { EncoderMetricKey.valueOf(m.key) }.isSuccess)
        require(runCatching { EncoderMetricUnit.valueOf(m.unit) }.isSuccess)
        require(runCatching { MetricValidity.valueOf(m.validity) }.isSuccess)
        require(m.validity == MetricValidity.INVALID.name || m.value != null)
        require(m.reason == null || runCatching { QualityReason.valueOf(m.reason) }.isSuccess)
        finite(m.value, "encoder metric")
    }
    t.encoderSessions.forEach { s -> val reps = t.encoderRepetitions.count { it.sessionId == s.id }; require(s.totalRepetitions == reps); require(s.validRepetitions == t.encoderRepetitions.count { it.sessionId == s.id && it.quality == RepetitionQuality.VALID.name }); finite(s.bestMcv,"bestMcv"); finite(s.setVelocityLoss,"setVelocityLoss") }
    // --- F-V-P (Room 11) -----------------------------------------------------------------------
    unique(t.fvpAssessments.map { it.sessionKey }, "fvp sessionKey")
    unique(t.fvpConditions.map { it.assessmentId to it.ordinal }, "fvp condition ordinal")
    unique(t.fvpConditions.map { it.assessmentId to it.conditionKey }, "fvp condition key")
    unique(t.fvpAttempts.map { it.conditionId to it.ordinal }, "fvp attempt ordinal")
    // Las tablas F-V-P nacen en Room 11: un payload que se declara 9/10 no puede traerlas. El gate
    // usa la version de INTRODUCCION de F-V-P, no el esquema actual: un documento legítimo de
    // Room 11 sí puede traer filas F-V-P y no debe rechazarse al subir [MANUAL_BACKUP_ROOM_VERSION].
    if (sourceRoomSchemaVersion < MANUAL_BACKUP_FVP_INTRODUCED_ROOM_VERSION) {
        require(t.fvpAssessments.isEmpty() && t.fvpConditions.isEmpty() && t.fvpAttempts.isEmpty()) {
            "Room $sourceRoomSchemaVersion backup cannot contain F-V-P rows."
        }
    }
    // La procedencia nace en Room 12: un documento que se declara 9/10/11 no puede traer ninguna de
    // las 20 columnas. Mismo criterio que el gate de filas F-V-P: la version debe describir la forma,
    // y asi los defaults no pueden tapar un linaje mentido ni una restauracion hibrida.
    if (sourceRoomSchemaVersion < MANUAL_BACKUP_ROOM_VERSION) {
        require(
            t.fvpAssessments.all { it.provenanceIsUnknown() } &&
                t.fvpConditions.all { it.provenanceIsUnknown() } &&
                t.fvpAttempts.all { it.provenanceIsUnknown() },
        ) {
            "Room $sourceRoomSchemaVersion backup cannot carry Room $MANUAL_BACKUP_ROOM_VERSION provenance."
        }
    }
    t.fvpAssessments.forEach { a ->
        require(a.sessionKey.isNotBlank())
        require(a.athleteId == null || a.athleteId in athleteIds)
        require(a.testingSessionId == null || a.testingSessionId in testingIds)
        require(a.protocolId in FVP_PROTOCOL_KEYS)
        require(a.bodyMassSource in FVP_BODY_MASS_SOURCES)
        require(a.pushOffMode in FVP_PUSH_OFF_MODES)
        require(a.pushOffSource in FVP_PUSH_OFF_SOURCES)
        require(a.acquisitionContextKind in FVP_ACQUISITION_CONTEXT_KINDS) {
            "fvp assessment ${a.id} has an unknown acquisitionContextKind: '${a.acquisitionContextKind}'"
        }
        require(a.bodyMassMeasurement in FVP_BODY_MASS_MEASUREMENTS) {
            "fvp assessment ${a.id} has an unknown bodyMassMeasurement: '${a.bodyMassMeasurement}'"
        }
        requireFvpTimestamp(a.acquiredAt, "fvp assessment ${a.id} acquiredAt")
        requireFvpTimestamp(a.bodyMassMeasuredAt, "fvp assessment ${a.id} bodyMassMeasuredAt")
        requireFvpProvenanceKey(a.acquisitionContextKey, "fvp assessment ${a.id} acquisitionContextKey")
        // UNKNOWN/NULL sigue siendo desconocido: un contexto declarado como "misma adquisición"
        // sin clave que lo identifique no es auditable y no se deriva de fechas ni de otro campo.
        require(a.acquisitionContextKind != "SAME_ACQUISITION" || a.acquisitionContextKey != null) {
            "fvp assessment ${a.id} SAME_ACQUISITION requires acquisitionContextKey"
        }
        require(a.algorithmVersion.isNotBlank())
        require(a.methodVersion.isNotBlank())
        require(a.notes == NoteNormalizer.normalize(a.notes))
        require(a.warningsCsv.length <= MANUAL_BACKUP_MAX_WARNINGS_CSV_CHARS) { "fvp warningsCsv is too long" }
        require(a.conditionCount >= 1) { "fvp conditionCount must be >= 1" }
        finite(a.bodyMassKg, "fvp bodyMassKg")
        finite(a.pushOffM, "fvp pushOffM")
        finite(a.pushOffLandmarkHeightCm, "fvp pushOffLandmarkHeightCm")
        finite(a.pushOffCrouchCm, "fvp pushOffCrouchCm")
        finite(a.pushOffExtendedCm, "fvp pushOffExtendedCm")
        finite(a.sourceFps, "fvp sourceFps")
        finite(a.f0N, "fvp f0N")
        finite(a.slopeFv, "fvp slopeFv")
        finite(a.sfvNsPerM, "fvp sfvNsPerM")
        // Misma invariante dura que `FvpDao.save` (`sfv = -slope` en el motor): un backup editado a
        // mano no puede inyectar un par de signos imposible en la base de datos.
        require(a.sfvNsPerM == -a.slopeFv) {
            "fvp assessment ${a.id} sfvNsPerM must be exactly -slopeFv " +
                "(sfvNsPerM=${a.sfvNsPerM}, slopeFv=${a.slopeFv})"
        }
        finite(a.v0Mps, "fvp v0Mps")
        finite(a.pmaxW, "fvp pmaxW")
        finite(a.r2, "fvp r2")
        finite(a.vMinMps, "fvp vMinMps")
        finite(a.vMaxMps, "fvp vMaxMps")
        finite(a.fMinN, "fvp fMinN")
        finite(a.fMaxN, "fvp fMaxN")
        finite(a.f0RelNPerKg, "fvp f0RelNPerKg")
        finite(a.pmaxRelWPerKg, "fvp pmaxRelWPerKg")
    }
    t.fvpConditions.forEach { c ->
        require(c.assessmentId in fvpAssessmentIds) { "fvp condition references an unknown assessment" }
        require(c.ordinal >= 0)
        require(c.conditionKey.isNotBlank())
        require(c.selectedAttemptOrdinal == null || c.selectedAttemptOrdinal >= 0)
        require(c.addedLoadSource in FVP_ADDED_LOAD_SOURCES) {
            "fvp condition ${c.id} has an unknown addedLoadSource: '${c.addedLoadSource}'"
        }
        requireFvpTimestamp(c.addedLoadMeasuredAt, "fvp condition ${c.id} addedLoadMeasuredAt")
        finite(c.addedLoadKg, "fvp addedLoadKg")
        finite(c.totalMassKg, "fvp totalMassKg")
        finite(c.pushOffM, "fvp pushOffM")
        finite(c.selectedHeightM, "fvp selectedHeightM")
    }
    t.fvpAttempts.forEach { a ->
        require(a.conditionId in fvpConditionIds) { "fvp attempt references an unknown condition" }
        require(a.ordinal >= 0)
        require(a.method in FVP_MEASURE_METHODS)
        // Misma regla dura que `FvpDao.save`/`checkSeries` (gate B3): un descarte tecnico sin motivo
        // no es auditable. El backup es otra frontera de integridad (JSON editado a mano o de otra
        // instalacion) y sin este `require` una fila `valid=false` sin `exclusionReason` se
        // restauraria para degradar despues a `RECALCULATION_UNAVAILABLE` para siempre.
        require(a.valid || !a.exclusionReason.isNullOrBlank()) {
            "fvp attempt ${a.id} is invalid but has no exclusionReason"
        }
        require(a.sourceAssessmentId == null || a.sourceAssessmentId > 0L)
        require(a.sourceAttemptOrdinal == null || a.sourceAttemptOrdinal >= 0)
        require(a.sourceProtocolId == null || a.sourceProtocolId.isNotBlank())
        // --- Procedencia y snapshots temporales (Room 12, B0) ------------------------------
        require(a.timeMapping in FVP_TIME_MAPPINGS) {
            "fvp attempt ${a.id} has an unknown timeMapping: '${a.timeMapping}'"
        }
        requireFvpTimestamp(a.sourceRecordedAt, "fvp attempt ${a.id} sourceRecordedAt")
        requireFvpTimestamp(a.acquiredAt, "fvp attempt ${a.id} acquiredAt")
        requireFvpTimestamp(a.reuseConfirmedAt, "fvp attempt ${a.id} reuseConfirmedAt")
        require(a.sourceAthleteId == null || a.sourceAthleteId > 0L) {
            "fvp attempt ${a.id} sourceAthleteId must be > 0 when present"
        }
        requireFvpProvenanceKey(a.timeMappingEvidenceKey, "fvp attempt ${a.id} timeMappingEvidenceKey")
        // La evidencia temporal no se infiere de fps nominales, captura ni checkbox: un mapeo
        // IDENTITY_VERIFIED sin clave de evidencia no puede restaurarse como si estuviera verificado.
        require(a.timeMapping != "IDENTITY_VERIFIED" || a.timeMappingEvidenceKey != null) {
            "fvp attempt ${a.id} IDENTITY_VERIFIED requires timeMappingEvidenceKey"
        }
        require(a.reuseConfirmationVersion == null || a.reuseConfirmationVersion == FVP_REUSE_CONFIRMATION_VERSION) {
            "fvp attempt ${a.id} reuseConfirmationVersion must be null or '$FVP_REUSE_CONFIRMATION_VERSION', " +
                "was '${a.reuseConfirmationVersion}'"
        }
        // Confirmacion durable: version y fecha van juntas o ninguna de las dos. No se completa una
        // con la hora de restauracion ni con la fecha del intento.
        require((a.reuseConfirmationVersion == null) == (a.reuseConfirmedAt == null)) {
            "fvp attempt ${a.id} reuseConfirmationVersion and reuseConfirmedAt must be set together"
        }
        requireFvpPtsOrder(
            label = "fvp attempt ${a.id}",
            eventField = "takeoffPtsUs",
            event = a.takeoffPtsUs,
            previousField = "takeoffPreviousPtsUs",
            previous = a.takeoffPreviousPtsUs,
            nextField = "takeoffNextPtsUs",
            next = a.takeoffNextPtsUs,
        )
        requireFvpPtsOrder(
            label = "fvp attempt ${a.id}",
            eventField = "landingPtsUs",
            event = a.landingPtsUs,
            previousField = "landingPreviousPtsUs",
            previous = a.landingPreviousPtsUs,
            nextField = "landingNextPtsUs",
            next = a.landingNextPtsUs,
        )
        require(a.takeoffPtsUs == null || a.landingPtsUs == null || a.landingPtsUs > a.takeoffPtsUs) {
            "fvp attempt ${a.id} landingPtsUs must be > takeoffPtsUs when both are present " +
                "(takeoffPtsUs=${a.takeoffPtsUs}, landingPtsUs=${a.landingPtsUs})"
        }
        finite(a.heightM, "fvp heightM")
        finite(a.flightTimeS, "fvp flightTimeS")
        finite(a.sourceFps, "fvp sourceFps")
    }
    // Coherencia raiz-hijos: una evaluacion sin condiciones, o con un `conditionCount` que no
    // coincide con sus condiciones, restauraria un perfil incompleto (el export INNER JOIN
    // devolveria cero detalles). Se rechaza en la validacion, no se acepta en silencio.
    t.fvpAssessments.forEach { a ->
        val conditions = t.fvpConditions.count { it.assessmentId == a.id }
        require(conditions >= 1) { "fvp assessment ${a.id} has no conditions" }
        require(a.conditionCount == conditions) {
            "fvp assessment ${a.id} conditionCount ${a.conditionCount} != $conditions stored conditions"
        }
    }
    return this
}

private fun <T> unique(values: List<T>, field: String): Set<T> { require(values.distinct().size == values.size) { "Duplicate $field" }; return values.toSet() }

/** Timestamp epoch ms de procedencia: `>= 0` cuando se informa, `null` = desconocido (nunca "hoy"). */
private fun requireFvpTimestamp(value: Long?, field: String) {
    require(value == null || value >= 0L) { "$field must be >= 0 when present" }
}

/** Clave opaca de procedencia: `null` = desconocida; si se informa, no vacía y de `<= 128`. */
private fun requireFvpProvenanceKey(value: String?, field: String) {
    if (value == null) return
    require(value.isNotBlank() && value.length <= MANUAL_BACKUP_MAX_PROVENANCE_KEY_CHARS) {
        "$field must be non-blank and at most $MANUAL_BACKUP_MAX_PROVENANCE_KEY_CHARS characters"
    }
}

/** PTS [µs] de un evento con vecinos opcionales: no negativos, vecino exige evento y orden estricto. */
private fun requireFvpPtsOrder(
    label: String,
    eventField: String,
    event: Long?,
    previousField: String,
    previous: Long?,
    nextField: String,
    next: Long?,
) {
    requireFvpTimestamp(event, "$label $eventField")
    requireFvpTimestamp(previous, "$label $previousField")
    requireFvpTimestamp(next, "$label $nextField")
    require(previous == null || event != null) { "$label $previousField requires $eventField" }
    require(next == null || event != null) { "$label $nextField requires $eventField" }
    require(previous == null || event == null || previous < event) { "$label $previousField must be < $eventField" }
    require(next == null || event == null || event < next) { "$label $nextField must be > $eventField" }
}
private fun ManualBackupTables.allLists() = listOf(athletes, athleteGroups, athleteGroupCrossRef, testingSessions, testingParticipants, assessments, attempts, attemptEvents, attemptMetrics, attemptCalibrations, attemptSpatialMarks, encoderSessions, encoderSamples, encoderRepetitions, encoderRepMetrics, fvpAssessments, fvpConditions, fvpAttempts)

/** `true` si la raiz no declara ninguna columna de procedencia (`UNKNOWN`/NULL, como un documento <12). */
private fun BackupFvpAssessment.provenanceIsUnknown(): Boolean =
    acquiredAt == null &&
        acquisitionContextKind == "UNKNOWN" &&
        acquisitionContextKey == null &&
        bodyMassMeasurement == "UNKNOWN" &&
        bodyMassMeasuredAt == null

/** `true` si la condicion no declara procedencia de carga (`UNKNOWN`/NULL, como un documento <12). */
private fun BackupFvpCondition.provenanceIsUnknown(): Boolean =
    addedLoadSource == "UNKNOWN" && addedLoadMeasuredAt == null

/** `true` si el intento no declara procedencia (`UNKNOWN`/NULL, como un documento <12). */
private fun BackupFvpAttempt.provenanceIsUnknown(): Boolean =
    sourceRecordedAt == null &&
        sourceAthleteId == null &&
        acquiredAt == null &&
        reuseConfirmedAt == null &&
        reuseConfirmationVersion == null &&
        takeoffPtsUs == null &&
        takeoffPreviousPtsUs == null &&
        takeoffNextPtsUs == null &&
        landingPtsUs == null &&
        landingPreviousPtsUs == null &&
        landingNextPtsUs == null &&
        timeMapping == "UNKNOWN" &&
        timeMappingEvidenceKey == null
