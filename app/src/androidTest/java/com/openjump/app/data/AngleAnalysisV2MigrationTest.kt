package com.openjump.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Migración Room 13→14 del agregado multi-fotograma (paso D del plan de ángulos).
 *
 * El contrato es **aditivo**: las tablas `angle_analyses`/`angle_marks` y todas las tablas de
 * saltos, Encoder y F-V-P conservan filas, IDs y referencias; las tres tablas nuevas nacen vacías
 * y con la misma FK de atleta (`NO_ACTION`) que el agregado legacy. Los IDs del agregado nuevo
 * pueden colisionar con los legacy sin ambigüedad porque la identidad incluye tabla y
 * `analysisFormat`.
 *
 * Cada test construye un nombre de base único con [UUID] y lo usa como nombre de la base de
 * [MigrationTestHelper]. Así ni `createDatabase` ni ninguna limpieza del helper pueden coincidir
 * con la base de la app/usuario ni con un residuo de otro run: la única base que el helper puede
 * borrar es la que ese mismo test acaba de nombrar. No requiere borrar datos del dispositivo. Son
 * pruebas de migración y de esquema; no ejecutan la app ni la UI.
 */
@RunWith(AndroidJUnit4::class)
class AngleAnalysisV2MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JumpDatabase::class.java,
    )

    /** Nombre de base propio y no reutilizable: el prefijo sólo ayuda a identificarlo en logs. */
    private fun newTestDatabaseName(label: String): String = "$label-${UUID.randomUUID()}"

    /**
     * Alinea la conexión de [MigrationTestHelper] con la base de producción.
     *
     * El helper abre la base con un `SQLiteOpenHelper` de framework cuyo callback no ejecuta el
     * `PRAGMA foreign_keys = ON` que sí ejecuta el `onOpen` generado por Room, así que esa conexión
     * nace con la verificación de claves foráneas apagada. No es un defecto de la migración: el
     * esquema v14 define las FK y la app real las comprueba. Aquí se activa explícitamente (fuera
     * de transacción, único momento en que el framework lo permite) y se verifica que el PRAGMA
     * quedó realmente en 1, para que las aserciones de huérfanos/cascada prueben el esquema y no el
     * modo por defecto de la conexión de test.
     */
    private fun enableForeignKeysLikeProduction(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.query("PRAGMA foreign_keys").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    // ---------------------------------------------------------------------------------------
    // 13 → 14
    // ---------------------------------------------------------------------------------------

    @Test
    fun migrate13To14_isAdditiveAndKeepsLegacyAngleRowsAndOtherTables() {
        val name = newTestDatabaseName("angle-v2-migration-from-13")
        helper.createDatabase(name, 13).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (31, 'Legacy', 1, 1)")
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (77, 'Nuevo', 2, 2)")
            execSQL(
                "INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, " +
                    "primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes) " +
                    "VALUES (32, 'jump-v13', 31, 'SJ', 5, 'HEIGHT_CM', 32.0, 'CENTIMETER', 'nota v13')",
            )
            execSQL(
                "INSERT INTO attempts (id, assessmentId, ordinal, side, source, detectedFps) " +
                    "VALUES (33, 32, 0, NULL, 'UNKNOWN', 240)",
            )
            execSQL("INSERT INTO attempt_events (attemptId, type, ordinal, ptsUs) VALUES (33, 'TAKEOFF', 0, 100)")
            execSQL(INSERT_ENCODER_SESSION_13)
            execSQL(
                "INSERT INTO angle_analyses (id, sessionKey, athleteId, createdAt, updatedAt, revision, " +
                    "videoUri, sourceIdentity, sourceHash, encodedWidth, encodedHeight, " +
                    "presentationWidth, presentationHeight, rotationDegrees, frameIndex, sourcePtsUs, " +
                    "geometryVersion) VALUES (41, 'angle-shared-key', 31, 1000, 1000, 2, " +
                    "'content://video/41', 'identity-41', NULL, 1920, 1080, 1080, 1920, 90, 42, " +
                    "1400000, 1)",
            )
            execSQL(
                "INSERT INTO angle_marks (id, analysisId, ordinal, name, colorArgb, visible, " +
                    "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                    "VALUES (51, 41, 0, 'Rodilla', -65536, 1, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)",
            )
            execSQL(
                "INSERT INTO angle_marks (id, analysisId, ordinal, name, colorArgb, visible, " +
                    "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                    "VALUES (52, 41, 1, 'Cadera', -16711936, 0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            name,
            14,
            true,
            JumpDatabase.MIGRATION_13_14,
        )
        enableForeignKeysLikeProduction(db)

        // (a) Nada legacy cambia: ni filas, ni columnas, ni referencias de ángulos/saltos/Encoder.
        db.query(
            "SELECT id, sessionKey, athleteId, revision, videoUri, frameIndex, sourcePtsUs, " +
                "presentationWidth, rotationDegrees, geometryVersion FROM angle_analyses WHERE id = 41",
        ).use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(41L, it.getLong(0))
            assertEquals("angle-shared-key", it.getString(1))
            assertEquals(31L, it.getLong(2))
            assertEquals(2, it.getInt(3))
            assertEquals("content://video/41", it.getString(4))
            assertEquals(42, it.getInt(5))
            assertEquals(1_400_000L, it.getLong(6))
            assertEquals(1080, it.getInt(7))
            assertEquals(90, it.getInt(8))
            assertEquals(1, it.getInt(9))
        }
        db.query("SELECT id, analysisId, ordinal, name, colorArgb, visible, pointAX, pointCY FROM angle_marks ORDER BY ordinal").use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals(51L, it.getLong(0))
            assertEquals(41L, it.getLong(1))
            assertEquals(0, it.getInt(2))
            assertEquals("Rodilla", it.getString(3))
            assertEquals(-65536, it.getInt(4))
            assertEquals(1, it.getInt(5))
            assertEquals(1.0, it.getDouble(6), 0.0001)
            assertEquals(6.0, it.getDouble(7), 0.0001)
            it.moveToNext()
            assertEquals(52L, it.getLong(0))
            assertEquals(0, it.getInt(5))
        }
        db.query("SELECT primaryMetricValue, notes FROM assessments WHERE id = 32").use {
            it.moveToFirst()
            assertEquals(32.0, it.getDouble(0), 0.0001)
            assertEquals("nota v13", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM attempts WHERE assessmentId = 32").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM attempt_events WHERE attemptId = 33").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT sessionKey, exercise FROM encoder_sessions WHERE id = 61").use {
            it.moveToFirst()
            assertEquals("encoder-v13", it.getString(0))
            assertEquals("BENCH_PRESS", it.getString(1))
        }

        // (b) Las tres tablas nuevas existen, vacías, con user_version 14.
        V2_TABLES.forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use {
                assertEquals("Missing $table", 1, it.count)
            }
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        db.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(14, it.getInt(0)) }

        // (c) Colisión de IDs y de sessionKey con el agregado legacy: tablas e identidad de tipo
        // distintas, así que ambas raíces coexisten y se consultan por separado.
        db.execSQL(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, " +
                "createdAt, updatedAt, revision) VALUES (41, '$ANGLE_ANALYSIS_V2_FORMAT', " +
                "'angle-shared-key', 77, 2000, 2000, 0)",
        )
        insertFrame(
            db,
            id = 51,
            rootId = 41,
            ordinal = 0,
            assignedFrameIndex = 42,
            reportedFrameIndex = null,
            assignedPtsUs = 1_400_000L,
            reportedPtsUs = null,
            imageKey = "image-key-frame-0",
        )
        insertFrame(
            db,
            id = 52,
            rootId = 41,
            ordinal = 1,
            assignedFrameIndex = 200,
            reportedFrameIndex = 199,
            assignedPtsUs = 6_633_333L,
            reportedPtsUs = 6_600_000L,
            imageKey = "image-key-frame-1",
        )
        db.execSQL(
            "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, " +
                "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                "VALUES (71, 51, 0, 'Rodilla', -65536, 1, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5)",
        )
        db.execSQL(
            "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, " +
                "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                "VALUES (72, 52, 0, 'Tobillo', -16711936, 0, 7.5, 8.5, 9.5, 10.5, 11.5, 12.5)",
        )

        db.query("SELECT COUNT(*) FROM angle_analyses").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_roots").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query(
            "SELECT r.analysisFormat, r.sessionKey, f.reportedFrameIndex, f.reportedPtsUs, " +
                "f.imageSha256, f.imageMimeType FROM angle_analysis_v2_roots r " +
                "JOIN angle_analysis_v2_frames f ON f.rootId = r.id WHERE f.id = 51",
        ).use {
            it.moveToFirst()
            assertEquals(ANGLE_ANALYSIS_V2_FORMAT, it.getString(0))
            assertEquals("angle-shared-key", it.getString(1))
            assertTrue("El PTS reportado no verificado debe quedar NULL", it.isNull(2))
            assertTrue(it.isNull(3))
            assertEquals("a".repeat(64), it.getString(4))
            assertEquals("image/png", it.getString(5))
        }
        db.query(
            "SELECT f.reportedFrameIndex, f.reportedPtsUs FROM angle_analysis_v2_frames f WHERE f.id = 52",
        ).use {
            it.moveToFirst()
            assertEquals(199, it.getInt(0))
            assertEquals(6_600_000L, it.getLong(1))
        }
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_marks").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }

        // (d) Unicidad por raíz/fotograma y de la clave de imagen.
        assertTrue(
            "sessionKey repetido en la raíz V2 debe rechazarse",
            constraintRejected(
                db,
                "INSERT INTO angle_analysis_v2_roots (analysisFormat, sessionKey, athleteId, " +
                    "createdAt, updatedAt, revision) VALUES ('$ANGLE_ANALYSIS_V2_FORMAT', " +
                    "'angle-shared-key', 77, 2000, 2000, 0)",
            ),
        )
        assertTrue(
            "ordinal de fotograma repetido en la misma raíz debe rechazarse",
            constraintRejected(db, frameInsertSql(id = 90, ordinal = 0, imageKey = "image-key-dup-ordinal")),
        )
        assertTrue(
            "imageKey repetida debe rechazarse",
            constraintRejected(db, frameInsertSql(id = 91, ordinal = 2, imageKey = "image-key-frame-0")),
        )
        assertTrue(
            "ordinal de marca repetido en el mismo fotograma debe rechazarse",
            constraintRejected(
                db,
                "INSERT INTO angle_analysis_v2_marks (frameId, ordinal, name, colorArgb, visible, " +
                    "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                    "VALUES (51, 0, 'Duplicada', -1, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)",
            ),
        )

        // (e) Las FK no permiten huérfanos ni borrar un atleta referenciado (NO_ACTION).
        // La conexión de test se alineó con producción tras migrar; se vuelve a comprobar aquí,
        // justo antes de las aserciones referenciales.
        db.query("PRAGMA foreign_keys").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertTrue(
            "un fotograma sin raíz debe rechazarse",
            constraintRejected(db, frameInsertSql(id = 92, rootId = 999, ordinal = 3, imageKey = "image-key-orphan")),
        )
        assertTrue(
            "una marca sin fotograma debe rechazarse",
            constraintRejected(
                db,
                "INSERT INTO angle_analysis_v2_marks (frameId, ordinal, name, colorArgb, visible, " +
                    "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) " +
                    "VALUES (999, 0, 'Huérfana', -1, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)",
            ),
        )
        db.query("PRAGMA foreign_keys").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertTrue(
            "borrar un atleta con raíz V2 debe fallar en cerrado",
            constraintRejected(db, "DELETE FROM athletes WHERE id = 77"),
        )

        // (f) Cascada raíz → fotogramas → marcas, sin tocar el agregado legacy.
        db.execSQL("DELETE FROM angle_analysis_v2_roots WHERE id = 41")
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_roots").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_frames").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_marks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM angle_analyses WHERE id = 41").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM angle_marks WHERE analysisId = 41").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }

        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.close()
    }

    // ---------------------------------------------------------------------------------------
    // 12 → 13 → 14
    // ---------------------------------------------------------------------------------------

    @Test
    fun migrate12To14_keepsJumpEncoderAndFvpAndAddsBothAngleAggregatesEmpty() {
        val name = newTestDatabaseName("angle-v2-migration-from-12")
        helper.createDatabase(name, 12).apply {
            execSQL("INSERT INTO athletes (id, displayName, createdAt, updatedAt) VALUES (21, 'V12', 1, 1)")
            execSQL(
                "INSERT INTO assessments (id, sessionKey, athleteId, protocolId, dateTime, " +
                    "primaryMetricKey, primaryMetricValue, primaryMetricUnit, notes) " +
                    "VALUES (22, 'jump-v12', 21, 'CMJ', 7, 'HEIGHT_CM', 28.5, 'CENTIMETER', 'nota v12')",
            )
            execSQL(
                "INSERT INTO attempts (id, assessmentId, ordinal, side, source, detectedFps) " +
                    "VALUES (23, 22, 0, 'LEFT', 'UNKNOWN', 240)",
            )
            execSQL("INSERT INTO attempt_events (attemptId, type, ordinal, ptsUs) VALUES (23, 'TAKEOFF', 0, 555)")
            execSQL(
                "INSERT INTO attempt_metrics (attemptId, `key`, ordinal, value, unit) " +
                    "VALUES (23, 'HEIGHT_CM', 0, 28.5, 'CENTIMETER')",
            )
            execSQL(INSERT_ENCODER_SESSION_12)
            execSQL(
                "INSERT INTO encoder_samples (sessionId, ordinal, frameIndex, sourcePtsUs, " +
                    "physicalTimeUs, rawPixelX, rawPixelY, rawMetricX, rawMetricY, smoothedMetricX, " +
                    "smoothedMetricY, velocityXMps, velocityYMps, confidence, trackingStatus, " +
                    "observationKind, fitResidualM, gapBeforeUs) VALUES (62, 0, 1, 100, 0, 1, 2, " +
                    "0, 0, 0, 0, 0, 0, 0.8, 'TRACKING', 'MEASURED', 0.01, NULL)",
            )
            execSQL(
                "INSERT INTO fvp_assessments (id, sessionKey, athleteId, protocolId, dateTime, " +
                    "bodyMassKg, bodyMassSource, pushOffMode, pushOffM, pushOffSource, " +
                    "algorithmVersion, methodVersion, f0N, slopeFv, sfvNsPerM, conditionCount, " +
                    "vMinMps, vMaxMps, fMinN, fMaxN, warningsCsv) VALUES (24, 'fvp-v12', 21, 'CMJ', " +
                    "8, 74.0, 'PROFILE', 'COMMON', 0.41, 'MEASURED', 'fvp-poc-1.0', 'fvp-app-1.0', " +
                    "1700.0, -300.0, 300.0, 2, 1.2, 3.0, 1400.0, 2000.0, '')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            name,
            14,
            true,
            JumpDatabase.MIGRATION_12_13,
            JumpDatabase.MIGRATION_13_14,
        )
        enableForeignKeysLikeProduction(db)

        // (a) Los datos v12 de saltos/Encoder/F-V-P siguen intactos y con sus IDs.
        db.query("SELECT sessionKey, athleteId, primaryMetricValue, notes FROM assessments WHERE id = 22").use {
            it.moveToFirst()
            assertEquals("jump-v12", it.getString(0))
            assertEquals(21L, it.getLong(1))
            assertEquals(28.5, it.getDouble(2), 0.0001)
            assertEquals("nota v12", it.getString(3))
        }
        db.query("SELECT side, detectedFps FROM attempts WHERE id = 23").use {
            it.moveToFirst()
            assertEquals("LEFT", it.getString(0))
            assertEquals(240, it.getInt(1))
        }
        db.query("SELECT ptsUs FROM attempt_events WHERE attemptId = 23").use { it.moveToFirst(); assertEquals(555L, it.getLong(0)) }
        db.query("SELECT value FROM attempt_metrics WHERE attemptId = 23").use { it.moveToFirst(); assertEquals(28.5, it.getDouble(0), 0.0001) }
        db.query("SELECT sessionKey, exercise FROM encoder_sessions WHERE id = 62").use {
            it.moveToFirst()
            assertEquals("encoder-v12", it.getString(0))
            assertEquals("BENCH_PRESS", it.getString(1))
        }
        db.query("SELECT frameIndex, confidence FROM encoder_samples WHERE sessionId = 62").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
            assertEquals(0.8, it.getDouble(1), 0.0001)
        }
        db.query("SELECT sessionKey, conditionCount FROM fvp_assessments WHERE id = 24").use {
            it.moveToFirst()
            assertEquals("fvp-v12", it.getString(0))
            assertEquals(2, it.getInt(1))
        }

        // (b) Ambos agregados de ángulos existen vacíos tras la cadena; versión 14.
        (listOf("angle_analyses", "angle_marks") + V2_TABLES).forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use {
                assertEquals("Missing $table", 1, it.count)
            }
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        db.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(14, it.getInt(0)) }

        // (c) El agregado nuevo acepta una raíz con dueño válido y dos fotogramas ordenados.
        db.execSQL(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, " +
                "createdAt, updatedAt, revision) VALUES (81, '$ANGLE_ANALYSIS_V2_FORMAT', " +
                "'angle-v2-from-12', 21, 3000, 3000, 0)",
        )
        insertFrame(db, id = 82, rootId = 81, ordinal = 0, assignedFrameIndex = 0, reportedFrameIndex = 0, assignedPtsUs = 0L, reportedPtsUs = 0L, imageKey = "image-key-82")
        insertFrame(db, id = 83, rootId = 81, ordinal = 1, assignedFrameIndex = 10, reportedFrameIndex = 10, assignedPtsUs = 333_333L, reportedPtsUs = 333_000L, imageKey = "image-key-83")
        db.query(
            "SELECT id, ordinal, reportedFrameIndex FROM angle_analysis_v2_frames WHERE rootId = 81 ORDER BY ordinal",
        ).use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals(82L, it.getLong(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
            it.moveToNext()
            assertEquals(83L, it.getLong(0))
            assertEquals(1, it.getInt(1))
            assertEquals(10, it.getInt(2))
        }
        // Colisión deliberada con el id de la fila legacy de `assessments`/`attempts`.
        db.execSQL(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, " +
                "createdAt, updatedAt, revision) VALUES (22, '$ANGLE_ANALYSIS_V2_FORMAT', " +
                "'angle-v2-collision', 21, 3001, 3001, 0)",
        )
        db.query("SELECT COUNT(*) FROM angle_analysis_v2_roots WHERE id = 22").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM assessments WHERE id = 22").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }

        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.close()
    }

    @Test
    fun migrate13To14_leavesLegacyAngleTableWithImageColumnsAbsentAndSchemaStill13Compatible() {
        // El agregado legacy no recibe columnas nuevas: la forma v13 sigue siendo válida byte a byte
        // para lectores/backup actuales. Se comprueba el conjunto exacto de columnas de cada tabla.
        val name = newTestDatabaseName("angle-v2-migration-shape")
        helper.createDatabase(name, 13).close()
        val db = helper.runMigrationsAndValidate(name, 14, true, JumpDatabase.MIGRATION_13_14)

        db.query("PRAGMA table_info(angle_analyses)").use {
            val columns = mutableListOf<String>()
            while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
            assertEquals(LEGACY_ANALYSIS_COLUMNS, columns)
        }
        db.query("PRAGMA table_info(angle_marks)").use {
            val columns = mutableListOf<String>()
            while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
            assertEquals(LEGACY_MARK_COLUMNS, columns)
        }
        // El índice único legacy por (analysisId, ordinal) y el de sessionKey siguen presentes.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name IN " +
                "('index_angle_analyses_sessionKey', 'index_angle_marks_analysisId_ordinal')",
        ).use { assertEquals(2, it.count) }
        // Y las tablas nuevas están vacías incluso sin filas legacy.
        V2_TABLES.forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        db.query("SELECT COUNT(*) FROM angle_analyses").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    private fun insertFrame(
        db: SupportSQLiteDatabase,
        id: Long,
        rootId: Long = 41,
        ordinal: Int,
        assignedFrameIndex: Int,
        reportedFrameIndex: Int?,
        assignedPtsUs: Long,
        reportedPtsUs: Long?,
        imageKey: String,
    ) {
        db.execSQL(
            """
            INSERT INTO angle_analysis_v2_frames (
                id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex, assignedPtsUs,
                reportedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight,
                rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount,
                imageSha256, imageMimeType
            ) VALUES (
                $id, $rootId, $ordinal, $assignedFrameIndex,
                ${reportedFrameIndex ?: "NULL"}, $assignedPtsUs,
                ${reportedPtsUs ?: "NULL"}, 1920, 1080, 1080, 1920, 90, 1,
                '$imageKey', 1080, 1920, 2048, '${"a".repeat(64)}', 'image/png'
            )
            """.trimIndent(),
        )
    }

    private fun frameInsertSql(id: Long, rootId: Long = 41, ordinal: Int, imageKey: String): String =
        "INSERT INTO angle_analysis_v2_frames (id, rootId, ordinal, assignedFrameIndex, " +
            "assignedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
            "rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount, " +
            "imageSha256, imageMimeType) VALUES ($id, $rootId, $ordinal, 1, 0, 1920, 1080, 1080, " +
            "1920, 90, 1, '$imageKey', 1080, 1920, 2048, '${"b".repeat(64)}', 'image/png')"

    private fun constraintRejected(db: SupportSQLiteDatabase, sql: String): Boolean =
        try {
            db.execSQL(sql)
            false
        } catch (_: SQLiteConstraintException) {
            true
        }

    private companion object {
        val V2_TABLES = listOf(
            "angle_analysis_v2_roots",
            "angle_analysis_v2_frames",
            "angle_analysis_v2_marks",
        )

        val LEGACY_ANALYSIS_COLUMNS = listOf(
            "id", "sessionKey", "athleteId", "createdAt", "updatedAt", "revision", "videoUri",
            "sourceIdentity", "sourceHash", "encodedWidth", "encodedHeight", "presentationWidth",
            "presentationHeight", "rotationDegrees", "frameIndex", "sourcePtsUs", "geometryVersion",
        )

        val LEGACY_MARK_COLUMNS = listOf(
            "id", "analysisId", "ordinal", "name", "colorArgb", "visible",
            "pointAX", "pointAY", "pointBX", "pointBY", "pointCX", "pointCY",
        )

        const val INSERT_ENCODER_SESSION_13 =
            "INSERT INTO encoder_sessions (id, sessionKey, exercise, loadKg, dateTime, source, " +
                "timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, " +
                "calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, " +
                "minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, " +
                "stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, " +
                "minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, " +
                "maximumUncertainFraction, smoothingWindowSamples, totalRepetitions, " +
                "validRepetitions) VALUES (61, 'encoder-v13', 'BENCH_PRESS', 0.0, 1, 'UNKNOWN', " +
                "'UNKNOWN', 1.0, 0, 0, 0, 1, 1, 1, 1, 1, 0.04, 4, 0.5, 50000, 300000, 150000, " +
                "150000, 0.02, 0.2, 0.5, 0.15, 5, 0, 0)"

        const val INSERT_ENCODER_SESSION_12 =
            "INSERT INTO encoder_sessions (id, sessionKey, exercise, loadKg, dateTime, source, " +
                "timingMode, slowMotionFactor, calibrationFrameIndex, calibrationAX, calibrationAY, " +
                "calibrationBX, calibrationBY, referenceLengthM, metersPerPixel, analysisVersion, " +
                "minimumVelocityMps, noiseMultiplier, exitRatio, minimumDirectionHoldUs, " +
                "stationaryDurationUs, pauseDurationUs, minimumPhaseDurationUs, " +
                "minimumPhaseDisplacementM, romOutlierFraction, minimumMedianConfidence, " +
                "maximumUncertainFraction, smoothingWindowSamples, totalRepetitions, " +
                "validRepetitions) VALUES (62, 'encoder-v12', 'BENCH_PRESS', 0.0, 1, 'UNKNOWN', " +
                "'UNKNOWN', 1.0, 0, 0, 0, 1, 1, 1, 1, 1, 0.04, 4, 0.5, 50000, 300000, 150000, " +
                "150000, 0.02, 0.2, 0.5, 0.15, 5, 0, 0)"
    }
}
