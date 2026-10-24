package com.openjump.app.data.export

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DataExportDao {
    @Query("SELECT id, athleteId, testingSessionId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes FROM assessments ORDER BY dateTime, id")
    suspend fun assessmentRows(): List<ExportAssessmentRow>

    @Query("SELECT id, assessmentId, ordinal, side, dropHeightCm, distanceCm FROM attempts ORDER BY assessmentId, ordinal, id")
    suspend fun attemptRows(): List<ExportAttemptRow>

    @Query("SELECT attemptId, key, ordinal, value, unit FROM attempt_metrics ORDER BY attemptId, ordinal, key")
    suspend fun jumpMetricRows(): List<ExportJumpMetricRow>

    @Query("SELECT id, athleteId, testingSessionId, exercise, loadKg, dateTime, analysisVersion, totalRepetitions, validRepetitions, bestMcv, setVelocityLoss, notes FROM encoder_sessions ORDER BY dateTime, id")
    suspend fun encoderRows(): List<ExportEncoderSessionRow>

    @Query("SELECT id, sessionId, ordinal, quality, reasons FROM encoder_repetitions ORDER BY sessionId, ordinal, id")
    suspend fun encoderRepetitionRows(): List<ExportEncoderRepetitionRow>

    @Query("SELECT repetitionId, key, value, unit, validity, reason FROM encoder_rep_metrics ORDER BY repetitionId, key")
    suspend fun encoderMetricRows(): List<ExportEncoderMetricRow>

    @Query("SELECT a.id, a.displayName, a.birthDate, a.sex FROM athletes AS a WHERE EXISTS (SELECT 1 FROM assessments AS m WHERE m.athleteId = a.id) OR EXISTS (SELECT 1 FROM encoder_sessions AS s WHERE s.athleteId = a.id) ORDER BY a.id")
    suspend fun athleteRows(): List<ExportAthleteRow>

    @Query("SELECT s.id, s.groupNameSnapshot FROM testing_sessions AS s WHERE EXISTS (SELECT 1 FROM assessments AS m WHERE m.testingSessionId = s.id) OR EXISTS (SELECT 1 FROM encoder_sessions AS e WHERE e.testingSessionId = s.id) OR EXISTS (SELECT 1 FROM fvp_assessments AS f WHERE f.testingSessionId = s.id) ORDER BY s.id")
    suspend fun testingRows(): List<ExportTestingRow>

    @Query("SELECT id, displayName, birthDate, sex FROM athletes WHERE (" +
        "EXISTS (SELECT 1 FROM assessments m WHERE m.athleteId = athletes.id) OR " +
        "EXISTS (SELECT 1 FROM encoder_sessions s WHERE s.athleteId = athletes.id) OR " +
        "EXISTS (SELECT 1 FROM fvp_assessments f WHERE f.athleteId = athletes.id)) AND " +
        "(:afterIdText IS NULL OR CAST(id AS TEXT) > :afterIdText) ORDER BY CAST(id AS TEXT) LIMIT :limit")
    suspend fun athletePage(afterIdText: String?, limit: Int): List<ExportAthleteRow>

    /** One keyset over both families; separate limited queries can drop tie rows. */
    @Query("SELECT * FROM (" +
        "SELECT a.id AS id, a.dateTime AS dateTime, CAST(a.id AS TEXT) AS idText, a.athleteId AS athleteId, a.testingSessionId AS testingSessionId, " +
        "ath.displayName AS athleteName, ath.birthDate AS athleteBirthDate, ath.sex AS athleteSex, ts.groupNameSnapshot AS groupNameSnapshot, 'jump' AS kind, " +
        "a.protocolId AS protocolId, CAST(NULL AS TEXT) AS exercise, CAST(NULL AS REAL) AS loadKg, CAST(NULL AS INTEGER) AS analysisVersion, " +
        "a.primaryMetricKey AS primaryMetricKey, a.primaryMetricValue AS primaryMetricValue, a.primaryMetricUnit AS primaryMetricUnit, a.notes AS notes, " +
        "(SELECT at.id FROM attempts at LEFT JOIN attempt_metrics pm ON pm.attemptId = at.id WHERE at.assessmentId = a.id AND pm.ordinal = 0 AND pm.key = a.primaryMetricKey AND pm.unit = a.primaryMetricUnit AND pm.value = a.primaryMetricValue ORDER BY at.ordinal, at.id LIMIT 1) AS primaryAttemptId, " +
        "(SELECT at.side FROM attempts at LEFT JOIN attempt_metrics pm ON pm.attemptId = at.id WHERE at.assessmentId = a.id AND pm.ordinal = 0 AND pm.key = a.primaryMetricKey AND pm.unit = a.primaryMetricUnit AND pm.value = a.primaryMetricValue ORDER BY at.ordinal, at.id LIMIT 1) AS primarySide, " +
        "(SELECT at.ordinal FROM attempts at LEFT JOIN attempt_metrics pm ON pm.attemptId = at.id WHERE at.assessmentId = a.id AND pm.ordinal = 0 AND pm.key = a.primaryMetricKey AND pm.unit = a.primaryMetricUnit AND pm.value = a.primaryMetricValue ORDER BY at.ordinal, at.id LIMIT 1) AS primaryOrdinal, " +
        "(SELECT at.dropHeightCm FROM attempts at LEFT JOIN attempt_metrics pm ON pm.attemptId = at.id WHERE at.assessmentId = a.id AND pm.ordinal = 0 AND pm.key = a.primaryMetricKey AND pm.unit = a.primaryMetricUnit AND pm.value = a.primaryMetricValue ORDER BY at.ordinal, at.id LIMIT 1) AS primaryDropHeightCm, " +
        "(SELECT at.distanceCm FROM attempts at LEFT JOIN attempt_metrics pm ON pm.attemptId = at.id WHERE at.assessmentId = a.id AND pm.ordinal = 0 AND pm.key = a.primaryMetricKey AND pm.unit = a.primaryMetricUnit AND pm.value = a.primaryMetricValue ORDER BY at.ordinal, at.id LIMIT 1) AS primaryDistanceCm, " +
        "CAST(NULL AS INTEGER) AS totalRepetitions, CAST(NULL AS INTEGER) AS validRepetitions, CAST(NULL AS REAL) AS bestMcv, CAST(NULL AS REAL) AS setVelocityLoss " +
        "FROM assessments a LEFT JOIN athletes ath ON ath.id = a.athleteId LEFT JOIN testing_sessions ts ON ts.id = a.testingSessionId " +
        "UNION ALL " +
        "SELECT s.id AS id, s.dateTime AS dateTime, CAST(s.id AS TEXT) AS idText, s.athleteId AS athleteId, s.testingSessionId AS testingSessionId, " +
        "ath.displayName AS athleteName, ath.birthDate AS athleteBirthDate, ath.sex AS athleteSex, ts.groupNameSnapshot AS groupNameSnapshot, 'encoder' AS kind, " +
        "CAST(NULL AS TEXT) AS protocolId, s.exercise AS exercise, s.loadKg AS loadKg, s.analysisVersion AS analysisVersion, " +
        "CAST(NULL AS TEXT) AS primaryMetricKey, CAST(NULL AS REAL) AS primaryMetricValue, CAST(NULL AS TEXT) AS primaryMetricUnit, s.notes AS notes, " +
        "CAST(NULL AS INTEGER) AS primaryAttemptId, CAST(NULL AS TEXT) AS primarySide, CAST(NULL AS INTEGER) AS primaryOrdinal, CAST(NULL AS REAL) AS primaryDropHeightCm, CAST(NULL AS REAL) AS primaryDistanceCm, " +
        "s.totalRepetitions AS totalRepetitions, s.validRepetitions AS validRepetitions, s.bestMcv AS bestMcv, s.setVelocityLoss AS setVelocityLoss " +
        "FROM encoder_sessions s LEFT JOIN athletes ath ON ath.id = s.athleteId LEFT JOIN testing_sessions ts ON ts.id = s.testingSessionId" +
        ") WHERE (:afterDate IS NULL OR dateTime > :afterDate OR (dateTime = :afterDate AND (kind > :afterKind OR (kind = :afterKind AND idText > :afterIdText)))) " +
        "ORDER BY dateTime, kind, idText LIMIT :limit")
    suspend fun measurementPage(afterDate: Long?, afterKind: String, afterIdText: String, limit: Int): List<ExportMeasurementRow>

    @Query("SELECT a.dateTime AS parentDateTime, a.id AS assessmentId, CAST(a.id AS TEXT) AS parentIdText, " +
        "at.id AS attemptId, at.ordinal AS attemptOrdinal, at.side, at.dropHeightCm, at.distanceCm, " +
        "CASE WHEN m.attemptId IS NULL THEN 0 ELSE 1 END AS metricPresent, " +
        "m.ordinal AS metricOrdinal, m.key AS metricKey, m.value AS metricValue, m.unit AS metricUnit " +
        "FROM assessments a JOIN attempts at ON at.assessmentId = a.id " +
        "LEFT JOIN attempt_metrics m ON m.attemptId = at.id " +
        "WHERE a.id IN (:parentIds) AND (:afterDate IS NULL OR " +
        "(a.dateTime, CAST(a.id AS TEXT), at.ordinal, at.id, CASE WHEN m.attemptId IS NULL THEN 0 ELSE 1 END, COALESCE(m.ordinal, -1), COALESCE(m.key, ''), COALESCE(m.unit, '')) > " +
        "(:afterDate, :afterIdText, :afterAttemptOrdinal, :afterAttemptId, :afterMetricPresent, :afterMetricOrdinal, :afterMetricKey, :afterMetricUnit)) " +
        "ORDER BY a.dateTime, CAST(a.id AS TEXT), at.ordinal, at.id, metricPresent, m.ordinal, m.key, m.unit LIMIT :limit")
    suspend fun jumpDetailPage(
        parentIds: List<Long>, afterDate: Long?, afterIdText: String, afterAttemptOrdinal: Int,
        afterAttemptId: Long, afterMetricPresent: Int, afterMetricOrdinal: Int,
        afterMetricKey: String, afterMetricUnit: String, limit: Int,
    ): List<ExportJumpDetailRow>

    /** CSV observation order is metric-key order within an attempt ordinal, not JSON child order. */
    @Query("SELECT a.dateTime AS parentDateTime, a.id AS assessmentId, CAST(a.id AS TEXT) AS parentIdText, " +
        "at.id AS attemptId, at.ordinal AS attemptOrdinal, at.side, at.dropHeightCm, at.distanceCm, " +
        "1 AS metricPresent, m.ordinal AS metricOrdinal, m.key AS metricKey, m.value AS metricValue, m.unit AS metricUnit " +
        "FROM assessments a JOIN attempts at ON at.assessmentId = a.id " +
        "JOIN attempt_metrics m ON m.attemptId = at.id " +
        "WHERE a.id IN (:parentIds) AND (:afterDate IS NULL OR " +
        "(a.dateTime, CAST(a.id AS TEXT), at.ordinal, m.key, m.unit, at.id, m.ordinal) > " +
        "(:afterDate, :afterIdText, :afterAttemptOrdinal, :afterMetricKey, :afterMetricUnit, :afterAttemptId, :afterMetricOrdinal)) " +
        "ORDER BY a.dateTime, CAST(a.id AS TEXT), at.ordinal, m.key, m.unit, at.id, m.ordinal LIMIT :limit")
    suspend fun jumpCsvDetailPage(
        parentIds: List<Long>, afterDate: Long?, afterIdText: String, afterAttemptOrdinal: Int,
        afterMetricKey: String, afterMetricUnit: String, afterAttemptId: Long,
        afterMetricOrdinal: Int, limit: Int,
    ): List<ExportJumpDetailRow>

    @Query("SELECT s.dateTime AS parentDateTime, s.id AS sessionId, CAST(s.id AS TEXT) AS parentIdText, " +
        "r.id AS repetitionId, r.ordinal AS repetitionOrdinal, r.quality, r.reasons, " +
        "CASE WHEN m.repetitionId IS NULL THEN 0 ELSE 1 END AS metricPresent, " +
        "m.key AS metricKey, m.value AS metricValue, m.unit AS metricUnit, m.validity AS metricValidity, m.reason AS metricReason " +
        "FROM encoder_sessions s JOIN encoder_repetitions r ON r.sessionId = s.id " +
        "LEFT JOIN encoder_rep_metrics m ON m.repetitionId = r.id " +
        "WHERE s.id IN (:parentIds) AND (:afterDate IS NULL OR " +
        "(s.dateTime, CAST(s.id AS TEXT), r.ordinal, r.id, CASE WHEN m.repetitionId IS NULL THEN 0 ELSE 1 END, COALESCE(m.key, ''), COALESCE(m.unit, '')) > " +
        "(:afterDate, :afterIdText, :afterRepetitionOrdinal, :afterRepetitionId, :afterMetricPresent, :afterMetricKey, :afterMetricUnit)) " +
        "ORDER BY s.dateTime, CAST(s.id AS TEXT), r.ordinal, r.id, metricPresent, m.key, m.unit LIMIT :limit")
    suspend fun encoderDetailPage(
        parentIds: List<Long>, afterDate: Long?, afterIdText: String, afterRepetitionOrdinal: Int,
        afterRepetitionId: Long, afterMetricPresent: Int, afterMetricKey: String,
        afterMetricUnit: String, limit: Int,
    ): List<ExportEncoderDetailRow>

    /**
     * Raíces F-V-P con su propio cursor keyset: la familia no comparte forma con `measurements`,
     * así que se pagina aparte y se emite después de las familias jump/encoder.
     */
    @Query("SELECT " + FVP_ROOT_COLUMNS + " FROM fvp_assessments a WHERE (:afterDate IS NULL OR " +
        "dateTime > :afterDate OR (dateTime = :afterDate AND CAST(a.id AS TEXT) > :afterIdText)) " +
        "ORDER BY a.dateTime, CAST(a.id AS TEXT) LIMIT :limit")
    suspend fun fvpPage(afterDate: Long?, afterIdText: String, limit: Int): List<ExportFvpAssessmentRow>

    /** Condiciones con sus intentos (`LEFT JOIN`: una condición sin intentos viaja con `attemptId` NULL). */
    @Query("SELECT " + FVP_DETAIL_COLUMNS + " FROM fvp_assessments a " +
        "JOIN fvp_conditions c ON c.assessmentId = a.id " +
        "LEFT JOIN fvp_attempts at ON at.conditionId = c.id " +
        "WHERE a.id IN (:parentIds) AND (:afterDate IS NULL OR " +
        "(a.dateTime, CAST(a.id AS TEXT), c.ordinal, c.id, COALESCE(at.ordinal, -1), COALESCE(at.id, -1)) > " +
        "(:afterDate, :afterIdText, :afterConditionOrdinal, :afterConditionId, :afterAttemptOrdinal, :afterAttemptId)) " +
        "ORDER BY a.dateTime, CAST(a.id AS TEXT), c.ordinal, c.id, at.ordinal, at.id LIMIT :limit")
    suspend fun fvpDetailPage(
        parentIds: List<Long>, afterDate: Long?, afterIdText: String, afterConditionOrdinal: Int,
        afterConditionId: Long, afterAttemptOrdinal: Int, afterAttemptId: Long, limit: Int,
    ): List<ExportFvpDetailRow>

    /** Compatibility API for backup-era tests; production uses the paged methods above. */
    @Transaction
    suspend fun snapshot(): DataExportSnapshot {
        val assessments = assessmentRows()
        val attempts = attemptRows()
        val encoder = encoderRows()
        return DataExportSnapshot(
            assessments = assessments,
            attempts = attempts,
            jumpMetrics = jumpMetricRows(),
            encoderSessions = encoder,
            encoderRepetitions = encoderRepetitionRows(),
            encoderMetrics = encoderMetricRows(),
            athletes = athleteRows(),
            testingSessions = testingRows(),
        )
    }
}

const val DATA_EXPORT_PAGE_SIZE = 256

private const val FVP_ROOT_COLUMNS = "a.id AS id, CAST(a.id AS TEXT) AS idText, a.sessionKey AS sessionKey, " +
    "a.athleteId AS athleteId, a.testingSessionId AS testingSessionId, a.protocolId AS protocolId, " +
    "a.dateTime AS dateTime, a.bodyMassKg AS bodyMassKg, a.bodyMassSource AS bodyMassSource, " +
    "a.pushOffMode AS pushOffMode, a.pushOffM AS pushOffM, a.pushOffSource AS pushOffSource, " +
    "a.pushOffLandmark AS pushOffLandmark, a.pushOffLandmarkHeightCm AS pushOffLandmarkHeightCm, " +
    "a.pushOffProtocol AS pushOffProtocol, a.pushOffMeasuredAt AS pushOffMeasuredAt, " +
    "a.pushOffCrouchCm AS pushOffCrouchCm, a.pushOffExtendedCm AS pushOffExtendedCm, " +
    "a.sourceFps AS sourceFps, a.algorithmVersion AS algorithmVersion, a.methodVersion AS methodVersion, " +
    "a.conditionCount AS conditionCount, a.f0N AS f0N, a.slopeFv AS slopeFv, a.sfvNsPerM AS sfvNsPerM, " +
    "a.v0Mps AS v0Mps, a.pmaxW AS pmaxW, a.r2 AS r2, a.vMinMps AS vMinMps, a.vMaxMps AS vMaxMps, " +
    "a.fMinN AS fMinN, a.fMaxN AS fMaxN, a.f0RelNPerKg AS f0RelNPerKg, " +
    "a.pmaxRelWPerKg AS pmaxRelWPerKg, a.warningsCsv AS warningsCsv, a.notes AS notes, " +
    // Procedencia B0 (Room 12), 1:1 con las columnas de `fvp_assessments`.
    "a.acquiredAt AS acquiredAt, a.acquisitionContextKind AS acquisitionContextKind, " +
    "a.acquisitionContextKey AS acquisitionContextKey, a.bodyMassMeasurement AS bodyMassMeasurement, " +
    "a.bodyMassMeasuredAt AS bodyMassMeasuredAt"

private const val FVP_DETAIL_COLUMNS = "a.dateTime AS parentDateTime, a.id AS assessmentId, " +
    "CAST(a.id AS TEXT) AS parentIdText, c.id AS conditionId, c.ordinal AS conditionOrdinal, " +
    "c.conditionKey AS conditionKey, c.addedLoadKg AS addedLoadKg, c.totalMassKg AS totalMassKg, " +
    "c.pushOffM AS pushOffM, c.selectedHeightM AS selectedHeightM, " +
    "c.selectedAttemptOrdinal AS selectedAttemptOrdinal, at.id AS attemptId, at.ordinal AS attemptOrdinal, " +
    "at.heightM AS heightM, at.method AS method, at.flightTimeS AS flightTimeS, at.sourceFps AS sourceFps, " +
    "at.sourceAssessmentId AS sourceAssessmentId, at.sourceAttemptOrdinal AS sourceAttemptOrdinal, " +
    "at.sourceProtocolId AS sourceProtocolId, at.valid AS attemptValid, " +
    "at.exclusionReason AS exclusionReason, at.notes AS attemptNotes, " +
    // Procedencia B0 (Room 12): condiciones e intentos, 1:1 con sus columnas.
    "c.addedLoadSource AS addedLoadSource, c.addedLoadMeasuredAt AS addedLoadMeasuredAt, " +
    "at.sourceRecordedAt AS sourceRecordedAt, at.sourceAthleteId AS sourceAthleteId, " +
    "at.acquiredAt AS acquiredAt, at.reuseConfirmedAt AS reuseConfirmedAt, " +
    "at.reuseConfirmationVersion AS reuseConfirmationVersion, at.takeoffPtsUs AS takeoffPtsUs, " +
    "at.takeoffPreviousPtsUs AS takeoffPreviousPtsUs, at.takeoffNextPtsUs AS takeoffNextPtsUs, " +
    "at.landingPtsUs AS landingPtsUs, at.landingPreviousPtsUs AS landingPreviousPtsUs, " +
    "at.landingNextPtsUs AS landingNextPtsUs, at.timeMapping AS timeMapping, " +
    "at.timeMappingEvidenceKey AS timeMappingEvidenceKey"

/** Raíz F-V-P tal como la pagina [DataExportDao.fvpPage]. */
data class ExportFvpAssessmentRow(
    val id: Long, val idText: String, val sessionKey: String, val athleteId: Long?, val testingSessionId: Long?,
    val protocolId: String, val dateTime: Long, val bodyMassKg: Double, val bodyMassSource: String,
    val pushOffMode: String, val pushOffM: Double, val pushOffSource: String, val pushOffLandmark: String?,
    val pushOffLandmarkHeightCm: Double?, val pushOffProtocol: String?, val pushOffMeasuredAt: Long?,
    val pushOffCrouchCm: Double?, val pushOffExtendedCm: Double?, val sourceFps: Double?,
    val algorithmVersion: String, val methodVersion: String, val conditionCount: Int, val f0N: Double,
    val slopeFv: Double, val sfvNsPerM: Double, val v0Mps: Double?, val pmaxW: Double?, val r2: Double?,
    val vMinMps: Double, val vMaxMps: Double, val fMinN: Double, val fMaxN: Double,
    val f0RelNPerKg: Double?, val pmaxRelWPerKg: Double?, val warningsCsv: String, val notes: String? = null,
    // Procedencia B0 (Room 12). Sin default: si falta en la proyeccion, Room falla al compilar.
    val acquiredAt: Long?, val acquisitionContextKind: String, val acquisitionContextKey: String?,
    val bodyMassMeasurement: String, val bodyMassMeasuredAt: Long?,
)

/** Fila plana condición+intento de la familia F-V-P. */
data class ExportFvpDetailRow(
    val parentDateTime: Long, val assessmentId: Long, val parentIdText: String, val conditionId: Long,
    val conditionOrdinal: Int, val conditionKey: String, val addedLoadKg: Double, val totalMassKg: Double,
    val pushOffM: Double, val selectedHeightM: Double, val selectedAttemptOrdinal: Int?,
    val attemptId: Long?, val attemptOrdinal: Int?, val heightM: Double?, val method: String?,
    val flightTimeS: Double?, val sourceFps: Double?, val sourceAssessmentId: Long?,
    val sourceAttemptOrdinal: Int?, val sourceProtocolId: String?, val attemptValid: Boolean?,
    val exclusionReason: String?, val attemptNotes: String?,
    // Procedencia B0 (Room 12). Sin default: si falta en la proyeccion, Room falla al compilar.
    val addedLoadSource: String, val addedLoadMeasuredAt: Long?,
    val sourceRecordedAt: Long?, val sourceAthleteId: Long?, val acquiredAt: Long?,
    val reuseConfirmedAt: Long?, val reuseConfirmationVersion: String?,
    val takeoffPtsUs: Long?, val takeoffPreviousPtsUs: Long?, val takeoffNextPtsUs: Long?,
    val landingPtsUs: Long?, val landingPreviousPtsUs: Long?, val landingNextPtsUs: Long?,
    // `timeMapping` llega NULL en una condición SIN intentos (LEFT JOIN): solo se usa cuando
    // `attemptId != null`, donde la columna Room es NOT NULL.
    val timeMapping: String?, val timeMappingEvidenceKey: String?,
)

data class ExportAssessmentRow(val id: Long, val athleteId: Long?, val testingSessionId: Long?, val protocolId: String, val dateTime: Long, val primaryMetricKey: String, val primaryMetricValue: Double, val primaryMetricUnit: String, val notes: String? = null)
data class ExportAttemptRow(val id: Long, val assessmentId: Long, val ordinal: Int, val side: String?, val dropHeightCm: Double?, val distanceCm: Double?)
data class ExportJumpMetricRow(val attemptId: Long, val key: String, val ordinal: Int, val value: Double, val unit: String)
data class ExportEncoderSessionRow(val id: Long, val athleteId: Long?, val testingSessionId: Long?, val exercise: String, val loadKg: Double, val dateTime: Long, val analysisVersion: Int, val totalRepetitions: Int, val validRepetitions: Int, val bestMcv: Double?, val setVelocityLoss: Double?, val notes: String? = null)
data class ExportEncoderRepetitionRow(val id: Long, val sessionId: Long, val ordinal: Int, val quality: String, val reasons: String)
data class ExportEncoderMetricRow(val repetitionId: Long, val key: String, val value: Double?, val unit: String, val validity: String, val reason: String?)
data class ExportAthleteRow(val id: Long, val displayName: String, val birthDate: Long?, val sex: String?)
data class ExportTestingRow(val id: Long, val groupNameSnapshot: String?)

data class ExportMeasurementRow(
    val id: Long, val dateTime: Long, val idText: String, val athleteId: Long?, val testingSessionId: Long?,
    val athleteName: String?, val athleteBirthDate: Long?, val athleteSex: String?, val groupNameSnapshot: String?,
    val protocolId: String? = null, val exercise: String? = null, val loadKg: Double? = null, val analysisVersion: Int? = null,
    val primaryMetricKey: String? = null, val primaryMetricValue: Double? = null, val primaryMetricUnit: String? = null,
    val primaryAttemptId: Long? = null, val primarySide: String? = null, val primaryOrdinal: Int? = null,
    val primaryDropHeightCm: Double? = null, val primaryDistanceCm: Double? = null,
    val totalRepetitions: Int? = null, val validRepetitions: Int? = null, val bestMcv: Double? = null, val setVelocityLoss: Double? = null,
    val notes: String? = null,
    /** Internal SQL tie-breaker; never serialized. */
    val kind: String? = null,
)

data class ExportJumpDetailRow(
    val parentDateTime: Long, val assessmentId: Long, val parentIdText: String, val attemptId: Long,
    val attemptOrdinal: Int, val side: String?, val dropHeightCm: Double?, val distanceCm: Double?,
    val metricPresent: Int, val metricOrdinal: Int?, val metricKey: String?, val metricValue: Double?, val metricUnit: String?,
)

data class ExportEncoderDetailRow(
    val parentDateTime: Long, val sessionId: Long, val parentIdText: String, val repetitionId: Long,
    val repetitionOrdinal: Int, val quality: String, val reasons: String, val metricPresent: Int,
    val metricKey: String?, val metricValue: Double?, val metricUnit: String?, val metricValidity: String?, val metricReason: String?,
)

data class DataExportSnapshot(val assessments: List<ExportAssessmentRow>, val attempts: List<ExportAttemptRow>, val jumpMetrics: List<ExportJumpMetricRow>, val encoderSessions: List<ExportEncoderSessionRow>, val encoderRepetitions: List<ExportEncoderRepetitionRow>, val encoderMetrics: List<ExportEncoderMetricRow>, val athletes: List<ExportAthleteRow>, val testingSessions: List<ExportTestingRow>)
