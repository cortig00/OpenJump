package com.openjump.app.data.angles

import java.io.File
import java.io.IOException

/**
 * Tipo de una entrada del almacén, obtenido con `lstat` (nunca siguiendo enlaces).
 *
 * Existe para que el almacén pueda distinguir "no hay nada", "hay un fichero regular" y "hay algo
 * que no se debe seguir" con **una** consulta, sin encadenar `exists()`/`isFile()` (cada una de las
 * cuales es una comprobación separada y, por tanto, una ventana distinta).
 */
enum class AngleImageEntryKind {
    /** La ruta no existe (incluye el caso de un ancestro que no es directorio). */
    ABSENT,

    /** Fichero regular. */
    REGULAR_FILE,

    /** Directorio real (no un enlace). */
    DIRECTORY,

    /** Enlace simbólico, incluso colgante: no se sigue nunca. */
    SYMBOLIC_LINK,

    /** Cualquier otra cosa (FIFO, socket, dispositivo): nunca es una imagen. */
    OTHER,
}

/**
 * Medición del uso real del almacén, compartida por los dos backends ([NioAngleImageFileIo] y
 * [AndroidAngleImageFileIo]) para que ambos cuenten **lo mismo**.
 *
 * Cuenta, sin seguir enlaces simbólicos:
 *
 * - los **finales** publicados (`angle-images/<clave>/image.bin`);
 * - los **temporales** propios, estén en vuelo o abandonados (`angle-images/<clave>/.tmp-…`);
 * - los **huérfanos**: cualquier fichero regular dentro de un directorio de clave, tenga el nombre
 *   que tenga (por ejemplo un final publicado cuyo rename se completó y cuya fila de Room nunca
 *   llegó a escribirse);
 * - el **legado plano**: ficheros regulares directamente en `angle-images/`, publicados con el
 *   layout anterior de E1.
 *
 * Los directorios no suman bytes (sólo metadatos del sistema de ficheros).
 *
 * **Fail-closed ante estructura inesperada.** Un enlace simbólico, un directorio anidado más
 * profundo de lo que permite el layout, o un desbordamiento de la suma lanzan [IOException]: el
 * presupuesto no se puede medir con certeza, así que no se autoriza ninguna escritura. No se ignora
 * nada en silencio (ignorar entradas permitiría que el almacén creciera sin contabilizarse).
 *
 * **Trabajo acotado.** El recorrido está limitado en profundidad ([AngleImageFileIo.MAX_TRAVERSAL_DEPTH])
 * y en número de entradas visitadas ([AngleImageFileIo.MAX_TRAVERSAL_ENTRIES_TOTAL]): un directorio
 * con millones de entradas no puede bloquear la app; se rechaza con [IOException] en vez de
 * recorrerlo. La medición es de bytes en disco, no un análisis de contenido: no abre ficheros.
 */
internal fun measureAngleImageUsageBytes(fileIo: AngleImageFileIo, directory: File): Long {
    if (fileIo.entryKind(directory) != AngleImageEntryKind.DIRECTORY) return 0L
    val budget = UsageTraversalBudget()
    var total = 0L
    for (name in fileIo.listEntryNames(directory)) {
        budget.count(name)
        val entry = File(directory, name)
        when (val kind = fileIo.entryKind(entry)) {
            AngleImageEntryKind.ABSENT -> Unit
            AngleImageEntryKind.REGULAR_FILE -> total = total.addExact(fileIo.length(entry))
            AngleImageEntryKind.DIRECTORY -> total = total.addExact(
                measureKeyDirectoryBytes(fileIo, entry, budget),
            )
            AngleImageEntryKind.SYMBOLIC_LINK -> throw IOException(
                "enlace simbólico inesperado dentro del almacén: $name",
            )
            AngleImageEntryKind.OTHER -> throw IOException(
                "entrada inesperada en el almacén ($kind): $name",
            )
        }
    }
    return total
}

/**
 * Bytes de un directorio de clave. Sólo admite ficheros regulares dentro: un subdirectorio,
 * un enlace o un tipo especial indican una estructura que el almacén no produce, y contarla
 * "a ojo" dejaría bytes fuera del presupuesto.
 */
private fun measureKeyDirectoryBytes(
    fileIo: AngleImageFileIo,
    directory: File,
    budget: UsageTraversalBudget,
): Long {
    var total = 0L
    for (name in fileIo.listEntryNames(directory)) {
        budget.count(name)
        val entry = File(directory, name)
        when (val kind = fileIo.entryKind(entry)) {
            AngleImageEntryKind.ABSENT -> Unit
            AngleImageEntryKind.REGULAR_FILE -> total = total.addExact(fileIo.length(entry))
            // Profundidad agotada: el layout sólo tiene `angle-images/<clave>/<fichero>`
            // (MAX_TRAVERSAL_DEPTH niveles), así que un subdirectorio aquí no se puede contabilizar.
            AngleImageEntryKind.DIRECTORY, AngleImageEntryKind.SYMBOLIC_LINK, AngleImageEntryKind.OTHER ->
                throw IOException(
                    "estructura inesperada dentro del almacén ($kind) a profundidad " +
                        "${AngleImageFileIo.MAX_TRAVERSAL_DEPTH}: $name",
                )
        }
    }
    return total
}

/** Presupuesto de entradas visitadas por una medición: acota el trabajo ante directorios enormes. */
private class UsageTraversalBudget {

    private var visited = 0

    fun count(name: String) {
        visited++
        if (visited > AngleImageFileIo.MAX_TRAVERSAL_ENTRIES_TOTAL) {
            throw IOException(
                "el almacén tiene más de ${AngleImageFileIo.MAX_TRAVERSAL_ENTRIES_TOTAL} entradas " +
                    "(límite de recorrido): última vista \"$name\"",
            )
        }
    }
}

/** Suma con desbordamiento cerrado: un total inverosímil no se interpreta como hueco libre. */
private fun Long.addExact(delta: Long): Long = try {
    Math.addExact(this, delta)
} catch (_: ArithmeticException) {
    throw IOException("desbordamiento al medir el uso del almacén")
}

/**
 * Comprueba la **condición de propiedad** antes de publicar dentro de un directorio de clave.
 *
 * El almacén crea el directorio de clave con un `mkdir` exclusivo y no lo reutiliza nunca; el
 * backend de E/S no puede verificar esa historia, así que comprueba lo que sí está a su alcance:
 * que [ownedDirectory] es un directorio real (no un enlace), que el temporal y el destino cuelgan
 * **exactamente** de él y que el temporal tiene un nombre propio de temporal. Si algo no cuadra, no
 * se publica nada.
 *
 * Esta comprobación es necesaria pero **no suficiente** para considerar la publicación
 * "no sobrescribible": en POSIX `rename(2)` reemplaza el destino. La garantía frente a reemplazos
 * proviene de que sólo el creador exclusivo del directorio escribe dentro de él
 * (escritores cooperantes del proceso), no de `rename`.
 */
internal fun requireOwnedDirectoryForPublication(
    fileIo: AngleImageFileIo,
    ownedDirectory: File,
    temporary: File,
    target: File,
) {
    if (fileIo.entryKind(ownedDirectory) != AngleImageEntryKind.DIRECTORY) {
        throw IOException("el directorio de publicación no es un directorio real: ${ownedDirectory.name}")
    }
    val owned = fileIo.canonicalFile(ownedDirectory)
    val temporaryParent = fileIo.canonicalFile(
        temporary.parentFile ?: throw IOException("el temporal no tiene directorio padre"),
    )
    val targetParent = fileIo.canonicalFile(
        target.parentFile ?: throw IOException("el destino no tiene directorio padre"),
    )
    if (temporaryParent.path != owned.path || targetParent.path != owned.path) {
        throw IOException("la publicación debe ocurrir dentro del directorio de clave propio")
    }
    if (!temporary.name.startsWith(AngleImageFileIo.TEMPORARY_FILE_PREFIX)) {
        throw IOException("el origen de la publicación no es un temporal propio: ${temporary.name}")
    }
    if (target.name != AngleImageFileIo.FINAL_FILE_NAME) {
        throw IOException("el destino de la publicación no es el nombre fijado: ${target.name}")
    }
}
