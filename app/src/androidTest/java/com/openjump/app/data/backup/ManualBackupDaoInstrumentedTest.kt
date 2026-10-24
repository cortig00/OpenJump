package com.openjump.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualBackupDaoInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()

    @After fun close() = database.close()

    @Test fun restoreRoundTripCoversAllTablesPreservesIdsAndDropsVideoUris() = runBlocking {
        val document = fullDocument()
        database.manualBackupDao().replaceAll(document)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE attempts SET videoUri = 'content://private/jump' WHERE id = 301",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE encoder_sessions SET videoUri = 'content://private/encoder' WHERE id = 401",
        )

        val captured = database.manualBackupDao().snapshot()
        assertEquals(document.tables, captured.tables)
        val sentinel = document.copy(
            tables = ManualBackupTables(
                athletes = listOf(BackupAthlete(900L, "Sentinel", null, null, null, 1L, 1L, null, null, null, null)),
                athleteGroups = emptyList(), athleteGroupCrossRef = emptyList(), testingSessions = emptyList(),
                testingParticipants = emptyList(), assessments = emptyList(), attempts = emptyList(),
                attemptEvents = emptyList(), attemptMetrics = emptyList(), attemptCalibrations = emptyList(),
                attemptSpatialMarks = emptyList(), encoderSessions = emptyList(), encoderSamples = emptyList(),
                encoderRepetitions = emptyList(), encoderRepMetrics = emptyList(),
            ),
        )
        database.manualBackupDao().replaceAll(sentinel)
        database.manualBackupDao().replaceAll(captured)

        val restored = database.manualBackupDao().snapshot()
        assertEquals(captured.tables, restored.tables)
        assertEquals(1, restored.tables.athletes.size)
        assertEquals(1, restored.tables.athleteGroups.size)
        assertEquals(1, restored.tables.athleteGroupCrossRef.size)
        assertEquals(1, restored.tables.testingSessions.size)
        assertEquals(1, restored.tables.testingParticipants.size)
        assertEquals(1, restored.tables.assessments.size)
        assertEquals(1, restored.tables.attempts.size)
        assertEquals(3, restored.tables.attemptEvents.size)
        assertEquals(1, restored.tables.attemptMetrics.size)
        assertEquals(1, restored.tables.attemptCalibrations.size)
        assertEquals(1, restored.tables.attemptSpatialMarks.size)
        assertEquals(1, restored.tables.encoderSessions.size)
        assertEquals(2, restored.tables.encoderSamples.size)
        assertEquals(1, restored.tables.encoderRepetitions.size)
        assertEquals(1, restored.tables.encoderRepMetrics.size)
        assertTrue(restored.tables.attemptEvents.any { it.ptsUs == 123_456_789_012_345L && it.frameIndex == 1 && it.previousPtsUs == 123_456_789_012_343L && it.nextPtsUs == 123_456_789_012_346L })
        assertEquals("jump note", restored.tables.assessments.single().notes)
        assertEquals("encoder note", restored.tables.encoderSessions.single().notes)
        assertEquals(2_000_000L, restored.tables.encoderSamples[1].sourcePtsUs)
        assertNull(database.jumpDao().assessmentWithAttempts(201L)?.attempts?.single()?.attempt?.videoUri)
        assertTrue(database.openHelper.writableDatabase.query("SELECT videoUri FROM encoder_sessions WHERE id = 401").use { cursor ->
            cursor.moveToFirst()
            cursor.isNull(0)
        })
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })

        val insertedId = database.jumpDao().insertAssessment(
            AssessmentEntity(sessionKey = "after-restore", athleteId = 101L, protocolId = "CMJ", dateTime = 3L, primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 20.0, primaryMetricUnit = "CENTIMETER"),
        )
        assertTrue(insertedId > 201L)
        assertEquals(insertedId, database.jumpDao().assessmentIdForSession("after-restore"))
    }

    @Test fun legacyRoom9PayloadDecodesAndRestoresWithNewFieldsNull() = runBlocking {
        val v1Document = fullDocument().copy(
            formatVersion = 1,
            sourceRoomSchemaVersion = 10,
            tables = fullDocument().tables.copy(
                assessments = fullDocument().tables.assessments.map { it.copy(notes = null) },
                encoderSessions = fullDocument().tables.encoderSessions.map { it.copy(notes = null) },
                attemptEvents = fullDocument().tables.attemptEvents.map { it.copy(frameIndex = null, previousPtsUs = null, nextPtsUs = null) },
            ),
        )
        val room9Json = String(ManualBackupCodec.encode(v1Document), Charsets.UTF_8)
            .replace("\"sourceRoomSchemaVersion\":10,", "\"sourceRoomSchemaVersion\":9,")
            .replace(",\"primaryMetricUnit\":\"CENTIMETER\",\"notes\":\"jump note\"", ",\"primaryMetricUnit\":\"CENTIMETER\"")
            .replace(",\"setVelocityLoss\":0.1,\"notes\":\"encoder note\"", ",\"setVelocityLoss\":0.1")
            .replace(Regex("(\"ptsUs\":[0-9]+),\"frameIndex\":[0-9]+,\"previousPtsUs\":(?:[0-9]+|null),\"nextPtsUs\":(?:[0-9]+|null)"), "$1")
        val legacy = ManualBackupCodec.decode(room9Json)
        database.manualBackupDao().replaceAll(legacy)
        val restored = database.manualBackupDao().snapshot()
        assertNull(restored.tables.assessments.single().notes)
        assertNull(restored.tables.encoderSessions.single().notes)
        assertTrue(restored.tables.attemptEvents.all { it.frameIndex == null && it.previousPtsUs == null && it.nextPtsUs == null })
    }

    @Test fun bilateralBackupRestoresWithExplicitUnilateralSequences() = runBlocking {
        val base = fullDocument()
        val assessment = base.tables.assessments.single().copy(
            testingSessionId = null,
            protocolId = "ASYMMETRY",
            primaryMetricKey = "ASYMMETRY_PERCENT",
            primaryMetricUnit = "PERCENT",
        )
        val left = base.tables.attempts.single().copy(side = "LEFT", ordinal = 0)
        val right = left.copy(id = left.id + 1, ordinal = 1, side = "RIGHT")
        val events = base.tables.attemptEvents.flatMap { event ->
            listOf(event.copy(attemptId = left.id), event.copy(attemptId = right.id))
        }
        val metrics = base.tables.attemptMetrics + base.tables.attemptMetrics.map { it.copy(attemptId = right.id) }
        val bilateral = base.copy(tables = base.tables.copy(
            assessments = listOf(assessment),
            attempts = listOf(left, right),
            attemptEvents = events,
            attemptMetrics = metrics,
            attemptCalibrations = emptyList(),
            attemptSpatialMarks = emptyList(),
        ))
        database.manualBackupDao().replaceAll(bilateral)
        val restored = database.manualBackupDao().snapshot()
        assertEquals(bilateral.tables.assessments, restored.tables.assessments)
        assertEquals(bilateral.tables.attempts, restored.tables.attempts)
        assertEquals(6, restored.tables.attemptEvents.size)
        assertEquals(setOf("LEFT", "RIGHT"), restored.tables.attempts.map { it.side }.toSet())
    }

    @Test fun insertionFailureRollsBackTheCompleteReplacement() = runBlocking {
        val original = fullDocument()
        database.manualBackupDao().replaceAll(original)
        val before = database.manualBackupDao().snapshot().tables
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_manual_restore BEFORE INSERT ON athletes " +
                "WHEN NEW.id = 101 BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END",
        )
        try {
            val failure = runCatching { database.manualBackupDao().replaceAll(original) }
            assertTrue(failure.isFailure)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_manual_restore")
        }
        assertEquals(before, database.manualBackupDao().snapshot().tables)
    }

    @Test fun fvpTablesSurviveReplaceAllSnapshotAndClearingOrder() = runBlocking {
        val document = fvpDocument()
        // Dos restauraciones seguidas: la segunda debe vaciar el recolector F-V-P antes de
        // `clearAthletes()` (FK NO_ACTION) y no chocar con las claves primarias previas.
        database.manualBackupDao().replaceAll(document)
        database.manualBackupDao().replaceAll(document)
        val captured = database.manualBackupDao().snapshot()
        assertEquals(document.tables, captured.tables)
        assertEquals(1, captured.tables.fvpAssessments.size)
        assertEquals(2, captured.tables.fvpConditions.size)
        assertEquals(3, captured.tables.fvpAttempts.size)
        // El par de pendiente vuelve exacto y coherente (sfvNsPerM = -slopeFv, W3.1).
        val capturedRoot = captured.tables.fvpAssessments.single()
        assertEquals(-300.0, capturedRoot.slopeFv, 0.0)
        assertEquals(300.0, capturedRoot.sfvNsPerM, 0.0)
        assertEquals(-capturedRoot.slopeFv, capturedRoot.sfvNsPerM, 0.0)
        assertEquals(1, database.manualBackupDao().fvpAssessmentCount())
        assertEquals(2, database.manualBackupDao().fvpConditionCount())
        assertEquals(3, database.manualBackupDao().fvpAttemptCount())
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        // Un backup sin tablas F-V-P reemplaza al que si las tenia.
        database.manualBackupDao().replaceAll(fullDocument())
        val cleared = database.manualBackupDao().snapshot()
        assertTrue(cleared.tables.fvpAssessments.isEmpty())
        assertTrue(cleared.tables.fvpConditions.isEmpty())
        assertTrue(cleared.tables.fvpAttempts.isEmpty())
        assertEquals(document.tables.athletes, cleared.tables.athletes)
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        // Un payload que se declara Room 10 no puede traer filas F-V-P.
        val mismatched = runCatching { database.manualBackupDao().replaceAll(document.copy(sourceRoomSchemaVersion = 10)) }
        assertTrue(mismatched.isFailure)
    }

    @Test fun legacyRoom10PayloadRestoresWithEmptyFvpTables() = runBlocking {
        val encoded = String(ManualBackupCodec.encode(fullDocument().copy(sourceRoomSchemaVersion = 10)), Charsets.UTF_8)
        val withoutFvpKeys = encoded
            .replace(",\"fvpAssessments\":[]", "")
            .replace(",\"fvpConditions\":[]", "")
            .replace(",\"fvpAttempts\":[]", "")
        val legacy = ManualBackupCodec.decode(withoutFvpKeys)
        database.manualBackupDao().replaceAll(legacy)
        val restored = database.manualBackupDao().snapshot()
        assertTrue(restored.tables.fvpAssessments.isEmpty())
        assertTrue(restored.tables.fvpConditions.isEmpty())
        assertTrue(restored.tables.fvpAttempts.isEmpty())
        assertEquals(legacy.tables.athletes, restored.tables.athletes)
        assertEquals(legacy.tables.assessments, restored.tables.assessments)
        assertEquals(0, ManualBackupSummary.from(restored).fvpAssessmentCount)
    }

    /**
     * B2: DAO -> documento -> restore conserva las 20 columnas de procedencia conocidas y un
     * documento Room 11 (F-V-P nace en Room 11) las deja UNKNOWN/NULL sin rechazar las filas.
     */
    @Test fun fvpProvenanceRoundTripsThroughDaoAndRoom11DocumentsRestoreAsUnknown() = runBlocking {
        val document = fvpDocument()
        assertEquals(12, MANUAL_BACKUP_ROOM_VERSION)
        assertEquals(12, document.sourceRoomSchemaVersion)
        assertEquals(12, ManualBackupCodec.decode(ManualBackupCodec.encode(document)).sourceRoomSchemaVersion)

        database.manualBackupDao().replaceAll(document)
        val captured = database.manualBackupDao().snapshot()
        assertEquals(document.tables, captured.tables)
        val root = captured.tables.fvpAssessments.single()
        assertEquals(1_700_000_000_900L, root.acquiredAt)
        assertEquals("SAME_ACQUISITION", root.acquisitionContextKind)
        assertEquals("ctx-601", root.acquisitionContextKey)
        assertEquals("WEIGHED", root.bodyMassMeasurement)
        assertEquals(1_700_000_000_800L, root.bodyMassMeasuredAt)
        val weighedLoad = captured.tables.fvpConditions.single { it.conditionKey == "0kg" }
        assertEquals("WEIGHED", weighedLoad.addedLoadSource)
        assertEquals(1_700_000_001_000L, weighedLoad.addedLoadMeasuredAt)
        val nominalLoad = captured.tables.fvpConditions.single { it.conditionKey == "20kg" }
        assertEquals("NOMINAL", nominalLoad.addedLoadSource)
        assertEquals(null, nominalLoad.addedLoadMeasuredAt)
        val reused = captured.tables.fvpAttempts.single { it.id == 801L }
        assertEquals(1_690_000_000_000L, reused.sourceRecordedAt)
        assertEquals(101L, reused.sourceAthleteId)
        assertEquals(1_700_000_001_100L, reused.acquiredAt)
        assertEquals(1_700_000_001_200L, reused.reuseConfirmedAt)
        assertEquals(FVP_REUSE_CONFIRMATION_VERSION, reused.reuseConfirmationVersion)
        assertEquals(1_000_000L, reused.takeoffPtsUs)
        assertEquals(999_000L, reused.takeoffPreviousPtsUs)
        assertEquals(1_001_000L, reused.takeoffNextPtsUs)
        assertEquals(1_500_000L, reused.landingPtsUs)
        assertEquals(1_499_000L, reused.landingPreviousPtsUs)
        assertEquals(1_501_000L, reused.landingNextPtsUs)
        assertEquals("IDENTITY_VERIFIED", reused.timeMapping)
        assertEquals("evidence-801", reused.timeMappingEvidenceKey)

        // Documento Room 11 con filas F-V-P y sin claves de procedencia (lo que escribia la app 11).
        val room11 = document.copy(
            sourceRoomSchemaVersion = 11,
            tables = document.tables.copy(
                fvpAssessments = document.tables.fvpAssessments.map {
                    it.copy(acquiredAt = null, acquisitionContextKind = "UNKNOWN", acquisitionContextKey = null, bodyMassMeasurement = "UNKNOWN", bodyMassMeasuredAt = null)
                },
                fvpConditions = document.tables.fvpConditions.map { it.copy(addedLoadSource = "UNKNOWN", addedLoadMeasuredAt = null) },
                fvpAttempts = document.tables.fvpAttempts.map {
                    it.copy(
                        sourceRecordedAt = null, sourceAthleteId = null, acquiredAt = null, reuseConfirmedAt = null,
                        reuseConfirmationVersion = null, takeoffPtsUs = null, takeoffPreviousPtsUs = null,
                        takeoffNextPtsUs = null, landingPtsUs = null, landingPreviousPtsUs = null,
                        landingNextPtsUs = null, timeMapping = "UNKNOWN", timeMappingEvidenceKey = null,
                    )
                },
            ),
        )
        val encoded11 = ManualBackupCodec.encode(room11)
        assertTrue(String(encoded11, Charsets.UTF_8).contains("\"sourceRoomSchemaVersion\":11"))
        database.manualBackupDao().replaceAll(ManualBackupCodec.decode(encoded11))
        val restored = database.manualBackupDao().snapshot()
        assertEquals(1, restored.tables.fvpAssessments.size)
        assertEquals(2, restored.tables.fvpConditions.size)
        assertEquals(3, restored.tables.fvpAttempts.size)
        val restoredRoot = restored.tables.fvpAssessments.single()
        assertEquals("UNKNOWN", restoredRoot.acquisitionContextKind)
        assertEquals(null, restoredRoot.acquisitionContextKey)
        assertEquals("UNKNOWN", restoredRoot.bodyMassMeasurement)
        assertEquals(null, restoredRoot.acquiredAt)
        assertEquals(null, restoredRoot.bodyMassMeasuredAt)
        assertTrue(restored.tables.fvpConditions.all { it.addedLoadSource == "UNKNOWN" && it.addedLoadMeasuredAt == null })
        assertTrue(
            restored.tables.fvpAttempts.all {
                it.timeMapping == "UNKNOWN" && it.timeMappingEvidenceKey == null && it.sourceRecordedAt == null &&
                    it.sourceAthleteId == null && it.acquiredAt == null && it.reuseConfirmedAt == null &&
                    it.reuseConfirmationVersion == null && it.takeoffPtsUs == null && it.landingPtsUs == null
            },
        )

        // La procedencia nace en Room 12: un documento declarado 11 que la traiga es un hibrido y
        // se rechaza ANTES de mutar. El estado restaurado (11 sin claves) queda intacto.
        val beforeHybrid = database.manualBackupDao().snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { database.manualBackupDao().replaceAll(document.copy(sourceRoomSchemaVersion = 11)) }
        }
        assertEquals(beforeHybrid.tables, database.manualBackupDao().snapshot().tables)

        // Guarda de linaje: la declaracion del backup y la version real de SQLite no pueden divergir.
        val roomVersion = database.openHelper.readableDatabase.version
        assertEquals(MANUAL_BACKUP_ROOM_VERSION, roomVersion)
    }

    /**
     * Las referencias de origen son informativas y NO tienen FK: restaurar un documento cuyos
     * `sourceAthleteId`/`sourceAssessmentId` ya no existen debe conservar el snapshot FVP y dejar
     * `foreign_key_check` limpio (contrato 18 §5, "origen borrado").
     */
    @Test
    fun restoringDanglingSourceReferencesKeepsTheSnapshotAndForeignKeysClean() = runBlocking {
        val base = fvpDocument()
        val dangling = base.copy(
            tables = base.tables.copy(
                fvpAttempts = base.tables.fvpAttempts.map {
                    it.copy(sourceAthleteId = 4_000_000_000L, sourceAssessmentId = 999_999L)
                },
            ),
        )
        database.manualBackupDao().replaceAll(dangling)
        val restored = database.manualBackupDao().snapshot()
        assertTrue(restored.tables.fvpAttempts.isNotEmpty())
        restored.tables.fvpAttempts.forEach {
            assertEquals(4_000_000_000L, it.sourceAthleteId)
            assertEquals(999_999L, it.sourceAssessmentId)
        }
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
    }

    private fun fvpDocument(): ManualBackupDocument {
        val base = fullDocument()
        return base.copy(
            tables = base.tables.copy(
                fvpAssessments = listOf(
                    BackupFvpAssessment(
                        id = 601L, sessionKey = "fvp-key", athleteId = 101L, testingSessionId = null,
                        protocolId = "CMJ", dateTime = 6L, bodyMassKg = 80.0, bodyMassSource = "PROFILE",
                        pushOffMode = "COMMON", pushOffM = 0.4, pushOffSource = "MEASURED",
                        pushOffLandmark = "ASIS", pushOffLandmarkHeightCm = 92.5, pushOffProtocol = "LANDMARK",
                        pushOffMeasuredAt = 5L, pushOffCrouchCm = 30.0, pushOffExtendedCm = 122.5,
                        sourceFps = 240.0, algorithmVersion = "fvp-poc-1.0", methodVersion = "fvp-app-1.0",
                        notes = "fvp note", f0N = 1_200.0, slopeFv = -300.0, sfvNsPerM = 300.0,
                        v0Mps = 1.4, pmaxW = 900.0, r2 = 0.98, conditionCount = 2, vMinMps = 0.2,
                        vMaxMps = 1.6, fMinN = 400.0, fMaxN = 1_800.0, f0RelNPerKg = 15.0,
                        pmaxRelWPerKg = 11.25, warningsCsv = "LOW_R2",
                        acquiredAt = 1_700_000_000_900L, acquisitionContextKind = "SAME_ACQUISITION",
                        acquisitionContextKey = "ctx-601", bodyMassMeasurement = "WEIGHED",
                        bodyMassMeasuredAt = 1_700_000_000_800L,
                    ),
                ),
                fvpConditions = listOf(
                    BackupFvpCondition(701L, 601L, 0, "0kg", 0.0, 80.0, 0.4, 0.42, 0, "WEIGHED", 1_700_000_001_000L),
                    BackupFvpCondition(702L, 601L, 1, "20kg", 20.0, 100.0, 0.4, 0.31, null, "NOMINAL", null),
                ),
                fvpAttempts = listOf(
                    BackupFvpAttempt(
                        801L, 701L, 0, 0.42, "FLIGHT_TIME_VIDEO", 0.585, 240.0, 201L, 0, "CMJ", true, null, "attempt note",
                        sourceRecordedAt = 1_690_000_000_000L, sourceAthleteId = 101L,
                        acquiredAt = 1_700_000_001_100L, reuseConfirmedAt = 1_700_000_001_200L,
                        reuseConfirmationVersion = FVP_REUSE_CONFIRMATION_VERSION,
                        takeoffPtsUs = 1_000_000L, takeoffPreviousPtsUs = 999_000L, takeoffNextPtsUs = 1_001_000L,
                        landingPtsUs = 1_500_000L, landingPreviousPtsUs = 1_499_000L, landingNextPtsUs = 1_501_000L,
                        timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = "evidence-801",
                    ),
                    BackupFvpAttempt(802L, 701L, 1, 0.35, "MANUAL", null, null, null, null, null, false, "SHORT_FLIGHT", null),
                    BackupFvpAttempt(
                        803L, 702L, 0, 0.31, "IMPORTED", null, 120.0, null, null, null, true, null, null,
                        sourceRecordedAt = 1_680_000_000_000L,
                    ),
                ),
            ),
        )
    }

    private fun fullDocument() = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1L,
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = ManualBackupTables(
            athletes = listOf(BackupAthlete(101L, "Original", null, "F", "notes", 1L, 2L, null, "avatar", 80.0, 180.0)),
            athleteGroups = listOf(BackupGroup(102L, "Group", "group notes", 1L, 2L, null)),
            athleteGroupCrossRef = listOf(BackupMembership(102L, 101L)),
            testingSessions = listOf(BackupTestingSession(103L, 102L, "Group", "JUMP", "CMJ", null, null, null, null, null, 1, "COMPLETED", 0, 1L, 2L, 3L)),
            testingParticipants = listOf(BackupTestingParticipant(103L, 101L, 0, null)),
            assessments = listOf(BackupAssessment(201L, "assessment-key", 101L, 103L, "CMJ", 2L, "HEIGHT_CM", 20.0, "CENTIMETER", "jump note")),
            attempts = listOf(BackupAttempt(301L, 201L, 0, null, "IMPORTED", 30, null, null)),
            attemptEvents = listOf(
                BackupAttemptEvent(301L, "LANDING", 0, 123_456_789_012_346L, 2, 123_456_789_012_345L, null),
                BackupAttemptEvent(301L, "MOVEMENT_START", 0, 123_456_789_012_343L, 0, null, 123_456_789_012_345L),
                BackupAttemptEvent(301L, "TAKEOFF", 0, 123_456_789_012_345L, 1, 123_456_789_012_343L, 123_456_789_012_346L),
            ),
            attemptMetrics = listOf(BackupAttemptMetric(301L, "HEIGHT_CM", 0, 20.0, "CENTIMETER")),
            attemptCalibrations = listOf(BackupAttemptCalibration(301L, 2, 1.0, 2.0, 5.0, 2.0, 1.0, .25)),
            attemptSpatialMarks = listOf(BackupAttemptSpatialMark(301L, "START_POINT", 2, 123_456_789_012_345L, 1.0, 2.0)),
            encoderSessions = listOf(BackupEncoderSession(401L, "encoder-key", 101L, null, "BENCH_PRESS", 50.0, 4L, "IMPORTED", 120.0, 120.0, 120.0, "REAL_TIME", 1.0, 0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1, .04, 4.0, .5, 50_000, 300_000, 150_000, 150_000, .02, .2, .5, .15, 5, null, null, 1, 1, 1.2, .1, "encoder note")),
            encoderSamples = listOf(
                BackupEncoderSample(401L, 0, 2, 1_000_000L, 0L, 1.0, 2.0, .1, .2, .1, .2, .3, .4, .9, "TRACKING", "MEASURED", .01, null),
                BackupEncoderSample(401L, 1, 3, 2_000_000L, 1_000_000L, 1.1, 2.1, .2, .3, .2, .3, .4, .5, .8, "UNCERTAIN", "MEASURED", .02, 1_000_000L),
            ),
            encoderRepetitions = listOf(BackupEncoderRepetition(501L, 401L, 0, 0, 1, 0, 0, 1, 1, "VALID", "")),
            encoderRepMetrics = listOf(BackupEncoderRepMetric(501L, "MCV", 1.2, "METER_PER_SECOND", "VALID", null)),
        ),
    )
}
