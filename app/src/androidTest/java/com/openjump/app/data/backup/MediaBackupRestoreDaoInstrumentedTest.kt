package com.openjump.app.data.backup

import android.content.Context
import android.database.SQLException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificacion instrumentada de G3b: `ManualBackupDao.replaceAllWithAngleMedia(manifest)` sustituye
 * **legacy + agregado V2** en una unica transaccion de Room, con ids exactos y sin tocar ficheros.
 *
 * Aislamiento: base **en memoria** (`Room.inMemoryDatabaseBuilder`), sin base de usuario, sin
 * ficheros, sin imagenes reales, sin UI ni SAF. El estado previo del agregado V2 se siembra por SQL
 * directo (independiente del codigo bajo prueba): `AngleAnalysisV2Dao` sigue siendo de solo lectura y
 * el restore no abre ninguna via de escritura de producto.
 *
 * Lo que se prueba:
 *  - roundtrip completo con varias raices/fotogramas/marcas, dueno `null` y real, nombres, colores,
 *    visibilidad, ordinales, indices/PTS asignados frente a reportados e integridad de imagen;
 *  - que el estado anterior (legacy y V2) se **reemplaza**, no se fusiona, y que ids numericos
 *    iguales en tablas distintas conviven (la identidad incluye la tabla);
 *  - que un manifiesto invalido falla **antes** de mutar una sola fila;
 *  - que un fallo inyectado en la insercion legacy, en la de fotogramas y en la de marcas deshace
 *    **toda** la transaccion (las tres tablas V2 y las veinte legacy vuelven al estado previo);
 *  - que el gate v1 sigue cerrado tras restaurar V2 y se reabre con un manifiesto de V2 vacio;
 *  - que restaurar dos veces el mismo manifiesto es idempotente;
 *  - que el restore se serializa con otra transaccion de escritura y que una restauracion cancelada
 *    antes de entrar no deja rastro;
 *  - que la **segunda confirmacion** del agregado V2 se exige para cualquier manifiesto: sin ella un
 *    agregado actual no vacio bloquea (vacio atomico) y una instantanea obsoleta no borra nada,
 *    incluso con los mismos conteos.
 *
 * Lo que **no** prueba (y el DAO tampoco promete): existencia o durabilidad de los PNG. Aqui no hay
 * ficheros: `imageKey`/`imageSha256` son solo columnas. La publicacion y verificacion de medios y la
 * exclusion de save/delete/restore/backup pertenecen al futuro coordinador.
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupRestoreDaoInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()

    @After fun close() = database.close()

    private val dao get() = database.manualBackupDao()

    @Test
    fun restoreReplacesLegacyAndAngleV2WithExactIdsAndNoLeftovers() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        // La captura real (G3a) produce el manifiesto que se va a restaurar.
        val manifest = dao.snapshotWithAngleMedia()
        assertEquals(2, manifest.angleV2.roots.size)

        // La base cambia por completo antes del restore: otro documento legacy y otro agregado V2.
        clearAngleV2BySql()
        dao.replaceAll(previousLegacyDocument())
        seedAngleV2BySql(staleV2())

        restoreConfirmed(manifest)

        val restored = dao.snapshotWithAngleMedia()
        // Payload completo identico al manifiesto: ni una fila de mas, de menos o renumerada.
        assertEquals(manifest.legacy.tables, restored.legacy.tables)
        assertEquals(manifest.angleV2, restored.angleV2)
        // Identidades exactas (no autogeneradas) y orden estable.
        assertEquals(listOf(1L, 2L), restored.angleV2.roots.map { it.id })
        assertEquals(listOf("session-1", "session-2"), restored.angleV2.roots.map { it.sessionKey })
        assertEquals(listOf(1L, null), restored.angleV2.roots.map { it.athleteId })
        assertEquals(listOf(3, 0), restored.angleV2.roots.map { it.revision })
        assertEquals(listOf(11L, 12L, 13L), restored.angleV2.frames.map { it.id })
        assertEquals(listOf(1L, 2L, 2L), restored.angleV2.frames.map { it.rootId })
        assertEquals(listOf(0, 0, 1), restored.angleV2.frames.map { it.ordinal })
        assertEquals(listOf(5, 9, 11), restored.angleV2.frames.map { it.assignedFrameIndex })
        assertEquals(listOf(7, null, 12), restored.angleV2.frames.map { it.reportedFrameIndex })
        assertEquals(listOf(500_000L, 900_000L, 1_100_000L), restored.angleV2.frames.map { it.assignedPtsUs })
        assertEquals(listOf(700_000L, null, 1_200_000L), restored.angleV2.frames.map { it.reportedPtsUs })
        // Asignado frente a reportado: lo no verificado sigue `null` en la FILA, no solo en el JSON.
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE reportedPtsUs IS NOT NULL AND id = 12"))
        // Integridad de imagen tal cual la declaro el manifiesto.
        assertEquals(listOf(KEY_A, KEY_B, KEY_C), restored.angleV2.frames.map { it.imageKey })
        assertEquals(listOf(4_096L, 8_192L, 1_024L), restored.angleV2.frames.map { it.imageByteCount })
        assertEquals(listOf(SHA_A, SHA_B, SHA_C), restored.angleV2.frames.map { it.imageSha256 })
        assertEquals(listOf(2, 1, 1), restored.angleV2.frames.map { it.geometryVersion })
        assertEquals(listOf(0, 90, 90), restored.angleV2.frames.map { it.rotationDegrees })
        // Marcas: orden, nombre, color y visibilidad elegidos por el usuario.
        assertEquals(listOf(101L, 102L, 103L, 104L), restored.angleV2.marks.map { it.id })
        assertEquals(listOf(11L, 11L, 12L, 13L), restored.angleV2.marks.map { it.frameId })
        assertEquals(listOf(0, 1, 0, 0), restored.angleV2.marks.map { it.ordinal })
        assertEquals(listOf("rodilla", "cadera", "tobillo", "hombro"), restored.angleV2.marks.map { it.name })
        assertEquals(listOf(true, false, true, true), restored.angleV2.marks.map { it.visible })
        assertEquals(
            listOf(0xFFFF3366.toInt(), 0x7F102030, 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            restored.angleV2.marks.map { it.colorArgb },
        )
        // Nada del estado intermedio sobrevive.
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_roots WHERE sessionKey = 'stale-session'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE id = 771"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_marks WHERE id = 772"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM athletes"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM attempts"))
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertTrue(foreignKeysClean())
    }

    /**
     * La identidad de un id incluye la tabla: `angle_analysis_v2_*` y las tablas legacy pueden tener
     * el mismo numero sin pisarse ni violar ninguna FK (contrato de `AngleAnalysisV2Entities`).
     */
    @Test
    fun numericIdsMayCoincideBetweenLegacyTablesAndAngleV2() = runBlocking {
        // Legacy: atleta 1, evaluacion 2, intento 3. V2: raiz 2, fotograma 3, marca 2.
        val colliding = MediaBackupAngleV2(
            roots = listOf(root(id = 2L, sessionKey = "collision-session", athleteId = 1L)),
            frames = listOf(frame(id = 3L, rootId = 2L, ordinal = 0, imageKey = KEY_A, sha256 = SHA_A)),
            marks = listOf(mark(id = 2L, frameId = 3L, ordinal = 0, name = "rodilla")),
            images = listOf(image(KEY_A, byteCount = 4_096L, sha256 = SHA_A)),
        )

        restoreConfirmed(mediaBackupManifest(colliding))

        val restored = dao.snapshotWithAngleMedia()
        assertEquals(colliding, restored.angleV2)
        assertEquals(listOf(1L), restored.legacy.tables.athletes.map { it.id })
        assertEquals(listOf(2L), restored.legacy.tables.assessments.map { it.id })
        assertEquals(listOf(3L), restored.legacy.tables.attempts.map { it.id })
        // Las dos filas con el mismo numero existen, cada una en su tabla.
        assertEquals(1, scalar("SELECT COUNT(*) FROM athletes WHERE id = 1"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM assessments WHERE id = 2"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM attempts WHERE id = 3"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM angle_analysis_v2_roots WHERE id = 2"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM angle_analysis_v2_frames WHERE id = 3"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM angle_analysis_v2_marks WHERE id = 2"))
        assertEquals(1L, restored.angleV2.roots.single().athleteId)
        assertTrue(foreignKeysClean())
    }

    /**
     * Un manifiesto invalido no borra ni inserta nada: la validacion va antes del primer DELETE y la
     * transaccion no llega a mutar. Se incluyen ids/claves duplicados, huerfanos, dueno inexistente,
     * ordinales incompletos, integridad de imagen y versiones.
     */
    @Test
    fun invalidManifestFailsBeforeDeletingOrInsertingAnything() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val before = dao.snapshotWithAngleMedia()
        val v2 = before.angleV2

        val invalid = listOf(
            "raiz duplicada" to v2.copy(roots = v2.roots + v2.roots.first().copy(sessionKey = "session-duplicate")),
            "sessionKey duplicada" to v2.copy(roots = v2.roots.map { it.copy(sessionKey = "same") }),
            "fotograma duplicado" to v2.copy(frames = v2.frames + v2.frames.first().copy(imageKey = STALE_KEY)),
            "fotograma huerfano" to v2.copy(frames = v2.frames.map { it.copy(rootId = 99L) }),
            "marca huerfana" to v2.copy(marks = v2.marks.map { it.copy(frameId = 99L) }),
            "dueno inexistente" to v2.copy(roots = v2.roots.map { it.copy(athleteId = 99L) }),
            "marca sin ordinal completo" to v2.copy(marks = v2.marks.map { if (it.id == 101L) it.copy(ordinal = 5) else it }),
            "imagen declarada ausente" to v2.copy(images = v2.images.drop(1)),
            "imagen que no cuadra" to v2.copy(images = v2.images.map { if (it.imageKey == KEY_A) it.copy(byteCount = 1L) else it }),
            "clave de imagen duplicada" to v2.copy(
                frames = v2.frames.map { it.copy(imageKey = KEY_A) },
                images = listOf(image(KEY_A, byteCount = 4_096L, sha256 = SHA_A)),
            ),
            "sha no canonico" to v2.copy(
                frames = v2.frames.map { if (it.id == 11L) it.copy(imageSha256 = "sha256:nope") else it },
                images = v2.images.map { if (it.imageKey == KEY_A) it.copy(sha256 = "sha256:nope") else it },
            ),
            "id de marca no positivo" to v2.copy(marks = v2.marks.map { it.copy(id = 0L) }),
        )

        invalid.forEach { (label, badV2) ->
            assertThrows(label, IllegalArgumentException::class.java) {
                runBlocking { dao.replaceAllWithAngleMedia(before.copy(angleV2 = badV2)) }
            }
            val after = dao.snapshotWithAngleMedia()
            assertEquals("$label: el legacy no puede cambiar", before.legacy.tables, after.legacy.tables)
            assertEquals("$label: el V2 no puede cambiar", before.angleV2, after.angleV2)
            assertTrue("$label: sin violaciones de FK", foreignKeysClean())
        }
        // Version y contrato tambien se rechazan antes de tocar filas.
        listOf(
            before.copy(formatVersion = 2),
            before.copy(sourceRoomSchemaVersion = 13),
            before.copy(legacy = before.legacy.copy(mediaIncluded = true)),
        ).forEach { badManifest ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { dao.replaceAllWithAngleMedia(badManifest) }
            }
            assertEquals(before.legacy.tables, dao.snapshotWithAngleMedia().legacy.tables)
            assertEquals(before.angleV2, dao.snapshotWithAngleMedia().angleV2)
        }
        // El gate v1 sigue cerrado: V2 nunca se vacio.
        assertThrows(ManualBackupV2BlockedException::class.java) { runBlocking { dao.snapshot() } }
        // assertThrows devuelve la excepcion capturada: este Unit mantiene `= runBlocking` como Unit
        // (JUnit4 exige que los metodos @Test devuelvan void).
        Unit
    }

    @Test
    fun legacyInsertFailureRollsBackTheWholeTransaction() = runBlocking {
        assertInjectedFailureRollsBackEverything("attempts")
    }

    @Test
    fun angleV2FrameInsertFailureRollsBackLegacyAndV2() = runBlocking {
        assertInjectedFailureRollsBackEverything("angle_analysis_v2_frames")
    }

    @Test
    fun angleV2MarkInsertFailureRollsBackLegacyAndV2() = runBlocking {
        assertInjectedFailureRollsBackEverything("angle_analysis_v2_marks")
    }

    /**
     * Prueba de rollback exacto: se siembra un estado (legacy A + V2 A), se captura, se inyecta un
     * fallo SQLite en la tabla indicada y se intenta restaurar otro estado (legacy B + V2 B). Tras el
     * fallo, **todas** las tablas deben contener exactamente el estado previo, no una mezcla.
     *
     * El orden real del restore es: borrar V2 -> borrar+insertar legacy (con sus comprobaciones) ->
     * insertar V2. Por eso un fallo en `attempts` cae despues de borrar V2 y parte del legacy, uno en
     * `angle_analysis_v2_frames` despues de haber restaurado el legacy entero, y uno en
     * `angle_analysis_v2_marks` al final: los tres demuestran que el legacy y el V2 comparten la
     * misma transaccion y que Room la deshace completa.
     */
    private suspend fun assertInjectedFailureRollsBackEverything(table: String) {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val before = dao.snapshotWithAngleMedia()
        val target = mediaBackupManifest(targetV2(), legacy = previousLegacyDocument())

        failOnInsert(table)
        assertThrows("fallo inyectado en $table", SQLException::class.java) {
            runBlocking { restoreConfirmed(target) }
        }
        dropFailureTrigger(table)

        val after = dao.snapshotWithAngleMedia()
        assertEquals("$table: legacy intacto", before.legacy.tables, after.legacy.tables)
        assertEquals("$table: V2 intacto", before.angleV2, after.angleV2)
        assertEquals("$table: conteos V2 intactos", Triple(2, 3, 4), angleV2Counts())
        assertTrue("$table: sin violaciones de FK", foreignKeysClean())
        assertEquals("$table: el atleta previo sigue siendo el unico", 1, scalar("SELECT COUNT(*) FROM athletes"))
        // La conexion sigue usable y el restore correcto funciona despues del rollback.
        restoreConfirmed(target)
        val restored = dao.snapshotWithAngleMedia()
        assertEquals(sqlOrdered(target.legacy.tables), restored.legacy.tables)
        assertEquals(target.angleV2, restored.angleV2)
        assertTrue(foreignKeysClean())
    }

    /**
     * Tras un restore con V2 el camino v1 sigue fail-closed (snapshot y replaceAll), y solo un
     * manifiesto con V2 vacio deja la base como la dejaria el restore v1 y reabre ese camino.
     */
    @Test
    fun v1GateStaysClosedAfterMediaRestoreAndReopensOnlyWithEmptyAngleV2() = runBlocking {
        val manifest = mediaBackupManifest(defaultV2())
        dao.replaceAllWithAngleMedia(manifest)

        val blockedSnapshot = assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.snapshot() }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 2, frames = 3, marks = 4), blockedSnapshot.rows)
        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.replaceAll(legacyDocument()) }
        }
        // El gate no relajado no ha borrado nada.
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertEquals(manifest.angleV2, dao.snapshotWithAngleMedia().angleV2)

        // Manifiesto con V2 vacio: el camino v1 se reabre con el mismo payload legacy. El agregado
        // actual es el que se acaba de restaurar, asi que se confirma igual que en producto.
        restoreConfirmed(manifest.copy(angleV2 = MediaBackupAngleV2()))
        assertEquals(Triple(0, 0, 0), angleV2Counts())
        assertEquals(sqlOrdered(manifest.legacy.tables), dao.snapshot().tables)
        assertTrue(foreignKeysClean())
    }

    @Test
    fun restoringTheSameManifestTwiceIsIdempotent() = runBlocking {
        val manifest = mediaBackupManifest(defaultV2())
        dao.replaceAllWithAngleMedia(manifest)
        val first = dao.snapshotWithAngleMedia()
        // Segunda aplicacion: el agregado actual ya no esta vacio, asi que se confirma su instantanea.
        restoreConfirmed(manifest)
        val second = dao.snapshotWithAngleMedia()

        assertEquals(first.legacy.tables, second.legacy.tables)
        assertEquals(first.angleV2, second.angleV2)
        assertEquals(sqlOrdered(manifest.legacy.tables), second.legacy.tables)
        assertEquals(manifest.angleV2, second.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertEquals(1, scalar("SELECT COUNT(*) FROM athletes"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM angle_analysis_v2_marks WHERE id NOT IN (101, 102, 103, 104)"))
        assertTrue(foreignKeysClean())
    }

    /**
     * El restore comparte la serializacion de Room: mientras otra transaccion de escritura esta
     * abierta no puede completarse (no se observa un estado a medias); al liberarla, aplica entera.
     */
    @Test
    fun restoreSerializesWithAnotherWriterTransaction() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val target = mediaBackupManifest(targetV2(), legacy = previousLegacyDocument())
        // La segunda confirmacion se captura ANTES de abrir la otra transaccion: con ella abierta la
        // base en memoria (una sola conexion) no atiende ninguna otra lectura transaccional y este
        // `@Transaction` se quedaria esperando para siempre, colgando el test en vez del restore.
        val confirmed = dao.angleV2AggregateSnapshot()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            database.withTransaction {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        try {
            withTimeout(5_000) { writerEntered.await() }

            val restore = async(Dispatchers.IO) { dao.replaceAllWithAngleMedia(target, confirmed) }
            assertNull(
                "el restore no puede completarse con otra transaccion de escritura abierta",
                withTimeoutOrNull(750) { restore.await() },
            )

            releaseWriter.complete(Unit)
            withTimeout(5_000) { writer.join() }
            withTimeout(10_000) { restore.await() }
        } finally {
            // La transaccion ajena se libera siempre: si una asercion o un timeout cortan el test, el
            // escritor no puede quedarse reteniendo la conexion.
            releaseWriter.complete(Unit)
            withTimeoutOrNull(5_000) { writer.join() }
        }

        val restored = dao.snapshotWithAngleMedia()
        assertEquals(sqlOrdered(target.legacy.tables), restored.legacy.tables)
        assertEquals(target.angleV2, restored.angleV2)
        assertTrue(foreignKeysClean())
    }

    /**
     * Cancelacion: una restauracion que aun no ha entrado en la base (otra escritura mantiene la
     * conexion) se cancela sin dejar rastro. No se puede forzar de forma determinista una cancelacion
     * a mitad del cuerpo, pero ese caso usa la misma salida de emergencia que los fallos inyectados de
     * arriba (`withTransaction` deshace la transaccion completa tambien con `CancellationException`).
     */
    @Test
    fun cancelledRestoreLeavesTheDatabaseUntouched() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val before = dao.snapshotWithAngleMedia()
        val target = mediaBackupManifest(targetV2(), legacy = previousLegacyDocument())
        // La segunda confirmacion se captura ANTES de abrir la otra transaccion: con ella abierta la
        // base en memoria (una sola conexion) no atiende ninguna otra lectura transaccional y este
        // `@Transaction` se quedaria esperando para siempre, colgando el test en vez del restore.
        val confirmed = dao.angleV2AggregateSnapshot()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            database.withTransaction {
                writerEntered.complete(Unit)
                releaseWriter.await()
            }
        }
        try {
            withTimeout(5_000) { writerEntered.await() }

            val restore = async(Dispatchers.IO) { dao.replaceAllWithAngleMedia(target, confirmed) }
            // Ni siquiera puede entrar: la escritura ajena retiene la conexion, asi que no hay nada
            // que deshacer y la cancelacion no puede dejar rastro.
            assertNull(
                "el restore no puede completarse con otra transaccion de escritura abierta",
                withTimeoutOrNull(500) { restore.await() },
            )
            restore.cancel()
            releaseWriter.complete(Unit)
            withTimeout(5_000) { writer.join() }
            withTimeoutOrNull(5_000) { restore.join() }
        } finally {
            // La transaccion ajena se libera siempre: si una asercion o un timeout cortan el test, el
            // escritor no puede quedarse reteniendo la conexion.
            releaseWriter.complete(Unit)
            withTimeoutOrNull(5_000) { writer.join() }
        }

        val after = dao.snapshotWithAngleMedia()
        assertEquals(before.legacy.tables, after.legacy.tables)
        assertEquals(before.angleV2, after.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertTrue(foreignKeysClean())
    }

    // --- Segunda confirmacion del agregado V2 -----------------------------------------------

    /**
     * Un manifiesto con el agregado **vacio** (lo que produce un ZIP sin analisis) tambien sustituye
     * el agregado actual: sin la instantanea confirmada no se borra ni una fila.
     */
    @Test
    fun emptyAngleV2ManifestIsBlockedWithoutConfirmationWhenAnAggregateExists() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val before = dao.snapshotWithAngleMedia()
        val target = mediaBackupManifest(MediaBackupAngleV2(), legacy = previousLegacyDocument())

        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.replaceAllWithAngleMedia(target) }
        }
        val after = dao.snapshotWithAngleMedia()
        assertEquals("legacy intacto", before.legacy.tables, after.legacy.tables)
        assertEquals("V2 intacto", before.angleV2, after.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertTrue(foreignKeysClean())
    }

    /**
     * Contenido distinto con **los mismos conteos**: la huella completa cambia, asi que la
     * instantanea confirmada ya no vale y el restore falla cerrado sin borrar nada.
     */
    @Test
    fun staleConfirmationWithTheSameCountsIsRejectedWithoutDeletingAnything() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val confirmed = dao.angleV2AggregateSnapshot()
        // Mismo numero de filas, otro contenido (nombres de marca distintos).
        exec("UPDATE angle_analysis_v2_marks SET name = name || '-otro'")
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        // Estado real justo antes del restore: contra este se exige que todo siga "intacto".
        val beforeRestore = dao.snapshotWithAngleMedia()
        val mutated = dao.angleV2AggregateSnapshot()
        assertEquals("los conteos no cambian", confirmed.counts, mutated.counts)
        assertTrue("la huella cambio aunque los conteos no", mutated != confirmed)

        val target = mediaBackupManifest(MediaBackupAngleV2(), legacy = previousLegacyDocument())
        val changed = assertThrows(ManualBackupAngleV2ChangedException::class.java) {
            runBlocking { dao.replaceAllWithAngleMedia(target, confirmed) }
        }
        // El rechazo se detecta sobre el estado inmediatamente anterior, no sobre el confirmado.
        assertEquals(mutated, changed.observed)
        assertEquals("los conteos no cambian", confirmed.counts, changed.observed.counts)
        val after = dao.snapshotWithAngleMedia()
        assertEquals("legacy intacto", beforeRestore.legacy.tables, after.legacy.tables)
        assertEquals("V2 intacto", beforeRestore.angleV2, after.angleV2)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        assertTrue(foreignKeysClean())
    }

    /**
     * Advertencia **nula** (agregado vacio) y agregado que aparece antes de aplicar: el vacio no se
     * supone, se comprueba de forma atomica en la misma transaccion que borraria.
     */
    @Test
    fun emptyConfirmationIsRecheckedAtomicallyBeforeDeleting() = runBlocking {
        dao.replaceAll(legacyDocument())
        val confirmedEmpty = dao.angleV2AggregateSnapshot()
        assertTrue("la advertencia nula exige agregado vacio", confirmedEmpty.counts.isEmpty)
        seedAngleV2BySql(defaultV2())

        val target = mediaBackupManifest(MediaBackupAngleV2(), legacy = previousLegacyDocument())
        val changed = assertThrows(ManualBackupAngleV2ChangedException::class.java) {
            runBlocking { dao.replaceAllWithAngleMedia(target, confirmedEmpty) }
        }
        assertEquals(AngleAnalysisV2RowCounts(roots = 2, frames = 3, marks = 4), changed.observed.counts)
        assertEquals(Triple(2, 3, 4), angleV2Counts())
        // Sin confirmacion tampoco se asume el vacio.
        assertThrows(ManualBackupV2BlockedException::class.java) {
            runBlocking { dao.replaceAllWithAngleMedia(target) }
        }
        assertEquals(Triple(2, 3, 4), angleV2Counts())
    }

    /** Con la instantanea exacta que el usuario vio, el restore si se aplica (no es un bloqueo duro). */
    @Test
    fun matchingConfirmationAppliesTheRestore() = runBlocking {
        dao.replaceAll(legacyDocument())
        seedAngleV2BySql(defaultV2())
        val confirmed = dao.angleV2AggregateSnapshot()
        val target = mediaBackupManifest(targetV2(), legacy = previousLegacyDocument())

        dao.replaceAllWithAngleMedia(target, confirmed)

        val restored = dao.snapshotWithAngleMedia()
        assertEquals(sqlOrdered(target.legacy.tables), restored.legacy.tables)
        assertEquals(target.angleV2, restored.angleV2)
        assertTrue(foreignKeysClean())
    }

    // --- Utilidades ------------------------------------------------------------------------------

    /**
     * Restore con la segunda confirmacion real: la instantanea del agregado actual se lee justo
     * antes, igual que hace producto despues de la advertencia. El gate en si (huella distinta con
     * los mismos conteos, agregado que aparece cuando la advertencia fue nula) se prueba en los
     * tests de "Segunda confirmacion del agregado V2" de este mismo fichero.
     */
    private suspend fun restoreConfirmed(manifest: MediaBackupManifest) =
        dao.replaceAllWithAngleMedia(manifest, dao.angleV2AggregateSnapshot())

    private fun exec(sql: String, args: Array<Any?> = emptyArray()) {
        database.openHelper.writableDatabase.execSQL(sql, args)
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

    private fun clearAngleV2BySql() = exec("DELETE FROM angle_analysis_v2_roots")

    /**
     * `attemptEvents` se captura con el orden SQL (`ORDER BY attemptId, type, ordinal`, colacion
     * TEXT), no con el orden del JSON, asi que un documento construido a mano solo es comparable con
     * una captura despues de aplicar ese mismo orden. El contenido no se toca: mismo numero de filas
     * y mismos valores.
     */
    private fun sqlOrdered(tables: ManualBackupTables): ManualBackupTables = tables.copy(
        attemptEvents = tables.attemptEvents.sortedWith(
            compareBy({ it.attemptId }, { it.type }, { it.ordinal }),
        ),
    )

    private fun failOnInsert(table: String) = exec(
        "CREATE TRIGGER fail_${table}_insert BEFORE INSERT ON $table " +
            "BEGIN SELECT RAISE(ABORT, 'fallo inyectado en $table'); END",
    )

    private fun dropFailureTrigger(table: String) = exec("DROP TRIGGER fail_${table}_insert")

    /**
     * Siembra el agregado V2 por SQL directo, reproduciendo las columnas de Room 14 a partir de los
     * mismos modelos del manifiesto. No se abre ninguna via de escritura de producto y el estado
     * previo no depende del codigo bajo prueba.
     */
    private fun seedAngleV2BySql(v2: MediaBackupAngleV2) {
        v2.roots.forEach { root ->
            exec(
                "INSERT INTO angle_analysis_v2_roots (id, analysisFormat, sessionKey, athleteId, createdAt, updatedAt, revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    root.id, root.analysisFormat, root.sessionKey, root.athleteId, root.createdAt, root.updatedAt, root.revision,
                ),
            )
        }
        v2.frames.forEach { frame ->
            exec(
                "INSERT INTO angle_analysis_v2_frames (id, rootId, ordinal, assignedFrameIndex, reportedFrameIndex, " +
                    "assignedPtsUs, reportedPtsUs, encodedWidth, encodedHeight, presentationWidth, presentationHeight, " +
                    "rotationDegrees, geometryVersion, imageKey, imageWidth, imageHeight, imageByteCount, imageSha256, imageMimeType) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    frame.id, frame.rootId, frame.ordinal, frame.assignedFrameIndex, frame.reportedFrameIndex,
                    frame.assignedPtsUs, frame.reportedPtsUs, frame.encodedWidth, frame.encodedHeight,
                    frame.presentationWidth, frame.presentationHeight, frame.rotationDegrees, frame.geometryVersion,
                    frame.imageKey, frame.imageWidth, frame.imageHeight, frame.imageByteCount, frame.imageSha256,
                    frame.imageMimeType,
                ),
            )
        }
        v2.marks.forEach { mark ->
            exec(
                "INSERT INTO angle_analysis_v2_marks (id, frameId, ordinal, name, colorArgb, visible, " +
                    "pointAX, pointAY, pointBX, pointBY, pointCX, pointCY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    mark.id, mark.frameId, mark.ordinal, mark.name, mark.colorArgb, if (mark.visible) 1 else 0,
                    mark.pointAX, mark.pointAY, mark.pointBX, mark.pointBY, mark.pointCX, mark.pointCY,
                ),
            )
        }
    }

    // --- Fixtures --------------------------------------------------------------------------------

    private fun legacyDocument(): ManualBackupDocument = ManualBackupCodec.decode(LEGACY_JSON)

    /** Documento legacy distinto (mismo esquema): sirve para probar que se reemplaza, no se fusiona. */
    private fun previousLegacyDocument(): ManualBackupDocument = legacyDocument().let { document ->
        document.copy(
            tables = document.tables.copy(
                athletes = document.tables.athletes.map { it.copy(displayName = "previous athlete") },
                attempts = document.tables.attempts.map { it.copy(detectedFps = 30) },
            ),
        )
    }

    private fun mediaBackupManifest(
        angleV2: MediaBackupAngleV2,
        legacy: ManualBackupDocument = legacyDocument(),
    ) = MediaBackupManifest(
        contract = MEDIA_BACKUP_CONTRACT,
        formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1_700_000_000_000L,
        legacy = legacy,
        angleV2 = angleV2,
    )

    /** Agregado V2 de referencia: dos raices (una sin dueno), tres fotogramas y cuatro marcas. */
    private fun defaultV2() = MediaBackupAngleV2(
        roots = listOf(
            root(id = 1L, sessionKey = "session-1", athleteId = 1L, createdAt = 10L, updatedAt = 20L, revision = 3),
            root(id = 2L, sessionKey = "session-2", athleteId = null, createdAt = 30L, updatedAt = 40L),
        ),
        frames = listOf(
            frame(
                id = 11L, rootId = 1L, ordinal = 0, imageKey = KEY_A, sha256 = SHA_A,
                assignedFrameIndex = 5, reportedFrameIndex = 7,
                assignedPtsUs = 500_000L, reportedPtsUs = 700_000L, geometryVersion = 2,
            ),
            frame(
                id = 12L, rootId = 2L, ordinal = 0, imageKey = KEY_B, sha256 = SHA_B,
                assignedFrameIndex = 9, reportedFrameIndex = null,
                assignedPtsUs = 900_000L, reportedPtsUs = null,
                byteCount = 8_192L, rotated = true, geometryVersion = 1,
            ),
            frame(
                id = 13L, rootId = 2L, ordinal = 1, imageKey = KEY_C, sha256 = SHA_C,
                assignedFrameIndex = 11, reportedFrameIndex = 12,
                assignedPtsUs = 1_100_000L, reportedPtsUs = 1_200_000L,
                byteCount = 1_024L, rotated = true, geometryVersion = 1,
            ),
        ),
        marks = listOf(
            mark(id = 101L, frameId = 11L, ordinal = 0, name = "rodilla"),
            mark(id = 102L, frameId = 11L, ordinal = 1, name = "cadera", colorArgb = 0x7F102030, visible = false),
            mark(id = 103L, frameId = 12L, ordinal = 0, name = "tobillo", colorArgb = 0xFF00FF00.toInt()),
            mark(id = 104L, frameId = 13L, ordinal = 0, name = "hombro", colorArgb = 0xFF0000FF.toInt()),
        ),
        // Orden canonico de clave, el mismo que produce la captura (`sortedBy imageKey`).
        images = listOf(
            image(KEY_A, byteCount = 4_096L, sha256 = SHA_A),
            image(KEY_C, byteCount = 1_024L, sha256 = SHA_C),
            image(KEY_B, byteCount = 8_192L, sha256 = SHA_B),
        ),
    )

    /** Agregado que debe desaparecer: otras identidades, otra sesion y otra marca. */
    private fun staleV2() = MediaBackupAngleV2(
        roots = listOf(root(id = 77L, sessionKey = "stale-session", athleteId = null)),
        frames = listOf(frame(id = 771L, rootId = 77L, ordinal = 0, imageKey = STALE_KEY, sha256 = SHA_STALE)),
        marks = listOf(mark(id = 772L, frameId = 771L, ordinal = 0, name = "stale")),
        images = listOf(image(STALE_KEY, byteCount = 4_096L, sha256 = SHA_STALE)),
    )

    /**
     * Mismo grafo valido que [defaultV2] pero con contenido distinto (sesiones y nombres): asi
     * cualquier escritura a medias del restore es inconfundible al comparar con el estado previo.
     */
    private fun targetV2() = defaultV2().let { v2 ->
        v2.copy(
            roots = v2.roots.map { it.copy(sessionKey = "${it.sessionKey}-restored") },
            marks = v2.marks.map { it.copy(name = "${it.name}-restored") },
        )
    }

    private fun root(
        id: Long,
        sessionKey: String,
        athleteId: Long?,
        createdAt: Long = 1L,
        updatedAt: Long = 2L,
        revision: Int = 0,
    ) = MediaBackupRoot(
        id = id,
        analysisFormat = ANGLE_ANALYSIS_V2_FORMAT,
        sessionKey = sessionKey,
        athleteId = athleteId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
    )

    private fun frame(
        id: Long,
        rootId: Long,
        ordinal: Int,
        imageKey: String,
        sha256: String,
        assignedFrameIndex: Int = 5,
        reportedFrameIndex: Int? = null,
        assignedPtsUs: Long = 500_000L,
        reportedPtsUs: Long? = null,
        byteCount: Long = 4_096L,
        rotated: Boolean = false,
        geometryVersion: Int = 1,
    ) = MediaBackupFrame(
        id = id,
        rootId = rootId,
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = if (rotated) 1_920 else 640,
        encodedHeight = if (rotated) 1_080 else 480,
        presentationWidth = if (rotated) 1_080 else 640,
        presentationHeight = if (rotated) 1_920 else 480,
        rotationDegrees = if (rotated) 90 else 0,
        geometryVersion = geometryVersion,
        imageKey = imageKey,
        imageWidth = if (rotated) 540 else 640,
        imageHeight = if (rotated) 960 else 480,
        imageByteCount = byteCount,
        imageSha256 = sha256,
        imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
    )

    private fun mark(
        id: Long,
        frameId: Long,
        ordinal: Int,
        name: String,
        colorArgb: Int = 0xFFFF3366.toInt(),
        visible: Boolean = true,
        pointAX: Double = 10.0,
        pointAY: Double = 10.0,
        pointBX: Double = 100.0,
        pointBY: Double = 100.0,
        pointCX: Double = 200.0,
        pointCY: Double = 10.0,
    ) = MediaBackupMark(
        id = id,
        frameId = frameId,
        ordinal = ordinal,
        name = name,
        colorArgb = colorArgb,
        visible = visible,
        pointAX = pointAX,
        pointAY = pointAY,
        pointBX = pointBX,
        pointBY = pointBY,
        pointCX = pointCX,
        pointCY = pointCY,
    )

    private fun image(imageKey: String, byteCount: Long, sha256: String) =
        MediaBackupImage(imageKey = imageKey, byteCount = byteCount, sha256 = sha256)

    private companion object {
        const val KEY_A = "00112233445566778899aabbccddeeff"
        const val KEY_B = "ffeeddccbbaa99887766554433221100"
        const val KEY_C = "0123456789abcdef0123456789abcdef"
        const val STALE_KEY = "abcdefabcdefabcdefabcdefabcdefab"
        const val SHA_A = "aa00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_B = "bb00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_C = "cc00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_STALE = "dd00000000000000000000000000000000000000000000000000000000000000"

        /** Documento legacy v1 valido (Room 10) con atleta 1, evaluacion 2 e intento 3. */
        const val LEGACY_JSON =
            """{"contract":"openjump-manual-backup","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"instrumented athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"instrumented-assessment","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}
