package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EncoderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: EncoderSessionEntity): Long

    @Insert
    suspend fun insertSamples(samples: List<EncoderSampleEntity>)

    @Insert
    suspend fun insertRepetition(repetition: EncoderRepetitionEntity): Long

    @Insert
    suspend fun insertMetrics(metrics: List<EncoderRepMetricEntity>)

    @Query("SELECT id FROM encoder_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun sessionIdForKey(sessionKey: String): Long?

    @Query("UPDATE encoder_sessions SET notes = :notes WHERE id = :sessionId")
    suspend fun updateNotes(sessionId: Long, notes: String?): Int

    @Query("SELECT athleteId, testingSessionId FROM encoder_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun ownershipForKey(sessionKey: String): AssessmentOwnership?

    @Query("SELECT displayName FROM athletes WHERE id = :athleteId LIMIT 1")
    suspend fun athleteName(athleteId: Long): String?

    @Query("SELECT id FROM athletes WHERE id = :athleteId AND archivedAt IS NULL LIMIT 1")
    suspend fun activeAthleteId(athleteId: Long): Long?

    /** Testing context is validated atomically because root testingSessionId has no physical FK. */
    @Query(
        """
        SELECT COUNT(*) FROM testing_sessions AS s
        INNER JOIN testing_participants AS p ON p.sessionId = s.id
        WHERE s.id = :testingSessionId AND s.family = 'ENCODER' AND s.status = 'ACTIVE'
          AND s.exercise = :exercise
          AND ABS(s.loadKg - :loadKg) < 0.000001
          AND ABS(s.plateDiameterCm - :plateDiameterCm) < 0.000001
          AND p.athleteId = :athleteId AND p.skippedAt IS NULL
          AND (SELECT COUNT(*) FROM encoder_sessions AS existing
               WHERE existing.testingSessionId = s.id AND existing.athleteId = p.athleteId) < s.targetAttempts
        """,
    )
    suspend fun activeTestingParticipant(
        testingSessionId: Long,
        athleteId: Long,
        exercise: String,
        loadKg: Double,
        plateDiameterCm: Double,
    ): Int

    @Query("UPDATE testing_sessions SET status = 'COMPLETED', completedAt = :completedAt, updatedAt = :completedAt WHERE id = :testingSessionId AND family = 'ENCODER' AND status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM testing_participants p WHERE p.sessionId = :testingSessionId AND p.skippedAt IS NULL AND (SELECT COUNT(*) FROM encoder_sessions e WHERE e.testingSessionId = :testingSessionId AND e.athleteId = p.athleteId) < testing_sessions.targetAttempts)")
    suspend fun autoCompleteTestingSession(testingSessionId: Long, completedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun save(record: PersistableEncoderSession): Long {
        val existingId = sessionIdForKey(record.session.sessionKey)
        if (existingId != null) {
            val existing = ownershipForKey(record.session.sessionKey)
                ?: error("The encoder session exists but its ownership could not be read.")
            check(existing.athleteId == record.session.athleteId &&
                existing.testingSessionId == record.session.testingSessionId) {
                "Encoder sessionKey conflict: athleteId/testingSessionId differ."
            }
            return existingId
        }
        val athleteId = requireNotNull(record.session.athleteId) {
            "A new encoder session requires an athlete."
        }
        check(activeAthleteId(athleteId) != null) { "The selected athlete is not active." }
        record.session.testingSessionId?.let { testingSessionId ->
            check(
                activeTestingParticipant(
                    testingSessionId,
                    athleteId,
                    record.session.exercise,
                    record.session.loadKg,
                    requireNotNull(record.sessionPlateDiameterCm) {
                        "An encoder testing session requires its plate diameter snapshot."
                    },
                ) == 1,
            ) {
                "The testing session or participant is not active."
            }
        }
        val sessionId = insertSession(record.session)
        check(sessionId != -1L) { "The encoder session could not be inserted." }
        if (record.samples.isNotEmpty()) insertSamples(record.samples.map { it.copy(sessionId = sessionId) })
        record.repetitions.forEach { child ->
            val repetitionId = insertRepetition(child.repetition.copy(sessionId = sessionId))
            if (child.metrics.isNotEmpty()) insertMetrics(child.metrics.map { it.copy(repetitionId = repetitionId) })
        }
        record.session.testingSessionId?.let { testingId -> autoCompleteTestingSession(testingId) }
        return sessionId
    }

    @Transaction
    @Query("SELECT * FROM encoder_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun sessionWithData(sessionId: Long): EncoderSessionWithData?

    @Query("SELECT testingSessionId FROM encoder_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun testingSessionIdForEncoderSession(sessionId: Long): Long?

    @Query("DELETE FROM encoder_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long): Int

    @Query(
        """
        UPDATE testing_sessions
        SET status = 'ACTIVE', completedAt = NULL, updatedAt = :updatedAt
        WHERE id = :testingSessionId AND family = 'ENCODER' AND status = 'COMPLETED'
          AND EXISTS (
            SELECT 1 FROM testing_participants AS p
            WHERE p.sessionId = :testingSessionId AND p.skippedAt IS NULL
              AND (SELECT COUNT(*) FROM encoder_sessions AS s
                   WHERE s.testingSessionId = :testingSessionId AND s.athleteId = p.athleteId) < testing_sessions.targetAttempts
          )
        """,
    )
    suspend fun reopenTestingSessionAfterDeletion(testingSessionId: Long, updatedAt: Long)

    /** Deletes the root and all sample/repetition children through Room foreign-key cascades. */
    @Transaction
    suspend fun deleteSession(sessionId: Long, updatedAt: Long = System.currentTimeMillis()): Boolean {
        val testingSessionId = testingSessionIdForEncoderSession(sessionId)
        val deleted = deleteSessionById(sessionId) == 1
        if (deleted && testingSessionId != null) {
            reopenTestingSessionAfterDeletion(testingSessionId, updatedAt)
        }
        return deleted
    }

    @Query(
        """
        SELECT s.id, s.dateTime, s.athleteId, a.displayName AS athleteName,
               s.exercise, s.loadKg, s.validRepetitions, s.bestMcv,
               s.notes IS NOT NULL AS hasNotes
        FROM encoder_sessions AS s
        LEFT JOIN athletes AS a ON a.id = s.athleteId
        ORDER BY s.dateTime DESC
        LIMIT 5
        """,
    )
    fun recentFive(): Flow<List<RecentEncoderSession>>

    /** Personal/active-athlete home feed. The athlete predicate is evaluated before LIMIT. */
    @Query(
        """
        SELECT s.id, s.dateTime, s.athleteId, a.displayName AS athleteName,
               s.exercise, s.loadKg, s.validRepetitions, s.bestMcv,
               s.notes IS NOT NULL AS hasNotes
        FROM encoder_sessions AS s
        LEFT JOIN athletes AS a ON a.id = s.athleteId
        WHERE s.athleteId = :athleteId
        ORDER BY s.dateTime DESC
        LIMIT 5
        """,
    )
    fun recentFiveForAthlete(athleteId: Long): Flow<List<RecentEncoderSession>>

    @Query(
        """
        SELECT s.id, s.dateTime, s.athleteId, a.displayName AS athleteName,
               s.exercise, s.loadKg, s.validRepetitions, s.bestMcv,
               s.notes IS NOT NULL AS hasNotes
        FROM encoder_sessions AS s
        LEFT JOIN athletes AS a ON a.id = s.athleteId
        ORDER BY s.dateTime DESC
        """,
    )
    fun history(): Flow<List<RecentEncoderSession>>

    /** Bounded keyset page. All filters are evaluated by SQLite before LIMIT. */
    @Query(
        """
        SELECT s.id AS id, s.dateTime, 'ENCODER' AS kind, s.athleteId,
               ath.displayName AS athleteName, NULL AS protocolId, NULL AS side,
               NULL AS primaryMetricKey, NULL AS primaryMetricValue, NULL AS primaryMetricUnit,
               s.exercise, s.loadKg, s.validRepetitions, s.bestMcv,
               s.notes IS NOT NULL AS hasNotes
        FROM encoder_sessions AS s
        LEFT JOIN athletes AS ath ON ath.id = s.athleteId
        WHERE ((:unassignedOnly = 1 AND s.athleteId IS NULL)
          OR (:unassignedOnly = 0 AND (:athleteId IS NULL OR s.athleteId = :athleteId)))
          AND (:groupId IS NULL OR EXISTS (
              SELECT 1 FROM athlete_group_cross_ref AS membership
              WHERE membership.athleteId = s.athleteId AND membership.groupId = :groupId
          ))
          AND (:search IS NULL OR
               REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                 REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(ath.displayName,
                   'á', 'a'), 'Á', 'a'), 'é', 'e'), 'É', 'e'), 'í', 'i'), 'Í', 'i'),
                   'ó', 'o'), 'Ó', 'o'), 'ú', 'u'), 'Ú', 'u'), 'ü', 'u'), 'Ü', 'u'),
                   'ñ', 'n'), 'Ñ', 'n') LIKE '%' || :search || '%' COLLATE NOCASE
               OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
                 REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(s.exercise,
                   'á', 'a'), 'Á', 'a'), 'é', 'e'), 'É', 'e'), 'í', 'i'), 'Í', 'i'),
                   'ó', 'o'), 'Ó', 'o'), 'ú', 'u'), 'Ú', 'u'), 'ü', 'u'), 'Ü', 'u'),
                   'ñ', 'n'), 'Ñ', 'n') LIKE '%' || :search || '%' COLLATE NOCASE)
          AND (:protocolOrExercise IS NULL OR s.exercise = :protocolOrExercise)
          AND (:dateFrom IS NULL OR s.dateTime >= :dateFrom)
          AND (:dateTo IS NULL OR s.dateTime <= :dateTo)
          AND (:metricMin IS NULL OR s.bestMcv >= :metricMin)
          AND (:metricMax IS NULL OR s.bestMcv <= :metricMax)
          AND (:cursorDateTime IS NULL OR s.dateTime < :cursorDateTime
            OR (s.dateTime = :cursorDateTime AND s.id < :cursorId))
        ORDER BY s.dateTime DESC, s.id DESC
        LIMIT :limit
        """,
    )
    suspend fun historyPage(
        search: String?, athleteId: Long?, unassignedOnly: Boolean, groupId: Long?, protocolOrExercise: String?,
        dateFrom: Long?, dateTo: Long?, metricMin: Double?, metricMax: Double?,
        cursorDateTime: Long?, cursorId: Long, limit: Int,
    ): List<AthleteHistoryPage>

    @Query("SELECT COUNT(*) AS total, MAX(dateTime) AS lastDateTime FROM encoder_sessions WHERE athleteId = :athleteId")
    fun athleteSummary(athleteId: Long): Flow<AthleteEncoderSummary>

    /** Valid candidates; non-overlapping load clusters and best selection belong to the repository. */
    @Query(
        """
        SELECT s.id AS sessionId, s.exercise, s.loadKg, s.bestMcv, s.dateTime
        FROM encoder_sessions AS s
        WHERE s.athleteId = :athleteId AND s.bestMcv IS NOT NULL
        ORDER BY s.exercise, s.loadKg, s.dateTime, s.id
        """,
    )
    fun athleteEncoderRecordCandidates(athleteId: Long): Flow<List<AthleteEncoderRecord>>

    /** Full-history aggregate for a greedy load-cluster anchor. The lower-bound
     * loadKg >= :loadKg is intentional: removing it makes nearby anchors overlap
     * and double-count sessions in stats/evolution. Invalid sessions are excluded. */
    @Query(
        """
        SELECT COUNT(*) AS count,
               AVG(s.bestMcv) AS average,
               MAX(s.bestMcv) AS best,
               (SELECT latest.bestMcv
                FROM encoder_sessions AS latest
                WHERE latest.athleteId = :athleteId AND latest.exercise = :exercise
                  AND latest.loadKg >= :loadKg
                  AND ABS(latest.loadKg - :loadKg) < 0.000001
                  AND latest.bestMcv IS NOT NULL
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recent,
               (SELECT latest.dateTime
                FROM encoder_sessions AS latest
                WHERE latest.athleteId = :athleteId AND latest.exercise = :exercise
                  AND latest.loadKg >= :loadKg
                  AND ABS(latest.loadKg - :loadKg) < 0.000001
                  AND latest.bestMcv IS NOT NULL
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recentDateTime,
               (SELECT latest.id
                FROM encoder_sessions AS latest
                WHERE latest.athleteId = :athleteId AND latest.exercise = :exercise
                  AND latest.loadKg >= :loadKg
                  AND ABS(latest.loadKg - :loadKg) < 0.000001
                  AND latest.bestMcv IS NOT NULL
                ORDER BY latest.dateTime DESC, latest.id DESC LIMIT 1) AS recentId
        FROM encoder_sessions AS s
        WHERE s.athleteId = :athleteId AND s.exercise = :exercise
          AND s.loadKg >= :loadKg
          AND ABS(s.loadKg - :loadKg) < 0.000001
          AND s.bestMcv IS NOT NULL
        """,
    )
    suspend fun athleteEncoderStats(athleteId: Long, exercise: String, loadKg: Double): AthleteSeriesStats

    @Query(
        """
        SELECT s.id AS id, s.dateTime, s.bestMcv AS value,
               'METER_PER_SECOND' AS unit, 'ENCODER' AS kind
        FROM encoder_sessions AS s
        WHERE s.athleteId = :athleteId AND s.exercise = :exercise
          AND s.loadKg >= :loadKg
          AND ABS(s.loadKg - :loadKg) < 0.000001
          AND s.bestMcv IS NOT NULL
        ORDER BY s.dateTime DESC, s.id DESC
        LIMIT 50
        """,
    )
    suspend fun athleteEncoderEvolution(athleteId: Long, exercise: String, loadKg: Double): List<AthleteEvolutionPoint>

    @Query(
        """
        SELECT s.id AS id, s.dateTime, 'ENCODER' AS kind, s.athleteId,
               ath.displayName AS athleteName, NULL AS protocolId, NULL AS side,
               NULL AS primaryMetricKey, NULL AS primaryMetricValue, NULL AS primaryMetricUnit,
               s.exercise, s.loadKg, s.validRepetitions, s.bestMcv,
               s.notes IS NOT NULL AS hasNotes
        FROM encoder_sessions AS s
        LEFT JOIN athletes AS ath ON ath.id = s.athleteId
        WHERE s.athleteId = :athleteId
          AND (:cursorDateTime IS NULL OR s.dateTime < :cursorDateTime
            OR (s.dateTime = :cursorDateTime AND s.id < :cursorId))
        ORDER BY s.dateTime DESC, s.id DESC
        LIMIT 20
        """,
    )
    suspend fun athleteHistoryPage(
        athleteId: Long,
        cursorDateTime: Long?,
        cursorId: Long,
    ): List<AthleteHistoryPage>
}
