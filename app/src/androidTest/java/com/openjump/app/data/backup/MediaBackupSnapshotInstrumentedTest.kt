package com.openjump.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.JumpDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificacion instrumentada de G3a: `ManualBackupDao.snapshotWithAngleMedia()` captura, en **una
 * sola** transaccion de Room, el mismo payload legacy que `snapshot()` mas las filas del agregado
 * multi-fotograma V2 proyectadas al [MediaBackupManifest] (una entrada de imagen por fotograma,
 * solo metadatos).
 *
 * Aislamiento: base **en memoria** (`Room.inMemoryDatabaseBuilder`), sin base de datos de usuario,
 * sin ficheros, sin imagenes reales, sin UI ni SAF. El agregado V2 se siembra por SQL directo porque
 * [com.openjump.app.data.AngleAnalysisV2Dao] es de solo lectura y este arnes no debe abrir una via
 * de escritura de producto.
 *
 * Lo que se comprueba: orden e identidades; `athleteId` `null` respetado; asignado frente a
 * reportado; visibilidad de marcas; una imagen por fotograma con su SHA-256/tamano; que el camino
 * v1 sigue bloqueado (y que la captura de metadatos no muta ni una fila); que un agregado huerfano
 * o corrupto falla cerrado; y que la captura no puede completarse mientras hay una transaccion de
 * escritura abierta (interleaving determinista).
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupSnapshotInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()

    @After fun close() = database.close()

    private val dao get() = database.manualBackupDao()

    @Test
    fun mediaSnapshotCapturesLegacyOwnerAndEveryV2FrameMarkAndImageMetadata() = runBlocking {
        val legacy = legacyDocument()
        dao.replaceAll(legacy)
        // Raiz 11 con DOS fotogramas (uno con reportado verificado y otro sin verificar) y raiz 12
        // con un fotograma; todas con dueno = atleta legacy 1.
        insertRoot(id = 11L, sessionKey = "media-session", athleteId = 1L, revision = 2)
        insertFrame(id = 101L, rootId = 11L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 10, reportedFrameIndex = 12, byteCount = 4_096L, sha256 = SHA_A)
        insertFrame(id = 102L, rootId = 11L, ordinal = 1, imageKey = KEY_B, assignedFrameIndex = 20, reportedFrameIndex = null, byteCount = 8_192L, sha256 = SHA_B)
        insertRoot(id = 12L, sessionKey = "media-session-2", athleteId = 1L, revision = 0)
        insertFrame(id = 103L, rootId = 12L, ordinal = 0, imageKey = KEY_C, assignedFrameIndex = 30, reportedFrameIndex = 31, byteCount = 16_384L, sha256 = SHA_C)
        insertMark(id = 201L, frameId = 101L, ordinal = 0, name = "rodilla", visible = true)
        insertMark(id = 202L, frameId = 101L, ordinal = 1, name = "cadera", visible = false)
        insertMark(id = 203L, frameId = 102L, ordinal = 0, name = "tobillo", visible = true)
        insertMark(id = 204L, frameId = 103L, ordinal = 0, name = "hombro", visible = true)
        val legacyBefore = legacyRowCounts()
        val v2Before = angleV2RowCounts()

        val manifest = dao.snapshotWithAngleMedia()

        // Envoltorio y documento legacy: es el MISMO payload que capturaria `snapshot()`.
        assertEquals(MEDIA_BACKUP_CONTRACT, manifest.contract)
        assertEquals(MEDIA_BACKUP_FORMAT_VERSION, manifest.formatVersion)
        assertEquals(MEDIA_BACKUP_ROOM_VERSION, manifest.sourceRoomSchemaVersion)
        assertEquals(manifest.legacy.createdAtEpochMillis, manifest.createdAtEpochMillis)
        assertTrue(manifest.createdAtEpochMillis >= 0L)
        assertFalse(manifest.legacy.mediaIncluded)
        assertFalse(manifest.legacy.preferencesIncluded)
        // Orden explicito de `attemptEvents`: la consulta legacy ordena por `attemptId, type,
        // ordinal` (colacion TEXT), NO en orden temporal. Se afirma el orden y el contenido con
        // valores literales (LANDING ptsUs=3, MOVEMENT_START ptsUs=1, TAKEOFF ptsUs=2) y despues se
        // compara la tabla completa contra ese mismo orden SQL; no se relaja ni un dato ni una
        // asercion, solo se corrige el valor esperado que asumia el orden del documento.
        assertEquals(
            listOf("LANDING" to 3L, "MOVEMENT_START" to 1L, "TAKEOFF" to 2L),
            manifest.legacy.tables.attemptEvents.map { it.type to it.ptsUs },
        )
        assertEquals(listOf(3L, 3L, 3L), manifest.legacy.tables.attemptEvents.map { it.attemptId })
        assertEquals(
            "el snapshot no puede perder ni duplicar eventos: mismo conjunto que el fixture",
            legacy.tables.attemptEvents.toSet(),
            manifest.legacy.tables.attemptEvents.toSet(),
        )
        assertEquals(expectedLegacyTables(), manifest.legacy.tables)

        // Orden e identidades estables, dueno conservado.
        assertEquals(listOf(11L, 12L), manifest.angleV2.roots.map { it.id })
        assertEquals(listOf("media-session", "media-session-2"), manifest.angleV2.roots.map { it.sessionKey })
        assertEquals(listOf(1L, 1L), manifest.angleV2.roots.map { it.athleteId })
        assertEquals(listOf(2, 0), manifest.angleV2.roots.map { it.revision })
        assertEquals(listOf(101L, 102L, 103L), manifest.angleV2.frames.map { it.id })
        assertEquals(listOf(11L, 11L, 12L), manifest.angleV2.frames.map { it.rootId })
        assertEquals(listOf(0, 1, 0), manifest.angleV2.frames.map { it.ordinal })

        // Asignado frente a reportado: lo no verificado se queda `null`, nunca se copia.
        val verified = manifest.angleV2.frames[0]
        assertEquals(10, verified.assignedFrameIndex)
        assertEquals(1_000_000L, verified.assignedPtsUs)
        assertEquals(12, verified.reportedFrameIndex)
        assertEquals(1_200_000L, verified.reportedPtsUs)
        val unverified = manifest.angleV2.frames[1]
        assertEquals(20, unverified.assignedFrameIndex)
        assertEquals(2_000_000L, unverified.assignedPtsUs)
        assertNull(unverified.reportedFrameIndex)
        assertNull(unverified.reportedPtsUs)

        // Marcas en orden estable, con la visibilidad elegida por el usuario intacta.
        assertEquals(listOf(201L, 202L, 203L, 204L), manifest.angleV2.marks.map { it.id })
        assertEquals(listOf(101L, 101L, 102L, 103L), manifest.angleV2.marks.map { it.frameId })
        assertEquals(listOf(0, 1, 0, 0), manifest.angleV2.marks.map { it.ordinal })
        assertEquals(listOf(true, false, true, true), manifest.angleV2.marks.map { it.visible })
        assertEquals(listOf("rodilla", "cadera", "tobillo", "hombro"), manifest.angleV2.marks.map { it.name })

        // Una imagen por fotograma, en el orden canonico de clave que fija la produccion
        // (`sortedBy imageKey`, el mismo que usa `archivePlan()`), no en orden de fotograma:
        // KEY_A=0011... < KEY_C=0123... < KEY_B=ffee.... Se afirma el orden y el contenido con
        // valores literales y, ademas, la correspondencia clave -> tamano/SHA se comprueba de forma
        // independiente del orden.
        assertEquals(manifest.angleV2.frames.size, manifest.angleV2.images.size)
        assertEquals(listOf(KEY_A, KEY_C, KEY_B), manifest.angleV2.images.map { it.imageKey })
        assertEquals(listOf(4_096L, 16_384L, 8_192L), manifest.angleV2.images.map { it.byteCount })
        assertEquals(listOf(SHA_A, SHA_C, SHA_B), manifest.angleV2.images.map { it.sha256 })
        assertEquals(
            "cada imagen declarada corresponde por clave a su fotograma (independiente del orden)",
            manifest.angleV2.frames.associate { it.imageKey to (it.imageByteCount to it.imageSha256) },
            manifest.angleV2.images.associate { it.imageKey to (it.byteCount to it.sha256) },
        )
        assertEquals(manifest.angleV2.images.map { it.imageKey }.toSet(), manifest.angleV2.frames.map { it.imageKey }.toSet())
        assertEquals(
            listOf("images/$KEY_A.png", "images/$KEY_C.png", "images/$KEY_B.png"),
            manifest.archivePlan().imageEntries.map { it.entryName },
        )
        assertEquals(
            "el plan de archivo conserva el orden canonico del manifiesto",
            manifest.angleV2.images.map { it.imageKey },
            manifest.archivePlan().imageEntries.map { it.imageKey },
        )

        // La captura no muta ni una fila (ni legacy ni V2) y no deja violaciones de FK.
        assertEquals(legacyBefore, legacyRowCounts())
        assertEquals(v2Before, angleV2RowCounts())
        assertTrue(foreignKeysClean())
    }

    @Test
    fun rootWithoutOwnerKeepsNullAthleteInsteadOfInventingOne() = runBlocking {
        val legacy = legacyDocument()
        dao.replaceAll(legacy)
        insertRoot(id = 13L, sessionKey = "no-owner", athleteId = null)
        insertFrame(id = 131L, rootId = 13L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null)
        insertMark(id = 231L, frameId = 131L, ordinal = 0, name = "rodilla", visible = true)

        val manifest = dao.snapshotWithAngleMedia()

        val root = manifest.angleV2.roots.single()
        assertEquals(13L, root.id)
        assertNull("`null` es un estado real y debe conservarse", root.athleteId)
        assertEquals(1L, manifest.legacy.tables.athletes.single().id)
        assertEquals(1, manifest.angleV2.images.size)
        assertEquals(KEY_A, manifest.angleV2.images.single().imageKey)
        assertEquals(1, manifest.archivePlan().imageCount)
    }

    /**
     * El camino v1 sigue cerrado con V2 presente (snapshot y restore), la captura de metadatos no
     * toca la base, y con V2 vacio tanto el camino legacy como el manifiesto sin medios funcionan.
     */
    @Test
    fun legacyPathStaysBlockedAndMetadataCaptureNeverMutatesTheDatabase() = runBlocking {
        val legacy = legacyDocument()
        dao.replaceAll(legacy)
        insertRoot(id = 14L, sessionKey = "gate", athleteId = 1L)
        insertFrame(id = 141L, rootId = 14L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null)
        insertMark(id = 241L, frameId = 141L, ordinal = 0, name = "rodilla", visible = true)
        val legacyBefore = legacyRowCounts()
        val v2Before = angleV2RowCounts()

        val blockedSnapshot = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.snapshot() }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 1, frames = 1, marks = 1), blockedSnapshot.rows)
        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.replaceAll(legacy) }
        }

        val first = dao.snapshotWithAngleMedia()
        val second = dao.snapshotWithAngleMedia()
        assertEquals(first.angleV2, second.angleV2)
        assertEquals(first.legacy.tables, second.legacy.tables)
        assertEquals(legacyBefore, legacyRowCounts())
        assertEquals(v2Before, angleV2RowCounts())
        assertTrue(foreignKeysClean())

        // Agregado vacio: el camino v1 se reabre y el manifiesto sigue siendo valido sin medios.
        database.openHelper.writableDatabase.execSQL("DELETE FROM angle_analysis_v2_roots")
        assertEquals(Triple(0, 0, 0), angleV2RowCounts())
        assertEquals(expectedLegacyTables(), dao.snapshot().tables)
        val withoutMedia = dao.snapshotWithAngleMedia()
        assertTrue(withoutMedia.angleV2.roots.isEmpty())
        assertTrue(withoutMedia.angleV2.frames.isEmpty())
        assertTrue(withoutMedia.angleV2.marks.isEmpty())
        assertTrue(withoutMedia.angleV2.images.isEmpty())
        assertEquals(0, withoutMedia.archivePlan().imageCount)
        assertEquals(expectedLegacyTables(), withoutMedia.legacy.tables)
        assertTrue(foreignKeysClean())
    }

    /**
     * Sin reparaciones silenciosas: raiz sin fotogramas, fotograma sin marcas, SHA no canonico o
     * dueno inexistente hacen fallar la captura completa. El rechazo no muta nada y el gate v1 sigue
     * cerrado.
     */
    @Test
    fun orphanOrCorruptAngleV2FailsClosedWithoutReturningAPartialManifest() = runBlocking {
        val legacy = legacyDocument()
        dao.replaceAll(legacy)

        // (a) Raiz sin fotogramas.
        insertRoot(id = 21L, sessionKey = "no-frames", athleteId = 1L)
        assertFailsClosedAndKeepsRows()
        clearAngleV2()

        // (b) Fotograma sin marcas (sin angulo declarado).
        insertRoot(id = 22L, sessionKey = "no-marks", athleteId = 1L)
        insertFrame(id = 221L, rootId = 22L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null)
        assertFailsClosedAndKeepsRows()
        clearAngleV2()

        // (c) Metadatos de imagen invalidos: SHA que no es 64 hex en minuscula y tamano 0.
        insertRoot(id = 23L, sessionKey = "bad-image", athleteId = 1L)
        insertFrame(id = 231L, rootId = 23L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null, byteCount = 0L, sha256 = "sha256:not-canonical")
        insertMark(id = 2311L, frameId = 231L, ordinal = 0, name = "rodilla", visible = true)
        assertFailsClosedAndKeepsRows()
        clearAngleV2()

        // (d) Clave de imagen no canonica: no hay forma de derivar un nombre de entrada y no se
        // sanea (dos claves distintas colisionarian).
        insertRoot(id = 24L, sessionKey = "bad-key", athleteId = 1L)
        insertFrame(id = 241L, rootId = 24L, ordinal = 0, imageKey = "image-key-1", assignedFrameIndex = 0, reportedFrameIndex = null)
        insertMark(id = 242L, frameId = 241L, ordinal = 0, name = "rodilla", visible = true)
        assertFailsClosedAndKeepsRows()
        clearAngleV2()

        // (e) Marca fuera del fotograma de presentacion: la geometria no se recorta ni se corrige.
        insertRoot(id = 25L, sessionKey = "out-of-frame", athleteId = 1L)
        insertFrame(id = 251L, rootId = 25L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null)
        insertMark(id = 252L, frameId = 251L, ordinal = 0, name = "rodilla", visible = true, cx = 5_000.0, cy = 5_000.0)
        assertFailsClosedAndKeepsRows()
        clearAngleV2()

        assertEquals(expectedLegacyTables(), dao.snapshot().tables)
        assertTrue(foreignKeysClean())
    }

    /**
     * Interleaving determinista: mientras una transaccion de escritura mantiene abierto un agregado
     * V2 completo sin confirmar, la captura no puede completarse (no existe una foto parcial); al
     * confirmar, ve el agregado entero. Con `Room.inMemoryDatabaseBuilder` hay una unica conexion,
     * asi que la serializacion es la unica salida posible.
     */
    @Test
    fun mediaSnapshotCannotCompleteWhileAWriterTransactionIsOpen() = runBlocking {
        dao.replaceAll(legacyDocument())
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            database.withTransaction {
                insertRoot(id = 15L, sessionKey = "in-flight", athleteId = null)
                insertFrame(id = 151L, rootId = 15L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 0, reportedFrameIndex = null)
                insertMark(id = 251L, frameId = 151L, ordinal = 0, name = "rodilla", visible = true)
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        writerEntered.await()

        val snapshot = async(Dispatchers.IO) { dao.snapshotWithAngleMedia() }
        val early = withTimeoutOrNull(750) { snapshot.await() }
        assertNull(
            "la captura no puede completarse mientras la transaccion de escritura siga abierta (vio ${early?.angleV2})",
            early,
        )

        releaseWriter.complete(Unit)
        writer.join()
        val manifest = withTimeout(10_000) { snapshot.await() }

        // La foto es del estado confirmado completo: raiz, fotograma, marca e imagen a la vez.
        assertEquals(listOf(15L), manifest.angleV2.roots.map { it.id })
        assertEquals(listOf(151L), manifest.angleV2.frames.map { it.id })
        assertEquals(listOf(251L), manifest.angleV2.marks.map { it.id })
        assertEquals(listOf(KEY_A), manifest.angleV2.images.map { it.imageKey })
        assertEquals(1, manifest.archivePlan().imageCount)
    }

    // --- Aserciones y fixtures -------------------------------------------------------------------

    /**
     * El manifiesto no se devuelve parcial: la captura lanza `IllegalArgumentException` (validacion),
     * el camino v1 sigue bloqueado, no cambia ninguna fila y no aparecen violaciones de FK.
     */
    private suspend fun assertFailsClosedAndKeepsRows() {
        val legacyBefore = legacyRowCounts()
        val v2Before = angleV2RowCounts()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.snapshotWithAngleMedia() }
        }
        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.snapshot() }
        }
        assertEquals(legacyBefore, legacyRowCounts())
        assertEquals(v2Before, angleV2RowCounts())
        assertTrue(foreignKeysClean())
    }

    private fun clearAngleV2() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM angle_analysis_v2_roots")
    }

    private fun legacyDocument(): ManualBackupDocument = ManualBackupCodec.decode(LEGACY_JSON)

    /**
     * Tablas legacy esperadas tras `decode -> replaceAll -> snapshot`.
     *
     * `ManualBackupDao.attemptEvents()` fija el contrato de orden en SQL (`ORDER BY attemptId, type,
     * ordinal`, colacion TEXT) y el fixture escribe los eventos en orden temporal
     * (MOVEMENT_START, TAKEOFF, LANDING). La foto devuelve el orden SQL, asi que se compara contra
     * ese orden explicito en vez de contra el orden del documento. No se cambia ni se descarta
     * ningun dato: la unica diferencia es la posicion de los eventos, y el orden y los contenidos se
     * afirman ademas con valores literales en la prueba.
     */
    private fun expectedLegacyTables(): ManualBackupTables {
        val tables = legacyDocument().tables
        return tables.copy(attemptEvents = tables.attemptEvents.sortedWith(ATTEMPT_EVENT_SNAPSHOT_ORDER))
    }

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

    private fun foreignKeysClean(): Boolean =
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }

    /**
     * Siembra el agregado V2 por SQL directo, reproduciendo exactamente las columnas de Room 14. No
     * se abre ninguna via de escritura de producto: `AngleAnalysisV2Dao` sigue siendo de solo lectura.
     */
    private fun insertRoot(id: Long, sessionKey: String, athleteId: Long?, revision: Int = 0) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, createdAt, updatedAt, revision) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, ANGLE_ANALYSIS_V2_FORMAT, sessionKey, athleteId, 1_000L, 2_000L, revision),
        )
    }

    private fun insertFrame(
        id: Long,
        rootId: Long,
        ordinal: Int,
        imageKey: String,
        assignedFrameIndex: Int,
        reportedFrameIndex: Int?,
        byteCount: Long = 4_096L,
        sha256: String = SHA_A,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_frames (id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex, " +
                "assignedPtsUs, reportedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
                "rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount, imageSha256, imageMimeType) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex,
                assignedFrameIndex * 100_000L, reportedFrameIndex?.times(100_000L),
                1920, 1080, 1920, 1080, 0, 1, imageKey, 640, 360,
                byteCount, sha256, MEDIA_BACKUP_IMAGE_MIME_TYPE,
            ),
        )
    }

    private fun insertMark(
        id: Long,
        frameId: Long,
        ordinal: Int,
        name: String,
        visible: Boolean,
        cx: Double = 1918.0,
        cy: Double = 1.0,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, " +
                "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, frameId, ordinal, name, 0xFF3366CC.toInt(), if (visible) 1 else 0, 1.0, 1.0, 960.0, 540.0, cx, cy),
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

        const val KEY_A = "00112233445566778899aabbccddeeff"
        const val KEY_B = "ffeeddccbbaa99887766554433221100"
        const val KEY_C = "0123456789abcdef0123456789abcdef"
        const val SHA_A = "00000000000000000000000000000000000000000000000000000000000000aa"
        const val SHA_B = "00000000000000000000000000000000000000000000000000000000000000bb"
        const val SHA_C = "00000000000000000000000000000000000000000000000000000000000000cc"

        /**
         * Orden de `attemptEvents` que garantiza la consulta legacy (`ORDER BY attemptId, type,
         * ordinal`). Es el mismo criterio que aplica SQLite con colacion TEXT: para estos valores
         * ASCII equivale al orden natural de `String` en Kotlin.
         */
        val ATTEMPT_EVENT_SNAPSHOT_ORDER = compareBy<BackupAttemptEvent>({ it.attemptId }, { it.type }, { it.ordinal })

        /** Documento legacy v1 valido (Room 10) con atleta 1, evaluacion CMJ e intento. */
        const val LEGACY_JSON =
            """{"contract":"openjump-manual-backup","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"instrumented athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"instrumented-assessment","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}
