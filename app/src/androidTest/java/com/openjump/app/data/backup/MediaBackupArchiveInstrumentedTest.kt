package com.openjump.app.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
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
 * Verificacion instrumentada (Android real, API 35) del contenedor ZIP del backup con imagenes
 * (G2a escritor + G2b lector) **antes** de integrarlo.
 *
 * Por que existe esta clase: las pruebas JVM del escritor y del lector demuestran el contrato sobre
 * `java.util.zip` del host. En Android ese paquete es `libcore`, y su gramatica ZIP (flags de
 * proposito general, campos extra, descriptor de datos, atributos de host) no esta garantizada por
 * el contrato de Java SE. Esta clase escribe y lee **de verdad** el contenedor en el dispositivo y
 * comprueba la gramatica observada contra la que el lector estricto acepta. Si la gramatica de
 * Android difiere (flags/extra/atributos), el test falla y deja el valor observado en el mensaje y
 * en logcat; no se relaja ninguna comprobacion de produccion.
 *
 * Reglas de aislamiento:
 *  - sandbox **unico** `cacheDir/openjump-media-backup-instrumented-<uuid>` (un UUID por metodo);
 *  - staging de escritor y de lector propios dentro de ese sandbox;
 *  - fixtures sinteticos autocontenidos: documento legacy valido, manifiesto V2 valido con dos
 *    raices/fotogramas, marcas medibles y PNG generados en memoria (bytes/SHA coincidentes);
 *  - sin Room, sin base de datos, sin video de usuario, sin UI ni actividad;
 *  - la limpieza borra solo el arbol de ese UUID y comprueba que vive directamente bajo `cacheDir`.
 * No se modifica produccion desde esta clase.
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupArchiveInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runId: String = UUID.randomUUID().toString()

    private val sandbox: File = File(context.cacheDir, "$SANDBOX_PREFIX$runId")
    private lateinit var writerStaging: File
    private lateinit var readerStaging: File
    private lateinit var sentinel: File

    private val sentinelBytes = "openjump-media-backup-instrumented-sentinel-$runId".toByteArray(Charsets.UTF_8)

    @Before
    fun createOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        assertFalse("the sandbox must not pre-exist for this UUID", sandbox.exists())
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, sandbox.canonicalFile.parentFile)
        assertTrue("the unique sandbox must be created", sandbox.mkdirs())
        writerStaging = File(sandbox, "writer-staging").also { assertTrue(it.mkdirs()) }
        readerStaging = File(sandbox, "reader-staging").also { assertTrue(it.mkdirs()) }
        sentinel = File(sandbox, "sentinel-own.bin").also {
            it.writeBytes(sentinelBytes)
        }
        Log.i(TAG, "sandbox=${sandbox.name} cacheDirEntries=${cacheRoot.list()?.size ?: 0}")
    }

    /**
     * Borra **solo** el arbol de este UUID. Antes de tocar nada comprueba que es el directorio
     * propio, hijo directo de `cacheDir` y con el UUID de este metodo.
     */
    @After
    fun deleteOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = sandbox.canonicalFile
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, root.parentFile)
        assertEquals("the sandbox must carry this method's UUID", "$SANDBOX_PREFIX$runId", root.name)
        deleteOwnTree(root)
        assertFalse("the sandbox must be gone after cleanup", root.exists())
    }

    // --- 1. Cero imagenes -----------------------------------------------------------------------

    @Test
    fun zeroImageArchiveRoundTripKeepsLegacyPayloadAndDeclaresNoMedia() {
        val manifest = manifestWith(emptyList())
        val writer = MediaBackupArchiveWriter(writerStaging)
        val prepared = writer.write(
            manifest,
            MediaBackupImageSource { key -> throw AssertionError("a zero-image backup must never open '$key'") },
        )
        try {
            assertEquals(MEDIA_BACKUP_MANIFEST_ENTRY, prepared.manifestEntryName)
            assertEquals(0, prepared.imageCount)
            assertEquals(emptyList<String>(), prepared.imageEntryNames)
            assertEquals(0L, prepared.totalImageByteCount)
            assertTrue(prepared.file.isFile)
            assertEquals(prepared.file.length(), prepared.archiveByteCount)
            assertTrue("the writer must own its temporary inside its staging", prepared.file.parentFile?.canonicalFile == writerStaging.canonicalFile)
            assertEquals(setOf(prepared.file.name), stagingNames(writerStaging))

            val grammar = verifyAndroidZipGrammar(prepared.file)
            assertEquals(listOf(MEDIA_BACKUP_MANIFEST_ENTRY), grammar.map { it.name })

            ZipFile(prepared.file).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(listOf(MEDIA_BACKUP_MANIFEST_ENTRY), entries.map { it.name })
                val manifestEntry = entries.single()
                assertEquals(prepared.manifestByteCount, manifestEntry.size)
                val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                assertEquals(prepared.manifestByteCount, bytes.size.toLong())
                assertEquals(prepared.manifestSha256, sha256Hex(bytes))
            }

            val reader = MediaBackupArchiveReader(readerStaging)
            val input = FileInputStream(prepared.file)
            input.use { stream ->
                val imported = reader.read(stream)
                imported.use { import ->
                    assertEquals(0, import.imageCount)
                    assertEquals(emptyList<String>(), import.imageKeys)
                    assertEquals(manifest.legacy, import.manifest.legacy)
                    assertEquals(canonicalJson(manifest), canonicalJson(import.manifest))
                    assertEquals(1, stagingNames(readerStaging).size)
                    val unavailable = assertThrows(MediaBackupImportException::class.java) {
                        import.copyImageTo(FIXTURE_KEY_A, ByteArrayOutputStream())
                    }
                    assertTrue(
                        "an undeclared image must be unavailable, was $unavailable",
                        unavailable is MediaBackupImportException.ImageUnavailable,
                    )
                    assertEquals(sentinelBytes.size.toLong(), sentinel.length())
                    assertArrayEquals(sentinelBytes, sentinel.readBytes())
                }
            }
            assertEquals(emptySet<String>(), stagingNames(readerStaging))
        } finally {
            prepared.close()
        }
        assertEquals(emptySet<String>(), stagingNames(writerStaging))
        assertArrayEquals(sentinelBytes, sentinel.readBytes())
    }

    // --- 2. Varias imagenes: bytes, digests y anclaje por clave --------------------------------

    @Test
    fun twoRootArchiveRoundTripTransfersExactPngBytesByKey() {
        val images = listOf(
            fixtureImage(FIXTURE_KEY_A, png(64, 48, seed = 11)),
            fixtureImage(FIXTURE_KEY_B, png(32, 40, seed = 29)),
        )
        val manifest = manifestWith(images)
        val bytesByKey = images.associate { it.key to it.bytes }
        assertNotEquals(
            "fixture images must differ so a key swap cannot pass",
            images[0].sha256,
            images[1].sha256,
        )

        val opened = mutableListOf<String>()
        val prepared = MediaBackupArchiveWriter(writerStaging).write(
            manifest,
            MediaBackupImageSource { key ->
                opened += key
                ByteArrayInputStream(bytesByKey.getValue(key))
            },
        )
        try {
            assertEquals(images.map { it.key }.sorted(), opened)
            assertEquals(2, prepared.imageCount)
            assertEquals(listOf("images/$FIXTURE_KEY_A.png", "images/$FIXTURE_KEY_B.png"), prepared.imageEntryNames)
            assertEquals(images.sumOf { it.bytes.size.toLong() }, prepared.totalImageByteCount)

            val grammar = verifyAndroidZipGrammar(prepared.file)
            assertEquals(
                listOf(MEDIA_BACKUP_MANIFEST_ENTRY, "images/$FIXTURE_KEY_A.png", "images/$FIXTURE_KEY_B.png"),
                grammar.map { it.name },
            )

            ZipFile(prepared.file).use { zip ->
                images.forEach { image ->
                    val entry = zip.getEntry(MediaBackupArchive.imageEntryName(image.key))
                    assertTrue("the container must carry the entry of ${image.key}", entry != null)
                    assertEquals(image.bytes.size.toLong(), entry!!.size)
                    assertEquals(crc32(image.bytes), entry.crc)
                }
            }

            val reader = MediaBackupArchiveReader(readerStaging)
            val imported = FileInputStream(prepared.file).use { reader.read(it) }
            imported.use { import ->
                assertEquals(images.map { it.key }.sorted(), import.imageKeys)
                assertEquals(2, import.manifest.angleV2.roots.size)
                assertEquals(2, import.manifest.angleV2.frames.size)
                assertEquals(4, import.manifest.angleV2.marks.size)
                assertEquals(manifest.legacy, import.manifest.legacy)
                assertEquals("canonical manifest must survive the round trip", canonicalJson(manifest), canonicalJson(import.manifest))

                // Anclaje por clave: cada fila del manifiesto sigue apuntando a SU imagen.
                val rowsByKey = import.manifest.angleV2.frames.associateBy { it.imageKey }
                images.forEach { image ->
                    val row = rowsByKey.getValue(image.key)
                    assertEquals(image.sha256, row.imageSha256)
                    assertEquals(image.bytes.size.toLong(), row.imageByteCount)
                    assertEquals(image.width, row.imageWidth)
                    assertEquals(image.height, row.imageHeight)
                    assertEquals(MEDIA_BACKUP_IMAGE_MIME_TYPE, row.imageMimeType)
                    assertEquals(image.bytes.size.toLong(), import.imageByteCount(image.key))
                }
                val declaredByKey = import.manifest.angleV2.images.associateBy { it.imageKey }
                images.forEach { image ->
                    val declared = declaredByKey.getValue(image.key)
                    assertEquals(image.bytes.size.toLong(), declared.byteCount)
                    assertEquals(image.sha256, declared.sha256)
                }

                images.forEach { image ->
                    // Ruta 1: memoria. Los bytes extraidos son exactamente los del fixture.
                    val toMemory = ByteArrayOutputStream()
                    val transfer = import.copyImageTo(image.key, toMemory)
                    assertEquals(image.key, transfer.imageKey)
                    assertEquals(image.bytes.size.toLong(), transfer.byteCount)
                    assertEquals(image.sha256, transfer.sha256)
                    val extracted = toMemory.toByteArray()
                    assertArrayEquals("copyImageTo must deliver the exact bytes of ${image.key}", image.bytes, extracted)
                    assertEquals(image.sha256, sha256Hex(extracted))
                    assertPngDimensions(extracted, image.width, image.height)

                    // Ruta 2: fichero propio dentro del sandbox. Mismo contrato byte a byte.
                    val sink = File(sandbox, "extracted-${image.key}.png")
                    FileOutputStream(sink).use { output -> import.copyImageTo(image.key, output) }
                    assertEquals(image.sha256, sha256Hex(sink.readBytes()))
                    assertArrayEquals(image.bytes, sink.readBytes())
                }

                // La segunda transferencia de la misma clave sigue siendo valida y estable.
                val again = ByteArrayOutputStream()
                import.copyImageTo(FIXTURE_KEY_A, again)
                assertArrayEquals(bytesByKey.getValue(FIXTURE_KEY_A), again.toByteArray())

                assertEquals(1, stagingNames(readerStaging).size)
            }
            assertEquals(emptySet<String>(), stagingNames(readerStaging))
        } finally {
            prepared.close()
        }
        assertEquals(emptySet<String>(), stagingNames(writerStaging))
        assertArrayEquals(sentinelBytes, sentinel.readBytes())
    }

    // --- 3. Cancelacion y fallos del origen -----------------------------------------------------

    @Test
    fun cancellationAndImageFailuresAreRejectedAndLeaveNoTemporary() {
        val images = listOf(
            fixtureImage(FIXTURE_KEY_A, png(24, 18, seed = 5)),
            fixtureImage(FIXTURE_KEY_B, png(20, 16, seed = 9)),
        )
        val manifest = manifestWith(images)
        val bytesByKey = images.associate { it.key to it.bytes }
        val writer = MediaBackupArchiveWriter(writerStaging)

        // a) Cancelado antes de cualquier E/S: no se crea ni un temporal.
        val canceledBeforeStart = assertThrows(MediaBackupArchiveException::class.java) {
            writer.write(
                manifest,
                MediaBackupImageSource { key -> ByteArrayInputStream(bytesByKey.getValue(key)) },
                MediaBackupArchiveCancellation { true },
            )
        }
        assertTrue("must be a cancellation, was $canceledBeforeStart", canceledBeforeStart is MediaBackupArchiveException.Canceled)
        assertEquals(emptySet<String>(), stagingNames(writerStaging))

        // b) Cancelado mientras se copia una imagen: el temporal propio se elimina.
        var cancelAfterOpen = false
        val canceledDuringCopy = assertThrows(MediaBackupArchiveException::class.java) {
            writer.write(
                manifest,
                MediaBackupImageSource { key ->
                    cancelAfterOpen = true
                    ByteArrayInputStream(bytesByKey.getValue(key))
                },
                MediaBackupArchiveCancellation { cancelAfterOpen },
            )
        }
        assertTrue("must be a cancellation, was $canceledDuringCopy", canceledDuringCopy is MediaBackupArchiveException.Canceled)
        assertEquals(emptySet<String>(), stagingNames(writerStaging))

        // c) Imagen ausente: se aborta sin dejar contenedor.
        val missing = assertThrows(MediaBackupArchiveException::class.java) {
            writer.write(manifest, MediaBackupImageSource { null })
        }
        assertTrue("must be a missing image, was $missing", missing is MediaBackupArchiveException.ImageMissing)
        assertEquals(FIXTURE_KEY_A, (missing as MediaBackupArchiveException.ImageMissing).imageKey)
        assertEquals(emptySet<String>(), stagingNames(writerStaging))

        // d) Mismo tamano, digest distinto: se aborta sin dejar contenedor.
        val wrongBytes = bytesByKey.mapValues { (_, value) ->
            value.copyOf().also { copy -> copy[copy.size / 2] = (copy[copy.size / 2].toInt() xor 0x01).toByte() }
        }
        val digestMismatch = assertThrows(MediaBackupArchiveException::class.java) {
            writer.write(manifest, MediaBackupImageSource { key -> ByteArrayInputStream(wrongBytes.getValue(key)) })
        }
        assertTrue("must be a digest mismatch, was $digestMismatch", digestMismatch is MediaBackupArchiveException.ImageDigestMismatch)
        assertEquals(emptySet<String>(), stagingNames(writerStaging))

        // e) Cancelacion del lector antes de copiar: no se crea ni un temporal de importacion.
        val goodArchive = writer.write(manifest, MediaBackupImageSource { key -> ByteArrayInputStream(bytesByKey.getValue(key)) })
        try {
            val reader = MediaBackupArchiveReader(readerStaging)
            val readCanceled = assertThrows(MediaBackupImportException::class.java) {
                FileInputStream(goodArchive.file).use { stream ->
                    reader.read(stream, MediaBackupArchiveCancellation { true })
                }
            }
            assertTrue("must be a cancellation, was $readCanceled", readCanceled is MediaBackupImportException.Canceled)
            assertEquals(emptySet<String>(), stagingNames(readerStaging))
        } finally {
            goodArchive.close()
        }
        assertEquals(emptySet<String>(), stagingNames(writerStaging))
        assertEquals(emptySet<String>(), stagingNames(readerStaging))

        // Nada de lo anterior toco el centinela.
        assertEquals(sha256Hex(sentinelBytes), sha256Hex(sentinel.readBytes()))
    }

    // --- 4. ZIP corrupto / truncado / con cola -------------------------------------------------

    @Test
    fun truncatedCorruptAndTailAppendedArchivesAreRejectedWithoutLeavingTemporaries() {
        val image = fixtureImage(FIXTURE_KEY_A, png(28, 22, seed = 3))
        val manifest = manifestWith(listOf(image))
        val prepared = MediaBackupArchiveWriter(writerStaging).write(
            manifest,
            MediaBackupImageSource { key -> ByteArrayInputStream(if (key == image.key) image.bytes else throw AssertionError(key)) },
        )
        val goodBytes = prepared.file.readBytes()
        prepared.close()
        assertEquals(emptySet<String>(), stagingNames(writerStaging))

        val reader = MediaBackupArchiveReader(readerStaging)
        val sentinelDigest = sha256Hex(sentinel.readBytes())

        // a) Truncado: sin EOCD al final, la estructura no puede probarse.
        val truncated = File(sandbox, "truncated.zip").also {
            it.writeBytes(goodBytes.copyOf((goodBytes.size - EOCD_BYTES).toInt()))
        }
        val truncatedFailure = assertThrows(MediaBackupImportException::class.java) {
            FileInputStream(truncated).use { reader.read(it) }
        }
        assertTrue(
            "a truncated container must be rejected as invalid structure, was $truncatedFailure",
            truncatedFailure is MediaBackupImportException.StructureRejected,
        )
        assertEquals(emptySet<String>(), stagingNames(readerStaging))

        // b) Cola anadida despues del EOCD: el EOCD debe terminar el fichero.
        val withTail = File(sandbox, "tail.zip").also {
            it.writeBytes(goodBytes + byteArrayOf(0x50, 0x4B, 0x00, 0x00, 0x2A))
        }
        val tailFailure = assertThrows(MediaBackupImportException::class.java) {
            FileInputStream(withTail).use { reader.read(it) }
        }
        assertTrue(
            "a container with hidden trailing bytes must be rejected, was $tailFailure",
            tailFailure is MediaBackupImportException.StructureRejected,
        )
        assertEquals(emptySet<String>(), stagingNames(readerStaging))

        // c) Bytes comprimidos corruptos con directorio central intacto: solo una relectura real
        //    de la imagen puede detectarlo. El lector debe rechazar; la capa exacta puede variar.
        val corrupted = File(sandbox, "corrupted.zip").also { it.writeBytes(goodBytes) }
        corruptCompressedPayload(corrupted, MediaBackupArchive.imageEntryName(image.key))
        val corruptFailure = assertThrows(MediaBackupImportException::class.java) {
            FileInputStream(corrupted).use { reader.read(it) }
        }
        assertTrue(
            "a container with corrupted image bytes must be rejected by manifest or image verification, was $corruptFailure",
            corruptFailure is MediaBackupImportException.StructureRejected ||
                corruptFailure is MediaBackupImportException.ManifestRejected ||
                corruptFailure is MediaBackupImportException.ImageIntegrityFailed,
        )
        assertEquals(emptySet<String>(), stagingNames(readerStaging))

        // d) Basura: no es un contenedor.
        val garbage = File(sandbox, "garbage.zip").also { it.writeBytes(ByteArray(64) { 0x7F.toByte() }) }
        val garbageFailure = assertThrows(MediaBackupImportException::class.java) {
            FileInputStream(garbage).use { reader.read(it) }
        }
        assertTrue(
            "arbitrary bytes must never be accepted as a container, was $garbageFailure",
            garbageFailure is MediaBackupImportException.StructureRejected,
        )
        assertEquals(emptySet<String>(), stagingNames(readerStaging))

        // Ningun rechazo toco el centinela ni los ficheros de trabajo. El contenedor corrupto
        // conserva exactamente la longitud del valido (solo cambio una ventana de bytes).
        assertEquals(sentinelDigest, sha256Hex(sentinel.readBytes()))
        val corruptedBytes = File(sandbox, "corrupted.zip").readBytes()
        assertEquals("corruption must not change the archive length", goodBytes.size.toLong(), corruptedBytes.size.toLong())
        assertFalse("the corrupted container must differ from the valid one", goodBytes.contentEquals(corruptedBytes))
        assertEquals(goodBytes.size.toLong() + 5L, withTail.length())
        assertEquals(goodBytes.size.toLong() - EOCD_BYTES, truncated.length())
    }

    // --- 5. close: solo el temporal propio ------------------------------------------------

    @Test
    fun closeDeletesOnlyItsOwnTemporaryAndIsIdempotent() {
        val image = fixtureImage(FIXTURE_KEY_A, png(16, 12, seed = 2))
        val manifest = manifestWith(listOf(image))

        // Ficheros ajenos (dentro de nuestro sandbox) que close() no debe tocar nunca.
        val foreign = File(writerStaging, "keep-me.txt").also { it.writeText("do not delete") }
        val foreignPrefixWrongSuffix = File(writerStaging, "openjump-media-backup-not-ours.txt").also {
            it.writeText("foreign prefix lookalike")
        }
        val directoryLookalike = File(writerStaging, "openjump-media-backup-directory.zip.tmp").also {
            assertTrue(it.mkdirs())
        }

        val prepared = MediaBackupArchiveWriter(writerStaging).write(
            manifest,
            MediaBackupImageSource { key -> ByteArrayInputStream(image.bytes) },
        )
        val writerTemporary = prepared.file
        assertTrue("the writer must own a real temporary file", writerTemporary.isFile)
        assertTrue(
            "the writer temporary must carry the documented own prefix/suffix",
            writerTemporary.name.startsWith(WRITER_TEMP_PREFIX) && writerTemporary.name.endsWith(WRITER_TEMP_SUFFIX),
        )
        assertEquals(
            setOf(writerTemporary.name, foreign.name, foreignPrefixWrongSuffix.name, directoryLookalike.name),
            stagingNames(writerStaging),
        )

        val imported = MediaBackupArchiveReader(readerStaging).read(FileInputStream(writerTemporary))
        val readerTemporaryName = stagingNames(readerStaging).single()
        assertTrue(
            "the reader temporary must carry the documented own prefix/suffix",
            readerTemporaryName.startsWith(READER_TEMP_PREFIX) && readerTemporaryName.endsWith(READER_TEMP_SUFFIX),
        )
        assertFalse(
            "writer and reader temporaries must be distinct files",
            readerTemporaryName == writerTemporary.name,
        )

        // Cerrar el lector elimina su temporal y deja intacto el del escritor.
        imported.close()
        assertTrue(imported.isClosed)
        assertEquals(emptySet<String>(), stagingNames(readerStaging))
        assertTrue("closing the import must not touch the writer temporary", writerTemporary.isFile)
        imported.close()
        assertEquals("close must be idempotent", emptySet<String>(), stagingNames(readerStaging))
        val closedImport = assertThrows(MediaBackupImportException::class.java) {
            imported.copyImageTo(image.key, ByteArrayOutputStream())
        }
        assertTrue("a closed import cannot transfer images, was $closedImport", closedImport is MediaBackupImportException.ArchiveClosed)

        // Cerrar el escritor elimina solo su temporal: ajenos y "lookalike" sobreviven.
        prepared.close()
        assertTrue(prepared.isClosed)
        assertFalse(writerTemporary.exists())
        assertTrue(foreign.isFile)
        assertTrue(foreignPrefixWrongSuffix.isFile)
        assertTrue("a directory with the temporary name must never be deleted", directoryLookalike.isDirectory)
        assertEquals("do not delete", foreign.readText())
        prepared.close()
        assertEquals(
            setOf(foreign.name, foreignPrefixWrongSuffix.name, directoryLookalike.name),
            stagingNames(writerStaging),
        )
        assertArrayEquals(sentinelBytes, sentinel.readBytes())
    }

    // --- Fixtures -------------------------------------------------------------------------------

    private class FixtureImage(
        val key: String,
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
    ) {
        val sha256: String = sha256Hex(bytes)
    }

    /** Fixture autocontenido: el PNG se genera en memoria y sus dimensiones salen de su IHDR. */
    private fun fixtureImage(key: String, bytes: ByteArray): FixtureImage {
        assertTrue("the fixture must be a PNG", bytes.size >= 24)
        assertArrayEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
        return FixtureImage(
            key = key,
            width = readU32(bytes, 16).toInt(),
            height = readU32(bytes, 20).toInt(),
            bytes = bytes,
        )
    }

    /** Manifiesto V2 valido: una raiz por imagen, con su fotograma y dos marcas medibles. */
    private fun manifestWith(images: List<FixtureImage>): MediaBackupManifest {
        val roots = ArrayList<MediaBackupRoot>(images.size)
        val frames = ArrayList<MediaBackupFrame>(images.size)
        val marks = ArrayList<MediaBackupMark>(images.size * 2)
        val declaredImages = ArrayList<MediaBackupImage>(images.size)
        images.forEachIndexed { index, image ->
            val rootId = index + 1L
            val frameId = FRAME_ID_BASE + index
            roots += MediaBackupRoot(
                id = rootId,
                analysisFormat = ANGLE_ANALYSIS_V2_FORMAT,
                sessionKey = "instrumented-session-${index + 1}",
                athleteId = if (index == 0) LEGACY_ATHLETE_ID else null,
                createdAt = FIXTURE_EPOCH_MILLIS + index,
                updatedAt = FIXTURE_EPOCH_MILLIS + 1_000L + index,
                revision = index,
            )
            frames += MediaBackupFrame(
                id = frameId,
                rootId = rootId,
                ordinal = 0,
                assignedFrameIndex = index,
                reportedFrameIndex = null,
                assignedPtsUs = index * 33_333L,
                reportedPtsUs = null,
                encodedWidth = image.width,
                encodedHeight = image.height,
                presentationWidth = image.width,
                presentationHeight = image.height,
                rotationDegrees = 0,
                geometryVersion = 1,
                imageKey = image.key,
                imageWidth = image.width,
                imageHeight = image.height,
                imageByteCount = image.bytes.size.toLong(),
                imageSha256 = image.sha256,
                imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
            )
            marks += mark(
                id = MARK_ID_BASE + index * 2L,
                frameId = frameId,
                ordinal = 0,
                name = "rodilla",
                ax = 1.0,
                ay = 1.0,
                bx = image.width / 2.0,
                by = image.height / 2.0,
                cx = (image.width - 2).toDouble(),
                cy = 1.0,
            )
            marks += mark(
                id = MARK_ID_BASE + index * 2L + 1L,
                frameId = frameId,
                ordinal = 1,
                name = "cadera",
                ax = 2.0,
                ay = (image.height - 2).toDouble(),
                bx = image.width / 2.0,
                by = image.height / 2.0,
                cx = (image.width - 2).toDouble(),
                cy = (image.height - 2).toDouble(),
            )
            declaredImages += MediaBackupImage(
                imageKey = image.key,
                byteCount = image.bytes.size.toLong(),
                sha256 = image.sha256,
            )
        }
        return MediaBackupManifest(
            contract = MEDIA_BACKUP_CONTRACT,
            formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
            sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
            createdAtEpochMillis = FIXTURE_EPOCH_MILLIS,
            legacy = legacyDocument(),
            angleV2 = MediaBackupAngleV2(
                roots = roots,
                frames = frames,
                marks = marks,
                images = declaredImages,
            ),
        )
    }

    private fun mark(
        id: Long,
        frameId: Long,
        ordinal: Int,
        name: String,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
    ) = MediaBackupMark(
        id = id,
        frameId = frameId,
        ordinal = ordinal,
        name = name,
        colorArgb = -0x10000,
        visible = true,
        pointAX = ax,
        pointAY = ay,
        pointBX = bx,
        pointBY = by,
        pointCX = cx,
        pointCY = cy,
    )

    /** Documento legacy v1 valido (Room 10): un atleta, una evaluacion CMJ y su intento. */
    private fun legacyDocument(): ManualBackupDocument = ManualBackupCodec.decode(LEGACY_JSON)

    /**
     * PNG real generado en el dispositivo con `Bitmap`; el fixture no depende de ficheros externos
     * ni del video del usuario. Las dimensiones declaradas por el manifiesto son las del PNG.
     */
    private fun png(width: Int, height: Int, seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val red = (x * 7 + seed) and 0xFF
                    val green = (y * 11 + seed) and 0xFF
                    val blue = (x * y + seed) and 0xFF
                    bitmap.setPixel(x, y, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
                }
            }
            val output = ByteArrayOutputStream()
            assertTrue("the device must encode the synthetic PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPngDimensions(bytes: ByteArray, width: Int, height: Int) {
        assertTrue("extracted image must contain a PNG signature and IHDR, was ${bytes.size} bytes", bytes.size >= 24)
        assertArrayEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(width, readU32(bytes, 16).toInt())
        assertEquals(height, readU32(bytes, 20).toInt())
    }

    // --- Gramatica ZIP observada en Android -----------------------------------------------------

    private class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val extraLength: Int,
        val commentLength: Int,
        val internalAttributes: Int,
        val externalAttributes: Long,
        val versionNeeded: Int,
    )

    /**
     * Lee el directorio central real escrito por `java.util.zip` de Android y comprueba la gramatica
     * que el lector de produccion exige. Si Android difiere del JVM (p. ej. otro juego de flags o
     * campos extra), esto falla con el valor observado en el mensaje y en logcat.
     */
    private fun verifyAndroidZipGrammar(file: File): List<CentralEntry> {
        val entries = centralEntries(file)
        Log.i(
            TAG,
            "android-zip file=${file.name} length=${file.length()} sha256=${sha256Hex(file.readBytes())} entries=" +
                entries.joinToString(separator = " | ") { entry ->
                    "name=${entry.name} flags=0x${entry.flags.toString(16)} method=${entry.method} " +
                        "extra=${entry.extraLength} comment=${entry.commentLength} " +
                        "internal=0x${entry.internalAttributes.toString(16)} external=0x${entry.externalAttributes.toString(16)} " +
                        "versionNeeded=${entry.versionNeeded}"
                },
        )
        entries.forEach { entry ->
            assertTrue(
                "entry ${entry.name} must declare data-descriptor and optionally UTF-8 flags " +
                    "(0x0008 or 0x0808), Android wrote 0x${entry.flags.toString(16)}",
                entry.flags == DATA_DESCRIPTOR_FLAG || entry.flags == DATA_DESCRIPTOR_FLAG or UTF8_FLAG,
            )
            assertEquals("entry ${entry.name} must be DEFLATED", DEFLATED_METHOD, entry.method)
            assertEquals("entry ${entry.name} must not carry extra fields", 0, entry.extraLength)
            assertEquals("entry ${entry.name} must not carry comments", 0, entry.commentLength)
            assertEquals("entry ${entry.name} must not declare internal attributes", 0, entry.internalAttributes)
            assertEquals("entry ${entry.name} must not declare host attributes", 0L, entry.externalAttributes)
            assertTrue(
                "entry ${entry.name} must not require a newer ZIP feature than 2.0",
                entry.versionNeeded <= MAX_SUPPORTED_VERSION,
            )
        }
        return entries
    }

    private fun centralEntries(file: File): List<CentralEntry> {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            assertTrue("the archive must contain an EOCD", length >= EOCD_BYTES)
            val eocd = ByteArray(EOCD_BYTES.toInt())
            raf.seek(length - EOCD_BYTES)
            raf.readFully(eocd)
            assertEquals("the archive must end with the EOCD", EOCD_SIGNATURE, readU32(eocd, 0))
            assertEquals("the archive must not carry a comment", 0, readU16(eocd, 20))
            val total = readU16(eocd, 10)
            assertEquals("the entry count must be consistent", total, readU16(eocd, 8))
            val centralSize = readU32(eocd, 12)
            val centralOffset = readU32(eocd, 16)
            assertEquals("the central directory must end at the EOCD", length - EOCD_BYTES, centralOffset + centralSize)
            val entries = ArrayList<CentralEntry>(total)
            var position = centralOffset
            repeat(total) {
                val header = ByteArray(CENTRAL_HEADER_BYTES)
                raf.seek(position)
                raf.readFully(header)
                assertEquals(CENTRAL_SIGNATURE, readU32(header, 0))
                val nameLength = readU16(header, 28)
                val extraLength = readU16(header, 30)
                val commentLength = readU16(header, 32)
                val nameBytes = ByteArray(nameLength)
                raf.seek(position + CENTRAL_HEADER_BYTES)
                raf.readFully(nameBytes)
                entries += CentralEntry(
                    name = String(nameBytes, Charsets.US_ASCII),
                    flags = readU16(header, 8),
                    method = readU16(header, 10),
                    extraLength = extraLength,
                    commentLength = commentLength,
                    internalAttributes = readU16(header, 36),
                    externalAttributes = readU32(header, 38),
                    versionNeeded = readU16(header, 6),
                )
                position += CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength
            }
            assertEquals("the central directory must be fully consumed", centralOffset + centralSize, position)
            return entries
        }
    }

    // --- Corrupcion controlada ------------------------------------------------------------------

    /** XOR 0xFF en una ventana de los bytes comprimidos, sin cambiar tamano ni directorio central. */
    private fun corruptCompressedPayload(file: File, entryName: String) {
        val bytes = file.readBytes()
        val headerAt = localHeaderOffset(bytes, entryName)
        val dataStart = headerAt + LOCAL_HEADER_BYTES + readU16(bytes, headerAt + 26) + readU16(bytes, headerAt + 28)
        val compressedSize = compressedSizeOf(file, entryName)
        assertTrue("the compressed payload of $entryName must be corruptible, was $compressedSize bytes", compressedSize >= 8)
        val windowStart = dataStart + compressedSize / 2
        val windowLength = maxOf(1, compressedSize / 8)
        assertTrue("the corruption window must stay inside the compressed data", windowStart + windowLength <= dataStart + compressedSize)
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

    private fun localHeaderOffset(bytes: ByteArray, entryName: String): Int {
        var position = 0
        while (position + LOCAL_HEADER_BYTES <= bytes.size) {
            if (readU32(bytes, position) == LOCAL_SIGNATURE) {
                val nameLength = readU16(bytes, position + 26)
                val extraLength = readU16(bytes, position + 28)
                val nameStart = position + LOCAL_HEADER_BYTES
                if (nameStart + nameLength + extraLength <= bytes.size) {
                    val name = String(bytes, nameStart, nameLength, Charsets.US_ASCII)
                    if (name == entryName) return position
                }
            }
            position++
        }
        throw AssertionError("local header of '$entryName' not found")
    }

    private fun compressedSizeOf(file: File, entryName: String): Int = ZipFile(file).use { zip ->
        val entry = zip.getEntry(entryName) ?: throw AssertionError("entry '$entryName' not found")
        val size = entry.compressedSize
        assertTrue("compressed size of '$entryName' must be known, was $size", size in 1..Int.MAX_VALUE.toLong())
        size.toInt()
    }

    // --- Helpers de fichero ---------------------------------------------------------------------

    private fun stagingNames(directory: File): Set<String> =
        directory.listFiles().orEmpty().mapTo(LinkedHashSet()) { it.name }

    private fun deleteOwnTree(directory: File) {
        directory.listFiles().orEmpty().forEach { entry ->
            val symbolic = java.nio.file.Files.isSymbolicLink(entry.toPath())
            if (entry.isDirectory && !symbolic) {
                // deleteOwnTree ya elimina el directorio al final: no se borra dos veces.
                deleteOwnTree(entry)
            } else {
                assertTrue("could not delete own artifact '${entry.name}'", entry.delete())
            }
        }
        assertTrue("could not delete own directory '${directory.name}'", directory.delete())
    }

    private fun canonicalJson(manifest: MediaBackupManifest): String =
        MediaBackupManifestCodec.encode(manifest).toString(Charsets.UTF_8)

    // --- Helpers binarios -----------------------------------------------------------------------

    private fun crc32(bytes: ByteArray): Long = java.util.zip.CRC32().apply { update(bytes) }.value

    private fun readU16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private companion object {
        const val TAG = "MediaBackupArchiveInstrumentedTest"
        const val SANDBOX_PREFIX = "openjump-media-backup-instrumented-"

        // Prefijos que el contrato publico documenta para los temporales de cada componente.
        const val WRITER_TEMP_PREFIX = "openjump-media-backup-"
        const val WRITER_TEMP_SUFFIX = ".zip.tmp"
        const val READER_TEMP_PREFIX = "openjump-media-backup-import-"
        const val READER_TEMP_SUFFIX = ".zip.partial"

        const val FIXTURE_KEY_A = "00112233445566778899aabbccddeeff"
        const val FIXTURE_KEY_B = "ffeeddccbbaa99887766554433221100"
        const val LEGACY_ATHLETE_ID = 1L
        const val FRAME_ID_BASE = 11L
        const val MARK_ID_BASE = 101L
        const val FIXTURE_EPOCH_MILLIS = 1_700_000_000_000L

        const val EOCD_SIGNATURE = 0x06054B50L
        const val EOCD_BYTES = 22L
        const val CENTRAL_SIGNATURE = 0x02014B50L
        const val CENTRAL_HEADER_BYTES = 46
        const val LOCAL_SIGNATURE = 0x04034B50L
        const val LOCAL_HEADER_BYTES = 30
        const val DATA_DESCRIPTOR_FLAG = 0x0008
        const val UTF8_FLAG = 0x0800
        const val DEFLATED_METHOD = 8
        const val MAX_SUPPORTED_VERSION = 20

        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        const val LEGACY_JSON =
            """{"contract":"openjump-manual-backup","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"instrumented athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"instrumented-assessment","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}

/** SHA-256 en minuscula, independiente de Locale y del formato del dispositivo. */
private fun sha256Hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef".toCharArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        digits[(byte.toInt() and 0xFF) ushr 4].toString() + digits[byte.toInt() and 0x0F]
    }
}
