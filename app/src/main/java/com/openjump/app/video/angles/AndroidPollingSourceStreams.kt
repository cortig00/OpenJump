package com.openjump.app.video.angles

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream

/**
 * Adaptador Android de [PollingSourceStream] para descriptores entregados por un proveedor.
 *
 * ## Decisiones comprobables
 *
 * - **Versión de plataforma:** `Os.fcntlInt` (`F_GETFL`/`F_SETFL`) entró en la superficie **pública**
 *   del SDK en API 30 (`Build.VERSION_CODES.R`). Con `minSdk` 26 y sin reflexión ni API oculta, un
 *   descriptor no seekable en API 26–29 se **rechaza** con [SourceNonSeekableUnsupported] **antes** de
 *   leer el descriptor, pedir banderas, sondear o tomar el permiso de proceso; el descriptor sigue
 *   siendo del llamador y no se cierra aquí. La alternativa es una copia local del vídeo. Los
 *   descriptores **seekables** no dependen de esta ruta y siguen funcionando en API 26+.
 * - **Seekable vs no seekable:** se decide con `lseek(fd, 0, SEEK_CUR)`. Un fichero regular sigue la
 *   ruta de siempre (`channel.position(startOffset)`), sin tocar banderas; solo un descriptor sin
 *   búsqueda (pipe/socket) entra aquí.
 * - **`O_NONBLOCK`:** `O_NONBLOCK` es una bandera de estado de la *open file description*, no del
 *   proceso ni de un descriptor concreto. Se activa sobre el descriptor de **lectura** que el proveedor
 *   entrega. En un pipe el extremo de escritura tiene su propia descripción, así que el escritor del
 *   proveedor conserva su comportamiento bloqueante; un `dup` del mismo extremo de lectura sí comparte
 *   la bandera, y por eso hay un **permiso de proceso** ([NonSeekablePollingLease]):
 *   - sólo un flujo no seekable puede estar en vuelo; el segundo intento falla con
 *     [SourceDescriptorBusy] **antes** de leer o escribir banderas, en lugar de esperar un hilo;
 *   - las banderas originales se guardan con `F_GETFL` **después** de tomar el permiso, de modo que no
 *     puedan incluir una bandera activada por otro adaptador;
 *   - al cerrar (o si la apertura falla) se restauran con `F_SETFL`, se cierra el descriptor y sólo
 *     entonces se libera el permiso. Liberar antes de restaurar dejaría que una apertura nueva leyera
 *     `O_NONBLOCK` como estado inicial y lo restaurara permanentemente al cerrar.
 * - **Archivos seekables:** no pasan por aquí (los detecta [isSeekable]), no cambian banderas y no
 *   toman el permiso, así que una captura sobre pipe no bloquea la apertura de un fichero regular.
 * - **Seguridad ante el proveedor:** no se confía en su longitud ni en su offset; el tope, la
 *   longitud declarada y el descarte del prefijo se aplican en [FrozenFrameSources]. Este adaptador
 *   solo aporta lectura acotada, sondeo y cancelación.
 * - **Fallo cerrado:** si no se puede leer o fijar `F_GETFL`/`F_SETFL`, se cierra el descriptor y se
 *   lanza `IOException` en lugar de degradar a un `read` bloqueante sin cancelación demostrable.
 *
 * Límite: el sondeo usa `POLLIN`; `POLLHUP`/`POLLERR` se reflejan igualmente en `revents` y
 * `Os.poll` cuenta el descriptor como listo, de modo que la lectura siguiente devuelve EOF o error en
 * lugar de esperar para siempre.
 *
 * Límite: el permiso ordena los flujos **de este proceso**. Un `dup` hecho por otro proceso, o un
 * descriptor que el proveedor conserve fuera de estos adaptadores, sigue compartiendo la *open file
 * description*; esas modificaciones externas no se pueden arbitrar desde aquí y no se prometen
 * resueltas.
 */
internal object AndroidPollingSourceStreams {

    /** `true` si [fd] admite posicionamiento absoluto (fichero regular o descriptor con búsqueda). */
    fun isSeekable(fd: FileDescriptor): Boolean = try {
        Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
        true
    } catch (_: ErrnoException) {
        false
    }

    /**
     * Envuelve [descriptor] en un flujo no bloqueante con sondeo y cancelación. **Toma posesión** del
     * descriptor: [PollingSourceStream.close] restaura las banderas, lo cierra y libera el permiso de
     * proceso del flujo no seekable.
     *
     * Si la versión de plataforma no admite la ruta no bloqueante (API 26–29), lanza
     * [SourceNonSeekableUnsupported] **antes** de tocar el descriptor: no se lee, no se cierran
     * banderas ni el descriptor y no se toma el permiso, de modo que el llamador conserva la posesión
     * y puede elegir una copia local del vídeo.
     *
     * Si ya hay otro flujo no seekable en vuelo en este proceso, lanza [SourceDescriptorBusy] sin
     * tocar las banderas del descriptor (la *open file description* podría ser la misma) y sin
     * cerrarlo: en ese rechazo el llamador conserva la posesión.
     */
    fun open(
        descriptor: ParcelFileDescriptor,
        cancellationCheck: () -> Boolean,
    ): InputStream {
        // Guard canónico de plataforma: precede a cualquier lectura del descriptor, bandera, sondeo o
        // permiso. Es también la comprobación que lint exige para usar la superficie API 30 de
        // `fcntlInt`; el abridor puro revalida la misma política en su punto único de banderas.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw NonSeekablePollingSupport.rejection(Build.VERSION.SDK_INT)
        }
        val fd = try {
            descriptor.fileDescriptor
        } catch (error: RuntimeException) {
            throw IOException("El descriptor de la fuente ya no es válido.", error)
        }
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        return NonBlockingSourceStreamOpener.open(
            platformApiLevel = Build.VERSION.SDK_INT,
            nonBlockingFlag = OsConstants.O_NONBLOCK,
            readFlags = { readDescriptorFlags(fd) },
            writeFlags = { flags -> writeDescriptorFlags(fd, flags) },
            closeDescriptor = { runCatching { descriptor.close() } },
            awaitReadable = { timeoutMillis ->
                pollFd.revents = 0
                try {
                    Os.poll(arrayOf(pollFd), timeoutMillis) > 0
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) {
                        false
                    } else {
                        throw IOException("Falló el sondeo del descriptor de la fuente.", error)
                    }
                }
            },
            readRaw = { buffer, offset, length ->
                try {
                    Os.read(fd, buffer, offset, length)
                } catch (error: ErrnoException) {
                    when (error.errno) {
                        OsConstants.EAGAIN -> throw SourceWouldBlock()
                        OsConstants.EINTR -> throw SourceWouldBlock()
                        else -> throw IOException("Falló la lectura del descriptor de la fuente.", error)
                    }
                }
            },
            cancellationCheck = cancellationCheck,
        )
    }

    /**
     * `F_GETFL` con la superficie pública del SDK. Solo se invoca en API 30+; el guard canónico de
     * [open] y la política de [NonSeekablePollingSupport] lo garantizan.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readDescriptorFlags(fd: FileDescriptor): Int = try {
        Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
    } catch (error: ErrnoException) {
        throw IOException("No se pudo leer el estado del descriptor de la fuente.", error)
    }

    /** `F_SETFL` con la superficie pública del SDK. Solo se invoca en API 30+. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeDescriptorFlags(fd: FileDescriptor, flags: Int) {
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags)
        } catch (error: ErrnoException) {
            throw IOException("No se pudo fijar el estado del descriptor de la fuente.", error)
        }
    }
}
