package com.openjump.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.PrivateAngleImageStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificacion instrumentada de la **segunda confirmacion** del restore de un JSON legacy sobre una
 * base con analisis multi-fotograma V2, y de la **revalidacion de la huella completa** del agregado
 * antes de borrar nada.
 *
 * Que se prueba:
 *  - la advertencia (`legacyRestoreWarning` / `legacyAngleV2DiscardWarning`) es solo lectura y solo
 *    existe cuando hay filas V2 que el documento v1 no puede devolver;
 *  - con la segunda confirmacion (`AngleV2DiscardWarning`) el restore del JSON se aplica de verdad:
 *    sustituye el legacy y descarta el agregado V2 que el usuario vio;
 *  - **sin** esa segunda confirmacion no hay via: la barrera del DAO revierte y no se borra ni una
 *    fila;
 *  - si entre la advertencia y la aplicacion el agregado cambia **con los mismos conteos** (otro
 *    contenido: nombre de marca, revision de la raiz), se rechaza fail-closed en las dos capas
 *    (coordinador y revalidacion atomica dentro de la transaccion del DAO) y la base queda
 *    exactamente como estaba;
 *  - el repositorio de producto (`startConfirmedRestore` con el aviso que la UI acepto) informa
 *    reversion y no deja el estado cambiado.
 *
 * Aislamiento: base Room **en memoria**, sin base de usuario, sin dispositivo real de datos, sin
 * ficheros de imagen ni SAF. El agregado V2 se siembra por SQL directo (independiente del codigo bajo
 * prueba). El JSON legacy es el fixture v1 valido compartido con el resto de pruebas de backup. No se
 * ejecuta UI: el dialogo de la segunda confirmacion pertenece al ViewModel y no se prueba aqui.
 *
 * Las restauraciones confirmadas de producto corren en un ambito de aplicacion (no en el del
 * ViewModel); aqui se replica con un `CoroutineScope` propio y se espera su callback tipado.
 *
 * Esta clase **no** se ejecuta en este slice (sin AVD): se compila con
 * `compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class LegacyJsonDiscardRestoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runId: String = UUID.randomUUID().toString()
    private val sandbox: File = File(context.cacheDir, "$SANDBOX_PREFIX$runId")
    private val staging: File = File(sandbox, "staging").also { it.mkdirs() }
    private val storeRoot: File = File(sandbox, "images").also { it.mkdirs() }

    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
    private val dao get() = database.manualBackupDao()

    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val housekeeping = AtomicInteger(0)

    private val coordinator = MediaBackupCoordinator(
        dao = dao,
        imageStore = PrivateAngleImageStore(rootDirectory = storeRoot, fileIo = AndroidAngleImageFileIo()),
        stagingDirectory = staging,
        coordinationMutex = Mutex(),
    )

    private val repository = ManualBackupRepository(
        dao = dao,
        media = coordinator,
        confirmedRestoreScope = restoreScope,
        afterConfirmedRestore = { housekeeping.incrementAndGet() },
    )

    @Before
    fun createSandbox() {
        assertTrue("el sandbox debe existir", sandbox.isDirectory)
        assertTrue("el staging debe existir", staging.isDirectory)
        assertTrue("la raiz del almacen debe existir", storeRoot.isDirectory)
    }

    @After
    fun closeEverything() {
        restoreScope.cancel()
        database.close()
        // Solo se borra el arbol de este UUID, y solo si cuelga directamente de cacheDir.
        val cacheRoot = context.cacheDir.canonicalFile
        if (sandbox.exists() && sandbox.canonicalFile.parentFile == cacheRoot) {
            sandbox.deleteRecursively()
        }
    }

    // --- Segunda confirmacion ------------------------------------------------------------------

    /**
     * Camino feliz de producto: el usuario lee la carga legacy, ve la advertencia con datos y acepta
     * descartar el agregado V2. El JSON sustituye el legacy y las filas V2 advertidas desaparecen.
     */
    @Test
    fun secondConfirmationAppliesTheLegacyRestoreAndDiscardsTheWarnedAngleV2() = runBlocking {
        dao.replaceAll(previousLegacyDocument())
        seedDefaultAngleV2()
        val beforeWarning = dao.snapshotWithAngleMedia()

        val load = loadLegacyJson()
        val warning = requireNotNull(repository.legacyAngleV2DiscardWarning(load)) {
            "un JSON legacy sobre filas V2 debe producir la segunda confirmacion"
        }
        assertEquals(2, warning.rootCount)
        assertEquals(3, warning.frameCount)
        assertEquals(4, warning.markCount)

        // Leer la advertencia no toca la base: ni conteos ni contenido cambian.
        val afterWarning = dao.snapshotWithAngleMedia()
        assertEquals(beforeWarning.legacy.tables, afterWarning.legacy.tables)
        assertEquals(beforeWarning.angleV2, afterWarning.angleV2)

        val result = startConfirmedRestore(load, warning)
        val applied = requireNotNull(result as? ManualBackupRestoreRun.Applied) {
            "la restauracion confirmada debe aplicarse"
        }
        assertEquals(1, applied.summary.athleteCount)
        assertEquals(1, applied.summary.assessmentCount)
        assertFalse("la reconciliacion posterior no debe fallar", applied.housekeepingFailed)
        assertEquals(1, housekeeping.get())

        // El documento aplicado es el del JSON, no el que habia antes en la base.
        assertEquals(sqlOrdered(legacyDocument().tables), dao.snapshot().tables)
        assertEquals("el agregado V2 advertido se descarta entero", Triple(0, 0, 0), angleV2Counts())
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE id IN (11, 12, 13)"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_marks WHERE id IN (101, 102, 103, 104)"))
        assertTrue(foreignKeysClean())
    }

    /**
     * Sin la segunda confirmacion (`angleV2Discard == null`) la via sigue cerrada: el repositorio
     * intenta el restore legacy normal, la barrera del DAO lo rechaza dentro de su transaccion y se
     * informa reversion sin haber borrado ni una fila.
     */
    @Test
    fun withoutTheSecondConfirmationTheRestoreIsRevertedAndTheAngleV2RowsStayIntact() = runBlocking {
        dao.replaceAll(previousLegacyDocument())
        seedDefaultAngleV2()
        val before = dao.snapshotWithAngleMedia()

        val load = loadLegacyJson()
        // Hay agregado que perder: la UI habria mostrado la segunda advertencia.
        assertNotNull(repository.legacyAngleV2DiscardWarning(load))

        val result = startConfirmedRestore(load, angleV2Discard = null)

        assertEquals(ManualBackupRestoreRun.Reverted, result)
        assertEquals("no se aplico nada: no hubo reconciliacion", 0, housekeeping.get())
        val after = dao.snapshotWithAngleMedia()
        assertEquals("el legacy previo sigue intacto", before.legacy.tables, after.legacy.tables)
        assertEquals("el agregado V2 sigue intacto", before.angleV2, after.angleV2)
        assertEquals("previous athlete", dao.athletes().single().displayName)
        assertEquals(30, dao.attempts().single().detectedFps)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
    }

    /**
     * Sin filas V2 no hay nada que advertir: la advertencia es `null` y el restore legacy sigue
     * funcionando con una sola confirmacion (la barrera v1 esta abierta).
     */
    @Test
    fun aLegacyJsonWithoutAngleV2RowsNeedsNoSecondConfirmationAndProducesNoWarning() = runBlocking {
        dao.replaceAll(previousLegacyDocument())

        val load = loadLegacyJson()
        assertNull(repository.legacyAngleV2DiscardWarning(load))

        val result = startConfirmedRestore(load, angleV2Discard = null)

        assertNotNull("sin V2 el restore legacy debe aplicarse", result as? ManualBackupRestoreRun.Applied)
        assertEquals(1, housekeeping.get())
        assertEquals(sqlOrdered(legacyDocument().tables), dao.snapshot().tables)
        assertEquals(Triple(0, 0, 0), angleV2Counts())
    }

    // --- Revalidacion de la huella con los mismos conteos ---------------------------------------

    /**
     * El caso que motiva la huella completa: entre la advertencia y la aplicacion, otra escritura
     * cambia el **contenido** del agregado sin cambiar ningun conteo. Una confirmacion basada solo en
     * "2 raices, 3 fotogramas, 4 marcas" destruiria un conjunto que el usuario nunca vio; aqui el
     * coordinador lo rechaza antes de abrir la transaccion y no se modifica nada.
     */
    @Test
    fun aConfirmationChangedWithTheSameCountsIsRejectedByTheCoordinatorAndDeletesNothing() = runBlocking {
        dao.replaceAll(previousLegacyDocument())
        seedDefaultAngleV2()
        val load = loadLegacyJson()
        val warning = requireNotNull(repository.legacyAngleV2DiscardWarning(load))
        val beforeChange = dao.snapshotWithAngleMedia()

        changeAngleV2ContentKeepingCounts()

        val afterChange = dao.snapshotWithAngleMedia()
        assertEquals("los conteos siguen siendo los mismos", Triple(2, 3, 4), angleV2Counts())
        assertNotEquals(
            "la confirmacion identifica contenido, no solo conteos",
            beforeChange.angleV2,
            afterChange.angleV2,
        )
        assertEquals("el cambio no toca el legacy", beforeChange.legacy.tables, afterChange.legacy.tables)

        val outcome = coordinator.replaceLegacyDiscardingAngleV2(load, warning)
        val rejected = requireNotNull(outcome as? MediaBackupRestoreOutcome.NotReplaced) {
            "una advertencia obsoleta no puede sustituir filas"
        }
        assertEquals(MediaBackupRestoreFailureReason.ANGLE_V2_CHANGED, rejected.reason)
        assertEquals(emptyList<String>(), rejected.preservedImageKeys)
        assertEquals(emptyList<String>(), rejected.unsyncedImageKeys)

        val afterRejection = dao.snapshotWithAngleMedia()
        assertEquals("no se sustituyo el legacy", afterChange.legacy.tables, afterRejection.legacy.tables)
        assertEquals("el agregado V2 sigue como estaba", afterChange.angleV2, afterRejection.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertTrue(foreignKeysClean())
    }

    /**
     * La misma garantia en la capa mas interna: `replaceAllDiscardingAngleV2` recalcula la huella
     * **dentro** de su propia transaccion y antes del primer `DELETE`. Una instantanea confirmada que
     * ya no coincide (mismos conteos, otro contenido) lanza
     * [ManualBackupAngleV2ChangedException] y no borra nada.
     */
    @Test
    fun theDaoRejectsTheAtomicFingerprintRevalidationWithTheSameCountsWithoutDeletingAnything() = runBlocking {
        dao.replaceAll(previousLegacyDocument())
        seedDefaultAngleV2()
        val confirmed = dao.angleV2AggregateSnapshot()
        assertEquals(AngleAnalysisV2RowCounts(roots = 2, frames = 3, marks = 4), confirmed.counts)
        val beforeChange = dao.snapshotWithAngleMedia()

        changeAngleV2ContentKeepingCounts()

        val observedAfterChange = dao.angleV2AggregateSnapshot()
        assertEquals("mismos conteos", confirmed.counts, observedAfterChange.counts)
        assertNotEquals("contenido distinto", confirmed, observedAfterChange)
        val afterChange = dao.snapshotWithAngleMedia()

        val failure = assertThrows(ManualBackupAngleV2ChangedException::class.java) {
            runBlocking { dao.replaceAllDiscardingAngleV2(legacyDocument(), confirmed) }
        }
        assertEquals(confirmed, failure.confirmed)
        assertEquals(observedAfterChange, failure.observed)

        val afterRejection = dao.snapshotWithAngleMedia()
        assertEquals("el legacy previo sigue intacto", beforeChange.legacy.tables, afterRejection.legacy.tables)
        assertEquals("el agregado V2 no se borro", afterChange.angleV2, afterRejection.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertEquals("previous athlete", dao.athletes().single().displayName)
        assertThrows(ManualBackupV2BlockedException::class.java) { runBlocking { dao.snapshot() } }
        // assertThrows devuelve la excepcion capturada: este Unit mantiene `= runBlocking` como Unit
        // (JUnit4 exige que los metodos @Test devuelvan void).
        Unit
    }

    /**
     * El camino de producto completo: el aviso que acepto el usuario viaja con la confirmacion, pero
     * el agregado cambio (mismos conteos, otro contenido). El coordinador lo rechaza, el repositorio
     * informa [ManualBackupRestoreRun.Reverted] y ni el legacy ni el V2 se modifican.
     */
    @Test
    fun theRepositoryRevertsWhenTheConfirmedAggregateChangedWithTheSameCounts() = runBlocking {
        dao.replaceAll(previousLegacyDocument())
        seedDefaultAngleV2()
        val load = loadLegacyJson()
        val warning = requireNotNull(repository.legacyAngleV2DiscardWarning(load))

        changeAngleV2ContentKeepingCounts()
        val beforeApply = dao.snapshotWithAngleMedia()

        val result = startConfirmedRestore(load, warning)

        assertEquals(ManualBackupRestoreRun.Reverted, result)
        assertEquals("no se aplico nada: no hubo reconciliacion", 0, housekeeping.get())
        val after = dao.snapshotWithAngleMedia()
        assertEquals("el legacy previo sigue intacto", beforeApply.legacy.tables, after.legacy.tables)
        assertEquals("el agregado V2 cambiado sigue intacto", beforeApply.angleV2, after.angleV2)
        assertEquals("previous athlete", dao.athletes().single().displayName)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
    }

    // --- Utilidades ------------------------------------------------------------------------------

    /**
     * Restauracion confirmada de producto: arranca en el ambito de aplicacion y espera el callback
     * tipado. El repositorio cierra la carga en su propio `finally`.
     */
    private suspend fun startConfirmedRestore(
        load: MediaBackupLoad,
        angleV2Discard: AngleV2DiscardWarning?,
    ): ManualBackupRestoreRun {
        val finished = CompletableDeferred<ManualBackupRestoreRun>()
        assertTrue(
            "la restauracion confirmada debe arrancar",
            repository.startConfirmedRestore(load, angleV2Discard) { finished.complete(it) },
        )
        return withTimeout(RESTORE_TIMEOUT_MS) { finished.await() }
    }

    private suspend fun loadLegacyJson(): MediaBackupLoad.Legacy =
        coordinator.load(ByteArrayInputStream(LEGACY_JSON.toByteArray(Charsets.UTF_8))) as MediaBackupLoad.Legacy

    private fun legacyDocument(): ManualBackupDocument = ManualBackupCodec.decode(LEGACY_JSON)

    /** Documento legacy distinto (mismo esquema): sirve para distinguir lo aplicado de lo previo. */
    private fun previousLegacyDocument(): ManualBackupDocument = legacyDocument().let { document ->
        document.copy(
            tables = document.tables.copy(
                athletes = document.tables.athletes.map { it.copy(displayName = "previous athlete") },
                attempts = document.tables.attempts.map { it.copy(detectedFps = 30) },
            ),
        )
    }

    /**
     * `attemptEvents` se captura con el orden SQL (`ORDER BY attemptId, type, ordinal`), no con el
     * orden del JSON: mismo contenido, comparacion fiable contra una captura.
     */
    private fun sqlOrdered(tables: ManualBackupTables): ManualBackupTables = tables.copy(
        attemptEvents = tables.attemptEvents.sortedWith(
            compareBy({ it.attemptId }, { it.type }, { it.ordinal }),
        ),
    )

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun scalar(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun foreignKeysClean(): Boolean =
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() }

    private fun angleV2Counts(): Triple<Int, Int, Int> = Triple(
        scalar("SELECT COUNT(*) FROM angle_analysis_v2_roots"),
        scalar("SELECT COUNT(*) FROM angle_analysis_v2_frames"),
        scalar("SELECT COUNT(*) FROM angle_analysis_v2_marks"),
    )

    /**
     * Siembra por SQL directo el mismo agregado valido de referencia que el resto de pruebas de
     * restore (dos raices, una sin dueno; tres fotogramas; cuatro marcas). No abre ninguna via de
     * escritura de producto y no depende del codigo bajo prueba.
     */
    private fun seedDefaultAngleV2() {
        val firstMarkColor = 0xFFFF3366.toInt()
        val secondMarkColor = 0x7F102030
        val thirdMarkColor = 0xFF00FF00.toInt()
        val fourthMarkColor = 0xFF0000FF.toInt()
        exec(
            "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, createdAt, updatedAt, revision) VALUES " +
                "(1, '$ANGLE_ANALYSIS_V2_FORMAT', 'session-1', 1, 10, 20, 3), " +
                "(2, '$ANGLE_ANALYSIS_V2_FORMAT', 'session-2', NULL, 30, 40, 0)",
        )
        exec(
            "INSERT INTO angle_analysis_v2_frames (id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex, " +
                "assignedPtsUs, reportedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
                "rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount, imageSha256, " +
                "imageMimeType) VALUES " +
                "(11, 1, 0, 5, 7, 500000, 700000, 640, 480, 640, 480, 0, 2, '$KEY_A', 640, 480, 4096, '$SHA_A', " +
                "'$MEDIA_BACKUP_IMAGE_MIME_TYPE'), " +
                "(12, 2, 0, 9, NULL, 900000, NULL, 1920, 1080, 1080, 1920, 90, 1, '$KEY_B', 540, 960, 8192, '$SHA_B', " +
                "'$MEDIA_BACKUP_IMAGE_MIME_TYPE'), " +
                "(13, 2, 1, 11, 12, 1100000, 1200000, 1920, 1080, 1080, 1920, 90, 1, '$KEY_C', 540, 960, 1024, '$SHA_C', " +
                "'$MEDIA_BACKUP_IMAGE_MIME_TYPE')",
        )
        exec(
            "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, " +
                "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) VALUES " +
                "(101, 11, 0, 'rodilla', $firstMarkColor, 1, 10.0, 10.0, 100.0, 100.0, 200.0, 10.0), " +
                "(102, 11, 1, 'cadera', $secondMarkColor, 0, 10.0, 10.0, 100.0, 100.0, 200.0, 10.0), " +
                "(103, 12, 0, 'tobillo', $thirdMarkColor, 1, 10.0, 10.0, 100.0, 100.0, 200.0, 10.0), " +
                "(104, 13, 0, 'hombro', $fourthMarkColor, 1, 10.0, 10.0, 100.0, 100.0, 200.0, 10.0)",
        )
    }

    /**
     * Cambia contenido del agregado V2 **sin cambiar ningun conteo**: otro nombre de marca y otra
     * revision de raiz. Ambas columnas entran en la huella semantica y son validas para el manifiesto.
     */
    private fun changeAngleV2ContentKeepingCounts() {
        exec("UPDATE angle_analysis_v2_marks SET name = 'changed-after-warning' WHERE id = 101")
        exec("UPDATE angle_analysis_v2_roots SET revision = 99 WHERE id = 1")
    }

    private companion object {
        const val SANDBOX_PREFIX = "openjump-legacy-discard-instrumented-"
        const val RESTORE_TIMEOUT_MS = 10_000L
        const val KEY_A = "00112233445566778899aabbccddeeff"
        const val KEY_B = "ffeeddccbbaa99887766554433221100"
        const val KEY_C = "0123456789abcdef0123456789abcdef"
        const val SHA_A = "aa00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_B = "bb00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_C = "cc00000000000000000000000000000000000000000000000000000000000000"

        /** Documento legacy v1 valido (Room 10) con atleta 1, evaluacion 2 e intento 3. */
        const val LEGACY_JSON =
            """{"contract":"openjump-manual-backup","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"instrumented athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"instrumented-assessment","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}
