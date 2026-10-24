package com.openjump.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JumpDatabaseMigrationTest {

    private val databaseName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JumpDatabase::class.java,
    )

    @Test
    fun migrate1To12_runsCompleteChain() {
        val chainDatabase = "migration-chain-test"
        helper.createDatabase(chainDatabase, 1).close()

        val db = helper.runMigrationsAndValidate(
            chainDatabase,
            12,
            true,
            JumpDatabase.MIGRATION_1_2,
            JumpDatabase.MIGRATION_2_3,
            JumpDatabase.MIGRATION_3_4,
            JumpDatabase.MIGRATION_4_5,
            JumpDatabase.MIGRATION_5_6,
            JumpDatabase.MIGRATION_6_7,
            JumpDatabase.MIGRATION_7_8,
            JumpDatabase.MIGRATION_8_9,
            JumpDatabase.MIGRATION_9_10,
            JumpDatabase.MIGRATION_10_11,
            JumpDatabase.MIGRATION_11_12,
        )
        listOf(
            "encoder_sessions", "attempt_calibrations", "attempt_spatial_marks", "athletes",
            "testing_sessions", "testing_participants",
            "fvp_assessments", "fvp_conditions", "fvp_attempts",
        ).forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use {
                assertEquals("Missing $table", 1, it.count)
            }
        }
        db.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(12, it.getInt(0)) }
        db.close()
    }

    @Test
    fun migrate11To12_addsProvenanceColumnsWithoutTouchingFvpRows() {
        val name = "migration-fvp-provenance-test"
        helper.createDatabase(name, 11).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (21, 'Existing', 1, 1)")
            execSQL("INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes) VALUES (25, 'existing-v11', 21, 'SJ', 3, 'HEIGHT_CM', 33.0, 'CENTIMETER', 'nota v11')")
            execSQL("INSERT INTO fvp_assessments (id, sessionKey, athleteId, protocolId, dateTime, bodyMassKg, bodyMassSource, pushOffMode, pushOffM, pushOffSource, algorithmVersion, methodVersion, f0N, slopeFv, sfvNsPerM, conditionCount, vMinMps, vMaxMps, fMinN, fMaxN, warningsCsv) VALUES (22, 'fvp-v11', 21, 'CMJ', 4, 72.5, 'PROFILE', 'COMMON', 0.42, 'MEASURED', 'fvp-poc-1.0', 'fvp-app-1.0', 1802.5, -355.25, 355.25, 2, 1.5, 3.5, 1500.0, 2100.0, '')")
            execSQL("INSERT INTO fvp_conditions (id, assessmentId, ordinal, conditionKey, addedLoadKg, totalMassKg, pushOffM, selectedHeightM, selectedAttemptOrdinal) VALUES (23, 22, 0, '20kg', 20.0, 92.5, 0.42, 0.31, 0)")
            execSQL("INSERT INTO fvp_attempts (id, conditionId, ordinal, heightM, method, flightTimeS, sourceFps, valid, exclusionReason, notes) VALUES (24, 23, 0, 0.31, 'FLIGHT_TIME_VIDEO', 0.503, 240.0, 1, NULL, 'intento v11')")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 12, true, JumpDatabase.MIGRATION_11_12)

        // (a) Los valores v11 de la fila F-V-P y de las tablas ajenas siguen intactos.
        db.query("SELECT bodyMassKg, pushOffM, dateTime, conditionCount, warningsCsv FROM fvp_assessments WHERE id = 22").use {
            it.moveToFirst()
            assertEquals(72.5, it.getDouble(0), 0.0001)
            assertEquals(0.42, it.getDouble(1), 0.0001)
            assertEquals(4L, it.getLong(2))
            assertEquals(2, it.getInt(3))
            assertEquals("", it.getString(4))
        }
        db.query("SELECT conditionKey, addedLoadKg, totalMassKg, selectedHeightM FROM fvp_conditions WHERE id = 23").use {
            it.moveToFirst()
            assertEquals("20kg", it.getString(0))
            assertEquals(20.0, it.getDouble(1), 0.0001)
            assertEquals(92.5, it.getDouble(2), 0.0001)
            assertEquals(0.31, it.getDouble(3), 0.0001)
        }
        db.query("SELECT heightM, method, flightTimeS, sourceFps, valid, notes FROM fvp_attempts WHERE id = 24").use {
            it.moveToFirst()
            assertEquals(0.31, it.getDouble(0), 0.0001)
            assertEquals("FLIGHT_TIME_VIDEO", it.getString(1))
            assertEquals(0.503, it.getDouble(2), 0.0001)
            assertEquals(240.0, it.getDouble(3), 0.0001)
            assertEquals(1, it.getInt(4))
            assertEquals("intento v11", it.getString(5))
        }
        db.query("SELECT displayName FROM athletes WHERE id = 21").use { it.moveToFirst(); assertEquals("Existing", it.getString(0)) }
        db.query("SELECT sessionKey, notes FROM assessments WHERE id = 25").use {
            it.moveToFirst()
            assertEquals("existing-v11", it.getString(0))
            assertEquals("nota v11", it.getString(1))
        }

        // (b) Las columnas nuevas opcionales son NULL y las cuatro textuales valen 'UNKNOWN'.
        db.query(
            "SELECT acquiredAt, acquisitionContextKind, acquisitionContextKey, bodyMassMeasurement, " +
                "bodyMassMeasuredAt FROM fvp_assessments WHERE id = 22",
        ).use {
            it.moveToFirst()
            assertTrue(it.isNull(0))
            assertEquals("UNKNOWN", it.getString(1))
            assertTrue(it.isNull(2))
            assertEquals("UNKNOWN", it.getString(3))
            assertTrue(it.isNull(4))
        }
        db.query("SELECT addedLoadSource, addedLoadMeasuredAt FROM fvp_conditions WHERE id = 23").use {
            it.moveToFirst()
            assertEquals("UNKNOWN", it.getString(0))
            assertTrue(it.isNull(1))
        }
        db.query(
            "SELECT sourceRecordedAt, sourceAthleteId, acquiredAt, reuseConfirmedAt, " +
                "reuseConfirmationVersion, takeoffPtsUs, takeoffPreviousPtsUs, takeoffNextPtsUs, " +
                "landingPtsUs, landingPreviousPtsUs, landingNextPtsUs, timeMapping, " +
                "timeMappingEvidenceKey FROM fvp_attempts WHERE id = 24",
        ).use {
            it.moveToFirst()
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12).forEach { index ->
                assertTrue("Expected NULL in new nullable column at index $index", it.isNull(index))
            }
            assertEquals("UNKNOWN", it.getString(11))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(12, it.getInt(0)) }
        db.close()
    }

    @Test
    fun migrate10To11_addsEmptyFvpTablesWithoutTouchingExistingData() {
        val name = "migration-fvp-test"
        helper.createDatabase(name, 10).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (11, 'Existing', 1, 1)")
            execSQL("INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes) VALUES (12, 'existing-v10', 11, 'SJ', 2, 'HEIGHT_CM', 30.0, 'CENTIMETER', 'nota previa')")
            execSQL("INSERT INTO attempts (id, assessmentId, ordinal, side, source, detectedFps) VALUES (13, 12, 0, NULL, 'UNKNOWN', 120)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 11, true, JumpDatabase.MIGRATION_10_11)

        db.query("SELECT COUNT(*) FROM assessments WHERE id = 12").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT notes FROM assessments WHERE id = 12").use {
            it.moveToFirst()
            assertEquals("nota previa", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM attempts WHERE id = 13").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        listOf("fvp_assessments", "fvp_conditions", "fvp_attempts").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use {
                assertEquals("Missing $table", 1, it.count)
            }
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        // Las tablas nuevas admiten filas válidas y la FK del atleta existe tras migrar.
        db.execSQL(
            "INSERT INTO fvp_assessments (sessionKey, athleteId, protocolId, dateTime, bodyMassKg, bodyMassSource, pushOffMode, pushOffM, pushOffSource, algorithmVersion, methodVersion, f0N, slopeFv, sfvNsPerM, conditionCount, vMinMps, vMaxMps, fMinN, fMaxN, warningsCsv) VALUES ('migration-fvp', 11, 'CMJ', 2, 70.0, 'PROFILE', 'COMMON', 0.4, 'MEASURED', 'test', 'test', 1000.0, -100.0, 100.0, 2, 1.0, 1.2, 900.0, 1100.0, '')",
        )
        db.query("SELECT id FROM fvp_assessments WHERE sessionKey = 'migration-fvp'").use {
            it.moveToFirst()
            assertTrue(it.getLong(0) > 0L)
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun migrate8To9_addsNullableAnthropometricsWithoutChangingExistingAthletes() {
        val name = "migration-anthropometrics-test"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (11, 'Existing', 1, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, JumpDatabase.MIGRATION_8_9)
        db.query("SELECT displayName, weightKg, heightCm FROM athletes WHERE id = 11").use {
            it.moveToFirst()
            assertEquals("Existing", it.getString(0))
            assertEquals(true, it.isNull(1))
            assertEquals(true, it.isNull(2))
        }
        db.close()
    }

    @Test
    fun migrate9To10_addsNullableNotesAndEventNeighborhood() {
        val name = "migration-notes-neighborhood-test"
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO assessments (id, sessionKey, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit) VALUES (12, 'legacy', 'SJ', 1, 'HEIGHT_CM', 30.0, 'CENTIMETER')")
            execSQL("INSERT INTO encoder_sessions (id, sessionKey, exercise, loadKg, dateTime, source, timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, maximumUncertainFraction, smoothingWindowSamples, totalRepetitions, validRepetitions) VALUES (13, 'encoder', 'BENCH_PRESS', 0.0, 1, 'UNKNOWN', 'UNKNOWN', 1.0, 0, 0, 0, 1, 1, 1, 1, 1, 0.04, 4, 0.5, 50000, 300000, 150000, 150000, 0.02, 0.2, 0.5, 0.15, 5, 0, 0)")
            execSQL("INSERT INTO attempts (id, assessmentId, ordinal, source, detectedFps) VALUES (14, 12, 0, 'UNKNOWN', 0)")
            execSQL("INSERT INTO attempt_events (attemptId, type, ordinal, ptsUs) VALUES (14, 'TAKEOFF', 0, 100)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 10, true, JumpDatabase.MIGRATION_9_10)
        db.query("SELECT notes FROM assessments WHERE id = 12").use { it.moveToFirst(); assertTrue(it.isNull(0)) }
        db.query("SELECT notes FROM encoder_sessions WHERE id = 13").use { it.moveToFirst(); assertTrue(it.isNull(0)) }
        db.query("SELECT frameIndex, previousPtsUs, nextPtsUs FROM attempt_events WHERE attemptId = 14").use { it.moveToFirst(); assertTrue(it.isNull(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2)) }
        db.close()
    }

    @Test
    fun migrate7To8_addsNullableAvatarWithoutChangingExistingAthletes() {
        val name = "migration-avatar-test"
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (11, 'Existing', 1, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 8, true, JumpDatabase.MIGRATION_7_8)
        db.query("SELECT displayName, avatarKey FROM athletes WHERE id = 11").use {
            it.moveToFirst()
            assertEquals("Existing", it.getString(0))
            assertEquals(true, it.isNull(1))
        }
        db.close()
    }

    @Test
    fun migrate6To7_addsTestingWithoutChangingExistingRows() {
        val name = "migration-testing-test"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (11, 'Existing', 1, 1)")
            execSQL("INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit) VALUES (12, 'existing-v6', 11, 'SJ', 2, 'HEIGHT_CM', 30.0, 'CENTIMETER')")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 7, true, JumpDatabase.MIGRATION_6_7)
        db.query("SELECT sessionKey, testingSessionId FROM assessments WHERE id = 12").use {
            it.moveToFirst()
            assertEquals("existing-v6", it.getString(0))
            assertEquals(true, it.isNull(1))
        }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun migrate5To6_isAdditiveAndPreservesExistingRows() {
        val name = "migration-groups-test"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (11, 'Existing', 1, 1)")
            execSQL("INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit) VALUES (12, 'existing', 11, 'SJ', 2, 'HEIGHT_CM', 30.0, 'CENTIMETER')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 6, true, JumpDatabase.MIGRATION_5_6)
        db.execSQL("INSERT INTO athlete_groups (id, name, createdAt, updatedAt) VALUES (20, 'Group', 3, 3)")
        db.execSQL("INSERT INTO athlete_group_cross_ref (groupId, athleteId) VALUES (20, 11)")
        db.query("SELECT COUNT(*) FROM assessments WHERE id = 12").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun migrate3To4_preservesMeasurementsAndAddsSpatialTables() {
        helper.createDatabase("migration-spatial-test", 3).apply {
            execSQL(
                """
                INSERT INTO assessments (
                    id, sessionKey, protocolId, dateTime,
                    primaryMetricKey, primaryMetricValue, primaryMetricUnit
                ) VALUES (12, 'existing-12', 'SJ', 66, 'HEIGHT_CM', 31.0, 'CENTIMETER')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migration-spatial-test",
            4,
            true,
            JumpDatabase.MIGRATION_3_4,
        )
        db.query("SELECT sessionKey FROM assessments WHERE id = 12").use { cursor ->
            cursor.moveToFirst()
            assertEquals("existing-12", cursor.getString(0))
        }
        listOf("attempt_calibrations", "attempt_spatial_marks").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertEquals("Missing $table", 1, cursor.count)
            }
        }
        db.close()
    }

    @Test
    fun migrate4To5_preservesLegacyRootsChildrenAndForeignKeys() {
        val name = "migration-ownership-test"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO assessments (id, sessionKey, protocolId, dateTime, primaryMetricKey, primaryMetricValue, primaryMetricUnit) VALUES (10, 'legacy-10', 'SJ', 10, 'HEIGHT_CM', 30.0, 'CENTIMETER')")
            execSQL("INSERT INTO attempts (id, assessmentId, ordinal, side, source, videoUri, detectedFps, dropHeightCm, distanceCm) VALUES (20, 10, 0, NULL, 'UNKNOWN', NULL, 120, NULL, NULL)")
            execSQL("INSERT INTO attempt_events (attemptId, type, ordinal, ptsUs) VALUES (20, 'TAKEOFF', 0, 100)")
            execSQL("INSERT INTO attempt_metrics (attemptId, `key`, ordinal, value, unit) VALUES (20, 'HEIGHT_CM', 0, 30.0, 'CENTIMETER')")
            execSQL("INSERT INTO attempt_calibrations (attemptId, frameIndex, pointAX, pointAY, pointBX, pointBY, referenceLengthM, metersPerPixel) VALUES (20, 1, 1, 2, 3, 4, 1, 0.01)")
            execSQL("INSERT INTO attempt_spatial_marks (attemptId, type, frameIndex, ptsUs, pointX, pointY) VALUES (20, 'START_POINT', 1, 100, 1, 2)")
            execSQL("INSERT INTO encoder_sessions (id, sessionKey, exercise, loadKg, dateTime, source, videoUri, observedFps, nominalFps, captureFps, timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, maximumUncertainFraction, smoothingWindowSamples, velocityEnterMps, velocityExitMps, totalRepetitions, validRepetitions, bestMcv, setVelocityLoss) VALUES (30, 'encoder-legacy', 'BENCH_PRESS', 80, 20, 'IMPORTED', NULL, 120, 120, NULL, 'REAL_TIME', 1, 1, 1, 2, 3, 4, 1, 0.01, 1, 0.04, 4, 0.5, 50000, 300000, 150000, 150000, 0.02, 0.2, 0.5, 0.15, 11, 0.04, 0.02, 1, 1, 0.8, NULL)")
            execSQL("INSERT INTO encoder_samples (sessionId, ordinal, frameIndex, sourcePtsUs, physicalTimeUs, rawPixelX, rawPixelY, rawMetricX, rawMetricY, smoothedMetricX, smoothedMetricY, velocityXMps, velocityYMps, confidence, trackingStatus, observationKind, fitResidualM, gapBeforeUs) VALUES (30, 0, 1, 100, 0, 1, 2, 0, 0, 0, 0, 0, 0.8, 0.9, 'TRACKING', 'MEASURED', 0.01, NULL)")
            execSQL("INSERT INTO encoder_repetitions (id, sessionId, ordinal, startSample, endSample, eccentricStartSample, eccentricEndSample, concentricStartSample, concentricEndSample, quality, reasons) VALUES (40, 30, 0, 0, 0, 0, 0, 0, 0, 'VALID', '')")
            execSQL("INSERT INTO encoder_rep_metrics (repetitionId, `key`, value, unit, validity, reason) VALUES (40, 'MCV', 0.8, 'METER_PER_SECOND', 'VALID', NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 5, true, JumpDatabase.MIGRATION_4_5)
        db.query("SELECT athleteId, testingSessionId FROM assessments WHERE id = 10").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
            assertEquals(true, c.isNull(1))
        }
        listOf("attempts", "attempt_events", "attempt_metrics", "attempt_calibrations", "attempt_spatial_marks").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        listOf("encoder_sessions", "encoder_samples", "encoder_repetitions", "encoder_rep_metrics").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        db.query("PRAGMA foreign_key_check").use { c -> assertEquals(0, c.count) }
        db.close()
    }

    @Test
    fun migrate2To3_preservesMeasurementsAndAddsEncoderTables() {
        helper.createDatabase(databaseName, 2).apply {
            execSQL(
                """
                INSERT INTO assessments (
                    id, sessionKey, protocolId, dateTime,
                    primaryMetricKey, primaryMetricValue, primaryMetricUnit
                ) VALUES (9, 'existing-9', 'SJ', 55, 'HEIGHT_CM', 30.0, 'CENTIMETER')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            JumpDatabase.MIGRATION_2_3,
        )

        db.query("SELECT sessionKey FROM assessments WHERE id = 9").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("existing-9", cursor.getString(0))
        }
        listOf("encoder_sessions", "encoder_samples", "encoder_repetitions", "encoder_rep_metrics").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertEquals("Missing $table", 1, cursor.count)
            }
        }
        db.close()
    }

    @Test
    fun migrate1To2_preservesLegacyCmj() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                """
                INSERT INTO jump_measurements (
                    id, dateTime, testType, jumpHeightCm, flightTimeMs,
                    takeoffVelocity, timeToTakeoffMs, rsiMod,
                    movementStartTimestampUs, takeoffTimestampUs,
                    landingTimestampUs, videoUri, detectedFps
                ) VALUES (
                    7, 123456789, 'CMJ', 44.13, 600.0,
                    2.942, 684.0, 0.645,
                    100000, 784000, 1384000, 'content://jump/7', 240
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            JumpDatabase.MIGRATION_1_2,
        )

        db.query("SELECT * FROM assessments WHERE id = 7").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("legacy-7", cursor.getString(cursor.getColumnIndexOrThrow("sessionKey")))
            assertEquals("CMJ", cursor.getString(cursor.getColumnIndexOrThrow("protocolId")))
            assertEquals(44.13, cursor.getDouble(cursor.getColumnIndexOrThrow("primaryMetricValue")), 0.001)
        }
        db.query("SELECT * FROM attempts WHERE assessmentId = 7").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("content://jump/7", cursor.getString(cursor.getColumnIndexOrThrow("videoUri")))
            assertEquals(240, cursor.getInt(cursor.getColumnIndexOrThrow("detectedFps")))
        }
        db.query("SELECT type, ptsUs FROM attempt_events WHERE attemptId = 7 ORDER BY ptsUs").use { cursor ->
            assertEquals(3, cursor.count)
            cursor.moveToFirst()
            assertEquals("MOVEMENT_START", cursor.getString(0))
            assertEquals(100000L, cursor.getLong(1))
            cursor.moveToNext()
            assertEquals("TAKEOFF", cursor.getString(0))
            assertEquals(784000L, cursor.getLong(1))
            cursor.moveToNext()
            assertEquals("LANDING", cursor.getString(0))
            assertEquals(1384000L, cursor.getLong(1))
        }
        db.query("SELECT `key`, value FROM attempt_metrics WHERE attemptId = 7").use { cursor ->
            assertEquals(5, cursor.count)
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='jump_measurements'").use {
            assertFalse(it.moveToFirst())
        }
        db.close()
    }
}
