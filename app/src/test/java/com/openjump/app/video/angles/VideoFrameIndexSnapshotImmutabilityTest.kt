package com.openjump.app.video.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la inmutabilidad del índice sellado (hallazgo de la auditoría del padre).
 *
 * `VideoFrameIndexSnapshot` publicaba su `LongArray` de PTS y lo compartía entre la solicitud sellada
 * y el vínculo de la sesión. Quien retuviera esa referencia podía escribir los PTS de otro frame
 * —incluso los del array original que pasó al constructor— y seguir obteniendo una solicitud «válida»
 * con un timestamp distinto del que el índice acreditaba. Aquí se comprueba, sin Android:
 *
 * - el constructor copia el array del llamador y mutarlo después no cambia los PTS del índice;
 * - el getter devuelve una copia nueva en cada lectura y mutarla tampoco cambia el índice;
 * - la solicitud de la sesión conserva el PTS original aunque se hayan mutado ambos arrays, y la
 *   validación del vínculo sigue rechazando un PTS que no sea el del índice;
 * - [VideoFrameIndexSnapshot.ptsAt] falla fuera de rango en lugar de devolver otro PTS.
 *
 * No ejecuta `MediaCodec` ni copia vídeo real: la sesión se construye sobre un snapshot de bytes
 * temporales, así que el PTS no se atribuye a ningún contenido y la prueba es determinista.
 */
class VideoFrameIndexSnapshotImmutabilityTest {

    private val pts = longArrayOf(0L, 40_000L, 80_000L, 120_000L)

    private fun snapshotOf(frameTimesUs: LongArray): VideoFrameIndexSnapshot = VideoFrameIndexSnapshot(
        frameTimesUs = frameTimesUs,
        durationUs = 160_000L,
        encodedWidth = 320,
        encodedHeight = 240,
        declaredRotationDegrees = 0,
    )

    private val index: VideoFrameIndexSnapshot get() = snapshotOf(pts.copyOf())

    private fun tempDirectory(): File = Files.createTempDirectory("angles_snapshot_mutation").toFile()

    /** Snapshot de bytes sellado y reservado, como el que la sesión real usa para leer frames. */
    private fun seal(directory: File): SealedSourceSnapshot {
        val length = 2_048
        val result = SourceSnapshotCopier.copy(
            input = ByteArrayInputStream(ByteArray(length) { (it % 97).toByte() }),
            declaredLengthBytes = length.toLong(),
            snapshotDirectory = directory,
        )
        assertTrue("La copia de prueba debe sellarse: $result", result is SnapshotSealResult.Sealed)
        return (result as SnapshotSealResult.Sealed).snapshot
    }

    @Test
    fun `mutar el array entregado al constructor no cambia los pts acreditados`() {
        val callerArray = pts.copyOf()
        val snapshot = snapshotOf(callerArray)

        callerArray[1] = 7L
        callerArray[2] = 999_999L
        callerArray[3] = 0L

        assertEquals("El PTS del frame 1 no puede venir del array mutado", 40_000L, snapshot.ptsAt(1))
        assertEquals("El PTS del frame 2 no puede venir del array mutado", 80_000L, snapshot.ptsAt(2))
        assertEquals("El seek debe usar el PTS del índice, no el array mutado", 80_000L, CodecCapturePolicy.seekTargetPtsUs(snapshot, 2))
        assertArrayEquals(pts, snapshot.frameTimesUs)
        assertEquals(pts.size, snapshot.frameCount)
    }

    @Test
    fun `mutar el array devuelto por el getter no cambia el indice`() {
        val snapshot = index

        val firstRead = snapshot.frameTimesUs
        firstRead[3] = 3_000_000L
        firstRead.fill(0L)

        assertNotSame("Cada lectura debe devolver una copia, no la referencia interna", firstRead, snapshot.frameTimesUs)
        assertEquals("El PTS del frame 3 debe sobrevivir a la mutación del getter", 120_000L, snapshot.ptsAt(3))
        assertArrayEquals(pts, snapshot.frameTimesUs)
        assertEquals(160_000L, snapshot.durationUs)
        assertEquals(320, snapshot.encodedWidth)
        assertEquals(240, snapshot.encodedHeight)
    }

    @Test
    fun `la solicitud sellada conserva el pts original aunque el llamador mute sus arrays`() {
        val callerArray = pts.copyOf()
        val snapshot = snapshotOf(callerArray)
        val directory = tempDirectory()
        val sealed = seal(directory)
        val session = FrozenFrameSourceSession(sealed.identity, snapshot, sealed)
        try {
            // Dos vías de mutación de la misma referencia: el array del constructor y el del getter.
            callerArray[2] = 1L
            session.index.frameTimesUs[2] = 2L

            val granted = session.request(2)
            assertTrue("La sesión debe conceder el frame 2: $granted", granted is FrameSourceRequestResult.Granted)
            val request = (granted as FrameSourceRequestResult.Granted).request
            assertEquals(
                "El PTS asignado debe ser el del índice sellado, no el array mutado",
                80_000L,
                request.assignedPtsUs,
            )
            assertNull(
                "La solicitud debe seguir validando contra el vínculo de la sesión",
                session.binding.validate(request),
            )
            // Un PTS que no sea el del índice se sigue rechazando: la copia no relaja la validación.
            val forged = request.copy(assignedPtsUs = 1L)
            assertEquals(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                session.binding.validate(forged)?.reason,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `ptsAt fuera de rango falla en lugar de devolver otro pts`() {
        val snapshot = index
        for (outOfRange in listOf(-1, snapshot.frameCount)) {
            try {
                snapshot.ptsAt(outOfRange)
                throw AssertionError("ptsAt($outOfRange) debía fallar.")
            } catch (_: IllegalArgumentException) {
                // Esperado: se falla cerrado en lugar de leer otro frame.
            }
        }
        assertEquals(120_000L, snapshot.ptsAt(snapshot.frameCount - 1))
    }
}
