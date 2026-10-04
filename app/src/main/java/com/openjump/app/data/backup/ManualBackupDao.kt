package com.openjump.app.data.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.openjump.app.data.*

@Dao
interface ManualBackupDao {
    @Query("SELECT * FROM athletes ORDER BY id") suspend fun athletes(): List<BackupAthlete>
    @Query("SELECT * FROM athlete_groups ORDER BY id") suspend fun groups(): List<BackupGroup>
    @Query("SELECT * FROM athlete_group_cross_ref ORDER BY groupId, athleteId") suspend fun memberships(): List<BackupMembership>
    @Query("SELECT * FROM testing_sessions ORDER BY id") suspend fun testingSessions(): List<BackupTestingSession>
    @Query("SELECT * FROM testing_participants ORDER BY sessionId, ordinal, athleteId") suspend fun testingParticipants(): List<BackupTestingParticipant>
    @Query("SELECT * FROM assessments ORDER BY id") suspend fun assessments(): List<BackupAssessment>
    /** Explicit projection intentionally omits the private video URI. */
    @Query("SELECT id, assessmentId, ordinal, side, source, detectedFps, dropHeightCm, distanceCm FROM attempts ORDER BY assessmentId, ordinal, id") suspend fun attempts(): List<BackupAttempt>
    @Query("SELECT attemptId, type, ordinal, ptsUs, frameIndex, previousPtsUs, nextPtsUs FROM attempt_events ORDER BY attemptId, type, ordinal") suspend fun attemptEvents(): List<BackupAttemptEvent>
    @Query("SELECT * FROM attempt_metrics ORDER BY attemptId, ordinal, key") suspend fun attemptMetrics(): List<BackupAttemptMetric>
    @Query("SELECT * FROM attempt_calibrations ORDER BY attemptId") suspend fun attemptCalibrations(): List<BackupAttemptCalibration>
    @Query("SELECT * FROM attempt_spatial_marks ORDER BY attemptId, type") suspend fun attemptSpatialMarks(): List<BackupAttemptSpatialMark>
    /** Explicit projection intentionally omits the private video URI. */
    @Query("SELECT id, sessionKey, athleteId, testingSessionId, exercise, loadKg, dateTime, source, observedFps, nominalFps, captureFps, timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, maximumUncertainFraction, smoothingWindowSamples, velocityEnterMps, velocityExitMps, totalRepetitions, validRepetitions, bestMcv, setVelocityLoss, notes FROM encoder_sessions ORDER BY id") suspend fun encoderSessions(): List<BackupEncoderSession>
    @Query("SELECT * FROM encoder_samples ORDER BY sessionId, ordinal") suspend fun encoderSamples(): List<BackupEncoderSample>
    @Query("SELECT * FROM encoder_repetitions ORDER BY sessionId, ordinal, id") suspend fun encoderRepetitions(): List<BackupEncoderRepetition>
    @Query("SELECT * FROM encoder_rep_metrics ORDER BY repetitionId, key") suspend fun encoderRepMetrics(): List<BackupEncoderRepMetric>

    @Transaction
    suspend fun snapshot(): ManualBackupDocument = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = System.currentTimeMillis(),
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = ManualBackupTables(
            athletes(), groups(), memberships(), testingSessions(), testingParticipants(), assessments(), attempts(),
            attemptEvents(), attemptMetrics(), attemptCalibrations(), attemptSpatialMarks(), encoderSessions(),
            encoderSamples(), encoderRepetitions(), encoderRepMetrics(),
        ),
    )

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAthletes(rows: List<AthleteEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroups(rows: List<GroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMemberships(rows: List<AthleteGroupCrossRef>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTestingSessions(rows: List<TestingSessionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTestingParticipants(rows: List<TestingParticipantEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAssessments(rows: List<AssessmentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttempts(rows: List<AttemptEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptEvents(rows: List<AttemptEventEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptMetrics(rows: List<AttemptMetricEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptCalibrations(rows: List<AttemptCalibrationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttemptSpatialMarks(rows: List<AttemptSpatialMarkEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderSessions(rows: List<EncoderSessionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderSamples(rows: List<EncoderSampleEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderRepetitions(rows: List<EncoderRepetitionEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEncoderRepMetrics(rows: List<EncoderRepMetricEntity>)

    @Query("DELETE FROM assessments") suspend fun clearAssessments()
    @Query("DELETE FROM encoder_sessions") suspend fun clearEncoderSessions()
    @Query("DELETE FROM testing_sessions") suspend fun clearTestingSessions()
    @Query("DELETE FROM athlete_group_cross_ref") suspend fun clearMemberships()
    @Query("DELETE FROM athlete_groups") suspend fun clearGroups()
    @Query("DELETE FROM athletes") suspend fun clearAthletes()

    @RawQuery
    suspend fun foreignKeyCheck(query: SupportSQLiteQuery): List<ForeignKeyViolation>

    @Transaction
    suspend fun replaceAll(snapshot: ManualBackupDocument) {
        val document = snapshot.validate()
        val t = document.tables
        clearAssessments()
        clearEncoderSessions()
        clearTestingSessions()
        clearMemberships()
        clearGroups()
        clearAthletes()
        insertAthletes(t.athletes.map { it.entity() })
        insertGroups(t.athleteGroups.map { it.entity() })
        insertMemberships(t.athleteGroupCrossRef.map { it.entity() })
        insertTestingSessions(t.testingSessions.map { it.entity() })
        insertTestingParticipants(t.testingParticipants.map { it.entity() })
        insertAssessments(t.assessments.map { it.entity() })
        insertAttempts(t.attempts.map { it.entity() })
        insertAttemptEvents(t.attemptEvents.map { it.entity() })
        insertAttemptMetrics(t.attemptMetrics.map { it.entity() })
        insertAttemptCalibrations(t.attemptCalibrations.map { it.entity() })
        insertAttemptSpatialMarks(t.attemptSpatialMarks.map { it.entity() })
        insertEncoderSessions(t.encoderSessions.map { it.entity() })
        insertEncoderSamples(t.encoderSamples.map { it.entity() })
        insertEncoderRepetitions(t.encoderRepetitions.map { it.entity() })
        insertEncoderRepMetrics(t.encoderRepMetrics.map { it.entity() })
        check(foreignKeyCheck(SimpleSQLiteQuery("PRAGMA foreign_key_check")).isEmpty()) { "Restored data has foreign-key violations" }
        check(counts() == t.counts()) { "Restored data counts do not match backup" }
    }

    @Query("SELECT COUNT(*) FROM athletes") suspend fun athleteCount(): Int
    @Query("SELECT COUNT(*) FROM athlete_groups") suspend fun groupCount(): Int
    @Query("SELECT COUNT(*) FROM athlete_group_cross_ref") suspend fun membershipCount(): Int
    @Query("SELECT COUNT(*) FROM testing_sessions") suspend fun testingSessionCount(): Int
    @Query("SELECT COUNT(*) FROM testing_participants") suspend fun testingParticipantCount(): Int
    @Query("SELECT COUNT(*) FROM assessments") suspend fun assessmentCount(): Int
    @Query("SELECT COUNT(*) FROM attempts") suspend fun attemptCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_events") suspend fun attemptEventCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_metrics") suspend fun attemptMetricCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_calibrations") suspend fun attemptCalibrationCount(): Int
    @Query("SELECT COUNT(*) FROM attempt_spatial_marks") suspend fun attemptSpatialMarkCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_sessions") suspend fun encoderSessionCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_samples") suspend fun encoderSampleCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_repetitions") suspend fun encoderRepetitionCount(): Int
    @Query("SELECT COUNT(*) FROM encoder_rep_metrics") suspend fun encoderRepMetricCount(): Int

    private suspend fun counts() = listOf(athleteCount(), groupCount(), membershipCount(), testingSessionCount(), testingParticipantCount(), assessmentCount(), attemptCount(), attemptEventCount(), attemptMetricCount(), attemptCalibrationCount(), attemptSpatialMarkCount(), encoderSessionCount(), encoderSampleCount(), encoderRepetitionCount(), encoderRepMetricCount())
    private fun ManualBackupTables.counts() = listOf(athletes.size, athleteGroups.size, athleteGroupCrossRef.size, testingSessions.size, testingParticipants.size, assessments.size, attempts.size, attemptEvents.size, attemptMetrics.size, attemptCalibrations.size, attemptSpatialMarks.size, encoderSessions.size, encoderSamples.size, encoderRepetitions.size, encoderRepMetrics.size)
}

data class ForeignKeyViolation(val table: String, val rowid: Long, val parent: String, val fkid: Int)
