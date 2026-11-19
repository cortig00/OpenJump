package com.openjump.app.ui.history

import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2DeleteOutcome
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2OwnedImage
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import com.openjump.app.data.AngleAnalysisV2StoredFrame
import com.openjump.app.data.angles.AngleImageDirectorySync
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReclaimDurability
import com.openjump.app.data.angles.AngleImageReclaimResult
import com.openjump.app.data.angles.AngleMediaRestoreEpoch
import com.openjump.app.data.angles.AngleV2DeleteOwnership
import com.openjump.app.data.angles.AngleV2DeleteProof
import com.openjump.app.data.angles.AngleV2DeleteRepository
import com.openjump.app.data.angles.StoredAngleImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del ciclo de borrado V2 del historial ([HistoryV2DeletionController]) con las piezas
 * REALES de S3: [AngleV2DeleteRepository] (preparación + commit + reclamación), la generación de
 * restauración compartida y el dueño de aplicación [AngleV2DeleteOwnership].
 *
 * El DAO es un fake que devuelve los resultados tipados (no hay Room en JVM), así que se prueba la
 * coordinación que añade este slice: autoridad por generación, prueba en memoria, un solo vuelo,
 * cancelar sin efectos, stale sin borrado, reintento con la MISMA prueba, commit con limpieza
 * incompleta que no se convierte en fallo, e informe del dueño aunque la pantalla ya no exista. No se
 * afirma runtime de Room ni reclamación real del almacén (fuera de este slice).
 */
class HistoryV2DeletionLifecycleTest {

    // --- cancelar / autoridad ---------------------------------------------------------------------

    @Test
    fun cancelNeverTouchesRowsOrFiles() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertNotNull("la preparación sólo lee y abre la confirmación", harness.controller.confirmation.value)
        harness.controller.cancel()
        assertNull(harness.controller.confirmation.value)

        // Cancelar olvida la prueba: confirmar después no ejecuta nada.
        harness.controller.confirm()
        assertEquals("cancelar no borra filas", 0, harness.dao.deleteCalls)
        assertTrue("cancelar no toca ficheros", harness.reclaimer.calls.isEmpty())
        assertEquals(0, harness.startedProofs.size)
        assertEquals(0, harness.refreshes)
    }

    @Test
    fun idOnlyRestoredKeyCannotAuthorizeV2() {
        // La clave serializable de una confirmación V2 no se rehidrata: un id suelto no es prueba.
        assertNull(HistoryDeleteTarget.fromKey("angle_v2-12"))
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 12L), HistoryDeleteTarget.fromKey("angle-12"))
    }

    @Test
    fun numericIdCollisionKeepsLegacyAndV2AsSeparateFamilies() {
        val legacy = historyDeleteTarget(
            MeasurementHistoryItem.Angle(entry(id = 12L, source = AngleHistorySource.LEGACY)),
        )
        val v2 = historyDeleteTarget(
            MeasurementHistoryItem.Angle(entry(id = 12L, source = AngleHistorySource.MULTI_FRAME, restoreToken = 5L)),
        )
        val v2WithoutToken = historyDeleteTarget(
            MeasurementHistoryItem.Angle(entry(id = 12L, source = AngleHistorySource.MULTI_FRAME, restoreToken = null)),
        )

        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, 12L), legacy)
        assertEquals(HistoryDeleteTarget(HistoryDeleteFamily.ANGLE_V2, 12L), v2)
        assertNotEquals("el mismo número en otra tabla no es el mismo objetivo", legacy, v2)
        assertNull("sin generación observada no hay autoridad V2", v2WithoutToken)
        // El camino legacy falla cerrado para V2 y el legacy sigue siendo válido.
        assertTrue(historyLegacyDeleteSupports(HistoryDeleteFamily.ENCODER))
        assertTrue(historyLegacyDeleteSupports(HistoryDeleteFamily.ANGLE))
        assertFalse(historyLegacyDeleteSupports(HistoryDeleteFamily.ANGLE_V2))
    }

    // --- preparación: lista vieja, restauración ---------------------------------------------------

    @Test
    fun aStalePageNeverReadsTheRoot() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current() + 1L)

        assertEquals("la generación se comprueba antes de leer", 0, harness.dao.aggregateReads)
        assertNull(harness.controller.confirmation.value)
        assertEquals(HistoryV2DeleteNotice.Reconfirm, harness.controller.notice.value)
        assertEquals("se refresca sin retargetear", 1, harness.refreshes)
        assertEquals(0, harness.dao.deleteCalls)
    }

    @Test
    fun aRestoreWhileTheDialogIsOpenRejectsWithoutDeleting() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot())) }

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertNotNull(harness.controller.confirmation.value)

        // Restauración real: nueva generación bajo el MISMO mutex, con contenido y raíz idénticos.
        harness.mutex.withLock { harness.epoch.newTokenLocked() }

        harness.controller.confirm()

        assertEquals("una prueba vieja no borra la raíz restaurada", 0, harness.dao.deleteCalls)
        assertEquals(HistoryV2DeleteNotice.Reconfirm, harness.controller.notice.value)
        assertTrue(harness.reclaimer.calls.isEmpty())
    }

    // --- un solo vuelo / reintento ----------------------------------------------------------------

    @Test
    fun duplicateTapAndDuplicateConfirmRunASingleMutation() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot())) }

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertEquals("un segundo toque no prepara otra prueba", 1, harness.dao.aggregateReads)

        harness.controller.confirm()
        harness.controller.confirm()
        assertEquals("un segundo confirmar no repite el DELETE", 1, harness.dao.deleteCalls)
        assertEquals(1, harness.startedProofs.size)
    }

    @Test
    fun databaseFailureKeepsTheSameProofForRetry() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        var first = true
        harness.dao.deleteAction = {
            if (first) {
                first = false
                throw IllegalStateException("Room no disponible")
            }
            AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot()))
        }

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        harness.controller.confirm()

        assertEquals("fallo antes de confirmar: la fila sigue y se puede reintentar", HistoryV2DeleteNotice.Retryable, harness.controller.notice.value)
        assertEquals(1, harness.dao.deleteCalls)
        assertEquals("un fallo antes del commit no refresca como si se hubiera borrado", 0, harness.refreshes)
        assertTrue(harness.reclaimer.calls.isEmpty())

        harness.controller.retry()

        assertEquals(2, harness.dao.deleteCalls)
        assertEquals("el reintento reutiliza la MISMA prueba", 2, harness.startedProofs.size)
        assertSame(harness.startedProofs[0], harness.startedProofs[1])
        assertNull("tras el commit sí se refresca", harness.controller.notice.value)
        assertEquals(1, harness.refreshes)
    }

    // --- descartar el aviso de fallo no deja el controlador bloqueado --------------------------

    @Test
    fun dismissingTheRetryableNoticeReleasesTheProofSoANewRequestIsAccepted() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = { throw IllegalStateException("Room no disponible") }

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        harness.controller.confirm()
        assertEquals(HistoryV2DeleteNotice.Retryable, harness.controller.notice.value)
        assertEquals(1, harness.dao.deleteCalls)
        assertEquals(1, harness.startedProofs.size)

        // Descartar el aviso (Cancelar / onDismiss) no reintenta ni toca filas ni ficheros.
        harness.controller.dismissNotice()
        assertNull(harness.controller.notice.value)
        assertEquals("descartar el aviso no repite el DELETE", 1, harness.dao.deleteCalls)
        assertTrue("descartar el aviso no toca ficheros", harness.reclaimer.calls.isEmpty())
        assertEquals("descartar el aviso no refresca", 0, harness.refreshes)

        // Sin liberar la prueba retenida, TODA petición posterior sería un no-op silencioso.
        harness.dao.aggregate = snapshot(rootId = ROOT_ID_OTHER)
        harness.controller.requestDelete(ROOT_ID_OTHER, harness.epoch.current())
        assertEquals("otra fila también se puede volver a pedir", 2, harness.dao.aggregateReads)
        assertEquals(
            "la nueva confirmación es de la raíz pedida, no de la retenida",
            HistoryV2DeleteConfirmation(rootId = ROOT_ID_OTHER, frameCount = 2),
            harness.controller.confirmation.value,
        )
        harness.controller.cancel()
        assertNull(harness.controller.confirmation.value)
        assertEquals(1, harness.dao.deleteCalls)

        // La MISMA raíz del fallo también se puede volver a pedir, preparar y confirmar.
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = {
            AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot()))
        }
        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertEquals(3, harness.dao.aggregateReads)
        assertNotNull("la nueva confirmación se acepta", harness.controller.confirmation.value)
        assertEquals("nada se borra hasta confirmar de nuevo", 1, harness.dao.deleteCalls)
        harness.controller.confirm()
        assertEquals("la nueva confirmación sí ejecuta", 2, harness.dao.deleteCalls)
        assertTrue(
            "la nueva petición prepara una prueba nueva, no la retenida",
            harness.startedProofs[0] !== harness.startedProofs[1],
        )
        assertEquals(1, harness.refreshes)
    }

    // --- fallo de preparación: reintentable, con la generación observada retenida ----------------

    @Test
    fun transientPreparationFailureIsRetryableAndTheRetryKeepsTheObservedGeneration() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.readFailure = IllegalStateException("Room no disponible")

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())

        assertEquals(HistoryV2DeleteNotice.Retryable, harness.controller.notice.value)
        assertNull("un fallo de lectura no abre confirmación", harness.controller.confirmation.value)
        assertEquals("un fallo de lectura no refresca como si la lista hubiera cambiado", 0, harness.refreshes)
        assertEquals(1, harness.dao.aggregateReads)
        assertEquals(0, harness.dao.deleteCalls)
        assertEquals(0, harness.startedProofs.size)

        // El reintento reutiliza la MISMA (raíz, generación observada) y vuelve a leer.
        harness.dao.readFailure = null
        harness.dao.deleteAction = {
            AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot()))
        }
        harness.controller.retry()

        assertEquals(2, harness.dao.aggregateReads)
        assertEquals("el reintento exige confirmación explícita", 0, harness.dao.deleteCalls)
        assertEquals(
            HistoryV2DeleteConfirmation(rootId = ROOT_ID, frameCount = 2),
            harness.controller.confirmation.value,
        )
        assertNull(harness.controller.notice.value)

        harness.controller.confirm()

        assertEquals(1, harness.dao.deleteCalls)
        assertEquals(
            "la prueba preparada por el reintento usa la generación observada",
            harness.epoch.current(),
            harness.startedProofs.single().restoreToken,
        )
        assertEquals(1, harness.refreshes)
    }

    @Test
    fun aRestoreBetweenThePreparationFailureAndTheRetryRejectsBeforeTheDao() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.readFailure = IllegalStateException("Room no disponible")

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertEquals(HistoryV2DeleteNotice.Retryable, harness.controller.notice.value)
        assertEquals(1, harness.dao.aggregateReads)

        // Restauración real entre el fallo y el reintento: la generación observada deja de valer.
        harness.mutex.withLock { harness.epoch.newTokenLocked() }
        harness.controller.retry()

        assertEquals("el reintento comprueba la generación antes de leer la raíz", 1, harness.dao.aggregateReads)
        assertEquals(HistoryV2DeleteNotice.Reconfirm, harness.controller.notice.value)
        assertNull(harness.controller.confirmation.value)
        assertEquals("se refresca sin reapuntar a otra raíz", 1, harness.refreshes)
        assertEquals(0, harness.dao.deleteCalls)
        assertEquals(0, harness.startedProofs.size)

        // La petición quedó olvidada: otro reintento no prepara nada por su cuenta.
        harness.controller.retry()
        assertEquals(1, harness.dao.aggregateReads)
        assertEquals(0, harness.dao.deleteCalls)
    }

    @Test
    fun dismissingAPreparationFailurePermitsANewRequest() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.readFailure = IllegalStateException("Room no disponible")

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        assertEquals(HistoryV2DeleteNotice.Retryable, harness.controller.notice.value)

        harness.controller.dismissNotice()

        assertNull(harness.controller.notice.value)
        assertEquals(0, harness.dao.deleteCalls)
        assertTrue(harness.reclaimer.calls.isEmpty())
        assertEquals(0, harness.refreshes)

        harness.dao.readFailure = null
        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())

        assertEquals("descartar el aviso libera la petición retenida", 2, harness.dao.aggregateReads)
        assertNotNull(harness.controller.confirmation.value)
        assertEquals(0, harness.dao.deleteCalls)
    }

    // --- commit con limpieza incompleta -----------------------------------------------------------

    @Test
    fun committedCleanupFailureRefreshesAndWarnsInsteadOfReportingDeleteFailure() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot())) }
        // Una imagen aún referenciada: el almacén debe conservarla y el borrado sigue siendo válido.
        harness.dao.referenceCounts[KEY_A] = 2

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        harness.controller.confirm()

        val notice = harness.controller.notice.value as HistoryV2DeleteNotice.CleanupIncomplete
        assertEquals("imagen conservada por referencia viva", 1, notice.retained)
        assertEquals(0, notice.unconfirmed)
        assertEquals(1, harness.refreshes)
        assertEquals(1, harness.dao.deleteCalls)
        assertEquals("sólo se reclama la clave propia", listOf(KEY_B), harness.reclaimer.calls.map { it.first })
    }

    // --- dueño inactivo y pantalla cerrada --------------------------------------------------------

    @Test
    fun inactiveOwnerNeverExecutesAndOffersTheSameProofAgain() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        val inactiveOwner = AngleV2DeleteOwnership(scope = null)
        val started = ArrayList<AngleV2DeleteProof>()
        val controller = HistoryV2DeletionController(
            scope = harness.uiScope,
            prepare = harness.repository::prepare,
            startDelete = { proof, onFinished ->
                started += proof
                inactiveOwner.start(harness.repository, proof, onFinished)
            },
            onRefresh = { harness.refreshes += 1 },
            mainDispatcher = Dispatchers.Unconfined,
        )

        controller.requestDelete(ROOT_ID, harness.epoch.current())
        controller.confirm()

        assertEquals(0, harness.dao.deleteCalls)
        assertEquals(HistoryV2DeleteNotice.Retryable, controller.notice.value)

        // El candado se liberó: el reintento vuelve a arrancar con la misma prueba.
        controller.retry()
        assertEquals(2, started.size)
        assertSame(started[0], started[1])
    }

    @Test
    fun aConfirmedDeleteIsReportedByTheOwnerEvenAfterTheUiIsGone() = runBlocking {
        val harness = Harness()
        harness.dao.aggregate = snapshot()
        harness.dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(snapshot())) }

        harness.controller.requestDelete(ROOT_ID, harness.epoch.current())
        harness.uiScope.cancel()
        harness.controller.confirm()

        assertEquals(1, harness.dao.deleteCalls)
        val reports = harness.ownership.drainReports()
        assertEquals("el informe no depende de la pantalla", 1, reports.size)
        assertTrue(reports.single().run is com.openjump.app.data.angles.AngleV2DeleteRun.Deleted)
        assertEquals(0, harness.refreshes)
    }

    // --- arnés ------------------------------------------------------------------------------------

    private class Harness {
        val dao = FakeV2Dao()
        val epoch = AngleMediaRestoreEpoch()
        val mutex = Mutex()
        val reclaimer = FakeReclaimer()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var refreshes = 0
        val startedProofs = ArrayList<AngleV2DeleteProof>()
        val ownership = AngleV2DeleteOwnership(scope = appScope)
        val repository = AngleV2DeleteRepository(
            dao = dao,
            imageStore = null,
            ioDispatcher = Dispatchers.Unconfined,
            coordinationMutex = mutex,
            restoreEpoch = epoch,
            reclaimImage = reclaimer::reclaim,
        )
        val controller = HistoryV2DeletionController(
            scope = uiScope,
            prepare = repository::prepare,
            startDelete = { proof, onFinished ->
                startedProofs += proof
                ownership.start(repository, proof, onFinished)
            },
            onRefresh = { refreshes += 1 },
            mainDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun entry(
        id: Long,
        source: AngleHistorySource,
        restoreToken: Long? = null,
    ) = AngleAnalysisHistoryEntry(
        id = id,
        dateTime = 1_000L,
        athleteId = 1L,
        athleteName = "Ana",
        markCount = 2,
        videoUri = null,
        source = source,
        restoreToken = restoreToken,
    )

    private fun snapshot(rootId: Long = ROOT_ID): AngleAnalysisV2StoredAggregate = AngleAnalysisV2StoredAggregate(
        root = AngleAnalysisV2RootEntity(
            id = rootId,
            sessionKey = SESSION_KEY,
            athleteId = 3L,
            createdAt = 1_000L,
            updatedAt = 2_000L,
        ),
        frames = listOf(
            storedFrame(rootId = rootId, ordinal = 0, frameId = 101L, imageKey = KEY_A),
            storedFrame(rootId = rootId, ordinal = 1, frameId = 102L, imageKey = KEY_B),
        ),
    )

    private fun storedFrame(
        rootId: Long = ROOT_ID,
        ordinal: Int,
        frameId: Long,
        imageKey: String,
    ): AngleAnalysisV2StoredFrame =
        AngleAnalysisV2StoredFrame(
            frame = AngleAnalysisV2FrameEntity(
                id = frameId,
                rootId = rootId,
                ordinal = ordinal,
                assignedFrameIndex = ordinal * 10,
                reportedFrameIndex = ordinal * 10,
                assignedPtsUs = ordinal * 33_333L,
                reportedPtsUs = ordinal * 33_333L,
                encodedWidth = 1_920,
                encodedHeight = 1_080,
                presentationWidth = 1_920,
                presentationHeight = 1_080,
                rotationDegrees = 0,
                geometryVersion = 1,
                imageKey = imageKey,
                imageWidth = 1_920,
                imageHeight = 1_080,
                imageByteCount = 600L,
                imageSha256 = SHA_A,
                imageMimeType = "image/png",
            ),
            marks = listOf(
                AngleAnalysisV2MarkEntity(
                    id = frameId * 10,
                    frameId = frameId,
                    ordinal = 0,
                    name = "cadera",
                    colorArgb = 0xFF00FF00.toInt(),
                    visible = true,
                    pointAX = 1.0,
                    pointAY = 2.0,
                    pointBX = 3.0,
                    pointBY = 4.0,
                    pointCX = 5.0,
                    pointCY = 6.0,
                ),
            ),
        )

    private fun ownedImagesOf(stored: AngleAnalysisV2StoredAggregate): List<AngleAnalysisV2OwnedImage> =
        stored.frames.map { storedFrame ->
            val frame = storedFrame.frame
            AngleAnalysisV2OwnedImage(
                imageKey = frame.imageKey,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                imageByteCount = frame.imageByteCount,
                imageSha256 = frame.imageSha256,
                imageMimeType = frame.imageMimeType,
            )
        }

    /** DAO fake de S3: resultados tipados guionados y trazas; no simula Room. */
    private class FakeV2Dao : AngleAnalysisV2Dao {
        var aggregate: AngleAnalysisV2StoredAggregate? = null
        var aggregateReads: Int = 0

        /** Fallo de lectura de la raíz guionado (equivale a un error de Room antes de mutar nada). */
        var readFailure: RuntimeException? = null
        var deleteCalls: Int = 0
        var deleteAction: suspend () -> AngleAnalysisV2DeleteOutcome = {
            error("el test no guionó deleteConfirmedAggregate")
        }
        val referenceCounts = HashMap<String, Int>()
        val referenceQueries = ArrayList<String>()

        override suspend fun aggregateByRootId(rootId: Long): AngleAnalysisV2StoredAggregate? {
            aggregateReads += 1
            readFailure?.let { throw it }
            // Igual que el DAO real: cada id apunta a SU raíz (un id ajeno no devuelve esta fila).
            return aggregate?.takeIf { it.root.id == rootId }
        }

        override suspend fun deleteConfirmedAggregate(proof: AngleAnalysisV2StoredAggregate): AngleAnalysisV2DeleteOutcome {
            deleteCalls += 1
            return deleteAction()
        }

        override suspend fun frameImageReferenceCount(imageKey: String): Int {
            referenceQueries += imageKey
            return referenceCounts[imageKey] ?: 0
        }

        override suspend fun rootById(rootId: Long): AngleAnalysisV2RootEntity? = unused()
        override suspend fun rootBySessionKey(sessionKey: String): AngleAnalysisV2RootEntity? = unused()
        override suspend fun framesForRoot(rootId: Long): List<AngleAnalysisV2FrameEntity> = unused()
        override suspend fun frameById(rootId: Long, frameId: Long): AngleAnalysisV2FrameEntity? = unused()
        override suspend fun marksForFrame(frameId: Long): List<AngleAnalysisV2MarkEntity> = unused()
        override suspend fun frameCount(rootId: Long): Int = unused()
        override suspend fun insertRoot(root: AngleAnalysisV2RootEntity): Long = unused()
        override suspend fun insertFrame(frame: AngleAnalysisV2FrameEntity): Long = unused()
        override suspend fun insertMarks(marks: List<AngleAnalysisV2MarkEntity>) = unused()
        override suspend fun deleteRootRow(rootId: Long): Int = unused()

        private fun unused(): Nothing = error("el fake no cubre esta consulta")
    }

    private class FakeReclaimer {
        val calls = ArrayList<Pair<String, AngleImageIntegrity>>()

        suspend fun reclaim(imageKey: String, integrity: AngleImageIntegrity): AngleImageReclaimResult {
            calls += imageKey to integrity
            return AngleImageReclaimResult.Removed(
                image = StoredAngleImage(imageKey = imageKey, byteCount = 600L, sha256Hex = SHA_A),
                directoryRetained = false,
                durability = AngleImageReclaimDurability(
                    keyDirectory = AngleImageDirectorySync.SYNCED,
                    imagesDirectory = AngleImageDirectorySync.SYNCED,
                ),
            )
        }
    }

    private companion object {
        const val ROOT_ID: Long = 7L
        const val ROOT_ID_OTHER: Long = 8L
        const val SESSION_KEY: String = "session-angles-v2"
        const val KEY_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val KEY_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_A: String = "1111111111111111111111111111111111111111111111111111111111111111"
    }
}
