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
const val MANUAL_BACKUP_ROOM_VERSION = 10
private const val LEGACY_MANUAL_BACKUP_ROOM_VERSION = 9
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

data class ManualBackupSummary(val createdAtEpochMillis: Long, val formatVersion: Int, val athleteCount: Int, val groupCount: Int, val testingSessionCount: Int, val assessmentCount: Int, val encoderSessionCount: Int) {
    companion object { fun from(document: ManualBackupDocument) = with(document.tables) { ManualBackupSummary(document.createdAtEpochMillis, document.formatVersion, athletes.size, athleteGroups.size, testingSessions.size, assessments.size, encoderSessions.size) } }
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

private fun finite(value: Double?, field: String) { require(value == null || value.isFinite()) { "$field must be finite" } }

fun ManualBackupDocument.validate(): ManualBackupDocument {
    ManualBackupPreflight.checkDocument(this)
    require(contract == MANUAL_BACKUP_CONTRACT) { "Not an OpenJump manual backup" }
    require(formatVersion == MANUAL_BACKUP_FORMAT_VERSION) { "Unsupported manual backup version" }
    require(sourceRoomSchemaVersion == MANUAL_BACKUP_ROOM_VERSION || sourceRoomSchemaVersion == LEGACY_MANUAL_BACKUP_ROOM_VERSION) { "Unsupported Room schema version" }
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
    listOf(
        athleteIds to "athlete id",
        groupIds to "group id",
        testingIds to "testing session id",
        assessmentIds to "assessment id",
        attemptIds to "attempt id",
        encoderIds to "encoder session id",
        repetitionIds to "repetition id",
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
                // alphabetical order would put LANDING before MOVEMENT_START.
                val eventsByKey = eventsByAttempt[attempt.id].orEmpty()
                    .associateBy { it.type to it.ordinal }
                val ordered = listOf("MOVEMENT_START", "TAKEOFF", "LANDING").map { type ->
                    requireNotNull(eventsByKey[type to 0]) {
                        "Missing bilateral event $type for attempt ${attempt.id}."
                    }
                }
                require(ordered.zipWithNext().all { (first, second) -> second.ptsUs > first.ptsUs })
            }
            else -> {
                val definition = requireNotNull(ProtocolCatalog.find(protocol))
                val byKey = eventsByAttempt[attempt.id].orEmpty()
                    .associateBy { it.type to it.ordinal }
                val required = definition.requiredEvents.map { event ->
                    requireNotNull(byKey[event.storageKey to 0])
                }
                require(required.zipWithNext().all { (first, second) -> second.ptsUs > first.ptsUs })
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
    return this
}

private fun <T> unique(values: List<T>, field: String): Set<T> { require(values.distinct().size == values.size) { "Duplicate $field" }; return values.toSet() }
private fun ManualBackupTables.allLists() = listOf(athletes, athleteGroups, athleteGroupCrossRef, testingSessions, testingParticipants, assessments, attempts, attemptEvents, attemptMetrics, attemptCalibrations, attemptSpatialMarks, encoderSessions, encoderSamples, encoderRepetitions, encoderRepMetrics)
