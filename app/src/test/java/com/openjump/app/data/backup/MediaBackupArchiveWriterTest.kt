package com.openjump.app.data.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contrato del escritor ZIP del backup con medios (G2a), en JVM y con sandbox propio por prueba.
 *
 * No hay AVD ni staging real de Android: el staging es un directorio temporal unico de esta prueba y
 * el origen de imagenes es un fake por clave opaca. Las fixtures son diminutas; ninguna prueba
 * construye 12 MiB ni 4096 entradas.
 */
class MediaBackupArchiveWriterTest {

    private lateinit var sandbox: File

    @Before
    fun setUp() {
        sandbox = Files.createTempDirectory("openjump-media-backup-test-").toFile()
        assertTrue(sandbox.isDirectory)
    }

    @After
    fun tearDown() {
        // Sandbox creado por esta prueba: se elimina solo aqui, nunca desde el escritor.
        sandbox.deleteRecursively()
    }

    // --- Camino feliz ---------------------------------------------------------------------------

    @Test
    fun writesVerifiedArchiveWithExactManifestAndImages() {
        val fixture = fixture(24, 32, 40)
        val prepared = writer().write(fixture.manifest, fixture.source())

        assertTrue(prepared.file.isFile)
        assertTrue(prepared.file.parentFile!!.canonicalFile == sandbox.canonicalFile)
        assertEquals("manifest.json", prepared.manifestEntryName)
        assertEquals(3, prepared.imageCount)
        assertEquals(fixture.manifest.archivePlan().totalImageByteCount, prepared.totalImageByteCount)
        assertEquals(prepared.file.length(), prepared.archiveByteCount)
        assertFalse(prepared.isClosed)

        val expectedNames = listOf("manifest.json") + fixture.manifest.archivePlan().imageEntries.map { it.entryName }
        ZipFile(prepared.file).use { zip ->
            val entries = zip.entries().toList()
            // Orden canonico: manifiesto primero y despues las imagenes por clave.
            assertEquals(expectedNames, entries.map { it.name })
            assertEquals(expectedNames.size, expectedNames.distinct().size)
            entries.forEach { entry ->
                assertFalse(entry.isDirectory)
                assertEquals(java.util.zip.ZipEntry.DEFLATED, entry.method)
                assertFalse("no extra fields", entry.extra != null)
                assertFalse("no comments", entry.comment != null)
            }
            val manifestEntry = zip.getEntry("manifest.json")!!
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            assertEquals(MediaBackupManifestCodec.encode(fixture.manifest).size, manifestBytes.size)
            assertEquals(fixture.manifest, MediaBackupManifestCodec.decode(manifestBytes))
            fixture.imagesByKey.forEach { (key, bytes) ->
                val entryName = MediaBackupArchive.imageEntryName(key)
                val entry = zip.getEntry(entryName)!!
                assertEquals(bytes.size.toLong(), entry.size)
                assertEquals(key, MediaBackupArchive.imageKeyOfEntry(entry.name))
                val archived = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals(bytes.toList(), archived.toList())
                assertEquals(sha256(bytes), fixture.manifest.angleV2.images.single { it.imageKey == key }.sha256)
            }
        }
        assertTrue(sandbox.listFiles()!!.any { it == prepared.file })
        prepared.close()
        assertTrue(prepared.isClosed)
        assertFalse(prepared.file.exists())
    }

    @Test
    fun zeroImagesProducesAValidManifestOnlyArchive() {
        val manifest = MediaBackupManifestCodecTest.sample(
            roots = emptyList(),
            frames = emptyList(),
            marks = emptyList(),
            images = emptyList(),
        )
        val neverOpened = MediaBackupImageSource { throw AssertionError("no image must be opened") }
        val prepared = writer().write(manifest, neverOpened)
        try {
            ZipFile(prepared.file).use { zip ->
                assertEquals(listOf("manifest.json"), zip.entries().toList().map { it.name })
                val decoded = zip.getInputStream(zip.getEntry("manifest.json")!!).use {
                    MediaBackupManifestCodec.decode(it.readBytes())
                }
                assertEquals(manifest, decoded)
            }
            assertEquals(0, prepared.imageCount)
            assertEquals(0L, prepared.totalImageByteCount)
        } finally {
            prepared.close()
        }
    }

    // --- Rechazo antes de la E/S ----------------------------------------------------------------

    @Test
    fun invalidManifestIsRejectedBeforeAnyIo() {
        val fixture = fixture(16)
        val broken = fixture.manifest.copy(
            angleV2 = fixture.manifest.angleV2.copy(
                images = fixture.manifest.angleV2.images.map { it.copy(sha256 = "0".repeat(64)) },
            ),
        )
        val port = RecordingPort()
        var opened = false
        val source = MediaBackupImageSource { opened = true; fixturesFor(fixture)[it] }
        val error = assertThrows(MediaBackupArchiveException.ManifestRejected::class.java) {
            writer(port).write(broken, source)
        }
        assertTrue(error.cause is IllegalArgumentException)
        assertFalse("the source must not be opened", opened)
        assertTrue("no temporary file may be created", port.created.isEmpty())
        assertEquals(0, tempFiles().size)
    }

    @Test
    fun limitsAreCheckedBeforeAnyIo() {
        val fixture = fixture(16, 16)
        val tooFewImages = RecordingPort()
        val imagesError = assertThrows(MediaBackupArchiveException.LimitExceeded::class.java) {
            writer(tooFewImages, MediaBackupArchiveLimits(maxImages = 1)).write(fixture.manifest, fixture.source())
        }
        assertTrue(imagesError.message!!.contains("images"))
        assertTrue(tooFewImages.created.isEmpty())

        val totalPort = RecordingPort()
        assertThrows(MediaBackupArchiveException.LimitExceeded::class.java) {
            writer(
                totalPort,
                MediaBackupArchiveLimits(maxImageBytes = 16, maxTotalImageBytes = 16),
            ).write(fixture.manifest, fixture.source())
        }
        assertTrue(totalPort.created.isEmpty())

        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)
        val manifestPort = RecordingPort()
        assertThrows(MediaBackupArchiveException.LimitExceeded::class.java) {
            writer(manifestPort, MediaBackupArchiveLimits(maxManifestBytes = manifestBytes.size.toLong() - 1L))
                .write(fixture.manifest, fixture.source())
        }
        assertTrue(manifestPort.created.isEmpty())
        assertEquals(0, tempFiles().size)
    }

    @Test
    fun stagingDirectoryIsValidatedAndSymlinksAreRejected() {
        val fixture = fixture(8)
        val missing = File(sandbox, "missing")
        assertThrows(MediaBackupArchiveException.StagingDirectoryRejected::class.java) {
            MediaBackupArchiveWriter(missing).write(fixture.manifest, fixture.source())
        }
        // El writer recibe el staging por constructor; no hay API para un destino externo.
        assertFalse(missing.exists())
        val link = File(sandbox, "link")
        Files.createSymbolicLink(link.toPath(), sandbox.toPath())
        assertThrows(MediaBackupArchiveException.StagingDirectoryRejected::class.java) {
            MediaBackupArchiveWriter(link).write(fixture.manifest, fixture.source())
        }
    }

    // --- Integridad de cada imagen --------------------------------------------------------------

    @Test
    fun missingImageAbortsAndDeletesOnlyItsOwnTemporary() {
        val fixture = fixture(16, 16)
        val missingKey = fixture.keys[1]
        val error = assertThrows(MediaBackupArchiveException.ImageMissing::class.java) {
            writer().write(
                fixture.manifest,
                MediaBackupImageSource { key -> if (key == missingKey) null else ByteArrayInputStream(fixture.imagesByKey.getValue(key)) },
            )
        }
        assertEquals(missingKey, error.imageKey)
        assertNoTemporariesLeft()
    }

    @Test
    fun shortImageAborts() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val error = assertThrows(MediaBackupArchiveException.ImageSizeMismatch::class.java) {
            writer().write(
                fixture.manifest,
                MediaBackupImageSource { ByteArrayInputStream(fixture.imagesByKey.getValue(key).copyOf(31)) },
            )
        }
        assertEquals(32L, error.expectedByteCount)
        assertEquals(31L, error.observedByteCount)
        assertNoTemporariesLeft()
    }

    @Test
    fun longImageAbortsWithoutExtraBytes() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val error = assertThrows(MediaBackupArchiveException.ImageSizeMismatch::class.java) {
            writer().write(
                fixture.manifest,
                MediaBackupImageSource {
                    ByteArrayInputStream(fixture.imagesByKey.getValue(key) + ByteArray(7) { 0x5A })
                },
            )
        }
        assertEquals(32L, error.expectedByteCount)
        assertTrue("observed bytes must exceed the declaration", error.observedByteCount > 32L)
        assertNoTemporariesLeft()
    }

    @Test
    fun digestMismatchAbortsEvenWithTheExactSize() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val corrupted = fixture.imagesByKey.getValue(key).copyOf().also { it[3] = (it[3] + 1).toByte() }
        val error = assertThrows(MediaBackupArchiveException.ImageDigestMismatch::class.java) {
            writer().write(fixture.manifest, MediaBackupImageSource { ByteArrayInputStream(corrupted) })
        }
        assertEquals(key, error.imageKey)
        assertNoTemporariesLeft()
    }

    @Test
    fun sourceFailureAbortsAndCleansUp() {
        val fixture = fixture(32)
        assertThrows(MediaBackupArchiveException.WriteFailed::class.java) {
            writer().write(fixture.manifest, MediaBackupImageSource { throw IOException("source broken") })
        }
        assertNoTemporariesLeft()
        val midStream = object : FilterInputStream(ByteArrayInputStream(ByteArray(32))) {
            private var reads = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("source broke mid-stream")
                return super.read(buffer, offset, minOf(length, 8))
            }
        }
        assertThrows(MediaBackupArchiveException.WriteFailed::class.java) {
            writer().write(fixture.manifest, MediaBackupImageSource { midStream })
        }
        assertNoTemporariesLeft()
    }

    // --- Cancelacion ----------------------------------------------------------------------------

    @Test
    fun cancellationBeforeStartCreatesNoFile() {
        val fixture = fixture(8)
        var opened = false
        val error = assertThrows(MediaBackupArchiveException.Canceled::class.java) {
            writer().write(
                fixture.manifest,
                MediaBackupImageSource { opened = true; ByteArrayInputStream(fixture.imagesByKey.getValue(it)) },
                MediaBackupArchiveCancellation { true },
            )
        }
        assertEquals(MediaBackupArchiveException.Canceled, error)
        assertFalse(opened)
        assertEquals(0, tempFiles().size)
    }

    @Test
    fun cancellationBetweenImagesAbortsAndDeletesTheTemporary() {
        val fixture = fixture(16, 16)
        val opened = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val source = MediaBackupImageSource { key ->
            opened += key
            object : FilterInputStream(ByteArrayInputStream(fixture.imagesByKey.getValue(key))) {
                override fun close() {
                    super.close()
                    completed += key
                }
            }
        }
        assertThrows(MediaBackupArchiveException.Canceled::class.java) {
            writer().write(fixture.manifest, source, MediaBackupArchiveCancellation { completed.size >= 1 })
        }
        // La primera imagen se copio entera y la segunda ni se abrio.
        assertEquals(1, opened.size)
        assertEquals(1, completed.size)
        assertNoTemporariesLeft()
    }

    // --- Fallos de E/S inyectados por el puerto -------------------------------------------------

    @Test
    fun sinkFailureAbortsAndCleansUp() {
        val fixture = fixture(16)
        val port = RecordingPort(outputFailure = { base -> FailingOutputStream(base, failAfterBytes = 0L) })
        assertThrows(MediaBackupArchiveException.WriteFailed::class.java) {
            writer(port).write(fixture.manifest, fixture.source())
        }
        assertTrue("the temporary file created by this operation must be deleted", port.deleted.isNotEmpty())
        assertEquals(port.created, port.deleted)
        assertNoTemporariesLeft()
    }

    @Test
    fun syncFailureAbortsAndDeletesTheTemporary() {
        val fixture = fixture(16)
        val port = RecordingPort(syncFailure = IOException("fsync failed"))
        val error = assertThrows(MediaBackupArchiveException.SyncFailed::class.java) {
            writer(port).write(fixture.manifest, fixture.source())
        }
        assertTrue(error.cause is IOException)
        assertEquals(port.created, port.deleted)
        assertNoTemporariesLeft()
    }

    @Test
    fun corruptCentralDirectoryIsDetectedOnReopen() {
        val fixture = fixture(16)
        // El cierre fisico deja el fichero sin el final del directorio central: se reabre y falla.
        val port = RecordingPort(afterClose = { file ->
            RandomAccessFile(file, "rw").use { it.setLength(maxOf(0L, it.length() - 10L)) }
        })
        assertThrows(MediaBackupArchiveException.VerificationFailed::class.java) {
            writer(port).write(fixture.manifest, fixture.source())
        }
        assertNoTemporariesLeft()
    }

    // --- Relectura de los bytes comprimidos en la verificacion (G2a, revision) -------------------

    @Test
    fun corruptedCompressedBytesAreRejectedOnlyBecauseTheImagesAreReRead() {
        val fixture = fixture(24, 32, 40)
        val corruptedKey = fixture.keys[1]
        val corruptedEntry = MediaBackupArchive.imageEntryName(corruptedKey)
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val captured = File(sandbox, "corrupted-after-sync.zip")
        // El puerto corre al cerrar el descriptor: justo despues del fsync y antes de la reapertura
        // de verificacion. Solo se tocan bytes comprimidos; el directorio central queda intacto.
        val port = RecordingPort(
            afterClose = { file ->
                corruptCompressedBytes(file, corruptedEntry)
                file.copyTo(captured, overwrite = true)
            },
        )

        val error = assertThrows(MediaBackupArchiveException.VerificationFailed::class.java) {
            writer(port).write(fixture.manifest, fixture.source())
        }

        assertTrue(
            "the failure must name the corrupted image, was: ${error.message}",
            error.message!!.contains(corruptedKey),
        )
        // Evidencia del defecto revisado: la verificacion anterior (directorio central + manifiesto,
        // sin releer imagenes) daba este mismo fichero corrupto por bueno.
        assertTrue(
            "the previous central-directory-only verification accepts the corrupted archive",
            legacyCentralDirectoryOnlyChecksPass(captured, fixture.manifest, fixture.imagesByKey),
        )
        assertTrue("the capture is a test artifact, not a writer temporary", captured.isFile)
        assertNoTemporariesLeft()
        assertTrue("the staging sentinel must be untouched", sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun cancellationDuringTheImageReReadAbortsAndDeletesTheTemporary() {
        val block = MediaBackupArchiveWriter.COPY_BUFFER_BYTES
        // Varios bloques por imagen: si la relectura ignorase la cancelacion, la verificacion
        // terminaria y el contenedor se entregaria en vez de abortar.
        val fixture = fixture(4 * block, 4 * block, 4 * block)
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val verifying = AtomicBoolean(false)
        val consultsWhileVerifying = AtomicInteger(0)
        val port = RecordingPort(afterClose = { verifying.set(true) })

        val error = assertThrows(MediaBackupArchiveException.Canceled::class.java) {
            writer(port).write(
                fixture.manifest,
                fixture.source(),
                MediaBackupArchiveCancellation {
                    // Solo cuenta desde que el fichero esta cerrado: la cancelacion llega durante la
                    // relectura de verificacion, nunca durante la copia.
                    verifying.get() && consultsWhileVerifying.incrementAndGet() >= 10
                },
            )
        }
        assertEquals(MediaBackupArchiveException.Canceled, error)
        assertTrue(
            "cancellation must be observed while re-reading (consults=${consultsWhileVerifying.get()})",
            consultsWhileVerifying.get() >= 10,
        )
        assertNoTemporariesLeft()
        assertTrue(sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun validArchiveWithMultipleImagesIsReReadBlockByBlockAndAccepted() {
        val block = MediaBackupArchiveWriter.COPY_BUFFER_BYTES
        // Un byte, un tamano impar, el bloque exacto y un multiplo exacto: la relectura acotada no
        // puede rechazar por si misma un contenedor valido ni confundir corto/largo.
        val fixture = fixture(1, 7, block, 2 * block)
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val prepared = writer().write(fixture.manifest, fixture.source())
        try {
            assertEquals(4, prepared.imageCount)
            assertEquals(fixture.manifest.archivePlan().totalImageByteCount, prepared.totalImageByteCount)
            ZipFile(prepared.file).use { zip ->
                fixture.imagesByKey.forEach { (key, bytes) ->
                    val entry = zip.getEntry(MediaBackupArchive.imageEntryName(key))!!
                    assertEquals(bytes.size.toLong(), entry.size)
                    assertEquals(crc32(bytes), entry.crc)
                    val archived = zip.getInputStream(entry).use { it.readBytes() }
                    assertEquals(bytes.size, archived.size)
                    assertEquals(sha256(bytes), sha256(archived))
                }
            }
        } finally {
            prepared.close()
        }
        assertNoTemporariesLeft()
        assertTrue(sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun cleanupFailureIsReportedAndTheTemporaryIsPreserved() {
        val fixture = fixture(16)
        val port = RecordingPort(failDelete = true)
        val error = assertThrows(MediaBackupArchiveException.CleanupFailed::class.java) {
            writer(port).write(fixture.manifest, MediaBackupImageSource { null })
        }
        assertTrue(error.cause is MediaBackupArchiveException.ImageMissing)
        assertEquals(1, port.created.size)
        assertTrue("the temporary file must be preserved", port.created.single().isFile)
        // Limpieza manual de la prueba (el sandbox es suyo), nunca del escritor.
        assertTrue(port.created.single().delete())
    }

    // --- Propiedad y limpieza selectiva ---------------------------------------------------------

    @Test
    fun closeIsIdempotentAndOnlyDeletesItsOwnTemporary() {
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val decoy = File(sandbox, "openjump-media-backup-decoy.zip.tmp").apply { writeText("not ours") }
        val fixture = fixture(8)
        val prepared = writer().write(fixture.manifest, fixture.source())
        val own = prepared.file
        assertTrue(own.isFile)
        prepared.close()
        prepared.close()
        assertFalse(own.exists())
        assertTrue(sentinel.isFile)
        assertTrue(decoy.isFile)
        assertEquals("keep me", sentinel.readText())
        assertEquals("not ours", decoy.readText())
    }

    @Test
    fun failuresNeverTouchPreexistingFilesInTheStagingDirectory() {
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val decoy = File(sandbox, "openjump-media-backup-decoy.zip.tmp").apply { writeText("not ours") }
        val fixture = fixture(16, 16)
        assertThrows(MediaBackupArchiveException.ImageMissing::class.java) {
            writer().write(fixture.manifest, MediaBackupImageSource { null })
        }
        assertTrue(sentinel.isFile)
        assertTrue(decoy.isFile)
        assertEquals("keep me", sentinel.readText())
        assertEquals(listOf(decoy.name, sentinel.name).sorted(), sandbox.listFiles()!!.map { it.name }.sorted())
    }

    // --- Aristas estructurales ------------------------------------------------------------------

    @Test
    fun planTotalsUseCheckedArithmetic() {
        val huge = Long.MAX_VALUE / 2L + 1L
        val plan = MediaBackupArchivePlan(
            manifestEntryName = "manifest.json",
            imageEntries = listOf(
                MediaBackupImageEntry("images/${key(1)}.png", key(1), huge, "0".repeat(64)),
                MediaBackupImageEntry("images/${key(2)}.png", key(2), huge, "1".repeat(64)),
            ),
        )
        assertThrows(ArithmeticException::class.java) { plan.totalImageByteCount }
    }

    @Test
    fun concurrentWritesUseDistinctVerifiedTemporaries() {
        val fixture = fixture(16, 16)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) {
                Callable {
                    writer().write(fixture.manifest, fixture.source())
                }
            }
            val results = executor.invokeAll(tasks, 60, TimeUnit.SECONDS).map { it.get() }
            assertNotEquals(results[0].file.name, results[1].file.name)
            results.forEach { prepared ->
                assertTrue(prepared.file.isFile)
                ZipFile(prepared.file).use { zip ->
                    assertEquals(3, zip.entries().toList().size)
                }
            }
            results.forEach { it.close() }
            assertEquals(0, tempFiles().size)
        } finally {
            executor.shutdownNow()
        }
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun writer(
        port: MediaBackupArchiveFilePort = RecordingPort(),
        limits: MediaBackupArchiveLimits = MediaBackupArchiveLimits(),
    ) = MediaBackupArchiveWriter(sandbox, port, limits)

    private fun tempFiles(): List<File> = sandbox.listFiles().orEmpty().filter {
        it.name.startsWith(MediaBackupArchiveFiles.TEMPORARY_PREFIX) && it.name.endsWith(MediaBackupArchiveFiles.TEMPORARY_SUFFIX)
    }

    private fun assertNoTemporariesLeft() {
        assertEquals("no temporary file may survive a failure", emptyList<File>(), tempFiles())
    }

    /**
     * Copia de la verificacion que hacia el escritor **antes** de releer las imagenes: directorio
     * central (nombres en orden, entradas canonicas, tamano y CRC de cada imagen) y `manifest.json`
     * por SHA-256. Devuelve `true` si esa comprobacion aceptaba el contenedor.
     */
    private fun legacyCentralDirectoryOnlyChecksPass(
        file: File,
        manifest: MediaBackupManifest,
        imagesByKey: Map<String, ByteArray>,
    ): Boolean {
        val plan = manifest.archivePlan()
        val manifestBytes = MediaBackupManifestCodec.encode(manifest)
        val expectedNames = listOf(plan.manifestEntryName) + plan.imageEntries.map { it.entryName }
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val namesMatch = entries.map { it.name } == expectedNames
                val entriesCanonical = entries.none { entry ->
                    entry.isDirectory || entry.extra != null || entry.comment != null ||
                        entry.method != java.util.zip.ZipEntry.DEFLATED
                }
                val manifestEntry = zip.getEntry(plan.manifestEntryName)
                val manifestMatches = manifestEntry != null &&
                    manifestEntry.size == manifestBytes.size.toLong() &&
                    sha256(zip.getInputStream(manifestEntry).use { it.readBytes() }) == sha256(manifestBytes)
                val imagesMatch = plan.imageEntries.all { planned ->
                    val entry = zip.getEntry(planned.entryName)
                    val bytes = imagesByKey[planned.imageKey]
                    entry != null && bytes != null &&
                        entry.size == planned.byteCount &&
                        entry.crc == crc32(bytes)
                }
                namesMatch && entriesCanonical && manifestMatches && imagesMatch
            }
        } catch (failure: Exception) {
            false
        }
    }

    /**
     * Corrompe bytes del flujo **comprimido** de [entryName] con un XOR 0xFF en una ventana central,
     * sin tocar el directorio central, los tamanos declarados ni la longitud del fichero. El escritor
     * usa descriptor de datos, asi que el tamano comprimido se toma del directorio central y el
     * inicio de los datos se calcula con las longitudes de nombre/extra de la cabecera local.
     */
    private fun corruptCompressedBytes(file: File, entryName: String) {
        val bytes = file.readBytes()
        val headerAt = localHeaderOffset(bytes, entryName)
        val dataStart = headerAt + 30 + readLe16(bytes, headerAt + 26) + readLe16(bytes, headerAt + 28)
        val compressedSize = compressedSizeOf(file, entryName)
        val windowLength = maxOf(1, compressedSize / 2)
        val windowStart = dataStart + compressedSize / 4
        check(compressedSize > 0 && windowStart + windowLength <= dataStart + compressedSize) {
            "corruption window must stay inside the compressed data of $entryName"
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(windowStart.toLong())
            val window = ByteArray(windowLength)
            raf.readFully(window)
            for (index in window.indices) {
                window[index] = (window[index].toInt() xor 0xFF).toByte()
            }
            raf.seek(windowStart.toLong())
            raf.write(window)
        }
        assertEquals("corruption must not change the archive length", bytes.size.toLong(), file.length())
    }

    /** Desplazamiento de la cabecera local `PK\x03\x04` de [entryName] segun su nombre UTF-8. */
    private fun localHeaderOffset(bytes: ByteArray, entryName: String): Int {
        var position = 0
        while (position + 30 <= bytes.size) {
            val signature = readLe32(bytes, position)
            if (signature == LOCAL_HEADER_SIGNATURE) {
                val nameLength = readLe16(bytes, position + 26)
                val extraLength = readLe16(bytes, position + 28)
                val nameStart = position + 30
                if (nameStart + nameLength + extraLength <= bytes.size) {
                    val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
                    if (name == entryName) return position
                }
            }
            position++
        }
        throw AssertionError("local header of '$entryName' not found")
    }

    /** Tamano comprimido declarado en el directorio central (el local usa descriptor de datos). */
    private fun compressedSizeOf(file: File, entryName: String): Int = ZipFile(file).use { zip ->
        val entry = zip.getEntry(entryName) ?: throw AssertionError("entry '$entryName' not found")
        val size = entry.compressedSize
        assertTrue("compressed size of '$entryName' must be known, was $size", size in 1..Int.MAX_VALUE.toLong())
        size.toInt()
    }

    private fun readLe16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private fun fixturesFor(fixture: Fixture): Map<String, InputStream> =
        fixture.imagesByKey.mapValues { ByteArrayInputStream(it.value) }

    private class Fixture(
        val manifest: MediaBackupManifest,
        val imagesByKey: Map<String, ByteArray>,
    ) {
        val keys: List<String> get() = imagesByKey.keys.toList()

        fun source(opened: MutableList<String>? = null): MediaBackupImageSource = MediaBackupImageSource { key ->
            opened?.add(key)
            ByteArrayInputStream(imagesByKey.getValue(key))
        }
    }

    private fun fixture(vararg sizes: Int): Fixture {
        require(sizes.isNotEmpty())
        val keys = sizes.indices.map { key(it + 1) }
        val contents = sizes.mapIndexed { index, size ->
            ByteArray(size) { offset -> (offset * 31 + index * 7 + 5).toByte() }
        }
        val frames = sizes.indices.map { index ->
            MediaBackupManifestCodecTest.frame(
                id = 100L + index,
                rootId = 1L,
                ordinal = index,
                imageKey = keys[index],
                byteCount = sizes[index].toLong(),
                sha256 = sha256(contents[index]),
            )
        }
        val marks = sizes.indices.map { index ->
            MediaBackupManifestCodecTest.mark(id = 200L + index, frameId = 100L + index, ordinal = 0)
        }
        val images = sizes.indices.map { index ->
            MediaBackupImage(keys[index], sizes[index].toLong(), sha256(contents[index]))
        }
        val manifest = MediaBackupManifestCodecTest.sample(
            roots = listOf(MediaBackupManifestCodecTest.defaultRoots().first()),
            frames = frames,
            marks = marks,
            images = images,
        )
        return Fixture(manifest, keys.indices.associate { keys[it] to contents[it] })
    }

    private companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034B50L

        fun key(value: Int): String = String.format(Locale.ROOT, "%032x", value)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

        fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    }
}

/** Puerto real con ganchos de fallo y registro: solo las pruebas inyectan fallos. */
private class RecordingPort(
    private val syncFailure: IOException? = null,
    private val outputFailure: ((OutputStream) -> OutputStream)? = null,
    private val failDelete: Boolean = false,
    private val afterClose: ((File) -> Unit)? = null,
) : MediaBackupArchiveFilePort {

    val created = mutableListOf<File>()
    val deleted = mutableListOf<File>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun createStagingFile(directory: File): File =
        DefaultMediaBackupArchiveFilePort.createStagingFile(directory).also { created += it }

    override fun openOutput(file: File): MediaBackupArchiveFileOutput {
        val base = DefaultMediaBackupArchiveFilePort.openOutput(file)
        return object : MediaBackupArchiveFileOutput {
            override val output: OutputStream = outputFailure?.invoke(base.output) ?: base.output

            override fun sync() {
                syncFailure?.let { throw it }
                base.sync()
            }

            override fun close() {
                base.close()
                if (closed.compareAndSet(false, true)) afterClose?.invoke(file)
            }
        }
    }

    override fun delete(file: File): Boolean {
        deleted += file
        return if (failDelete) false else DefaultMediaBackupArchiveFilePort.delete(file)
    }
}

/** Salida que falla en cuanto se escribe el byte [failAfterBytes]; prueba `disk full`/sink roto. */
private class FailingOutputStream(
    private val delegate: OutputStream,
    private val failAfterBytes: Long,
) : OutputStream() {

    private var written = 0L

    override fun write(value: Int) {
        checkOpen()
        delegate.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkOpen()
        delegate.write(buffer, offset, length)
        written += length
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun checkOpen() {
        if (written >= failAfterBytes) throw IOException("sink failure after $written bytes")
    }
}

/** `Enumeration` de un `ZipFile` ya abierto, sin materializar entrada a entrada. */
private fun java.util.Enumeration<out java.util.zip.ZipEntry>.toList(): List<java.util.zip.ZipEntry> =
    generateSequence { if (hasMoreElements()) nextElement() else null }.toList()
