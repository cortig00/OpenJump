package com.openjump.app.video.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM del flujo no bloqueante ([PollingSourceStream]) y de su integración con el copiador.
 *
 * No usan Android: el sondeo (`Os.poll`), la lectura no bloqueante (`Os.read`) y las banderas del
 * descriptor se inyectan como dependencias falsas y deterministas. Acreditan la aritmética que la
 * prueba instrumentada con pipes reales no puede forzar de forma fiable: cancelación antes de emitir
 * ninguna lectura, `EAGAIN` que no gira en vacío, EOF, cierre idempotente, que nunca se pide más de
 * lo declarado y que un fallo no deja temporales ni reservas.
 */
class PollingSourceStreamTest {

    private fun tempDirectory(): File = Files.createTempDirectory("polling_source_test").toFile()

    @Test
    fun `la cancelacion durante el sondeo lanza la excepcion tipada sin leer`() {
        var polls = 0
        var reads = 0
        val stream = PollingSourceStream(
            awaitReadable = {
                polls += 1
                false
            },
            readRaw = { _, _, _ ->
                reads += 1
                1
            },
            cancellationCheck = { polls >= 3 },
        )
        val error = runCatching { stream.read(ByteArray(16), 0, 16) }.exceptionOrNull()
        assertTrue("Debe ser cancelación tipada y no un fallo de E/S ambiguo", error is SourceReadCancelled)
        assertEquals("No se puede emitir una lectura con el flujo sin datos", 0, reads)
        assertTrue("El sondeo con tiempo límite acota la latencia de cancelación", polls <= 4)
    }

    @Test
    fun `la cancelacion activada durante el sondeo se observa antes de leer`() {
        var cancelled = false
        var polls = 0
        var reads = 0
        val stream = PollingSourceStream(
            // El sondeo despierta por EOF/legibilidad en el mismo instante en que el llamador cancela:
            // antes de la corrección se leía y se devolvía EOF como si la copia hubiera terminado.
            awaitReadable = {
                polls += 1
                cancelled = true
                true
            },
            readRaw = { _, _, _ ->
                reads += 1
                0
            },
            cancellationCheck = { cancelled },
        )
        val error = runCatching { stream.read(ByteArray(16), 0, 16) }.exceptionOrNull()
        assertTrue("Debe ser cancelación tipada y no un EOF silencioso: $error", error is SourceReadCancelled)
        assertEquals("El sondeo se consulta una sola vez antes de observar la cancelación", 1, polls)
        assertEquals("No se puede emitir la lectura tras observar la cancelación en el sondeo", 0, reads)
    }

    @Test
    fun `la cancelacion se comprueba antes del sondeo y antes de la lectura sin sondeos extra`() {
        var pollsAtEntry = 0
        var readsAtEntry = 0
        val alreadyCancelled = PollingSourceStream(
            awaitReadable = {
                pollsAtEntry += 1
                true
            },
            readRaw = { _, _, _ ->
                readsAtEntry += 1
                1
            },
            cancellationCheck = { true },
        )
        assertTrue(
            "La cancelación previa al sondeo sigue observándose antes de cualquier E/S",
            runCatching { alreadyCancelled.read(ByteArray(4), 0, 4) }.exceptionOrNull() is SourceReadCancelled,
        )
        assertEquals("Sin sondeo si ya está cancelado", 0, pollsAtEntry)
        assertEquals("Sin lectura si ya está cancelado", 0, readsAtEntry)

        var polls = 0
        var reads = 0
        val healthy = PollingSourceStream(
            awaitReadable = {
                polls += 1
                true
            },
            readRaw = { _, _, _ ->
                reads += 1
                2
            },
        )
        assertEquals(2, healthy.read(ByteArray(4), 0, 4))
        assertEquals("La revalidación no añade sondeos", 1, polls)
        assertEquals("La revalidación no añade lecturas", 1, reads)
    }

    @Test
    fun `la cancelacion activada durante el sondeo no se confunde con una copia fallida`() {
        val directory = tempDirectory()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        try {
            var cancelled = false
            var reads = 0
            val stream = PollingSourceStream(
                awaitReadable = {
                    cancelled = true
                    true
                },
                readRaw = { _, _, _ ->
                    reads += 1
                    0
                },
                cancellationCheck = { cancelled },
            )
            val result = SourceSnapshotCopier.copy(
                input = stream,
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
            )
            assertEquals(
                "Una cancelación observada en el sondeo debe traducirse a CANCELLED, no a COPY_FAILED",
                SourceOpenFailure.CANCELLED,
                (result as SnapshotSealResult.Failed).failure,
            )
            assertEquals("La copia cancelada no puede emitir ninguna lectura", 0, reads)
            assertEquals("El temporal no puede sobrevivir a la cancelación", 0, directory.listFiles()?.size ?: 0)
            assertEquals("La reserva del snapshot debe liberarse", pinnedBefore, SnapshotLeases.pinnedCount())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `eagain vuelve a sondear y no se convierte en eof ni en giro sin sondeo`() {
        var polls = 0
        var reads = 0
        val stream = PollingSourceStream(
            awaitReadable = {
                polls += 1
                true
            },
            readRaw = { buffer, offset, length ->
                reads += 1
                if (reads <= 2) throw SourceWouldBlock()
                for (index in 0 until length) buffer[offset + index] = 0x42
                length
            },
        )
        val buffer = ByteArray(8)
        assertEquals(8, stream.read(buffer, 0, 8))
        assertEquals(3, reads)
        assertEquals("Cada EAGAIN debe ir precedido de un nuevo sondeo", 3, polls)
        assertTrue(buffer.all { it == 0x42.toByte() })
    }

    @Test
    fun `eof del descriptor se propaga una sola vez como fin de flujo`() {
        val stream = PollingSourceStream(
            awaitReadable = { true },
            readRaw = { _, _, _ -> 0 },
        )
        assertEquals(-1, stream.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `cerrar es idempotente y no permite seguir leyendo`() {
        val closes = AtomicInteger(0)
        val stream = PollingSourceStream(
            awaitReadable = { true },
            readRaw = { _, _, _ -> 1 },
            onClose = { closes.incrementAndGet() },
        )
        stream.close()
        stream.close()
        assertEquals("El descriptor se cierra una sola vez", 1, closes.get())
        assertTrue(stream.isClosed)
        val error = runCatching { stream.read(ByteArray(1), 0, 1) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `la copia nunca pide mas bytes que la longitud declarada`() {
        val directory = tempDirectory()
        try {
            val source = ByteArray(100) { it.toByte() }
            val position = AtomicInteger(0)
            val maxRequested = AtomicInteger(0)
            val stream = PollingSourceStream(
                awaitReadable = { true },
                readRaw = { buffer, offset, length ->
                    maxRequested.accumulateAndGet(length) { a, b -> maxOf(a, b) }
                    val available = (source.size - position.get()).coerceAtMost(length)
                    if (available <= 0) {
                        0
                    } else {
                        source.copyInto(buffer, offset, position.get(), position.get() + available)
                        position.addAndGet(available)
                        available
                    }
                },
            )
            val sealed = SourceSnapshotCopier.copy(
                input = stream,
                declaredLengthBytes = 10L,
                snapshotDirectory = directory,
            ) as SnapshotSealResult.Sealed
            assertEquals(10L, sealed.snapshot.lengthBytes)
            assertEquals("Copiar de más sería un overread del rango declarado", 10, maxRequested.get())
            assertEquals(10, position.get())
            sealed.snapshot.discard()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `un fallo por cancelacion no deja temporales ni reservas de snapshot`() {
        val directory = tempDirectory()
        val pinnedBefore = SnapshotLeases.pinnedCount()
        try {
            val stream = PollingSourceStream(
                awaitReadable = { false },
                readRaw = { _, _, _ -> 1 },
                cancellationCheck = { true },
            )
            val result = SourceSnapshotCopier.copy(
                input = stream,
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
            )
            assertEquals(
                SourceOpenFailure.CANCELLED,
                (result as SnapshotSealResult.Failed).failure,
            )
            assertEquals("El temporal no puede sobrevivir a la cancelación", 0, directory.listFiles()?.size ?: 0)
            assertEquals("La reserva del snapshot debe liberarse", pinnedBefore, SnapshotLeases.pinnedCount())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `el copiador sigue mapeando un flujo bloqueante que falla a source_unavailable`() {
        val directory = tempDirectory()
        try {
            val failing = object : ByteArrayInputStream(ByteArray(16)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    throw IOException("fallo simulado del proveedor")
                }
            }
            val result = SourceSnapshotCopier.copy(
                input = failing,
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
            )
            assertEquals(
                SourceOpenFailure.SOURCE_UNAVAILABLE,
                (result as SnapshotSealResult.Failed).failure,
            )
            assertFalse(directory.listFiles()?.isNotEmpty() ?: false)
        } finally {
            directory.deleteRecursively()
        }
    }
}
