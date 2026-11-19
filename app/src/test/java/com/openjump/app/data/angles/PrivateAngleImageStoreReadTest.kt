package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM de lectura: una imagen sólo se entrega después de validar tamaño y SHA-256, y toda
 * ausencia, corrupción o ruta insegura es un resultado tipado que no borra ni "repara" nada.
 */
class PrivateAngleImageStoreReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        root: File,
        limits: AngleImageStoreLimits = AngleImageStoreLimits(),
    ): PrivateAngleImageStore = PrivateAngleImageStore(
        rootDirectory = root,
        limits = limits,
        fileIo = NioAngleImageFileIo(),
    )

    private fun writeImage(root: File, bytes: ByteArray): StoredAngleImage {
        val result = store(root).write(ByteArrayInputStream(bytes), bytes.size.toLong())
        return assertStored(result).image
    }

    @Test
    fun `entrega los bytes verificados con su integridad`() {
        val root = folder.newFolder("read-ok")
        val bytes = payload(2_048)
        val image = writeImage(root, bytes)
        val store = store(root)

        val result = store.readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) { source -> source.readBytes() }

        val delivered = result as AngleImageReadResult.Delivered
        assertEquals(image, delivered.image)
        assertArrayEquals(bytes, delivered.value as ByteArray)
    }

    @Test
    fun `un byte alterado no se expone y se reporta por digest`() {
        val root = folder.newFolder("tampered")
        val bytes = payload(512)
        val image = writeImage(root, bytes)
        val file = publicImage(root, image.imageKey)
        val originalSize = file.length()
        RandomAccessFile(file, "rw").use { handle ->
            handle.seek(0L)
            handle.write(0x7F)
        }
        var consumerInvocations = 0

        val result = store(root).readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) {
            consumerInvocations++
            it.readBytes()
        }

        val failed = result as AngleImageReadResult.Failed
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.DIGEST_MISMATCH),
            failed.error,
        )
        assertEquals("el consumidor no debe ver bytes parciales", 0, consumerInvocations)
        assertTrue("la corrupción no se borra", file.isFile)
        assertEquals(originalSize, file.length())
    }

    @Test
    fun `un fichero truncado se reporta por tamano sin exponer bytes`() {
        val root = folder.newFolder("truncated")
        val bytes = payload(1_024)
        val image = writeImage(root, bytes)
        val file = publicImage(root, image.imageKey)
        RandomAccessFile(file, "rw").use { it.setLength(512L) }
        var consumerInvocations = 0

        val result = store(root).readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) {
            consumerInvocations++
            it.readBytes()
        }

        val failed = result as AngleImageReadResult.Failed
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.SIZE_MISMATCH),
            failed.error,
        )
        assertEquals(0, consumerInvocations)
        assertTrue("el fichero truncado no se borra", file.isFile)
    }

    @Test
    fun `la ausencia se tipa y no crea ni inventa nada`() {
        val root = folder.newFolder("absent")
        val store = store(root)
        val absentKey = fixedKey('c')
        val expected = AngleImageIntegrity(64L, "a".repeat(64))

        val verified = store.verify(absentKey, expected)
        val read = store.readVerified(absentKey, expected) { it.readBytes() }

        assertEquals(AngleImageVerifyResult.Failed(AngleImageStoreError.Absent(absentKey)), verified)
        assertEquals(AngleImageReadResult.Failed(AngleImageStoreError.Absent(absentKey)), read)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
    }

    @Test
    fun `una clave no canonica se rechaza en lectura sin tocar rutas externas`() {
        val root = folder.newFolder("read-invalid-key")
        val outside = folder.newFile("outside.txt").apply { writeText("no tocar") }
        val store = store(root)

        val traversal = store.verify("../outside.txt", AngleImageIntegrity(8L, "b".repeat(64)))
        val absolute = store.readVerified(
            outside.absolutePath,
            AngleImageIntegrity(8L, "b".repeat(64)),
        ) { it.readBytes() }

        assertEquals(
            AngleImageVerifyResult.Failed(AngleImageStoreError.InvalidKey("../outside.txt")),
            traversal,
        )
        assertEquals(
            AngleImageReadResult.Failed(AngleImageStoreError.InvalidKey(outside.absolutePath)),
            absolute,
        )
        assertEquals("no tocar", outside.readText())
    }

    @Test
    fun `un symlink a un fichero externo no se sigue aunque la integridad cuadre`() {
        val root = folder.newFolder("read-symlink")
        val key = fixedKey('d')
        val outsideBytes = payload(128, seed = 5)
        val outside = folder.newFile("outside-read.png").apply { writeBytes(outsideBytes) }
        val images = imagesDirectory(root).apply { mkdirs() }
        Files.createSymbolicLink(File(images, key).toPath(), outside.toPath())
        var consumerInvocations = 0

        val verified = store(root).verify(
            key,
            AngleImageIntegrity(outsideBytes.size.toLong(), sha256Hex(outsideBytes)),
        )
        val read = store(root).readVerified(
            key,
            AngleImageIntegrity(outsideBytes.size.toLong(), sha256Hex(outsideBytes)),
        ) {
            consumerInvocations++
            it.readBytes()
        }

        assertEquals(
            AngleImageVerifyResult.Failed(
                AngleImageStoreError.Corrupted(key, AngleImageCorruptionReason.SYMBOLIC_LINK),
            ),
            verified,
        )
        assertTrue(read is AngleImageReadResult.Failed)
        assertEquals(0, consumerInvocations)
        assertArrayEquals("el fichero externo queda intacto", outsideBytes, outside.readBytes())
    }

    @Test
    fun `una expectativa de integridad invalida se rechaza`() {
        val root = folder.newFolder("bad-integrity")
        val image = writeImage(root, payload(64))
        val store = store(root)

        val zeroSize = store.verify(image.imageKey, AngleImageIntegrity(0L, image.sha256Hex))
        val notHex = store.verify(image.imageKey, AngleImageIntegrity(image.byteCount, "no-es-sha256"))
        val tooLarge = store.verify(
            image.imageKey,
            AngleImageIntegrity(AngleImageStoreLimits.DEFAULT_MAX_IMAGE_BYTES + 1L, image.sha256Hex),
        )

        listOf(zeroSize, notHex, tooLarge).forEach { result ->
            assertTrue("$result", result is AngleImageVerifyResult.Failed)
            assertTrue(
                "esperaba InvalidIntegrityExpectation: $result",
                (result as AngleImageVerifyResult.Failed).error is AngleImageStoreError.InvalidIntegrityExpectation,
            )
        }
        assertTrue("la imagen sigue publicada", publicImage(root, image.imageKey).isFile)
    }

    @Test
    fun `el consumidor recibe un flujo acotado al tamano verificado`() {
        val root = folder.newFolder("bounded")
        val bytes = payload(256)
        val image = writeImage(root, bytes)
        val requested = ByteArray((image.byteCount + 256L).toInt())

        val read = store(root).readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) { source ->
            var total = 0
            while (true) {
                val count = source.read(requested)
                if (count < 0) break
                total += count
            }
            total
        }

        val delivered = read as AngleImageReadResult.Delivered
        assertEquals(bytes.size, delivered.value as Int)
    }

    @Test
    fun `un fallo del consumidor se reporta en la etapa de lectura`() {
        val root = folder.newFolder("consumer-failure")
        val image = writeImage(root, payload(128))

        val read = store(root).readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) {
            throw IOException("fallo del consumidor")
        }

        val failed = read as AngleImageReadResult.Failed
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(AngleImageStoreStage.READ, (error as AngleImageStoreError.IoFailure).stage)
        assertTrue("la imagen no se pierde", publicImage(root, image.imageKey).isFile)
    }

    @Test
    fun `no se entrega nada de un fichero que no es regular`() {
        val root = folder.newFolder("not-regular")
        val key = fixedKey('e')
        // Directorio de clave real, pero el nombre final es un directorio: no es una imagen.
        val finalAsDirectory = File(keyDirectory(root, key).apply { mkdirs() }, AngleImageFileIo.FINAL_FILE_NAME)
            .apply { mkdirs() }

        val verified = store(root).verify(key, AngleImageIntegrity(64L, "c".repeat(64)))

        assertEquals(
            AngleImageVerifyResult.Failed(
                AngleImageStoreError.Corrupted(key, AngleImageCorruptionReason.NOT_REGULAR_FILE),
            ),
            verified,
        )
        assertTrue("el directorio no se toca", finalAsDirectory.isDirectory)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `un fichero plano del legado en la ruta de la clave no es una imagen`() {
        val root = folder.newFolder("legacy-flat")
        val key = fixedKey('f')
        val legacy = File(imagesDirectory(root).apply { mkdirs() }, key)
            .apply { writeBytes(payload(64, seed = 13)) }
        val before = legacy.readBytes()

        val verified = store(root).verify(key, AngleImageIntegrity(64L, sha256Hex(before)))
        val read = store(root).readVerified(key, AngleImageIntegrity(64L, sha256Hex(before))) { it.readBytes() }

        assertEquals(
            AngleImageVerifyResult.Failed(
                AngleImageStoreError.Corrupted(key, AngleImageCorruptionReason.NOT_REGULAR_FILE),
            ),
            verified,
        )
        assertTrue("no se entrega nada del layout antiguo: $read", read is AngleImageReadResult.Failed)
        assertArrayEquals("el fichero del legado no se toca", before, legacy.readBytes())
    }

    @Test
    fun `un directorio de clave sin fichero final es ausencia y no crea nada`() {
        val root = folder.newFolder("empty-key-directory")
        val key = fixedKey('a')
        val directory = keyDirectory(root, key).apply { mkdirs() }
        var consumerInvocations = 0

        val verified = store(root).verify(key, AngleImageIntegrity(8L, "d".repeat(64)))
        val read = store(root).readVerified(key, AngleImageIntegrity(8L, "d".repeat(64))) {
            consumerInvocations++
            it.readBytes()
        }

        assertEquals(
            AngleImageVerifyResult.Failed(AngleImageStoreError.Absent(key)),
            verified,
        )
        assertEquals(AngleImageReadResult.Failed(AngleImageStoreError.Absent(key)), read)
        assertEquals("no se inventa contenido", 0, consumerInvocations)
        assertTrue("el directorio previo no se toca", directory.isDirectory)
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `un enlace en el fichero final no se sigue aunque la integridad cuadre`() {
        val root = folder.newFolder("final-symlink")
        val key = fixedKey('b')
        val outsideBytes = payload(96, seed = 29)
        val outside = folder.newFile("outside-final.png").apply { writeBytes(outsideBytes) }
        val directory = keyDirectory(root, key).apply { mkdirs() }
        Files.createSymbolicLink(File(directory, AngleImageFileIo.FINAL_FILE_NAME).toPath(), outside.toPath())
        var consumerInvocations = 0

        val verified = store(root).verify(
            key,
            AngleImageIntegrity(outsideBytes.size.toLong(), sha256Hex(outsideBytes)),
        )
        store(root).readVerified(
            key,
            AngleImageIntegrity(outsideBytes.size.toLong(), sha256Hex(outsideBytes)),
        ) {
            consumerInvocations++
            it.readBytes()
        }

        assertEquals(
            AngleImageVerifyResult.Failed(
                AngleImageStoreError.Corrupted(key, AngleImageCorruptionReason.SYMBOLIC_LINK),
            ),
            verified,
        )
        assertEquals("no se entrega el destino del enlace", 0, consumerInvocations)
        assertArrayEquals("el fichero externo queda intacto", outsideBytes, outside.readBytes())
    }
}
