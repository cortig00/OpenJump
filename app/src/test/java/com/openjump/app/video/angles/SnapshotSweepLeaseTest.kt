package com.openjump.app.video.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM deterministas de la barrera de snapshots: barrido, reservas y carreras de cierre.
 *
 * El riesgo que fijan es concreto: el barrido decidía solo por `lastModified`, así que un reloj
 * adelantado, una restauración de backup o simplemente el paso de 24 h con una sesión abierta podía
 * borrar el fichero que una captura estaba a punto de abrir (la captura reabre por ruta, no reutiliza
 * un descriptor ya abierto). Aquí se comprueba, sin Android y sin tiempos de espera reales, que:
 *
 * - un snapshot reservado por una sesión viva sobrevive a un barrido con reloj muy adelantado;
 * - una copia en curso tampoco se barre aunque su temporal parezca viejo;
 * - los restos de un proceso muerto (sin reserva y viejos) sí se limpian, y nunca ficheros ajenos;
 * - cerrar durante una captura difiere el borrado y no permite abrir más capturas ni pedir frames.
 *
 * Los ficheros viven en directorios temporales del test; no se toca caché real ni datos de usuario.
 */
class SnapshotSweepLeaseTest {

    private val dayMillis = 24L * 60L * 60L * 1000L

    private val index = VideoFrameIndexSnapshot(
        frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L),
        durationUs = 160_000L,
        encodedWidth = 320,
        encodedHeight = 240,
        declaredRotationDegrees = 0,
    )

    private fun tempDirectory(): File = Files.createTempDirectory("angles_sweep_test").toFile()

    private fun seal(directory: File, bytes: Int = 2_048): SealedSourceSnapshot {
        val result = SourceSnapshotCopier.copy(
            input = ByteArrayInputStream(ByteArray(bytes) { (it % 97).toByte() }),
            declaredLengthBytes = bytes.toLong(),
            snapshotDirectory = directory,
        )
        assertTrue("La copia de prueba debe sellarse: $result", result is SnapshotSealResult.Sealed)
        return (result as SnapshotSealResult.Sealed).snapshot
    }

    @Test
    fun `un reloj adelantado no borra el snapshot de una sesion viva`() {
        val directory = tempDirectory()
        val sealed = seal(directory)
        val session = FrozenFrameSourceSession(sealed.identity, index, sealed)
        try {
            // El fichero parece de hace 48 h: el barrido lo vería "viejo" si solo mirase la edad.
            sealed.file.setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)
            val wallClockAhead = System.currentTimeMillis() + 72L * 60L * 60L * 1000L

            val deleted = SourceSnapshotCopier.sweepStale(directory, wallClockAhead, dayMillis)

            assertEquals("El barrido no debe borrar un snapshot reservado", 0, deleted)
            assertTrue("El snapshot de una sesión viva debe sobrevivir", sealed.file.isFile)
            assertTrue(session.isOpen)
            val granted = session.request(1)
            assertTrue("La sesión viva debe seguir entregando solicitudes: $granted", granted is FrameSourceRequestResult.Granted)
        } finally {
            session.close()
        }
        assertFalse("close() debe borrar el snapshot", sealed.file.exists())
        assertFalse("close() debe liberar la reserva", SnapshotLeases.isPinned(sealed.file))
    }

    @Test
    fun `una copia en curso no se barre aunque su temporal parezca viejo`() {
        val directory = tempDirectory()
        val staleTimestamp = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
        val wallClockAhead = System.currentTimeMillis() + 96L * 60L * 60L * 1000L
        var sweptDuringCopy = -1

        val payload = ByteArray(64_000) { (it % 251).toByte() }
        val slowStream = object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // "Reloj adelantado" durante la copia: el temporal se marca como si tuviera días.
                directory.listFiles()
                    ?.filter { it.name.startsWith(SourceSnapshotCopier.TEMP_PREFIX) }
                    ?.forEach { it.setLastModified(staleTimestamp) }
                sweptDuringCopy = SourceSnapshotCopier.sweepStale(directory, wallClockAhead, dayMillis)
                return super.read(buffer, offset, length)
            }
        }

        val result = SourceSnapshotCopier.copy(
            input = slowStream,
            declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
            snapshotDirectory = directory,
        )

        assertEquals("El barrido no puede llevarse una copia en curso", 0, sweptDuringCopy)
        assertTrue("La copia en curso debe completarse: $result", result is SnapshotSealResult.Sealed)
        val sealed = (result as SnapshotSealResult.Sealed).snapshot
        assertEquals(payload.size.toLong(), sealed.lengthBytes)
        assertTrue("El snapshot sellado debe existir", sealed.file.isFile)
        // La copia no creó sesión: se descarta y con ello se libera la reserva.
        sealed.discard()
        assertFalse(sealed.file.exists())
        assertFalse("Tras descartar no puede quedar una reserva", SnapshotLeases.isPinned(sealed.file))
        directory.deleteRecursively()
    }

    @Test
    fun `el barrido limpia restos de un proceso interrumpido y nada ajeno`() {
        val directory = tempDirectory()
        val staleTimestamp = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
        val wallClockAhead = System.currentTimeMillis() + 72L * 60L * 60L * 1000L
        // Umbral de 4 días: con el reloj 72 h adelantado, lo de hace 48 h (120 h de edad) caduca y lo
        // recién creado (72 h) no. Así el test distingue edad de reserva sin depender del reloj real.
        val maxAgeMillis = 96L * 60L * 60L * 1000L

        // Proceso muerto: snapshot y temporal sin reserva (la reserva es de proceso, así que un
        // proceso nuevo no la ve) y con antigüedad por encima del umbral.
        val deadSnapshot = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}dead.mp4").apply {
            writeBytes(ByteArray(16))
            setLastModified(staleTimestamp)
        }
        val deadTemp = File(directory, "${SourceSnapshotCopier.TEMP_PREFIX}dead.bin").apply {
            writeBytes(ByteArray(16))
            setLastModified(staleTimestamp)
        }
        // Fresco (no debe borrarse por edad), ajeno (otro nombre) y un directorio con prefijo propio.
        val freshSnapshot = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}fresh.mp4").apply {
            writeBytes(ByteArray(16))
        }
        val foreign = File(directory, "angles_source_notes.txt").apply {
            writeBytes(ByteArray(8))
            setLastModified(staleTimestamp)
        }
        val ownNamedDirectory = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}not_a_file").apply {
            mkdirs()
        }
        try {
            val deleted = SourceSnapshotCopier.sweepStale(directory, wallClockAhead, maxAgeMillis)

            assertEquals(2, deleted)
            assertFalse("El snapshot huérfano debe limpiarse", deadSnapshot.exists())
            assertFalse("El temporal huérfano debe limpiarse", deadTemp.exists())
            assertTrue("Un fichero fresco no se barre", freshSnapshot.isFile)
            assertTrue("Un fichero ajeno no se toca", foreign.isFile)
            assertTrue("Un directorio nunca se borra por el barrido", ownNamedDirectory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cerrar durante una captura difiere el borrado y no admite capturas ni solicitudes nuevas`() {
        val directory = tempDirectory()
        val file = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}live.mp4").apply { writeBytes(ByteArray(32)) }
        val lease = SnapshotLeases.pin(file)
        val lifecycle = SourceSessionLifecycle(file, lease)

        assertTrue("Debe poder empezar una captura", lifecycle.openCapture())
        assertEquals(1, lifecycle.activeCaptureCount)

        assertFalse("El cierre no borra mientras hay una captura en vuelo", lifecycle.close())
        assertTrue(lifecycle.isClosed)
        assertTrue("El fichero no puede desaparecer bajo una captura en vuelo", file.isFile)
        assertFalse("Una sesión cerrada no admite capturas nuevas", lifecycle.openCapture())
        // La captura en vuelo sigue contabilizada: la negativa no la descuenta.
        assertEquals(1, lifecycle.activeCaptureCount)

        lifecycle.releaseCapture()

        assertFalse("Al liberar la última captura se borra el snapshot", file.exists())
        assertFalse("Al cerrar se libera la reserva", SnapshotLeases.isPinned(file))
        // Idempotente: cerrar o liberar de nuevo no rompe ni resucita nada.
        assertFalse(lifecycle.close())
        lifecycle.releaseCapture()
        assertFalse(file.exists())
        directory.deleteRecursively()
    }

    @Test
    fun `una sesion cerrada rechaza solicitudes y descriptores con resultados tipados, no con excepciones`() {
        val directory = tempDirectory()
        val sealed = seal(directory, bytes = 1_024)
        val session = FrozenFrameSourceSession(sealed.identity, index, sealed)

        val granted = session.request(0)
        assertTrue("Con la sesión abierta la solicitud debe concederse: $granted", granted is FrameSourceRequestResult.Granted)
        assertTrue("Un índice fuera de rango se rechaza tipado", session.request(99) is FrameSourceRequestResult.Blocked)

        session.close()

        assertEquals(FrameFreezeRejectionReason.SOURCE_SESSION_INVALID, (session.request(0) as FrameSourceRequestResult.Blocked).reason)
        val blocked = session.openSnapshotDescriptor()
        assertTrue("El descriptor de una sesión cerrada se rechaza tipado", blocked is SnapshotDescriptorOpen.Blocked)
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
            (blocked as SnapshotDescriptorOpen.Blocked).result.reason,
        )
        // Cerrar dos veces es idempotente y la captura posterior no lanza.
        session.close()
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
            (session.capture((granted as FrameSourceRequestResult.Granted).request) as FrameFreezeResult.Blocked).reason,
        )
        directory.deleteRecursively()
    }

    @Test
    fun `una cache purgada se reporta como rechazo tipado en lugar de excepcion`() {
        val directory = tempDirectory()
        val sealed = seal(directory, bytes = 512)
        val session = FrozenFrameSourceSession(sealed.identity, index, sealed)
        try {
            // El sistema puede vaciar la caché: el fichero desaparece sin que la sesión se cierre.
            assertTrue(sealed.file.delete())
            val result = session.openSnapshotDescriptor()
            assertTrue("Un snapshot purgado debe rechazar el descriptor: $result", result is SnapshotDescriptorOpen.Blocked)
            assertEquals(
                FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                (result as SnapshotDescriptorOpen.Blocked).result.reason,
            )
        } finally {
            session.close()
            directory.deleteRecursively()
        }
    }
}
