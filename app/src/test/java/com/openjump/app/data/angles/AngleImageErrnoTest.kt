package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM de la traducción de `errno` de la capa Android y de su efecto en el almacén.
 *
 * La traducción es una función pura, así que se prueba sin dispositivo. Lo que **sólo** el
 * dispositivo puede demostrar —que `mkdir(2)` devuelve `EEXIST` en el volumen real y que el
 * `rename(2)` interno no deja enlaces duros— lo cubre la prueba instrumentada con
 * `android.system.Os`.
 */
class AngleImageErrnoTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `EEXIST se traduce a FileAlreadyExistsException`() {
        val error = errnoToIOException(AngleImageErrno.EEXIST, "enlazar temporal con clave", "/privado/clave")
        assertTrue("se esperaba FileAlreadyExistsException: $error", error is FileAlreadyExistsException)
        assertEquals("/privado/clave", (error as FileAlreadyExistsException).file)
    }

    @Test
    fun `ENOSPC se traduce a fallo de espacio tipado con ENOSPC legible`() {
        val error = errnoToIOException(AngleImageErrno.ENOSPC, "enlazar temporal con clave", "/privado/clave")
        assertTrue("se esperaba AngleImageNoSpaceException: $error", error is AngleImageNoSpaceException)
        assertTrue("el mensaje debe conservar ENOSPC: ${error.message}", error.message.orEmpty().contains("ENOSPC"))
    }

    @Test
    fun `los demas errnos son IOException de sistema tipadas y no colision ni espacio`() {
        val otherErrnos = listOf(
            AngleImageErrno.EACCES,
            AngleImageErrno.EPERM,
            AngleImageErrno.EXDEV,
            AngleImageErrno.EMLINK,
            AngleImageErrno.EROFS,
            AngleImageErrno.ELOOP,
            AngleImageErrno.ENAMETOOLONG,
        )
        for (errno in otherErrnos) {
            val error = errnoToIOException(errno, "enlazar temporal con clave", "/privado/clave")
            assertTrue("errno $errno: se esperaba FileSystemException, fue $error", error is FileSystemException)
            assertTrue(
                "errno $errno: el motivo debe nombrar el errno: ${(error as FileSystemException).reason}",
                error.message.orEmpty().contains(errnoName(errno)),
            )
            assertFalse("errno $errno no es una colisión", error is FileAlreadyExistsException)
        }
    }

    @Test
    fun `un errno desconocido se reporta numerico y nunca como exito`() {
        val error = errnoToIOException(999, "stat", "/privado/clave")
        assertEquals("errno=999", errnoName(999))
        assertTrue(
            "el detalle debe conservar el número: ${(error as FileSystemException).reason}",
            error.reason.orEmpty().contains("errno=999"),
        )
    }

    @Test
    fun `un ENOSPC tipado al publicar es InsufficientStorage y no publica ni deja temporales`() {
        val root = folder.newFolder("publish-enospc")
        val io = ProbedIo().apply {
            failPublish = { throw AngleImageNoSpaceException("enlazar", "clave-destino") }
        }
        val bytes = payload(128)
        val result = store(root, io).write(ByteArrayInputStream(bytes), bytes.size.toLong())

        val failed = assertWriteFailed(result)
        assertEquals(AngleImageStoreError.InsufficientStorage(requiredBytes = null, usableBytes = null), failed.error)
        assertEquals("no se publica nada", emptyList<String>(), publishedNames(root))
        assertEquals("el temporal propio se elimina", emptyList<String>(), temporaryNames(root))
    }

    @Test
    fun `un EEXIST tipado al publicar es KeyAlreadyExists y no reemplaza el fichero existente`() {
        val root = folder.newFolder("publish-eexist")
        val images = imagesDirectory(root).apply { assertTrue(mkdirs()) }
        val key = fixedKey('b')
        val target = File(images, key)
        val existingBytes = payload(64, seed = 5)
        target.writeBytes(existingBytes)
        val io = ProbedIo().apply {
            // Se oculta el destino a la comprobación previa para forzar la carrera hasta publicar.
            hideExisting = { it == target }
            failPublish = { throw FileAlreadyExistsException(target.path) }
        }
        val bytes = payload(96, seed = 9)

        val result = store(root, io, key).write(ByteArrayInputStream(bytes), bytes.size.toLong())

        val failed = assertWriteFailed(result)
        assertEquals(AngleImageStoreError.KeyAlreadyExists(key), failed.error)
        assertArrayEquals("el fichero publicado no se reemplaza", existingBytes, target.readBytes())
        assertEquals(listOf(key), publishedNames(root))
        assertEquals("el temporal propio se elimina", emptyList<String>(), temporaryNames(root))
    }

    private fun store(root: File, io: AngleImageFileIo, key: String? = null): PrivateAngleImageStore =
        PrivateAngleImageStore(
            rootDirectory = root,
            generateKey = { key ?: AngleImageKey.generate() },
            fileIo = io,
        )

    private fun publishedNames(root: File): List<String> =
        imagesDirectory(root).listFiles().orEmpty()
            .filterNot { it.name.startsWith(AngleImageFileIo.TEMPORARY_FILE_PREFIX) }
            .map { it.name }
            .sorted()

    private fun temporaryNames(root: File): List<String> =
        temporaryFiles(root).map { it.name }.sorted()
}
