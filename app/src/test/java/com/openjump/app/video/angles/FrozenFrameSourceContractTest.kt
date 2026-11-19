package com.openjump.app.video.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la frontera de fuente y del presupuesto de captura (corrección de los P1 de fuente y
 * memoria de la revisión B2).
 *
 * Son independientes del productor MediaCodec: el copiador de snapshot es Kotlin puro y el vínculo
 * identidad↔índice se prueba con índices sintéticos. El objetivo central es que dos fuentes con
 * **exactamente la misma metadata y los mismos PTS** no puedan atribuirse entre sí, y que ninguna ruta
 * asigne píxeles antes de comprobar el presupuesto.
 */
class FrozenFrameSourceContractTest {

    private val indexA = VideoFrameIndexSnapshot(
        frameTimesUs = longArrayOf(0L, 40_000L, 80_000L, 120_000L),
        durationUs = 160_000L,
        encodedWidth = 320,
        encodedHeight = 240,
        declaredRotationDegrees = 0,
    )

    private fun tempDirectory(): File = Files.createTempDirectory("angles_source_test").toFile()

    private fun request(
        identity: FrameSourceIdentity,
        frameIndex: Int = 2,
        assignedPtsUs: Long = indexA.frameTimesUs[2],
    ) = FrameFreezeRequest(
        sourceIdentity = identity,
        frameIndex = frameIndex,
        assignedPtsUs = assignedPtsUs,
        indexFrameCount = indexA.frameCount,
        encodedWidth = indexA.encodedWidth,
        encodedHeight = indexA.encodedHeight,
        declaredRotationDegrees = indexA.declaredRotationDegrees,
    )

    @Test
    fun `dos fuentes con la misma metadata y pts no se atribuyen entre si`() {
        val identityA = FrameSourceIdentity("snapshot:a:1234")
        val bindingA = FrameSourceBinding(identityA, indexA)
        // B tiene un índice idéntico (mismos PTS, dimensiones, rotación y recuento) y otra identidad.
        val bindingB = FrameSourceBinding(FrameSourceIdentity("snapshot:b:4321"), indexA)

        val requestFromA = request(identityA)
        assertNull("La solicitud de A debe validar en su propia sesión", bindingA.validate(requestFromA))
        val misattributed = bindingB.validate(requestFromA)
        assertEquals(
            FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
            misattributed?.reason,
        )
        assertNotEquals(bindingA.identity, bindingB.identity)

        // La misma metadata/PTS sin identidad de sesión tampoco sirve como prueba.
        val forged = bindingA.validate(request(FrameSourceIdentity("uri:content://video/42")))
        assertEquals(FrameFreezeRejectionReason.SOURCE_SESSION_INVALID, forged?.reason)

        // Con la identidad correcta, un PTS ajeno al índice sigue rechazándose por metadatos.
        val wrongPts = bindingA.validate(request(identityA, assignedPtsUs = indexA.frameTimesUs[2] + 1L))
        assertEquals(FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH, wrongPts?.reason)
    }

    @Test
    fun `el copiador sella exactamente la longitud declarada y acuna una identidad unica`() {
        val directory = tempDirectory()
        try {
            val payload = ByteArray(10_000) { (it % 251).toByte() }
            val extra = ByteArray(500) { 0x7F }
            val declared = 4_000L
            val first = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(payload.copyOfRange(0, 4_000) + extra),
                declaredLengthBytes = declared,
                snapshotDirectory = directory,
            )
            val firstSnapshot = (first as SnapshotSealResult.Sealed).snapshot
            assertEquals(declared, firstSnapshot.lengthBytes)
            assertEquals(declared, firstSnapshot.file.length())
            assertTrue(firstSnapshot.file.parentFile == directory)
            assertTrue(firstSnapshot.file.name.startsWith(SourceSnapshotCopier.SNAPSHOT_PREFIX))
            assertEquals(payload.copyOfRange(0, 4_000).toList(), firstSnapshot.file.readBytes().toList())

            val second = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(payload),
                declaredLengthBytes = declared,
                snapshotDirectory = directory,
            )
            val secondSnapshot = (second as SnapshotSealResult.Sealed).snapshot
            assertNotEquals("Dos copias no pueden compartir identidad", firstSnapshot.identity, secondSnapshot.identity)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `una longitud no declarada copia hasta eof y una longitud mayor que el flujo falla sin sellar`() {
        val directory = tempDirectory()
        val shortDirectory = File(directory, "short").apply { mkdirs() }
        try {
            val payload = ByteArray(2_000) { 1 }
            val unknown = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(payload),
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
            )
            assertEquals(2_000L, (unknown as SnapshotSealResult.Sealed).snapshot.lengthBytes)

            val short = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(payload),
                declaredLengthBytes = 2_001L,
                snapshotDirectory = shortDirectory,
            )
            assertEquals(
                SourceOpenFailure.COPY_FAILED,
                (short as SnapshotSealResult.Failed).failure,
            )
            assertNoOwnedFiles(shortDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `el tope de bytes y la cancelacion no dejan snapshots parciales`() {
        val directory = tempDirectory()
        try {
            val limits = FrozenFrameSourceLimits(maxSnapshotBytes = 1_024L)
            val tooLargeDeclared = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(ByteArray(100)),
                declaredLengthBytes = 2_000L,
                snapshotDirectory = directory,
                limits = limits,
            )
            assertEquals(SourceOpenFailure.SOURCE_TOO_LARGE, (tooLargeDeclared as SnapshotSealResult.Failed).failure)

            // El proveedor miente o no declara longitud: el tope se aplica igual durante la copia.
            val tooLargeStreamed = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(ByteArray(4_096)),
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
                limits = limits,
            )
            assertEquals(SourceOpenFailure.SOURCE_TOO_LARGE, (tooLargeStreamed as SnapshotSealResult.Failed).failure)
            assertNoOwnedFiles(directory)

            var reads = 0
            val cancelled = SourceSnapshotCopier.copy(
                input = object : java.io.ByteArrayInputStream(ByteArray(4_096)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        reads += 1
                        return super.read(buffer, offset, length)
                    }
                },
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = directory,
                cancellationCheck = { reads >= 1 },
            )
            assertEquals(SourceOpenFailure.CANCELLED, (cancelled as SnapshotSealResult.Failed).failure)
            assertNoOwnedFiles(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `un directorio no escribible falla de forma tipada`() {
        val directory = tempDirectory()
        val blocker = File(directory, "not_a_directory")
        try {
            blocker.writeBytes(ByteArray(8))
            val result = SourceSnapshotCopier.copy(
                input = ByteArrayInputStream(ByteArray(32)),
                declaredLengthBytes = SourceSnapshotCopier.UNKNOWN_LENGTH,
                snapshotDirectory = blocker,
            )
            assertEquals(SourceOpenFailure.STORAGE_UNAVAILABLE, (result as SnapshotSealResult.Failed).failure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `el barrido solo borra snapshots propios y antiguos`() {
        val directory = tempDirectory()
        try {
            val now = 1_000_000_000L
            val staleOwn = File(directory, "${SourceSnapshotCopier.SNAPSHOT_PREFIX}stale.mp4").apply {
                writeBytes(ByteArray(4))
                setLastModified(now - 48L * 60L * 60L * 1000L)
            }
            val freshOwn = File(directory, "${SourceSnapshotCopier.TEMP_PREFIX}fresh.bin").apply {
                writeBytes(ByteArray(4))
                setLastModified(now)
            }
            val foreign = File(directory, "user_video.mp4").apply {
                writeBytes(ByteArray(4))
                setLastModified(now - 48L * 60L * 60L * 1000L)
            }
            val deleted = SourceSnapshotCopier.sweepStale(directory, now, 24L * 60L * 60L * 1000L)
            assertEquals(1, deleted)
            assertFalse("Un snapshot antiguo propio debe borrarse", staleOwn.exists())
            assertTrue("Un snapshot reciente no debe borrarse", freshOwn.exists())
            assertTrue("Un fichero ajeno nunca se borra", foreign.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `el presupuesto de captura rechaza 4k y acepta 1080p con aritmetica segura`() {
        // 4K: matriz + bitmap + copias congeladas del peor caso no caben en 64 MiB. No se declara soportado.
        assertFalse(FrameFreezeBudget.isWithinCaptureBudget(3840, 2160, 3840, 2160))
        // 1440p tampoco cabe en la contabilidad conservadora (no se inventa soporte).
        assertFalse(FrameFreezeBudget.isWithinCaptureBudget(2560, 1440, 2560, 1440))
        // 1080p sí cabe (orientación ya resuelta: mismos píxeles presentados).
        assertTrue(FrameFreezeBudget.isWithinCaptureBudget(1920, 1080, 1920, 1080))
        assertTrue(FrameFreezeBudget.isWithinCaptureBudget(1920, 1080, 1080, 1920))

        // Consulta total: desbordamiento y valores absurdos devuelven null, nunca lanzan.
        assertNull(FrameFreezeBudget.argbArrayBytes(Int.MAX_VALUE, 2))
        assertNull(FrameFreezeBudget.argbArrayBytes(0, 10))
        assertNull(FrameFreezeBudget.captureRequiredBytes(3840, 2160, 0, 0))
        assertNull(FrameFreezeBudget.captureRequiredBytes(1, 1, 1, 1, simultaneousFrozenCopies = 0))
        assertEquals(
            320L * 240L * 4,
            FrameFreezeBudget.argbArrayBytes(320, 240),
        )
    }

    @Test
    fun `el gasto de memoria se rechaza antes de invocar la asignacion`() {
        var invoked = false
        val rejected = guardedAllocation(requiredBytes = 128L, maxBytes = 64L) {
            invoked = true
            ByteArray(1)
        }
        assertTrue(rejected is FrameAllocation.Unavailable)
        assertEquals(FrameAllocationFailure.BUDGET_EXCEEDED, (rejected as FrameAllocation.Unavailable).failure)
        assertFalse("Una asignación fuera de presupuesto no debe ejecutarse", invoked)
    }

    @Test
    fun `un out of memory simulado se convierte en rechazo tipado`() {
        val simulated = guardedAllocation<ByteArray>(requiredBytes = 1_024L) {
            throw OutOfMemoryError("simulado")
        }
        assertTrue(simulated is FrameAllocation.Unavailable)
        assertEquals(FrameAllocationFailure.OUT_OF_MEMORY, (simulated as FrameAllocation.Unavailable).failure)

        val refused = guardedAllocation<ByteArray>(requiredBytes = 1_024L) { null }
        assertEquals(FrameAllocationFailure.REFUSED, (refused as FrameAllocation.Unavailable).failure)

        val allocated = guardedAllocation(requiredBytes = 1_024L) { ByteArray(16) }
        assertTrue(allocated is FrameAllocation.Allocated)
        assertEquals(16, (allocated as FrameAllocation.Allocated).value.size)
    }

    @Test
    fun `la conversion argb no asigna cuando el presupuesto es cero`() {
        val layout = Yuv420OutputLayout(
            bufferWidth = 8,
            bufferHeight = 8,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 7,
            cropBottom = 7,
            yRowStride = 8,
            yPixelStride = 1,
            uRowStride = 4,
            uPixelStride = 1,
            vRowStride = 4,
            vPixelStride = 1,
        )
        val y = ByteBuffer.allocate(8 * 8).apply { for (index in 0 until 64) put(index, 235.toByte()) }
        val chroma = ByteBuffer.allocate(16).apply { for (index in 0 until 16) put(index, 128.toByte()) }
        assertNull(
            "Con presupuesto 1 no puede asignarse la matriz ARGB visible",
            layout.toArgb(y, chroma, chroma.duplicate(), maxAllocationBytes = 1L),
        )
        assertEquals(64, layout.toArgb(y, chroma, chroma.duplicate(), maxAllocationBytes = 64L * 4)?.size)
    }

    private fun assertNoOwnedFiles(directory: File) {
        val owned = directory.listFiles()?.filter {
            it.name.startsWith(SourceSnapshotCopier.SNAPSHOT_PREFIX) ||
                it.name.startsWith(SourceSnapshotCopier.TEMP_PREFIX)
        }.orEmpty()
        assertTrue("No debe quedar ningún snapshot/temporal propio: $owned", owned.isEmpty())
    }
}
