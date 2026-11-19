package com.openjump.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // El esquema actual es Room 14 (el agregado V2 es aditivo y la barrera vive en el DAO); la
        // procedencia F-V-P nacio en Room 12 y por eso su compatibilidad se prueba aparte con
        // documentos declarados 12.
        assertEquals(14, MANUAL_BACKUP_ROOM_VERSION)
        assertEquals(14, document.sourceRoomSchemaVersion)
        assertEquals(14, ManualBackupCodec.decode(ManualBackupCodec.encode(document)).sourceRoomSchemaVersion)

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
     * Un backup escrito por la app Room 12 (sin tablas de angulos) sigue restaurando en el esquema
     * actual 13 con las tablas de angulos vacias; un documento declarado 12 que traiga filas de
     * angulos (que nacen en Room 13) es un hibrido y se rechaza ANTES de mutar la base.
     */
    @Test fun room12DocumentRestoresIntoCurrentSchemaWithEmptyAngleTables() = runBlocking {
        val document = fvpDocument().copy(sourceRoomSchemaVersion = 12)
        database.manualBackupDao().replaceAll(document)
        val restored = database.manualBackupDao().snapshot()
        assertEquals(1, restored.tables.fvpAssessments.size)
        assertEquals(2, restored.tables.fvpConditions.size)
        assertEquals(3, restored.tables.fvpAttempts.size)
        assertTrue(restored.tables.angleAnalyses.isEmpty())
        assertTrue(restored.tables.angleMarks.isEmpty())
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        assertEquals(0, database.manualBackupDao().angleAnalysisCount())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database.manualBackupDao().replaceAll(angleDocument().copy(sourceRoomSchemaVersion = 12))
            }
        }
        assertEquals(restored.tables, database.manualBackupDao().snapshot().tables)
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

    /**
     * Barrera TRANSITORIA (Room 14): una raiz V2 **sin atleta** (`athleteId` NULL) no cuelga de
     * `athletes`, asi que el snapshot v1 no la veria y `replaceAll` la borraria sin poder recrearla.
     * Debe bloquear snapshot y restore, y no tocar ni las filas legacy ni la propia raiz.
     */
    @Test fun angleV2RootWithoutOwnerBlocksSnapshotAndRestoreLeavingEveryRowIntact() = runBlocking {
        val baseline = fullDocument()
        database.manualBackupDao().replaceAll(baseline)
        val legacyBefore = legacyRowCounts()
        insertAngleV2Root(id = 9_001L, sessionKey = "v2-no-owner", athleteId = null)

        val snapshotFailure = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { database.manualBackupDao().snapshot() }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 1, frames = 0, marks = 0), snapshotFailure.rows)

        val restoreFailure = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { database.manualBackupDao().replaceAll(baseline) }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 1, frames = 0, marks = 0), restoreFailure.rows)

        // Ni una fila legacy borrada/reescrita y la raiz V2 (sin dueno) sigue exactamente igual.
        assertEquals(legacyBefore, legacyRowCounts())
        assertEquals(Triple(1, 0, 0), angleV2RowCounts())
        assertTrue(
            "la raiz V2 sin atleta debe seguir en su sitio",
            rowExists("SELECT COUNT(*) FROM angle_analysis_v2_roots WHERE id = 9001 AND athleteId IS NULL AND sessionKey = 'v2-no-owner'"),
        )
        assertTrue(foreignKeysClean())
    }

    /**
     * La barrera cubre las TRES tablas: con raiz (con atleta), fotograma y marca presentes, snapshot
     * y `replaceAll` rechazan y el agregado completo sigue intacto.
     */
    @Test fun angleV2RootWithAthleteFrameAndMarkBlocksBothPathsLeavingTheAggregateIntact() = runBlocking {
        val baseline = fullDocument()
        database.manualBackupDao().replaceAll(baseline)
        insertAngleV2Root(id = 9_002L, sessionKey = "v2-owned", athleteId = 101L)
        insertAngleV2Frame(id = 9_101L, rootId = 9_002L, ordinal = 0, imageKey = "image-key-1")
        insertAngleV2Mark(id = 9_201L, frameId = 9_101L, ordinal = 0, name = "Cadera")
        val legacyBefore = legacyRowCounts()

        val snapshotFailure = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { database.manualBackupDao().snapshot() }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 1, frames = 1, marks = 1), snapshotFailure.rows)
        val restoreFailure = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { database.manualBackupDao().replaceAll(baseline) }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 1, frames = 1, marks = 1), restoreFailure.rows)

        assertEquals(legacyBefore, legacyRowCounts())
        assertEquals(Triple(1, 1, 1), angleV2RowCounts())
        assertTrue(rowExists("SELECT COUNT(*) FROM angle_analysis_v2_roots WHERE id = 9002 AND athleteId = 101"))
        assertTrue(rowExists("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE id = 9101 AND rootId = 9002 AND imageKey = 'image-key-1'"))
        assertTrue(rowExists("SELECT COUNT(*) FROM angle_analysis_v2_marks WHERE id = 9201 AND frameId = 9101 AND name = 'Cadera'"))
        assertTrue(foreignKeysClean())
    }

    /**
     * Con V2 vacio el backup legacy sigue siendo el de siempre (snapshot captura, restore reemplaza)
     * y, en cuanto el agregado se vacia, el camino legacy se reabre: la barrera es sobre DATOS, no
     * sobre el esquema.
     */
    @Test fun legacySnapshotAndRestoreKeepWorkingWhileAngleV2IsEmpty() = runBlocking {
        val document = fvpDocument()
        database.manualBackupDao().replaceAll(document)
        assertEquals(document.tables, database.manualBackupDao().snapshot().tables)
        assertEquals(Triple(0, 0, 0), angleV2RowCounts())

        insertAngleV2Root(id = 9_003L, sessionKey = "v2-transient", athleteId = null)
        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { database.manualBackupDao().snapshot() }
        }
        // Vaciar el agregado (la cascada se lleva fotogramas y marcas) reabre snapshot y restore.
        database.openHelper.writableDatabase.execSQL("DELETE FROM angle_analysis_v2_roots")
        assertEquals(Triple(0, 0, 0), angleV2RowCounts())
        assertEquals(document.tables, database.manualBackupDao().snapshot().tables)

        val replacement = fullDocument()
        database.manualBackupDao().replaceAll(replacement)
        assertEquals(replacement.tables, database.manualBackupDao().snapshot().tables)
        assertTrue(foreignKeysClean())
    }

    /** Tablas que `replaceAll` reemplaza: se comparan antes/despues para probar que quedan intactas. */
    private fun legacyRowCounts(): Map<String, Int> = LEGACY_TABLES.associateWith(::tableRowCount)

    private fun tableRowCount(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun angleV2RowCounts(): Triple<Int, Int, Int> = Triple(
        tableRowCount("angle_analysis_v2_roots"),
        tableRowCount("angle_analysis_v2_frames"),
        tableRowCount("angle_analysis_v2_marks"),
    )

    private fun rowExists(sql: String): Boolean =
        database.openHelper.writableDatabase.query(sql).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

    private fun foreignKeysClean(): Boolean =
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }

    /**
     * Siembra el agregado V2 por SQL directo: `AngleAnalysisV2Dao` es de solo lectura y este arnes
     * no debe abrir una via de escritura de producto. Reproduce exactamente la forma de las tablas
     * Room 14, incluida una raiz con `athleteId` NULL.
     */
    private fun insertAngleV2Root(id: Long, sessionKey: String, athleteId: Long?) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, createdAt, updatedAt, revision) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, ANGLE_ANALYSIS_V2_FORMAT, sessionKey, athleteId, 1L, 2L, 0),
        )
    }

    private fun insertAngleV2Frame(id: Long, rootId: Long, ordinal: Int, imageKey: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_frames (id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex, " +
                "assignedPtsUs, reportedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
                "rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount, imageSha256, imageMimeType) " +
                "VALUES (?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, rootId, ordinal, 10L, 1_000L, 1920, 1080, 1920, 1080, 0, 1, imageKey,
                640, 360, 4_096L, "sha256:image-key-1", "image/png",
            ),
        )
    }

    private fun insertAngleV2Mark(id: Long, frameId: Long, ordinal: Int, name: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, frameId, ordinal, name, 0xFF3366CC.toInt(), 1, 10.0, 10.0, 20.0, 20.0, 30.0, 10.0),
        )
    }

    private companion object {
        val LEGACY_TABLES = listOf(
            "athletes", "athlete_groups", "athlete_group_cross_ref", "testing_sessions", "testing_participants",
            "assessments", "attempts", "attempt_events", "attempt_metrics", "attempt_calibrations",
            "attempt_spatial_marks", "encoder_sessions", "encoder_samples", "encoder_repetitions",
            "encoder_rep_metrics", "fvp_assessments", "fvp_conditions", "fvp_attempts",
            "angle_analyses", "angle_marks",
        )
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

    @Test fun angleRestorePreservesGeometryAndDropsPrivateSourceIdentity() = runBlocking {
        val document = angleDocument()
        database.manualBackupDao().replaceAll(document)
        // La app guarda identidad privada de la fuente en estas columnas; el backup no la lleva.
        val privateVideoUri = "content://private/angle"
        val privateIdentity = "private-file-id"
        val privateHash = "sha256:private-abc"
        database.openHelper.writableDatabase.execSQL(
            "UPDATE angle_analyses SET videoUri = '$privateVideoUri', sourceIdentity = '$privateIdentity', sourceHash = '$privateHash' WHERE id = 801",
        )
        // El sembrado tiene que haber escrito las tres columnas: si no, el NULL posterior a la
        // restauracion no probaria que el backup las omite.
        assertTrue(
            "el sembrado previo no dejo identidad privada en la base",
            database.openHelper.writableDatabase.query("SELECT videoUri, sourceIdentity, sourceHash FROM angle_analyses WHERE id = 801").use { cursor ->
                cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2)
            },
        )
        val captured = database.manualBackupDao().snapshot()
        assertEquals(document.tables.angleAnalyses, captured.tables.angleAnalyses)
        assertEquals(document.tables.angleMarks, captured.tables.angleMarks)
        val geometry = captured.tables.angleAnalyses.single()
        assertEquals(2, geometry.revision)
        assertEquals(1080, geometry.presentationWidth)
        assertEquals(1920, geometry.presentationHeight)
        assertEquals(90, geometry.rotationDegrees)
        assertEquals(42, geometry.frameIndex)
        assertEquals(1_400_000L, geometry.sourcePtsUs)
        assertEquals(1, geometry.geometryVersion)
        assertEquals(2, captured.tables.angleMarks.size)
        // El documento no modela las columnas privadas, asi que tampoco aparecen en su JSON.
        val encoded = String(ManualBackupCodec.encode(captured), Charsets.UTF_8)
        assertFalse(encoded.contains(privateVideoUri))
        assertFalse(encoded.contains(privateIdentity))
        assertFalse(encoded.contains(privateHash))

        // Un restore completo devuelve las mismas filas (ids y geometria intactos) y deja la
        // identidad privada NULL, igual que el contrato de URI de video de intentos y encoder.
        // La asercion va DESPUES del restore: antes, la base aun conserva lo sembrado.
        database.manualBackupDao().replaceAll(captured)
        val restored = database.manualBackupDao().snapshot()
        assertEquals(captured.tables, restored.tables)
        assertTrue(
            "la restauracion debe dejar la identidad privada en NULL",
            database.openHelper.writableDatabase.query("SELECT videoUri, sourceIdentity, sourceHash FROM angle_analyses WHERE id = 801").use { cursor ->
                cursor.moveToFirst() && cursor.isNull(0) && cursor.isNull(1) && cursor.isNull(2)
            },
        )
        assertTrue(database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        assertEquals(1, database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM angle_analyses").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(2, database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM angle_marks").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(
            "Rodilla",
            database.openHelper.writableDatabase.query("SELECT name FROM angle_marks WHERE id = 812").use { it.moveToFirst(); it.getString(0) },
        )
        // Geometria restaurada releida por SQL directo, no solo desde el snapshot del DAO.
        database.openHelper.writableDatabase.query(
            "SELECT revision, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
                "rotationDegrees, frameIndex, sourcePtsUs, geometryVersion FROM angle_analyses WHERE id = 801",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals(1920, cursor.getInt(1))
            assertEquals(1080, cursor.getInt(2))
            assertEquals(1080, cursor.getInt(3))
            assertEquals(1920, cursor.getInt(4))
            assertEquals(90, cursor.getInt(5))
            assertEquals(42, cursor.getInt(6))
            assertEquals(1_400_000L, cursor.getLong(7))
            assertEquals(1, cursor.getInt(8))
        }
        database.openHelper.writableDatabase.query(
            "SELECT visible, pointAX, pointAY, pointBX, pointBY, pointCX, pointCY FROM angle_marks WHERE id = 812",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(100.5, cursor.getDouble(1), 0.0)
            assertEquals(200.25, cursor.getDouble(2), 0.0)
            assertEquals(300.0, cursor.getDouble(3), 0.0)
            assertEquals(100.0, cursor.getDouble(4), 0.0)
            assertEquals(250.0, cursor.getDouble(5), 0.0)
            assertEquals(300.0, cursor.getDouble(6), 0.0)
        }
    }

    @Test fun angleRestoreRollsBackAtomicallyWhenAnInsertFails() = runBlocking {
        val baseline = fullDocument()
        database.manualBackupDao().replaceAll(baseline)
        // La marca falla despues de borrar e insertar todo lo anterior: si la transaccion no fuera
        // atomica, la base quedaria vacia o a medias.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER angle_marks_abort BEFORE INSERT ON angle_marks WHEN NEW.name = 'BOOM' " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        val booming = angleDocument().copy(
            tables = angleDocument().tables.copy(
                angleMarks = angleDocument().tables.angleMarks.map { if (it.ordinal == 1) it.copy(name = "BOOM") else it },
            ),
        )
        var failure: Exception? = null
        try {
            database.manualBackupDao().replaceAll(booming)
        } catch (error: Exception) {
            failure = error
        }
        assertTrue("el insert de la marca debe fallar: ${failure?.message}", failure?.message.orEmpty().contains("boom"))
        val after = database.manualBackupDao().snapshot()
        assertEquals("el rollback conserva los datos previos", baseline.tables, after.tables)
        assertTrue(after.tables.angleAnalyses.isEmpty() && after.tables.angleMarks.isEmpty())
    }

    /** Documento solo-angulos (sin atletas): ejercicio minimo de las tablas de Room 13. */
    private fun angleDocument() = ManualBackupDocument(
        contract = MANUAL_BACKUP_CONTRACT,
        formatVersion = MANUAL_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MANUAL_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 2L,
        mediaIncluded = false,
        preferencesIncluded = false,
        tables = ManualBackupTables(
            athletes = emptyList(), athleteGroups = emptyList(), athleteGroupCrossRef = emptyList(),
            testingSessions = emptyList(), testingParticipants = emptyList(), assessments = emptyList(),
            attempts = emptyList(), attemptEvents = emptyList(), attemptMetrics = emptyList(),
            attemptCalibrations = emptyList(), attemptSpatialMarks = emptyList(), encoderSessions = emptyList(),
            encoderSamples = emptyList(), encoderRepetitions = emptyList(), encoderRepMetrics = emptyList(),
            angleAnalyses = listOf(
                BackupAngleAnalysis(
                    id = 801L, sessionKey = "angle-restore-key", athleteId = null, createdAt = 10L,
                    updatedAt = 20L, revision = 2, encodedWidth = 1920, encodedHeight = 1080,
                    presentationWidth = 1080, presentationHeight = 1920, rotationDegrees = 90,
                    frameIndex = 42, sourcePtsUs = 1_400_000L, geometryVersion = 1,
                ),
            ),
            angleMarks = listOf(
                BackupAngleMark(811L, 801L, 0, "Cadera", 0xFF3366CC.toInt(), true, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0),
                BackupAngleMark(812L, 801L, 1, "Rodilla", 0x803366CC.toInt(), false, 100.5, 200.25, 300.0, 100.0, 250.0, 300.0),
            ),
        ),
    )

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
