package com.openjump.app.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificacion instrumentada (Android real, API 35) del probe de PNG de G3c **antes** de conectarlo
 * a Room o SAF.
 *
 * Por que en dispositivo: el probe depende de `android.graphics.BitmapFactory`, cuya gramatica de
 * decodificacion (que acepta, que devuelve `null`, como trata un PNG truncado o un JPEG) no la
 * garantiza el host. Esta clase genera PNG de verdad con [Bitmap.compress], los escribe en un
 * sandbox privado y comprueba cada rechazo con la plataforma real. Las rutas hostiles se construyen
 * con bytes sinteticos y se sellan con su propio SHA/tamano, de modo que **no** se rechazan por el
 * sello sino por el contenido (cabecera, dimensiones, firma), que es lo que este slice demuestra.
 *
 * Reglas de aislamiento:
 *  - sandbox **unico** `cacheDir/openjump-media-backup-png-probe-<uuid>` por metodo;
 *  - ningun fichero de usuario, ningun fixture de video, sin Room, sin base de datos, sin UI y sin
 *    SAF; los bytes de origen se comprueban inalterados despues de cada probe;
 *  - la limpieza borra solo el arbol de ese UUID y comprueba que es hijo directo de `cacheDir`;
 *  - no se modifica produccion desde esta clase.
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupPngProbeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runId: String = UUID.randomUUID().toString()
    private val sandbox: File = File(context.cacheDir, "$SANDBOX_PREFIX$runId")

    @Before
    fun createOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        assertFalse("the sandbox must not pre-exist for this UUID", sandbox.exists())
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, sandbox.canonicalFile.parentFile)
        assertTrue("the unique sandbox must be created", sandbox.mkdirs())
        Log.i(TAG, "sandbox=${sandbox.name}")
    }

    @After
    fun deleteOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = sandbox.canonicalFile
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, root.parentFile)
        assertEquals("the sandbox must carry this method's UUID", "$SANDBOX_PREFIX$runId", root.name)
        deleteOwnTree(root)
        assertFalse("the sandbox must be gone after cleanup", root.exists())
    }

    // --- Camino feliz ---------------------------------------------------------------------------

    @Test
    fun realPngIsDecodedAndEveryStageIsConfirmed() {
        val bytes = pngBytes(width = 24, height = 16)
        val file = writePrivate("real-$runId.png", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        val verified = probe.probe(expectationOf(bytes, width = 24, height = 16), source::open)

        assertEquals(KEY, verified.imageKey)
        assertEquals(24, verified.width)
        assertEquals(16, verified.height)
        assertEquals(24L * 16L, verified.area)
        assertEquals(24L * 16L * 4L, verified.decodedBytes)
        assertEquals(bytes.size.toLong(), verified.byteCount)
        assertEquals(sha256Hex(bytes), verified.sha256)
        // Una pasada de integridad + cabecera, una de bounds y una de pixeles.
        assertEquals(3, source.opens)
        assertTrue("every opened stream must be closed", source.allClosed())
        assertEquals(1, decoder.boundsCalls)
        assertEquals(1, decoder.pixelCalls)
        assertEquals(1, decoder.bitmaps.size)
        assertTrue("the decoded bitmap must be recycled", decoder.bitmaps.single().isRecycled)
        // El probe no transforma ni reescribe el staged: los bytes de origen siguen intactos.
        assertTrue(file.isFile)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(sha256Hex(bytes), sha256Hex(file.readBytes()))
        // Sin bitmap vivo no se puede publicar una dimension distinta de la confirmada.
        assertEquals(24, verified.width)
    }

    @Test
    fun probeAsyncDecodesOffTheMainThread() {
        val bytes = pngBytes(width = 8, height = 6)
        val file = writePrivate("async-$runId.png", bytes)
        val threads = mutableListOf<String>()
        val source = TrackingSource(file) { threads += Thread.currentThread().name }
        val probe = MediaBackupPngProbe(MediaBackupPngBudget())

        val verified = runBlocking {
            probe.probeAsync(expectationOf(bytes, width = 8, height = 6), source::open)
        }

        assertEquals(8, verified.width)
        assertEquals(6, verified.height)
        assertTrue("probeAsync must open the staged file", threads.isNotEmpty())
        assertTrue("probeAsync must not decode on the caller thread", threads.none { it == Thread.currentThread().name })
        assertNotEquals(Looper.getMainLooper().thread.name, threads.first())
        assertTrue(source.allClosed())
    }

    // --- Contenido hostil -----------------------------------------------------------------------

    @Test
    fun jpegDisguisedAsPngIsRejectedBeforeAnyDecode() {
        val bytes = jpegBytes(width = 24, height = 16)
        val file = writePrivate("jpeg-$runId.bin", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        assertThrows(MediaBackupPngProbeException.HeaderRejected::class.java) {
            probe.probe(expectationOf(bytes, width = 24, height = 16), source::open)
        }
        assertEquals(1, source.opens)
        assertTrue(source.allClosed())
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun truncatedHeaderIsRejectedBeforeAnyDecode() {
        val truncated = pngBytes(width = 24, height = 16).copyOfRange(0, 20)
        val file = writePrivate("truncated-$runId.png", truncated)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        assertThrows(MediaBackupPngProbeException.Truncated::class.java) {
            probe.probe(expectationOf(truncated, width = 24, height = 16), source::open)
        }
        assertEquals(1, source.opens)
        assertTrue(source.allClosed())
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertArrayEquals(truncated, file.readBytes())
    }

    @Test
    fun corruptIhdrCrcIsRejectedBeforeAnyDecode() {
        val corrupt = pngBytes(width = 24, height = 16).also { bytes ->
            // Byte dentro de la geometria del IHDR: el CRC-32 del chunk deja de cuadrar.
            bytes[23] = (bytes[23].toInt() xor 0x01).toByte()
        }
        val file = writePrivate("corrupt-$runId.png", corrupt)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        assertThrows(MediaBackupPngProbeException.HeaderRejected::class.java) {
            probe.probe(expectationOf(corrupt, width = 24, height = 16), source::open)
        }
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertTrue(source.allClosed())
    }

    @Test
    fun manifestDimensionsDifferentFromTheRealHeaderAreRejectedBeforeBounds() {
        val bytes = pngBytes(width = 24, height = 16)
        val file = writePrivate("mismatch-$runId.png", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        val failure = assertThrows(MediaBackupPngProbeException.DimensionMismatch::class.java) {
            probe.probe(expectationOf(bytes, width = 32, height = 32), source::open)
        }
        assertEquals(MediaBackupPngStage.HEADER, failure.stage)
        assertEquals(32, failure.expectedWidth)
        assertEquals(32, failure.expectedHeight)
        assertEquals(24L, failure.observedWidth)
        assertEquals(16L, failure.observedHeight)
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun hugeDeclaredGeometryIsRejectedWithoutAllocatingPixels() {
        // El manifiesto miente con 1x1 (dentro del presupuesto) y sella los bytes reales: lo unico
        // que puede frenar el fichero es leer su IHDR antes de decodificar.
        val huge = syntheticPngBytes(width = 60_000L, height = 60_000L)
        val file = writePrivate("huge-$runId.png", huge)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        val failure = assertThrows(MediaBackupPngProbeException.DimensionsRejected::class.java) {
            probe.probe(expectationOf(huge, width = 1, height = 1), source::open)
        }
        assertEquals(MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE, failure.reason)
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertTrue(source.allClosed())
        assertArrayEquals(huge, file.readBytes())
    }

    @Test
    fun excessiveAreaWithLegalDimensionsIsRejectedWithoutAllocatingPixels() {
        val wide = syntheticPngBytes(width = 4_000L, height = 4_000L) // 16 MP > 12 MP
        val file = writePrivate("area-$runId.png", wide)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        val failure = assertThrows(MediaBackupPngProbeException.DimensionsRejected::class.java) {
            probe.probe(expectationOf(wide, width = 1, height = 1), source::open)
        }
        assertEquals(MediaBackupPngDimensionRejection.AREA_TOO_LARGE, failure.reason)
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertTrue(source.allClosed())
    }

    @Test
    fun sealedShaMismatchIsRejectedBeforeAnyDecode() {
        val bytes = pngBytes(width = 24, height = 16)
        val file = writePrivate("sha-$runId.png", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)
        val wrongSeal = expectationOf(bytes, width = 24, height = 16, sha256 = "0".repeat(64))

        assertThrows(MediaBackupPngProbeException.IntegrityFailed::class.java) {
            probe.probe(wrongSeal, source::open)
        }
        assertEquals(1, source.opens)
        assertTrue(source.allClosed())
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun sealedSizeMismatchIsRejectedBeforeAnyDecode() {
        val bytes = pngBytes(width = 24, height = 16)
        val file = writePrivate("size-$runId.png", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)
        val wrongSize = expectationOf(bytes, width = 24, height = 16)
            .copy(byteCount = bytes.size.toLong() + 1L)

        assertThrows(MediaBackupPngProbeException.IntegrityFailed::class.java) {
            probe.probe(wrongSize, source::open)
        }
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertTrue(source.allClosed())
    }

    // --- Cancelacion ----------------------------------------------------------------------------

    @Test
    fun cancellationBeforeStartNeverOpensTheStagedFile() {
        val bytes = pngBytes(width = 8, height = 8)
        val file = writePrivate("cancel-before-$runId.png", bytes)
        val source = TrackingSource(file)
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        assertThrows(MediaBackupPngProbeException.Canceled::class.java) {
            probe.probe(
                expectationOf(bytes, width = 8, height = 8),
                source::open,
                MediaBackupArchiveCancellation { true },
            )
        }
        assertEquals(0, source.opens)
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
    }

    @Test
    fun cancellationDuringTheHeaderPassStopsBeforeBounds() {
        val bytes = pngBytes(width = 8, height = 8)
        val file = writePrivate("cancel-mid-$runId.png", bytes)
        val canceled = AtomicBoolean(false)
        val streams = mutableListOf<CloseTrackingStream>()
        val decoder = TrackingDecoder()
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), decoder)

        val failure = assertThrows(MediaBackupPngProbeException.Canceled::class.java) {
            probe.probe(
                expectationOf(bytes, width = 8, height = 8),
                {
                    val stream = CloseTrackingStream(
                        object : FilterInputStream(FileInputStream(file)) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val count = super.read(buffer, offset, length)
                                if (count > 0) canceled.set(true)
                                return count
                            }
                        },
                    )
                    streams += stream
                    stream
                },
                MediaBackupArchiveCancellation { canceled.get() },
            )
        }
        assertEquals(MediaBackupPngProbeException.Canceled, failure)
        assertEquals(1, streams.size)
        assertTrue("the canceled stream must still be closed", streams.single().closed)
        assertEquals(0, decoder.boundsCalls)
        assertEquals(0, decoder.pixelCalls)
        assertArrayEquals(bytes, file.readBytes())
    }

    // --- Bitmap liberado en fallo ---------------------------------------------------------------

    @Test
    fun bitmapIsRecycledWhenTheDecodedDimensionsDoNotMatch() {
        val bytes = pngBytes(width = 24, height = 16)
        val file = writePrivate("wrong-decode-$runId.png", bytes)
        val source = TrackingSource(file)
        val bitmaps = mutableListOf<Bitmap>()
        val lieDecoder = object : MediaBackupPngDecoder {
            override fun decodeBounds(stream: InputStream): MediaBackupPngBounds? =
                AndroidMediaBackupPngDecoder.decodeBounds(stream)

            override fun decodePixels(stream: InputStream): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmaps += it }
        }
        val probe = MediaBackupPngProbe(MediaBackupPngBudget(), lieDecoder)

        val failure = assertThrows(MediaBackupPngProbeException.DimensionMismatch::class.java) {
            probe.probe(expectationOf(bytes, width = 24, height = 16), source::open)
        }
        assertEquals(MediaBackupPngStage.DECODED, failure.stage)
        assertEquals(24, failure.expectedWidth)
        assertEquals(1L, failure.observedWidth)
        assertEquals(1, bitmaps.size)
        assertTrue("a mismatching decoded bitmap must still be recycled", bitmaps.single().isRecycled)
        assertTrue(source.allClosed())
        assertArrayEquals(bytes, file.readBytes())
    }

    // --- Utilidades -----------------------------------------------------------------------------

    private fun writePrivate(name: String, bytes: ByteArray): File =
        File(sandbox, name).also { file ->
            file.writeBytes(bytes)
            assertTrue(file.isFile)
        }

    private fun expectationOf(
        bytes: ByteArray,
        width: Int,
        height: Int,
        sha256: String = sha256Hex(bytes),
    ) = MediaBackupPngExpectation(
        imageKey = KEY,
        width = width,
        height = height,
        byteCount = bytes.size.toLong(),
        sha256 = sha256,
    )

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xFF3366CC.toInt())
            val output = ByteArrayOutputStream()
            assertTrue("the platform must encode a PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xFFCC6633.toInt())
            val output = ByteArrayOutputStream()
            assertTrue("the platform must encode a JPEG", bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * PNG sintetico con la geometria que se pida y CRCs validos. No tiene `IDAT`, asi que no se
     * puede decodificar: el probe tiene que rechazarlo por el `IHDR` antes de intentarlo.
     */
    private fun syntheticPngBytes(width: Long, height: Long): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        val ihdr = ByteArray(13)
        writeUInt32(ihdr, 0, width)
        writeUInt32(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 2 // truecolor
        ihdr[10] = 0 // compression
        ihdr[11] = 0 // filter
        ihdr[12] = 0 // interlace
        writeChunk(output, "IHDR", ihdr)
        writeChunk(output, "IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeUInt32(output, data.size.toLong())
        val typed = type.toByteArray(Charsets.US_ASCII) + data
        output.write(typed)
        val crc = CRC32()
        crc.update(typed)
        writeUInt32(output, crc.value)
    }

    private fun writeUInt32(output: ByteArrayOutputStream, value: Long) {
        output.write(((value ushr 24) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write((value and 0xFF).toInt())
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") {
            String.format(Locale.ROOT, "%02x", it)
        }

    private fun deleteOwnTree(directory: File) {
        directory.listFiles().orEmpty().forEach { entry ->
            val symbolic = java.nio.file.Files.isSymbolicLink(entry.toPath())
            if (entry.isDirectory && !symbolic) {
                deleteOwnTree(entry)
            } else {
                assertTrue("could not delete own artifact '${entry.name}'", entry.delete())
            }
        }
        assertTrue("could not delete own directory '${directory.name}'", directory.delete())
    }

    private class TrackingSource(
        private val file: File,
        private val onOpen: () -> Unit = {},
    ) {
        private val streams = mutableListOf<CloseTrackingStream>()
        var opens = 0
            private set

        fun open(): InputStream {
            opens++
            onOpen()
            val stream = CloseTrackingStream(FileInputStream(file))
            streams += stream
            return stream
        }

        fun allClosed(): Boolean = streams.isNotEmpty() && streams.all { it.closed }
    }

    private class CloseTrackingStream(input: InputStream) : FilterInputStream(input) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingDecoder(
        private val delegate: MediaBackupPngDecoder = AndroidMediaBackupPngDecoder,
    ) : MediaBackupPngDecoder {
        var boundsCalls = 0
            private set
        var pixelCalls = 0
            private set
        val bitmaps = mutableListOf<Bitmap>()

        override fun decodeBounds(stream: InputStream): MediaBackupPngBounds? {
            boundsCalls++
            return delegate.decodeBounds(stream)
        }

        override fun decodePixels(stream: InputStream): Bitmap? {
            pixelCalls++
            val bitmap = delegate.decodePixels(stream)
            if (bitmap != null) bitmaps += bitmap
            return bitmap
        }
    }

    private companion object {
        const val TAG = "MediaBackupPngProbeInstrumentedTest"
        const val SANDBOX_PREFIX = "openjump-media-backup-png-probe-"
        const val KEY = "0123456789abcdef0123456789abcdef"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
