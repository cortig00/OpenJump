package com.openjump.app.video.angles

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.openjump.app.video.VideoFrameIndexer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Frontera de sesión de fuente para la captura de fotogramas.
 *
 * ## Problema que resuelve
 *
 * `MediaExtractor.setDataSource(path)` solo acepta una ruta de fichero y no permite demostrar que el
 * índice y la captura leyeron **el mismo contenido**. Un `content://` del selector de fotos, una ruta
 * reemplazada por otro editor o un proveedor que cambia el contenido entre dos aperturas producirían
 * un PTS correcto sobre píxeles de otro vídeo. La identidad que aporta el llamador
 * ([FrameSourceIdentity]) tampoco es prueba de nada: es una etiqueta.
 *
 * ## Mecanismo
 *
 * [FrozenFrameSources] abre la fuente **una sola vez** y copia su contenido a un snapshot privado en
 * `context.cacheDir/angles_source_snapshots` (nombre opaco, sin URI ni nombres de usuario). El
 * snapshot se sella con su longitud exacta y la sesión acuña una identidad nueva que no depende de lo
 * que declare el llamador. La indexación y cada captura abren un descriptor **del snapshot sellado**;
 * el productor lee con `MediaExtractor.setDataSource(fd, 0, sealedLength)`. A partir de ahí:
 *
 * - Reemplazar el `content://` original, revocar el permiso del proveedor o que otro editor escriba
 *   encima no cambia el snapshot: las capturas siguen siendo del contenido copiado.
 * - Un fichero distinto con las mismas dimensiones, duración y PTS **no** puede atribuirse al índice
 *   de esta sesión: la solicitud debe llevar la identidad acuñada por la sesión y esta comprueba
 *   además el índice sellado.
 * - La identidad de sesión no es una promesa sobre el estado externo del proveedor: acredita que
 *   índice y captura usan el mismo snapshot privado, no que el proveedor siga sirviendo ese contenido.
 *
 * ## Límites y ciclo de vida
 *
 * - Copia en streaming con tope ([FrozenFrameSourceLimits.maxSnapshotBytes]), reserva de espacio
 *   libre, comprobación de cancelación por bloque y borrado del temporal ante cualquier fallo.
 * - Derechos de offset/longitud del proveedor (`AssetFileDescriptor`) se aplican **de forma
 *   explícita**: el flujo se abre sobre el descriptor del proveedor y se reposiciona con una búsqueda
 *   absoluta en `startOffset` —**también cuando el offset declarado es `0`**, porque un descriptor
 *   de fichero puede llegar ya posicionado a mitad y el rango del `AssetFileDescriptor` es relativo
 *   al comienzo del fichero, no a la posición actual—, sin depender de la semántica de offset de
 *   `AssetFileDescriptor.createInputStream()`. Una longitud no declarada (`UNKNOWN_LENGTH` o un
 *   pipe) se copia hasta EOF y siempre con tope; en un descriptor sin búsqueda el prefijo se
 *   descarta leyendo (nunca se entrega), con tope de bytes descartados y comprobación de
 *   cancelación.
 * - **Un pipe/socket no puede bloquear la copia para siempre.** Un `read(2)` bloqueante sobre un
 *   descriptor sin búsqueda no se despierta por una bandera de cancelación ni por cerrar el
 *   descriptor, y con un escritor abierto sin datos el hilo quedaría colgado reteniendo temporal,
 *   reserva y descriptor. Por eso los descriptores no seekables se leen con sondeo (`Os.poll`) y
 *   E/S no bloqueante ([AndroidPollingSourceStreams]): el bucle comprueba la cancelación cada
 *   [PollingSourceStream.DEFAULT_POLL_TIMEOUT_MILLIS], trata `EAGAIN` volviendo a sondear y cierra
 *   el descriptor al salir por cualquier camino. No se afirma que `Future.cancel()` interrumpa una
 *   lectura: se evita emitir la lectura bloqueante. El `O_NONBLOCK` se activa sobre el extremo de
 *   lectura entregado por el proveedor y se restaura antes de cerrar; el extremo de escritura del
 *   pipe es otra *open file description* y conserva su semántica. Como `O_NONBLOCK` pertenece a la
 *   *open file description* y los `dup` del mismo extremo la comparten, sólo puede haber **un flujo
 *   no seekable en vuelo por proceso**: el permiso se toma antes de `F_GETFL` y se libera después de
 *   restaurar y cerrar, y una segunda captura no seekable se rechaza con
 *   [SourceOpenFailure.SOURCE_BUSY] (recuperable, sin esperar un hilo) en lugar de pisar banderas
 *   compartidas. Los descriptores seekables no pasan por ese permiso. **Versión de plataforma:** el
 *   sondeo no bloqueante usa `Os.fcntlInt`, que solo está en la superficie pública del SDK desde API
 *   30; con `minSdk` 26, en API 26–29 un pipe/socket se rechaza con
 *   [SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED] antes de tocar el descriptor (que sigue siendo del
 *   llamador y no se cierra ahí) y sin tomar ningún permiso, en lugar de emitir una lectura
 *   bloqueante sin cancelación demostrable. La alternativa que se ofrece es una copia local del
 *   vídeo; los ficheros seekables no se ven afectados en ninguna versión. Si el proveedor no expone
 *   `ParcelFileDescriptor` (caso raro) queda la vía `createInputStream()`, que no puede ofrecer
 *   cancelación dentro de una lectura: es un límite residual documentado, no una promesa. Al
 *   cancelar se cierra **nuestro** extremo de lectura (nunca un descriptor propiedad del
 *   proveedor más allá del `AssetFileDescriptor` que ya se cerraba siempre): si el proveedor sigue
 *   escribiendo en un pipe, su propio hilo puede observar `EPIPE`/`IOException` al vaciarse el
 *   búfer, que es el resultado de flujo habitual de una cancelación y no un recurso liberado por
 *   nosotros mientras otro hilo lo usa.
 * - `close()` es idempotente y borra el snapshot. Un barrido oportunista elimina snapshots de sesiones
 *   muertas (más de 24 h) al abrir otra fuente.
 * - **El barrido nunca borra un fichero reservado.** Cada copia/sesión registra una reserva de
 *   proceso ([SnapshotLease]) sobre su ruta antes de escribirla; el barrido comprueba la reserva y la
 *   edad bajo el mismo cerrojo con el que borra, así que ni un reloj adelantado ni una carrera de
 *   apertura/cierre pueden eliminar el snapshot de una sesión viva o de una copia en curso. Lo que sí
 *   se barre son restos de procesos muertos (no reservados y más antiguos que el umbral), que es
 *   exactamente el caso de un proceso interrumpido.
 * - Cerrar una sesión durante una captura **no** borra el fichero bajo los pies del descriptor: el
 *   borrado físico se difiere a la última captura en vuelo y, aun así, la captura no entrega imagen
 *   tras el cierre. Ni `request` ni la apertura del descriptor lanzan excepciones de estado: devuelven
 *   un rechazo tipado.
 * - Permiso revocado **después** de abrir: la sesión ya copió el contenido y sigue siendo utilizable;
 *   una apertura nueva falla con [SourceOpenFailure.PERMISSION_DENIED].
 * - El snapshot reside en caché privada de la app; no se expone por FileProvider ni se publica.
 */

/** Fallo tipado al abrir una fuente. Nunca se degrada a "capturar otro fotograma". */
enum class SourceOpenFailure {
    /** URI o ruta vacía. */
    BLANK_SOURCE,

    /** Esquema no soportado (solo se aceptan `content` y `file`). */
    UNSUPPORTED_SOURCE,

    /** El proveedor denegó el acceso (permiso revocado o grant ausente). */
    PERMISSION_DENIED,

    /**
     * Fallo de E/S, de entorno o **ambiguo** al abrir/copiar/indexar la fuente y su snapshot.
     *
     * También cubre cualquier `IOException` del `MediaExtractor`, coincida o no la cabecera con una
     * firma conocida: Android no permite distinguir ahí un contenido no soportado o inválido de un
     * fallo interno del extractor o de E/S (`initMediaExtractor` puede fallar sin hablar del
     * contenido), así que se falla cerrado sin atribuir el fallo ni al contenido ni al formato.
     */
    SOURCE_UNAVAILABLE,

    /**
     * Ya hay otra captura no seekable (pipe/socket) en vuelo en este proceso.
     *
     * `O_NONBLOCK` vive en la *open file description*, que los `dup` del mismo extremo comparten: dos
     * adaptadores simultáneos se pisar\u00edan las banderas al cerrar (uno dejar\u00eda la lectura
     * bloqueante y el \u00faltimo, `O_NONBLOCK` activado permanentemente). La apertura concurrente se
     * rechaza antes de tocar el descriptor en lugar de esperar.
     *
     * Es **recuperable**: no describe el contenido ni el proveedor, y el llamador puede reintentar
     * cuando la captura en vuelo termine. No niega pipes normales: una \u00fanica captura no seekable
     * funciona igual que antes.
     */
    SOURCE_BUSY,

    /**
     * El origen es un pipe/socket (no seekable) en una versión de Android que no expone las banderas
     * no bloqueantes en la **superficie pública** del SDK (API 26–29).
     *
     * `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) se publicó en API 30; con `minSdk` 26 y sin reflexión ni API
     * oculta no hay forma pública de activar `O_NONBLOCK`, y sin esa bandera una lectura podría quedar
     * bloqueada sin cancelación demostrable. La apertura se rechaza **antes** de tocar el descriptor,
     * que sigue siendo propiedad del llamador; no es un fallo del proveedor ni del contenido y no
     * implica que el vídeo sea inválido. El llamador debe ofrecer una copia local del vídeo.
     */
    NON_SEEKABLE_UNSUPPORTED,

    /** La fuente declara o entrega más bytes que el tope de snapshot. */
    SOURCE_TOO_LARGE,

    /** No hay espacio libre suficiente, el directorio no es escribible o falló la escritura. */
    STORAGE_UNAVAILABLE,

    /** La copia o el sellado fallaron (p. ej. el proveedor entregó menos bytes de los declarados). */
    COPY_FAILED,

    /** La apertura se canceló antes de sellar el snapshot. */
    CANCELLED,

    /**
     * El extractor afirma que el contenido copiado no ofrece un track de vídeo utilizable.
     *
     * Sólo se devuelve con un veredicto estructural demostrable sobre bytes ya entregados al
     * extractor (ausencia de track de vídeo declarada por `VideoFrameIndexer`). Nunca por una
     * `IOException`, ni por una cabecera no reconocida: ninguna de las dos prueba nada sobre el
     * formato. Los límites del índice se tipan como [SOURCE_TOO_LARGE].
     */
    INVALID_VIDEO,
}

/** Límites de la copia de seguridad de la fuente. Los valores son guardarraíles, no promesas de producto. */
data class FrozenFrameSourceLimits(
    val maxSnapshotBytes: Long = DEFAULT_MAX_SNAPSHOT_BYTES,
    val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    val copyBufferBytes: Int = DEFAULT_COPY_BUFFER_BYTES,
) {
    init {
        require(maxSnapshotBytes in 1..MAX_ALLOWED_SNAPSHOT_BYTES) { "Tope de snapshot no válido." }
        require(freeSpaceReserveBytes in 0..MAX_ALLOWED_FREE_SPACE) { "Reserva de espacio no válida." }
        require(copyBufferBytes in 4 * 1024..1024 * 1024) { "Tamaño de bloque de copia no válido." }
    }

    companion object {
        /** 2 GiB: guardarraíl contra copias desbocadas, configurable por el llamador. */
        const val DEFAULT_MAX_SNAPSHOT_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_ALLOWED_SNAPSHOT_BYTES = 16L * 1024L * 1024L * 1024L
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
        const val MAX_ALLOWED_FREE_SPACE = 1024L * 1024L * 1024L
        const val DEFAULT_COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** Resultado de abrir una fuente: sesión sellada o fallo tipado. */
sealed interface FrozenFrameSourceOpen {

    data class Opened(val session: FrozenFrameSourceSession) : FrozenFrameSourceOpen

    data class Unavailable(
        val failure: SourceOpenFailure,
        val detail: String,
    ) : FrozenFrameSourceOpen
}

/** Resultado interno de copiar y sellar el snapshot. */
internal sealed interface SnapshotSealResult {

    data class Sealed(val snapshot: SealedSourceSnapshot) : SnapshotSealResult

    data class Failed(val failure: SourceOpenFailure, val detail: String) : SnapshotSealResult
}

/** Snapshot privado ya sellado: ruta opaca, longitud exacta e identidad acuñada. */
internal data class SealedSourceSnapshot(
    val file: File,
    val lengthBytes: Long,
    val identity: FrameSourceIdentity,
    /** Reserva de proceso que impide que el barrido borre este fichero mientras viva la sesión. */
    val lease: SnapshotLease,
) {
    /**
     * Descarta el snapshot sellado sin sesión: borra el fichero y libera la reserva. Si el borrado
     * falla, la reserva se libera igualmente para que un barrido posterior pueda reintentarlo; el
     * snapshot nunca queda reservado para siempre.
     */
    fun discard() {
        runCatching { file.delete() }
        lease.release()
    }
}

/**
 * Reserva de proceso sobre la ruta de un snapshot propio.
 *
 * Existe porque el barrido por edad no puede distinguir una sesión viva de un resto de proceso
 * muerto mirando `lastModified`: un reloj adelantado, una restauración de backup o el paso de 24 h
 * con una sesión abierta harían desaparecer el fichero que una captura en curso va a abrir. La
 * reserva se toma **antes** de crear el fichero (nombre ya fijado) y se libera al cerrar la sesión o
 * al descartar la copia; el registro es de proceso, así que lo que sobreviva a la muerte del proceso
 * queda sin reserva y el barrido puede limpiarlo.
 */
internal class SnapshotLease internal constructor(initialFile: File) {

    @Volatile
    private var leasedFile: File = initialFile

    private val released = AtomicBoolean(false)

    val file: File get() = leasedFile

    val isReleased: Boolean get() = released.get()

    internal fun retarget(target: File) {
        leasedFile = target
    }

    /** Libera la reserva; idempotente. */
    fun release() {
        val target = leasedFile
        if (released.compareAndSet(false, true)) SnapshotLeases.unpin(target)
    }
}

/** Registro de proceso de snapshots reservados. Todas las operaciones comparten un único cerrojo. */
internal object SnapshotLeases {

    private val lock = Any()
    private val pinned = HashMap<String, Int>()

    /** Reserva [file] (que puede no existir todavía, pero ya tiene nombre fijo). */
    fun pin(file: File): SnapshotLease = synchronized(lock) {
        pinned.merge(keyOf(file), 1, Int::plus)
        SnapshotLease(file)
    }

    /**
     * Publica el snapshot: renombra [source]→[target] y traslada la reserva, **todo bajo el mismo
     * cerrojo** con el que [deleteIfUnpinned] decide y con doble reserva.
     *
     * El orden importa y es el motivo de que esta operación exista: reservar el destino **antes** del
     * renombrado y hacer el renombrado dentro del cerrojo cierra la ventana en la que el fichero ya
     * existía en su ruta final pero todavía no tenía reserva. En esa ventana, un barrido concurrente
     * con reloj adelantado (o el de otra apertura) lo veía viejo y sin reserva, y lo borraba. Ahora
     * cualquier barrido posterior a la aparición del destino ve la reserva del destino; antes de ella
     * el fichero no existe. Si el renombrado falla no se publica nada, se retira la reserva doble y la
     * reserva original sobre [source] queda intacta para que el `finally` de la copia la libere.
     *
     * [publicationProbe] es un punto de interleaving determinista **solo para pruebas**: se ejecuta
     * dentro del cerrojo, después del renombrado y antes de retirar la reserva del origen, que es el
     * instante exactamente susceptible. Debe ser rápido y no lanzar; si lanzase, el `finally` deja la
     * reserva trasladada al destino y la propaga al llamador.
     *
     * @return `true` solo si el fichero existe en [target] con la reserva ya trasladada allí.
     */
    internal fun publish(
        lease: SnapshotLease,
        source: File,
        target: File,
        publicationProbe: (() -> Unit)? = null,
    ): Boolean = synchronized(lock) {
        val sourceKey = keyOf(source)
        val targetKey = keyOf(target)
        // La reserva de la copia debe ser la del origen y el destino no puede ser la misma ruta.
        if (targetKey == sourceKey || keyOf(lease.file) != sourceKey) return@synchronized false
        // Sin sobrescritura: el nombre lo acuña la sesión, pero un destino preexistente no se pisa.
        if (target.exists()) return@synchronized false
        pinned.merge(targetKey, 1, Int::plus)
        if (!source.renameTo(target)) {
            decrement(targetKey)
            return@synchronized false
        }
        try {
            publicationProbe?.invoke()
        } finally {
            decrement(sourceKey)
            lease.retarget(target)
        }
        true
    }

    internal fun unpin(file: File) {
        synchronized(lock) { decrement(keyOf(file)) }
    }

    fun isPinned(file: File): Boolean = synchronized(lock) { (pinned[keyOf(file)] ?: 0) > 0 }

    /** Nº total de reservas vivas. Solo para pruebas: acredita que un fallo no deja reservas colgadas. */
    internal fun pinnedCount(): Int = synchronized(lock) { pinned.values.sum() }

    /**
     * Borra [file] solo si no está reservado y supera [maxAgeMillis] de antigüedad. La comprobación de
     * reserva, la de edad y el borrado ocurren bajo el mismo cerrojo: una reserva tomada después de
     * listar el directorio no puede colarse entre la comprobación y el borrado.
     */
    fun deleteIfUnpinned(file: File, nowMillis: Long, maxAgeMillis: Long): Boolean = synchronized(lock) {
        if ((pinned[keyOf(file)] ?: 0) > 0) return@synchronized false
        if (!file.isFile) return@synchronized false
        if (nowMillis - file.lastModified() < maxAgeMillis) return@synchronized false
        file.delete()
    }

    internal fun keyOf(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun decrement(key: String) {
        val remaining = (pinned[key] ?: return) - 1
        if (remaining <= 0) pinned.remove(key) else pinned[key] = remaining
    }
}

/**
 * Copia una fuente a un snapshot privado con tope, reserva de espacio y cancelación por bloque.
 * Es puro Kotlin (sin Android) para poder probar límites, offsets y cancelación en JVM.
 */
internal object SourceSnapshotCopier {

    const val SNAPSHOT_PREFIX = "snapshot_"
    const val TEMP_PREFIX = "copy_"

    /**
     * Copia [input] (ya posicionado al inicio del contenido de la fuente) en [snapshotDirectory].
     *
     * [declaredLengthBytes] es la longitud declarada por el proveedor o [UNKNOWN_LENGTH] (`-1`). Si
     * está declarada se copian exactamente esos bytes; si el flujo termina antes, falla en lugar de
     * sellar un snapshot truncado. Si no está declarada se copia hasta EOF, siempre con el tope.
     */
    fun copy(
        input: InputStream,
        declaredLengthBytes: Long,
        snapshotDirectory: File,
        limits: FrozenFrameSourceLimits = FrozenFrameSourceLimits(),
        cancellationCheck: () -> Boolean = { false },
        publicationProbe: (() -> Unit)? = null,
    ): SnapshotSealResult {
        if (declaredLengthBytes < UNKNOWN_LENGTH) {
            return SnapshotSealResult.Failed(SourceOpenFailure.COPY_FAILED, "Longitud declarada negativa no válida.")
        }
        if (declaredLengthBytes > limits.maxSnapshotBytes) {
            return SnapshotSealResult.Failed(
                SourceOpenFailure.SOURCE_TOO_LARGE,
                "La fuente declara $declaredLengthBytes bytes y el tope es ${limits.maxSnapshotBytes}.",
            )
        }
        if (!ensureDirectory(snapshotDirectory)) {
            return SnapshotSealResult.Failed(
                SourceOpenFailure.STORAGE_UNAVAILABLE,
                "No se pudo preparar el directorio privado del snapshot.",
            )
        }
        if (!hasFreeSpace(snapshotDirectory, declaredLengthBytes, limits)) {
            return SnapshotSealResult.Failed(
                SourceOpenFailure.STORAGE_UNAVAILABLE,
                "No hay espacio libre suficiente para el snapshot en caché privada.",
            )
        }

        val temp = File(snapshotDirectory, "$TEMP_PREFIX${UUID.randomUUID()}.bin")
        // La reserva se toma ANTES de crear el fichero: si el barrido lista el directorio en este
        // instante, ya no puede borrarlo aunque un reloj adelantado lo haga "viejo".
        val lease = SnapshotLeases.pin(temp)
        var leaseTransferred = false
        var total = 0L
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(limits.copyBufferBytes)
                while (true) {
                    if (cancellationCheck()) {
                        return SnapshotSealResult.Failed(SourceOpenFailure.CANCELLED, "La copia de la fuente se canceló.")
                    }
                    val remaining = if (declaredLengthBytes == UNKNOWN_LENGTH) {
                        buffer.size.toLong()
                    } else {
                        (declaredLengthBytes - total).coerceAtMost(buffer.size.toLong())
                    }
                    if (remaining <= 0L) break
                    val read = try {
                        input.read(buffer, 0, remaining.toInt())
                    } catch (error: SourceReadCancelled) {
                        // Cancelación observada dentro de la lectura no bloqueante: se traduce a
                        // CANCELLED en lugar de atribuirla a un fallo ambiguo del proveedor.
                        return SnapshotSealResult.Failed(
                            SourceOpenFailure.CANCELLED,
                            error.message ?: "La copia de la fuente se canceló.",
                        )
                    } catch (error: IOException) {
                        return SnapshotSealResult.Failed(
                            SourceOpenFailure.SOURCE_UNAVAILABLE,
                            "El proveedor falló al leer la fuente (${error.javaClass.simpleName}).",
                        )
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > limits.maxSnapshotBytes) {
                        return SnapshotSealResult.Failed(
                            SourceOpenFailure.SOURCE_TOO_LARGE,
                            "La fuente entrega más de ${limits.maxSnapshotBytes} bytes.",
                        )
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: IOException) {
                        return SnapshotSealResult.Failed(
                            if (!hasFreeSpace(snapshotDirectory, 0L, limits)) {
                                SourceOpenFailure.STORAGE_UNAVAILABLE
                            } else {
                                SourceOpenFailure.COPY_FAILED
                            },
                            "Falló la escritura del snapshot (${error.javaClass.simpleName}).",
                        )
                    }
                }
                try {
                    output.fd.sync()
                } catch (_: IOException) {
                    // La durabilidad física no es un requisito del contrato: el snapshot se usa en
                    // esta sesión; si el proceso muere se descarta y se vuelve a abrir la fuente.
                }
            }
            if (declaredLengthBytes != UNKNOWN_LENGTH && total != declaredLengthBytes) {
                return SnapshotSealResult.Failed(
                    SourceOpenFailure.COPY_FAILED,
                    "La fuente declaró $declaredLengthBytes bytes y solo entregó $total.",
                )
            }
            if (total <= 0L) {
                return SnapshotSealResult.Failed(SourceOpenFailure.COPY_FAILED, "La fuente no entregó contenido.")
            }
            val sealed = File(snapshotDirectory, "$SNAPSHOT_PREFIX${UUID.randomUUID()}.mp4")
            // Publicación y traslado de la reserva indivisibles bajo el cerrojo del barrido: el
            // destino queda reservado antes de existir y nunca hay un instante en el que la ruta
            // final sea visible sin reserva (ver SnapshotLeases.publish).
            if (!SnapshotLeases.publish(lease, temp, sealed, publicationProbe)) {
                return SnapshotSealResult.Failed(
                    SourceOpenFailure.COPY_FAILED,
                    "No se pudo sellar el snapshot privado.",
                )
            }
            leaseTransferred = true
            // Endurecimiento best-effort: el vínculo real es el descriptor abierto sobre este
            // fichero dentro del directorio privado, no los permisos POSIX.
            sealed.setReadOnly()
            return SnapshotSealResult.Sealed(
                SealedSourceSnapshot(
                    file = sealed,
                    lengthBytes = total,
                    identity = FrameSourceIdentity("snapshot:${sealed.name.removePrefix(SNAPSHOT_PREFIX)}:$total"),
                    lease = lease,
                ),
            )
        } catch (error: IOException) {
            // Abrir/escribir el temporal puede fallar (directorio purgado, sin espacio, permisos):
            // se convierte en fallo tipado en lugar de escapar como excepción del sistema.
            return SnapshotSealResult.Failed(
                if (!hasFreeSpace(snapshotDirectory, 0L, limits)) {
                    SourceOpenFailure.STORAGE_UNAVAILABLE
                } else {
                    SourceOpenFailure.COPY_FAILED
                },
                "No se pudo escribir el snapshot privado (${error.javaClass.simpleName}).",
            )
        } finally {
            if (temp.exists()) temp.delete()
            if (!leaseTransferred) lease.release()
        }
    }

    private fun ensureDirectory(directory: File): Boolean =
        directory.isDirectory || directory.mkdirs() || directory.isDirectory

    private fun hasFreeSpace(directory: File, declaredLengthBytes: Long, limits: FrozenFrameSourceLimits): Boolean {
        val required = if (declaredLengthBytes == UNKNOWN_LENGTH) {
            limits.freeSpaceReserveBytes
        } else {
            declaredLengthBytes + limits.freeSpaceReserveBytes
        }
        val usable = directory.usableSpace
        // usableSpace puede ser 0 en sistemas que no la exponen; no se usa como garantía absoluta.
        return usable <= 0L || usable >= required
    }

    /**
     * Borra snapshots/temporales propios más antiguos que [maxAgeMillis]; nunca toca otros ficheros.
     *
     * Sólo borra ficheros regulares con prefijo propio, cuyo directorio padre canónico es
     * [snapshotDirectory] (nunca un directorio ni una ruta fuera del directorio propio) y que **no**
     * estén reservados por una copia o sesión viva ([SnapshotLeases]). La edad se mide con
     * [nowMillis]; un reloj adelantado hace viejos los ficheros, por eso la reserva —y no la edad— es
     * lo que protege a una sesión activa.
     */
    fun sweepStale(
        snapshotDirectory: File,
        nowMillis: Long,
        maxAgeMillis: Long,
        maxFiles: Int = 16,
    ): Int {
        if (!snapshotDirectory.isDirectory) return 0
        val directoryKey = SnapshotLeases.keyOf(snapshotDirectory)
        val files = snapshotDirectory.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (deleted >= maxFiles) break
            val name = file.name
            if (!name.startsWith(SNAPSHOT_PREFIX) && !name.startsWith(TEMP_PREFIX)) continue
            // Nunca se sigue un enlace ni se borra un subdirectorio: solo ficheros regulares del
            // propio directorio de snapshots.
            if (!file.isFile) continue
            val parent = file.parentFile ?: continue
            if (SnapshotLeases.keyOf(parent) != directoryKey) continue
            if (SnapshotLeases.deleteIfUnpinned(file, nowMillis, maxAgeMillis)) deleted += 1
        }
        return deleted
    }

    /** Valor de `AssetFileDescriptor.UNKNOWN_LENGTH` sin depender del stub de Android en JVM. */
    const val UNKNOWN_LENGTH = -1L
}

/**
 * Vínculo puro entre una sesión de fuente (identidad + índice sellado) y una solicitud.
 *
 * Es la pieza que impide atribuir a una sesión la solicitud de otra fuente aun cuando el índice (PTS,
 * dimensiones, rotación y número de frames) sea idéntico. La identidad del llamador no se cree: debe
 * ser exactamente la acuñada por la sesión.
 */
class FrameSourceBinding(
    val identity: FrameSourceIdentity,
    val index: VideoFrameIndexSnapshot,
) {

    fun validate(request: FrameFreezeRequest): FrameFreezeResult.Blocked? {
        if (request.sourceIdentity != identity) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                "La identidad de la solicitud no es la acuñada por esta sesión: misma metadata no acredita el mismo contenido.",
            )
        }
        return CodecCapturePolicy.validateRequest(request, index)
    }
}

/**
 * Estado de vida de una sesión de fuente y de su snapshot.
 *
 * Resuelve dos carreras que un simple `AtomicBoolean` no cubre:
 *
 * - **Cierre durante una captura:** cerrar marca la sesión como cerrada, pero el fichero solo se borra
 *   cuando la última captura en vuelo termina. Así el descriptor abierto por esa captura sigue
 *   apuntando a un fichero válido (nunca a una ruta recreada con otro contenido) y el borrado ocurre
 *   igualmente: la limpieza no se pierde.
 * - **Apertura después del cierre:** [openCapture] devuelve `false` y el llamador entrega un rechazo
 *   tipado en lugar de lanzar `IllegalStateException`.
 *
 * Es Kotlin puro (sin Android) para poder probar las carreras de forma determinista en JVM.
 */
internal class SourceSessionLifecycle(
    private val file: File,
    private val lease: SnapshotLease,
) {

    private val lock = Any()
    private var closed = false
    private var activeCaptures = 0
    private var snapshotDiscarded = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Capturas en vuelo; expuesto para diagnósticos y pruebas deterministas. */
    val activeCaptureCount: Int get() = synchronized(lock) { activeCaptures }

    /** `true` si la captura puede empezar; en caso contrario la sesión ya estaba cerrada. */
    fun openCapture(): Boolean = synchronized(lock) {
        if (closed) {
            false
        } else {
            activeCaptures += 1
            true
        }
    }

    /** Fin de una captura: si la sesión ya se cerró, este es el punto donde se borra el snapshot. */
    fun releaseCapture() {
        synchronized(lock) {
            if (activeCaptures > 0) activeCaptures -= 1
            if (closed && activeCaptures == 0) discardLocked()
        }
    }

    /** Cierra la sesión. Devuelve `true` si el snapshot se borró en este momento. */
    fun close(): Boolean = synchronized(lock) {
        closed = true
        activeCaptures == 0 && discardLocked()
    }

    /** Descarta el snapshot sin sesión abierta (fallo al sellar o al indexar). */
    fun discard() {
        synchronized(lock) { discardLocked() }
    }

    private fun discardLocked(): Boolean {
        if (snapshotDiscarded) return false
        snapshotDiscarded = true
        runCatching { file.delete() }
        // Se libera la reserva incluso si el borrado falló: el barrido podrá reintentarlo, pero el
        // fichero no queda reservado para siempre por una sesión muerta.
        lease.release()
        return true
    }
}

/** Resultado de pedir un frame a una sesión: solicitud sellada o rechazo tipado (nunca excepción). */
sealed interface FrameSourceRequestResult {

    data class Granted(val request: FrameFreezeRequest) : FrameSourceRequestResult

    data class Blocked(
        val reason: FrameFreezeRejectionReason,
        val detail: String,
    ) : FrameSourceRequestResult
}

/** Resultado de abrir el descriptor del snapshot sellado. */
internal sealed interface SnapshotDescriptorOpen {

    data class Opened(val descriptor: ParcelFileDescriptor) : SnapshotDescriptorOpen

    data class Blocked(val result: FrameFreezeResult.Blocked) : SnapshotDescriptorOpen
}

/**
 * Sesión de una fuente congelada: índice y captura se abren siempre sobre el mismo snapshot privado
 * sellado. Las solicitudes válidas nacen de [request]; [capture] no acepta rutas ni URIs.
 */
class FrozenFrameSourceSession internal constructor(
    val identity: FrameSourceIdentity,
    val index: VideoFrameIndexSnapshot,
    internal val snapshot: SealedSourceSnapshot,
) : AutoCloseable {

    val snapshotLengthBytes: Long get() = snapshot.lengthBytes

    internal val binding = FrameSourceBinding(identity, index)

    private val lifecycle = SourceSessionLifecycle(snapshot.file, snapshot.lease)

    val isOpen: Boolean get() = !lifecycle.isClosed

    /** Capturas en vuelo sobre esta sesión (diagnóstico y pruebas de cierre/captura). */
    internal val activeCaptures: Int get() = lifecycle.activeCaptureCount

    /**
     * Solicitud para un frame del índice sellado, con la identidad de esta sesión.
     *
     * Devuelve un rechazo tipado si la sesión ya se cerró o el índice no contiene ese frame: una
     * excepción de estado que escapara a la UI no es un camino aceptable.
     */
    fun request(frameIndex: Int): FrameSourceRequestResult {
        if (lifecycle.isClosed) {
            return FrameSourceRequestResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                "La sesión de fuente está cerrada.",
            )
        }
        if (frameIndex !in 0 until index.frameCount) {
            return FrameSourceRequestResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "El frame solicitado está fuera del índice sellado.",
            )
        }
        return FrameSourceRequestResult.Granted(
            FrameFreezeRequest(
                sourceIdentity = identity,
                frameIndex = frameIndex,
                assignedPtsUs = index.ptsAt(frameIndex),
                indexFrameCount = index.frameCount,
                encodedWidth = index.encodedWidth,
                encodedHeight = index.encodedHeight,
                declaredRotationDegrees = index.declaredRotationDegrees,
            ),
        )
    }

    fun capture(
        request: FrameFreezeRequest,
        cancellationCheck: () -> Boolean = { false },
    ): FrameFreezeResult {
        if (!lifecycle.openCapture()) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                "La sesión de fuente está cerrada.",
            )
        }
        try {
            val result = CodecFrozenFrameProducer().capture(this, request, cancellationCheck)
            // Puerta final de cierre: una sesión cerrada durante la captura no entrega ninguna imagen.
            // La imagen ya producida se libera aquí; el snapshot se borra al liberar esta captura.
            if (lifecycle.isClosed) {
                (result as? FrameFreezeResult.Frozen)?.image?.close()
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "La sesión se cerró durante la captura: no se entrega la imagen.",
                )
            }
            return result
        } finally {
            lifecycle.releaseCapture()
        }
    }

    /**
     * Descriptor de solo lectura del snapshot sellado. Verifica que la ruta sigue siendo el fichero
     * sellado (longitud exacta) y vuelve a comprobar el tamaño del descriptor abierto.
     *
     * No lanza excepciones de estado ni de E/S hacia el llamador: la sesión cerrada, el snapshot
     * purgado de la caché o un fichero de longitud distinta se devuelven como rechazo tipado.
     */
    internal fun openSnapshotDescriptor(): SnapshotDescriptorOpen {
        if (lifecycle.isClosed) {
            return SnapshotDescriptorOpen.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "La sesión de fuente está cerrada.",
                ),
            )
        }
        val file = snapshot.file
        if (!file.isFile || file.length() != snapshot.lengthBytes) {
            // Caché purgada por el sistema o fichero sustituido: la sesión no puede seguir usando esa
            // ruta como fuente del índice sellado.
            return SnapshotDescriptorOpen.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "El snapshot privado falta o no coincide con la longitud sellada.",
                ),
            )
        }
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: IOException) {
            return SnapshotDescriptorOpen.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "No se pudo abrir el descriptor del snapshot privado.",
                ),
            )
        } catch (_: SecurityException) {
            return SnapshotDescriptorOpen.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "El snapshot privado no es legible.",
                ),
            )
        }
        val statSize = runCatching { descriptor.statSize }.getOrDefault(-1L)
        if (statSize >= 0L && statSize != snapshot.lengthBytes) {
            runCatching { descriptor.close() }
            return SnapshotDescriptorOpen.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                    "El descriptor del snapshot no coincide con la longitud sellada.",
                ),
            )
        }
        return SnapshotDescriptorOpen.Opened(descriptor)
    }

    override fun close() {
        // Si hay una captura en vuelo, el borrado se difiere a su liberación (ver
        // [SourceSessionLifecycle]): el fichero no desaparece bajo el descriptor abierto.
        lifecycle.close()
    }
}

/**
 * Apertura de fuentes de vídeo sin confiar en rutas: copia una vez a un snapshot privado y lo indexa.
 *
 * No se conecta todavía a la UI ni sustituye al selector; esta capa solo fija la frontera que usará
 * la sesión de análisis.
 */
object FrozenFrameSources {

    /** Subdirectorio de caché privada; los nombres de fichero son opacos (UUID), sin datos de usuario. */
    const val SNAPSHOT_DIRECTORY = "angles_source_snapshots"

    private const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    /**
     * Abre un `content://` o `file://` real a través de [ContentResolver] y lo sella.
     *
     * Respeta `startOffset` y `length` del proveedor (`AssetFileDescriptor`); un pipe o una longitud
     * desconocida se copia hasta EOF con el tope de [limits]. La URI original no se guarda.
     */
    fun openFromUri(
        context: Context,
        uri: Uri,
        limits: FrozenFrameSourceLimits = FrozenFrameSourceLimits(),
        cancellationCheck: () -> Boolean = { false },
    ): FrozenFrameSourceOpen {
        if (uri.toString().isBlank()) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.BLANK_SOURCE, "La URI de la fuente está vacía.")
        }
        val scheme = uri.scheme
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) {
            return FrozenFrameSourceOpen.Unavailable(
                SourceOpenFailure.UNSUPPORTED_SOURCE,
                "Solo se aceptan fuentes content:// o file:// (recibido ${scheme ?: "sin esquema"}).",
            )
        }
        if (cancellationCheck()) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.CANCELLED, "La apertura se canceló.")
        }
        val asset = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (_: SecurityException) {
            return FrozenFrameSourceOpen.Unavailable(
                SourceOpenFailure.PERMISSION_DENIED,
                "El proveedor denegó el acceso a la fuente.",
            )
        } catch (_: IOException) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.SOURCE_UNAVAILABLE, "No se pudo abrir la fuente.")
        } catch (_: IllegalArgumentException) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.SOURCE_UNAVAILABLE, "URI de fuente no válida.")
        } ?: return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.SOURCE_UNAVAILABLE, "El proveedor no abrió la fuente.")

        return sealFromAssetDescriptor(context, asset, limits, cancellationCheck)
    }

    /**
     * Sella e indexa un [AssetFileDescriptor] ya abierto por el proveedor.
     *
     * Es la ruta única de [openFromUri] y también la costura que usan las pruebas instrumentadas con
     * un `ContentProvider` de test (evita exportar un componente de prueba para llegar al mismo
     * código). Aplica `startOffset` y `length` declarados por el proveedor:
     *
     * - **Offset:** se posiciona el descriptor con una búsqueda **absoluta** en `startOffset` antes de
     *   leer, también cuando el offset declarado es `0`: un descriptor de fichero seekable puede
     *   entregarse ya posicionado en mitad del fichero y el rango del `AssetFileDescriptor` es
     *   relativo al comienzo del fichero, no a la posición actual. No se delega en
     *   `AssetFileDescriptor.createInputStream()` porque su tratamiento de offset/longitud no está
     *   garantizado por el contrato público y no es demostrable sin dispositivo; aquí el offset se
     *   aplica de forma comprobable y la prueba instrumentada compara los bytes del snapshot con el
     *   fixture y adjunta bytes centinela fuera del rango. Si el descriptor no admite búsqueda (pipe),
     *   el prefijo declarado se descarta leyendo —con tope de bytes, sondeo no bloqueante y
     *   cancelación— y nunca llega al snapshot; el rango sigue sin aceptar bytes fuera de él. En ese
     *   caso la lectura no puede quedarse esperando indefinidamente a un proveedor sin datos: el
     *   sondeo acota la latencia de cancelación y el descriptor se cierra al salir.
     * - **Longitud:** [SourceSnapshotCopier] copia exactamente los bytes declarados y falla
     *   ([SourceOpenFailure.COPY_FAILED]) si el proveedor entrega menos: nunca se sella un snapshot
     *   truncado ni se presenta un prefijo como contenido. `UNKNOWN_LENGTH` se copia hasta EOF con
     *   tope.
     *
     * El descriptor y su flujo se cierran siempre; no hay ruta que deje el `AssetFileDescriptor`
     * abierto tras un fallo tipado. La única excepción deliberada es el **rechazo de plataforma** de un
     * descriptor no seekable en API 26–29 ([SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED]): el adaptador
     * falla antes de tocar el descriptor, que no se cierra en esa rama (la propiedad sigue en el
     * `AssetFileDescriptor` del llamador), y este `finally` lo cierra como en cualquier otra salida.
     */
    internal fun sealFromAssetDescriptor(
        context: Context,
        asset: AssetFileDescriptor,
        limits: FrozenFrameSourceLimits = FrozenFrameSourceLimits(),
        cancellationCheck: () -> Boolean = { false },
    ): FrozenFrameSourceOpen = try {
        val stream = try {
            openDeclaredStream(asset, limits, cancellationCheck)
        } catch (error: IOException) {
            // El tipo de excepción decide el fallo: una cancelación dentro de la E/S, una apertura no
            // seekable concurrente y un origen no seekable en API 26–29 tienen su propio código; el
            // resto es E/S ambigua y no se atribuye ni al contenido ni al formato.
            val rejection = sourceStreamRejection(error)
            return FrozenFrameSourceOpen.Unavailable(rejection.failure, rejection.detail)
        } catch (_: IllegalArgumentException) {
            // La vía de respaldo (`createInputStream()`, sin `ParcelFileDescriptor`) rechaza
            // combinaciones de offset/longitud que no puede representar: se entrega un fallo tipado en
            // lugar de dejar escapar una excepción del sistema hacia el llamador.
            return FrozenFrameSourceOpen.Unavailable(
                SourceOpenFailure.SOURCE_UNAVAILABLE,
                "El proveedor declaró un offset/longitud que no se pueden aplicar de forma verificable.",
            )
        }
        stream.use {
            sealAndIndex(context, it, asset.length.toLong(), limits, cancellationCheck)
        }
    } finally {
        // El flujo de auto-cierre cierra el descriptor; un segundo cierre es best-effort.
        runCatching { asset.close() }
    }

    /**
     * Abre el flujo del descriptor y lo posiciona en el `startOffset` **declarado por el proveedor**
     * con una búsqueda absoluta, también cuando el offset declarado es `0`.
     *
     * El rango de un `AssetFileDescriptor` es relativo al comienzo del fichero, no a la posición en la
     * que el proveedor entregue el descriptor: un fichero seekable puede llegar ya posicionado a mitad
     * y leer desde ahí sellaría los bytes equivocados (o fallaría la longitud declarada).
     *
     * - **Descriptor seekable:** ruta de siempre, `channel.position(startOffset)` sobre el descriptor
     *   del proveedor; no se tocan banderas.
     * - **Descriptor sin búsqueda (pipe/socket):** [AndroidPollingSourceStreams] lo abre en modo no
     *   bloqueante con sondeo y cancelación; si declara offset, el prefijo se descarta leyendo
     *   (también con sondeo y cancelación) y nunca llega al snapshot. Pertenece al **único** flujo no
     *   seekable en vuelo del proceso: si ya hay otro, se devuelve [SourceOpenFailure.SOURCE_BUSY]
     *   antes de tocar las banderas de la *open file description* compartida por los `dup`. En API
     *   26–29 la ruta no bloqueante no está disponible en la superficie pública del SDK, así que el
     *   origen se rechaza con [SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED] **sin** cerrar el descriptor
     *   (sigue siendo del llamador y el `finally` de [sealFromAssetDescriptor] cierra el
     *   `AssetFileDescriptor`) y sin tomar ningún permiso.
     * - **Proveedor sin [ParcelFileDescriptor]** (caso raro, pero permitido): se usa
     *   `createInputStream()` como única vía disponible; la plataforma aplica el offset, la prueba
     *   instrumentada no puede acreditarlo y la cancelación dentro de una lectura bloqueante no está
     *   garantizada. Por eso no es la ruta normal.
     *
     * Cualquier fallo cierra el flujo antes de propagarse: un seek o un descarte fallido no deja el
     * descriptor abierto (el llamador cierra además el [AssetFileDescriptor]).
     */
    private fun openDeclaredStream(
        asset: AssetFileDescriptor,
        limits: FrozenFrameSourceLimits,
        cancellationCheck: () -> Boolean,
    ): InputStream {
        val startOffset = asset.startOffset
        if (startOffset < 0L) {
            throw IOException("El proveedor declaró un offset negativo ($startOffset).")
        }
        val descriptor = asset.parcelFileDescriptor ?: return asset.createInputStream()
        val sourceFd = descriptor.fileDescriptor
            ?: throw IOException("El proveedor entregó un descriptor sin FileDescriptor utilizable.")
        if (AndroidPollingSourceStreams.isSeekable(sourceFd)) {
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            try {
                // Un descriptor de fichero regular admite posicionamiento exacto, sin copiar el prefijo.
                stream.channel.position(startOffset)
                return stream
            } catch (error: Throwable) {
                runCatching { stream.close() }
                throw error
            }
        }
        // Descriptor sin búsqueda: no hay posición absoluta que fijar y una lectura bloqueante no se
        // puede cancelar. El flujo no bloqueante toma posesión del descriptor y lo cierra al final.
        val stream = try {
            AndroidPollingSourceStreams.open(descriptor, cancellationCheck)
        } catch (error: SourceNonSeekableUnsupported) {
            // Rechazo de plataforma (API 26–29): el adaptador falla antes de leer o cerrar el descriptor
            // y antes de tomar el permiso. Cerrarlo aquí sería un cierre ajeno: la propiedad sigue en el
            // AssetFileDescriptor del llamador, que sealFromAssetDescriptor cierra en su finally.
            throw error
        } catch (error: IOException) {
            runCatching { descriptor.close() }
            throw error
        }
        try {
            // El rango solo puede empezar donde el proveedor lo dejó: si declaró offset, se descarta
            // leyendo, con el mismo sondeo cancelable.
            if (startOffset > 0L) skipFully(stream, startOffset, limits, cancellationCheck)
            return stream
        } catch (error: Throwable) {
            runCatching { stream.close() }
            throw error
        }
    }

    /**
     * Descarta [count] bytes leyendo. Solo se usa con descriptores sin búsqueda, donde el prefijo no se
     * puede saltar sin consumirlo.
     *
     * Los bytes descartados nunca llegan al snapshot, pero el descarte cuesta E/S: se acota al tope de
     * snapshot, se comprueba la cancelación en cada bloque y un flujo que devuelve `0` sin EOF no deja
     * el bucle girando (se falla cerrado). Si el flujo termina antes del offset, también es un fallo.
     */
    private fun skipFully(
        stream: InputStream,
        count: Long,
        limits: FrozenFrameSourceLimits,
        cancellationCheck: () -> Boolean,
    ) {
        if (count > limits.maxSnapshotBytes) {
            throw IOException("El offset declarado ($count bytes) supera el tope de snapshot.")
        }
        var remaining = count
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0L) {
            if (cancellationCheck()) {
                throw SourceReadCancelled("La apertura se canceló al descartar el offset declarado.")
            }
            val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("La fuente terminó antes del offset declarado.")
            if (read == 0) throw IOException("El flujo no avanzó al descartar el offset declarado.")
            remaining -= read.toLong()
        }
    }

    /** Variante para ficheros ya accesibles (pruebas instrumentadas, importaciones internas). */
    fun openFromFile(
        context: Context,
        source: File,
        limits: FrozenFrameSourceLimits = FrozenFrameSourceLimits(),
        cancellationCheck: () -> Boolean = { false },
    ): FrozenFrameSourceOpen {
        if (!source.isFile) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.SOURCE_UNAVAILABLE, "La fuente no es un fichero legible.")
        }
        if (cancellationCheck()) {
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.CANCELLED, "La apertura se canceló.")
        }
        return try {
            FileInputStream(source).use { stream ->
                sealAndIndex(context, stream, source.length(), limits, cancellationCheck)
            }
        } catch (_: IOException) {
            FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.SOURCE_UNAVAILABLE, "No se pudo leer la fuente.")
        }
    }

    private fun sealAndIndex(
        context: Context,
        stream: InputStream,
        declaredLengthBytes: Long,
        limits: FrozenFrameSourceLimits,
        cancellationCheck: () -> Boolean,
    ): FrozenFrameSourceOpen {
        val directory = File(context.cacheDir, SNAPSHOT_DIRECTORY)
        sweepStaleSnapshots(directory)
        val sealed = when (val result = SourceSnapshotCopier.copy(stream, declaredLengthBytes, directory, limits, cancellationCheck)) {
            is SnapshotSealResult.Sealed -> result.snapshot
            is SnapshotSealResult.Failed -> return FrozenFrameSourceOpen.Unavailable(result.failure, result.detail)
        }
        // El fallo tipado se decide por etapa: la longitud sellada y la apertura del descriptor son
        // integridad/E-S de nuestro propio fichero (nunca se atribuyen al contenido). Lo que el
        // extractor rechaza después sólo se atribuye al contenido cuando el propio extractor da un
        // veredicto estructural explícito (sin track de vídeo, límites del índice); una `IOException`
        // es ambigua y no se usa ninguna lista de firmas como prueba.
        SourceIndexFailurePolicy.integrityIssue(sealed.lengthBytes, sealed.file.length())?.let { issue ->
            sealed.discard()
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.STORAGE_UNAVAILABLE, issue)
        }
        val descriptor = try {
            ParcelFileDescriptor.open(sealed.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: IOException) {
            sealed.discard()
            return FrozenFrameSourceOpen.Unavailable(
                SourceOpenFailure.SOURCE_UNAVAILABLE,
                "No se pudo abrir el descriptor del snapshot sellado (${error.javaClass.simpleName}); " +
                    "es un fallo de E/S, no del contenido.",
            )
        }
        try {
            val index = descriptor.use {
                VideoFrameIndexer.indexFromFd(it.fileDescriptor) {
                    if (cancellationCheck()) throw CancellationException("Apertura de fuente cancelada.")
                }
            }
            val snapshot = VideoFrameIndexSnapshot(
                frameTimesUs = index.frameTimesUs,
                durationUs = index.durationUs,
                encodedWidth = index.width,
                encodedHeight = index.height,
                declaredRotationDegrees = index.rotationDegrees,
            )
            return FrozenFrameSourceOpen.Opened(
                FrozenFrameSourceSession(
                    identity = sealed.identity,
                    index = snapshot,
                    snapshot = sealed,
                ),
            )
        } catch (_: CancellationException) {
            sealed.discard()
            return FrozenFrameSourceOpen.Unavailable(SourceOpenFailure.CANCELLED, "La indexación se canceló.")
        } catch (error: OutOfMemoryError) {
            sealed.discard()
            return FrozenFrameSourceOpen.Unavailable(
                SourceOpenFailure.SOURCE_TOO_LARGE,
                "El índice de la fuente no cabe en memoria (${error.javaClass.simpleName}).",
            )
        } catch (error: IOException) {
            // `MediaExtractor` colapsa contenido no soportado, contenedor rechazado y fallo interno
            // del extractor en `IOException`: la política no acusa al contenido ni al formato.
            sealed.discard()
            val verdict = SourceIndexFailurePolicy.verdict(error)
            return FrozenFrameSourceOpen.Unavailable(verdict.failure, verdict.detail)
        } catch (error: RuntimeException) {
            // Incluye IllegalArgumentException: sólo un mensaje estructural del indexador (sin track
            // de vídeo, límite de frames) es un veredicto atribuible a los bytes; el resto es ambiguo.
            sealed.discard()
            val verdict = SourceIndexFailurePolicy.verdict(error)
            return FrozenFrameSourceOpen.Unavailable(verdict.failure, verdict.detail)
        }
    }

    private fun sweepStaleSnapshots(directory: File) {
        try {
            SourceSnapshotCopier.sweepStale(directory, System.currentTimeMillis(), STALE_AFTER_MILLIS)
        } catch (_: RuntimeException) {
            // Un fallo de limpieza nunca impide abrir una fuente nueva.
        }
    }
}
