package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM deterministas de la escritura del almacén privado: integridad sellada, límites,
 * fallos tipados, publicación sin sobrescritura y limpieza que nunca toca ficheros ajenos.
 *
 * Cada prueba usa su propio directorio de [TemporaryFolder]; ninguna ruta del proyecto se lee ni
 * se modifica.
 */
class PrivateAngleImageStoreWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        root: File,
        limits: AngleImageStoreLimits = AngleImageStoreLimits(),
        io: AngleImageFileIo = NioAngleImageFileIo(),
        key: (() -> String)? = null,
    ): PrivateAngleImageStore = PrivateAngleImageStore(
        rootDirectory = root,
        limits = limits,
        generateKey = key ?: AngleImageKey::generate,
        fileIo = io,
    )

    @Test
    fun `publica bytes y sella tamano y sha256 sin tocar ficheros ajenos`() {
        val root = folder.newFolder("happy")
        File(root, "ajeno.txt").writeText("dato del llamante")
        File(root, "database").mkdirs()
        val bytes = payload(4 * 1_024)

        val result = store(root).write(ByteArrayInputStream(bytes), expectedByteCount = bytes.size.toLong())

        val stored = assertStored(result)
        assertTrue("clave canónica: ${stored.image.imageKey}", AngleImageKey.isCanonical(stored.image.imageKey))
        assertEquals(bytes.size.toLong(), stored.image.byteCount)
        assertEquals(sha256Hex(bytes), stored.image.sha256Hex)
        assertFalse(stored.temporaryFileRetained)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertEquals("dato del llamante", File(root, "ajeno.txt").readText())
        assertTrue("el directorio ajeno sigue existiendo", File(root, "database").isDirectory)
        assertEquals("no quedan temporales propios", 0, temporaryFiles(root).size)
        assertEquals(
            "el final vive dentro del directorio de su clave",
            stored.image.imageKey,
            publicImage(root, stored.image.imageKey).parentFile?.name,
        )
        assertEquals("un único directorio de clave", 1, keyDirectories(root).size)
        assertTrue(
            "los directorios propios se sincronizaron: ${stored.durability}",
            stored.durability.directoryEntriesSynced,
        )
    }

    @Test
    fun `sella bytes opacos sin interpretar imagen ni formato`() {
        val bytes = "esto no es un PNG: el almacén sólo sella bytes".toByteArray()
        val store = store(folder.newFolder("opaque"))

        val stored = assertStored(store.write(ByteArrayInputStream(bytes), bytes.size.toLong()))

        assertEquals(bytes.size.toLong(), stored.image.byteCount)
        assertEquals(sha256Hex(bytes), stored.image.sha256Hex)
        val read = store.readVerified(
            stored.image.imageKey,
            AngleImageIntegrity(stored.image.byteCount, stored.image.sha256Hex),
        ) { source -> source.readBytes() }
        assertTrue("lectura verificada: $read", read is AngleImageReadResult.Delivered)
        val delivered = read as AngleImageReadResult.Delivered
        assertEquals(stored.image, delivered.image)
        assertArrayEquals(bytes, delivered.value as ByteArray)
    }

    @Test
    fun `un flujo mas corto que el tamano declarado se rechaza sin publicar`() {
        val root = folder.newFolder("short")
        val result = store(root).write(ByteArrayInputStream(payload(32)), expectedByteCount = 64L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.SizeMismatch)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `un flujo mas largo que el tamano declarado se aborta antes de publicar`() {
        val root = folder.newFolder("long")
        val result = store(root).write(ByteArrayInputStream(payload(64)), expectedByteCount = 16L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.SizeMismatch)
        assertEquals(16L, (error as AngleImageStoreError.SizeMismatch).declaredByteCount)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `el limite por fichero se aplica durante el streaming`() {
        val root = folder.newFolder("file-limit")
        val limits = AngleImageStoreLimits(maxImageBytes = 64, maxTotalBytes = 256)

        val result = store(root, limits).write(ByteArrayInputStream(payload(65)))

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.TooLarge)
        assertEquals(64L, (error as AngleImageStoreError.TooLarge).maxImageBytes)
        assertEquals(65L, error.observedByteCount)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `la cuota agregada rechaza sin tocar la imagen ya publicada`() {
        val root = folder.newFolder("quota")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val store = store(root, limits)
        val firstBytes = payload(600)

        val first = assertStored(store.write(ByteArrayInputStream(firstBytes), 600L))
        val second = store.write(ByteArrayInputStream(payload(500, seed = 71)), 500L)

        val failed = assertWriteFailed(second)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.QuotaExceeded)
        assertEquals(1_000L, (error as AngleImageStoreError.QuotaExceeded).maxTotalBytes)
        assertEquals(600L, error.usedByteCount)
        assertArrayEquals(firstBytes, publicImage(root, first.image.imageKey).readBytes())
        assertTrue(
            store.verify(first.image.imageKey, AngleImageIntegrity(600L, first.image.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
    }

    @Test
    fun `sin espacio declarado por el volumen no se crea ningun temporal`() {
        val root = folder.newFolder("no-space-preflight")
        val io = ProbedIo().apply { usableSpace = 0L }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(1_024)), 1_024L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.InsufficientStorage)
        assertEquals(0L, (error as AngleImageStoreError.InsufficientStorage).usableBytes)
        assertEquals("no se debe crear temporal si no cabe", 0, io.createdTemporaries)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
    }

    @Test
    fun `ENOSPC al escribir se tipa y limpia el temporal propio`() {
        val root = folder.newFolder("enospc")
        File(root, "ajeno.txt").writeText("intacto")
        val io = ProbedIo().apply { sinkFactory = { NoSpaceSink() } }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(1_024)), 1_024L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.InsufficientStorage)
        assertFalse(failed.temporaryFileRetained)
        assertEquals(0, temporaryFiles(root).size)
        assertEquals("intacto", File(root, "ajeno.txt").readText())
    }

    @Test
    fun `una interrupcion a mitad del flujo se reporta como cancelacion`() {
        val root = folder.newFolder("interrupted")
        val interrupting = object : InputStream() {
            private var served = false

            override fun read(): Int = throw InterruptedIOException("interrumpido")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!served) {
                    served = true
                    val count = minOf(length, 16)
                    for (index in 0 until count) buffer[offset + index] = 1
                    return count
                }
                throw InterruptedIOException("interrumpido a mitad del flujo")
            }
        }

        val result = store(root).write(interrupting, 1_024L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.Canceled)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `la cancelacion del llamante no publica nada`() {
        val root = folder.newFolder("canceled")
        var checks = 0

        val result = store(root).write(
            ByteArrayInputStream(payload(256)),
            expectedByteCount = 256L,
            shouldCancel = { checks++ >= 1 },
        )

        val failed = assertWriteFailed(result)
        assertEquals(AngleImageStoreError.Canceled, failed.error)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `una clave ya existente no se sobrescribe`() {
        val root = folder.newFolder("collision")
        val key = fixedKey('d')
        val firstBytes = payload(128)
        val store = store(root, key = { key })

        val first = assertStored(store.write(ByteArrayInputStream(firstBytes), 128L))
        val second = store.write(ByteArrayInputStream(payload(128, seed = 99)), 128L)

        val failed = assertWriteFailed(second)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.KeyAlreadyExists)
        assertEquals(key, (failed.error as AngleImageStoreError.KeyAlreadyExists).imageKey)
        assertArrayEquals(firstBytes, publicImage(root, first.image.imageKey).readBytes())
        assertEquals(0, temporaryFiles(root).size)
        assertEquals(1, imagesDirectory(root).listFiles().orEmpty().size)
    }

    @Test
    fun `un fichero preexistente del llamante se preserva byte a byte`() {
        val root = folder.newFolder("preexisting")
        val key = fixedKey('e')
        val images = imagesDirectory(root).apply { mkdirs() }
        val previous = File(images, key).apply { writeBytes("imagen previa del llamante".toByteArray()) }
        val previousModified = previous.lastModified()

        val result = store(root, key = { key }).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.KeyAlreadyExists)
        assertArrayEquals("imagen previa del llamante".toByteArray(), previous.readBytes())
        assertEquals("no se reescribe ni se toca el fichero previo", previousModified, previous.lastModified())
    }

    @Test
    fun `la publicacion concurrente de la misma clave tiene un unico ganador`() {
        val root = folder.newFolder("concurrent-same")
        val key = fixedKey('f')
        val store = store(root, key = { key })
        val threads = 8
        val payloads = (0 until threads).map { payload(512, seed = 100 + it) }
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures = payloads.map { bytes ->
                executor.submit<AngleImageWriteResult> {
                    start.await()
                    store.write(ByteArrayInputStream(bytes), bytes.size.toLong())
                }
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            val stored = results.filterIsInstance<AngleImageWriteResult.Stored>()
            val errors = results.filterIsInstance<AngleImageWriteResult.Failed>().map { it.error }
            assertEquals("un único ganador: $results", 1, stored.size)
            assertEquals(threads - 1, errors.size)
            assertTrue("sólo colisiones: $errors", errors.all { it is AngleImageStoreError.KeyAlreadyExists })
            val onDisk = publicImage(root, key).readBytes()
            assertEquals(stored.single().image.sha256Hex, sha256Hex(onDisk))
            assertTrue("el contenido publicado es uno de los enviados", payloads.any { it.contentEquals(onDisk) })
            assertEquals(0, temporaryFiles(root).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `la publicacion concurrente con claves distintas publica todas`() {
        val root = folder.newFolder("concurrent-distinct")
        val counter = AtomicInteger(1)
        val store = store(root, key = { numberedKey(counter.getAndIncrement()) })
        val threads = 6
        val payloads = (0 until threads).map { payload(384, seed = 200 + it) }
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures = payloads.map { bytes ->
                executor.submit<AngleImageWriteResult> {
                    start.await()
                    store.write(ByteArrayInputStream(bytes), bytes.size.toLong())
                }
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            val stored = results.map { assertStored(it) }
            assertEquals(threads, stored.map { it.image.imageKey }.toSet().size)
            stored.forEach { entry ->
                val verified = store.verify(
                    entry.image.imageKey,
                    AngleImageIntegrity(entry.image.byteCount, entry.image.sha256Hex),
                )
                assertTrue("verificación de ${entry.image.imageKey}: $verified", verified is AngleImageVerifyResult.Verified)
                assertEquals(
                    entry.image.sha256Hex,
                    (verified as AngleImageVerifyResult.Verified).image.sha256Hex,
                )
            }
            assertEquals(0, temporaryFiles(root).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `un fallo al publicar limpia el temporal y retira el directorio propio`() {
        val root = folder.newFolder("publish-failure")
        File(root, "ajeno.txt").writeText("dato del llamante")
        val io = ProbedIo().apply { failPublish = { throw IOException("rename inyectado") } }
        val store = store(root, io = io)

        val result = store.write(ByteArrayInputStream(payload(100)), 100L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(AngleImageStoreStage.PUBLISH, (error as AngleImageStoreError.IoFailure).stage)
        assertFalse(failed.temporaryFileRetained)
        assertFalse("el directorio propio vacío se retira", failed.directoryRetained)
        assertEquals("no se publica nada", 0, imagesDirectory(root).listFiles().orEmpty().size)
        assertEquals("dato del llamante", File(root, "ajeno.txt").readText())
    }

    @Test
    fun `si el temporal no se puede borrar se conserva el directorio propio y se informa`() {
        val root = folder.newFolder("cleanup-failure")
        File(root, "ajeno.txt").writeText("dato ajeno")
        val io = ProbedIo().apply {
            sinkFactory = { NoSpaceSink() }
            failDelete = { it.name.startsWith(AngleImageFileIo.TEMPORARY_FILE_PREFIX) }
        }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(1_024)), 1_024L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.InsufficientStorage)
        assertTrue("el temporal no se pudo borrar", failed.temporaryFileRetained)
        assertTrue("el directorio propio se preserva", failed.directoryRetained)
        assertEquals("el temporal huérfano se conserva", 1, temporaryFiles(root).size)
        val retained = keyDirectories(root).single()
        assertTrue(
            "nada se publica",
            retained.listFiles().orEmpty().none { it.name == AngleImageFileIo.FINAL_FILE_NAME },
        )
        assertEquals("dato ajeno", File(root, "ajeno.txt").readText())
    }

    @Test
    fun `una carrera en la reserva la decide el mkdir exclusivo sin tocar el fichero previo`() {
        val root = folder.newFolder("reserve-race")
        val key = fixedKey('a')
        val images = imagesDirectory(root).apply { mkdirs() }
        val preexisting = File(images, key).apply { writeBytes("no tocar".toByteArray()) }
        val foreign = File(root, "ajeno.txt").apply { writeText("ajeno") }
        // La clasificación previa "no ve" la ruta; el mkdir exclusivo sí la ve y decide.
        val io = ProbedIo().apply { hideExisting = { it.name == key } }

        val result = store(root, io = io, key = { key }).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        assertTrue("error: ${failed.error}", failed.error is AngleImageStoreError.KeyAlreadyExists)
        assertFalse(failed.temporaryFileRetained)
        assertArrayEquals("no tocar".toByteArray(), preexisting.readBytes())
        assertEquals("ajeno", foreign.readText())
        assertEquals("no se crea ningún temporal", 0, temporaryFiles(root).size)
        assertEquals(listOf(key), images.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `una clave generada no canonica se rechaza antes de construir la ruta`() {
        val root = folder.newFolder("invalid-key")
        val escape = File(root.parentFile, "escape.png")

        val result = store(root, key = { "../../escape.png" }).write(ByteArrayInputStream(payload(32)), 32L)

        val failed = assertWriteFailed(result)
        assertEquals(AngleImageStoreError.InvalidKey("../../escape.png"), failed.error)
        assertFalse("nada se escribe fuera del almacén", escape.exists())
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
    }

    @Test
    fun `un symlink en la ruta de la clave no se sigue ni se sobrescribe`() {
        val root = folder.newFolder("symlink")
        val key = fixedKey('b')
        val outsideBytes = payload(64, seed = 33)
        val outside = folder.newFile("outside.png").apply { writeBytes(outsideBytes) }
        val images = imagesDirectory(root).apply { mkdirs() }
        Files.createSymbolicLink(File(images, key).toPath(), outside.toPath())

        val result = store(root, key = { key }).write(ByteArrayInputStream(payload(32)), 32L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.UnsafePath)
        assertEquals(
            AngleImagePathViolation.SYMBOLIC_LINK,
            (error as AngleImageStoreError.UnsafePath).reason,
        )
        assertArrayEquals("el fichero externo no se toca", outsideBytes, outside.readBytes())
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `un directorio de clave previo vacio se rechaza sin reutilizarlo`() {
        val root = folder.newFolder("existing-empty-key-dir")
        val key = fixedKey('1')
        val previous = keyDirectory(root, key).apply { mkdirs() }
        val modified = previous.lastModified()

        val result = store(root, key = { key }).write(ByteArrayInputStream(payload(32)), 32L)

        val failed = assertWriteFailed(result)
        assertEquals(AngleImageStoreError.KeyAlreadyExists(key), failed.error)
        assertTrue("el directorio previo se conserva", previous.isDirectory)
        assertEquals("no se reutiliza: sigue vacío", 0, previous.listFiles().orEmpty().size)
        assertEquals("no se reescribe ni se toca", modified, previous.lastModified())
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `un directorio de clave previo con contenido no se reutiliza`() {
        val root = folder.newFolder("existing-nonempty-key-dir")
        val key = fixedKey('2')
        val previous = keyDirectory(root, key).apply { mkdirs() }
        val orphan = File(previous, AngleImageFileIo.FINAL_FILE_NAME).apply { writeBytes(payload(64, seed = 7)) }
        val orphanBytes = orphan.readBytes()

        val result = store(root, key = { key }).write(ByteArrayInputStream(payload(32)), 32L)

        assertEquals(AngleImageStoreError.KeyAlreadyExists(key), assertWriteFailed(result).error)
        assertArrayEquals("el huérfano no se reemplaza", orphanBytes, orphan.readBytes())
        assertEquals(0, temporaryFiles(root).size)
    }

    @Test
    fun `un mkdir exclusivo que falla no publica ni deja directorio`() {
        val root = folder.newFolder("mkdir-failure")
        val io = ProbedIo().apply { failKeyDirectory = { throw IOException("mkdir inyectado") } }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(
            AngleImageStoreStage.RESERVE_KEY_DIRECTORY,
            (error as AngleImageStoreError.IoFailure).stage,
        )
        assertEquals("no hay temporal que retener", 0, io.createdTemporaries)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
        assertFalse(failed.directoryRetained)
    }

    @Test
    fun `un fallo de fsync del contenido no publica y limpia el directorio`() {
        val root = folder.newFolder("content-sync-failure")
        val io = ProbedIo().apply { failSinkSync = true }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(AngleImageStoreStage.SYNC, (error as AngleImageStoreError.IoFailure).stage)
        assertFalse(failed.temporaryFileRetained)
        assertEquals("ni final ni temporal", 0, imagesDirectory(root).listFiles().orEmpty().size)
    }

    @Test
    fun `un fallo al sincronizar directorios no oculta la publicacion ni promete durabilidad`() {
        val root = folder.newFolder("dirsync-failure")
        val io = ProbedIo().apply {
            failDirectorySync = { AngleImageDirectorySync.FAILED }
        }
        val bytes = payload(256)
        val store = store(root, io = io)

        val stored = assertStored(store.write(ByteArrayInputStream(bytes), 256L))

        assertFalse("no se promete durabilidad", stored.durability.directoryEntriesSynced)
        assertEquals(AngleImageDirectorySync.FAILED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.FAILED, stored.durability.parentDirectory)
        assertArrayEquals("la imagen está publicada", bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertTrue(
            store.verify(stored.image.imageKey, AngleImageIntegrity(256L, stored.image.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
    }

    @Test
    fun `un fallo tras el rename preserva el huerfano y se reporta como publicacion incierta`() {
        val root = folder.newFolder("post-rename-failure")
        val io = ProbedIo().apply {
            afterPublish = { target -> target.writeBytes(byteArrayOf(1, 2, 3)) }
        }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.PublicationUncertain)
        assertEquals(1, keyDirectories(root).size)
        val preserved = keyDirectories(root).single().listFiles().orEmpty()
            .single { it.name == AngleImageFileIo.FINAL_FILE_NAME }
        assertArrayEquals("el huérfano se preserva tal cual", byteArrayOf(1, 2, 3), preserved.readBytes())
        assertFalse("no hay temporal que retener", failed.temporaryFileRetained)
        assertFalse("no se retira el directorio publicado", failed.directoryRetained)
    }

    @Test
    fun `el uso medido cuenta finales, temporales, huerfanos y legado plano`() {
        val root = folder.newFolder("usage-layout")
        val images = imagesDirectory(root).apply { mkdirs() }
        val legacy = File(images, fixedKey('3')).apply { writeBytes(ByteArray(7)) }
        val final = File(keyDirectory(root, fixedKey('4')).apply { mkdirs() }, AngleImageFileIo.FINAL_FILE_NAME)
            .apply { writeBytes(ByteArray(100)) }
        val ownKey = keyDirectory(root, fixedKey('5')).apply { mkdirs() }
        val abandoned = File(ownKey, "${AngleImageFileIo.TEMPORARY_FILE_PREFIX}abandonado.part")
            .apply { writeBytes(ByteArray(10)) }
        val orphan = File(ownKey, "huerfano.bin").apply { writeBytes(ByteArray(5)) }

        val usage = NioAngleImageFileIo().usageBytes(images)

        assertEquals(7L + 100L + 10L + 5L, usage)
        assertTrue(legacy.isFile)
        assertTrue(final.isFile)
        assertTrue(abandoned.isFile && orphan.isFile)
    }

    @Test
    fun `una estructura inesperada en el almacen falla cerrado al medir`() {
        val root = folder.newFolder("unexpected-structure")
        val key = fixedKey('6')
        val outside = folder.newFile("outside-usage.png").apply { writeBytes(ByteArray(32)) }
        val directory = keyDirectory(root, key).apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "enlace-inesperado").toPath(), outside.toPath())

        val result = store(root).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(
            AngleImageStoreStage.CHECK_CAPACITY,
            (error as AngleImageStoreError.IoFailure).stage,
        )
        assertEquals("no se añade nada al almacén", 1, imagesDirectory(root).listFiles().orEmpty().size)
        assertEquals("el enlace externo no se toca", 32, outside.readBytes().size)
    }

    @Test
    fun `un subdirectorio anidado en una clave falla cerrado al medir`() {
        val root = folder.newFolder("nested-directory")
        val key = fixedKey('7')
        val nested = File(keyDirectory(root, key).apply { mkdirs() }, "anidado").apply { mkdirs() }

        val result = store(root).write(ByteArrayInputStream(payload(64)), 64L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(
            AngleImageStoreStage.CHECK_CAPACITY,
            (error as AngleImageStoreError.IoFailure).stage,
        )
        assertTrue("el directorio anidado no se toca", nested.isDirectory)
    }

    @Test
    fun `el recorrido de uso esta acotado y no bloquea con demasiadas entradas`() {
        val root = folder.newFolder("usage-bound")
        val images = imagesDirectory(root).apply { mkdirs() }
        val total = AngleImageFileIo.MAX_TRAVERSAL_ENTRIES_TOTAL + 1
        for (index in 0 until total) {
            File(images, "legado-plano-$index").createNewFile()
        }

        val result = store(root).write(ByteArrayInputStream(payload(16)), 16L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("error: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(
            AngleImageStoreStage.CHECK_CAPACITY,
            (error as AngleImageStoreError.IoFailure).stage,
        )
        assertTrue("el detalle debe citar el límite: ${error.detail}", error.detail.contains("entradas"))
        assertEquals("no se publica nada", total, images.listFiles().orEmpty().size)
    }

    @Test
    fun `dos escrituras secuenciales con claves aleatorias reciben claves distintas`() {
        val root = folder.newFolder("random-keys")
        val store = store(root)

        val first = assertStored(store.write(ByteArrayInputStream(payload(16)), 16L))
        val second = assertStored(store.write(ByteArrayInputStream(payload(16, seed = 55)), 16L))

        assertNotEquals(first.image.imageKey, second.image.imageKey)
        assertTrue(publicImage(root, first.image.imageKey).isFile)
        assertTrue(publicImage(root, second.image.imageKey).isFile)
    }
}
