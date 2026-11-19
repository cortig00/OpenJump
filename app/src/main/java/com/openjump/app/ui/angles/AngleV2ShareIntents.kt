package com.openjump.app.ui.angles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.os.TransactionTooLargeException

/**
 * Desenlace tipado de lanzar el selector múltiple.
 *
 * Sólo [Launched] implica que el selector se despachó; el resto deja al llamante sin nada entregado y
 * con un mensaje distinto. En particular [TooLarge] es un problema de transporte —se informa como tal
 * y **no** como «no hay apps»—, y [Unavailable] agrupa permisos y fallos genéricos del sistema.
 */
sealed interface AngleV2ShareLaunchResult {

    /** El selector se despachó al sistema con la secuencia completa. */
    data object Launched : AngleV2ShareLaunchResult

    /** No hay ninguna app que pueda abrir el selector. */
    data object NoReceiver : AngleV2ShareLaunchResult

    /** El selector final no cabe en una transacción de transporte: no se ha llamado a `startActivity`. */
    data object TooLarge : AngleV2ShareLaunchResult

    /** Construcción/serialización imposible, permiso denegado o fallo genérico del sistema. */
    data object Unavailable : AngleV2ShareLaunchResult
}

/**
 * Construcción y lanzamiento del selector **múltiple** de imágenes ya verificadas de un análisis V2.
 *
 * Lo que se entrega es sólo la lista ordenada de URI de copias PNG verificadas del `FileProvider`
 * dedicado: no hay vídeo, ni ZIP, ni el almacén privado, ni rutas de directorios. Se concede
 * **únicamente** permiso de lectura sobre esas URI ([Intent.FLAG_GRANT_READ_URI_PERMISSION]); no se
 * usa ningún permiso de prefijo/árbol y no se escribe nada.
 *
 * Las funciones de construcción son puras (sin `Context`) para que el Intent real se pueda inspeccionar
 * en pruebas instrumentadas. Antes de despachar, [launch] serializa el selector **final** en un
 * `Parcel` real y rechaza el envío si supera [AngleV2ShareAllTransport.MAX_INTENT_BYTES]: ésa es la
 * comprobación autoritativa de transporte, y la estimación de
 * [AngleV2ShareAllTransport.estimatedBytes] sólo sirve para descartar antes durante la preparación.
 * El umbral es prudente, no una garantía: el límite Binder es un búfer compartido por proceso y
 * receptor, no un cupo fijo por `Intent`.
 */
object AngleV2ShareIntents {

    /** Profundidad máxima de la cadena de causas que se inspecciona buscando el fallo de transporte. */
    private const val MAX_CAUSE_DEPTH: Int = 8

    /**
     * Intent de envío múltiple con la secuencia **exacta** de [uris].
     *
     * Se usa `ACTION_SEND_MULTIPLE` también con un único fotograma: el orden y la forma del envío no
     * cambian con el número de imágenes, y el `ClipData` repite la misma secuencia ítem por ítem.
     * Si [uris] está vacío se lanza una excepción de contrato: es un error de programación, nunca un
     * envío vacío.
     */
    fun sendMultiple(uris: List<Uri>, chooserTitle: String): Intent {
        require(uris.isNotEmpty()) { "Un envío múltiple necesita al menos una imagen." }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = AngleV2FrameShare.MIME_TYPE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clipData(uris, chooserTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * `ClipData` con la misma secuencia que [uris] y el tipo `image/png`.
     *
     * El primer ítem se pasa al constructor y el resto se añade en orden, así que el receptor ve la
     * misma lista que en `EXTRA_STREAM` (no se agrupa, no se reordena y no se deduplica).
     */
    fun clipData(uris: List<Uri>, label: String): ClipData {
        require(uris.isNotEmpty()) { "El ClipData necesita al menos una imagen." }
        val clip = ClipData(
            label,
            arrayOf(AngleV2FrameShare.MIME_TYPE),
            ClipData.Item(uris.first()),
        )
        for (index in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[index]))
        }
        return clip
    }

    /**
     * Selector del sistema que envuelve [sendMultiple].
     *
     * [newTask] sólo se añade cuando no hay `Activity` dueña del contexto (misma regla que el envío
     * individual), para no cambiar el `Intent` que inspecciona una prueba.
     */
    fun chooser(uris: List<Uri>, chooserTitle: String, newTask: Boolean): Intent {
        val chooser = Intent.createChooser(sendMultiple(uris, chooserTitle), chooserTitle)
        if (newTask) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooser
    }

    /**
     * Serializa [intent] en un `Parcel` real y devuelve el tamaño en bytes que ocuparía al cruzar la
     * transacción, o `null` si la serialización falla.
     *
     * Es la medición que se aplica al selector **final** (chooser + `EXTRA_INTENT` + `EXTRA_STREAM` +
     * `ClipData`) justo antes de `startActivity`; el `Parcel` se recicla **siempre** en un `finally`.
     * No se escribe nada a disco y no se abre ningún proveedor: sólo se serializa lo que ya se va a
     * enviar. No se trunca el `Intent` en ningún caso.
     */
    fun measuredParcelBytes(intent: Intent): Int? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(intent, 0)
            parcel.dataSize()
        } catch (_: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Lanza el selector con las imágenes verificadas y devuelve un desenlace tipado.
     *
     * Orden de las barreras:
     * 1. Si construir el selector falla, es [AngleV2ShareLaunchResult.Unavailable] (no hay nada que
     *    despachar).
     * 2. Se mide el `Parcel` del selector final; si no se puede medir
     *    ([measuredParcelBytes] devuelve `null`) es [AngleV2ShareLaunchResult.Unavailable] y si supera
     *    [AngleV2ShareAllTransport.MAX_INTENT_BYTES] es [AngleV2ShareLaunchResult.TooLarge] **sin
     *    llamar** a `startActivity` (nunca se entrega un subconjunto).
     * 3. Sólo entonces se despacha. Sin app receptora ([ActivityNotFoundException]) es
     *    [AngleV2ShareLaunchResult.NoReceiver]; un rechazo de permisos o un fallo genérico del sistema
     *    es [AngleV2ShareLaunchResult.Unavailable]; y un [TransactionTooLargeException], directo o
     *    anidado en la causa, es [AngleV2ShareLaunchResult.TooLarge]. Ninguna de estas rutas propaga
     *    la excepción: la pantalla informa y sigue viva.
     */
    fun launch(context: Context, uris: List<Uri>, chooserTitle: String): AngleV2ShareLaunchResult {
        val finalChooser = try {
            chooser(uris, chooserTitle, newTask = context !is Activity)
        } catch (_: Exception) {
            return AngleV2ShareLaunchResult.Unavailable
        }
        val measuredBytes = measuredParcelBytes(finalChooser)
            ?: return AngleV2ShareLaunchResult.Unavailable
        if (measuredBytes > AngleV2ShareAllTransport.MAX_INTENT_BYTES) {
            return AngleV2ShareLaunchResult.TooLarge
        }
        return try {
            context.startActivity(finalChooser)
            AngleV2ShareLaunchResult.Launched
        } catch (_: ActivityNotFoundException) {
            AngleV2ShareLaunchResult.NoReceiver
        } catch (failure: Exception) {
            if (isTransportTooLarge(failure)) {
                AngleV2ShareLaunchResult.TooLarge
            } else {
                AngleV2ShareLaunchResult.Unavailable
            }
        }
    }

    /**
     * `true` si [failure] es un fallo de tamaño de transacción, ya sea directo o envuelto.
     *
     * El sistema no siempre expone [TransactionTooLargeException] como tipo exacto: puede llegar
     * dentro de un `RuntimeException` del arranque de actividad. Se recorre la cadena de causas con un
     * tope para no depender de una forma concreta ni entrar en un ciclo.
     */
    private fun isTransportTooLarge(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is TransactionTooLargeException) return true
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return false
    }
}
