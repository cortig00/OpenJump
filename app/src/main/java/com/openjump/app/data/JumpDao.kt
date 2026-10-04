package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface JumpDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssessment(assessment: AssessmentEntity): Long

    @Insert
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Insert
    suspend fun insertEvents(events: List<AttemptEventEntity>)

    @Insert
    suspend fun insertMetrics(metrics: List<AttemptMetricEntity>)

    @Insert
    suspend fun insertCalibration(calibration: AttemptCalibrationEntity)

    @Insert
    suspend fun insertSpatialMarks(marks: List<AttemptSpatialMarkEntity>)

    @Query("SELECT id FROM assessments WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun assessmentIdForSession(sessionKey: String): Long?

    @Query("UPDATE assessments SET notes = :notes WHERE id = :assessmentId")
    suspend fun updateNotes(assessmentId: Long, notes: String?): Int

    @Query("SELECT athleteId, testingSessionId FROM assessments WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun ownershipForSession(sessionKey: String): AssessmentOwnership?

    /** All saved video references across jump and encoder sessions, independent of selected athlete. */
    @Query(
        """
        SELECT videoUri FROM attempts WHERE videoUri IS NOT NULL AND TRIM(videoUri) <> ''
        UNION
        SELECT videoUri FROM encoder_sessions WHERE videoUri IS NOT NULL AND TRIM(videoUri) <> ''
        """,
    )
    suspend fun savedVideoUris(): List<String>

    @Query("SELECT displayName FROM athletes WHERE id = :athleteId LIMIT 1")
    suspend fun athleteName(athleteId: Long): String?

    @Query("SELECT id FROM athletes WHERE id = :athleteId AND archivedAt IS NULL LIMIT 1")
    suspend fun activeAthleteId(athleteId: Long): Long?

    /** Testing context is validated atomically because root testingSessionId has no physical FK. */
    @Query(
        """
        SELECT COUNT(*) FROM testing_sessions AS s
        INNER JOIN testing_participants AS p ON p.sessionId = s.id
        WHERE s.id = :testingSessionId AND s.family = 'JUMP' AND s.status = 'ACTIVE'
          AND s.protocolId = :protocolId
          AND (s.side = :side OR (s.side IS NULL AND :side IS NULL))
          AND (s.dropHeightCm = :dropHeightCm OR (s.dropHeightCm IS NULL AND :dropHeightCm IS NULL))
          AND p.athleteId = :athleteId AND p.skippedAt IS NULL
          AND (SELECT COUNT(*) FROM assessments AS existing
               WHERE existing.testingSessionId = s.id AND existing.athleteId = p.athleteId) < s.targetAttempts
        """,
    )
    suspend fun activeTestingParticipant(
        testingSessionId: Long,
        athleteId: Long,
        protocolId: String,
        side: String?,
        dropHeightCm: Double?,
    ): Int

    @Query("UPDATE testing_sessions SET status = 'COMPLETED', completedAt = :completedAt, updatedAt = :completedAt WHERE id = :testingSessionId AND family = 'JUMP' AND status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM testing_participants p WHERE p.sessionId = :testingSessionId AND p.skippedAt IS NULL AND (SELECT COUNT(*) FROM assessments a WHERE a.testingSessionId = :testingSessionId AND a.athleteId = p.athleteId) < testing_sessions.targetAttempts)")
    suspend fun autoCompleteTestingSession(testingSessionId: Long, completedAt: Long = System.currentTimeMillis())

    /** A unique session key makes retries and accidental double taps idempotent. */
    @Transaction
    suspend fun save(record: PersistableAssessment): Long {
        val existingId = assessmentIdForSession(record.assessment.sessionKey)
        if (existingId != null) {
            val existing = ownershipForSession(record.assessment.sessionKey)
                ?: error("The assessment exists but its ownership could not be read.")
            check(existing.athleteId == record.assessment.athleteId &&
                existing.testingSessionId == record.assessment.testingSessionId) {
                "Assessment sessionKey conflict: athleteId/testingSessionId differ."
            }
            return existingId
        }
        val athleteId = requireNotNull(record.assessment.athleteId) {
            "A new assessment requires an athlete."
        }
        val children = record.children()
        check(children.isNotEmpty()) { "An assessment requires at least one attempt." }
        check(children.map { it.attempt.ordinal }.sorted() == children.indices.toList()) {
            "Attempt ordinals must be contiguous and unique."
        }
        children.flatMap { it.events }.forEach { event ->
            check(event.frameIndex == null || event.frameIndex >= 0) { "Event frameIndex must be non-negative." }
            if (event.frameIndex == null) {
                check(event.previousPtsUs == null && event.nextPtsUs == null) {
                    "PTS neighborhood requires a frameIndex."
                }
            }
            check(event.previousPtsUs == null || event.previousPtsUs < event.ptsUs) {
                "Event previousPtsUs must precede ptsUs."
            }
            check(event.nextPtsUs == null || event.ptsUs < event.nextPtsUs) {
                "Event nextPtsUs must follow ptsUs."
            }
        }
        if (record.assessment.protocolId == com.openjump.app.protocol.ProtocolId.ASYMMETRY.storageKey) {
            check(record.assessment.testingSessionId == null) { "ASYMMETRY no está disponible en Testing." }
            check(children.size % 2 == 0 && children.size in 2..10) { "La comparación bilateral no contiene 2N intentos." }
            val half = children.size / 2
            children.forEachIndexed { index, child ->
                val expected = if (index < half) "LEFT" else "RIGHT"
                check(child.attempt.side == expected) { "Los lados bilaterales no están ordenados." }
                check(child.attempt.ordinal == index) { "Los ordinales bilaterales no son válidos." }
            }
        }
        check(activeAthleteId(athleteId) != null) { "The selected athlete is not active." }
        record.assessment.testingSessionId?.let { testingSessionId ->
            check(
                activeTestingParticipant(
                    testingSessionId,
                    athleteId,
                    record.assessment.protocolId,
                    children.single().attempt.side,
                    children.single().attempt.dropHeightCm,
                ) == 1,
            ) {
                "The testing session or participant is not active."
            }
        }
        val assessmentId = insertAssessment(record.assessment)
        check(assessmentId != -1L) { "The assessment could not be inserted." }
        children.forEach { child ->
            val attemptId = insertAttempt(child.attempt.copy(assessmentId = assessmentId))
            insertEvents(child.events.map { it.copy(attemptId = attemptId) })
            insertMetrics(child.metrics.map { it.copy(attemptId = attemptId) })
            child.calibration?.let { insertCalibration(it.copy(attemptId = attemptId)) }
            if (child.spatialMarks.isNotEmpty()) {
                insertSpatialMarks(child.spatialMarks.map { it.copy(attemptId = attemptId) })
            }
        }
        record.assessment.testingSessionId?.let { testingId -> autoCompleteTestingSession(testingId) }
        return assessmentId
    }

    @Transaction
    @Query("SELECT * FROM assessments WHERE id = :assessmentId LIMIT 1")
    suspend fun assessmentWithAttempts(assessmentId: Long): AssessmentWithAttempts?

    @Query("SELECT testingSessionId FROM assessments WHERE id = :assessmentId LIMIT 1")
    suspend fun testingSessionIdForAssessment(assessmentId: Long): Long?

    @Query("DELETE FROM assessments WHERE id = :assessmentId")
    suspend fun deleteAssessmentById(assessmentId: Long): Int

    @Query(
        """
        UPDATE testing_sessions
        SET status = 'ACTIVE', completedAt = NULL, updatedAt = :updatedAt
        WHERE id = :testingSessionId AND family = 'JUMP' AND status = 'COMPLETED'
          AND EXISTS (
            SELECT 1 FROM testing_participants AS p
            WHERE p.sessionId = :testingSessionId AND p.skippedAt IS NULL
              AND (SELECT COUNT(*) FROM assessments AS a
                   WHERE a.testingSessionId = :testingSessionId AND a.athleteId = p.athleteId) < testing_sessions.targetAttempts
          )
        """,
    )
    suspend fun reopenTestingSessionAfterDeletion(testingSessionId: Long, updatedAt: Long)

    /** Deletes the root and all attempt children through Room foreign-key cascades. */
    @Transaction
    suspend fun deleteAssessment(assessmentId: Long, updatedAt: Long = System.currentTimeMillis()): Boolean {
        val testingSessionId = testingSessionIdForAssessment(assessmentId)
        val deleted = deleteAssessmentById(assessmentId) == 1
        if (deleted && testingSessionId != null) {
            reopenTestingSessionAfterDeletion(testingSessionId, updatedAt)
        }
        return deleted
    }

    @Query(
        """
        SELECT a.id, a.dateTime, a.athleteId, ath.displayName AS athleteName,
               a.protocolId, a.primaryMetricKey, a.primaryMetricValue,
               a.primaryMetricUnit, CASE WHEN a.protocolId = 'ASYMMETRY' THEN NULL ELSE t.side END AS side,
               a.notes IS NOT NULL AS hasNotes
        FROM assessments AS a
        LEFT JOIN athletes AS ath ON ath.id = a.athleteId
        LEFT JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        ORDER BY a.dateTime DESC
        LIMIT 5
        """,
    )
    fun recentFive(): Flow<List<RecentMeasurement>>

    /** Personal/active-athlete home feed. The athlete predicate is evaluated before LIMIT. */
    @Query(
        """
        SELECT a.id, a.dateTime, a.athleteId, ath.displayName AS athleteName,
               a.protocolId, a.primaryMetricKey, a.primaryMetricValue,
               a.primaryMetricUnit, CASE WHEN a.protocolId = 'ASYMMETRY' THEN NULL ELSE t.side END AS side,
               a.notes IS NOT NULL AS hasNotes
        FROM assessments AS a
        LEFT JOIN athletes AS ath ON ath.id = a.athleteId
        LEFT JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId
        ORDER BY a.dateTime DESC
        LIMIT 5
        """,
    )
    fun recentFiveForAthlete(athleteId: Long): Flow<List<RecentMeasurement>>

    @Query(
        """
        SELECT a.id, a.dateTime, a.athleteId, ath.displayName AS athleteName,
               a.protocolId, a.primaryMetricKey, a.primaryMetricValue,
               a.primaryMetricUnit, t.side, a.notes IS NOT NULL AS hasNotes
        FROM assessments AS a
        LEFT JOIN athletes AS ath ON ath.id = a.athleteId
        LEFT JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        ORDER BY a.dateTime DESC
        """,
    )
    fun history(): Flow<List<RecentMeasurement>>

    /** Bounded keyset page. All filters are evaluated by SQLite before LIMIT. */
    @Query(
        """
        SELECT a.id AS id, a.dateTime, 'JUMP' AS kind, a.athleteId,
               ath.displayName AS athleteName, a.protocolId, CASE WHEN a.protocolId = 'ASYMMETRY' THEN NULL ELSE t.side END AS side,
               a.primaryMetricKey, a.primaryMetricValue, a.primaryMetricUnit,
               NULL AS exercise, NULL AS loadKg, NULL AS validRepetitions,
               NULL AS bestMcv, a.notes IS NOT NULL AS hasNotes
        FROM assessments AS a
        LEFT JOIN athletes AS ath ON ath.id = a.athleteId
        LEFT JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE ((:unassignedOnly = 1 AND a.athleteId IS NULL)
          OR (:unassignedOnly = 0 AND (:athleteId IS NULL OR a.athleteId = :athleteId)))
          AND (:groupId IS NULL OR EXISTS (
              SELECT 1 FROM athlete_group_cross_ref AS membership
              WHERE membership.athleteId = a.athleteId AND membership.groupId = :groupId
          ))
          AND (:search IS NULL OR
               REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                 REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(ath.displayName,
                   'á', 'a'), 'Á', 'a'), 'é', 'e'), 'É', 'e'), 'í', 'i'), 'Í', 'i'),
                   'ó', 'o'), 'Ó', 'o'), 'ú', 'u'), 'Ú', 'u'), 'ü', 'u'), 'Ü', 'u'),
                   'ñ', 'n'), 'Ñ', 'n') LIKE '%' || :search || '%' COLLATE NOCASE
               OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                 REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(a.protocolId,
                   'á', 'a'), 'Á', 'a'), 'é', 'e'), 'É', 'e'), 'í', 'i'), 'Í', 'i'),
                   'ó', 'o'), 'Ó', 'o'), 'ú', 'u'), 'Ú', 'u'), 'ü', 'u'), 'Ü', 'u'),
                   'ñ', 'n'), 'Ñ', 'n') LIKE '%' || :search || '%' COLLATE NOCASE)
          AND (:protocolOrExercise IS NULL OR a.protocolId = :protocolOrExercise)
          AND (:dateFrom IS NULL OR a.dateTime >= :dateFrom)
          AND (:dateTo IS NULL OR a.dateTime <= :dateTo)
          AND (:metricMin IS NULL OR a.primaryMetricValue >= :metricMin)
          AND (:metricMax IS NULL OR a.primaryMetricValue <= :metricMax)
          AND (:cursorDateTime IS NULL OR a.dateTime < :cursorDateTime
            OR (a.dateTime = :cursorDateTime AND
                (a.id < :cursorId OR (:cursorKind = 'ENCODER' AND a.id = :cursorId))))
        ORDER BY a.dateTime DESC, a.id DESC
        LIMIT :limit
        """,
    )
    suspend fun historyPage(
        search: String?, athleteId: Long?, unassignedOnly: Boolean, groupId: Long?, protocolOrExercise: String?,
        dateFrom: Long?, dateTo: Long?, metricMin: Double?, metricMax: Double?,
        cursorDateTime: Long?, cursorId: Long, cursorKind: String, limit: Int,
    ): List<AthleteHistoryPage>

    /** Small, reactive PR projection: ordinary roots plus each unilateral attempt in a bilateral root. */
    @Query(
        """
        SELECT a.id AS assessmentId, t.ordinal AS attemptOrdinal, a.dateTime,
               a.protocolId, t.side, t.dropHeightCm, a.primaryMetricKey AS metricKey,
               a.primaryMetricValue AS value, a.primaryMetricUnit AS unit, 0 AS fromBilateral
        FROM assessments a JOIN attempts t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId AND a.protocolId IN
              ('CMJ', 'SJ', 'ABALAKOV', 'UNILATERAL', 'DROP_JUMP', 'HORIZONTAL')
        UNION ALL
        SELECT a.id AS assessmentId, t.ordinal AS attemptOrdinal, a.dateTime,
               'UNILATERAL' AS protocolId, t.side, t.dropHeightCm,
               m.`key` AS metricKey, m.value, m.unit, 1 AS fromBilateral
        FROM assessments a JOIN attempts t ON t.assessmentId = a.id
             JOIN attempt_metrics m ON m.attemptId = t.id AND m.`key` = 'HEIGHT_CM' AND m.ordinal = 0
        WHERE a.athleteId = :athleteId AND a.protocolId = 'ASYMMETRY'
        ORDER BY dateTime, assessmentId, attemptOrdinal
        """,
    )
    fun personalRecordCandidates(athleteId: Long): Flow<List<PersonalRecordCandidate>>

    @Query("SELECT COUNT(*) AS total, MAX(dateTime) AS lastDateTime FROM assessments WHERE athleteId = :athleteId")
    fun athleteSummary(athleteId: Long): Flow<AthleteJumpSummary>

    @Query("SELECT protocolId, COUNT(*) AS count FROM assessments WHERE athleteId = :athleteId GROUP BY protocolId ORDER BY protocolId")
    fun athleteProtocolCounts(athleteId: Long): Flow<List<AthleteProtocolCount>>

    /** One deterministic maximum per protocol and side; LEFT and RIGHT are never combined. */
    @Query(
        """
        SELECT a.id AS assessmentId, a.protocolId, t.side, a.primaryMetricValue AS value,
               a.primaryMetricUnit AS unit, a.dateTime
        FROM assessments AS a
        JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId
          AND a.protocolId != 'ASYMMETRY'
          AND NOT EXISTS (
            SELECT 1
            FROM assessments AS better
            JOIN attempts AS betterAttempt
              ON betterAttempt.assessmentId = better.id AND betterAttempt.ordinal = 0
            WHERE better.athleteId = a.athleteId
              AND better.protocolId = a.protocolId
              AND (betterAttempt.side = t.side OR (betterAttempt.side IS NULL AND t.side IS NULL))
              AND (
                better.primaryMetricValue > a.primaryMetricValue
                OR (better.primaryMetricValue = a.primaryMetricValue AND better.dateTime < a.dateTime)
                OR (better.primaryMetricValue = a.primaryMetricValue AND better.dateTime = a.dateTime AND better.id < a.id)
              )
          )
        ORDER BY a.protocolId, t.side, a.dateTime, a.id
        """,
    )
    fun athleteJumpRecords(athleteId: Long): Flow<List<AthleteJumpRecord>>

    /** Full-history aggregate; recent is selected by dateTime and id, not by a bounded evolution page. */
    @Query(
        """
        SELECT COUNT(*) AS count,
               AVG(a.primaryMetricValue) AS average,
               MAX(a.primaryMetricValue) AS best,
               (SELECT latest.primaryMetricValue
                FROM assessments AS latest
                JOIN attempts AS latestAttempt
                  ON latestAttempt.assessmentId = latest.id AND latestAttempt.ordinal = 0
                WHERE latest.athleteId = :athleteId AND latest.protocolId = :protocolId
                  AND latest.primaryMetricUnit = :unit
                  AND (latestAttempt.side = :side OR (latestAttempt.side IS NULL AND :side IS NULL))
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recent,
               (SELECT latest.dateTime
                FROM assessments AS latest
                JOIN attempts AS latestAttempt
                  ON latestAttempt.assessmentId = latest.id AND latestAttempt.ordinal = 0
                WHERE latest.athleteId = :athleteId AND latest.protocolId = :protocolId
                  AND latest.primaryMetricUnit = :unit
                  AND (latestAttempt.side = :side OR (latestAttempt.side IS NULL AND :side IS NULL))
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recentDateTime,
               (SELECT latest.id
                FROM assessments AS latest
                JOIN attempts AS latestAttempt
                  ON latestAttempt.assessmentId = latest.id AND latestAttempt.ordinal = 0
                WHERE latest.athleteId = :athleteId AND latest.protocolId = :protocolId
                  AND latest.primaryMetricUnit = :unit
                  AND (latestAttempt.side = :side OR (latestAttempt.side IS NULL AND :side IS NULL))
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recentId
        FROM assessments AS a
        JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId AND a.protocolId = :protocolId
          AND a.protocolId != 'ASYMMETRY'
          AND a.primaryMetricUnit = :unit
          AND (t.side = :side OR (t.side IS NULL AND :side IS NULL))
        """,
    )
    suspend fun athleteJumpStats(
        athleteId: Long,
        protocolId: String,
        side: String?,
        unit: String,
    ): AthleteSeriesStats

    @Query(
        """
        SELECT a.id AS id, a.dateTime, a.primaryMetricValue AS value,
               a.primaryMetricUnit AS unit, 'JUMP' AS kind
        FROM assessments AS a
        JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId AND a.protocolId = :protocolId
          AND a.protocolId != 'ASYMMETRY'
          AND (t.side = :side OR (t.side IS NULL AND :side IS NULL))
        ORDER BY a.dateTime DESC, a.id DESC
        LIMIT 50
        """,
    )
    suspend fun athleteJumpEvolution(athleteId: Long, protocolId: String, side: String?): List<AthleteEvolutionPoint>

    @Query(
        """
        SELECT a.id AS id, a.dateTime, 'JUMP' AS kind, a.athleteId,
               ath.displayName AS athleteName, a.protocolId, CASE WHEN a.protocolId = 'ASYMMETRY' THEN NULL ELSE t.side END AS side,
               a.primaryMetricKey, a.primaryMetricValue, a.primaryMetricUnit,
               NULL AS exercise, NULL AS loadKg, NULL AS validRepetitions,
               NULL AS bestMcv, a.notes IS NOT NULL AS hasNotes
        FROM assessments AS a
        LEFT JOIN athletes AS ath ON ath.id = a.athleteId
        LEFT JOIN attempts AS t ON t.assessmentId = a.id AND t.ordinal = 0
        WHERE a.athleteId = :athleteId
          AND (:cursorDateTime IS NULL OR a.dateTime < :cursorDateTime
            OR (a.dateTime = :cursorDateTime AND
                (a.id < :cursorId OR (:cursorKind = 'ENCODER' AND a.id = :cursorId))))
        ORDER BY a.dateTime DESC, a.id DESC
        LIMIT 20
        """,
    )
    suspend fun athleteHistoryPage(
        athleteId: Long,
        cursorDateTime: Long?,
        cursorId: Long,
        cursorKind: String,
    ): List<AthleteHistoryPage>
}
