package com.openjump.app.data.angles

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException

/**
 * Errnos de Linux que la capa Android ([AndroidAngleImageFileIo]) traduce a excepciones tipadas.
 *
 * Se declaran aquí, como constantes propias, para que la traducción sea una función pura
 * verificable en JVM sin cargar `android.system.OsConstants` (que en pruebas unitarias es un stub
 * de la plataforma). Los valores son los genéricos de Linux, los únicos que usan todas las ABI de
 * Android; la prueba instrumentada ejerce el camino real (`Os.mkdir` exclusivo → `EEXIST` y
 * `Os.rename` interno) y falla si la traducción no coincide con lo que devuelve el kernel.
 */
internal object AngleImageErrno {

    const val EPERM: Int = 1
    const val EACCES: Int = 13
    const val EEXIST: Int = 17
    const val EXDEV: Int = 18
    const val ENOSPC: Int = 28
    const val EROFS: Int = 30
    const val EMLINK: Int = 31
    const val ENAMETOOLONG: Int = 36
    const val ELOOP: Int = 40
}

/**
 * `ENOSPC` real del sistema (`mkdir(2)`, `rename(2)`, `write(2)`...): no queda espacio en el volumen.
 *
 * Se distingue por **tipo**, no por el texto del mensaje, para que el almacén decida
 * `InsufficientStorage` sin depender de la redacción del sistema. El mensaje conserva `ENOSPC`
 * para que el diagnóstico y los ganchos de prueba existentes sigan siendo legibles.
 */
internal class AngleImageNoSpaceException(
    operation: String,
    path: String,
) : IOException("ENOSPC: no queda espacio al $operation \"$path\"")

/**
 * Traduce un `errno` del sistema Android a una `IOException` tipada.
 *
 * - `EEXIST` → [FileAlreadyExistsException], que el almacén convierte en
 *   [AngleImageStoreError.KeyAlreadyExists] (publicación sin sobrescritura).
 * - `ENOSPC` → [AngleImageNoSpaceException] (espacio agotado de verdad, fail-closed).
 * - el resto → [FileSystemException] con el nombre simbólico del errno en el motivo: es un fallo
 *   de E/S real, nunca una ausencia ni una colisión.
 *
 * [operation] describe la operación fallida (para diagnóstico) y [path] la ruta afectada; ninguno
 * de los dos se interpreta para tomar decisiones.
 */
internal fun errnoToIOException(errno: Int, operation: String, path: String): IOException = when (errno) {
    AngleImageErrno.EEXIST -> FileAlreadyExistsException(path, null, "EEXIST: $operation")
    AngleImageErrno.ENOSPC -> AngleImageNoSpaceException(operation, path)
    else -> FileSystemException(path, null, "${errnoName(errno)}: $operation")
}

/** Nombre simbólico del errno para diagnóstico; un valor desconocido se muestra numérico. */
internal fun errnoName(errno: Int): String = when (errno) {
    AngleImageErrno.EPERM -> "EPERM"
    AngleImageErrno.EACCES -> "EACCES"
    AngleImageErrno.EEXIST -> "EEXIST"
    AngleImageErrno.EXDEV -> "EXDEV"
    AngleImageErrno.ENOSPC -> "ENOSPC"
    AngleImageErrno.EROFS -> "EROFS"
    AngleImageErrno.EMLINK -> "EMLINK"
    AngleImageErrno.ENAMETOOLONG -> "ENAMETOOLONG"
    AngleImageErrno.ELOOP -> "ELOOP"
    else -> "errno=$errno"
}
