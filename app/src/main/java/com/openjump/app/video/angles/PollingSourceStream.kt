package com.openjump.app.video.angles

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cancelación observada dentro de una lectura de fuente.
 *
 * Es una `IOException` porque nace en mitad de la E/S, pero se distingue para no atribuir una
 * cancelación del llamador a un fallo ambiguo del proveedor: [SourceSnapshotCopier] y
 * [FrozenFrameSources.sealFromAssetDescriptor] la traducen a
 * [SourceOpenFailure.CANCELLED].
 */
internal class SourceReadCancelled(
    message: String = "La lectura de la fuente se canceló.",
) : IOException(message)

/**
 * Señal interna de «no hay datos ahora» (`EAGAIN`) en un descriptor no bloqueante.
 *
 * No es un error: el bucle de [PollingSourceStream] vuelve a sondear tras recibirla.
 */
internal class SourceWouldBlock : IOException("La lectura no bloqueante no tenía datos disponibles.")

/**
 * Rechazo tipado y **recuperable** de una segunda apertura no seekable concurrente.
 *
 * La E/S no bloqueante se consigue con `F_SETFL` y `O_NONBLOCK` pertenece a la *open file description*,
 * no a un descriptor concreto: dos descriptores que comparten descripción (un `dup`, o dos
 * `ParcelFileDescriptor` que el proveedor obtuvo del mismo extremo) comparten la bandera. Con dos
 * adaptadores en vuelo, el primero en cerrarse desactivaría `O_NONBLOCK` mientras el otro sigue
 * leyendo (lectura potencialmente bloqueante, cancelación no demostrable) y el último en cerrarse
 * dejaría la bandera activada en los descriptores del proveedor.
 *
 * No describe el contenido ni el proveedor: es un rechazo transitorio, y el llamador puede reintentar
 * cuando el flujo en vuelo termine. Se lanza **antes** de leer o escribir banderas, así que el
 * descriptor del segundo intento no se modifica.
 */
internal class SourceDescriptorBusy(
    message: String = "Ya hay un flujo de fuente no seekable en vuelo en este proceso.",
) : IOException(message)

/**
 * Rechazo tipado de la lectura no bloqueante de un descriptor no seekable en una versión de Android
 * que no expone las banderas en la **superficie pública** del SDK.
 *
 * `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) se publicó en API 30. En API 26–29 solo existe como API
 * interna de la plataforma, y esta app no usa reflexión ni API oculta: sin una forma pública de
 * activar `O_NONBLOCK`, una lectura sobre un pipe/socket podría bloquearse sin cancelación
 * demostrable. Por eso el origen no seekable se **rechaza** en esas versiones en lugar de degradar
 * a una lectura bloqueante cuyo cierre no se puede garantizar.
 *
 * No describe el proveedor, el contenido ni el descriptor: es una limitación de la versión de
 * plataforma y el llamador debe ofrecer una alternativa (una copia local del vídeo). Se lanza
 * **antes** de tomar el permiso de proceso [NonSeekablePollingLease] y de leer o escribir banderas:
 * el descriptor sigue siendo propiedad del llamador y aquí no se cierra.
 */
internal class SourceNonSeekableUnsupported(
    message: String = "La lectura no bloqueante de un origen no seekable requiere API 30 o superior.",
) : IOException(message)

/**
 * Política de plataforma mínima para el sondeo no bloqueante de descriptores no seekables.
 *
 * `Os.fcntlInt` entró en la superficie **pública** del SDK en API 30 (`Build.VERSION_CODES.R`); en
 * API 26–29 solo está disponible como API interna, y este proyecto no usa reflexión ni API oculta.
 * La política vive aquí, en Kotlin puro, para poder demostrarla en JVM sin dispositivo:
 *
 * - los descriptores **seekables** no dependen de ella: siguen funcionando en API 26+ por la ruta de
 *   siempre (`channel.position`), sin tocar banderas ni tomar el permiso;
 * - a partir de [MIN_SUPPORTED_API] el sondeo no bloqueante funciona igual que hasta ahora;
 * - por debajo, el rechazo es [SourceNonSeekableUnsupported] y no una lectura bloqueante sin
 *   cancelación demostrable.
 */
internal object NonSeekablePollingSupport {

    /** Primera versión con `fcntlInt` en la superficie pública del SDK (Android 11 / `VERSION_CODES.R`). */
    const val MIN_SUPPORTED_API: Int = 30

    /** `true` si [platformApiLevel] admite la ruta no bloqueante sobre descriptores no seekables. */
    fun isSupported(platformApiLevel: Int): Boolean = platformApiLevel >= MIN_SUPPORTED_API

    /**
     * Rechazo tipado para [platformApiLevel]: una `IOException` de la capa de E/S, no un fallo
     * ambiguo del proveedor ni del contenido.
     */
    fun rejection(platformApiLevel: Int): SourceNonSeekableUnsupported = SourceNonSeekableUnsupported(
        "La lectura no bloqueante de un origen no seekable requiere API $MIN_SUPPORTED_API o superior " +
            "(dispositivo en API $platformApiLevel); elige una copia local del vídeo.",
    )

    /** Lanza [SourceNonSeekableUnsupported] si [platformApiLevel] no admite el sondeo no bloqueante. */
    fun requireSupported(platformApiLevel: Int) {
        if (!isSupported(platformApiLevel)) throw rejection(platformApiLevel)
    }
}

/** Fallo tipado de fuente derivado de una excepción de apertura del flujo, con su detalle visible. */
internal data class SourceStreamRejection(
    val failure: SourceOpenFailure,
    val detail: String,
)

/**
 * Traduce una excepción de la apertura del flujo a un fallo tipado de fuente, sin degradar todo a un
 * único caso genérico.
 *
 * - Una cancelación observada dentro de la E/S es [SourceOpenFailure.CANCELLED], no un fallo del
 *   proveedor.
 * - Un rechazo de concurrencia no seekable es [SourceOpenFailure.SOURCE_BUSY] (recuperable).
 * - Un origen no seekable en una plataforma sin `fcntlInt` público es
 *   [SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED], que la UI presenta con su propio aviso.
 * - Cualquier otra `IOException` de apertura sigue siendo [SourceOpenFailure.SOURCE_UNAVAILABLE]
 *   (E/S ambigua): no se atribuye al contenido ni al formato.
 */
internal fun sourceStreamRejection(error: IOException): SourceStreamRejection = when (error) {
    is SourceReadCancelled -> SourceStreamRejection(
        SourceOpenFailure.CANCELLED,
        error.message ?: "La apertura se canceló.",
    )
    is SourceDescriptorBusy -> SourceStreamRejection(
        SourceOpenFailure.SOURCE_BUSY,
        error.message ?: "Ya hay otra captura no seekable en vuelo.",
    )
    is SourceNonSeekableUnsupported -> SourceStreamRejection(
        SourceOpenFailure.NON_SEEKABLE_UNSUPPORTED,
        error.message ?: "La lectura no bloqueante no está disponible en esta versión de Android.",
    )
    else -> SourceStreamRejection(
        SourceOpenFailure.SOURCE_UNAVAILABLE,
        "El proveedor no entregó un flujo legible o el offset declarado no se pudo aplicar.",
    )
}

/**
 * Permiso de proceso (no de hilo) para el **único** flujo no seekable en vuelo.
 *
 * `O_NONBLOCK` se activa sobre la *open file description* que comparten los descriptores del mismo
 * extremo de lectura, y no existe una comprobación local que demuestre que dos descriptores no
 * seekables pertenecen a descripciones distintas (mismo inodo no implica misma descripción, y dos
 * `dup` sí comparten la descripción). Por eso la exclusión es por proceso: sólo un adaptador puede
 * cambiar y restaurar banderas a la vez.
 *
 * - Se toma **antes** de `F_GETFL`, de modo que las banderas originales guardadas no puedan incluir
 *   una activada por otro adaptador.
 * - Se mantiene hasta **después** de restaurar las banderas y cerrar el descriptor: liberar antes de
 *   restaurar dejaría que una apertura nueva leyera `O_NONBLOCK` como estado «original».
 * - Nunca espera: la segunda apertura falla con [SourceDescriptorBusy] en lugar de bloquear un hilo.
 *
 * No interviene en descriptores seekables: esa ruta no cambia banderas ni toma el permiso.
 */
internal object NonSeekablePollingLease {

    private val holder = AtomicReference<Any?>(null)

    /** `true` si hay un flujo no seekable en vuelo. Sólo para diagnóstico y pruebas. */
    val isHeld: Boolean get() = holder.get() != null

    /**
     * Intenta tomar el permiso sin esperar. Devuelve el testigo del titular, o `null` si ya está tomado.
     */
    fun tryAcquire(): Any? {
        val token = Any()
        return if (holder.compareAndSet(null, token)) token else null
    }

    /**
     * Libera el permiso **sólo** si [token] sigue siendo el titular: un cierre tardío no puede retirar
     * el permiso de un flujo abierto después.
     */
    fun release(token: Any) {
        holder.compareAndSet(token, null)
    }
}

/**
 * Secuencia de apertura de un flujo no bloqueante sobre un descriptor **no seekable**, con el permiso
 * exclusivo de proceso de [NonSeekablePollingLease].
 *
 * Es Kotlin puro con puertos inyectados ([readFlags], [writeFlags], [closeDescriptor]) para poder
 * demostrar en JVM el orden exacto exigido por las banderas compartidas:
 *
 * 0. comprobar la política de plataforma ([NonSeekablePollingSupport.requireSupported] con el nivel de
 *    API recibido) —si la versión no expone `fcntlInt` en el SDK público, el rechazo es
 *    [SourceNonSeekableUnsupported] sin tomar el permiso, sin tocar banderas y sin cerrar nada—,
 * 1. tomar el permiso —si ya está tomado, [SourceDescriptorBusy] sin tocar el descriptor—,
 * 2. `F_GETFL` y `F_SETFL` con `O_NONBLOCK`,
 * 3. al cerrar (o ante **cualquier** excepción o cancelación): restaurar banderas, cerrar descriptor y
 *    liberar el permiso, en ese orden.
 *
 * [platformApiLevel] es el nivel real de la plataforma (`Build.VERSION.SDK_INT` en el adaptador). Se
 * revalida aquí, y no solo en el adaptador, porque este objeto es el único punto que toma el permiso
 * y cambia banderas: ningún llamador nuevo puede saltarse la política de la versión.
 */
internal object NonBlockingSourceStreamOpener {

    /**
     * Abre el flujo y **transfiere** el permiso al [PollingSourceStream] devuelto: cerrar el flujo lo
     * restaura, cierra el descriptor y libera el permiso.
     *
     * Si la apertura falla o la plataforma no admite la ruta no bloqueante, restaura lo que pudo haber
     * cambiado, cierra el descriptor y libera el permiso antes de propagar el error original. En el
     * rechazo de plataforma no hay nada que restaurar ni cerrar: el descriptor sigue siendo del
     * llamador.
     */
    fun open(
        platformApiLevel: Int,
        nonBlockingFlag: Int,
        readFlags: () -> Int,
        writeFlags: (flags: Int) -> Unit,
        closeDescriptor: () -> Unit,
        awaitReadable: (timeoutMillis: Int) -> Boolean,
        readRaw: (buffer: ByteArray, offset: Int, length: Int) -> Int,
        cancellationCheck: () -> Boolean,
    ): PollingSourceStream {
        // Política de versión ANTES del permiso, de las banderas y de cualquier cierre: en una
        // plataforma sin `fcntlInt` público no se emite E/S ni se modifica el descriptor.
        NonSeekablePollingSupport.requireSupported(platformApiLevel)
        val lease = NonSeekablePollingLease.tryAcquire()
            ?: throw SourceDescriptorBusy(
                "Ya hay un flujo de fuente no seekable en vuelo en este proceso; " +
                    "se rechaza la apertura concurrente sin modificar las banderas del descriptor.",
            )
        var originalFlags: Int? = null
        try {
            val flags = readFlags()
            originalFlags = flags
            writeFlags(flags or nonBlockingFlag)
            return PollingSourceStream(
                awaitReadable = awaitReadable,
                readRaw = readRaw,
                cancellationCheck = cancellationCheck,
                onClose = {
                    // Restaurar → cerrar → liberar. El permiso se libera al final para que ninguna
                    // apertura nueva pueda leer `F_GETFL` a mitad de la restauración.
                    try {
                        originalFlags?.let { restore -> runCatching { writeFlags(restore) } }
                        runCatching { closeDescriptor() }
                    } finally {
                        NonSeekablePollingLease.release(lease)
                    }
                },
            )
        } catch (error: Throwable) {
            // Cualquier fallo de apertura (incluida una `Error`) no puede dejar la bandera activada ni
            // el permiso tomado: se restaura lo que pudo cambiarse y se libera el permiso.
            try {
                originalFlags?.let { restore -> runCatching { writeFlags(restore) } }
                runCatching { closeDescriptor() }
            } finally {
                NonSeekablePollingLease.release(lease)
            }
            throw error
        }
    }
}

/**
 * Flujo de lectura sobre un descriptor **no seekable** (pipe/socket) con sondeo y cancelación real.
 *
 * ## Por qué existe
 *
 * Un `read(2)` bloqueante sobre el extremo de lectura de un pipe no se despierta por una bandera de
 * cancelación: comprobarla «entre bloques» solo funciona si el proveedor entrega datos. Con un
 * escritor abierto y sin datos, una copia bloqueada no termina nunca y el hilo queda colgado
 * (además de retener temporal, reserva y descriptor). Este flujo no promete que `Future.cancel()` ni
 * cerrar el descriptor despierten un `read` ya bloqueado: evita emitir ese `read` bloqueante. El
 * descriptor se pone en modo no bloqueante y cada iteración:
 *
 * 1. comprueba la cancelación del llamador,
 * 2. espera legibilidad con [awaitReadable] (poll con tiempo límite) y, si despierta, vuelve a
 *    comprobar la cancelación antes de leer: el sondeo pudo consumir el plazo mientras el llamador
 *    cancelaba, y leer entonces devolvería datos/EOF de una copia ya cancelada,
 * 3. lee sin bloquear; `EAGAIN` vuelve al paso 1.
 *
 * El tiempo límite del sondeo acota la latencia de cancelación (no la duración total de una copia
 * legítima) y, si el proveedor no entrega datos, el bucle no gira en vacío.
 *
 * ## Contrato de las dependencias inyectadas
 *
 * - [awaitReadable] devuelve `true` si el descriptor tiene datos, EOF o error pendiente, y `false`
 *   si venció el tiempo límite. Puede lanzar [IOException] en un fallo de sondeo.
 * - [readRaw] devuelve bytes leídos (> 0) o `0`/negativo en EOF (semántica de `read(2)` con longitud
 *   positiva), y **lanza [SourceWouldBlock] ante `EAGAIN`**. Nunca se le pide más de `length` bytes.
 * - [cancellationCheck] se consulta antes de cada sondeo y antes de cada lectura; si devuelve `true`
 *   se lanza [SourceReadCancelled] sin emitir más E/S.
 * - [onClose] se ejecuta una sola vez al cerrar, incluso si se cierra dos veces. Es donde el adaptador
 *   de plataforma restaura las banderas del descriptor, lo cierra y libera el permiso de proceso de
 *   [NonSeekablePollingLease], en ese orden.
 *
 * Es Kotlin puro (sin Android) para poder probar en JVM la cancelación, `EAGAIN`, EOF, el acotado de
 * lecturas y el cierre idempotente con dependencias falsas y deterministas. [NonBlockingSourceStreamOpener]
 * es la única ruta que activa `O_NONBLOCK`: toma el permiso de [NonSeekablePollingLease] antes de
 * `F_GETFL` porque `O_NONBLOCK` pertenece a la *open file description* compartida por los `dup` del
 * mismo extremo.
 */
internal class PollingSourceStream(
    private val awaitReadable: (timeoutMillis: Int) -> Boolean,
    private val readRaw: (buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val cancellationCheck: () -> Boolean = { false },
    private val pollTimeoutMillis: Int = DEFAULT_POLL_TIMEOUT_MILLIS,
    private val onClose: () -> Unit = {},
) : InputStream() {

    init {
        require(pollTimeoutMillis > 0) { "El tiempo límite de sondeo debe ser positivo." }
    }

    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /**
     * Una lectura de un byte. No se usa en la copia (siempre lee por bloques), pero `InputStream`
     * exige implementarla; delega en [read] para conservar cancelación y EOF.
     */
    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Rango de lectura no válido (offset=$offset, length=$length).")
        }
        if (length == 0) return 0
        if (closed.get()) throw IOException("El flujo de la fuente ya está cerrado.")
        while (true) {
            if (closed.get()) throw IOException("El flujo de la fuente ya está cerrado.")
            if (cancellationCheck()) throw SourceReadCancelled()
            if (!awaitReadable(pollTimeoutMillis)) continue
            // El sondeo puede haber consumido el tiempo límite mientras el llamador cancelaba: se
            // revalida antes de emitir la lectura para no leer ni devolver EOF con la copia cancelada
            // (el llamador traduciría ese EOF a COPY_FAILED/SOURCE_UNAVAILABLE en vez de CANCELLED).
            if (cancellationCheck()) throw SourceReadCancelled()
            val read = try {
                readRaw(buffer, offset, length)
            } catch (_: SourceWouldBlock) {
                // Carrera entre el sondeo y la lectura (otro lector o un HUP sin datos): se vuelve a
                // sondear en lugar de reintentar la lectura en vacío.
                continue
            }
            if (read == 0) return -1
            return read
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    companion object {
        /**
         * Latencia máxima de cancelación cuando el proveedor no entrega datos. Un valor pequeño no
         * añade E/S (el sondeo duerme), solo llamadas de sistema.
         */
        const val DEFAULT_POLL_TIMEOUT_MILLIS = 50
    }
}
