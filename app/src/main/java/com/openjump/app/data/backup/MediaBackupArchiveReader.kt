package com.openjump.app.data.backup

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Cota del contenedor **comprimido** completo que el lector acepta copiar al staging.
 *
 * El maximo legitimo que puede producir el escritor es el manifiesto ([MEDIA_BACKUP_MAX_MANIFEST_BYTES],
 * 16 MiB) mas el total de imagenes ([MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES], 512 MiB) sin comprimir;
 * deflate puede crecer ~0,03 % y cada entrada anade dos cabeceras y un descriptor (como mucho 4097
 * entradas). 2 MiB de holgura cubren ese sobrecoste con margen, de modo que ningun backup valido se
 * rechaza por esta cota y un flujo hostil no puede llenar el disco.
 */
const val MEDIA_BACKUP_MAX_ARCHIVE_BYTES: Long =
    MEDIA_BACKUP_MAX_MANIFEST_BYTES + MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES + 2L * 1024L * 1024L

/**
 * Lector aislado del contenedor ZIP del backup con imagenes (slice G2b).
 *
 * La entrada es **no confiable** y no se le concede ninguna suposicion. Contrato:
 *  1. **Staging privado y propio**: el [InputStream] entrante se copia a un fichero temporal nuevo
 *     (`0600`, prefijo/sufijo propios) dentro de un directorio de staging **explicito** del llamante.
 *     La copia esta acotada por [limits] (`maxArchiveBytes`), consulta la cancelacion antes de cada
 *     bloque, suma con aritmetica comprobada, hace `fsync` del descriptor y cierra antes de validar
 *     nada. El flujo del llamante no se cierra (lo posee el llamante) y no se crea ni modifica
 *     ningun otro fichero.
 *  2. **Estructura ZIP probada, no supuesta**: antes de descomprimir un solo byte se parsea el
 *     contenedor con [parseLayout]: EOCD exactamente al final del fichero (sin comentario de
 *     archivo ni cola oculta), sin marcadores ni centinelas ZIP64, un solo disco, recuento de
 *     entradas acotado, central directory que termina justo donde empieza el EOCD, entradas
 *     canonicas (`manifest.json` o `images/<hex32>.png`) sin duplicados, sin entradas de
 *     directorio, metodo DEFLATED exacto, flags sin cifrado, sin campos extra ni comentarios por
 *     entrada, offsets de cabecera local dentro de `[0, central)`, y **cabeceras locales
 *     contrastadas con la central** (nombre, metodo, flags, CRC y tamanos, incluido el data
 *     descriptor que escribe el productor). Las regiones locales deben empezar en 0, ser
 *     contiguas entre si y terminar exactamente en la central directory: un hueco, una cola o un
 *     fichero truncado no encajan y se rechazan. Cualquier aspecto que no se pueda probar se
 *     rechaza (fail-closed).
 *  3. **Manifiesto unico y acotado**: se exige exactamente una entrada `manifest.json` de como mucho
 *     [MEDIA_BACKUP_MAX_MANIFEST_BYTES] (y [limits]), se relee completa verificando longitud exacta
 *     y CRC-32 contra la central, y se decodifica con el codec estricto
 *     ([MediaBackupManifestCodec]) seguido de la validacion completa ([MediaBackupManifest.validate]
 *     via [MediaBackupManifest.archivePlan]). Un manifiesto invalido o un documento legacy que el
 *     contrato v1 no aceptaria aborta la importacion.
 *  4. **Conjunto exacto de entradas**: el nombre de cada imagen se deriva del manifiesto
 *     ([MediaBackupArchive.imageEntryName]); el contenedor debe declarar exactamente esas entradas
 *     mas el manifiesto. Ni una extra, ni una ausente, ni una duplicada, ni ningun nombre libre: los
 *     nombres ZIP nunca se convierten en rutas y no existe API que los materialice.
 *  5. **Descompresion en streaming y verificada**: cada imagen se descomprime por bloques con el
 *     tamano **declarado por el manifiesto** como cota (nunca `available()` ni un tope inferido del
 *     contenedor), sin materializar la imagen en memoria, y se compara byte a byte observado,
 *     SHA-256 y CRC-32 con el manifiesto y con la central. Un exceso (bomba ZIP), un defecto
 *     (truncado), un CRC o un SHA-256 distintos abortan la importacion. El total observado se
 *     vuelve a acotar a [MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES].
 *  6. **Preparado solo si TODO valida**: [read] no devuelve nada hasta que estructura, manifiesto y
 *     todas las imagenes han sido verificadas. Devuelve un [MediaBackupPreparedImport], dueno del
 *     temporal, con el manifiesto validado y una API de transferencia
 *     ([MediaBackupPreparedImport.copyImageTo]) que **recomprueba** tamano, CRC-32 y SHA-256 del
 *     fichero staged mientras lo entrega al sumidero del llamante y solo retorna normalmente si todo
 *     coincide; una transferencia parcial o cancelada lanza y nunca se informa como exito.
 *  7. **Fallo = nada utilizable**: cancelacion, overflow, limite, estructura, manifiesto, integridad o
 *     E/S dejan la operacion abortada y borran **solo** el temporal creado por esta llamada (nunca
 *     `deleteRecursively`, nunca otro fichero ni directorio). Si la limpieza falla se informa con
 *     [MediaBackupImportException.CleanupFailed] y el fichero se preserva. [MediaBackupPreparedImport.close]
 *     es idempotente y solo borra su propio temporal.
 *
 * Fuera de alcance de este slice: publicar en Room, mover/copiar al almacen privado, UI, restore,
 * validacion de contenido PNG o de dimensiones (el contrato sella bytes opacos: la capa de restore
 * tendra que hacer su propio probe explicito) y cualquier log con rutas, claves o contenidos.
 */
class MediaBackupArchiveReader internal constructor(
    private val stagingDirectory: File,
    private val filePort: MediaBackupImportFilePort,
    private val limits: MediaBackupReaderLimits,
) {

    /** Lector de produccion: staging explicito del llamante y puerto de ficheros real. */
    constructor(stagingDirectory: File) : this(
        stagingDirectory = stagingDirectory,
        filePort = DefaultMediaBackupImportFilePort,
        limits = MediaBackupReaderLimits(),
    )

    /**
     * Copia, valida y entrega el contenedor de [input].
     *
     * Devuelve un [MediaBackupPreparedImport] solo cuando la estructura, el manifiesto y todas las
     * imagenes han sido verificadas. Si no, lanza una [MediaBackupImportException] y el temporal
     * propio se elimina (o se informa de que no pudo eliminarse). [cancellation] se consulta antes de
     * crear el fichero, antes de cada bloque copiado, entre entradas del directorio central, entre
     * cabeceras locales, antes y durante la lectura del manifiesto y durante cada bloque de cada
     * imagen.
     */
    fun read(
        input: InputStream,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupPreparedImport {
        val staging = stagingDirectoryOrThrow()
        checkImportCanceled(cancellation)
        val temporary = createTemporary(staging)
        try {
            copyToStaging(temporary, input, cancellation)
            checkImportCanceled(cancellation)
            val layout = parseLayout(temporary, limits, cancellation)
            checkImportCanceled(cancellation)
            val manifestBytes = readManifestBytes(temporary, layout, cancellation)
            checkImportCanceled(cancellation)
            val manifest = decodeManifest(manifestBytes)
            val plan = planFor(manifest)
            requireExactEntrySet(layout, plan)
            verifyImages(temporary, layout, plan, cancellation)
            checkImportCanceled(cancellation)
            return MediaBackupPreparedImport(
                manifest = manifest,
                manifestEntryName = plan.manifestEntryName,
                imageKeys = plan.imageEntries.map { it.imageKey },
                imageByteCounts = plan.imageEntries.associate { it.imageKey to it.byteCount },
                manifestByteCount = manifestBytes.size.toLong(),
                totalImageByteCount = plan.totalImageByteCount,
                archiveByteCount = stagingFileLength(temporary),
                archiveFile = temporary,
                stagingDirectory = staging,
                deleteFile = filePort::delete,
            )
        } catch (failure: Throwable) {
            failAndCleanUp(temporary, staging, failure)
        }
    }

    // --- Copia al staging -----------------------------------------------------------------------

    private fun stagingDirectoryOrThrow(): File {
        if (Files.isSymbolicLink(stagingDirectory.toPath())) {
            throw MediaBackupImportException.StagingRejected(
                "media backup import staging directory must not be a symbolic link",
            )
        }
        if (!stagingDirectory.isDirectory) {
            throw MediaBackupImportException.StagingRejected(
                "media backup import staging directory does not exist or is not a directory",
            )
        }
        return try {
            stagingDirectory.canonicalFile
        } catch (failure: IOException) {
            throw MediaBackupImportException.StagingRejected(
                "media backup import staging directory cannot be resolved: ${failure.message}",
            )
        }
    }

    private fun createTemporary(staging: File): File = try {
        filePort.createStagingFile(staging)
    } catch (failure: IOException) {
        throw MediaBackupImportException.CopyFailed(failure)
    }

    /**
     * Copia el flujo entrante con cota total, cancelacion por bloque, suma comprobada, `fsync` y
     * cierre. El fichero se escribe entero antes de parsearse: la validacion lee del disco lo mismo
     * que se sincronizo, no del flujo del llamante.
     */
    private fun copyToStaging(temporary: File, input: InputStream, cancellation: MediaBackupArchiveCancellation) {
        val output = try {
            filePort.openOutput(temporary)
        } catch (failure: IOException) {
            throw MediaBackupImportException.CopyFailed(failure)
        }
        var completed = false
        try {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                checkImportCanceled(cancellation)
                val count = try {
                    input.read(buffer)
                } catch (failure: IOException) {
                    throw MediaBackupImportException.CopyFailed(failure)
                }
                if (count < 0) break
                if (count == 0) {
                    // Flujo que no progresa: se fuerza un byte para no girar en vacio.
                    val single = try {
                        input.read()
                    } catch (failure: IOException) {
                        throw MediaBackupImportException.CopyFailed(failure)
                    }
                    if (single < 0) break
                    total = try {
                        Math.addExact(total, 1L)
                    } catch (overflow: ArithmeticException) {
                        throw MediaBackupImportException.LimitExceeded(
                            "media backup archive is larger than ${limits.maxArchiveBytes} bytes",
                        )
                    }
                    if (total > limits.maxArchiveBytes) {
                        throw MediaBackupImportException.LimitExceeded(
                            "media backup archive is larger than ${limits.maxArchiveBytes} bytes",
                        )
                    }
                    try {
                        output.output.write(single)
                    } catch (failure: IOException) {
                        throw MediaBackupImportException.CopyFailed(failure)
                    }
                    continue
                }
                total = try {
                    Math.addExact(total, count.toLong())
                } catch (overflow: ArithmeticException) {
                    throw MediaBackupImportException.LimitExceeded(
                        "media backup archive is larger than ${limits.maxArchiveBytes} bytes",
                    )
                }
                if (total > limits.maxArchiveBytes) {
                    // Acotado: no se consume ni se escribe el resto de un contenedor que ya no cabe.
                    throw MediaBackupImportException.LimitExceeded(
                        "media backup archive is larger than ${limits.maxArchiveBytes} bytes",
                    )
                }
                try {
                    output.output.write(buffer, 0, count)
                } catch (failure: IOException) {
                    throw MediaBackupImportException.CopyFailed(failure)
                }
            }
            checkImportCanceled(cancellation)
            try {
                output.sync()
            } catch (failure: IOException) {
                throw MediaBackupImportException.SyncFailed(failure)
            }
            try {
                output.close()
            } catch (failure: IOException) {
                throw MediaBackupImportException.CopyFailed(failure)
            }
            completed = true
        } finally {
            if (!completed) {
                runCatching { output.close() }
            }
        }
    }

    private fun stagingFileLength(file: File): Long = file.length()

    // --- Manifiesto -----------------------------------------------------------------------------

    /**
     * Relee `manifest.json` completo con longitud y CRC-32 exactos contra la central. Se materializa
     * en memoria porque el manifiesto esta acotado a [MEDIA_BACKUP_MAX_MANIFEST_BYTES] (16 MiB) y el
     * codec necesita los bytes completos; las imagenes, en cambio, nunca se cargan enteras.
     */
    private fun readManifestBytes(
        file: File,
        layout: ZipLayout,
        cancellation: MediaBackupArchiveCancellation,
    ): ByteArray {
        val record = layout.entries.singleOrNull { it.name == MEDIA_BACKUP_MANIFEST_ENTRY }
            ?: throw MediaBackupImportException.StructureRejected(
                "media backup archive must contain exactly one '$MEDIA_BACKUP_MANIFEST_ENTRY' entry",
            )
        if (record.uncompressedSize > limits.maxManifestBytes) {
            throw MediaBackupImportException.LimitExceeded(
                "media backup manifest is larger than ${limits.maxManifestBytes} bytes",
            )
        }
        if (record.uncompressedSize < 1L) {
            throw MediaBackupImportException.StructureRejected("media backup archive manifest entry is empty")
        }
        val bytes = ByteArray(record.uncompressedSize.toInt())
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(record.name)
                    ?: throw MediaBackupImportException.StructureRejected(
                        "media backup archive manifest entry disappeared from the central directory",
                    )
                var offset = 0
                val crc = CRC32()
                zip.getInputStream(entry).use { stream ->
                    while (offset < bytes.size) {
                        checkImportCanceled(cancellation)
                        val count = stream.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        if (count == 0) continue
                        offset += count
                        crc.update(bytes, offset - count, count)
                    }
                    // Un byte mas alla de la longitud declarada: la entrada no puede ser la declarada
                    // (el flujo de ZipFile esta acotado al tamano central, asi que normalmente -1).
                    checkImportCanceled(cancellation)
                    if (stream.read() >= 0) {
                        throw MediaBackupImportException.ManifestRejected(
                            "media backup manifest entry is longer than its declared length",
                        )
                    }
                }
                if (crc.value != record.crc32) {
                    throw MediaBackupImportException.ManifestRejected(
                        "media backup manifest entry does not match its central directory CRC-32",
                    )
                }
                if (offset != bytes.size) {
                    throw MediaBackupImportException.ManifestRejected(
                        "media backup manifest entry is truncated",
                    )
                }
            }
        } catch (failure: MediaBackupImportException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupImportException.ManifestRejected(
                "media backup manifest entry cannot be read: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupImportException.ManifestRejected(
                "media backup manifest entry cannot be read: ${failure.message}",
                failure,
            )
        }
        return bytes
    }

    private fun decodeManifest(bytes: ByteArray): MediaBackupManifest = try {
        MediaBackupManifestCodec.decode(bytes, MediaBackupCodecLimits(maxManifestBytes = limits.maxManifestBytes))
    } catch (failure: MediaBackupImportException) {
        throw failure
    } catch (failure: Exception) {
        throw MediaBackupImportException.ManifestRejected(
            "media backup manifest is not a valid media backup: ${failure.message}",
            failure,
        )
    }

    /** Plan canonico del manifiesto reaplicando los topes del lector (que solo acotan mas). */
    private fun planFor(manifest: MediaBackupManifest): MediaBackupArchivePlan {
        val plan = try {
            manifest.archivePlan()
        } catch (failure: Exception) {
            throw MediaBackupImportException.ManifestRejected(
                "media backup manifest is invalid: ${failure.message}",
                failure,
            )
        }
        if (plan.imageCount > limits.maxImages) {
            throw MediaBackupImportException.LimitExceeded(
                "media backup declares more than ${limits.maxImages} images",
            )
        }
        if (plan.imageCount + 1 > limits.maxEntries) {
            throw MediaBackupImportException.LimitExceeded(
                "media backup declares more than ${limits.maxEntries} entries",
            )
        }
        var total = 0L
        plan.imageEntries.forEach { entry ->
            if (entry.byteCount < 1L || entry.byteCount > limits.maxImageBytes) {
                throw MediaBackupImportException.LimitExceeded(
                    "media backup image ${entry.imageKey} byteCount must be in 1..${limits.maxImageBytes}",
                )
            }
            total = try {
                Math.addExact(total, entry.byteCount)
            } catch (overflow: ArithmeticException) {
                throw MediaBackupImportException.LimitExceeded("media backup image bytes overflow")
            }
            if (total > limits.maxTotalImageBytes) {
                throw MediaBackupImportException.LimitExceeded(
                    "media backup declares more than ${limits.maxTotalImageBytes} image bytes",
                )
            }
        }
        return plan
    }

    /** Conjunto **exacto** de nombres: el manifiesto y sus imagenes canonicas, ni uno mas ni uno menos. */
    private fun requireExactEntrySet(layout: ZipLayout, plan: MediaBackupArchivePlan) {
        val expected = HashSet<String>(plan.imageCount + 1)
        expected += plan.manifestEntryName
        plan.imageEntries.forEach { expected += it.entryName }
        val observed = layout.entries.mapTo(LinkedHashSet()) { it.name }
        if (observed.size != layout.entries.size) {
            throw MediaBackupImportException.StructureRejected("media backup archive declares duplicate entries")
        }
        val missing = expected - observed
        if (missing.isNotEmpty()) {
            throw MediaBackupImportException.EntrySetRejected(
                "media backup archive is missing ${missing.size} entr${if (missing.size == 1) "y" else "ies"} declared by the manifest",
            )
        }
        val extra = observed - expected
        if (extra.isNotEmpty()) {
            // Los nombres no canonicos ya se rechazaron en el parser; aqui solo sobra una clave
            // canonica que el manifiesto no declara.
            throw MediaBackupImportException.EntrySetRejected(
                "media backup archive carries ${extra.size} entr${if (extra.size == 1) "y" else "ies"} that the manifest does not declare",
            )
        }
        if (layout.entries.size != expected.size) {
            throw MediaBackupImportException.EntrySetRejected(
                "media backup archive carries ${layout.entries.size} entries, expected ${expected.size}",
            )
        }
    }

    // --- Imagenes -------------------------------------------------------------------------------

    /**
     * Descomprime cada imagen en streaming y compara lo observado con el manifiesto y con la central.
     * El tamano declarado por el manifiesto es la cota dura: en cuanto lo supera se aborta sin
     * consumir el resto, de modo que una bomba ZIP no puede inflarse mas alla de lo declarado.
     */
    private fun verifyImages(
        file: File,
        layout: ZipLayout,
        plan: MediaBackupArchivePlan,
        cancellation: MediaBackupArchiveCancellation,
    ) {
        val centralByName = layout.entries.associateBy { it.name }
        var total = 0L
        try {
            ZipFile(file).use { zip ->
                plan.imageEntries.forEach { planned ->
                    checkImportCanceled(cancellation)
                    val record = centralByName.getValue(planned.entryName)
                    if (record.uncompressedSize != planned.byteCount) {
                        throw MediaBackupImportException.ImageIntegrityFailed(
                            planned.imageKey,
                            "entry declares ${record.uncompressedSize} bytes, the manifest declares ${planned.byteCount}",
                        )
                    }
                    val entry = zip.getEntry(planned.entryName)
                        ?: throw MediaBackupImportException.StructureRejected(
                            "media backup archive image entry disappeared from the central directory",
                        )
                    if (entry.size != planned.byteCount) {
                        throw MediaBackupImportException.ImageIntegrityFailed(
                            planned.imageKey,
                            "entry size ${entry.size} does not match the manifest byteCount ${planned.byteCount}",
                        )
                    }
                    verifyImageStream(zip, entry, planned, cancellation)
                    total = try {
                        Math.addExact(total, planned.byteCount)
                    } catch (overflow: ArithmeticException) {
                        throw MediaBackupImportException.LimitExceeded("media backup image bytes overflow")
                    }
                    if (total > limits.maxTotalImageBytes) {
                        throw MediaBackupImportException.LimitExceeded(
                            "media backup carries more than ${limits.maxTotalImageBytes} image bytes",
                        )
                    }
                }
            }
        } catch (failure: MediaBackupImportException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive cannot be re-opened for image verification: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive cannot be re-opened for image verification: ${failure.message}",
                failure,
            )
        }
    }

    private fun verifyImageStream(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        planned: MediaBackupImageEntry,
        cancellation: MediaBackupArchiveCancellation,
    ) {
        val digest = MessageDigest.getInstance(SHA256)
        val crc = CRC32()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var observed = 0L
        try {
            zip.getInputStream(entry).use { stream ->
                while (true) {
                    checkImportCanceled(cancellation)
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    observed = try {
                        Math.addExact(observed, count.toLong())
                    } catch (overflow: ArithmeticException) {
                        throw MediaBackupImportException.ImageIntegrityFailed(
                            planned.imageKey,
                            "entry expands beyond the ${planned.byteCount} bytes declared by the manifest",
                        )
                    }
                    if (observed > planned.byteCount) {
                        // Cota dura del manifiesto: no se infla -- ni se consume -- un byte mas.
                        throw MediaBackupImportException.ImageIntegrityFailed(
                            planned.imageKey,
                            "entry expands beyond the ${planned.byteCount} bytes declared by the manifest",
                        )
                    }
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
            }
        } catch (failure: MediaBackupImportException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                planned.imageKey,
                "entry cannot be decompressed: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                planned.imageKey,
                "entry cannot be decompressed: ${failure.message}",
                failure,
            )
        }
        if (observed != planned.byteCount) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                planned.imageKey,
                "entry decoded $observed bytes, the manifest declares ${planned.byteCount}",
            )
        }
        if (crc.value != entry.crc) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                planned.imageKey,
                "entry CRC-32 does not match the central directory",
            )
        }
        if (importToLowerHex(digest.digest()) != planned.sha256) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                planned.imageKey,
                "entry SHA-256 does not match the manifest",
            )
        }
    }

    // --- Limpieza y cancelacion -----------------------------------------------------------------

    private fun failAndCleanUp(temporary: File, staging: File, failure: Throwable): Nothing {
        val deleted = try {
            deleteOwnImport(temporary, staging, filePort::delete)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
            false
        }
        if (!deleted && temporary.exists()) {
            throw MediaBackupImportException.CleanupFailed(temporary.name, failure)
        }
        throw failure
    }

    companion object {
        internal const val SHA256: String = "SHA-256"

        /** Buffer pequeno: ni el contenedor ni las imagenes se materializan completos en memoria. */
        internal const val COPY_BUFFER_BYTES: Int = 64 * 1024
    }
}

/**
 * Contenedor importado y verificado: **dueno** del fichero temporal.
 *
 * Se entrega solo cuando estructura, manifiesto y todas las imagenes han pasado la verificacion. No
 * expone el fichero staged ni una URI: la unica forma de sacar bytes es [copyImageTo], que resuelve
 * la imagen por su **clave opaca**, la busca en el contenedor y **vuelve a comprobar** tamano,
 * CRC-32 y SHA-256 mientras la entrega al sumidero. Una transferencia solo se considera correcta si
 * el metodo retorna normalmente; si lanza, el sumidero debe descartarse.
 *
 * [close] elimina ese unico fichero (idempotente) y nunca toca otros ficheros ni directorios; si el
 * borrado falla, lanza [MediaBackupImportException.CleanupFailed] y el fichero se conserva.
 */
class MediaBackupPreparedImport internal constructor(
    val manifest: MediaBackupManifest,
    val manifestEntryName: String,
    val imageKeys: List<String>,
    private val imageByteCounts: Map<String, Long>,
    val manifestByteCount: Long,
    val totalImageByteCount: Long,
    val archiveByteCount: Long,
    private val archiveFile: File,
    private val stagingDirectory: File,
    private val deleteFile: (File) -> Boolean,
) : Closeable {

    val imageCount: Int get() = imageKeys.size

    var isClosed: Boolean = false
        private set

    /** Bytes declarados (y verificados) de [imageKey]; `null` si el manifiesto no la declara. */
    fun imageByteCount(imageKey: String): Long? = imageByteCounts[imageKey]

    /**
     * Transfiere la imagen [imageKey] a [output] releyendo el contenedor staged y recomprobando
     * tamano, CRC-32 y SHA-256 **durante** la copia.
     *
     * El exito solo se informa cuando todos los bytes se han entregado y coinciden con el manifiesto
     * y con la central. Si el contenedor staged ya no es el verificado, si esta truncado o alterado,
     * si el sumidero falla o si se cancela, se lanza [MediaBackupImportException] (o [Canceled]) y el
     * llamante **no** debe publicar lo escrito: el sumidero puede haber recibido bytes parciales.
     * [output] no se cierra ni se vacia desde aqui (lo posee el llamante). La cancelacion no cierra
     * este objeto: la importacion sigue siendo utilizable.
     */
    fun copyImageTo(
        imageKey: String,
        output: OutputStream,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupImageTransfer {
        if (isClosed) throw MediaBackupImportException.ArchiveClosed
        val image = manifest.angleV2.images.singleOrNull { it.imageKey == imageKey }
            ?: throw MediaBackupImportException.ImageUnavailable(imageKey)
        checkImportCanceled(cancellation)
        val entryName = MediaBackupArchive.imageEntryName(imageKey)
        val digest = MessageDigest.getInstance(MediaBackupArchiveReader.SHA256)
        val crc = CRC32()
        val buffer = ByteArray(MediaBackupArchiveReader.COPY_BUFFER_BYTES)
        var observed = 0L
        var centralCrc = -1L
        try {
            ZipFile(archiveFile).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: throw MediaBackupImportException.ImageUnavailable(imageKey)
                if (entry.size != image.byteCount) {
                    throw MediaBackupImportException.ImageIntegrityFailed(
                        imageKey,
                        "staged entry size ${entry.size} does not match the manifest byteCount ${image.byteCount}",
                    )
                }
                centralCrc = entry.crc
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        checkImportCanceled(cancellation)
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        observed = try {
                            Math.addExact(observed, count.toLong())
                        } catch (overflow: ArithmeticException) {
                            throw MediaBackupImportException.ImageIntegrityFailed(
                                imageKey,
                                "staged entry expands beyond the declared ${image.byteCount} bytes",
                            )
                        }
                        if (observed > image.byteCount) {
                            throw MediaBackupImportException.ImageIntegrityFailed(
                                imageKey,
                                "staged entry expands beyond the declared ${image.byteCount} bytes",
                            )
                        }
                        digest.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        try {
                            output.write(buffer, 0, count)
                        } catch (failure: IOException) {
                            throw MediaBackupImportException.TransferFailed(imageKey, failure)
                        }
                    }
                }
            }
        } catch (failure: MediaBackupImportException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                imageKey,
                "staged entry cannot be transferred: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                imageKey,
                "staged entry cannot be transferred: ${failure.message}",
                failure,
            )
        }
        if (observed != image.byteCount) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                imageKey,
                "staged entry produced $observed bytes, expected ${image.byteCount}",
            )
        }
        if (crc.value != centralCrc) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                imageKey,
                "staged entry CRC-32 does not match the central directory",
            )
        }
        if (importToLowerHex(digest.digest()) != image.sha256) {
            throw MediaBackupImportException.ImageIntegrityFailed(
                imageKey,
                "staged entry SHA-256 does not match the manifest",
            )
        }
        return MediaBackupImageTransfer(imageKey, image.byteCount, image.sha256)
    }

    /** Elimina el temporal propio. Idempotente; solo borra su fichero regular dentro del staging. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        val target = archiveFile
        if (!target.exists()) return
        val deleted = try {
            deleteOwnImport(target, stagingDirectory, deleteFile)
        } catch (failure: Throwable) {
            throw MediaBackupImportException.CleanupFailed(target.name, failure)
        }
        if (!deleted && target.exists()) {
            throw MediaBackupImportException.CleanupFailed(target.name, null)
        }
    }
}

/** Resultado de una transferencia verificada de una imagen. */
data class MediaBackupImageTransfer(
    val imageKey: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * Fallos del lector. Todos implican que **no** se entrega (o no se transfiere) contenido valido; en
 * [read], todos salvo [CleanupFailed] implican ademas que el temporal propio se ha eliminado.
 */
sealed class MediaBackupImportException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** El directorio de staging explicito no es utilizable (no existe, no es directorio, enlace...). */
    class StagingRejected(detail: String) : MediaBackupImportException(detail)

    /** El contenedor o el manifiesto superan un presupuesto del lector. */
    class LimitExceeded(detail: String) : MediaBackupImportException(detail)

    /** Fallo de E/S copiando el flujo entrante al staging. */
    class CopyFailed(cause: Throwable?) :
        MediaBackupImportException("Media backup archive could not be copied to staging: ${cause?.message}", cause)

    /** El descriptor del temporal no pudo sincronizarse; no se valida ni se entrega nada. */
    class SyncFailed(cause: Throwable?) :
        MediaBackupImportException("Media backup archive could not be synced: ${cause?.message}", cause)

    /** La estructura ZIP no cumple el contrato estricto (EOCD, central, local, nombres, metodos...). */
    class StructureRejected(detail: String, cause: Throwable? = null) :
        MediaBackupImportException("Media backup archive rejected: $detail", cause)

    /** `manifest.json` no se puede leer completo o no cumple el contrato del manifiesto. */
    class ManifestRejected(detail: String, cause: Throwable? = null) :
        MediaBackupImportException("Media backup manifest rejected: $detail", cause)

    /** Las entradas del contenedor no son exactamente las que el manifiesto declara. */
    class EntrySetRejected(detail: String) :
        MediaBackupImportException("Media backup archive entry set rejected: $detail")

    /** Una imagen no coincide con el manifiesto o con la central, o no se puede descomprimir. */
    class ImageIntegrityFailed(val imageKey: String, detail: String, cause: Throwable? = null) :
        MediaBackupImportException("Media backup image $imageKey failed verification: $detail", cause)

    /** La clave no existe en el manifiesto o la entrada ya no esta en el contenedor staged. */
    class ImageUnavailable(val imageKey: String) :
        MediaBackupImportException("Media backup image $imageKey is not available for transfer")

    /** El sumidero del llamante fallo durante una transferencia; lo escrito no es valido. */
    class TransferFailed(val imageKey: String, cause: Throwable?) :
        MediaBackupImportException("Media backup image $imageKey could not be transferred: ${cause?.message}", cause)

    /** La importacion ya se cerro y su temporal ya no es legible. */
    data object ArchiveClosed : MediaBackupImportException("Media backup import is closed")

    /** Operacion cancelada por el llamante o por interrupcion del hilo. */
    data object Canceled : MediaBackupImportException("Media backup archive read canceled")

    /** El fallo original ocurrio, pero el temporal propio **no** pudo eliminarse y se preserva. */
    class CleanupFailed(val fileName: String, cause: Throwable?) : MediaBackupImportException(
        "Media backup import failed and its temporary file '$fileName' could not be deleted",
        cause,
    )
}

/**
 * Presupuestos del lector, inyectables **solo** desde pruebas del propio modulo.
 *
 * Los valores por defecto son los del contrato; un valor inyectado solo puede acotar mas, porque el
 * manifiesto se sigue validando con su propio contrato y el codec con su tope.
 */
internal data class MediaBackupReaderLimits(
    val maxArchiveBytes: Long = MEDIA_BACKUP_MAX_ARCHIVE_BYTES,
    val maxManifestBytes: Long = MEDIA_BACKUP_MAX_MANIFEST_BYTES,
    val maxImages: Int = MEDIA_BACKUP_MAX_IMAGES,
    val maxImageBytes: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES,
    val maxTotalImageBytes: Long = MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES,
    val maxEntries: Int = MEDIA_BACKUP_MAX_IMAGES + 1,
) {
    init {
        require(maxArchiveBytes in 1..MEDIA_BACKUP_MAX_ARCHIVE_BYTES) {
            "maxArchiveBytes debe estar en 1..$MEDIA_BACKUP_MAX_ARCHIVE_BYTES."
        }
        require(maxManifestBytes in 1..MEDIA_BACKUP_MAX_MANIFEST_BYTES) {
            "maxManifestBytes debe estar en 1..$MEDIA_BACKUP_MAX_MANIFEST_BYTES."
        }
        require(maxImages in 1..MEDIA_BACKUP_MAX_IMAGES) { "maxImages debe estar en 1..$MEDIA_BACKUP_MAX_IMAGES." }
        require(maxImageBytes in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "maxImageBytes debe estar en 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES."
        }
        require(maxTotalImageBytes in maxImageBytes..MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES) {
            "maxTotalImageBytes debe estar en maxImageBytes..$MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES."
        }
        require(maxEntries in 1..(MEDIA_BACKUP_MAX_IMAGES + 1)) {
            "maxEntries debe estar en 1..${MEDIA_BACKUP_MAX_IMAGES + 1}."
        }
    }
}

/**
 * Puerto de ficheros del staging de importacion. Existe para inyectar fallos de creacion, salida,
 * `sync` y borrado en pruebas JVM; la implementacion de produccion es
 * [DefaultMediaBackupImportFilePort]. No decide rutas: recibe el staging ya validado.
 */
internal interface MediaBackupImportFilePort {
    fun createStagingFile(directory: File): File
    fun openOutput(file: File): MediaBackupImportFileOutput
    fun delete(file: File): Boolean
}

/** Salida fisica del temporal de importacion: flujo de bytes, `fsync` del descriptor y cierre. */
internal interface MediaBackupImportFileOutput {
    val output: OutputStream
    fun sync()
    fun close()
}

/** Implementacion real: `createTempFile` unico (`0600`), `FileOutputStream` y `FileDescriptor.sync()`. */
internal object DefaultMediaBackupImportFilePort : MediaBackupImportFilePort {

    override fun createStagingFile(directory: File): File =
        File.createTempFile(MediaBackupImportFiles.TEMPORARY_PREFIX, MediaBackupImportFiles.TEMPORARY_SUFFIX, directory)

    override fun openOutput(file: File): MediaBackupImportFileOutput {
        val stream = FileOutputStream(file)
        return object : MediaBackupImportFileOutput {
            override val output: OutputStream = stream

            override fun sync() {
                stream.fd.sync()
            }

            override fun close() {
                stream.close()
            }
        }
    }

    override fun delete(file: File): Boolean = file.delete()
}

/** Prefijo/sufijo propios del temporal de importacion; la limpieza solo toca nombres con esta forma. */
internal object MediaBackupImportFiles {
    const val TEMPORARY_PREFIX: String = "openjump-media-backup-import-"
    const val TEMPORARY_SUFFIX: String = ".zip.partial"
}

/**
 * Borra [file] solo si es exactamente un temporal propio dentro de [staging]: fichero regular (no
 * directorio), no enlace simbolico, con el prefijo/sufijo propios y cuyo padre canonico es el staging
 * canonico. Cualquier otra cosa se deja intacta y se devuelve `false`.
 */
private fun deleteOwnImport(file: File, staging: File, delete: (File) -> Boolean): Boolean {
    if (Files.isSymbolicLink(file.toPath())) return false
    if (!file.isFile) return false
    val name = file.name
    if (!name.startsWith(MediaBackupImportFiles.TEMPORARY_PREFIX) ||
        !name.endsWith(MediaBackupImportFiles.TEMPORARY_SUFFIX)
    ) {
        return false
    }
    val parent = file.parentFile ?: return false
    val canonicalParent = try {
        parent.canonicalFile
    } catch (failure: IOException) {
        return false
    }
    if (canonicalParent != staging) return false
    return delete(file)
}

private fun checkImportCanceled(cancellation: MediaBackupArchiveCancellation) {
    if (Thread.currentThread().isInterrupted || cancellation.isCanceled()) {
        throw MediaBackupImportException.Canceled
    }
}

// --- Parser ZIP estricto ------------------------------------------------------------------------

/** Entrada del directorio central ya validada contra su cabecera local. */
private class ZipLayoutEntry(
    val name: String,
    val flags: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

/** Estructura probada del contenedor: entradas canonicas y su prefijo local contiguo. */
private class ZipLayout(val entries: List<ZipLayoutEntry>)

/**
 * Comprueba la estructura completa del ZIP antes de descomprimir: EOCD exacto al final, sin ZIP64 ni
 * multidisco, central directory contigua al EOCD, entradas canonicas y cabeceras locales
 * contrastadas con la central (incluido el data descriptor). La ultima comprobacion es geometrica:
 * las regiones locales ordenadas deben cubrir `[0, central)` sin huecos. Nada de esto se apoya en
 * `ZipFile`: si un solo campo no se puede probar, se rechaza.
 */
private fun parseLayout(
    file: File,
    limits: MediaBackupReaderLimits,
    cancellation: MediaBackupArchiveCancellation,
): ZipLayout {
    try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < EOCD_BYTES) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive is too short to be a ZIP container",
                )
            }
            if (length > limits.maxArchiveBytes) {
                throw MediaBackupImportException.LimitExceeded(
                    "media backup archive is larger than ${limits.maxArchiveBytes} bytes",
                )
            }
            val eocdOffset = length - EOCD_BYTES
            val eocd = readRange(raf, eocdOffset, EOCD_BYTES.toInt())
            if (readU32(eocd, 0) != EOCD_SIGNATURE) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must end with an end of central directory record",
                )
            }
            val commentLength = readU16(eocd, 20)
            if (commentLength != 0) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must not carry an archive comment",
                )
            }
            if (length >= EOCD_BYTES + ZIP64_LOCATOR_BYTES &&
                readU32(readRange(raf, eocdOffset - ZIP64_LOCATOR_BYTES, 4), 0) == ZIP64_LOCATOR_SIGNATURE
            ) {
                throw MediaBackupImportException.StructureRejected("media backup archive must not use ZIP64")
            }
            val diskNumber = readU16(eocd, 4)
            val centralDisk = readU16(eocd, 6)
            val entriesOnDisk = readU16(eocd, 8)
            val totalEntries = readU16(eocd, 10)
            val centralSize = readU32(eocd, 12)
            val centralOffset = readU32(eocd, 16)
            if (entriesOnDisk == ZIP64_SENTINEL_16 || totalEntries == ZIP64_SENTINEL_16 ||
                centralSize == ZIP64_SENTINEL_32 || centralOffset == ZIP64_SENTINEL_32
            ) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must not use ZIP64 sentinel values",
                )
            }
            if (diskNumber != 0 || centralDisk != 0) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must be a single-disk archive",
                )
            }
            if (entriesOnDisk != totalEntries) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive declares a different entry count on disk than in total",
                )
            }
            if (totalEntries < 1) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must declare at least one entry",
                )
            }
            if (totalEntries > limits.maxEntries) {
                throw MediaBackupImportException.LimitExceeded(
                    "media backup archive declares $totalEntries entries, more than ${limits.maxEntries}",
                )
            }
            if (centralOffset <= 0L || centralSize <= 0L) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive must carry a central directory between its entries and its EOCD",
                )
            }
            if (checkedAdd(centralOffset, centralSize, "central directory") != eocdOffset) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive central directory must end exactly where the EOCD begins",
                )
            }
            val entries = parseCentralDirectory(raf, centralOffset, eocdOffset, totalEntries, cancellation)
            requireLocalHeadersCoverPayload(raf, entries, centralOffset, cancellation)
            return ZipLayout(entries)
        }
    } catch (failure: MediaBackupImportException) {
        throw failure
    } catch (failure: IOException) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive cannot be parsed: ${failure.message}",
            failure,
        )
    }
}

private fun parseCentralDirectory(
    raf: RandomAccessFile,
    centralOffset: Long,
    end: Long,
    totalEntries: Int,
    cancellation: MediaBackupArchiveCancellation,
): List<ZipLayoutEntry> {
    val entries = ArrayList<ZipLayoutEntry>(totalEntries)
    val names = HashSet<String>(totalEntries * 2)
    var position = centralOffset
    while (position < end) {
        checkImportCanceled(cancellation)
        if (entries.size >= totalEntries) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive central directory carries more entries than its EOCD declares",
            )
        }
        if (end - position < CENTRAL_HEADER_BYTES) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive central directory is truncated",
            )
        }
        val header = readRange(raf, position, CENTRAL_HEADER_BYTES)
        if (readU32(header, 0) != CENTRAL_SIGNATURE) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive central directory entry has an unexpected signature",
            )
        }
        val versionNeeded = readU16(header, 6)
        if (versionNeeded > MAX_SUPPORTED_VERSION) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry requires ZIP feature version $versionNeeded",
            )
        }
        val flags = readU16(header, 8)
        requireDeflatedFlags(flags)
        if (readU16(header, 10) != DEFLATED_METHOD) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must be DEFLATED",
            )
        }
        val crc = readU32(header, 16)
        val compressedSize = readU32(header, 20)
        val uncompressedSize = readU32(header, 24)
        val nameLength = readU16(header, 28)
        val extraLength = readU16(header, 30)
        val commentLength = readU16(header, 32)
        val diskStart = readU16(header, 34)
        val internalAttributes = readU16(header, 36)
        val externalAttributes = readU32(header, 38)
        val localOffset = readU32(header, 42)
        if (extraLength != 0) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must not carry extra fields",
            )
        }
        if (commentLength != 0) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must not carry comments",
            )
        }
        if (diskStart != 0) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must start on disk 0",
            )
        }
        if (internalAttributes != 0 || externalAttributes != 0L) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must not declare host attributes",
            )
        }
        if (nameLength < 1 || nameLength > MAX_ENTRY_NAME_BYTES) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry name has an unexpected length",
            )
        }
        val recordLength = CENTRAL_HEADER_BYTES.toLong() + nameLength + extraLength + commentLength
        if (checkedAdd(position, recordLength, "central directory entry") > end) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive central directory entry overruns the central directory",
            )
        }
        val name = decodeEntryName(readRange(raf, position + CENTRAL_HEADER_BYTES, nameLength))
        if (!names.add(name)) {
            throw MediaBackupImportException.StructureRejected("media backup archive declares duplicate entries")
        }
        if (name != MEDIA_BACKUP_MANIFEST_ENTRY && MediaBackupArchive.imageKeyOfEntry(name) == null) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry name is not a canonical manifest or image entry",
            )
        }
        if (uncompressedSize < 1L) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entries must not be empty",
            )
        }
        if (uncompressedSize > MEDIA_BACKUP_MAX_MANIFEST_BYTES) {
            throw MediaBackupImportException.LimitExceeded(
                "media backup archive entry declares $uncompressedSize bytes, more than any contract entry",
            )
        }
        if (localOffset >= centralOffset) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry local header must live before the central directory",
            )
        }
        entries += ZipLayoutEntry(name, flags, crc, compressedSize, uncompressedSize, localOffset)
        position += recordLength
    }
    if (position != end) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive central directory size does not match its entries",
        )
    }
    if (entries.size != totalEntries) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive declares $totalEntries entries but its central directory carries ${entries.size}",
        )
    }
    return entries
}

/**
 * Contrasta cada cabecera local con su entrada central: nombre, metodo, flags, CRC y tamanos (con
 * data descriptor cuando el productor no conocia los tamanos al escribir). Despues exige que las
 * regiones locales, ordenadas por offset, empiecen en 0, sean contiguas y terminen exactamente en la
 * central directory: eso prueba que no hay huecos, entradas solapadas ni cola oculta.
 */
private fun requireLocalHeadersCoverPayload(
    raf: RandomAccessFile,
    entries: List<ZipLayoutEntry>,
    centralOffset: Long,
    cancellation: MediaBackupArchiveCancellation,
) {
    val regions = ArrayList<LongArray>(entries.size)
    entries.forEach { entry ->
        checkImportCanceled(cancellation)
        val headerOffset = entry.localHeaderOffset
        if (headerOffset + LOCAL_HEADER_BYTES.toLong() > centralOffset) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header overruns the central directory",
            )
        }
        val header = readRange(raf, headerOffset, LOCAL_HEADER_BYTES)
        if (readU32(header, 0) != LOCAL_SIGNATURE) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header has an unexpected signature",
            )
        }
        if (readU16(header, 4) > MAX_SUPPORTED_VERSION) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header requires a newer ZIP feature version",
            )
        }
        if (readU16(header, 6) != entry.flags) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local and central headers disagree on flags",
            )
        }
        if (readU16(header, 8) != DEFLATED_METHOD) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header must declare DEFLATED",
            )
        }
        val localExtraLength = readU16(header, 28)
        val localNameLength = readU16(header, 26)
        if (localExtraLength != 0) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local headers must not carry extra fields",
            )
        }
        if (localNameLength < 1 || localNameLength > MAX_ENTRY_NAME_BYTES) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header name has an unexpected length",
            )
        }
        val headerEnd = checkedAdd(headerOffset, LOCAL_HEADER_BYTES.toLong() + localNameLength, "local header")
        if (headerEnd > centralOffset) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local header overruns the central directory",
            )
        }
        val localName = decodeEntryName(readRange(raf, headerOffset + LOCAL_HEADER_BYTES, localNameLength))
        if (localName != entry.name) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive local and central headers disagree on the entry name",
            )
        }
        val localCrc = readU32(header, 14)
        val localCompressed = readU32(header, 18)
        val localUncompressed = readU32(header, 22)
        val dataStart = headerEnd
        val dataEnd = checkedAdd(dataStart, entry.compressedSize, "local entry data")
        if (dataEnd > centralOffset) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry data overruns the central directory",
            )
        }
        val hasDataDescriptor = (entry.flags and DATA_DESCRIPTOR_FLAG) != 0
        var regionEnd = dataEnd
        if (hasDataDescriptor) {
            if (localCrc != 0L || localCompressed != 0L || localUncompressed != 0L) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive local header must zero CRC and sizes when it uses a data descriptor",
                )
            }
            regionEnd = verifyDataDescriptor(raf, dataEnd, centralOffset, entry)
        } else {
            if (localCrc != entry.crc32 || localCompressed != entry.compressedSize ||
                localUncompressed != entry.uncompressedSize
            ) {
                throw MediaBackupImportException.StructureRejected(
                    "media backup archive local and central headers disagree on CRC or sizes",
                )
            }
        }
        regions += longArrayOf(headerOffset, regionEnd)
    }
    regions.sortBy { it[0] }
    var expectedStart = 0L
    regions.forEach { region ->
        if (region[0] != expectedStart) {
            throw MediaBackupImportException.StructureRejected(
                "media backup archive entry regions must be contiguous and start at offset 0",
            )
        }
        expectedStart = region[1]
    }
    if (expectedStart != centralOffset) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive payload must end exactly where the central directory begins",
        )
    }
}

/** Lee el data descriptor (con o sin firma) y exige que coincida con la entrada central. */
private fun verifyDataDescriptor(
    raf: RandomAccessFile,
    at: Long,
    limit: Long,
    entry: ZipLayoutEntry,
): Long {
    if (limit - at < 4) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive entry is missing its data descriptor",
        )
    }
    val first = readU32(readRange(raf, at, 4), 0)
    val hasSignature = first == DATA_DESCRIPTOR_SIGNATURE
    val descriptorSize = if (hasSignature) DATA_DESCRIPTOR_SIGNED_BYTES else DATA_DESCRIPTOR_BYTES
    if (limit - at < descriptorSize) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive data descriptor is truncated",
        )
    }
    val bytes = readRange(raf, at, descriptorSize.toInt())
    val offset = if (hasSignature) 4 else 0
    if (readU32(bytes, offset) != entry.crc32 ||
        readU32(bytes, offset + 4) != entry.compressedSize ||
        readU32(bytes, offset + 8) != entry.uncompressedSize
    ) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive data descriptor disagrees with the central directory",
        )
    }
    return at + descriptorSize
}

/** Flags permitidos: bit 3 (data descriptor) y bit 11 (nombres UTF-8). Nada de cifrado ni parches. */
private fun requireDeflatedFlags(flags: Int) {
    if ((flags and PERMITTED_FLAGS) != flags) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive entry declares unsupported or encrypted flags",
        )
    }
}

private fun decodeEntryName(bytes: ByteArray): String {
    if (bytes.isEmpty()) {
        throw MediaBackupImportException.StructureRejected("media backup archive entry name is empty")
    }
    if (bytes.any { (it.toInt() and 0xFF) < 0x20 || (it.toInt() and 0xFF) > 0x7E }) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive entry name must be printable ASCII",
        )
    }
    return String(bytes, Charsets.US_ASCII)
}

private fun readRange(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
    if (size < 0) {
        throw MediaBackupImportException.StructureRejected("media backup archive read length is invalid")
    }
    val bytes = ByteArray(size)
    try {
        raf.seek(offset)
        raf.readFully(bytes)
    } catch (failure: IOException) {
        throw MediaBackupImportException.StructureRejected(
            "media backup archive is truncated at offset $offset",
            failure,
        )
    }
    return bytes
}

private fun readU16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun readU32(bytes: ByteArray, at: Int): Long =
    (bytes[at].toLong() and 0xFF) or
        ((bytes[at + 1].toLong() and 0xFF) shl 8) or
        ((bytes[at + 2].toLong() and 0xFF) shl 16) or
        ((bytes[at + 3].toLong() and 0xFF) shl 24)

private fun checkedAdd(left: Long, right: Long, detail: String): Long = try {
    Math.addExact(left, right)
} catch (overflow: ArithmeticException) {
    throw MediaBackupImportException.StructureRejected("media backup archive $detail length overflows", overflow)
}

/** SHA-256 hexadecimal en minuscula (misma forma que el manifiesto declara). */
private fun importToLowerHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef".toCharArray()
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(digits[value ushr 4])
            append(digits[value and 0x0F])
        }
    }
}

private const val EOCD_SIGNATURE = 0x06054B50L
private const val EOCD_BYTES = 22L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
private const val ZIP64_LOCATOR_BYTES = 20L
private const val ZIP64_SENTINEL_16 = 0xFFFF
private const val ZIP64_SENTINEL_32 = 0xFFFFFFFFL
private const val CENTRAL_SIGNATURE = 0x02014B50L
private const val CENTRAL_HEADER_BYTES = 46
private const val LOCAL_SIGNATURE = 0x04034B50L
private const val LOCAL_HEADER_BYTES = 30
private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074B50L
private const val DATA_DESCRIPTOR_BYTES = 12L
private const val DATA_DESCRIPTOR_SIGNED_BYTES = 16L
private const val DATA_DESCRIPTOR_FLAG = 0x0008
private const val PERMITTED_FLAGS = 0x0008 or 0x0800
private const val DEFLATED_METHOD = 8
private const val MAX_SUPPORTED_VERSION = 20
private const val MAX_ENTRY_NAME_BYTES = 255
