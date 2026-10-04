package com.openjump.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AssessmentEntity::class,
        AttemptEntity::class,
        AttemptEventEntity::class,
        AttemptMetricEntity::class,
        AttemptCalibrationEntity::class,
        AttemptSpatialMarkEntity::class,
        EncoderSessionEntity::class,
        EncoderSampleEntity::class,
        EncoderRepetitionEntity::class,
        EncoderRepMetricEntity::class,
        AthleteEntity::class,
        GroupEntity::class,
        AthleteGroupCrossRef::class,
        TestingSessionEntity::class,
        TestingParticipantEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class JumpDatabase : RoomDatabase() {

    abstract fun jumpDao(): JumpDao
    abstract fun encoderDao(): EncoderDao
    abstract fun athleteDao(): AthleteDao
    abstract fun groupDao(): GroupDao
    abstract fun testingDao(): TestingDao
    abstract fun dataExportDao(): com.openjump.app.data.export.DataExportDao
    abstract fun manualBackupDao(): com.openjump.app.data.backup.ManualBackupDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assessments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionKey TEXT NOT NULL,
                        protocolId TEXT NOT NULL,
                        dateTime INTEGER NOT NULL,
                        primaryMetricKey TEXT NOT NULL,
                        primaryMetricValue REAL NOT NULL,
                        primaryMetricUnit TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_assessments_sessionKey " +
                        "ON assessments (sessionKey)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        assessmentId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        side TEXT,
                        source TEXT NOT NULL,
                        videoUri TEXT,
                        detectedFps INTEGER NOT NULL,
                        dropHeightCm REAL,
                        distanceCm REAL,
                        FOREIGN KEY (assessmentId) REFERENCES assessments (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attempts_assessmentId ON attempts (assessmentId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attempts_assessmentId_ordinal " +
                        "ON attempts (assessmentId, ordinal)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attempt_events (
                        attemptId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        ptsUs INTEGER NOT NULL,
                        PRIMARY KEY (attemptId, type, ordinal),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attempt_events_attemptId " +
                        "ON attempt_events (attemptId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attempt_metrics (
                        attemptId INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        value REAL NOT NULL,
                        unit TEXT NOT NULL,
                        PRIMARY KEY (attemptId, `key`, ordinal),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attempt_metrics_attemptId " +
                        "ON attempt_metrics (attemptId)",
                )

                db.execSQL(
                    """
                    INSERT INTO assessments (
                        id, sessionKey, protocolId, dateTime,
                        primaryMetricKey, primaryMetricValue, primaryMetricUnit
                    )
                    SELECT id, 'legacy-' || id, testType, dateTime,
                           'HEIGHT_CM', jumpHeightCm, 'CENTIMETER'
                    FROM jump_measurements
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attempts (
                        id, assessmentId, ordinal, side, source, videoUri,
                        detectedFps, dropHeightCm, distanceCm
                    )
                    SELECT id, id, 0, NULL, 'UNKNOWN', videoUri,
                           detectedFps, NULL, NULL
                    FROM jump_measurements
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attempt_events (attemptId, type, ordinal, ptsUs)
                    SELECT id, 'MOVEMENT_START', 0, movementStartTimestampUs FROM jump_measurements
                    UNION ALL
                    SELECT id, 'TAKEOFF', 0, takeoffTimestampUs FROM jump_measurements
                    UNION ALL
                    SELECT id, 'LANDING', 0, landingTimestampUs FROM jump_measurements
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attempt_metrics (attemptId, `key`, ordinal, value, unit)
                    SELECT id, 'HEIGHT_CM', 0, jumpHeightCm, 'CENTIMETER' FROM jump_measurements
                    UNION ALL
                    SELECT id, 'FLIGHT_TIME_MS', 0, flightTimeMs, 'MILLISECOND' FROM jump_measurements
                    UNION ALL
                    SELECT id, 'TAKEOFF_VELOCITY_MPS', 0, takeoffVelocity, 'METER_PER_SECOND'
                        FROM jump_measurements
                    UNION ALL
                    SELECT id, 'TIME_TO_TAKEOFF_MS', 0, timeToTakeoffMs, 'MILLISECOND'
                        FROM jump_measurements
                    UNION ALL
                    SELECT id, 'RSI_MOD', 0, rsiMod, 'METER_PER_SECOND' FROM jump_measurements
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE jump_measurements")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encoder_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionKey TEXT NOT NULL,
                        exercise TEXT NOT NULL,
                        loadKg REAL NOT NULL,
                        dateTime INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        videoUri TEXT,
                        observedFps REAL,
                        nominalFps REAL,
                        captureFps REAL,
                        timingMode TEXT NOT NULL,
                        slowMotionFactor REAL NOT NULL,
                        calibrationFrameIndex INTEGER NOT NULL,
                        calibrationAX REAL NOT NULL,
                        calibrationAY REAL NOT NULL,
                        calibrationBX REAL NOT NULL,
                        calibrationBY REAL NOT NULL,
                        referenceLengthM REAL NOT NULL,
                        metersPerPixel REAL NOT NULL,
                        analysisVersion INTEGER NOT NULL,
                        minimumVelocityMps REAL NOT NULL,
                        noiseMultiplier REAL NOT NULL,
                        exitRatio REAL NOT NULL,
                        minimumDirectionHoldUs INTEGER NOT NULL,
                        stationaryDurationUs INTEGER NOT NULL,
                        pauseDurationUs INTEGER NOT NULL,
                        minimumPhaseDurationUs INTEGER NOT NULL,
                        minimumPhaseDisplacementM REAL NOT NULL,
                        romOutlierFraction REAL NOT NULL,
                        minimumMedianConfidence REAL NOT NULL,
                        maximumUncertainFraction REAL NOT NULL,
                        smoothingWindowSamples INTEGER NOT NULL,
                        velocityEnterMps REAL,
                        velocityExitMps REAL,
                        totalRepetitions INTEGER NOT NULL,
                        validRepetitions INTEGER NOT NULL,
                        bestMcv REAL,
                        setVelocityLoss REAL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_encoder_sessions_sessionKey " +
                        "ON encoder_sessions (sessionKey)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encoder_samples (
                        sessionId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        sourcePtsUs INTEGER NOT NULL,
                        physicalTimeUs INTEGER,
                        rawPixelX REAL,
                        rawPixelY REAL,
                        rawMetricX REAL,
                        rawMetricY REAL,
                        smoothedMetricX REAL,
                        smoothedMetricY REAL,
                        velocityXMps REAL,
                        velocityYMps REAL,
                        confidence REAL NOT NULL,
                        trackingStatus TEXT NOT NULL,
                        observationKind TEXT NOT NULL,
                        fitResidualM REAL,
                        gapBeforeUs INTEGER,
                        PRIMARY KEY (sessionId, ordinal),
                        FOREIGN KEY (sessionId) REFERENCES encoder_sessions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_encoder_samples_sessionId ON encoder_samples (sessionId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encoder_repetitions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        startSample INTEGER NOT NULL,
                        endSample INTEGER NOT NULL,
                        eccentricStartSample INTEGER NOT NULL,
                        eccentricEndSample INTEGER NOT NULL,
                        concentricStartSample INTEGER NOT NULL,
                        concentricEndSample INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        reasons TEXT NOT NULL,
                        FOREIGN KEY (sessionId) REFERENCES encoder_sessions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_encoder_repetitions_sessionId ON encoder_repetitions (sessionId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_encoder_repetitions_sessionId_ordinal " +
                        "ON encoder_repetitions (sessionId, ordinal)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encoder_rep_metrics (
                        repetitionId INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        value REAL,
                        unit TEXT NOT NULL,
                        validity TEXT NOT NULL,
                        reason TEXT,
                        PRIMARY KEY (repetitionId, `key`),
                        FOREIGN KEY (repetitionId) REFERENCES encoder_repetitions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encoder_rep_metrics_repetitionId " +
                        "ON encoder_rep_metrics (repetitionId)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attempt_calibrations (
                        attemptId INTEGER NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        pointAX REAL NOT NULL,
                        pointAY REAL NOT NULL,
                        pointBX REAL NOT NULL,
                        pointBY REAL NOT NULL,
                        referenceLengthM REAL NOT NULL,
                        metersPerPixel REAL NOT NULL,
                        PRIMARY KEY (attemptId),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attempt_spatial_marks (
                        attemptId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        ptsUs INTEGER NOT NULL,
                        pointX REAL NOT NULL,
                        pointY REAL NOT NULL,
                        PRIMARY KEY (attemptId, type),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attempt_spatial_marks_attemptId " +
                        "ON attempt_spatial_marks (attemptId)",
                )
            }
        }

        /**
         * v5 adds nullable ownership columns for legacy rows and physical NO ACTION FKs for
         * all future writes. SQLite cannot add a FK with ALTER TABLE, and Room invokes this
         * migration inside a transaction where changing PRAGMA foreign_keys is unsafe. Back up
         * both complete ownership trees, rebuild the roots, then recreate and restore children.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS athletes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        birthDate INTEGER,
                        sex TEXT,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        archivedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athletes_archivedAt_displayName ON athletes (archivedAt, displayName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athletes_displayName ON athletes (displayName)")

                val jumpChildren = listOf(
                    "attempt_events", "attempt_metrics", "attempt_calibrations", "attempt_spatial_marks", "attempts",
                )
                val encoderChildren = listOf("encoder_rep_metrics", "encoder_repetitions", "encoder_samples")
                listOf("assessments", "encoder_sessions") .forEach { table ->
                    db.execSQL("CREATE TABLE ${table}_backup AS SELECT * FROM $table")
                }
                (jumpChildren + encoderChildren).forEach { table ->
                    db.execSQL("CREATE TABLE ${table}_backup AS SELECT * FROM $table")
                }
                (jumpChildren + encoderChildren).forEach { table -> db.execSQL("DROP TABLE $table") }
                db.execSQL("DROP TABLE assessments")
                db.execSQL("DROP TABLE encoder_sessions")

                db.execSQL(
                    """
                    CREATE TABLE assessments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionKey TEXT NOT NULL,
                        athleteId INTEGER,
                        testingSessionId INTEGER,
                        protocolId TEXT NOT NULL,
                        dateTime INTEGER NOT NULL,
                        primaryMetricKey TEXT NOT NULL,
                        primaryMetricValue REAL NOT NULL,
                        primaryMetricUnit TEXT NOT NULL,
                        FOREIGN KEY (athleteId) REFERENCES athletes (id)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE encoder_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionKey TEXT NOT NULL,
                        athleteId INTEGER,
                        testingSessionId INTEGER,
                        exercise TEXT NOT NULL,
                        loadKg REAL NOT NULL,
                        dateTime INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        videoUri TEXT,
                        observedFps REAL,
                        nominalFps REAL,
                        captureFps REAL,
                        timingMode TEXT NOT NULL,
                        slowMotionFactor REAL NOT NULL,
                        calibrationFrameIndex INTEGER NOT NULL,
                        calibrationAX REAL NOT NULL,
                        calibrationAY REAL NOT NULL,
                        calibrationBX REAL NOT NULL,
                        calibrationBY REAL NOT NULL,
                        referenceLengthM REAL NOT NULL,
                        metersPerPixel REAL NOT NULL,
                        analysisVersion INTEGER NOT NULL,
                        minimumVelocityMps REAL NOT NULL,
                        noiseMultiplier REAL NOT NULL,
                        exitRatio REAL NOT NULL,
                        minimumDirectionHoldUs INTEGER NOT NULL,
                        stationaryDurationUs INTEGER NOT NULL,
                        pauseDurationUs INTEGER NOT NULL,
                        minimumPhaseDurationUs INTEGER NOT NULL,
                        minimumPhaseDisplacementM REAL NOT NULL,
                        romOutlierFraction REAL NOT NULL,
                        minimumMedianConfidence REAL NOT NULL,
                        maximumUncertainFraction REAL NOT NULL,
                        smoothingWindowSamples INTEGER NOT NULL,
                        velocityEnterMps REAL,
                        velocityExitMps REAL,
                        totalRepetitions INTEGER NOT NULL,
                        validRepetitions INTEGER NOT NULL,
                        bestMcv REAL,
                        setVelocityLoss REAL,
                        FOREIGN KEY (athleteId) REFERENCES athletes (id)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO assessments SELECT id, sessionKey, NULL, NULL, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit FROM assessments_backup")
                db.execSQL("INSERT INTO encoder_sessions SELECT id, sessionKey, NULL, NULL, exercise, loadKg, dateTime, source, videoUri, observedFps, nominalFps, captureFps, timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, maximumUncertainFraction, smoothingWindowSamples, velocityEnterMps, velocityExitMps, totalRepetitions, validRepetitions, bestMcv, setVelocityLoss FROM encoder_sessions_backup")

                db.execSQL("CREATE UNIQUE INDEX index_assessments_sessionKey ON assessments (sessionKey)")
                db.execSQL("CREATE INDEX index_assessments_athleteId_dateTime_id ON assessments (athleteId, dateTime, id)")
                db.execSQL("CREATE INDEX index_assessments_testingSessionId_dateTime_id ON assessments (testingSessionId, dateTime, id)")
                db.execSQL("CREATE INDEX index_assessments_dateTime_id ON assessments (dateTime, id)")
                db.execSQL("CREATE UNIQUE INDEX index_encoder_sessions_sessionKey ON encoder_sessions (sessionKey)")
                db.execSQL("CREATE INDEX index_encoder_sessions_athleteId_dateTime_id ON encoder_sessions (athleteId, dateTime, id)")
                db.execSQL("CREATE INDEX index_encoder_sessions_testingSessionId_dateTime_id ON encoder_sessions (testingSessionId, dateTime, id)")
                db.execSQL("CREATE INDEX index_encoder_sessions_dateTime_id ON encoder_sessions (dateTime, id)")

                db.execSQL(
                    """
                    CREATE TABLE attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        assessmentId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        side TEXT,
                        source TEXT NOT NULL,
                        videoUri TEXT,
                        detectedFps INTEGER NOT NULL,
                        dropHeightCm REAL,
                        distanceCm REAL,
                        FOREIGN KEY (assessmentId) REFERENCES assessments (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_attempts_assessmentId ON attempts (assessmentId)")
                db.execSQL("CREATE UNIQUE INDEX index_attempts_assessmentId_ordinal ON attempts (assessmentId, ordinal)")
                db.execSQL(
                    """
                    CREATE TABLE attempt_events (
                        attemptId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        ptsUs INTEGER NOT NULL,
                        PRIMARY KEY (attemptId, type, ordinal),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_attempt_events_attemptId ON attempt_events (attemptId)")
                db.execSQL(
                    """
                    CREATE TABLE attempt_metrics (
                        attemptId INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        value REAL NOT NULL,
                        unit TEXT NOT NULL,
                        PRIMARY KEY (attemptId, `key`, ordinal),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_attempt_metrics_attemptId ON attempt_metrics (attemptId)")
                db.execSQL(
                    """
                    CREATE TABLE attempt_calibrations (
                        attemptId INTEGER NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        pointAX REAL NOT NULL,
                        pointAY REAL NOT NULL,
                        pointBX REAL NOT NULL,
                        pointBY REAL NOT NULL,
                        referenceLengthM REAL NOT NULL,
                        metersPerPixel REAL NOT NULL,
                        PRIMARY KEY (attemptId),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE attempt_spatial_marks (
                        attemptId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        ptsUs INTEGER NOT NULL,
                        pointX REAL NOT NULL,
                        pointY REAL NOT NULL,
                        PRIMARY KEY (attemptId, type),
                        FOREIGN KEY (attemptId) REFERENCES attempts (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_attempt_spatial_marks_attemptId ON attempt_spatial_marks (attemptId)")
                db.execSQL("INSERT INTO attempts SELECT * FROM attempts_backup")
                db.execSQL("INSERT INTO attempt_events SELECT * FROM attempt_events_backup")
                db.execSQL("INSERT INTO attempt_metrics SELECT * FROM attempt_metrics_backup")
                db.execSQL("INSERT INTO attempt_calibrations SELECT * FROM attempt_calibrations_backup")
                db.execSQL("INSERT INTO attempt_spatial_marks SELECT * FROM attempt_spatial_marks_backup")

                db.execSQL(
                    """
                    CREATE TABLE encoder_samples (
                        sessionId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        frameIndex INTEGER NOT NULL,
                        sourcePtsUs INTEGER NOT NULL,
                        physicalTimeUs INTEGER,
                        rawPixelX REAL,
                        rawPixelY REAL,
                        rawMetricX REAL,
                        rawMetricY REAL,
                        smoothedMetricX REAL,
                        smoothedMetricY REAL,
                        velocityXMps REAL,
                        velocityYMps REAL,
                        confidence REAL NOT NULL,
                        trackingStatus TEXT NOT NULL,
                        observationKind TEXT NOT NULL,
                        fitResidualM REAL,
                        gapBeforeUs INTEGER,
                        PRIMARY KEY (sessionId, ordinal),
                        FOREIGN KEY (sessionId) REFERENCES encoder_sessions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_encoder_samples_sessionId ON encoder_samples (sessionId)")
                db.execSQL(
                    """
                    CREATE TABLE encoder_repetitions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        startSample INTEGER NOT NULL,
                        endSample INTEGER NOT NULL,
                        eccentricStartSample INTEGER NOT NULL,
                        eccentricEndSample INTEGER NOT NULL,
                        concentricStartSample INTEGER NOT NULL,
                        concentricEndSample INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        reasons TEXT NOT NULL,
                        FOREIGN KEY (sessionId) REFERENCES encoder_sessions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_encoder_repetitions_sessionId ON encoder_repetitions (sessionId)")
                db.execSQL("CREATE UNIQUE INDEX index_encoder_repetitions_sessionId_ordinal ON encoder_repetitions (sessionId, ordinal)")
                db.execSQL(
                    """
                    CREATE TABLE encoder_rep_metrics (
                        repetitionId INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        value REAL,
                        unit TEXT NOT NULL,
                        validity TEXT NOT NULL,
                        reason TEXT,
                        PRIMARY KEY (repetitionId, `key`),
                        FOREIGN KEY (repetitionId) REFERENCES encoder_repetitions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_encoder_rep_metrics_repetitionId ON encoder_rep_metrics (repetitionId)")
                db.execSQL("INSERT INTO encoder_samples SELECT * FROM encoder_samples_backup")
                db.execSQL("INSERT INTO encoder_repetitions SELECT * FROM encoder_repetitions_backup")
                db.execSQL("INSERT INTO encoder_rep_metrics SELECT * FROM encoder_rep_metrics_backup")

                (jumpChildren + encoderChildren + listOf("assessments", "encoder_sessions")).forEach { table ->
                    db.execSQL("DROP TABLE ${table}_backup")
                }
            }
        }

        /** v5→v6 is strictly additive: groups and memberships do not touch existing data. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS athlete_groups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        archivedAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athlete_groups_archivedAt_name ON athlete_groups (archivedAt, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athlete_groups_name ON athlete_groups (name)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS athlete_group_cross_ref (
                        groupId INTEGER NOT NULL,
                        athleteId INTEGER NOT NULL,
                        PRIMARY KEY (groupId, athleteId),
                        FOREIGN KEY (groupId) REFERENCES athlete_groups (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY (athleteId) REFERENCES athletes (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athlete_group_cross_ref_groupId ON athlete_group_cross_ref (groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_athlete_group_cross_ref_athleteId ON athlete_group_cross_ref (athleteId)")
            }
        }

        /** v6→v7 is additive; root testingSessionId columns deliberately remain nullable and are validated transactionally by each root DAO. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS testing_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER,
                        groupNameSnapshot TEXT,
                        family TEXT NOT NULL,
                        protocolId TEXT,
                        exercise TEXT,
                        side TEXT,
                        dropHeightCm REAL,
                        loadKg REAL,
                        plateDiameterCm REAL,
                        targetAttempts INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        currentOrdinal INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        FOREIGN KEY (groupId) REFERENCES athlete_groups (id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_testing_sessions_groupId ON testing_sessions (groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_testing_sessions_status_updatedAt ON testing_sessions (status, updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_testing_sessions_createdAt ON testing_sessions (createdAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS testing_participants (
                        sessionId INTEGER NOT NULL,
                        athleteId INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        skippedAt INTEGER,
                        PRIMARY KEY (sessionId, athleteId),
                        FOREIGN KEY (sessionId) REFERENCES testing_sessions (id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY (athleteId) REFERENCES athletes (id)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_testing_participants_sessionId_ordinal ON testing_participants (sessionId, ordinal)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_testing_participants_athleteId ON testing_participants (athleteId)")
            }
        }

        /** v7→v8 adds an optional bundled-avatar key without changing athlete identity. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE athletes ADD COLUMN avatarKey TEXT")
            }
        }

        /** v8→v9 stores optional anthropometric data in canonical metric units. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE athletes ADD COLUMN weightKg REAL")
                db.execSQL("ALTER TABLE athletes ADD COLUMN heightCm REAL")
            }
        }

        /** v9→v10 is strictly additive; all new values are nullable for legacy rows. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assessments ADD COLUMN notes TEXT")
                db.execSQL("ALTER TABLE encoder_sessions ADD COLUMN notes TEXT")
                db.execSQL("ALTER TABLE attempt_events ADD COLUMN frameIndex INTEGER")
                db.execSQL("ALTER TABLE attempt_events ADD COLUMN previousPtsUs INTEGER")
                db.execSQL("ALTER TABLE attempt_events ADD COLUMN nextPtsUs INTEGER")
            }
        }

        @Volatile
        private var instance: JumpDatabase? = null

        fun get(context: Context): JumpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JumpDatabase::class.java,
                    "openjump.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
