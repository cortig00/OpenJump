package com.openjump.app.data.angles

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Sumidero append-only de un fichero temporal, con `fsync` explícito.
 *
 * Se separa de [AngleImageFileIo] para que las pruebas puedan interrumpir la escritura o fallar en
 * el `fsync` sin tocar el disco real, y para que la secuencia "escribir todo → sincronizar →
 * publicar" sea visible en el tipo.
 */
interface AngleImageSink : Closeable {

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Fuerza el contenido a disco (`fsync`) antes de publicar el nombre definitivo. */
    fun sync()

    override fun close()
}

/**
 * Resultado de sincronizar (o intentarlo) un directorio con `fsync`.
 *
 * Un `fsync` de fichero no persiste el **nombre**: para que "publicado" sobreviva a un corte hay
 * que sincronizar también los directorios. La plataforma puede no soportar `fsync` sobre un
 * descriptor de directorio; en ese caso se informa [UNSUPPORTED] en vez de prometer durabilidad.
 * [FAILED] significa que la operación está publicada y verificada en proceso, pero que su
 * persistencia frente a un corte **no** se ha podido confirmar.
 */
enum class AngleImageDirectorySync {
    /** El directorio se sincronizó con éxito. */
    SYNCED,

    /** La plataforma no permite `fsync` sobre directorios: durabilidad no declarada. */
    UNSUPPORTED,

    /** El sistema devolvió un error al sincronizar el directorio: durabilidad no confirmada. */
    FAILED,
}

/**
 * Frontera de E/S del almacén privado de imágenes.
 *
 * Existe para dos motivos acotados: (1) que el almacén sea JVM puro y testeable sin Android, y
 * (2) que las pruebas puedan inyectar `ENOSPC`, interrupciones y fallos de publicación/borrado
 * sin depender del estado del disco de la máquina.
 *
 * Hay **dos** implementaciones de producción y se inyectan **explícitamente**; el almacén no
 * detecta la plataforma ni elige backend por su cuenta:
 *
 * - [NioAngleImageFileIo] sobre `java.nio.file`, para JVM y pruebas unitarias;
 * - [AndroidAngleImageFileIo] sobre primitivos de Android (`StatFs`, `Os.mkdir`, `Os.rename`,
 *   `Os.lstat`, `Os.fsync`), para la app en dispositivo.
 *
 * Ninguna otra parte del código debería implementar esta interfaz.
 *
 * ## Modelo de publicación (E2)
 *
 * El layout es un **directorio por clave**, no un fichero plano:
 *
 * ```
 * angle-images/<clave-hex32>/        <- creado con mkdir EXCLUSIVO; es la reserva de propiedad
 * angle-images/<clave-hex32>/.tmp-…  <- temporal exclusivo, dentro del directorio propio
 * angle-images/<clave-hex32>/image.bin  <- nombre fijo final, alcanzado con un rename interno
 * ```
 *
 * La garantía frente a sobrescrituras **no** viene de `rename(2)` (en POSIX reemplaza el destino),
 * sino de que el directorio de clave se crea con un `mkdir` exclusivo y de que sólo su creador
 * escribe dentro: el namespace es nuevo, así que no hay nada que reemplazar. No existe
 * "reanudación de clave": si la ruta de la clave ya existe (fichero, directorio o enlace, incluso
 * colgante), la escritura se rechaza sin tocar nada.
 *
 * ## Contrato esperado de la implementación
 *
 * - [createKeyDirectory] es un `mkdir` **exclusivo**: falla con [FileAlreadyExistsException] ante
 *   cualquier entrada preexistente (fichero, directorio, enlace simbólico incluso colgante) y no
 *   modifica la ruta existente.
 * - [publishWithinOwnedDirectory] mueve el temporal al nombre fijo **dentro del mismo directorio**
 *   y falla con [FileAlreadyExistsException] si el destino ya existe; nunca reemplaza. El llamante
 *   mantiene el cerrojo del presupuesto y la condición de propiedad durante la llamada.
 * - [entryKind], [isRegularFile], [isSymbolicLink] e [isDirectory] usan `lstat`: **no siguen**
 *   enlaces simbólicos.
 * - [openSource] abre sin seguir el componente final del enlace.
 * - [delete] borra exactamente el [File] indicado; nunca un directorio.
 * - [removeEmptyDirectory] es un `rmdir`: retira el directorio **sólo** si está vacío y **nunca**
 *   recursivo. Devuelve `false` si no estaba vacío o ya no existía.
 * - [syncDirectory] no lanza: informa [AngleImageDirectorySync].
 */
interface AngleImageFileIo {

    fun ensureDirectory(directory: File)

    /** Espacio utilizable del volumen de [directory], o `null` si el sistema no lo declara. */
    fun usableSpaceBytes(directory: File): Long?

    /**
     * Uso agregado real del almacén: finales, temporales, huérfanos y legado plano, sin seguir
     * enlaces y con recorrido acotado (ver [measureAngleImageUsageBytes]). Lanza [IOException] si
     * la estructura es inesperada o el recorrido desborda: el presupuesto no se puede medir con
     * certeza, así que la escritura debe fallar cerrada.
     */
    fun usageBytes(directory: File): Long = measureAngleImageUsageBytes(this, directory)

    /** Nombres de las entradas de [directory] (sin abrirlas). Lanza si no se puede listar. */
    fun listEntryNames(directory: File): List<String>

    /** Tipo de [file] con `lstat`, sin seguir enlaces; [AngleImageEntryKind.ABSENT] si no existe. */
    fun entryKind(file: File): AngleImageEntryKind

    /**
     * Reserva el namespace de una clave con un `mkdir` **exclusivo** dentro de [parent].
     *
     * Falla con [FileAlreadyExistsException] si ya existe cualquier entrada con ese nombre; en ese
     * caso no se ha modificado nada.
     */
    fun createKeyDirectory(parent: File, name: String): File

    /** Crea un nombre temporal único dentro de [directory] (el directorio de clave propio). */
    fun createTemporaryFile(directory: File): File

    fun openSink(file: File): AngleImageSink

    /** Abre [file] para lectura sin seguir enlaces simbólicos. */
    fun openSource(file: File): InputStream

    /**
     * Publica [temporary] como [target] con un rename **interno** al directorio [ownedDirectory].
     *
     * Se llama con el cerrojo del presupuesto tomado y habiendo comprobado la condición de
     * propiedad ([requireOwnedDirectoryForPublication]). Falla con [FileAlreadyExistsException] si
     * [target] ya existe: no se reemplaza contenido publicado.
     */
    fun publishWithinOwnedDirectory(temporary: File, target: File, ownedDirectory: File)

    /** Fuerza a disco la entrada de directorio [directory]; nunca lanza. */
    fun syncDirectory(directory: File): AngleImageDirectorySync

    fun canonicalFile(file: File): File

    fun exists(file: File): Boolean

    fun isRegularFile(file: File): Boolean

    fun isSymbolicLink(file: File): Boolean

    fun isDirectory(file: File): Boolean

    fun length(file: File): Long

    fun delete(file: File)

    /** `rmdir` no recursivo de [directory]; `true` sólo si se retiró un directorio vacío. */
    fun removeEmptyDirectory(directory: File): Boolean

    companion object {
        /** Prefijo de los temporales propios; nunca coincide con una clave canónica. */
        const val TEMPORARY_FILE_PREFIX: String = ".tmp-"

        /** Nombre fijo del fichero publicado dentro del directorio de clave. */
        const val FINAL_FILE_NAME: String = "image.bin"

        /** Profundidad máxima del layout: `angle-images/<clave>/<fichero>`. */
        const val MAX_TRAVERSAL_DEPTH: Int = 2

        /** Entradas máximas visitadas por una medición de uso (todas las de todos los niveles). */
        const val MAX_TRAVERSAL_ENTRIES_TOTAL: Int = 8_192
    }
}

/**
 * Implementación de **JVM** sobre `java.nio.file`.
 *
 * No es el backend de la app Android: allí `Files.getFileStore(...).usableSpace` y `Files.createLink(...)`
 * no están implementados y en API 35 el `link(2)` está denegado por SELinux para `untrusted_app`;
 * en dispositivo se inyecta [AndroidAngleImageFileIo]. Esta clase se conserva para pruebas unitarias
 * y para ejecutar la lógica del almacén en una JVM de escritorio.
 *
 * **Publicación.** `Files.move(temporal, destino)` **sin** `REPLACE_EXISTING`: el JDK falla con
 * [FileAlreadyExistsException] si el destino existe, así que no se reemplaza nada. La seguridad no
 * viene del rename sino del namespace recién reservado con [createKeyDirectory]; el rename sólo
 * requiere que no haya destino, y el llamante lo comprueba bajo el cerrojo del presupuesto y con la
 * condición de propiedad verificada.
 *
 * `fsync` se aplica al contenido antes de publicar ([AngleImageSink.sync]) y a los directorios
 * después ([syncDirectory] sobre el directorio de clave y el padre). En JVM de escritorio (Linux)
 * ambos funcionan; en plataformas donde el `fsync` de directorio no esté soportado se informa
 * [AngleImageDirectorySync.UNSUPPORTED] en vez de prometer durabilidad.
 */
open class NioAngleImageFileIo : AngleImageFileIo {

    override fun ensureDirectory(directory: File) {
        val path = directory.toPath()
        Files.createDirectories(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("La ruta del almacén no es un directorio: ${directory.name}")
        }
    }

    override fun usableSpaceBytes(directory: File): Long? =
        try {
            Files.getFileStore(directory.toPath()).usableSpace
        } catch (_: IOException) {
            // El sistema no declara espacio utilizable; la comprobación previa se omite y el
            // ENOSPC real sigue traduciéndose a InsufficientStorage al escribir.
            null
        } catch (_: UnsupportedOperationException) {
            // Consulta no soportada por la plataforma: espacio **desconocido**, no inventado.
            null
        } catch (_: SecurityException) {
            // La plataforma rechaza la consulta (p. ej. el stub de Android): desconocido.
            null
        }

    override fun listEntryNames(directory: File): List<String> {
        val path = directory.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("no es un directorio del almacén: ${directory.name}")
        }
        return Files.newDirectoryStream(path).use { entries ->
            entries.map { it.fileName.toString() }
        }
    }

    override fun entryKind(file: File): AngleImageEntryKind {
        val attributes = try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return AngleImageEntryKind.ABSENT
        }
        return when {
            attributes.isSymbolicLink -> AngleImageEntryKind.SYMBOLIC_LINK
            attributes.isDirectory -> AngleImageEntryKind.DIRECTORY
            attributes.isRegularFile -> AngleImageEntryKind.REGULAR_FILE
            else -> AngleImageEntryKind.OTHER
        }
    }

    override fun createKeyDirectory(parent: File, name: String): File =
        Files.createDirectory(File(parent, name).toPath()).toFile()

    override fun createTemporaryFile(directory: File): File {
        val path = Files.createTempFile(
            directory.toPath(),
            AngleImageFileIo.TEMPORARY_FILE_PREFIX,
            ".part",
        )
        return path.toFile()
    }

    override fun openSink(file: File): AngleImageSink {
        val stream = FileOutputStream(file, /* append = */ false)
        return object : AngleImageSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                stream.write(buffer, offset, length)
            }

            override fun sync() {
                stream.fd.sync()
            }

            override fun close() {
                stream.close()
            }
        }
    }

    override fun openSource(file: File): InputStream {
        val path: Path = file.toPath()
        val channel = Files.newByteChannel(
            path,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
        return Channels.newInputStream(channel)
    }

    override fun publishWithinOwnedDirectory(temporary: File, target: File, ownedDirectory: File) {
        requireOwnedDirectoryForPublication(this, ownedDirectory, temporary, target)
        // Sin REPLACE_EXISTING: el JDK falla con FileAlreadyExistsException si el destino existe.
        Files.move(temporary.toPath(), target.toPath())
    }

    override fun syncDirectory(directory: File): AngleImageDirectorySync =
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            AngleImageDirectorySync.SYNCED
        } catch (_: UnsupportedOperationException) {
            AngleImageDirectorySync.UNSUPPORTED
        } catch (_: IOException) {
            AngleImageDirectorySync.FAILED
        }

    override fun canonicalFile(file: File): File = file.canonicalFile

    override fun exists(file: File): Boolean = entryKind(file) != AngleImageEntryKind.ABSENT

    override fun isRegularFile(file: File): Boolean = entryKind(file) == AngleImageEntryKind.REGULAR_FILE

    override fun isSymbolicLink(file: File): Boolean = entryKind(file) == AngleImageEntryKind.SYMBOLIC_LINK

    override fun isDirectory(file: File): Boolean = entryKind(file) == AngleImageEntryKind.DIRECTORY

    override fun length(file: File): Long = Files.size(file.toPath())

    override fun delete(file: File) {
        when (entryKind(file)) {
            AngleImageEntryKind.ABSENT -> return
            AngleImageEntryKind.DIRECTORY -> throw IOException("no se borra un directorio desde el almacén: ${file.name}")
            else -> Files.deleteIfExists(file.toPath())
        }
    }

    override fun removeEmptyDirectory(directory: File): Boolean {
        // Nunca se retira nada que no sea el directorio real esperado: ni un fichero ni un enlace.
        if (entryKind(directory) != AngleImageEntryKind.DIRECTORY) return false
        return try {
            Files.delete(directory.toPath())
            true
        } catch (_: DirectoryNotEmptyException) {
            false
        } catch (_: NoSuchFileException) {
            false
        }
    }
}
