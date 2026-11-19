package com.openjump.app.data.angles

import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Implementación de producción del almacén para **Android**, sobre primitivos de plataforma.
 *
 * **Por qué no usa `java.nio.file`.** En Android API 35 (y en las versiones soportadas por este
 * `minSdk`) `java.nio.file` no implementa `Files.getFileStore(...).usableSpace` y
 * `Files.createLink(...)`; además, el `link(2)` está **denegado** por SELinux para el dominio
 * `untrusted_app` (AVC `denied { link }`, `EACCES`) incluso dentro del propio sandbox. Esta clase
 * usa, en su lugar, la API pública de Android:
 *
 * - espacio utilizable: [StatFs.availableBytes] (API 18); si la plataforma no puede responder, se
 *   devuelve `null` (**espacio desconocido**, nunca un valor inventado) y el `ENOSPC` real sigue
 *   fallando de forma cerrada al escribir o publicar;
 * - reserva de propiedad: `mkdir(2)` **exclusivo** vía [Os.mkdir]; `EEXIST` se traduce a
 *   [java.nio.file.FileAlreadyExistsException] y el resto de errnos a `IOException` tipada;
 * - publicación: `rename(2)` **interno** vía [Os.rename], sólo dentro del directorio de clave
 *   recién creado por esta operación. `rename` **puede reemplazar** el destino: la garantía frente
 *   a sobrescrituras no viene de él, sino de que el namespace es nuevo y de que nadie más escribe
 *   en él (escritores cooperantes del proceso). Antes del `rename` se comprueba, con el cerrojo
 *   del presupuesto tomado, que el destino no existe;
 * - metadatos sin seguir enlaces: [Os.lstat] (`S_ISREG`/`S_ISDIR`/`S_ISLNK`), [Os.unlink];
 * - retirada del directorio propio: `rmdir(2)` a través de [File.delete] sobre un directorio
 *   (`Os.rmdir` no forma parte de la API pública), nunca recursivo y sólo si está vacío;
 * - durabilidad de nombres: `fsync(2)` del descriptor del directorio vía [Os.fsync].
 *
 * **Selección explícita.** Esta clase no se elige por detección de plataforma: [PrivateAngleImageStore]
 * recibe siempre un [AngleImageFileIo] inyectado. En la app Android se inyecta esta implementación;
 * en JVM y pruebas unitarias, [NioAngleImageFileIo].
 *
 * **Qué se ha demostrado en dispositivo (API 35) y qué no.** `mkdir`/`rename`/`lstat`/`unlink`/`fsync`
 * de fichero funcionan en el sandbox; la validación instrumentada de [PrivateAngleImageStore] es la
 * que demuestra el comportamiento real de estos primitivos. El `fsync` de **directorio** en
 * `filesDir` no se ha validado todavía en dispositivo: [syncDirectory] lo intenta y, si la
 * plataforma lo rechaza, lo reporta como [AngleImageDirectorySync.UNSUPPORTED] o
 * [AngleImageDirectorySync.FAILED] en vez de prometer durabilidad.
 */
class AndroidAngleImageFileIo : AngleImageFileIo {

    override fun ensureDirectory(directory: File) {
        val existing = lstatOrNull(directory)
        when {
            existing == null -> {
                if (!directory.mkdirs() && lstatOrNull(directory) == null) {
                    throw IOException("no se pudo crear el directorio del almacén: ${directory.name}")
                }
                val created = lstatOrNull(directory)
                    ?: throw IOException("el directorio del almacén no existe tras crearlo")
                if (!OsConstants.S_ISDIR(created.st_mode)) {
                    throw IOException("la ruta del almacén no es un directorio: ${directory.name}")
                }
            }
            OsConstants.S_ISDIR(existing.st_mode) -> Unit
            // Un enlace simbólico (u otra cosa) no se sigue ni se acepta como almacén.
            else -> throw IOException("la ruta del almacén no es un directorio: ${directory.name}")
        }
    }

    /**
     * Espacio utilizable del volumen de [directory] según [StatFs], o `null` si la plataforma no
     * puede declararlo (consulta rechazada o no soportada). No se inventa un valor: `null`
     * desactiva la comprobación previa, y el `ENOSPC` real sigue fallando al escribir.
     *
     * Un valor negativo (volumen sobre-comprometido) se devuelve tal cual: el almacén lo compara
     * con lo pedido y rechaza la escritura, que es el comportamiento fail-closed correcto.
     */
    override fun usableSpaceBytes(directory: File): Long? =
        try {
            StatFs(directory.path).availableBytes
        } catch (_: IllegalArgumentException) {
            // La plataforma no puede consultar este volumen: espacio desconocido.
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }

    override fun listEntryNames(directory: File): List<String> =
        directory.list()?.toList()
            ?: throw IOException("no se pudo listar el directorio del almacén: ${directory.name}")

    override fun entryKind(file: File): AngleImageEntryKind {
        val stat = lstatOrNull(file) ?: return AngleImageEntryKind.ABSENT
        return when {
            OsConstants.S_ISLNK(stat.st_mode) -> AngleImageEntryKind.SYMBOLIC_LINK
            OsConstants.S_ISDIR(stat.st_mode) -> AngleImageEntryKind.DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) -> AngleImageEntryKind.REGULAR_FILE
            else -> AngleImageEntryKind.OTHER
        }
    }

    override fun createKeyDirectory(parent: File, name: String): File {
        val path = File(parent, name)
        try {
            Os.mkdir(path.path, OsConstants.S_IRWXU)
        } catch (error: ErrnoException) {
            throw errnoToIOException(error.errno, "mkdir exclusivo", path.path)
        }
        return path
    }

    override fun createTemporaryFile(directory: File): File =
        File.createTempFile(AngleImageFileIo.TEMPORARY_FILE_PREFIX, ".part", directory)

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
        val descriptor = try {
            // O_NOFOLLOW: la lectura no atraviesa un enlace simbólico, igual que en JVM.
            Os.open(
                file.path,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or O_CLOEXEC,
                0,
            )
        } catch (error: ErrnoException) {
            throw errnoToIOException(error.errno, "abrir", file.path)
        }
        return FileInputStream(descriptor)
    }

    override fun publishWithinOwnedDirectory(temporary: File, target: File, ownedDirectory: File) {
        requireOwnedDirectoryForPublication(this, ownedDirectory, temporary, target)
        // `rename(2)` reemplaza el destino. La garantía de no sobrescribir viene de la propiedad
        // exclusiva del directorio (mkdir + un único creador); esta comprobación evita además
        // reemplazar un nombre que ya estuviera ahí dentro por una anomalía externa.
        if (entryKind(target) != AngleImageEntryKind.ABSENT) {
            throw java.nio.file.FileAlreadyExistsException(target.path, null, "EEXIST: el destino ya existe")
        }
        try {
            Os.rename(temporary.path, target.path)
        } catch (error: ErrnoException) {
            throw errnoToIOException(
                error.errno,
                "renombrar ${temporary.name} a ${target.name}",
                target.path,
            )
        }
    }

    override fun syncDirectory(directory: File): AngleImageDirectorySync {
        val descriptor = try {
            Os.open(
                directory.path,
                OsConstants.O_RDONLY or O_DIRECTORY or O_CLOEXEC,
                0,
            )
        } catch (error: ErrnoException) {
            return if (isSyncUnsupported(error.errno)) {
                AngleImageDirectorySync.UNSUPPORTED
            } else {
                AngleImageDirectorySync.FAILED
            }
        }
        return try {
            Os.fsync(descriptor)
            AngleImageDirectorySync.SYNCED
        } catch (error: ErrnoException) {
            if (isSyncUnsupported(error.errno)) {
                AngleImageDirectorySync.UNSUPPORTED
            } else {
                AngleImageDirectorySync.FAILED
            }
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // El descriptor ya no sirve; el resultado del fsync no cambia por no poder cerrar.
            }
        }
    }

    override fun canonicalFile(file: File): File = file.canonicalFile

    override fun exists(file: File): Boolean = entryKind(file) != AngleImageEntryKind.ABSENT

    override fun isRegularFile(file: File): Boolean = entryKind(file) == AngleImageEntryKind.REGULAR_FILE

    override fun isSymbolicLink(file: File): Boolean = entryKind(file) == AngleImageEntryKind.SYMBOLIC_LINK

    override fun isDirectory(file: File): Boolean = entryKind(file) == AngleImageEntryKind.DIRECTORY

    override fun length(file: File): Long {
        val stat = lstatOrNull(file)
            ?: throw IOException("la ruta no existe: ${file.name}")
        return stat.st_size
    }

    override fun delete(file: File) {
        when (entryKind(file)) {
            AngleImageEntryKind.ABSENT -> return
            AngleImageEntryKind.DIRECTORY -> throw IOException("no se borra un directorio desde el almacén: ${file.name}")
            else -> {
                try {
                    Os.remove(file.path)
                } catch (missing: ErrnoException) {
                    // Semántica deleteIfExists: ausente no es un fallo.
                    if (missing.errno != OsConstants.ENOENT) {
                        throw errnoToIOException(missing.errno, "borrar", file.path)
                    }
                }
            }
        }
    }

    override fun removeEmptyDirectory(directory: File): Boolean {
        if (entryKind(directory) != AngleImageEntryKind.DIRECTORY) return false
        // `File.delete()` sobre un directorio es `rmdir(2)`: no es recursivo y falla (devuelve
        // `false`) si el directorio no está vacío. `Os.rmdir` no forma parte de la API pública.
        return directory.delete()
    }

    /** `lstat` sin seguir enlaces; `null` si la ruta no existe. Otros errnos son fallo tipado. */
    private fun lstatOrNull(file: File): StructStat? =
        try {
            Os.lstat(file.path)
        } catch (missing: ErrnoException) {
            if (missing.errno == OsConstants.ENOENT || missing.errno == OsConstants.ENOTDIR) {
                null
            } else {
                throw errnoToIOException(missing.errno, "stat", file.path)
            }
        }

    /** Errnos que significan "esta plataforma/volumen no soporta fsync de directorio". */
    private fun isSyncUnsupported(errno: Int): Boolean =
        errno == OsConstants.EINVAL || errno == OsConstants.ENOTSUP || errno == OsConstants.EBADF

    private companion object {
        /**
         * `O_DIRECTORY` de Linux (`0200000`). No está expuesto en `android.system.OsConstants`
         * público, igual que otros errnos de [AngleImageErrno]: se declara aquí con su valor
         * genérico de Linux, el único que usan todas las ABI de Android. Abrir el directorio con
         * este flag falla si la ruta no es un directorio real, de modo que el `fsync` no puede
         * aplicarse a otra cosa.
         */
        const val O_DIRECTORY: Int = 0x10000

        /**
         * `O_CLOEXEC` de Linux (`02000000`, es decir `0x80000`).
         *
         * `android.system.OsConstants.O_CLOEXEC` es público pero **desde API 27**, y este proyecto
         * mantiene `minSdk 26`. En un dispositivo API 26 el campo sí existe en el runtime
         * (`OsConstants` lo declara en AOSP como `@hide`), pero al no formar parte de la API
         * pública de 26 no se puede referenciar compilando contra ese SDK. Por eso se declara aquí
         * el literal de Linux, igual que [O_DIRECTORY], en lugar de quitar la bandera: el flag se
         * conserva en todo el rango soportado sin depender de una API de SDK 27.
         *
         * `O_CLOEXEC` vale `02000000` en el `asm-generic/fcntl.h` que siguen todas las ABI de
         * Android (arm, arm64, x86, x86_64, riscv64): no es un valor que cambie por ABI. El núcleo
         * Linux lo interpreta desde 2.6.23, muy anterior a cualquier kernel que ejecute API 26, y
         * el efecto que se pide (que el descriptor no se herede en un `exec`) es el del núcleo.
         * Esto **no** es reflexión ni API oculta: es un literal de compilación con el mismo valor
         * que la constante pública donde existe.
         */
        const val O_CLOEXEC: Int = 0x80000
    }
}
