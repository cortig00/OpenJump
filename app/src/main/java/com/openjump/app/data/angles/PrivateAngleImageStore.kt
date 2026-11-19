package com.openjump.app.data.angles

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.FileSystemException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** Cancelación por defecto: una escritura sólo se cancela si el llamante lo pide explícitamente. */
private val NEVER_CANCELLED: () -> Boolean = { false }

/**
 * Almacén **privado** de imágenes anotadas: bytes opacos en `filesDir`, sin Android y sin caché.
 *
 * **Alcance de esta pieza (E1/E2).** Guarda y devuelve ficheros con integridad verificable. No se
 * conecta todavía a la UI, a Room ni al backup; no hay `Bitmap`, ni `FileProvider`, ni `ContentProvider`,
 * ni URIs públicas. El llamante entrega un `InputStream` de un PNG que **ya** generó y verificó:
 * este almacén no inspecciona el contenido (ni cabecera PNG, ni formato, ni dimensiones), sólo
 * sella tamaño y SHA-256 de los bytes que recibe. Las columnas `imageMimeType`, `imageWidth` y
 * `imageHeight` de la fila del fotograma las declara quien produce la imagen, no este almacén.
 *
 * **Aislamiento.** El constructor recibe un directorio raíz explícito (en producción `filesDir`) y
 * el almacén usa exclusivamente un subdirectorio propio ([IMAGE_DIRECTORY_NAME]). Ese raíz **debe
 * existir ya** como directorio real: es propiedad del llamante y el almacén **nunca** lo crea (ver
 * más abajo). El almacén no borra, mueve ni inspecciona nada fuera de su subdirectorio; los
 * ficheros preexistentes del llamante se preservan.
 *
 * **Claves.** Se generan aquí con [AngleImageKey] (SecureRandom) y se validan con la allowlist
 * canónica antes de construir ninguna ruta. Una clave no es una ruta ni un nombre de usuario y no
 * se acepta ninguna clave no canónica, ni siquiera para leer.
 *
 * **Publicación (E2, sin enlaces duros).** El layout es un **directorio por clave**:
 * `angle-images/<clave>/image.bin`. La escritura sigue esta secuencia:
 *
 * 1. validar la clave y medir/reservar el presupuesto compartido del directorio;
 * 2. **reservar la propiedad** con un `mkdir` **exclusivo** de `angle-images/<clave>`: si ya existe
 *    cualquier entrada (fichero, directorio o enlace, incluso colgante) la escritura se rechaza sin
 *    tocar nada. No hay reanudación de clave y `mkdirs()`/`exists()` no se usan como sustituto;
 * 3. crear un temporal **exclusivo dentro** del directorio recién creado y escribir el flujo con
 *    límites, SHA-256 y `fsync` del contenido;
 * 4. `rename` **interno** del temporal al nombre fijo `image.bin` (mismo directorio), bajo el
 *    cerrojo del presupuesto y con la condición de propiedad comprobada;
 * 5. verificar el fichero final (regular, tamaño y SHA-256) y sincronizar **en este orden y en
 *    cada publicación**: el directorio de la clave (`image.bin`), el del almacén (nombre de la
 *    clave) y el directorio raíz (nombre `angle-images`), informando el resultado de cada uno en
 *    [AngleImageDurability].
 *
 * **El raíz se sincroniza siempre, no sólo cuando `angle-images` acaba de crearse.** Observar que
 * el subdirectorio ya existía **no** demuestra que su entrada en el raíz esté persistida: otro
 * escritor del mismo proceso pudo crearlo y no haber sincronizado todavía su padre, o un intento
 * anterior pudo dejar la entrada sin confirmar. Observar no es confirmar, así que cada publicación
 * repite el `fsync` del raíz y sólo declara durabilidad si esa sincronización se confirma **en esta
 * operación**.
 *
 * **El [rootDirectory] lo provee el llamante y este almacén NUNCA lo crea.** La entrada más
 * superficial que el almacén puede persistir es `angle-images` dentro del raíz; la entrada del
 * raíz en su propio directorio padre queda **fuera del alcance** del almacén. Por eso el raíz:
 *
 * - debe **existir ya** como directorio real al empezar la escritura (en la app, `filesDir`, que
 *   crea y hace durable la plataforma). Es un requisito de propiedad y durabilidad del llamante;
 * - nunca se crea aquí, ni directa ni indirectamente. Si no existe, es un enlace o no es un
 *   directorio real, la escritura se rechaza **antes de cualquier otra E/S** con
 *   [AngleImageStoreError.RootDirectoryUnusable] (fail-closed) y sin tocar nada;
 * - se sincroniza en **cada** publicación y su resultado se reporta tal cual
 *   (`SYNCED`/`UNSUPPORTED`/`FAILED`).
 *
 * Nunca crearlo es lo que hace honesto el informe de durabilidad: si el almacén creara el raíz, su
 * entrada en el padre jamás se sincronizaría desde aquí y una escritura posterior —o dos escrituras
 * concurrentes— observarían el raíz ya existente y declararían durable un nombre que nadie
 * confirmó. Rechazar el raíz ausente elimina esa ambigüedad: un raíz presente es siempre
 * responsabilidad del llamante.
 *
 * **La garantía frente a sobrescrituras no viene de `rename`.** En POSIX `rename(2)` **reemplaza**
 * el destino; la seguridad proviene de que el namespace de la clave es nuevo y de que sólo su
 * creador escribe dentro. Por eso no se reutiliza una clave y se rechaza cualquier clave existente
 * (incluido un directorio vacío de un intento anterior). El alcance es **escritores cooperantes
 * del proceso**: no se promete aislamiento frente a otro proceso del mismo UID, ni frente a código
 * hostil dentro de la app, ni frente a alias externos (montajes, `dev/ino` reutilizados por un
 * tercero). El presupuesto compartido también es **de proceso**.
 *
 * **Fallos después del rename.** Si el fichero final no se puede verificar, la operación devuelve
 * [AngleImageStoreError.PublicationUncertain] y **preserva** el fichero: puede existir una imagen
 * válida sin fila en Room y la recuperación/GC es E2. No se afirma "nada publicado" ni se borra.
 * Un fallo al sincronizar directorios **tampoco** oculta la publicación: el resultado es
 * [AngleImageWriteResult.Stored] con su clave y `directoryEntriesSynced = false`, porque el fichero
 * ya está renombrado y verificado y no se puede deshacer ese hecho.
 *
 * **Lectura.** Se valida clave, directorio de clave (real, no enlace), fichero final (regular, no
 * enlace), tamaño y SHA-256 con una pasada acotada, y sólo después se entrega el flujo al
 * consumidor: un fichero truncado, trocado, ausente o con un enlace en su ruta nunca se presenta
 * como válido y no se borra ni se "repara". La verificación y la entrega son dos pasadas sobre el
 * mismo fichero; si un tercero con acceso al directorio privado lo modificase entre ambas, el
 * consumidor podría ver bytes distintos de los verificados. El almacén no promete aislamiento
 * frente a un proceso que ya puede escribir en el sandbox de la app.
 *
 * **Concurrencia y cuota.** El presupuesto agregado ([AngleImageStoreLimits.maxTotalBytes])
 * pertenece al **directorio real**, no a la instancia: todas las instancias del proceso que
 * publican en el mismo directorio comparten un único presupuesto
 * ([AngleImageCapacityRegistry]). La medición del uso y la reserva se hacen dentro del **mismo**
 * cerrojo que la publicación y la liberación, de modo que una medida no puede quedar obsoleta
 * entre reservar y publicar y dos instancias no pueden contar el mismo hueco. El cerrojo **no** se
 * mantiene durante el streaming. Además, el `mkdir` exclusivo resuelve las carreras de clave: con
 * la misma clave gana exactamente un escritor. Si otra instancia registró **otros** límites para
 * el mismo directorio, la escritura se rechaza con [AngleImageStoreError.LimitsConflict]
 * (fail-closed) en vez de mezclar presupuestos.
 *
 * **Limpieza.** Sólo se borra el temporal propio de la operación. El directorio de clave recién
 * creado se retira con `rmdir` **sólo si está vacío** y **nunca** de forma recursiva; si algo
 * impide retirarlo se preserva y se informa ([AngleImageWriteResult.Failed.directoryRetained]).
 * Tras el rename **no se borra nada**: un fallo de verificación deja un huérfano que E2 debe
 * recuperar. Este almacén **no ofrece borrado público**: no existe `delete`/`gc` ciego ni barrido
 * de huérfanos, y ningún fichero publicado se elimina automáticamente. Lo único que puede retirar
 * una imagen publicada es la reclamación **interna** y exacta
 * [reclaimVerifiedOwnedImage], que actúa clave por clave sobre una integridad ya verificada, no
 * enumera nada y obliga al llamante a aportar la exclusión (`AngleMediaCoordination.mutex`) y la
 * referencia cero de Room tras el commit: el almacén no ve Room y **no** puede comprobar ninguna de
 * las dos cosas. La reclamación de huérfanos, los temporales abandonados por un fallo de limpieza y
 * la coordinación con Room/restore/compartir siguen siendo **E2**; los temporales usan el prefijo
 * [AngleImageFileIo.TEMPORARY_FILE_PREFIX] para que esa recuperación pueda identificarlos.
 *
 * **Atomicidad con Room.** No se promete ninguna: Room y el sistema de ficheros no comparten
 * transacción. La fila del fotograma declara `imageByteCount`/`imageSha256` y el almacén las usa
 * como comprobación de integridad, no como garantía de que escritura de fichero e inserción de
 * fila ocurran juntas. El orden correcto (archivo durable validado → transacción Room) lo decide
 * E2.
 *
 * @param fileIo frontera de E/S **inyectada explícitamente**, sin valor por defecto. El almacén no
 *   elige backend por plataforma ni por detección de stubs: en la app Android se inyecta
 *   [AndroidAngleImageFileIo] y en JVM/pruebas [NioAngleImageFileIo]. Dejar que aquí se creara un
 *   backend `java.nio.file` haría fallar toda escritura en Android (`Files.getFileStore` y
 *   `Files.createLink` no están implementados y `link(2)` está denegado por SELinux) y el fallo
 *   aparecería lejos del punto de composición.
 */
class PrivateAngleImageStore(
    private val rootDirectory: File,
    private val limits: AngleImageStoreLimits = AngleImageStoreLimits(),
    private val generateKey: () -> String = AngleImageKey::generate,
    private val fileIo: AngleImageFileIo,
) {

    private val imagesDirectory: File = File(rootDirectory, IMAGE_DIRECTORY_NAME)

    /**
     * Escribe [source] como una imagen nueva y devuelve su clave opaca y su integridad.
     *
     * [expectedByteCount], si se declara, debe ser positivo, caber en
     * [AngleImageStoreLimits.maxImageBytes] y coincidir **exactamente** con los bytes del flujo:
     * un flujo más corto o más largo se rechaza con [AngleImageStoreError.SizeMismatch] en vez de
     * publicar un fichero que no cuadre con la fila. Si no se declara, el flujo se acota a
     * [AngleImageStoreLimits.maxImageBytes].
     *
     * [shouldCancel] se consulta entre bloques; al devolver `true` la operación termina con
     * [AngleImageStoreError.Canceled] sin publicar nada. Una interrupción del hilo o un
     * `InterruptedIOException` del flujo también se reportan como cancelación.
     *
     * Nunca lanza por causas esperables de disco/contrato: devuelve
     * [AngleImageWriteResult.Failed] con la causa tipada. Un fallo de E/S inesperado no se
     * convierte en éxito ni en borrado de datos ajenos. Un fallo **después** del rename devuelve
     * [AngleImageStoreError.PublicationUncertain] y conserva el fichero final. Un fallo al
     * sincronizar directorios **no** pierde la clave publicada: el resultado es
     * [AngleImageWriteResult.Stored] con `directoryEntriesSynced = false`.
     *
     * [rootDirectory] debe existir ya como directorio real: este almacén **nunca** lo crea y
     * rechaza la escritura con [AngleImageStoreError.RootDirectoryUnusable] antes de cualquier otra
     * E/S si falta, es un enlace o no es un directorio. Es un requisito de propiedad y durabilidad
     * del llamante (en la app, `filesDir`); sin él, una escritura posterior podría declarar durable
     * una entrada del raíz que nadie sincronizó.
     */
    fun write(
        source: InputStream,
        expectedByteCount: Long? = null,
        shouldCancel: () -> Boolean = NEVER_CANCELLED,
    ): AngleImageWriteResult {
        if (expectedByteCount != null && (expectedByteCount < 1L || expectedByteCount > limits.maxImageBytes)) {
            return AngleImageWriteResult.Failed(
                AngleImageStoreError.TooLarge(limits.maxImageBytes, expectedByteCount),
            )
        }
        val reservationBytes = expectedByteCount ?: limits.maxImageBytes
        var temporaryFile: File? = null
        var ownedKeyDirectory: File? = null
        var reservation: DirectoryCapacity.Reservation? = null
        var stored: StoredAngleImage? = null
        var durability = AngleImageDurability.NOT_PUBLISHED
        var failure: AngleImageStoreError? = null
        var temporaryFileRetained = false
        var directoryRetained = false
        var publishCompleted = false
        try {
            // La clave es un contrato puro: se valida antes de mirar el disco, así que una clave no
            // canónica se rechaza igual aunque el raíz no esté utilizable.
            val imageKey = generateKey()
            if (!AngleImageKey.isCanonical(imageKey)) {
                throw StoreAbort(AngleImageStoreError.InvalidKey(imageKey))
            }
            // El raíz lo provee el llamante y este almacén NUNCA lo crea: si no existe como
            // directorio real, se rechaza antes de tocar nada. Crearlo aquí (o dejar que la
            // creación de `angle-images` lo hiciera en cascada) dejaría su entrada en el padre sin
            // `fsync`, fuera del alcance del almacén, y una escritura posterior la observaría como
            // existente: se declararía durable un nombre que nadie confirmó.
            when (call(AngleImageStoreStage.PREPARE_DIRECTORY) { fileIo.entryKind(rootDirectory) }) {
                AngleImageEntryKind.DIRECTORY -> Unit
                AngleImageEntryKind.ABSENT -> throw StoreAbort(
                    AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> throw StoreAbort(
                    AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.SYMBOLIC_LINK),
                )
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> throw StoreAbort(
                    AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.NOT_DIRECTORY),
                )
            }
            // `angle-images` sí es del almacén: se crea (si falta) con un `mkdir` **exclusivo y no
            // recursivo** dentro del raíz ya verificado. Así, aunque el raíz desapareciera en este
            // instante, el alta falla con `ENOENT` en vez de recrear el raíz en cascada: la
            // creación recursiva nunca es un camino para crear el raíz.
            try {
                fileIo.createKeyDirectory(rootDirectory, IMAGE_DIRECTORY_NAME)
            } catch (_: FileAlreadyExistsException) {
                // Ya existía: `ensureDirectory` valida que sea un directorio real.
            } catch (error: IOException) {
                throw StoreAbort(mapIoFailure(AngleImageStoreStage.PREPARE_DIRECTORY, error))
            }
            call(AngleImageStoreStage.PREPARE_DIRECTORY) { fileIo.ensureDirectory(imagesDirectory) }
            // El raíz sigue siendo el que se verificó: `ensureDirectory` no lo crea (existe) y
            // ninguna otra E/S de esta escritura escribe fuera de `angle-images`.

            val keyDirectory = keyDirectoryFor(imageKey)
            val imageFile = File(keyDirectory, AngleImageFileIo.FINAL_FILE_NAME)

            // Clasificación previa (la autoridad es el `mkdir` exclusivo de más abajo): permite
            // devolver el error tipado correcto sin tocar la ruta existente y sin medir el uso.
            when (call(AngleImageStoreStage.VALIDATE_KEY) { fileIo.entryKind(keyDirectory) }) {
                AngleImageEntryKind.ABSENT -> Unit
                AngleImageEntryKind.SYMBOLIC_LINK -> throw StoreAbort(
                    AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.SYMBOLIC_LINK),
                )
                AngleImageEntryKind.DIRECTORY,
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> throw StoreAbort(AngleImageStoreError.KeyAlreadyExists(imageKey))
            }

            val capacity = when (val registration = capacityRegistration()) {
                is CapacityRegistration.Registered -> registration.capacity
                is CapacityRegistration.ConflictingLimits -> throw StoreAbort(
                    AngleImageStoreError.LimitsConflict(limits, registration.registeredLimits),
                )
            }
            // La medida se toma DENTRO del cerrojo del presupuesto compartido y la reserva se hace
            // en el mismo paso: ni una medida anterior a una publicación puede usarse después, ni
            // dos instancias del mismo directorio pueden reservar el mismo hueco.
            reservation = when (
                val attempt = capacity.reserve(
                    measureUsageBytes = {
                        call(AngleImageStoreStage.CHECK_CAPACITY) { fileIo.usageBytes(imagesDirectory) }
                    },
                    requestedBytes = reservationBytes,
                )
            ) {
                is CapacityReservationResult.Denied -> throw StoreAbort(quotaDenial(attempt.measuredUsageBytes))
                is CapacityReservationResult.Granted -> attempt.reservation
            }
            val usableSpace = call(AngleImageStoreStage.CHECK_CAPACITY) { fileIo.usableSpaceBytes(imagesDirectory) }
            if (usableSpace != null && usableSpace < reservationBytes) {
                throw StoreAbort(AngleImageStoreError.InsufficientStorage(reservationBytes, usableSpace))
            }

            // Reserva de propiedad: `mkdir` exclusivo. Si algo ocupa la clave, se rechaza sin tocar.
            ownedKeyDirectory = claimKeyDirectory(imageKey, keyDirectory)

            val temporary = call(AngleImageStoreStage.CREATE_TEMPORARY) {
                fileIo.createTemporaryFile(keyDirectory)
            }
            temporaryFile = temporary

            val digest = newSha256()
            var byteCount = 0L
            call(AngleImageStoreStage.WRITE) {
                fileIo.openSink(temporary).use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (shouldCancel()) throw StoreAbort(AngleImageStoreError.Canceled)
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        byteCount += read
                        if (byteCount > limits.maxImageBytes) {
                            throw StoreAbort(AngleImageStoreError.TooLarge(limits.maxImageBytes, byteCount))
                        }
                        if (expectedByteCount != null && byteCount > expectedByteCount) {
                            throw StoreAbort(AngleImageStoreError.SizeMismatch(expectedByteCount, byteCount))
                        }
                        digest.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                    }
                    call(AngleImageStoreStage.SYNC) { sink.sync() }
                }
            }
            if (byteCount == 0L) throw StoreAbort(AngleImageStoreError.EmptyImage)
            if (expectedByteCount != null && byteCount != expectedByteCount) {
                throw StoreAbort(AngleImageStoreError.SizeMismatch(expectedByteCount, byteCount))
            }
            if (shouldCancel()) throw StoreAbort(AngleImageStoreError.Canceled)

            val image = StoredAngleImage(
                imageKey = imageKey,
                byteCount = byteCount,
                sha256Hex = digest.digest().toLowerHexString(),
            )
            reservation.commit {
                try {
                    fileIo.publishWithinOwnedDirectory(temporary, imageFile, keyDirectory)
                } catch (existing: FileAlreadyExistsException) {
                    throw StoreAbort(AngleImageStoreError.KeyAlreadyExists(imageKey))
                } catch (error: IOException) {
                    throw StoreAbort(mapIoFailure(AngleImageStoreStage.PUBLISH, error))
                }
            }
            // El rename consumió el nombre temporal y creó el definitivo: a partir de aquí no se
            // borra nada (un huérfano publicado se preserva para la recuperación de E2).
            publishCompleted = true
            temporaryFile = null

            publishedFileProblem(imageKey, imageFile, image)?.let { throw StoreAbort(it) }

            durability = publishDurability(keyDirectory = keyDirectory)
            stored = image
        } catch (abort: StoreAbort) {
            failure = abort.error
        } finally {
            // Sólo el temporal propio de esta operación; nunca rutas ajenas ni publicadas.
            val pending = temporaryFile
            if (pending != null) {
                try {
                    fileIo.delete(pending)
                } catch (_: Exception) {
                    temporaryFileRetained = true
                }
            }
            val owned = ownedKeyDirectory
            if (owned != null && !publishCompleted) {
                directoryRetained = if (temporaryFileRetained) {
                    // El directorio conserva el temporal que no se pudo borrar: se preserva.
                    true
                } else {
                    try {
                        !fileIo.removeEmptyDirectory(owned)
                    } catch (_: Exception) {
                        true
                    }
                }
            }
            reservation?.release()
        }
        val image = stored
        if (image != null) {
            return AngleImageWriteResult.Stored(image, temporaryFileRetained, durability)
        }
        return AngleImageWriteResult.Failed(
            error = failure ?: AngleImageStoreError.IoFailure(AngleImageStoreStage.WRITE, "operación sin resultado"),
            temporaryFileRetained = temporaryFileRetained,
            directoryRetained = directoryRetained,
        )
    }

    /**
     * Comprueba que la imagen [imageKey] existe y coincide con [expected], sin entregar bytes.
     *
     * No borra ni modifica nada: ausencia ([AngleImageStoreError.Absent]) y corrupción
     * ([AngleImageStoreError.Corrupted]) son resultados distintos. Un directorio de clave sin
     * fichero final (resto de una publicación interrumpida) es **ausencia**, no una imagen válida.
     */
    fun verify(imageKey: String, expected: AngleImageIntegrity): AngleImageVerifyResult {
        invalidIntegrity(expected)?.let { return AngleImageVerifyResult.Failed(it) }
        if (!AngleImageKey.isCanonical(imageKey)) {
            return AngleImageVerifyResult.Failed(AngleImageStoreError.InvalidKey(imageKey))
        }
        return try {
            val keyDirectory = keyDirectoryFor(imageKey)
            when (call(AngleImageStoreStage.VERIFY) { fileIo.entryKind(keyDirectory) }) {
                AngleImageEntryKind.ABSENT -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Absent(imageKey),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.SYMBOLIC_LINK),
                )
                AngleImageEntryKind.DIRECTORY -> Unit
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.NOT_REGULAR_FILE),
                )
            }
            val imageFile = File(keyDirectory, AngleImageFileIo.FINAL_FILE_NAME)
            when (call(AngleImageStoreStage.VERIFY) { fileIo.entryKind(imageFile) }) {
                AngleImageEntryKind.ABSENT -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Absent(imageKey),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.SYMBOLIC_LINK),
                )
                AngleImageEntryKind.REGULAR_FILE -> Unit
                AngleImageEntryKind.DIRECTORY,
                AngleImageEntryKind.OTHER,
                -> return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.NOT_REGULAR_FILE),
                )
            }
            val length = call(AngleImageStoreStage.VERIFY) { fileIo.length(imageFile) }
            if (length != expected.byteCount) {
                return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.SIZE_MISMATCH),
                )
            }
            val actualSha256 = call(AngleImageStoreStage.VERIFY) { sha256Of(imageFile, expected.byteCount) }
            if (actualSha256 == null || actualSha256 != expected.sha256Hex.lowercase()) {
                return AngleImageVerifyResult.Failed(
                    AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.DIGEST_MISMATCH),
                )
            }
            AngleImageVerifyResult.Verified(StoredAngleImage(imageKey, length, actualSha256))
        } catch (abort: StoreAbort) {
            AngleImageVerifyResult.Failed(abort.error)
        }
    }

    /**
     * Verifica la imagen y, sólo si es válida, entrega a [consumer] un flujo **acotado** a los
     * bytes verificados. El consumidor no puede leer más allá del tamaño declarado ni de
     * [AngleImageStoreLimits.maxImageBytes].
     *
     * Si la imagen falta, está corrupta o no es segura, [consumer] **no** se invoca. Un
     * `IOException` del consumidor se reporta como
     * [AngleImageStoreError.IoFailure] en [AngleImageStoreStage.READ]; cualquier otra excepción
     * del consumidor se propaga, porque es un fallo del llamante, no del almacén.
     */
    fun <T> readVerified(
        imageKey: String,
        expected: AngleImageIntegrity,
        consumer: (InputStream) -> T,
    ): AngleImageReadResult<T> {
        when (val verified = verify(imageKey, expected)) {
            is AngleImageVerifyResult.Failed -> return AngleImageReadResult.Failed(verified.error)
            is AngleImageVerifyResult.Verified -> {
                val source = try {
                    call(AngleImageStoreStage.READ) { fileIo.openSource(imageFileFor(imageKey)) }
                } catch (abort: StoreAbort) {
                    return AngleImageReadResult.Failed(abort.error)
                }
                try {
                    val value = consumer(BoundedImageInputStream(source, verified.image.byteCount))
                    return AngleImageReadResult.Delivered(verified.image, value)
                } catch (error: IOException) {
                    return AngleImageReadResult.Failed(
                        AngleImageStoreError.IoFailure(AngleImageStoreStage.READ, describe(error)),
                    )
                } finally {
                    try {
                        source.close()
                    } catch (_: IOException) {
                        // El flujo es de sólo lectura: un fallo al cerrar no cambia los bytes ya
                        // entregados ni convierte la lectura en inválida.
                    }
                }
            }
        }
    }

    /**
     * Retira **exactamente** la imagen publicada de [imageKey] si en disco coincide con
     * [expectedIntegrity]. Reclamación interna posterior a un borrado ya confirmado.
     *
     * **Obligaciones del llamante que el almacén no puede comprobar.** Este método no es una
     * autorización: quien llama debe tener tomado `AngleMediaCoordination.mutex` (el cerrojo por
     * proceso que serializan guardado, restauración, exportación y backup con imágenes) y haber
     * confirmado, **después** del commit de la raíz en Room, que ninguna fila referencia ya la clave
     * y que la raíz borrada declaraba exactamente esta clave y esta integridad. El almacén no ve
     * Room, no cuenta referencias y **no** puede prometer referencia cero: aquí sólo se comprueba el
     * fichero. Tampoco promete aislamiento frente a otro proceso del mismo UID ni frente a código
     * hostil dentro de la app (mismo alcance que el resto del almacén).
     *
     * **Qué se comprueba antes de mutar.** Clave canónica e integridad esperada utilizable (sin
     * tocar el disco); después, con `lstat` y sin seguir enlaces, que [rootDirectory] y
     * `angle-images` son directorios reales (nunca enlaces) y que el directorio de la clave cuelga
     * canónicamente de ellos; que dentro del directorio de la clave está **sólo** `image.bin` (un
     * temporal, un fichero extra, un subdirectorio o un enlace se conservan y se reportan) y que
     * `image.bin` es un fichero regular con el tamaño y el SHA-256 esperados. Ningún rechazo muta
     * nada ([AngleImageReclaimResult.Retained]) y una ausencia es un resultado idempotente
     * ([AngleImageReclaimResult.Absent]).
     *
     * **Qué se muta.** Un único `unlink` del fichero final, dentro del cerrojo de medición y
     * publicación del presupuesto del directorio (ver [DirectoryCapacity.withMeasurementLock]), y
     * después un `rmdir` **no recursivo** del directorio de la clave, que sólo se retira si quedó
     * realmente vacío. Nunca se crea el raíz ni `angle-images`, nunca se borra un directorio con
     * contenido, nunca se toca otra clave, el vídeo, las copias de compartir, el legado plano ni
     * ningún temporal ajeno. Después se sincronizan los nombres que la retirada cambió y su
     * resultado se informa ([AngleImageReclaimDurability]); un `UNSUPPORTED` o `FAILED` no se
     * convierte en durabilidad declarada.
     *
     * **Fallo después de mutar.** Si el `unlink` lanza, no se puede afirmar que el fichero se
     * conserve: el resultado es [AngleImageReclaimResult.Unconfirmed] con lo que se pudo observar
     * del fichero exacto. Si el `unlink` se confirma y falla el `rmdir`, el `lstat` posterior o el
     * `fsync`, el resultado sigue siendo [AngleImageReclaimResult.Removed] con `directoryRetained` y
     * la durabilidad observada: el fichero ya no está y decir otra cosa mentiría.
     */
    internal fun reclaimVerifiedOwnedImage(
        imageKey: String,
        expectedIntegrity: AngleImageIntegrity,
    ): AngleImageReclaimResult {
        if (!AngleImageKey.isCanonical(imageKey)) {
            return refusedBeforeMutation(imageKey, AngleImageStoreError.InvalidKey(imageKey))
        }
        invalidIntegrity(expectedIntegrity)?.let { return refusedBeforeMutation(imageKey, it) }
        val target = when (val preflight = preflightReclaim(imageKey, expectedIntegrity)) {
            is ReclaimPreflight.Settled -> return preflight.result
            is ReclaimPreflight.Ready -> preflight.target
        }

        val imageFile = File(target.keyDirectory, AngleImageFileIo.FINAL_FILE_NAME)
        var unlinkFailure: AngleImageStoreError? = null
        var directoryRetired = false
        var keyDirectoryKind: AngleImageEntryKind? = null
        // El `unlink` y el `rmdir` cambian los bytes medidos: se ejecutan bajo el mismo cerrojo con
        // el que otra escritura mide y reserva, para que su medición no observe la retirada a medias
        // (ni un `length` espurio sobre un `image.bin` que acaba de desaparecer). No se reserva nada:
        // esta operación sólo libera bytes reales.
        target.capacity.withMeasurementLock {
            unlinkFailure = try {
                call(AngleImageStoreStage.CLEANUP) { fileIo.delete(imageFile) }
                null
            } catch (abort: StoreAbort) {
                abort.error
            }
            if (unlinkFailure == null) {
                directoryRetired = try {
                    call(AngleImageStoreStage.CLEANUP) { fileIo.removeEmptyDirectory(target.keyDirectory) }
                } catch (_: StoreAbort) {
                    false
                }
                keyDirectoryKind = try {
                    call(AngleImageStoreStage.CLEANUP) { fileIo.entryKind(target.keyDirectory) }
                } catch (_: StoreAbort) {
                    null
                }
            }
        }
        // A partir de aquí el fichero final se retiró o no: nunca se informa `Retained`.
        val failure = unlinkFailure
        if (failure != null) {
            return AngleImageReclaimResult.Unconfirmed(
                imageKey = imageKey,
                reason = failure,
                observed = observeImageFile(imageFile),
            )
        }
        if (!directoryRetired) {
            when (keyDirectoryKind) {
                // Se conserva de verdad: `rmdir` lo rechazó (contenido inesperado o plataforma).
                AngleImageEntryKind.DIRECTORY -> Unit
                // El nombre ya no existe: nada queda retenido y `angle-images` sí cambió.
                AngleImageEntryKind.ABSENT -> directoryRetired = true
                // Un nombre que no es un directorio real no lo produce ningún escritor de este
                // almacén. No se sincroniza (no se sigue un enlace potencial) y no se afirma que la
                // limpieza esté confirmada.
                AngleImageEntryKind.SYMBOLIC_LINK,
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                null,
                -> return AngleImageReclaimResult.Unconfirmed(
                    imageKey = imageKey,
                    reason = AngleImageStoreError.IoFailure(
                        AngleImageStoreStage.CLEANUP,
                        "el nombre de la clave no es un directorio real tras retirar image.bin",
                    ),
                    observed = observeImageFile(imageFile),
                )
            }
        }
        // Sólo los nombres que la retirada cambió, de más profundo a más superficial. Los `fsync` se
        // hacen fuera del cerrojo: no alteran el uso medido y no alargan el bloqueo de escrituras.
        val keyDirectorySync: AngleImageDirectorySync?
        val imagesDirectorySync: AngleImageDirectorySync?
        if (directoryRetired) {
            keyDirectorySync = null
            imagesDirectorySync = syncDirectoryReported(imagesDirectory)
        } else {
            keyDirectorySync = syncDirectoryReported(target.keyDirectory)
            imagesDirectorySync = null
        }
        return AngleImageReclaimResult.Removed(
            image = target.image,
            directoryRetained = !directoryRetired,
            durability = AngleImageReclaimDurability(
                keyDirectory = keyDirectorySync,
                imagesDirectory = imagesDirectorySync,
            ),
        )
    }

    /**
     * Preflight de [reclaimVerifiedOwnedImage]: todo lo que puede rechazar **antes** de mutar.
     *
     * No crea, mueve ni borra nada: sólo `lstat`, listado del directorio de la clave, registro del
     * presupuesto y verificación de tamaño/SHA-256. Cualquier fallo esperado se traduce a
     * [ReclaimPreflight.Settled] con su error tipado.
     */
    private fun preflightReclaim(
        imageKey: String,
        expectedIntegrity: AngleImageIntegrity,
    ): ReclaimPreflight {
        return try {
                // Raíz: propiedad del llamante, real y preexistente, nunca un enlace. A diferencia de
                // `write`, aquí no se usa para publicar; se exige para no afirmar ausencias ni retiradas
                // sobre un almacén cuya raíz no se puede ni clasificar.
                when (call(AngleImageStoreStage.PREPARE_DIRECTORY) { fileIo.entryKind(rootDirectory) }) {
                AngleImageEntryKind.DIRECTORY -> Unit
                AngleImageEntryKind.ABSENT -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
                    ),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.SYMBOLIC_LINK),
                    ),
                )
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.NOT_DIRECTORY),
                    ),
                )
            }
            // `angle-images` ausente = nada publicado: ausencia idempotente y sin crear el directorio.
            when (call(AngleImageStoreStage.PREPARE_DIRECTORY) { fileIo.entryKind(imagesDirectory) }) {
                AngleImageEntryKind.DIRECTORY -> Unit
                AngleImageEntryKind.ABSENT -> return ReclaimPreflight.Settled(
                    AngleImageReclaimResult.Absent(imageKey),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.SYMBOLIC_LINK),
                    ),
                )
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.NOT_REGULAR_FILE),
                    ),
                )
            }
            // Contención canónica defensiva: el directorio de la clave cuelga exactamente del almacén.
            val keyDirectory = keyDirectoryFor(imageKey)
            when (call(AngleImageStoreStage.VALIDATE_KEY) { fileIo.entryKind(keyDirectory) }) {
                AngleImageEntryKind.DIRECTORY -> Unit
                AngleImageEntryKind.ABSENT -> return ReclaimPreflight.Settled(
                    AngleImageReclaimResult.Absent(imageKey),
                )
                AngleImageEntryKind.SYMBOLIC_LINK -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.SYMBOLIC_LINK),
                    ),
                )
                AngleImageEntryKind.REGULAR_FILE,
                AngleImageEntryKind.OTHER,
                -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.Corrupted(imageKey, AngleImageCorruptionReason.NOT_REGULAR_FILE),
                    ),
                )
            }
            // Layout exacto: dentro de la clave sólo el nombre final. Nada de borrado por prefijo ni de
            // "limpiar" temporales: un extra se conserva y se reporta.
            val unexpectedEntryNames = call(AngleImageStoreStage.VALIDATE_KEY) {
                fileIo.listEntryNames(keyDirectory).filter { it != AngleImageFileIo.FINAL_FILE_NAME }
            }.sorted()
            if (unexpectedEntryNames.isNotEmpty()) {
                return ReclaimPreflight.Settled(
                    AngleImageReclaimResult.Retained(
                        imageKey = imageKey,
                        reason = AngleImageReclaimRefusal.UnexpectedKeyDirectoryContent(
                            imageKey = imageKey,
                            unexpectedEntryNames = unexpectedEntryNames,
                        ),
                    ),
                )
            }
            // Presupuesto del directorio real: un conflicto de límites se rechaza cerrado, igual que en
            // la escritura, en vez de retirar bytes contables bajo un presupuesto ambiguo.
            val capacity = when (val registration = capacityRegistration()) {
                is CapacityRegistration.Registered -> registration.capacity
                is CapacityRegistration.ConflictingLimits -> return ReclaimPreflight.Settled(
                    refusedBeforeMutation(
                        imageKey,
                        AngleImageStoreError.LimitsConflict(limits, registration.registeredLimits),
                    ),
                )
            }
            // Tamaño y SHA-256 exactos contra la integridad declarada por Room, antes de mutar nada.
            val image = when (val verified = verify(imageKey, expectedIntegrity)) {
                is AngleImageVerifyResult.Failed -> return ReclaimPreflight.Settled(
                    when (val error = verified.error) {
                        is AngleImageStoreError.Absent -> AngleImageReclaimResult.Absent(imageKey)
                        else -> refusedBeforeMutation(imageKey, error)
                    },
                )
                is AngleImageVerifyResult.Verified -> verified.image
            }
            ReclaimPreflight.Ready(ReclaimTarget(keyDirectory = keyDirectory, capacity = capacity, image = image))
        } catch (abort: StoreAbort) {
            ReclaimPreflight.Settled(refusedBeforeMutation(imageKey, abort.error))
        }
    }

    /** Rechazo tipado antes de mutar: se sabe que esta operación no ha borrado nada. */
    private fun refusedBeforeMutation(
        imageKey: String,
        error: AngleImageStoreError,
    ): AngleImageReclaimResult = AngleImageReclaimResult.Retained(
        imageKey = imageKey,
        reason = AngleImageReclaimRefusal.StoreError(error),
    )

    /** Observa el fichero exacto tras un intento fallido, sin convertirlo en éxito ni en rechazo. */
    private fun observeImageFile(imageFile: File): AngleImageReclaimObservation = try {
        when (call(AngleImageStoreStage.CLEANUP) { fileIo.entryKind(imageFile) }) {
            AngleImageEntryKind.ABSENT -> AngleImageReclaimObservation.ABSENT
            else -> AngleImageReclaimObservation.PRESENT
        }
    } catch (_: StoreAbort) {
        AngleImageReclaimObservation.UNKNOWN
    }

    /**
     * Presupuesto del **directorio real** de este almacén.
     *
     * La ruta se canonicaliza para que dos instancias que nombran el mismo directorio de formas
     * distintas (o que llegan a él por enlaces) compartan el mismo presupuesto. El conflicto de
     * límites no se resuelve aquí: se devuelve para que `write` lo rechace de forma tipada.
     */
    private fun capacityRegistration(): CapacityRegistration {
        val realDirectory = call(AngleImageStoreStage.CHECK_CAPACITY) { fileIo.canonicalFile(imagesDirectory) }
        return AngleImageCapacityRegistry.capacityFor(realDirectory.path, limits)
    }

    /** Traduce un rechazo de reserva: medida imposible = fallo de E/S; el resto, cuota agotada. */
    private fun quotaDenial(measuredUsageBytes: Long): AngleImageStoreError =
        if (measuredUsageBytes < 0L) {
            AngleImageStoreError.IoFailure(
                AngleImageStoreStage.CHECK_CAPACITY,
                "uso medido no fiable: $measuredUsageBytes",
            )
        } else {
            AngleImageStoreError.QuotaExceeded(limits.maxTotalBytes, measuredUsageBytes)
        }

    /**
     * Reclama el directorio de la clave con un `mkdir` **exclusivo** y traduce el rechazo.
     *
     * El `mkdir` es la autoridad sobre la propiedad: no se comprueba y luego se crea, se crea y el
     * kernel decide. Al fallar con `EEXIST` se clasifica la ruta **sólo para el error tipado**; no
     * se toca ni se reutiliza.
     */
    private fun claimKeyDirectory(imageKey: String, keyDirectory: File): File {
        val created = try {
            fileIo.createKeyDirectory(imagesDirectory, imageKey)
        } catch (existing: FileAlreadyExistsException) {
            throw StoreAbort(classifyOccupiedKeyPath(imageKey, keyDirectory))
        } catch (error: IOException) {
            throw StoreAbort(mapIoFailure(AngleImageStoreStage.RESERVE_KEY_DIRECTORY, error))
        }
        if (created.path != keyDirectory.path) {
            throw StoreAbort(
                AngleImageStoreError.IoFailure(
                    AngleImageStoreStage.RESERVE_KEY_DIRECTORY,
                    "el directorio reservado no es el directorio de la clave",
                ),
            )
        }
        return created
    }

    private fun classifyOccupiedKeyPath(imageKey: String, keyDirectory: File): AngleImageStoreError =
        when (call(AngleImageStoreStage.RESERVE_KEY_DIRECTORY) { fileIo.entryKind(keyDirectory) }) {
            AngleImageEntryKind.SYMBOLIC_LINK -> AngleImageStoreError.UnsafePath(
                imageKey,
                AngleImagePathViolation.SYMBOLIC_LINK,
            )
            AngleImageEntryKind.DIRECTORY,
            AngleImageEntryKind.REGULAR_FILE,
            -> AngleImageStoreError.KeyAlreadyExists(imageKey)
            AngleImageEntryKind.OTHER -> AngleImageStoreError.UnsafePath(
                imageKey,
                AngleImagePathViolation.NOT_REGULAR_FILE,
            )
            AngleImageEntryKind.ABSENT -> AngleImageStoreError.IoFailure(
                AngleImageStoreStage.RESERVE_KEY_DIRECTORY,
                "el mkdir exclusivo falló pero la ruta ya no existe",
            )
        }

    /**
     * Informa la durabilidad de los nombres de una publicación **ya renombrada y verificada**.
     *
     * Sincroniza **siempre y en este orden**, de más profundo a más superficial: el directorio de la
     * clave (nombre `image.bin`), el del almacén (nombre de la clave) y el [rootDirectory] (nombre
     * `angle-images`). El raíz **no** se omite cuando `angle-images` ya existía: su presencia es una
     * observación, no una confirmación de que esa entrada esté persistida (la pudo crear otro
     * escritor sin sincronizar su padre, o un fallo anterior dejó la entrada sin confirmar).
     *
     * El raíz llega ya verificado como directorio real preexistente (`write` rechaza cualquier otro
     * caso y nunca lo crea), así que aquí se reporta el resultado de su `fsync` tal cual: su propia
     * entrada en el directorio padre es responsabilidad del llamante y queda fuera del alcance del
     * almacén.
     *
     * [syncDirectory] se contrata como "no lanza"; si un backend lanza igualmente se reporta
     * [AngleImageDirectorySync.FAILED]. Nunca se propaga: a estas alturas el fichero final está
     * publicado y verificado, de modo que convertirlo en "no publicado" —o perder su clave—
     * mentiría sobre el estado del disco. Un raíz `FAILED`/`UNSUPPORTED` deja
     * [AngleImageDurability.directoryEntriesSynced] en `false` aunque los otros dos directorios se
     * hayan sincronizado.
     */
    private fun publishDurability(keyDirectory: File): AngleImageDurability {
        val key = syncDirectoryReported(keyDirectory)
        val parent = syncDirectoryReported(imagesDirectory)
        val root = syncDirectoryReported(rootDirectory)
        return AngleImageDurability(
            keyDirectory = key,
            parentDirectory = parent,
            ancestorDirectories = listOf(root),
        )
    }

    /** `fsync` de directorio que no lanza: un backend que incumpla el contrato se reporta [FAILED]. */
    private fun syncDirectoryReported(directory: File): AngleImageDirectorySync =
        try {
            fileIo.syncDirectory(directory)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            AngleImageDirectorySync.FAILED
        } catch (_: Exception) {
            // Incluye IOException y RuntimeException del backend: durabilidad no confirmada.
            AngleImageDirectorySync.FAILED
        }

    /**
     * Comprueba el fichero **ya publicado**: regular, tamaño y SHA-256. Devuelve
     * [AngleImageStoreError.PublicationUncertain] (nunca lanza por E/S) si no cuadra, para que el
     * llamante sepa que puede existir una imagen publicada sin fila en Room.
     */
    private fun publishedFileProblem(
        imageKey: String,
        imageFile: File,
        image: StoredAngleImage,
    ): AngleImageStoreError.PublicationUncertain? =
        try {
            when {
                !call(AngleImageStoreStage.PUBLISH) { fileIo.isRegularFile(imageFile) } ->
                    AngleImageStoreError.PublicationUncertain(
                        imageKey,
                        "el fichero publicado no es un fichero regular",
                    )
                call(AngleImageStoreStage.PUBLISH) { fileIo.length(imageFile) } != image.byteCount ->
                    AngleImageStoreError.PublicationUncertain(
                        imageKey,
                        "el tamaño publicado no coincide con los bytes escritos",
                    )
                call(AngleImageStoreStage.PUBLISH) { sha256Of(imageFile, image.byteCount) } != image.sha256Hex ->
                    AngleImageStoreError.PublicationUncertain(
                        imageKey,
                        "el SHA-256 publicado no coincide con los bytes escritos",
                    )
                else -> null
            }
        } catch (abort: StoreAbort) {
            AngleImageStoreError.PublicationUncertain(
                imageKey,
                "no se pudo verificar el fichero publicado: ${abort.error}".take(MAX_DETAIL_LENGTH),
            )
        }

    /** Directorio de la clave; valida que cuelga exactamente del directorio del almacén. */
    private fun keyDirectoryFor(imageKey: String): File {
        // La clave ya es canónica, así que no puede contener separadores; esta comprobación
        // canónica es defensa en profundidad, no la única barrera. Se canonicaliza el PADRE, no la
        // ruta de la clave: resolverla seguiría un enlace simbólico y ocultaría el motivo real (el
        // enlace se rechaza explícitamente antes de leerlo o publicarlo).
        val keyDirectory = File(imagesDirectory, imageKey)
        val parent = keyDirectory.parentFile
            ?: throw StoreAbort(AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.OUTSIDE_ROOT))
        val canonicalParent = call(AngleImageStoreStage.VALIDATE_KEY) { fileIo.canonicalFile(parent) }
        val canonicalRoot = call(AngleImageStoreStage.VALIDATE_KEY) { fileIo.canonicalFile(imagesDirectory) }
        if (canonicalParent.path != canonicalRoot.path) {
            throw StoreAbort(AngleImageStoreError.UnsafePath(imageKey, AngleImagePathViolation.OUTSIDE_ROOT))
        }
        return keyDirectory
    }

    private fun imageFileFor(imageKey: String): File =
        File(keyDirectoryFor(imageKey), AngleImageFileIo.FINAL_FILE_NAME)

    private fun invalidIntegrity(expected: AngleImageIntegrity): AngleImageStoreError? = when {
        expected.byteCount < 1L -> AngleImageStoreError.InvalidIntegrityExpectation("byteCount=${expected.byteCount}")
        expected.byteCount > limits.maxImageBytes -> AngleImageStoreError.InvalidIntegrityExpectation(
            "byteCount=${expected.byteCount} supera maxImageBytes=${limits.maxImageBytes}",
        )
        !SHA256_HEX.matches(expected.sha256Hex.lowercase()) -> AngleImageStoreError.InvalidIntegrityExpectation(
            "sha256 no hexadecimal de 64 caracteres",
        )
        else -> null
    }

    /** SHA-256 de [target] leído por bloques; `null` si el fichero no tiene [expectedLength] bytes. */
    private fun sha256Of(target: File, expectedLength: Long): String? {
        val digest = newSha256()
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        fileIo.openSource(target).use { source ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > expectedLength) return null
                digest.update(buffer, 0, read)
            }
        }
        return if (total == expectedLength) digest.digest().toLowerHexString() else null
    }

    private fun newSha256(): MessageDigest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (unsupported: NoSuchAlgorithmException) {
        throw StoreAbort(
            AngleImageStoreError.IoFailure(
                AngleImageStoreStage.WRITE,
                "SHA-256 no disponible: ${unsupported.javaClass.simpleName}",
            ),
        )
    }

    companion object {
        /** Nombre del subdirectorio propio dentro del directorio raíz recibido. */
        const val IMAGE_DIRECTORY_NAME: String = "angle-images"

        /** Bloque de streaming: acota memoria a 64 KiB por operación, independientemente del fichero. */
        const val BUFFER_BYTES: Int = 64 * 1024

        private val SHA256_HEX: Regex = Regex("^[0-9a-f]{64}$")
    }
}

/** Abort interno: convierte un fallo esperado en resultado tipado sin duplicar el `finally`. */
private class StoreAbort(val error: AngleImageStoreError) : Exception(null, null, false, false)

private inline fun <T> call(stage: AngleImageStoreStage, block: () -> T): T {
    try {
        return block()
    } catch (abort: StoreAbort) {
        throw abort
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw StoreAbort(AngleImageStoreError.Canceled)
    } catch (interrupted: InterruptedIOException) {
        throw StoreAbort(AngleImageStoreError.Canceled)
    } catch (error: IOException) {
        throw StoreAbort(mapIoFailure(stage, error))
    }
}

private fun mapIoFailure(stage: AngleImageStoreStage, error: IOException): AngleImageStoreError =
    if (isNoSpace(error)) {
        AngleImageStoreError.InsufficientStorage(requiredBytes = null, usableBytes = null)
    } else {
        AngleImageStoreError.IoFailure(stage, describe(error))
    }

private fun isNoSpace(error: IOException): Boolean {
    // ENOSPC tipado por la capa Android (mkdir/rename/write): no depende del texto del mensaje.
    if (error is AngleImageNoSpaceException) return true
    val reason = (error as? FileSystemException)?.reason
    return listOfNotNull(error.message, reason).any { text ->
        text.contains("ENOSPC", ignoreCase = true) || text.contains("No space left on device", ignoreCase = true)
    }
}

private fun describe(error: Throwable): String {
    val text = "${error.javaClass.simpleName}: ${error.message ?: "sin detalle"}"
    return if (text.length <= MAX_DETAIL_LENGTH) text else text.take(MAX_DETAIL_LENGTH) + "…"
}

private const val MAX_DETAIL_LENGTH: Int = 200

/**
 * Flujo de sólo lectura acotado al tamaño verificado: el consumidor no puede leer más bytes que
 * los sellados, aunque el fichero crezca después de la verificación.
 */
private class BoundedImageInputStream(
    source: InputStream,
    private val limit: Long,
) : FilterInputStream(source) {

    private var remaining: Long = limit

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return -1
        val capped = minOf(length.toLong(), remaining).toInt()
        val read = super.read(buffer, offset, capped)
        if (read > 0) remaining -= read
        return read
    }

    override fun skip(amount: Long): Long {
        val capped = minOf(amount, remaining)
        val skipped = super.skip(capped)
        if (skipped > 0L) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun markSupported(): Boolean = false
}
