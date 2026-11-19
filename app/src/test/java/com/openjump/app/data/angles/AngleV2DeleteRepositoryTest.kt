package com.openjump.app.data.angles

import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2DeleteOutcome
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2OwnedImage
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import com.openjump.app.data.AngleAnalysisV2StoredFrame
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM deterministas de [AngleV2DeleteRepository], de la generación de restauración
 * ([AngleMediaRestoreEpoch]) y del dueño de aplicación ([AngleV2DeleteOwnership]).
 *
 * **Qué se prueba de verdad.** La coordinación: la prueba se rechaza por generación **antes** de
 * leer, la instantánea se copia completa, un fallo del DAO no toca ficheros, el commit se mantiene
 * como borrado aunque el cleanup falle, una referencia existente (o una consulta de referencias
 * fallida) conserva el fichero, el mutex sigue tomado entre commit y cleanup, cancelar mientras se
 * espera el mutex no muta nada, y el dueño de aplicación entrega el informe aunque la corrutina de UI
 * esté cancelada.
 *
 * **Qué NO se prueba.** No hay Room ni Android: el DAO es un fake que devuelve los resultados
 * tipados de `deleteConfirmedAggregate` (la comparación exacta, el rollback y la cascada ya están
 * cubiertos por los 9 tests instrumentados de `AngleAnalysisV2DeleteRollbackException` diferidos a
 * la campaña final). Tampoco se ejecuta la reclamación real del almacén (12 tests JVM propios de
 * S2b) ni ningún restore real: sólo la costura de invalidación. No hay `sleep`: todas las esperas son
 * `CompletableDeferred`, `Mutex` real y `withTimeout` como cota.
 */
class AngleV2DeleteRepositoryTest {

    // --- Preparación: generación antes de leer, copia completa, sin mutación -------------------

    @Test
    fun prepareRejectsAnOldRestoreTokenBeforeReadingTheRoot() = runBlocking {
        val dao = FakeV2Dao()
        dao.aggregate = snapshot()
        val epoch = AngleMediaRestoreEpoch()
        val repository = repository(dao = dao, epoch = epoch)

        val preparation = repository.prepare(rootId = ROOT_ID, expectedRestoreToken = 41L)

        val stale = preparation as AngleV2DeletePreparation.Stale
        assertEquals(epoch.current(), stale.observedRestoreToken)
        assertEquals(0, dao.aggregateReads)
    }

    @Test
    fun prepareCopiesTheFullSnapshotSoLaterChangesCannotAlterTheProof() = runBlocking {
        val dao = FakeV2Dao()
        val mutableFrames = ArrayList(snapshot().frames)
        dao.aggregate = snapshot().copy(frames = mutableFrames)
        val epoch = AngleMediaRestoreEpoch()
        val repository = repository(dao = dao, epoch = epoch)

        val prepared = repository.prepare(ROOT_ID, epoch.current()) as AngleV2DeletePreparation.Prepared
        // El dialogo ya esta abierto: otro lector "muta" la lista que devolvio el DAO.
        mutableFrames.clear()
        dao.aggregate = null

        assertEquals(ROOT_ID, prepared.proof.rootId)
        assertEquals(2, prepared.proof.frameCount)
        assertEquals(SESSION_KEY, prepared.proof.snapshot.root.sessionKey)
    }

    @Test
    fun prepareReportsMissingWithoutMutatingAnything() = runBlocking {
        val dao = FakeV2Dao()
        val epoch = AngleMediaRestoreEpoch()
        val repository = repository(dao = dao, epoch = epoch)

        val preparation = repository.prepare(ROOT_ID, epoch.current())

        assertEquals(AngleV2DeletePreparation.Missing(ROOT_ID), preparation)
        assertEquals(1, dao.aggregateReads)
        assertEquals(0, dao.deleteCalls)
    }

    // --- Ejecucion: DAO primero, ficheros solo tras Deleted ------------------------------------

    @Test
    fun databaseFailureBeforeTheDeletePerformsNoFileReclaim() = runBlocking {
        val dao = FakeV2Dao()
        dao.deleteAction = { throw IllegalStateException("Room no disponible") }
        val reclaimer = FakeReclaimer()
        val repository = repository(dao = dao, reclaimer = reclaimer)
        val proof = proof(dao)

        val run = repository.execute(proof)

        assertEquals(
            AngleV2DeleteRun.Failed(AngleV2DeleteFailure.Database("IllegalStateException")),
            run,
        )
        assertEquals(1, dao.deleteCalls)
        assertTrue(reclaimer.calls.isEmpty())
        assertTrue(dao.referenceQueries.isEmpty())
    }

    @Test
    fun deletedUsesTheExactRowIntegrityForEachReclaim() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        val reclaimer = FakeReclaimer()
        val repository = repository(dao = dao, reclaimer = reclaimer)

        val run = repository.execute(proof(dao)) as AngleV2DeleteRun.Deleted

        assertEquals(listOf(KEY_A, KEY_B), reclaimer.calls.map { it.first })
        val firstFrame = stored.frames[0].frame
        assertEquals(firstFrame.imageByteCount, reclaimer.calls[0].second.byteCount)
        assertEquals(firstFrame.imageSha256, reclaimer.calls[0].second.sha256Hex)
        assertEquals(listOf(KEY_A, KEY_B), dao.referenceQueries)
        assertTrue(run.cleanup.fullyRemoved)
        assertEquals(2, run.cleanup.removed)
    }

    @Test
    fun cleanupFailureAfterTheCommitStillReportsTheAggregateAsDeleted() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        val reclaimer = FakeReclaimer()
        reclaimer.action = { key, _ ->
            if (key == KEY_A) throw IllegalStateException("unlink inesperado")
            removed(key, directoryRetained = true, synced = false)
        }
        val repository = repository(dao = dao, reclaimer = reclaimer)

        val run = repository.execute(proof(dao)) as AngleV2DeleteRun.Deleted

        assertEquals(ROOT_ID, run.rootId)
        assertEquals(1, run.cleanup.unconfirmed)
        assertEquals(1, run.cleanup.removed)
        assertEquals(1, run.cleanup.retainedDirectories)
        assertEquals(1, run.cleanup.unsyncedRemovals)
        assertFalse(run.cleanup.fullyRemoved)
        assertEquals(AngleV2DeleteObservation.UNKNOWN, (run.cleanup.entries[0] as AngleV2DeleteCleanupEntry.Unconfirmed).observed)
    }

    @Test
    fun referencedKeyOrFailedLookupRetainsTheImageWithoutGuessing() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        dao.referenceCounts[KEY_A] = 2
        dao.referenceFailures[KEY_B] = IllegalStateException("consulta rota")
        val reclaimer = FakeReclaimer()
        val repository = repository(dao = dao, reclaimer = reclaimer)

        val run = repository.execute(proof(dao)) as AngleV2DeleteRun.Deleted

        // Ni una reclamacion: no se adivina que la clave este libre.
        assertTrue(reclaimer.calls.isEmpty())
        assertEquals(
            AngleV2DeleteCleanupEntry.Retained(0, AngleV2DeleteRetentionReason.StillReferenced(2)),
            run.cleanup.entries[0],
        )
        assertEquals(
            AngleV2DeleteCleanupEntry.Retained(1, AngleV2DeleteRetentionReason.ReferenceLookupFailed),
            run.cleanup.entries[1],
        )
        assertEquals(2, run.cleanup.retained)
    }

    @Test
    fun changedMissingAndInvalidProofNeverTouchFiles() = runBlocking {
        val results = listOf(
            AngleAnalysisV2DeleteOutcome.Changed(ROOT_ID, "root.revision"),
            AngleAnalysisV2DeleteOutcome.Missing(ROOT_ID),
            AngleAnalysisV2DeleteOutcome.InvalidProof("la prueba declara un id de raíz no persistido"),
        )
        val expected = listOf(
            AngleV2DeleteRun.Changed(ROOT_ID, "root.revision"),
            AngleV2DeleteRun.Missing(ROOT_ID),
            AngleV2DeleteRun.Failed(
                AngleV2DeleteFailure.InvalidProof("la prueba declara un id de raíz no persistido"),
            ),
        )
        results.forEachIndexed { index, outcome ->
            val dao = FakeV2Dao()
            dao.deleteAction = { outcome }
            val reclaimer = FakeReclaimer()
            val repository = repository(dao = dao, reclaimer = reclaimer)

            assertEquals(expected[index], repository.execute(proof(dao)))
            assertTrue(reclaimer.calls.isEmpty())
            assertTrue(dao.referenceQueries.isEmpty())
        }
    }

    @Test
    fun restoreEpochInvalidatesAProofPreparedFromAnOlderPage() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        val epoch = AngleMediaRestoreEpoch()
        val mutex = Mutex()
        val repository = repository(dao = dao, epoch = epoch, mutex = mutex)
        val prepared = repository.prepare(ROOT_ID, epoch.current()) as AngleV2DeletePreparation.Prepared

        // Un restore real de la app, aunque deje el MISMO contenido y la MISMA raiz, abre generacion.
        withAngleRestoreInvalidation(mutex, epoch) { }

        val run = repository.execute(prepared.proof)

        assertEquals(AngleV2DeleteRun.Stale(epoch.current()), run)
        assertEquals(0, dao.deleteCalls)
    }

    // --- Exclusion y cancelacion ---------------------------------------------------------------

    @Test
    fun theCommitAndCleanupKeepTheCoordinationLockAgainstAnotherWriter() = runBlocking {
        val sequence = ArrayList<String>()
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = {
            sequence += "commit"
            AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored))
        }
        val insideCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val reclaimer = FakeReclaimer()
        reclaimer.action = { key, _ ->
            if (key == KEY_A) {
                sequence += "cleanup-start"
                insideCleanup.complete(Unit)
                releaseCleanup.await()
            }
            sequence += "cleanup-done-$key"
            removed(key)
        }
        val mutex = Mutex()
        val epoch = AngleMediaRestoreEpoch()
        val repository = repository(
            dao = dao,
            epoch = epoch,
            mutex = mutex,
            reclaimer = reclaimer,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val deleteJob = launch(Dispatchers.Unconfined) { repository.execute(proof(dao)) }
        insideCleanup.await()

        // Un guardado/restore con el MISMO mutex no puede entrar entre el commit y el cleanup.
        val otherWriter = launch(Dispatchers.Unconfined) {
            mutex.withLock { sequence += "other-writer" }
        }
        assertFalse(sequence.contains("other-writer"))

        releaseCleanup.complete(Unit)
        deleteJob.join()
        otherWriter.join()

        assertEquals(
            listOf(
                "commit",
                "cleanup-start",
                "cleanup-done-$KEY_A",
                "cleanup-done-$KEY_B",
                "other-writer",
            ),
            sequence,
        )
    }

    @Test
    fun cancellingWhileWaitingForTheLockMutatesNothing() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        val reclaimer = FakeReclaimer()
        val mutex = Mutex()
        val epoch = AngleMediaRestoreEpoch()
        val repository = repository(dao = dao, epoch = epoch, mutex = mutex, reclaimer = reclaimer)
        val proof = proof(dao)

        val locked = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            mutex.withLock {
                locked.complete(Unit)
                releaseLock.await()
            }
        }
        locked.await()
        // Unconfined: arranca en linea y queda suspendida esperando el mutex antes de cancelar.
        val waiting = launch(Dispatchers.Unconfined) { repository.execute(proof) }
        waiting.cancel()
        releaseLock.complete(Unit)
        holder.join()
        waiting.join()

        assertEquals(0, dao.deleteCalls)
        assertTrue(reclaimer.calls.isEmpty())
        assertTrue(dao.referenceQueries.isEmpty())
    }

    @Test
    fun historyObservationReadsThePageAndTheTokenUnderTheSameLock() = runBlocking {
        val mutex = Mutex()
        val epoch = AngleMediaRestoreEpoch()
        val page = ArrayList(listOf("frame-a"))
        val restorerHoldsLock = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val restorer = launch(Dispatchers.IO) {
            mutex.withLock {
                page += "frame-b"
                epoch.newTokenLocked()
                restorerHoldsLock.complete(Unit)
                releaseRestore.await()
            }
        }
        restorerHoldsLock.await()
        val observed = CompletableDeferred<AngleHistoryObservation<List<String>>>()
        val observer = launch(Dispatchers.Unconfined) {
            observed.complete(observeAngleHistoryAtomically(mutex, epoch) { page.toList() })
        }
        // La consulta no puede ejecutarse mientras el restore tiene el mutex.
        assertFalse(observed.isCompleted)

        releaseRestore.complete(Unit)
        restorer.join()
        observer.join()

        val observation = observed.await()
        assertEquals(listOf("frame-a", "frame-b"), observation.value)
        assertEquals(epoch.current(), observation.restoreToken)
    }

    @Test
    fun legacyRestoreInvalidationOpensANewGenerationEvenWhenTheReplaceFails() = runBlocking {
        val mutex = Mutex()
        val epoch = AngleMediaRestoreEpoch()
        val before = epoch.current()
        val insideReplace = AtomicReference<Long>()

        withAngleRestoreInvalidation(mutex, epoch) { insideReplace.set(epoch.current()) }

        assertTrue(insideReplace.get() > before)
        assertEquals(insideReplace.get(), epoch.current())

        val afterFirstRestore = epoch.current()
        runCatching { withAngleRestoreInvalidation(mutex, epoch) { throw IllegalStateException("fallo") } }
        assertTrue(epoch.current() > afterFirstRestore)
    }

    // --- Dueno de aplicacion -------------------------------------------------------------------

    @Test
    fun applicationOwnedRunDeliversTheResultAfterTheUiCoroutineIsCancelled() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        val releaseDelete = CompletableDeferred<Unit>()
        dao.deleteAction = {
            releaseDelete.await()
            AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored))
        }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reports = ArrayList<AngleV2DeleteReport>()
        val owner = AngleV2DeleteOwnership(appScope, reporter = { reports += it })
        val repository = repository(dao = dao)
        val finished = CompletableDeferred<AngleV2DeleteRun>()
        val uiCaller = launch(Dispatchers.Default) {
            owner.start(repository, proof(dao)) { finished.complete(it) }
            // La pantalla desaparece mientras el borrado de aplicacion sigue en vuelo.
            cancel()
        }
        uiCaller.join()
        assertFalse(finished.isCompleted)

        releaseDelete.complete(Unit)
        val run = withTimeout(5_000L) { finished.await() }
        appScope.cancel()

        assertTrue(run is AngleV2DeleteRun.Deleted)
        assertEquals(1, reports.size)
        assertEquals(ROOT_ID, reports.single().rootId)
        assertTrue(owner.lastCompletedRun is AngleV2DeleteRun.Deleted)
        assertEquals(1, owner.drainReports().size)
    }

    @Test
    fun anOwnerWithoutAnActiveScopeRejectsStartWithoutTouchingAnything() = runBlocking {
        val dao = FakeV2Dao()
        val deadScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.IO)
        val owner = AngleV2DeleteOwnership(deadScope)
        val repository = repository(dao = dao)

        val started = owner.start(repository, proof(dao)) { }

        assertFalse(started)
        assertEquals(0, dao.deleteCalls)
        assertTrue(owner.drainReports().isEmpty())
        assertNull(owner.lastCompletedRun)
    }

    @Test
    fun aThrowingCallbackDoesNotLoseTheRecordedReport() = runBlocking {
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reported = CompletableDeferred<AngleV2DeleteReport>()
        val owner = AngleV2DeleteOwnership(appScope, reporter = { reported.complete(it) })
        val repository = repository(dao = dao)

        assertTrue(
            owner.start(repository, proof(dao)) { throw IllegalStateException("callback roto") },
        )
        val report = withTimeout(5_000L) { reported.await() }
        appScope.cancel()

        assertTrue(report.run is AngleV2DeleteRun.Deleted)
        assertEquals(1, owner.drainReports().size)
        assertTrue(owner.lastCompletedRun is AngleV2DeleteRun.Deleted)
    }

    @Test
    fun aNegativeReferenceCountIsNotReadAsZeroAndTheImageIsRetained() = runBlocking {
        // `COUNT(*)` no puede ser negativo: si el contador llega así, la consulta no es fiable y la
        // imagen se conserva. Sólo el 0 exacto autoriza reclamar.
        val dao = FakeV2Dao()
        val stored = snapshot()
        dao.aggregate = stored
        dao.deleteAction = { AngleAnalysisV2DeleteOutcome.Deleted(ROOT_ID, ownedImagesOf(stored)) }
        dao.referenceCounts[KEY_A] = -1
        val reclaimer = FakeReclaimer()
        val repository = repository(dao = dao, reclaimer = reclaimer)

        val run = repository.execute(proof(dao)) as AngleV2DeleteRun.Deleted

        assertEquals(
            AngleV2DeleteRetentionReason.ReferenceLookupFailed,
            (run.cleanup.entries[0] as AngleV2DeleteCleanupEntry.Retained).reason,
        )
        assertEquals(listOf(KEY_B), reclaimer.calls.map { it.first })
        assertEquals(1, run.cleanup.retained)
        assertEquals(1, run.cleanup.removed)
    }

    // --- Arnes --------------------------------------------------------------------------------

    private fun repository(
        dao: AngleAnalysisV2Dao,
        epoch: AngleMediaRestoreEpoch,
        mutex: Mutex = Mutex(),
        reclaimer: FakeReclaimer = FakeReclaimer(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): AngleV2DeleteRepository = AngleV2DeleteRepository(
        dao = dao,
        imageStore = null,
        ioDispatcher = ioDispatcher,
        coordinationMutex = mutex,
        restoreEpoch = epoch,
        reclaimImage = reclaimer::reclaim,
    )

    private fun repository(
        dao: AngleAnalysisV2Dao,
        reclaimer: FakeReclaimer = FakeReclaimer(),
    ): AngleV2DeleteRepository = repository(
        dao = dao,
        epoch = AngleMediaRestoreEpoch(),
        reclaimer = reclaimer,
    )

    private fun proof(dao: FakeV2Dao): AngleV2DeleteProof =
        AngleV2DeleteProof(snapshot = dao.aggregate ?: snapshot(), restoreToken = 0L)

    private fun snapshot(): AngleAnalysisV2StoredAggregate = AngleAnalysisV2StoredAggregate(
        root = AngleAnalysisV2RootEntity(
            id = ROOT_ID,
            sessionKey = SESSION_KEY,
            athleteId = 3L,
            createdAt = 1_000L,
            updatedAt = 2_000L,
        ),
        frames = listOf(
            storedFrame(ordinal = 0, frameId = 101L, imageKey = KEY_A, byteCount = 600L, sha = SHA_A),
            storedFrame(ordinal = 1, frameId = 102L, imageKey = KEY_B, byteCount = 700L, sha = SHA_B),
        ),
    )

    private fun storedFrame(
        ordinal: Int,
        frameId: Long,
        imageKey: String,
        byteCount: Long,
        sha: String,
    ): AngleAnalysisV2StoredFrame = AngleAnalysisV2StoredFrame(
        frame = AngleAnalysisV2FrameEntity(
            id = frameId,
            rootId = ROOT_ID,
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
            imageByteCount = byteCount,
            imageSha256 = sha,
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

    private fun removed(
        imageKey: String,
        directoryRetained: Boolean = false,
        synced: Boolean = true,
    ): AngleImageReclaimResult.Removed = AngleImageReclaimResult.Removed(
        image = StoredAngleImage(imageKey = imageKey, byteCount = 600L, sha256Hex = SHA_A),
        directoryRetained = directoryRetained,
        durability = AngleImageReclaimDurability(
            keyDirectory = if (synced) AngleImageDirectorySync.SYNCED else AngleImageDirectorySync.FAILED,
            imagesDirectory = AngleImageDirectorySync.SYNCED,
        ),
    )

    /**
     * DAO fake: devuelve los resultados **tipados** que el test guiona y registra las llamadas.
     *
     * No simula Room: no compara la prueba, no revierte transacciones ni aplica la cascada (eso ya
     * está cubierto por los tests instrumentados diferidos). Existe para probar la coordinación sin
     * dispositivo y para verificar qué pruebas y qué claves recibe la puerta real.
     */
    private class FakeV2Dao : AngleAnalysisV2Dao {

        var aggregate: AngleAnalysisV2StoredAggregate? = null
        var aggregateReads: Int = 0
        var deleteCalls: Int = 0
        var lastProof: AngleAnalysisV2StoredAggregate? = null
        var deleteAction: suspend () -> AngleAnalysisV2DeleteOutcome = {
            error("el test no guionó deleteConfirmedAggregate")
        }
        val referenceCounts = HashMap<String, Int>()
        val referenceFailures = HashMap<String, RuntimeException>()
        val referenceQueries = ArrayList<String>()

        override suspend fun aggregateByRootId(rootId: Long): AngleAnalysisV2StoredAggregate? {
            aggregateReads += 1
            return aggregate
        }

        override suspend fun deleteConfirmedAggregate(proof: AngleAnalysisV2StoredAggregate): AngleAnalysisV2DeleteOutcome {
            deleteCalls += 1
            lastProof = proof
            return deleteAction()
        }

        override suspend fun frameImageReferenceCount(imageKey: String): Int {
            referenceQueries += imageKey
            referenceFailures[imageKey]?.let { throw it }
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

        private fun unused(): Nothing = error("el fake no cubre esta consulta en S3")
    }

    /** Costura de reclamación: registra (clave, integridad) y devuelve el resultado guionado. */
    private class FakeReclaimer {

        val calls = ArrayList<Pair<String, AngleImageIntegrity>>()
        var action: suspend (String, AngleImageIntegrity) -> AngleImageReclaimResult = { key, _ ->
            AngleImageReclaimResult.Removed(
                image = StoredAngleImage(imageKey = key, byteCount = 600L, sha256Hex = SHA_A),
                directoryRetained = false,
                durability = AngleImageReclaimDurability(
                    keyDirectory = AngleImageDirectorySync.SYNCED,
                    imagesDirectory = AngleImageDirectorySync.SYNCED,
                ),
            )
        }

        suspend fun reclaim(imageKey: String, integrity: AngleImageIntegrity): AngleImageReclaimResult {
            calls += imageKey to integrity
            return action(imageKey, integrity)
        }
    }

    private companion object {
        const val ROOT_ID: Long = 7L
        const val SESSION_KEY: String = "session-angles-v2"
        const val KEY_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val KEY_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_A: String = "1111111111111111111111111111111111111111111111111111111111111111"
        const val SHA_B: String = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
