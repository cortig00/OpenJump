package com.openjump.app.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contrato del lector ZIP del backup con medios (G2b), en JVM y con sandbox propio por prueba.
 *
 * Los contenedores hostiles se construyen con [RawZipBuilder], que escribe el ZIP a mano (cabeceras
 * locales, data descriptors, central directory y EOCD) para poder expresar exactamente cada
 * violacion: cola, huecos, duplicados, ZIP64, comentarios, flags, metodos, rutas y tamanos mentidos.
 * El camino feliz usa el **escritor real** ([MediaBackupArchiveWriter]) para probar el roundtrip.
 * Ninguna prueba toca fixtures de usuario ni construye 12 MiB.
 */
class MediaBackupArchiveReaderTest {

    private lateinit var sandbox: File

    @Before
    fun setUp() {
        sandbox = Files.createTempDirectory("openjump-media-backup-reader-test-").toFile()
        assertTrue(sandbox.isDirectory)
    }

    @After
    fun tearDown() {
        // Sandbox creado por esta prueba: se elimina solo aqui, nunca desde el lector.
        sandbox.deleteRecursively()
    }

    // --- Roundtrip escritor <-> lector ----------------------------------------------------------

    @Test
    fun roundTripWithImagesReadsTheManifestAndTransfersEveryImage() {
        val fixture = fixture(24, 32, 40)
        val prepared = read(writerArchive(fixture))
        try {
            assertEquals(fixture.manifest, prepared.manifest)
            assertEquals(writerManifestName, prepared.manifestEntryName)
            assertEquals(listOf(fixture.keys[0], fixture.keys[1], fixture.keys[2]), prepared.imageKeys)
            assertEquals(3, prepared.imageCount)
            assertEquals(fixture.manifest.archivePlan().totalImageByteCount, prepared.totalImageByteCount)
            assertTrue(prepared.archiveByteCount > 0L)
            assertEquals("the prepared import owns exactly one staging temporary", 1, importTemporaries().size)

            fixture.keys.forEach { key ->
                val sink = ByteArrayOutputStream()
                val transfer = prepared.copyImageTo(key, sink)
                assertEquals(key, transfer.imageKey)
                assertEquals(fixture.imagesByKey.getValue(key).size.toLong(), transfer.byteCount)
                assertEquals(sha256(fixture.imagesByKey.getValue(key)), transfer.sha256)
                assertEquals(fixture.imagesByKey.getValue(key).toList(), sink.toByteArray().toList())
                assertEquals(fixture.imagesByKey.getValue(key).size.toLong(), prepared.imageByteCount(key))
            }
            assertNull(prepared.imageByteCount(String.format(Locale.ROOT, "%032x", 99)))
        } finally {
            prepared.close()
        }
        assertTrue(prepared.isClosed)
        assertNoImportTemporariesLeft()
    }

    @Test
    fun roundTripWithZeroImagesAcceptsTheManifestOnlyArchive() {
        val manifest = MediaBackupManifestCodecTest.sample(
            roots = emptyList(),
            frames = emptyList(),
            marks = emptyList(),
            images = emptyList(),
        )
        val bytes = rawArchive(manifest, emptyMap())
        val prepared = read(bytes)
        try {
            assertEquals(manifest, prepared.manifest)
            assertEquals(0, prepared.imageCount)
            assertEquals(0L, prepared.totalImageByteCount)
            assertThrows(MediaBackupImportException.ImageUnavailable::class.java) {
                prepared.copyImageTo(String.format(Locale.ROOT, "%032x", 1), ByteArrayOutputStream())
            }
        } finally {
            prepared.close()
        }
        assertNoImportTemporariesLeft()
    }

    // --- Estructura ZIP -------------------------------------------------------------------------

    @Test
    fun truncatedArchiveIsRejectedAndLeavesNoTemporary() {
        val fixture = fixture(32)
        val bytes = writerArchive(fixture)
        assertThrows(MediaBackupImportException.StructureRejected::class.java) {
            read(bytes.copyOf(bytes.size - 10))
        }
        assertNoImportTemporariesLeft()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) {
            read(ByteArray(8))
        }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun appendedTrailingJunkAndArchiveCommentsAreRejected() {
        val fixture = fixture(32)
        val builderTail = writerArchive(fixture) + byteArrayOf(0, 1, 2, 3)
        assertThrows(MediaBackupImportException.StructureRejected::class.java) {
            read(builderTail)
        }
        assertNoImportTemporariesLeft()

        val withComment = RawZipBuilder()
            .entry(writerManifestName, MediaBackupManifestCodec.encode(fixture.manifest))
            .entry(imageName(fixture.keys[0]), fixture.imagesByKey.getValue(fixture.keys[0]))
            .archiveComment("openjump")
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) {
            read(withComment)
        }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun multiDiskArchivesAreRejected() {
        val fixture = fixture(32)
        val bytes = rawArchive(fixture.manifest, fixture.imagesByKey) { diskNumbers(1, 0) }
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(bytes) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun moreEntriesThanTheLimitAreRejectedBeforeAnyImageIsRead() {
        val fixture = fixture(16, 16)
        val error = assertThrows(MediaBackupImportException.LimitExceeded::class.java) {
            reader(limits = MediaBackupReaderLimits(maxEntries = 1))
                .read(ByteArrayInputStream(writerArchive(fixture)))
        }
        assertTrue(error.message!!.contains("entries"))
        assertNoImportTemporariesLeft()
    }

    @Test
    fun copyWriteFailureAbortsAndDeletesTheTemporary() {
        val fixture = fixture(16)
        val port = RecordingImportPort(outputFailure = { base -> FailingImportOutputStream(base) })
        assertThrows(MediaBackupImportException.CopyFailed::class.java) {
            reader(port).read(ByteArrayInputStream(writerArchive(fixture)))
        }
        assertEquals(port.created, port.deleted)
        assertNoImportTemporariesLeft()
    }

    @Test
    fun zip64SentinelsAreRejected() {
        val fixture = fixture(32)
        val bytes = rawArchive(fixture.manifest, fixture.imagesByKey) { totalEntriesOverride(0xFFFF) }
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(bytes) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun duplicateEntriesAreRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val bytes = RawZipBuilder()
            .entry(writerManifestName, MediaBackupManifestCodec.encode(fixture.manifest))
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(bytes) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun nonCanonicalNamesIncludingTraversalAbsoluteAndBackslashAreRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val hostileNames = listOf(
            "../evil.png",
            "images/../../$key.png",
            "/etc/$key.png",
            "images\\$key.png",
            "images/$key.jpg",
            "images/",
            "extra.json",
        )
        hostileNames.forEach { hostile ->
            val bytes = RawZipBuilder()
                .entry(writerManifestName, MediaBackupManifestCodec.encode(fixture.manifest))
                .entry(imageName(key), fixture.imagesByKey.getValue(key))
                .entry(hostile, ByteArray(4) { 0x41 })
                .build()
            assertThrows(
                "entry '$hostile' must be rejected",
                MediaBackupImportException.StructureRejected::class.java,
            ) { read(bytes) }
            assertNoImportTemporariesLeft()
        }
    }

    @Test
    fun unsupportedMethodsAndEncryptedFlagsAreRejected() {
        val fixture = fixture(32)
        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)
        val stored = RawZipBuilder()
            .entry(writerManifestName, manifestBytes, method = 0, flags = 0)
            .entry(imageName(fixture.keys[0]), fixture.imagesByKey.getValue(fixture.keys[0]), method = 0, flags = 0)
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(stored) }

        val encrypted = RawZipBuilder()
            .entry(writerManifestName, manifestBytes, flags = 0x0009)
            .entry(imageName(fixture.keys[0]), fixture.imagesByKey.getValue(fixture.keys[0]))
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(encrypted) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun localHeaderAndCentralDirectoryDisagreementsAreRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)
        val imageBytes = fixture.imagesByKey.getValue(key)

        val descriptorMismatch = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(key), imageBytes, descriptorCrc = 0L)
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(descriptorMismatch) }

        val localNameMismatch = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(key), imageBytes, localName = "images/not-the-same.png")
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(localNameMismatch) }

        val hiddenGap = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(key), imageBytes, gapBefore = 4)
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(hiddenGap) }

        val extraField = RawZipBuilder()
            .entry(writerManifestName, manifestBytes, localExtraBytes = 4)
            .build()
        assertThrows(MediaBackupImportException.StructureRejected::class.java) { read(extraField) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun hugeDeclaredUncompressedSizeIsRejectedWithoutInflatingIt() {
        val fixture = fixture(32)
        val bomb = RawZipBuilder()
            .entry(
                writerManifestName,
                MediaBackupManifestCodec.encode(fixture.manifest),
                declareUncompressed = 0x40000000L,
                descriptorUncompressed = 0x40000000L,
            )
            .build()
        assertThrows(MediaBackupImportException.LimitExceeded::class.java) { read(bomb) }
        assertNoImportTemporariesLeft()
    }

    // --- Manifiesto -----------------------------------------------------------------------------

    @Test
    fun manifestPayloadAndCrcCorruptionAreRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)

        val garbageManifest = RawZipBuilder()
            .entry(writerManifestName, "{}".toByteArray(Charsets.UTF_8))
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .build()
        assertThrows(MediaBackupImportException.ManifestRejected::class.java) { read(garbageManifest) }

        val crcCorruption = RawZipBuilder()
            .entry(writerManifestName, manifestBytes, declareCrc = 0L, descriptorCrc = 0L)
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .build()
        assertThrows(MediaBackupImportException.ManifestRejected::class.java) { read(crcCorruption) }

        val declaredLengthLie = RawZipBuilder()
            .entry(
                writerManifestName,
                manifestBytes,
                declareUncompressed = manifestBytes.size.toLong() - 5L,
                descriptorUncompressed = manifestBytes.size.toLong() - 5L,
            )
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .build()
        assertThrows(MediaBackupImportException.ManifestRejected::class.java) { read(declaredLengthLie) }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun manifestThatTheReaderContractRejectsFailsClosed() {
        val fixture = fixture(40)
        val key = fixture.keys.single()
        // Manifiesto sintacticamente valido pero con una version que el contrato no reconoce.
        val patched = String(MediaBackupManifestCodec.encode(fixture.manifest), Charsets.UTF_8)
            .replace("\"formatVersion\":1", "\"formatVersion\":9")
            .toByteArray(Charsets.UTF_8)
        val bytes = RawZipBuilder()
            .entry(writerManifestName, patched)
            .entry(imageName(key), fixture.imagesByKey.getValue(key))
            .build()
        assertThrows(MediaBackupImportException.ManifestRejected::class.java) { read(bytes) }
        assertEquals(40, fixture.imagesByKey.getValue(key).size)
        assertNoImportTemporariesLeft()
    }

    // --- Conjunto exacto de entradas ------------------------------------------------------------

    @Test
    fun missingAndExtraImageEntriesAreRejected() {
        val fixture = fixture(16, 16)
        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)
        val missing = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(fixture.keys[0]), fixture.imagesByKey.getValue(fixture.keys[0]))
            .build()
        assertThrows(MediaBackupImportException.EntrySetRejected::class.java) { read(missing) }

        val extra = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(fixture.keys[0]), fixture.imagesByKey.getValue(fixture.keys[0]))
            .entry(imageName(fixture.keys[1]), fixture.imagesByKey.getValue(fixture.keys[1]))
            .entry(imageName(String.format(Locale.ROOT, "%032x", 42)), ByteArray(4) { 0x5A })
            .build()
        assertThrows(MediaBackupImportException.EntrySetRejected::class.java) { read(extra) }
        assertNoImportTemporariesLeft()
    }

    // --- Integridad de imagen -------------------------------------------------------------------

    @Test
    fun imageSizeCrcAndDigestMismatchesAreRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val manifestBytes = MediaBackupManifestCodec.encode(fixture.manifest)
        val imageBytes = fixture.imagesByKey.getValue(key)

        val declaredSize = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(
                imageName(key),
                imageBytes,
                declareUncompressed = imageBytes.size.toLong() + 1L,
                descriptorUncompressed = imageBytes.size.toLong() + 1L,
            )
            .build()
        val sizeError = assertThrows(MediaBackupImportException.ImageIntegrityFailed::class.java) { read(declaredSize) }
        assertEquals(key, sizeError.imageKey)

        val crcMismatch = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(key), imageBytes, declareCrc = 0L, descriptorCrc = 0L)
            .build()
        val crcError = assertThrows(MediaBackupImportException.ImageIntegrityFailed::class.java) { read(crcMismatch) }
        assertEquals(key, crcError.imageKey)

        // Bytes distintos con CRC interno correcto: solo el SHA-256 del manifiesto puede detectarlo.
        val corrupted = imageBytes.copyOf().also { it[7] = (it[7].toInt() xor 0xFF).toByte() }
        val digestMismatch = RawZipBuilder()
            .entry(writerManifestName, manifestBytes)
            .entry(imageName(key), corrupted)
            .build()
        val digestError = assertThrows(MediaBackupImportException.ImageIntegrityFailed::class.java) {
            read(digestMismatch)
        }
        assertEquals(key, digestError.imageKey)
        assertTrue("the SHA-256 mismatch must be reported", digestError.message!!.contains("SHA-256"))
        assertNoImportTemporariesLeft()
    }

    @Test
    fun undecompressibleImageDataIsRejected() {
        val fixture = fixture(32)
        val key = fixture.keys.single()
        val bytes = RawZipBuilder()
            .entry(writerManifestName, MediaBackupManifestCodec.encode(fixture.manifest))
            .entry(imageName(key), ByteArray(32) { 0x5A }, deflate = false)
            .build()
        val error = assertThrows(MediaBackupImportException.ImageIntegrityFailed::class.java) { read(bytes) }
        assertEquals(key, error.imageKey)
        assertNoImportTemporariesLeft()
    }

    // --- Staging y limites ----------------------------------------------------------------------

    @Test
    fun missingAndSymlinkedStagingDirectoriesAreRejected() {
        val fixture = fixture(8)
        val missing = File(sandbox, "missing")
        assertThrows(MediaBackupImportException.StagingRejected::class.java) {
            MediaBackupArchiveReader(missing).read(ByteArrayInputStream(writerArchive(fixture)))
        }
        assertFalse(missing.exists())
        val link = File(sandbox, "link")
        Files.createSymbolicLink(link.toPath(), sandbox.toPath())
        assertThrows(MediaBackupImportException.StagingRejected::class.java) {
            MediaBackupArchiveReader(link).read(ByteArrayInputStream(writerArchive(fixture)))
        }
    }

    @Test
    fun archiveOverTheCopyLimitIsRejectedAndTheCopyStops() {
        val input = ByteArray(64 * 1024) { 0x41 }
        val consumed = AtomicInteger(0)
        val stream = object : InputStream() {
            private var position = 0
            override fun read(): Int = throw AssertionError("bulk reads only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= input.size) return -1
                val count = minOf(length, 256)
                System.arraycopy(input, position, buffer, offset, count)
                position += count
                consumed.addAndGet(count)
                return count
            }
        }
        val error = assertThrows(MediaBackupImportException.LimitExceeded::class.java) {
            reader(limits = MediaBackupReaderLimits(maxArchiveBytes = 64)).read(stream)
        }
        assertTrue(error.message!!.contains("larger than"))
        assertTrue("the copy must stop as soon as the limit is exceeded", consumed.get() <= 512)
        assertNoImportTemporariesLeft()
    }

    // --- Cancelacion ----------------------------------------------------------------------------

    @Test
    fun cancellationDuringCopyDeletesOnlyItsOwnTemporary() {
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val fixture = fixture(64)
        val copied = AtomicInteger(0)
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(writerArchive(fixture))
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = delegate.read(buffer, offset, length)
                if (count > 0) copied.addAndGet(count)
                return count
            }
        }
        val port = RecordingImportPort()
        val error = assertThrows(MediaBackupImportException.Canceled::class.java) {
            reader(port).read(input, MediaBackupArchiveCancellation { copied.get() > 0 })
        }
        assertEquals(MediaBackupImportException.Canceled, error)
        assertTrue(port.created.isNotEmpty())
        assertEquals(port.created, port.deleted)
        assertNoImportTemporariesLeft()
        assertTrue(sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun cancellationDuringVerificationDeletesTheTemporary() {
        val fixture = fixture(64, 64, 64)
        val copied = AtomicBoolean(false)
        val consults = AtomicInteger(0)
        val port = RecordingImportPort(afterClose = { copied.set(true) })
        val error = assertThrows(MediaBackupImportException.Canceled::class.java) {
            reader(port).read(
                ByteArrayInputStream(writerArchive(fixture)),
                MediaBackupArchiveCancellation {
                    copied.get() && consults.incrementAndGet() >= 8
                },
            )
        }
        assertEquals(MediaBackupImportException.Canceled, error)
        assertTrue("cancellation must be observed after the copy", consults.get() >= 8)
        assertNoImportTemporariesLeft()
    }

    // --- Limpieza, propiedad y transferencia ----------------------------------------------------

    @Test
    fun cleanupFailureIsReportedAndTheTemporaryIsPreserved() {
        val fixture = fixture(16)
        val port = RecordingImportPort(failDelete = true)
        val archive = writerArchive(fixture)
        val bytes = archive.copyOf(archive.size - 6)
        val error = assertThrows(MediaBackupImportException.CleanupFailed::class.java) {
            reader(port).read(ByteArrayInputStream(bytes))
        }
        assertTrue(error.cause is MediaBackupImportException.StructureRejected)
        assertEquals(1, port.created.size)
        assertTrue("the reader must preserve the temporary it could not delete", port.created.single().isFile)
        // Limpieza manual de la prueba (el sandbox es suyo), nunca del lector.
        assertTrue(port.created.single().delete())
        assertNoImportTemporariesLeft()
    }

    @Test
    fun closeIsIdempotentAndOnlyDeletesItsOwnTemporary() {
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val decoy = File(sandbox, "openjump-media-backup-import-decoy.zip.tmp").apply { writeText("not ours") }
        val fixture = fixture(16)
        val prepared = read(writerArchive(fixture))
        prepared.close()
        prepared.close()
        assertTrue(prepared.isClosed)
        assertThrows(MediaBackupImportException.ArchiveClosed::class.java) {
            prepared.copyImageTo(fixture.keys.single(), ByteArrayOutputStream())
        }
        assertNoImportTemporariesLeft()
        assertTrue(sentinel.isFile)
        assertTrue(decoy.isFile)
        assertEquals("keep me", sentinel.readText())
        assertEquals("not ours", decoy.readText())
    }

    @Test
    fun transferReverifiesTheStagedArchiveAndNeverSucceedsOnPartialWrites() {
        val fixture = fixture(48)
        val key = fixture.keys.single()
        var staged: File? = null
        val prepared = reader(RecordingImportPort(afterClose = { staged = it })).read(
            ByteArrayInputStream(writerArchive(fixture)),
        )
        try {
            // Un sumidero roto aborta la transferencia y no informa exito, aunque haya recibido bytes.
            val received = ByteArrayOutputStream()
            val failing = object : OutputStream() {
                private var written = 0
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (written > 8) throw IOException("sink broken")
                    received.write(buffer, offset, length)
                    written += length
                }
            }
            val sinkError = assertThrows(MediaBackupImportException.TransferFailed::class.java) {
                prepared.copyImageTo(key, failing)
            }
            assertEquals(key, sinkError.imageKey)
            assertTrue("partial bytes may have reached the sink", received.size() > 0)
            assertTrue(received.size() < fixture.imagesByKey.getValue(key).size)

            // Cancelacion durante la transferencia: aborta sin exito y la importacion sigue usable.
            assertThrows(MediaBackupImportException.Canceled::class.java) {
                prepared.copyImageTo(key, ByteArrayOutputStream(), MediaBackupArchiveCancellation { true })
            }
            val good = ByteArrayOutputStream()
            prepared.copyImageTo(key, good)
            assertEquals(fixture.imagesByKey.getValue(key).toList(), good.toByteArray().toList())

            // El contenedor staged se altera despues de validar: la transferencia lo recomprueba.
            val file = requireNonNull(staged)
            assertTrue(file.isFile)
            RandomAccessFile(file, "rw").use { it.setLength(it.length() - 10L) }
            val tampered = assertThrows(MediaBackupImportException.ImageIntegrityFailed::class.java) {
                prepared.copyImageTo(key, ByteArrayOutputStream())
            }
            assertEquals(key, tampered.imageKey)
        } finally {
            prepared.close()
        }
        assertNoImportTemporariesLeft()
    }

    @Test
    fun failuresNeverTouchPreexistingFilesInTheStagingDirectory() {
        val sentinel = File(sandbox, "sentinel.txt").apply { writeText("keep me") }
        val decoy = File(sandbox, "openjump-media-backup-import-decoy.zip.tmp").apply { writeText("not ours") }
        val fixture = fixture(16)
        assertThrows(MediaBackupImportException.EntrySetRejected::class.java) {
            read(
                RawZipBuilder()
                    .entry(writerManifestName, MediaBackupManifestCodec.encode(fixture.manifest))
                    .build(),
            )
        }
        assertTrue(sentinel.isFile)
        assertTrue(decoy.isFile)
        assertEquals("keep me", sentinel.readText())
        assertEquals(listOf(decoy.name, sentinel.name).sorted(), sandbox.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun portSyncFailureAbortsAndDeletesTheTemporary() {
        val fixture = fixture(16)
        val port = RecordingImportPort(syncFailure = IOException("fsync failed"))
        assertThrows(MediaBackupImportException.SyncFailed::class.java) {
            reader(port).read(ByteArrayInputStream(writerArchive(fixture)))
        }
        assertEquals(port.created, port.deleted)
        assertNoImportTemporariesLeft()
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun reader(
        port: RecordingImportPort = RecordingImportPort(),
        limits: MediaBackupReaderLimits = MediaBackupReaderLimits(),
    ) = MediaBackupArchiveReader(sandbox, port, limits)

    private fun read(bytes: ByteArray): MediaBackupPreparedImport =
        reader().read(ByteArrayInputStream(bytes))

    private fun writerArchive(fixture: Fixture): ByteArray {
        val prepared = MediaBackupArchiveWriter(sandbox).write(
            fixture.manifest,
            MediaBackupImageSource { key -> ByteArrayInputStream(fixture.imagesByKey.getValue(key)) },
        )
        try {
            return prepared.file.readBytes()
        } finally {
            prepared.close()
        }
    }

    private fun rawArchive(
        manifest: MediaBackupManifest,
        images: Map<String, ByteArray>,
        configure: RawZipBuilder.() -> Unit = {},
    ): ByteArray = RawZipBuilder()
        .entry(writerManifestName, MediaBackupManifestCodec.encode(manifest))
        .also { builder -> images.forEach { (key, bytes) -> builder.entry(imageName(key), bytes) } }
        .apply(configure)
        .build()

    private fun importTemporaries(): List<File> = sandbox.listFiles().orEmpty().filter {
        it.name.startsWith(MediaBackupImportFiles.TEMPORARY_PREFIX) &&
            it.name.endsWith(MediaBackupImportFiles.TEMPORARY_SUFFIX)
    }

    private fun assertNoImportTemporariesLeft() {
        assertEquals("no import temporary may survive", emptyList<File>(), importTemporaries())
    }

    private class Fixture(
        val manifest: MediaBackupManifest,
        val imagesByKey: Map<String, ByteArray>,
    ) {
        val keys: List<String> get() = imagesByKey.keys.toList()
    }

    private fun fixture(vararg sizes: Int): Fixture {
        require(sizes.isNotEmpty())
        val keys = sizes.indices.map { String.format(Locale.ROOT, "%032x", it + 1) }
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
        const val writerManifestName = "manifest.json"

        fun imageName(key: String): String = "images/$key.png"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

        fun requireNonNull(file: File?): File = file ?: throw AssertionError("staging file was not observed")
    }
}

/**
 * Constructor de ZIP por bytes para pruebas: escribe cabeceras locales, data descriptors, entradas
 * del directorio central y EOCD, y permite mentir en cada campo por separado para expresar una
 * violacion concreta sin depender de que `ZipOutputStream` la permita.
 */
private class RawZipBuilder {

    private class Entry(
        val name: String,
        val data: ByteArray,
        val deflate: Boolean,
        val method: Int,
        val flags: Int,
        val localName: String?,
        val declareUncompressed: Long,
        val declareCompressed: Long?,
        val declareCrc: Long?,
        val useDataDescriptor: Boolean,
        val descriptorCrc: Long?,
        val descriptorCompressed: Long?,
        val descriptorUncompressed: Long?,
        val localExtraBytes: Int,
        val centralExtra: ByteArray,
        val centralCommentBytes: Int,
        val diskStart: Int,
        val gapBefore: Int,
    )

    private val entries = mutableListOf<Entry>()
    private var comment = ByteArray(0)
    private var trailing = ByteArray(0)
    private var entriesOnDiskOverride: Int? = null
    private var totalEntriesOverride: Int? = null
    private var diskNumberOverride = 0
    private var centralDiskOverride = 0

    fun entry(
        name: String,
        data: ByteArray,
        deflate: Boolean = true,
        method: Int = 8,
        flags: Int = 0x0008,
        localName: String? = null,
        declareUncompressed: Long = data.size.toLong(),
        declareCompressed: Long? = null,
        declareCrc: Long? = null,
        useDataDescriptor: Boolean = true,
        descriptorCrc: Long? = null,
        descriptorCompressed: Long? = null,
        descriptorUncompressed: Long? = null,
        localExtraBytes: Int = 0,
        centralExtra: ByteArray = ByteArray(0),
        centralCommentBytes: Int = 0,
        diskStart: Int = 0,
        gapBefore: Int = 0,
    ): RawZipBuilder = apply {
        entries += Entry(
            name = name,
            data = data,
            deflate = deflate,
            method = method,
            flags = flags,
            localName = localName,
            declareUncompressed = declareUncompressed,
            declareCompressed = declareCompressed,
            declareCrc = declareCrc,
            useDataDescriptor = useDataDescriptor,
            descriptorCrc = descriptorCrc,
            descriptorCompressed = descriptorCompressed,
            descriptorUncompressed = descriptorUncompressed,
            localExtraBytes = localExtraBytes,
            centralExtra = centralExtra,
            centralCommentBytes = centralCommentBytes,
            diskStart = diskStart,
            gapBefore = gapBefore,
        )
    }

    fun archiveComment(value: String): RawZipBuilder = apply { comment = value.toByteArray(Charsets.US_ASCII) }

    fun trailingJunk(value: ByteArray): RawZipBuilder = apply { trailing = value }

    fun totalEntriesOverride(value: Int): RawZipBuilder = apply { totalEntriesOverride = value }

    fun diskNumbers(entryDisk: Int, centralDisk: Int): RawZipBuilder = apply {
        diskNumberOverride = entryDisk
        centralDiskOverride = centralDisk
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { entry ->
            out.write(ByteArray(entry.gapBefore))
            val localOffset = out.size().toLong()
            val stored = if (entry.method == 8 && entry.deflate) deflate(entry.data) else entry.data
            val crc = crc32(entry.data)
            val nameBytes = (entry.localName ?: entry.name).toByteArray(Charsets.US_ASCII)
            val centralNameBytes = entry.name.toByteArray(Charsets.US_ASCII)
            val localCrc = if (entry.useDataDescriptor) 0L else (entry.declareCrc ?: crc)
            val localCompressed = if (entry.useDataDescriptor) 0L else (entry.declareCompressed ?: stored.size.toLong())
            val localUncompressed = if (entry.useDataDescriptor) 0L else entry.declareUncompressed

            writeU32(out, 0x04034B50L)
            writeU16(out, 20)
            writeU16(out, entry.flags)
            writeU16(out, entry.method)
            writeU16(out, 0)
            writeU16(out, 0)
            writeU32(out, localCrc)
            writeU32(out, localCompressed)
            writeU32(out, localUncompressed)
            writeU16(out, nameBytes.size)
            writeU16(out, entry.localExtraBytes)
            out.write(nameBytes)
            out.write(ByteArray(entry.localExtraBytes))
            out.write(stored)
            if (entry.useDataDescriptor) {
                writeU32(out, 0x08074B50L)
                writeU32(out, entry.descriptorCrc ?: crc)
                writeU32(out, entry.descriptorCompressed ?: stored.size.toLong())
                writeU32(out, entry.descriptorUncompressed ?: entry.declareUncompressed)
            }

            writeU32(central, 0x02014B50L)
            writeU16(central, 20)
            writeU16(central, 20)
            writeU16(central, entry.flags)
            writeU16(central, entry.method)
            writeU16(central, 0)
            writeU16(central, 0)
            writeU32(central, entry.declareCrc ?: crc)
            writeU32(central, entry.declareCompressed ?: stored.size.toLong())
            writeU32(central, entry.declareUncompressed)
            writeU16(central, centralNameBytes.size)
            writeU16(central, entry.centralExtra.size)
            writeU16(central, entry.centralCommentBytes)
            writeU16(central, entry.diskStart)
            writeU16(central, 0)
            writeU32(central, 0L)
            writeU32(central, localOffset)
            central.write(centralNameBytes)
            central.write(entry.centralExtra)
            central.write(ByteArray(entry.centralCommentBytes))
        }
        val centralOffset = out.size().toLong()
        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        val centralSize = centralBytes.size.toLong()
        val commentBytes = comment
        writeU32(out, 0x06054B50L)
        writeU16(out, diskNumberOverride)
        writeU16(out, centralDiskOverride)
        writeU16(out, entriesOnDiskOverride ?: entries.size)
        writeU16(out, totalEntriesOverride ?: entries.size)
        writeU32(out, centralSize)
        writeU32(out, centralOffset)
        writeU16(out, commentBytes.size)
        out.write(commentBytes)
        out.write(trailing)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeU16(output: OutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(output: OutputStream, value: Long) {
        output.write((value and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}

/** Puerto real con ganchos de fallo y registro: solo las pruebas inyectan fallos. */
private class RecordingImportPort(
    private val syncFailure: IOException? = null,
    private val outputFailure: ((OutputStream) -> OutputStream)? = null,
    private val failDelete: Boolean = false,
    private val afterClose: ((File) -> Unit)? = null,
) : MediaBackupImportFilePort {

    val created = mutableListOf<File>()
    val deleted = mutableListOf<File>()
    private val closed = AtomicBoolean(false)

    override fun createStagingFile(directory: File): File =
        DefaultMediaBackupImportFilePort.createStagingFile(directory).also { created += it }

    override fun openOutput(file: File): MediaBackupImportFileOutput {
        val base = DefaultMediaBackupImportFilePort.openOutput(file)
        return object : MediaBackupImportFileOutput {
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
        return if (failDelete) false else DefaultMediaBackupImportFilePort.delete(file)
    }
}

/** Salida que falla en la primera escritura: prueba un `disk full`/sumidero roto durante la copia. */
private class FailingImportOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = throw IOException("staging sink broken")
    override fun write(buffer: ByteArray, offset: Int, length: Int) = throw IOException("staging sink broken")
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
