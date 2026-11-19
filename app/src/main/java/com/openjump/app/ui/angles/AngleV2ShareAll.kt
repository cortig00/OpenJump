package com.openjump.app.ui.angles

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puerta de un solo vuelo de la preparación múltiple.
 *
 * Vive aquí (no en el ViewModel) para poder verificar sin Android que un segundo toque no inicia
 * otra preparación mientras la primera está en curso y que la puerta vuelve a abrirse **siempre** al
 * terminar, incluso cuando la preparación falló o se canceló.
 */
class AngleV2ShareSingleFlight {

    private val inFlight = AtomicBoolean(false)

    /** `true` mientras una preparación está en curso. */
    val isBusy: Boolean get() = inFlight.get()

    /** Intenta tomar la puerta: `true` sólo para el primero; el resto no debe preparar nada. */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** Libera la puerta; debe llamarse en un `finally` para no dejarla cerrada tras un fallo. */
    fun end() {
        inFlight.set(false)
    }
}

/**
 * Resultado de preparar **un** fotograma dentro de un envío múltiple.
 *
 * Es genérico en el tipo de URI ([AngleV2ShareAllFrame.Ready.uri]) para que la secuencia del lote
 * (orden, todo-o-nada, presupuesto de transporte, cancelación) se pueda ejercitar sin Android. El
 * productor real entrega el `Uri` de la copia verificada del `FileProvider`; el adaptador de
 * producción que traduce [AngleV2FrameShareResult] a este tipo vive en el ViewModel del detalle, así
 * que esta pieza no conoce ni `Intent` ni `ClipData`.
 */
sealed interface AngleV2ShareAllFrame<out T> {

    /** Copia verificada publicada: el lote puede incluir este URI. */
    data class Ready<T>(val uri: T) : AngleV2ShareAllFrame<T>

    /** El almacén ya no tiene el PNG de ese fotograma. */
    data object Missing : AngleV2ShareAllFrame<Nothing>

    /** El PNG no coincide con la fila declarada o no se puede verificar. */
    data object Corrupted : AngleV2ShareAllFrame<Nothing>

    /** E/S, clave ajena o fallo al publicar la copia. */
    data object Unavailable : AngleV2ShareAllFrame<Nothing>
}

/**
 * Motivos tipados por los que un envío múltiple **no** se ofrece.
 *
 * Ningún motivo es un éxito parcial: cuando el resultado no es
 * [AngleV2ShareAllResult.Ready] no se ha lanzado ningún selector y no se entrega ningún subconjunto.
 * La traducción a texto localizado vive en la pantalla, no aquí.
 */
enum class AngleV2ShareAllFailure {

    /** No hay una raíz V2 cargada (o el snapshot se perdió antes de empezar). */
    NOT_LOADED,

    /** La raíz cargada no tiene fotogramas: no hay nada que compartir. */
    EMPTY,

    /** Algún fotograma cargado no tiene su PNG verificado en el almacén. */
    MISSING,

    /** Algún fotograma declara otro tipo que no es PNG, o su PNG no coincide con la fila. */
    CORRUPTED,

    /** No se pudo preparar/publicar la copia de algún fotograma. */
    UNAVAILABLE,

    /** La raíz o el snapshot cambiaron (recarga, relectura, nueva carga) durante la preparación. */
    STALE,

    /** El conjunto completo no cabe con seguridad en una transacción de transporte. */
    TOO_LARGE,
}

/**
 * Resultado puro de preparar **todas** las imágenes de un análisis guardado.
 *
 * [Ready] lleva exactamente una entrada por fotograma cargado, en el orden del contenido (ordinal e
 * id), incluidas las entradas cuyos URI coincidan entre sí: no se deduplica nada.
 */
sealed interface AngleV2ShareAllResult<out T> {

    /** Todas las imágenes están verificadas y publicadas, en orden. */
    data class Ready<T>(val items: List<T>) : AngleV2ShareAllResult<T>

    /** Fallo tipado: no hay subconjunto que compartir. */
    data class Failed(val failure: AngleV2ShareAllFailure) : AngleV2ShareAllResult<Nothing>
}

/**
 * Heurística **temprana** del tamaño de transporte del selector múltiple.
 *
 * Sirve únicamente para descartar pronto, durante la preparación secuencial, un lote que no podría
 * viajar: **no** es una cota exacta del `Parcel` real ni una garantía de que el envío quepa. La
 * comprobación autoritativa es la medición del `Parcel` del selector final antes de `startActivity`
 * (`AngleV2ShareIntents.launch`), y ni siquiera ésa promete éxito: el límite de una transacción
 * Binder no es un cupo fijo por `Intent` sino un búfer compartido por proceso y por receptor, de modo
 * que ni 1 MiB ni [MAX_INTENT_BYTES] son una garantía dura en todos los dispositivos.
 *
 * La cuenta sigue siendo deliberadamente conservadora: [FIXED_OVERHEAD_BYTES] de sobre fijo +
 * [PER_ITEM_OVERHEAD_BYTES] por entrada (cabeceras de `Parcelable`, `ClipData.Item`, `ArrayList`) +
 * [BYTES_PER_URI_CHAR] bytes por carácter de la URI. Nunca se trunca la lista ni se recorta el
 * mensaje; un conjunto que no cabe se informa como [AngleV2ShareAllFailure.TOO_LARGE].
 */
object AngleV2ShareAllTransport {

    /** Referencia nominal del búfer de una transacción Binder en AOSP (no es un cupo por `Intent`). */
    const val BINDER_LIMIT_BYTES: Int = 1 shl 20

    /**
     * Presupuesto aceptado sobre el tamaño **medido** del selector final: la mitad de
     * [BINDER_LIMIT_BYTES].
     *
     * Se aplica al `dataSize()` real del `Parcel` que se va a despachar y deja margen para el sobre
     * de la transacción y para el búfer compartido con el receptor; es un umbral prudente, no una
     * demostración de que el envío vaya a caber en cualquier dispositivo.
     */
    const val MAX_INTENT_BYTES: Int = BINDER_LIMIT_BYTES / 2

    /** Sobre fijo estimado del Intent, su `ClipData` y el selector que lo envuelve. */
    const val FIXED_OVERHEAD_BYTES: Int = 8 * 1024

    /** Sobre estimado por URI: objeto `Parcelable`, `ClipData.Item` y entrada del `ArrayList`. */
    const val PER_ITEM_OVERHEAD_BYTES: Int = 64

    /** Bytes conservadores por carácter de URI (UTF-16 + longitud). */
    const val BYTES_PER_URI_CHAR: Int = 2

    /** Estimación temprana (conservadora) para [count] URI que suman [uriChars] caracteres. */
    fun estimatedBytes(count: Int, uriChars: Int): Long {
        require(count >= 0) { "El número de URI no puede ser negativo, era $count." }
        require(uriChars >= 0) { "Los caracteres de URI no pueden ser negativos, eran $uriChars." }
        return FIXED_OVERHEAD_BYTES.toLong() +
            count.toLong() * PER_ITEM_OVERHEAD_BYTES +
            uriChars.toLong() * BYTES_PER_URI_CHAR
    }

    /** `true` si la estimación temprana de [count] URI de [uriChars] caracteres está bajo el umbral. */
    fun fits(count: Int, uriChars: Int): Boolean = estimatedBytes(count, uriChars) <= MAX_INTENT_BYTES
}

/**
 * Prepara **secuencialmente** la copia verificada de cada fotograma de un snapshot cargado.
 *
 * Contrato, en este orden:
 * 1. Una lista vacía no es un envío válido ([AngleV2ShareAllFailure.EMPTY]); tampoco lo es un
 *    snapshot que ya no está vigente ([AngleV2ShareAllFailure.STALE]).
 * 2. Todo fotograma debe declarar PNG: una fila con otro `imageMimeType` rechaza el lote completo
 *    **antes** de publicar ninguna copia ([AngleV2ShareAllFailure.CORRUPTED]).
 * 3. Cada fotograma se prepara de uno en uno con [prepareFrame]; no se materializan N bitmaps ni N
 *    PNG en memoria, sólo la lista de URI ya publicadas. La integridad se toma del contenido cargado
 *    ([AngleV2SavedDetailFrame.integrity]) y la verifica el publicador.
 * 4. [isStillCurrent] se consulta antes de cada fotograma y al final: si la raíz/snapshot cambia, el
 *    lote se declara [AngleV2ShareAllFailure.STALE] y no se devuelve ningún URI.
 * 5. El primer fotograma que falte, no coincida o no se pueda publicar cancela el lote con su motivo
 *    tipado: nunca se entrega un conjunto incompleto.
 * 6. El presupuesto de transporte se comprueba de forma incremental con [AngleV2ShareAllTransport]:
 *    si el conjunto completo no cabe, el resultado es [AngleV2ShareAllFailure.TOO_LARGE] y no se
 *    trunca la lista.
 * 7. [checkCancellation] se consulta **antes y después** de cada copia y otra vez antes de devolver
 *    [AngleV2ShareAllResult.Ready]. Un publicador bloqueante no lanza por sí mismo al cancelar su
 *    `Job`, así que sin esta comprobación un lote cancelado podría terminar en `Ready` y abrir el
 *    selector. La cancelación se propaga (no se convierte en fallo) para que el llamante no lance
 *    ningún selector y para que su `finally` limpie el indicador de ocupado.
 *
 * @param frames fotogramas del snapshot **en orden** (una entrada por fotograma, sin deduplicar).
 * @param isStillCurrent comprobación de vigencia del snapshot (raíz + revisión) del llamante.
 * @param prepareFrame preparación bloqueante de la copia de un fotograma; se invoca en secuencia.
 * @param uriLength longitud (caracteres) del URI entregado, para el presupuesto de transporte.
 * @param checkCancellation comprobación de cancelación de la corrutina llamante; por defecto no hace
 *   nada (el lote puro se puede usar sin corrutina). El ViewModel pasa `Job.ensureActive()` del
 *   contexto de E/S capturado. Debe lanzar `CancellationException` para abortar el lote.
 */
fun <T> prepareAngleV2ShareAll(
    frames: List<AngleV2SavedDetailFrame>,
    isStillCurrent: () -> Boolean,
    prepareFrame: (AngleV2SavedDetailFrame) -> AngleV2ShareAllFrame<T>,
    uriLength: (T) -> Int,
    checkCancellation: () -> Unit = {},
): AngleV2ShareAllResult<T> {
    if (frames.isEmpty()) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.EMPTY)
    if (!isStillCurrent()) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.STALE)
    val notPng = frames.any {
        !it.imageMimeType.trim().equals(AngleV2FrameShare.MIME_TYPE, ignoreCase = true)
    }
    if (notPng) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.CORRUPTED)

    val uris = ArrayList<T>(frames.size)
    var uriChars = 0
    for (frame in frames) {
        if (!isStillCurrent()) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.STALE)
        // Antes de la copia y después de ella: un `Job` cancelado durante una copia bloqueante no
        // interrumpe al publicador, así que la comprobación posterior impide seguir publicando o
        // declarar el lote listo. `CancellationException` se propaga desde aquí sin capturarse.
        checkCancellation()
        val prepared = try {
            prepareFrame(frame)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AngleV2ShareAllFrame.Unavailable
        }
        checkCancellation()
        when (prepared) {
            is AngleV2ShareAllFrame.Ready -> {
                val length = try {
                    uriLength(prepared.uri)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.UNAVAILABLE)
                }
                if (length <= 0) {
                    return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.UNAVAILABLE)
                }
                uris += prepared.uri
                uriChars += length
                if (!AngleV2ShareAllTransport.fits(uris.size, uriChars)) {
                    return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.TOO_LARGE)
                }
            }

            AngleV2ShareAllFrame.Missing ->
                return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.MISSING)

            AngleV2ShareAllFrame.Corrupted ->
                return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.CORRUPTED)

            AngleV2ShareAllFrame.Unavailable ->
                return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.UNAVAILABLE)
        }
    }
    if (!isStillCurrent()) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.STALE)
    // También tras la última copia: un lote cancelado nunca se declara `Ready` ni abre el selector.
    checkCancellation()
    if (uris.isEmpty()) return AngleV2ShareAllResult.Failed(AngleV2ShareAllFailure.EMPTY)
    return AngleV2ShareAllResult.Ready(uris)
}

/**
 * Reclama la puerta de un solo vuelo de la UI de «compartir todas las imágenes» **de forma síncrona**
 * y sólo entonces encola el lote.
 *
 * El orden importa: consultar el estado de ocupado y lanzar la corrutina en dos pasos deja una ventana
 * en la que el `launch` queda en cola (no despachado) y un toque individual puede colarse y arrancar
 * su propia preparación/selector. Aquí [claim] se ejecuta en el mismo frame y antes de [startBatch],
 * como hace la compartición individual, así que el segundo toque —incluido el que llega antes de la
 * recomposición que deshabilitaría el botón— ya encuentra la puerta cerrada.
 *
 * No sustituye a la puerta atómica del ViewModel ([AngleV2ShareSingleFlight]): es la primera barrera,
 * la del frame, y el `finally` del trabajo encolado debe liberarla en éxito, error, cancelación e
 * [AngleV2ShareAllOutcome.Ignored].
 *
 * @return `true` si este toque reclamó la puerta y [startBatch] se invocó; `false` si ya había una
 *   compartición (individual o múltiple) en curso y no debe arrancarse nada.
 */
internal inline fun beginAngleV2ShareAllBatch(
    isFrameShareBusy: () -> Boolean,
    isShareAllBusy: () -> Boolean,
    claim: () -> Unit,
    startBatch: () -> Unit,
): Boolean {
    if (isFrameShareBusy() || isShareAllBusy()) return false
    claim()
    startBatch()
    return true
}
