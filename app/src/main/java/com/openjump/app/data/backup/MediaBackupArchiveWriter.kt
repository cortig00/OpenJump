package com.openjump.app.data.backup

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Escritor del contenedor ZIP del backup con imagenes (slice G2a).
 *
 * Contrato (todo se comprueba; nada se confia al origen):
 *  1. **Validacion antes de la E/S**: el manifiesto se valida con [MediaBackupManifest.archivePlan]
 *     (que ya reaplica el contrato completo, incluido el documento legacy) y se serializa con
 *     [MediaBackupManifestCodec] antes de crear un solo byte de contenedor. Un manifiesto invalido
 *     no llega a crear fichero temporal.
 *  2. **Temporal propio y unico**: el ZIP se escribe en un fichero nuevo dentro de un directorio de
 *     staging **explicito** del llamante ([File.createTempFile], prefijo y sufijo propios). No hay
 *     API para escribir a un destino externo (SAF, `content://`, ruta de usuario): este slice no
 *     publica ni promete atomicidad fuera del staging.
 *  3. **Entradas canonicas y exactas**: `manifest.json` y las imagenes derivadas
 *     `images/<hex32>.png` del plan, ni una mas ni una menos, sin entradas de directorio, sin
 *     duplicados, sin campos extra ni comentarios. Los nombres no pueden venir del usuario: se
 *     derivan de la clave opaca ([MediaBackupArchive.imageEntryName]) y el manifiesto ya valido que
 *     sea canonica.
 *  4. **Streaming verificado**: cada imagen se abre por **clave opaca** a traves de
 *     [MediaBackupImageSource] (el escritor nunca recibe una ruta ni una URI) y se copia con un
 *     buffer pequeno calculando tamano exacto, SHA-256 y CRC-32 **mientras** se escribe. No se
 *     confia en `declaredLength` ni en `available()`: si el flujo entrega menos bytes, mas bytes o
 *     un digest distinto, se aborta. Nunca se cargan todas las imagenes en memoria.
 *  5. **Cierre y verificacion**: `finish()` + `sync()` del descriptor, y despues se **reabre** el
 *     fichero con [ZipFile] para comprobar el directorio central real (orden, nombres, metodo,
 *     tamano y CRC de cada entrada, y que cada nombre de imagen resuelva a la clave que el
 *     manifiesto declara), para releer `manifest.json` con su SHA-256 y para **releer cada imagen
 *     descomprimida** por bloques: tamano exacto, SHA-256 del manifiesto y CRC-32 contra el
 *     directorio central y contra el observado al escribir. El directorio central por si solo no
 *     basta como evidencia: unos bytes comprimidos danados entre el `sync` y la reapertura dejan
 *     intactos el tamano y el CRC centrales, y el contenedor se entregaria corrupto. Solo entonces
 *     se entrega un [MediaBackupPreparedArchive], que es el **dueno** del temporal. El coste es
 *     explicito y buscado: la verificacion vuelve a descomprimir cada imagen (una lectura extra
 *     acotada por el propio tamano declarado, nunca una copia en memoria).
 *  6. **Fallo = nada valido**: cualquier ausencia, truncado, exceso, digest, cancelacion, fallo de
 *     salida, `disk full`, fallo de `sync` o de cierre aborta la operacion y elimina **solo** el
 *     temporal creado por esta llamada (nunca `deleteRecursively`, nunca otros ficheros ni
 *     directorios). Si la limpieza falla se informa con [MediaBackupArchiveException.CleanupFailed]
 *     y se preserva el fichero para diagnostico.
 *
 * Fuera de alcance de este slice: escritura al destino SAF, atomicidad externa, UI/Room/restore,
 * lector ZIP, y validacion de contenido PNG o de dimensiones. El escritor sella bytes opacos: que
 * sean un PNG valido y con las dimensiones declaradas es responsabilidad explicita del productor y
 * del lector (test Android).
 */
class MediaBackupArchiveWriter internal constructor(
    private val stagingDirectory: File,
    private val filePort: MediaBackupArchiveFilePort,
    private val limits: MediaBackupArchiveLimits,
) {

    /** Escritor de produccion: staging explicito del llamante y puerto de ficheros real. */
    constructor(stagingDirectory: File) : this(
        stagingDirectory = stagingDirectory,
        filePort = DefaultMediaBackupArchiveFilePort,
        limits = MediaBackupArchiveLimits(),
    )

    /**
     * Construye, escribe y verifica el contenedor del [manifest] con las imagenes de [images].
     *
     * Devuelve un [MediaBackupPreparedArchive] cuyo fichero existe, esta cerrado y sincronizado y
     * ha pasado la verificacion del directorio central. Si algo falla, no se devuelve nada valido y
     * el temporal propio se elimina (o se informa de que no pudo eliminarse).
     *
     * [cancellation] se consulta antes de crear el fichero, entre imagenes, durante cada bloque
     * copiado y durante cada bloque releido en la verificacion; una cancelacion aborta con
     * [MediaBackupArchiveException.Canceled]. El hilo interrumpido tambien cuenta como cancelacion.
     */
    fun write(
        manifest: MediaBackupManifest,
        images: MediaBackupImageSource,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupPreparedArchive {
        // 1. Todo lo que puede rechazarse sin tocar disco, antes de tocar disco.
        val plan = planFor(manifest)
        checkCanceled(cancellation)
        val staging = stagingDirectoryOrThrow()
        val manifestBytes = encodeManifest(manifest)
        checkCanceled(cancellation)
        // 2. Temporal propio y unico. A partir de aqui todo fallo debe limpiar este fichero.
        val temporary = createTemporary(staging)
        try {
            val expectedCrc = writeArchive(temporary, plan, manifestBytes, images, cancellation)
            checkCanceled(cancellation)
            verifyArchive(temporary, plan, manifestBytes, expectedCrc, cancellation)
            checkCanceled(cancellation)
            return MediaBackupPreparedArchive(
                file = temporary,
                manifestEntryName = plan.manifestEntryName,
                imageEntryNames = plan.imageEntries.map { it.entryName },
                manifestByteCount = manifestBytes.size.toLong(),
                totalImageByteCount = plan.totalImageByteCount,
                archiveByteCount = temporary.length(),
                manifestSha256 = sha256Hex(manifestBytes),
                stagingDirectory = staging,
                deleteFile = filePort::delete,
            )
        } catch (failure: Throwable) {
            failAndCleanUp(temporary, staging, failure)
        }
    }

    // --- Validacion pura -----------------------------------------------------------------------

    /**
     * Plan canonico del manifiesto, reaplicando los topes de este escritor (que no pueden relajar
     * los del contrato: solo acotar mas).
     */
    private fun planFor(manifest: MediaBackupManifest): MediaBackupArchivePlan {
        val plan = try {
            manifest.archivePlan()
        } catch (failure: Exception) {
            throw MediaBackupArchiveException.ManifestRejected(failure)
        }
        if (plan.imageCount > limits.maxImages) {
            throw MediaBackupArchiveException.LimitExceeded(
                "media backup declares more than ${limits.maxImages} images",
            )
        }
        val entries = plan.imageEntries
        if (entries.map { it.entryName }.distinct().size != entries.size) {
            throw MediaBackupArchiveException.ManifestRejected(
                IllegalArgumentException("media backup image entry names must be unique"),
            )
        }
        if (entries.map { it.imageKey }.distinct().size != entries.size) {
            throw MediaBackupArchiveException.ManifestRejected(
                IllegalArgumentException("media backup image keys must be unique"),
            )
        }
        var total = 0L
        entries.forEach { entry ->
            if (entry.byteCount < 1L || entry.byteCount > limits.maxImageBytes) {
                throw MediaBackupArchiveException.LimitExceeded(
                    "media backup image ${entry.imageKey} byteCount must be in 1..${limits.maxImageBytes}",
                )
            }
            total = try {
                Math.addExact(total, entry.byteCount)
            } catch (overflow: ArithmeticException) {
                throw MediaBackupArchiveException.LimitExceeded("media backup image bytes overflow")
            }
            if (total > limits.maxTotalImageBytes) {
                throw MediaBackupArchiveException.LimitExceeded(
                    "media backup declares more than ${limits.maxTotalImageBytes} image bytes",
                )
            }
        }
        return plan
    }

    /** Staging explicito: existe, es directorio real (no enlace) y se usa canonizado. */
    private fun stagingDirectoryOrThrow(): File {
        if (Files.isSymbolicLink(stagingDirectory.toPath())) {
            throw MediaBackupArchiveException.StagingDirectoryRejected(
                "media backup staging directory must not be a symbolic link",
            )
        }
        if (!stagingDirectory.isDirectory) {
            throw MediaBackupArchiveException.StagingDirectoryRejected(
                "media backup staging directory does not exist or is not a directory",
            )
        }
        return try {
            stagingDirectory.canonicalFile
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.StagingDirectoryRejected(
                "media backup staging directory cannot be resolved: ${failure.message}",
            )
        }
    }

    /**
     * Manifiesto serializado y acotado. El codec ya valida y corta en
     * [MEDIA_BACKUP_MAX_MANIFEST_BYTES]; este escritor comprueba ademas su propio presupuesto
     * inyectable (menor o igual), de modo que un limite de prueba nunca puede ampliar el contrato.
     */
    private fun encodeManifest(manifest: MediaBackupManifest): ByteArray {
        val bytes = try {
            MediaBackupManifestCodec.encode(manifest)
        } catch (failure: Exception) {
            throw MediaBackupArchiveException.ManifestRejected(failure)
        }
        if (bytes.size.toLong() > limits.maxManifestBytes) {
            throw MediaBackupArchiveException.LimitExceeded(
                "media backup manifest is larger than ${limits.maxManifestBytes} bytes",
            )
        }
        return bytes
    }

    // --- E/S ------------------------------------------------------------------------------------

    private fun createTemporary(staging: File): File {
        return try {
            filePort.createStagingFile(staging)
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.STAGING, failure)
        }
    }

    /**
     * Escribe el ZIP completo y devuelve el CRC-32 observado de cada imagen (calculado sobre los
     * bytes realmente copiados, no sobre los declarados), que la verificacion del directorio central
     * volvera a comparar.
     */
    private fun writeArchive(
        file: File,
        plan: MediaBackupArchivePlan,
        manifestBytes: ByteArray,
        images: MediaBackupImageSource,
        cancellation: MediaBackupArchiveCancellation,
    ): Map<String, Long> {
        val fileOutput = try {
            filePort.openOutput(file)
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.STAGING, failure)
        }
        // El cierre del ZipOutputStream se delega en el puerto: asi el puerto ve el cierre fisico
        // (y una prueba puede inyectar un cierre que deje el fichero inservible).
        val zip = ZipOutputStream(PortBackedOutputStream(fileOutput))
        var completed = false
        try {
            writeManifestEntry(zip, plan, manifestBytes)
            val expectedCrc = HashMap<String, Long>(plan.imageCount)
            plan.imageEntries.forEach { planned ->
                checkCanceled(cancellation)
                expectedCrc[planned.imageKey] = writeImageEntry(zip, planned, images, cancellation)
            }
            zip.finish()
            zip.flush()
            // fsync del descriptor antes de cerrar: el contenido y el directorio central llegan al
            // almacenamiento, no solo al page cache. Un fallo aqui aborta el backup.
            try {
                fileOutput.sync()
            } catch (failure: IOException) {
                throw MediaBackupArchiveException.SyncFailed(failure)
            }
            zip.close()
            completed = true
            return expectedCrc
        } catch (failure: MediaBackupArchiveException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.FINALIZE, failure)
        } finally {
            if (!completed) {
                // El fallo primario manda: cerrar a medias no debe sustituirlo, pero un cierre
                // incompleto no puede dejar el descriptor abierto.
                runCatching { zip.close() }
                runCatching { fileOutput.close() }
            }
        }
    }

    private fun writeManifestEntry(
        zip: ZipOutputStream,
        plan: MediaBackupArchivePlan,
        manifestBytes: ByteArray,
    ) {
        val entry = ZipEntry(plan.manifestEntryName)
        val crc = CRC32()
        crc.update(manifestBytes)
        try {
            zip.putNextEntry(entry)
            zip.write(manifestBytes)
            zip.closeEntry()
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.MANIFEST, failure)
        }
        if (entry.size != manifestBytes.size.toLong() || entry.crc != crc.value) {
            throw MediaBackupArchiveException.WriteFailed(
                MediaBackupArchiveStage.MANIFEST,
                IOException("manifest entry does not match the bytes written"),
            )
        }
    }

    /**
     * Copia **una** imagen con verificacion en linea. Se aborta en cuanto el flujo entrega mas
     * bytes que los declarados (no se consume el resto) y al llegar al final se exige el tamano
     * exacto y el SHA-256 del manifiesto. El SHA-256 se calcula sobre lo escrito, asi que un origen
     * torcido o una clave que devuelve otra imagen no pueden colarse.
     */
    private fun writeImageEntry(
        zip: ZipOutputStream,
        planned: MediaBackupImageEntry,
        images: MediaBackupImageSource,
        cancellation: MediaBackupArchiveCancellation,
    ): Long {
        val input: InputStream = try {
            images.open(planned.imageKey)
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.IMAGE, failure)
        } ?: throw MediaBackupArchiveException.ImageMissing(planned.imageKey)
        val digest = MessageDigest.getInstance(SHA256)
        val crc = CRC32()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var observed = 0L
        return try {
            input.use { stream ->
                val entry = ZipEntry(planned.entryName)
                try {
                    zip.putNextEntry(entry)
                    while (true) {
                        checkCanceled(cancellation)
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            // Flujo que no progresa: se fuerza un byte para no girar en vacio.
                            val single = stream.read()
                            if (single < 0) break
                            buffer[0] = single.toByte()
                            observed = observe(planned, observed, 1L)
                            zip.write(buffer, 0, 1)
                            digest.update(buffer, 0, 1)
                            crc.update(buffer, 0, 1)
                            continue
                        }
                        observed = observe(planned, observed, count.toLong())
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                    }
                    zip.closeEntry()
                } catch (failure: MediaBackupArchiveException) {
                    throw failure
                } catch (failure: IOException) {
                    throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.IMAGE, failure)
                }
                if (observed != planned.byteCount) {
                    throw MediaBackupArchiveException.ImageSizeMismatch(
                        imageKey = planned.imageKey,
                        expectedByteCount = planned.byteCount,
                        observedByteCount = observed,
                    )
                }
                if (toLowerHex(digest.digest()) != planned.sha256) {
                    throw MediaBackupArchiveException.ImageDigestMismatch(planned.imageKey)
                }
                // El propio ZipOutputStream acaba de fijar tamano y CRC: si no coinciden con lo
                // copiado, el flujo ZIP no representa la imagen verificada.
                if (entry.size != observed || entry.crc != crc.value) {
                    throw MediaBackupArchiveException.WriteFailed(
                        MediaBackupArchiveStage.IMAGE,
                        IOException("image entry does not match the bytes written"),
                    )
                }
                crc.value
            }
        } catch (failure: MediaBackupArchiveException) {
            throw failure
        } catch (failure: IOException) {
            // Incluye un fallo al cerrar el flujo del origen: el backup no se entrega.
            throw MediaBackupArchiveException.WriteFailed(MediaBackupArchiveStage.IMAGE, failure)
        }
    }

    /** Suma los bytes observados con aritmetica comprobada y aborta al primer exceso. */
    private fun observe(planned: MediaBackupImageEntry, observed: Long, count: Long): Long {
        val total = try {
            Math.addExact(observed, count)
        } catch (overflow: ArithmeticException) {
            throw MediaBackupArchiveException.ImageSizeMismatch(
                imageKey = planned.imageKey,
                expectedByteCount = planned.byteCount,
                observedByteCount = Long.MAX_VALUE,
            )
        }
        if (total > planned.byteCount) {
            throw MediaBackupArchiveException.ImageSizeMismatch(
                imageKey = planned.imageKey,
                expectedByteCount = planned.byteCount,
                observedByteCount = total,
            )
        }
        return total
    }

    // --- Verificacion del contenedor ------------------------------------------------------------

    /**
     * Reabre el fichero con [ZipFile] y comprueba el directorio central **real**: orden y conjunto
     * exacto de entradas, que no haya directorios ni campos extra ni comentarios, metodo identico
     * ([ZipEntry.DEFLATED]), tamano y CRC de cada imagen contra lo observado al copiar, la
     * resolucion nombre -> clave canonica contra el plan, y el contenido de `manifest.json` por
     * SHA-256 y longitud.
     *
     * Ademas **relee cada imagen descomprimida** ([verifyImageEntry]): el directorio central no es
     * prueba de los bytes comprimidos, porque un dano entre el `sync` y esta reapertura deja el
     * tamano y el CRC centrales intactos. La relectura es streaming y acotada (buffer de
     * [COPY_BUFFER_BYTES]), con cancelacion entre bloques, y compara tamano exacto, SHA-256 del
     * manifiesto y CRC-32 contra el central y contra el observado al escribir.
     */
    private fun verifyArchive(
        file: File,
        plan: MediaBackupArchivePlan,
        manifestBytes: ByteArray,
        expectedCrc: Map<String, Long>,
        cancellation: MediaBackupArchiveCancellation,
    ) {
        if (!file.isFile || file.length() <= 0L) {
            throw MediaBackupArchiveException.VerificationFailed("media backup archive file is empty")
        }
        val plannedByKey = plan.imageEntries.associateBy { it.imageKey }
        val expectedNames = buildList {
            add(plan.manifestEntryName)
            plan.imageEntries.forEach { add(it.entryName) }
        }
        val manifestSha = sha256Hex(manifestBytes)
        val observedNames = ArrayList<String>(expectedNames.size)
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCanceled(cancellation)
                    val entry = entries.nextElement()
                    if (observedNames.size >= expectedNames.size) {
                        throw MediaBackupArchiveException.VerificationFailed(
                            "media backup archive has more entries than the manifest declares",
                        )
                    }
                    observedNames.add(entry.name)
                    requireCanonicalEntry(entry)
                    if (entry.name == plan.manifestEntryName) {
                        verifyManifestEntry(zip, entry, manifestBytes.size.toLong(), manifestSha, cancellation)
                    } else {
                        verifyImageEntry(
                            zip = zip,
                            entry = entry,
                            planned = plannedByKey[MediaBackupArchive.imageKeyOfEntry(entry.name)],
                            expectedCrc = expectedCrc,
                            cancellation = cancellation,
                        )
                    }
                }
            }
        } catch (failure: MediaBackupArchiveException) {
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive cannot be reopened: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive cannot be reopened: ${failure.message}",
                failure,
            )
        }
        if (observedNames != expectedNames) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive entries do not match the manifest exactly",
            )
        }
    }

    private fun requireCanonicalEntry(entry: ZipEntry) {
        if (entry.isDirectory) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive must not contain directory entries",
            )
        }
        if (entry.extra != null) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive entry '${entry.name}' must not carry extra fields",
            )
        }
        if (entry.comment != null) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive entry '${entry.name}' must not carry a comment",
            )
        }
        if (entry.method != ZipEntry.DEFLATED) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive entry '${entry.name}' has an unexpected compression method",
            )
        }
    }

    /**
     * Comprueba la entrada de imagen del directorio central **y relee sus bytes descomprimidos**.
     *
     * Primero exige que el nombre resuelva a una imagen del plan, que el tamano central sea el
     * declarado y que el CRC central sea el observado al copiar. Despues abre el flujo del [ZipFile]
     * y lo consume por bloques de [COPY_BUFFER_BYTES] (nunca la imagen entera en memoria) calculando
     * size, SHA-256 y CRC-32 sobre lo realmente releido: un danno en los bytes comprimidos que deje
     * el directorio central intacto se detecta aqui como `ZipException`/`IOException`, como tamano
     * distinto o como digest/CRC distinto, y en todos los casos el contenedor se rechaza. La
     * cancelacion se consulta entre bloques.
     */
    private fun verifyImageEntry(
        zip: ZipFile,
        entry: ZipEntry,
        planned: MediaBackupImageEntry?,
        expectedCrc: Map<String, Long>,
        cancellation: MediaBackupArchiveCancellation,
    ) {
        if (planned == null) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive entry '${entry.name}' is not a canonical image of the manifest",
            )
        }
        if (entry.size != planned.byteCount) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} has ${entry.size} bytes, expected ${planned.byteCount}",
            )
        }
        val writtenCrc = expectedCrc[planned.imageKey]
            ?: throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} was not written by this operation",
            )
        if (entry.crc != writtenCrc) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} has an unexpected CRC-32",
            )
        }
        val digest = MessageDigest.getInstance(SHA256)
        val crc = CRC32()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var observed = 0L
        try {
            zip.getInputStream(entry).use { input ->
                while (true) {
                    checkCanceled(cancellation)
                    val count = input.read(buffer)
                    if (count < 0) break
                    observed = try {
                        Math.addExact(observed, count.toLong())
                    } catch (overflow: ArithmeticException) {
                        throw MediaBackupArchiveException.VerificationFailed(
                            "media backup archive image ${planned.imageKey} is longer than declared",
                        )
                    }
                    if (observed > planned.byteCount) {
                        // Acotado: no se consume el resto de una entrada que ya no puede ser valida.
                        throw MediaBackupArchiveException.VerificationFailed(
                            "media backup archive image ${planned.imageKey} is longer than declared",
                        )
                    }
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
            }
        } catch (failure: MediaBackupArchiveException) {
            throw failure
        } catch (failure: IOException) {
            // ZipException (CRC o flujo DEFLATE invalido) es un IOException: se informa con la clave.
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} cannot be read back: ${failure.message}",
                failure,
            )
        } catch (failure: RuntimeException) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} cannot be read back: ${failure.message}",
                failure,
            )
        }
        if (observed != planned.byteCount) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} has $observed bytes on re-read, expected ${planned.byteCount}",
            )
        }
        if (toLowerHex(digest.digest()) != planned.sha256) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} does not match its declared SHA-256 on re-read",
            )
        }
        if (crc.value != entry.crc || crc.value != writtenCrc) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive image ${planned.imageKey} has an unexpected CRC-32 on re-read",
            )
        }
    }

    /** Relee `manifest.json` completo (acotado) y compara longitud y SHA-256 con lo escrito. */
    private fun verifyManifestEntry(
        zip: ZipFile,
        entry: ZipEntry,
        expectedBytes: Long,
        expectedSha: String,
        cancellation: MediaBackupArchiveCancellation,
    ) {
        if (entry.size != expectedBytes) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive manifest has ${entry.size} bytes, expected $expectedBytes",
            )
        }
        if (entry.size > MEDIA_BACKUP_MAX_MANIFEST_BYTES) {
            throw MediaBackupArchiveException.VerificationFailed("media backup archive manifest is too large")
        }
        val digest = MessageDigest.getInstance(SHA256)
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        try {
            zip.getInputStream(entry).use { stream ->
                while (true) {
                    checkCanceled(cancellation)
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total = try {
                        Math.addExact(total, count.toLong())
                    } catch (overflow: ArithmeticException) {
                        throw MediaBackupArchiveException.VerificationFailed(
                            "media backup archive manifest size overflow",
                        )
                    }
                    if (total > expectedBytes) {
                        throw MediaBackupArchiveException.VerificationFailed(
                            "media backup archive manifest is longer than declared",
                        )
                    }
                    digest.update(buffer, 0, count)
                }
            }
        } catch (failure: MediaBackupArchiveException) {
            // Cancelacion o verificacion fallida: se propagan tal cual, no como "no se pudo leer".
            throw failure
        } catch (failure: IOException) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive manifest cannot be read back: ${failure.message}",
                failure,
            )
        }
        if (total != expectedBytes || toLowerHex(digest.digest()) != expectedSha) {
            throw MediaBackupArchiveException.VerificationFailed(
                "media backup archive manifest does not match the bytes written",
            )
        }
    }

    // --- Limpieza y cancelacion -----------------------------------------------------------------

    /**
     * Limpieza tras un fallo: elimina **solo** el temporal que esta operacion acaba de crear. Si no
     * puede (o el fichero ya no es el temporal propio), se informa con
     * [MediaBackupArchiveException.CleanupFailed] y se preserva: nunca se borra un directorio ni un
     * fichero ajeno.
     */
    private fun failAndCleanUp(temporary: File, staging: File, failure: Throwable): Nothing {
        val deleted = try {
            deleteOwnTemporary(temporary, staging, filePort::delete)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
            false
        }
        if (!deleted && temporary.exists()) {
            throw MediaBackupArchiveException.CleanupFailed(temporary.name, failure)
        }
        throw failure
    }

    private fun checkCanceled(cancellation: MediaBackupArchiveCancellation) {
        if (Thread.currentThread().isInterrupted || cancellation.isCanceled()) {
            throw MediaBackupArchiveException.Canceled
        }
    }

    companion object {
        private const val SHA256 = "SHA-256"

        /** Buffer pequeno: ni la imagen ni el contenedor se materializan en memoria. */
        internal const val COPY_BUFFER_BYTES: Int = 64 * 1024
    }
}

/**
 * Flujo de bytes del ZIP que delega el cierre en el puerto. Sin esto, `ZipOutputStream.close()`
 * cerraria el descriptor a espaldas del puerto y una prueba no podria observar el cierre fisico.
 */
private class PortBackedOutputStream(
    private val target: MediaBackupArchiveFileOutput,
) : OutputStream() {

    override fun write(value: Int) = target.output.write(value)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = target.output.write(buffer, offset, length)

    override fun flush() = target.output.flush()

    override fun close() = target.close()
}

/** SHA-256 hexadecimal en minuscula (misma forma que el manifiesto declara). */
private fun sha256Hex(bytes: ByteArray): String =
    toLowerHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Hexadecimal en minuscula de un digest ya calculado. */
private fun toLowerHex(bytes: ByteArray): String {
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Prefijo/sufijo propios del temporal; la limpieza solo toca nombres con esta forma. */
internal object MediaBackupArchiveFiles {
    const val TEMPORARY_PREFIX: String = "openjump-media-backup-"
    const val TEMPORARY_SUFFIX: String = ".zip.tmp"
}

/**
 * Borra [file] solo si es exactamente un temporal propio dentro de [staging]: fichero regular (no
 * directorio), no enlace simbolico, con el prefijo/sufijo propios y cuyo padre canonico es el
 * staging canonico. Cualquier otra cosa se deja intacta y se devuelve `false`.
 */
private fun deleteOwnTemporary(file: File, staging: File, delete: (File) -> Boolean): Boolean {
    if (Files.isSymbolicLink(file.toPath())) return false
    if (!file.isFile) return false
    val name = file.name
    if (!name.startsWith(MediaBackupArchiveFiles.TEMPORARY_PREFIX) ||
        !name.endsWith(MediaBackupArchiveFiles.TEMPORARY_SUFFIX)
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

/**
 * Origen de imagenes por **clave opaca**. El escritor nunca recibe una ruta, una URI ni un nombre
 * de fichero: solo la clave que el manifiesto declara, y el puerto decide como abrirla (almacen
 * privado, fake de prueba...). Devolver `null` significa "esa imagen no existe" y aborta el backup;
 * lanzar [IOException] se reporta como fallo de escritura.
 */
fun interface MediaBackupImageSource {
    fun open(imageKey: String): InputStream?
}

/** Senal de cancelacion cooperativa; se consulta antes de cada E/S y entre bloques copiados. */
fun interface MediaBackupArchiveCancellation {
    fun isCanceled(): Boolean

    companion object {
        /** Sin cancelacion (uso de produccion por defecto). */
        val NEVER: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation { false }
    }
}

/** Etapa de E/S en la que se produjo un fallo; sirve de diagnostico, no de recuperacion. */
enum class MediaBackupArchiveStage { STAGING, MANIFEST, IMAGE, FINALIZE }

/**
 * Fallos del escritor. Todos los casos implican que **no** se entrega un backup valido y que el
 * temporal propio se ha eliminado, salvo [CleanupFailed], que indica explicitamente que el fichero
 * se conserva porque no pudo borrarse.
 */
sealed class MediaBackupArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** El manifiesto no cumple el contrato; se rechaza antes de cualquier E/S. */
    class ManifestRejected(cause: Throwable) :
        MediaBackupArchiveException("Media backup manifest rejected: ${cause.message}", cause)

    /** El directorio de staging explicito no es utilizable (no existe, no es directorio, enlace...). */
    class StagingDirectoryRejected(detail: String) : MediaBackupArchiveException(detail)

    /** El plan supera un presupuesto del escritor (entradas, bytes por imagen, total o manifiesto). */
    class LimitExceeded(detail: String) : MediaBackupArchiveException(detail)

    /** La imagen declarada no existe en el origen. */
    class ImageMissing(val imageKey: String) :
        MediaBackupArchiveException("Media backup image $imageKey is missing")

    /** El flujo entrego menos o mas bytes que los declarados; se aborta sin consumir el resto. */
    class ImageSizeMismatch(
        val imageKey: String,
        val expectedByteCount: Long,
        val observedByteCount: Long,
    ) : MediaBackupArchiveException(
        "Media backup image $imageKey has $observedByteCount bytes, expected $expectedByteCount",
    )

    /** Los bytes no son los que el manifiesto declara (SHA-256 distinto). */
    class ImageDigestMismatch(val imageKey: String) :
        MediaBackupArchiveException("Media backup image $imageKey does not match its declared SHA-256")

    /** Fallo de salida, cierre o E/S del origen en [stage]. */
    class WriteFailed(val stage: MediaBackupArchiveStage, cause: Throwable?) :
        MediaBackupArchiveException("Media backup archive write failed at $stage: ${cause?.message}", cause)

    /** El descriptor no pudo sincronizarse; el fichero no se entrega. */
    class SyncFailed(cause: Throwable?) :
        MediaBackupArchiveException("Media backup archive could not be synced: ${cause?.message}", cause)

    /** El ZIP reabierto no coincide con lo escrito: directorio central, entradas o manifiesto. */
    class VerificationFailed(detail: String, cause: Throwable? = null) :
        MediaBackupArchiveException("Media backup archive verification failed: $detail", cause)

    /** Operacion cancelada por el llamante o por interrupcion del hilo. */
    data object Canceled : MediaBackupArchiveException("Media backup archive write canceled")

    /** El fallo original ocurrio, pero el temporal propio **no** pudo eliminarse y se preserva. */
    class CleanupFailed(val fileName: String, cause: Throwable?) : MediaBackupArchiveException(
        "Media backup archive failed and its temporary file '$fileName' could not be deleted",
        cause,
    )
}

/**
 * Presupuestos del escritor, inyectables **solo** desde pruebas del propio modulo.
 *
 * Los valores por defecto son los del contrato ([MEDIA_BACKUP_MAX_IMAGES],
 * [MEDIA_BACKUP_MAX_IMAGE_BYTES], [MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES],
 * [MEDIA_BACKUP_MAX_MANIFEST_BYTES]); un valor inyectado solo puede acotar mas, porque el
 * manifiesto se sigue validando con [MediaBackupManifest.validate] y el codec con su propio tope.
 */
internal data class MediaBackupArchiveLimits(
    val maxImages: Int = MEDIA_BACKUP_MAX_IMAGES,
    val maxImageBytes: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES,
    val maxTotalImageBytes: Long = MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES,
    val maxManifestBytes: Long = MEDIA_BACKUP_MAX_MANIFEST_BYTES,
) {
    init {
        require(maxImages in 1..MEDIA_BACKUP_MAX_IMAGES) { "maxImages debe estar en 1..$MEDIA_BACKUP_MAX_IMAGES." }
        require(maxImageBytes in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "maxImageBytes debe estar en 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES."
        }
        require(maxTotalImageBytes in maxImageBytes..MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES) {
            "maxTotalImageBytes debe estar en maxImageBytes..$MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES."
        }
        require(maxManifestBytes in 1..MEDIA_BACKUP_MAX_MANIFEST_BYTES) {
            "maxManifestBytes debe estar en 1..$MEDIA_BACKUP_MAX_MANIFEST_BYTES."
        }
    }
}

/**
 * Puerto de ficheros del staging. Existe para poder inyectar fallos de creacion, salida, `sync` y
 * borrado en pruebas JVM; la implementacion de produccion es [DefaultMediaBackupArchiveFilePort].
 *
 * El puerto **no** decide rutas: recibe el directorio de staging ya validado y devuelve un fichero
 * nuevo. `openOutput` abre un descriptor sincronizable y `delete` borra un fichero concreto; ninguna
 * operacion es recursiva.
 */
internal interface MediaBackupArchiveFilePort {
    fun createStagingFile(directory: File): File
    fun openOutput(file: File): MediaBackupArchiveFileOutput
    fun delete(file: File): Boolean
}

/** Salida fisica del temporal: flujo de bytes, `fsync` del descriptor y cierre. */
internal interface MediaBackupArchiveFileOutput {
    val output: OutputStream
    fun sync()
    fun close()
}

/** Implementacion real: `createTempFile` unico, `FileOutputStream` y `FileDescriptor.sync()`. */
internal object DefaultMediaBackupArchiveFilePort : MediaBackupArchiveFilePort {

    override fun createStagingFile(directory: File): File =
        File.createTempFile(MediaBackupArchiveFiles.TEMPORARY_PREFIX, MediaBackupArchiveFiles.TEMPORARY_SUFFIX, directory)

    override fun openOutput(file: File): MediaBackupArchiveFileOutput {
        val stream = FileOutputStream(file)
        return object : MediaBackupArchiveFileOutput {
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

/**
 * Contenedor preparado y verificado: **dueno** del fichero temporal.
 *
 * Se entrega solo cuando el ZIP se ha cerrado, sincronizado y reabierto con exito. [close] elimina
 * ese unico fichero (idempotente) y nunca toca otros ficheros ni directorios; si el borrado falla,
 * lanza [MediaBackupArchiveException.CleanupFailed] y el fichero se conserva. No se registra en
 * ningun `FileProvider` ni se expone por URI desde este slice.
 */
class MediaBackupPreparedArchive internal constructor(
    val file: File,
    val manifestEntryName: String,
    val imageEntryNames: List<String>,
    val manifestByteCount: Long,
    val totalImageByteCount: Long,
    val archiveByteCount: Long,
    val manifestSha256: String,
    private val stagingDirectory: File,
    private val deleteFile: (File) -> Boolean,
) : Closeable {

    val imageCount: Int get() = imageEntryNames.size

    var isClosed: Boolean = false
        private set

    /**
     * Elimina el temporal propio. Idempotente: la segunda llamada no vuelve a borrar ni falla.
     * Solo borra un fichero regular con el nombre propio dentro del staging de esta operacion.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        val target = file
        if (!target.exists()) return
        val deleted = try {
            deleteOwnTemporary(target, stagingDirectory, deleteFile)
        } catch (failure: Throwable) {
            throw MediaBackupArchiveException.CleanupFailed(target.name, failure)
        }
        if (!deleted && target.exists()) {
            throw MediaBackupArchiveException.CleanupFailed(target.name, null)
        }
    }
}
