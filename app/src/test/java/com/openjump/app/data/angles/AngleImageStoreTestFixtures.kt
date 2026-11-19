package com.openjump.app.data.angles

import java.io.File
import java.nio.file.FileSystemException
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder

/**
 * Utilidades compartidas de las pruebas JVM del almacén privado de imágenes.
 *
 * Todas las carpetas salen de [TemporaryFolder], de modo que cada prueba usa un directorio único y
 * desechable: ninguna prueba lee, escribe ni borra rutas del proyecto ni del usuario.
 *
 * El layout que estas utilidades describen es el de E2: un **directorio por clave**
 * (`angle-images/<clave>/image.bin`), con los temporales dentro del directorio de la clave.
 */

internal fun imagesDirectory(root: File): File =
    File(root, PrivateAngleImageStore.IMAGE_DIRECTORY_NAME)

/** Directorio propio de la clave (creado con `mkdir` exclusivo). */
internal fun keyDirectory(root: File, imageKey: String): File =
    File(imagesDirectory(root), imageKey)

/** Fichero final publicado de la clave. */
internal fun publicImage(root: File, imageKey: String): File =
    File(keyDirectory(root, imageKey), AngleImageFileIo.FINAL_FILE_NAME)

/** Claves con directorio propio en el almacén (no incluye ficheros planos del legado). */
internal fun keyDirectories(root: File): List<File> =
    imagesDirectory(root).listFiles().orEmpty().filter { it.isDirectory }

/**
 * Temporales propios, estén donde estén (dentro de un directorio de clave o, por legado, en la
 * raíz del almacén).
 */
internal fun temporaryFiles(root: File): List<File> {
    val images = imagesDirectory(root)
    val entries = images.listFiles().orEmpty().toList()
    return entries.flatMap { entry ->
        if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry)
    }.filter { it.name.startsWith(AngleImageFileIo.TEMPORARY_FILE_PREFIX) }
}

internal fun temporaryNames(root: File): List<String> = temporaryFiles(root).map { it.name }.sorted()

/** Bytes realmente publicados: finales dentro de los directorios de clave. */
internal fun publishedBytesOf(root: File): Long =
    keyDirectories(root).sumOf { directory ->
        directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    }

/** Bytes deterministas; no pretenden ser un PNG, sólo contenido repetible. */
internal fun payload(size: Int, seed: Int = 17): ByteArray =
    ByteArray(size) { index -> ((index * 31 + seed) and 0xFF).toByte() }

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHexString()

/** Clave canónica fija para pruebas que necesitan controlar la publicación. */
internal fun fixedKey(char: Char = 'a'): String = char.toString().repeat(AngleImageKey.HEX_LENGTH)

/** Clave canónica sintética a partir de un entero; sirve para generadores concurrentes. */
internal fun numberedKey(index: Int): String = Integer.toHexString(index).padStart(AngleImageKey.HEX_LENGTH, '0')

internal fun assertStored(result: AngleImageWriteResult): AngleImageWriteResult.Stored {
    assertTrue("se esperaba Stored, fue $result", result is AngleImageWriteResult.Stored)
    return result as AngleImageWriteResult.Stored
}

internal fun assertWriteFailed(result: AngleImageWriteResult): AngleImageWriteResult.Failed {
    assertTrue("se esperaba Failed, fue $result", result is AngleImageWriteResult.Failed)
    return result as AngleImageWriteResult.Failed
}

internal fun assertVerifyFailed(result: AngleImageVerifyResult): AngleImageVerifyResult.Failed {
    assertTrue("se esperaba Failed, fue $result", result is AngleImageVerifyResult.Failed)
    return result as AngleImageVerifyResult.Failed
}

/**
 * E/S real con ganchos de inyección: espacio declarado, uso medido, sumidero que falla, `mkdir` que
 * falla, publicación que falla, directorio que no se puede sincronizar, borrado denegado y
 * "existencia oculta una vez" para simular la carrera entre la clasificación previa y el `mkdir`
 * exclusivo. Fuera de los ganchos delega en [NioAngleImageFileIo], así que el disco real sigue
 * siendo el del test.
 */
internal class ProbedIo : NioAngleImageFileIo() {

    var usableSpace: Long? = null
    var measuredUsage: Long? = null
    var sinkFactory: ((File) -> AngleImageSink)? = null
    var failSinkSync: Boolean = false
    var failDelete: (File) -> Boolean = { false }
    var hideExisting: (File) -> Boolean = { false }
    var failKeyDirectory: (() -> Unit)? = null
    var failPublish: (() -> Unit)? = null
    var afterPublish: ((File) -> Unit)? = null
    var failDirectorySync: ((File) -> AngleImageDirectorySync?)? = null
    var createdTemporaries: Int = 0

    /** Rutas ya ocultadas una vez: el segundo `lstat` ve la realidad (mkdir es la autoridad). */
    private val hiddenOnce = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun usableSpaceBytes(directory: File): Long? =
        usableSpace ?: super.usableSpaceBytes(directory)

    override fun usageBytes(directory: File): Long =
        measuredUsage ?: measureAngleImageUsageBytes(this, directory)

    override fun createKeyDirectory(parent: File, name: String): File {
        // El subdirectorio del propio almacén (`angle-images`) no participa del gancho: éste simula
        // el fallo del `mkdir` exclusivo **de la clave**, no el alta del almacén.
        if (name != PrivateAngleImageStore.IMAGE_DIRECTORY_NAME) failKeyDirectory?.invoke()
        return super.createKeyDirectory(parent, name)
    }

    override fun createTemporaryFile(directory: File): File {
        createdTemporaries++
        return super.createTemporaryFile(directory)
    }

    override fun openSink(file: File): AngleImageSink {
        val sink = sinkFactory?.invoke(file) ?: super.openSink(file)
        if (!failSinkSync) return sink
        return object : AngleImageSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                sink.write(buffer, offset, length)
            }

            override fun sync() {
                sink.close()
                throw IOException("fsync inyectado por la prueba")
            }

            override fun close() {
                sink.close()
            }
        }
    }

    override fun entryKind(file: File): AngleImageEntryKind =
        if (hideExisting(file) && hiddenOnce.add(file.path)) {
            AngleImageEntryKind.ABSENT
        } else {
            super.entryKind(file)
        }

    override fun publishWithinOwnedDirectory(temporary: File, target: File, ownedDirectory: File) {
        failPublish?.invoke()
        super.publishWithinOwnedDirectory(temporary, target, ownedDirectory)
        afterPublish?.invoke(target)
    }

    override fun syncDirectory(directory: File): AngleImageDirectorySync =
        failDirectorySync?.invoke(directory) ?: super.syncDirectory(directory)

    override fun delete(file: File) {
        if (failDelete(file)) throw IOException("borrado denegado por la prueba")
        super.delete(file)
    }
}

/** Sumidero que simula `ENOSPC` en la primera escritura, después de crear el temporal. */
internal class NoSpaceSink : AngleImageSink {

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        throw FileSystemException("temporal-propio", null, "ENOSPC: No space left on device")
    }

    override fun sync() = Unit

    override fun close() = Unit
}
