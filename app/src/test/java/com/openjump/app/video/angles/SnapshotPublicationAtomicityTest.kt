package com.openjump.app.video.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM deterministas de la **publicación** del snapshot sellado.
 *
 * El riesgo que fijan es concreto: el copiador hacía `temp.renameTo(sealed)` y sólo después
 * `SnapshotLeases.retarget`. Entre las dos llamadas el fichero ya existía en su ruta final sin
 * reserva, así que un barrido concurrente (otra apertura, o el mismo con reloj adelantado) lo veía
 * viejo y sin reserva y lo borraba; el `retarget` posterior trasladaba la reserva a una ruta vacía.
 *
 * Aquí se demuestra, sin Android, sin esperas reales y sin depender del planificador:
 *
 * - un barrido ejecutado en el instante exactamente susceptible (dentro del cerrojo, justo tras el
 *   renombrado y antes de retirar la reserva del origen) no borra nada porque el destino ya está
 *   reservado antes de existir;
 * - un renombrado que falla no publica nada, no deja reserva doble y conserva la reserva del origen
 *   para que el `finally` de la copia la libere;
 * - un barrido concurrente real, arrancado desde ese mismo instante y bloqueado por el cerrojo,
 *   tampoco borra el snapshot ya publicado (que sigue reservado por la sesión);
 * - y, como testigo de que el interleaving no es vacuo, el orden antiguo (renombrar sin reservar el
 *   destino) sí pierde el snapshot ante el mismo barrido.
 */
class SnapshotPublicationAtomicityTest {

    private val maxAgeMillis = 24L * 60L * 60L * 1000L
    private val staleTimestamp = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
    private val wallClockAhead = System.currentTimeMillis() + 72L * 60L * 60L * 1000L

    private fun tempDirectory(): File = Files.createTempDirectory("angles_publish_test").toFile()

    private fun ownedFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter {
            it.name.startsWith(SourceSnapshotCopier.SNAPSHOT_PREFIX) ||
                it.name.startsWith(SourceSnapshotCopier.TEMP_PREFIX)
        }

    @Test
    fun `el barrido en el instante de publicacion no borra el snapshot`() {
        val directory = tempDirectory()
        val payload = ByteArray(4_096) { (it % 89).toByte() }
        var sweptInWindow = -1
        var pinnedInWindow = false
        var publishedName: String? = null

        val result = SourceSnapshotCopier.copy(
            input = ByteArrayInputStream(payload),
            declaredLengthBytes = payload.size.toLong(),
            snapshotDirectory = directory,
            publicationProbe = {
                // Instante exactamente susceptible: el fichero ya tiene su nombre final y la reserva
                // del origen todavía no se retiró. Antes de este cambio, aquí no había reserva del
                // destino y el barrido lo borraba.
                val published = ownedFiles(directory).filter { it.isFile }
                assertEquals("Debe existir exactamente el snapshot publicado", 1, published.size)
                val sealed = published.single()
                publishedName = sealed.name
                sealed.setLastModified(staleTimestamp)
                pinnedInWindow = SnapshotLeases.isPinned(sealed)
                sweptInWindow = SourceSnapshotCopier.sweepStale(directory, wallClockAhead, maxAgeMillis)
            },
        )

        assertTrue("La copia debe sellarse: $result", result is SnapshotSealResult.Sealed)
        assertEquals("El barrido no puede borrar el snapshot en publicación", 0, sweptInWindow)
        assertTrue("El destino ya debe estar reservado antes de retirar la reserva del origen", pinnedInWindow)
        val sealed = (result as SnapshotSealResult.Sealed).snapshot
        assertTrue("El snapshot publicado debe existir", sealed.file.isFile)
        assertEquals(publishedName, sealed.file.name)
        assertEquals(sealed.file.canonicalPath, sealed.lease.file.canonicalPath)
        assertTrue("La reserva debe estar en el destino", SnapshotLeases.isPinned(sealed.file))
        assertEquals("No puede quedar ninguna copia temporal", 0, ownedFiles(directory).count { it.name.startsWith(SourceSnapshotCopier.TEMP_PREFIX) })

        sealed.discard()
        assertFalse(sealed.file.exists())
        assertFalse("Descartar libera la reserva del destino", SnapshotLeases.isPinned(sealed.file))
        directory.deleteRecursively()
    }

    @Test
    fun `un renombrado fallido no publica nada ni deja reserva doble`() {
        val directory = tempDirectory()
        val source = File(directory, "${SourceSnapshotCopier.TEMP_PREFIX}pending.bin").apply {
            writeBytes(ByteArray(32))
        }
        val lease = SnapshotLeases.pin(source)
        // Destino ocupado por un directorio: rename(2) de fichero sobre directorio falla.
        val occupied = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}occupied.mp4").apply { mkdirs() }
        try {
            assertFalse(
                "Publicar sobre un destino no renombrable debe fallar",
                SnapshotLeases.publish(lease, source, occupied),
            )
            assertTrue("El origen sigue donde estaba", source.isFile)
            assertTrue("La reserva del origen se conserva para el finally", SnapshotLeases.isPinned(source))
            assertFalse("No puede quedar una reserva doble en el destino", SnapshotLeases.isPinned(occupied))
            assertEquals("La reserva no debe apuntar a un destino no publicado", source.canonicalPath, lease.file.canonicalPath)
            assertTrue("Un directorio ajeno no se toca", occupied.isDirectory)
        } finally {
            lease.release()
            directory.deleteRecursively()
        }
        assertFalse("Liberar la reserva la retira del origen", SnapshotLeases.isPinned(source))
    }

    @Test
    fun `un barrido concurrente espera al cerrojo y no borra el snapshot publicado`() {
        val directory = tempDirectory()
        val payload = ByteArray(2_048) { 7 }
        val swept = AtomicInteger(-1)
        val launched = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var sweeper: Thread? = null

        val result = SourceSnapshotCopier.copy(
            input = ByteArrayInputStream(payload),
            declaredLengthBytes = payload.size.toLong(),
            snapshotDirectory = directory,
            publicationProbe = {
                ownedFiles(directory).filter { it.isFile }.forEach { it.setLastModified(staleTimestamp) }
                // El barrido se lanza en la ventana susceptible; el cerrojo (reentrante para este
                // hilo) lo deja esperando hasta que la publicación terminó.
                sweeper = Thread {
                    launched.countDown()
                    swept.set(SourceSnapshotCopier.sweepStale(directory, wallClockAhead, maxAgeMillis))
                    finished.countDown()
                }.apply { start() }
                assertTrue("El barrido debe haberse lanzado", launched.await(5, TimeUnit.SECONDS))
            },
        )

        assertTrue("La copia debe sellarse: $result", result is SnapshotSealResult.Sealed)
        val thread = requireNotNull(sweeper) { "La prueba debía lanzar un barrido concurrente" }
        assertTrue("El barrido concurrente debe terminar", finished.await(5, TimeUnit.SECONDS))
        thread.join(5_000L)
        assertEquals("El barrido no puede llevarse el snapshot reservado", 0, swept.get())
        val sealed = (result as SnapshotSealResult.Sealed).snapshot
        assertTrue("El snapshot debe sobrevivir al barrido concurrente", sealed.file.isFile)
        assertTrue(SnapshotLeases.isPinned(sealed.file))

        sealed.discard()
        assertFalse(sealed.file.exists())
        directory.deleteRecursively()
    }

    @Test
    fun `el orden antiguo sin reservar el destino si perdia el snapshot`() {
        val directory = tempDirectory()
        val source = File(directory, "${SourceSnapshotCopier.TEMP_PREFIX}witness.bin").apply {
            writeBytes(ByteArray(64))
        }
        val destination = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}witness.mp4")
        val lease = SnapshotLeases.pin(source)
        try {
            // Secuencia retirada: renombrar y sólo después trasladar la reserva.
            assertTrue("El renombrado de la prueba debe funcionar", source.renameTo(destination))
            destination.setLastModified(staleTimestamp)
            val deleted = SourceSnapshotCopier.sweepStale(directory, wallClockAhead, maxAgeMillis)

            assertEquals(
                "El interleaving de la prueba sí borra un destino sin reserva: la prueba no es vacua",
                1,
                deleted,
            )
            assertFalse("Con el orden antiguo el snapshot recién publicado se perdía", destination.exists())
        } finally {
            lease.release()
            directory.deleteRecursively()
        }
        assertFalse("La reserva del origen no puede quedar colgada", SnapshotLeases.isPinned(source))
    }
}
