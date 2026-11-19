package com.openjump.app.video.angles

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.Image
import android.os.ParcelFileDescriptor
import com.openjump.app.video.VideoFrameSafety
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Decisiones puras (sin Android) del productor MediaCodec: qué pedir al extractor, cuánto decodificar
 * y cuándo un PTS observado prueba la identidad temporal. Se prueban en JVM; el bucle real de
 * `MediaCodec` vive en [CodecFrozenFrameProducer].
 *
 * ## Qué NO promete esta capa
 *
 * - El PTS es **reportado por el decodificador** para un buffer de salida concreto; no es el instante
 *   físico de captura del sensor ni una prueba de corrección temporal del códec.
 * - La conversión YUV→RGB de [Yuv420OutputLayout] usa la **matriz y el rango declarados** por el
 *   formato de salida del decodificador (BT.601/BT.709, limitado/completo) cuando están presentes y
 *   son SDR conocidos; si declara HDR (PQ/HLG), BT.2020 o un valor que esta capa no entiende, se
 *   bloquea en lugar de convertir con una matriz supuesta. Aun así **no se certifica color** contra
 *   ningún render de referencia: lo que se certifica es que la imagen mostrada y anotada son el mismo
 *   bitmap (digest) y que píxeles y PTS provienen del mismo buffer de salida.
 * - La identidad de la fuente ya **no es una etiqueta del llamador**: [FrozenFrameSourceSession]
 *   la acuña al sellar un snapshot privado del contenido y el productor exige que la solicitud la
 *   lleve. La misma metadata/PTS de otra fuente no acredita el mismo contenido; índice y captura se
 *   abren sobre el descriptor del snapshot sellado (`setDataSource(fd, 0, longitud)`). Se comprueban
 *   además MIME, dimensiones, rotación y duración declarados por el contenedor y se bloquea si no
 *   coinciden con el índice.
 * - La rotación solo se aplica si [FrameOrientationPlanner] puede demostrarla desde dimensiones y
 *   metadatos. `MediaCodec` no rota la salida: el caso esperado es
 *   [FrameOrientationCertainty.DIMENSIONS_INDICATE_ENCODED_ORIENTATION].
 */
object CodecCapturePolicy {

    /** Un GOP de 12 s a 60 fps sigue holgadamente por debajo de este tope. */
    const val MAX_DECODED_FRAMES = 900

    /** Tope de espera acumulada por buffers de salida (una captura suelta, no un player). */
    const val MAX_OUTPUT_WAIT_US = 8_000_000L

    /**
     * Tope de salidas **sin payload de frame** (`EMPTY` y `CODEC_CONFIG`) en una captura.
     *
     * Un decodificador que devuelve buffers vacíos en bucle no gasta [MAX_OUTPUT_WAIT_US] (no se
     * espera: los buffers llegan ya) ni [DecodeBudget] (no son frames), así que sin este tope el
     * bucle puede girar indefinidamente. No recorta una decodificación válida: los frames útiles se
     * cuentan en [DecodeBudget] y no consumen este presupuesto.
     */
    const val MAX_EMPTY_OUTPUT_BUFFERS = 512

    /** Tope total de iteraciones del bucle de decodificación (frames + esperas + vacíos). */
    const val MAX_DECODER_ITERATIONS = 8_192

    /**
     * Plan de búsqueda: siempre `SEEK_TO_PREVIOUS_SYNC` para no saltar por delante del PTS pedido.
     * `SEEK_TO_CLOSEST_SYNC`/`SEEK_TO_NEXT_SYNC` podrían caer después del frame solicitado y dejarían
     * el PTS objetivo inalcanzable sin decodificar hacia atrás, que no es posible.
     */
    fun seekTargetPtsUs(index: VideoFrameIndexSnapshot, frameIndex: Int): Long {
        require(frameIndex in 0 until index.frameCount) { "Frame fuera del índice." }
        return index.ptsAt(frameIndex)
    }

    /**
     * ¿Puede pedirse este frame? Bloquea fail-closed si la solicitud no describe el mismo índice
     * (recuento, dimensiones, rotación o PTS del frame) o si el PTS queda fuera de la duración
     * declarada.
     *
     * El PTS debe ser **exactamente** `index.ptsAt(frameIndex)`: estar dentro del rango del
     * índice no demuestra que el decodificador vaya a producir ese timestamp y el bucle solo captura
     * una coincidencia exacta.
     *
     * El presupuesto de decodificación **no** se evalúa por posición absoluta en el índice: el
     * extractor salta con `SEEK_TO_PREVIOUS_SYNC`, así que el trabajo real empieza en el sync anterior
     * al PTS pedido. Un frame con índice 1500 puede costar unas pocas decodificaciones (GOP corto) y
     * un frame cercano al inicio puede costar cientos (GOP largo). El tope
     * [MAX_DECODED_FRAMES] se aplica a los frames **realmente decodificados desde ese sync** mediante
     * [DecodeBudget] dentro del bucle, no a la distancia al primer frame del vídeo.
     */
    fun validateRequest(request: FrameFreezeRequest, index: VideoFrameIndexSnapshot): FrameFreezeResult.Blocked? {
        if (index.frameCount != request.indexFrameCount) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "El índice de frames no coincide con el declarado en la solicitud.",
            )
        }
        if (request.encodedWidth != index.encodedWidth || request.encodedHeight != index.encodedHeight) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "Las dimensiones de la solicitud (${request.encodedWidth}×${request.encodedHeight}) no coinciden con el índice (${index.encodedWidth}×${index.encodedHeight}).",
            )
        }
        if (request.declaredRotationDegrees != index.declaredRotationDegrees) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "La rotación declarada (${request.declaredRotationDegrees}°) no coincide con la del índice (${index.declaredRotationDegrees}°).",
            )
        }
        val indexedPtsUs = index.ptsAt(request.frameIndex)
        if (indexedPtsUs != request.assignedPtsUs) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "El PTS asignado (${request.assignedPtsUs}µs) no es el del frame ${request.frameIndex} en el índice (${indexedPtsUs}µs).",
            )
        }
        val target = request.assignedPtsUs
        if (index.durationUs > 0L && target > index.durationUs + 1_000_000L) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                "El PTS solicitado queda fuera de la duración declarada.",
            )
        }
        return null
    }

    /** Resultado de comparar el PTS de salida con el objetivo mientras se decodifica. */
    enum class OutputPtsDecision {
        /** Aún por debajo del objetivo: seguir decodificando. */
        KEEP_DECODING,

        /** Coincide exactamente: esta es la muestra que puede acreditarse. */
        CAPTURE,

        /** Ya se pasó el objetivo: no se puede volver atrás sin otro seek; fail-closed. */
        PASSED_TARGET,
    }

    fun decideOutputPts(observedPtsUs: Long, targetPtsUs: Long): OutputPtsDecision = when {
        observedPtsUs < targetPtsUs -> OutputPtsDecision.KEEP_DECODING
        observedPtsUs == targetPtsUs -> OutputPtsDecision.CAPTURE
        else -> OutputPtsDecision.PASSED_TARGET
    }

    /**
     * Clasifica un buffer de salida **antes** de contar presupuesto de decodificación o de leer
     * píxeles.
     *
     * Un EOS **sin payload** no es un frame: se procesa como final del track (bloqueo) en lugar de
     * contarse como frame decodificado, compararse por PTS y acabar leyéndose como una imagen vacía;
     * tampoco debe dejar el bucle esperando hasta agotar [MAX_OUTPUT_WAIT_US]. Un buffer con payload
     * **y** EOS sí es el último frame legítimo y se procesa con normalidad.
     */
    fun classifyOutputBuffer(flags: Int, size: Int): CodecOutputBufferKind {
        val codecConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val endOfStream = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val hasPayload = size > 0
        return when {
            codecConfig -> CodecOutputBufferKind.CODEC_CONFIG
            !hasPayload && endOfStream -> CodecOutputBufferKind.END_OF_STREAM_WITHOUT_PAYLOAD
            hasPayload -> CodecOutputBufferKind.FRAME
            else -> CodecOutputBufferKind.EMPTY
        }
    }

    /** `true` si la bandera de fin de stream está presente en [flags]. */
    fun endsStream(flags: Int): Boolean = (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
}

/** Clasificación de un buffer de salida del decodificador para el ciclo de captura. */
enum class CodecOutputBufferKind {
    /** Configuración del códec (CSD): no aporta píxeles del frame, aunque puede cerrar el stream. */
    CODEC_CONFIG,

    /** Payload de frame; si además lleva EOS, es el último frame del track y se procesa normal. */
    FRAME,

    /** EOS sin payload: el track terminó y no hay píxeles que capturar (no cuenta como frame). */
    END_OF_STREAM_WITHOUT_PAYLOAD,

    /** Buffer sin payload ni banderas: no acredita ningún frame ni consume presupuesto. */
    EMPTY,
}

/**
 * Presupuesto de decodificación medido en frames **realmente decodificados desde el sync** al que
 * saltó el extractor (`SEEK_TO_PREVIOUS_SYNC`). No es una función de la posición del frame en el
 * índice: un GOP corto cerca del final del vídeo consume menos que un GOP largo al principio, y es el
 * coste real lo que hay que acotar. Al agotarse, la captura se bloquea con
 * [FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED]; el límite no se elimina ni se amplía.
 */
class DecodeBudget(val maxDecodedFrames: Int = CodecCapturePolicy.MAX_DECODED_FRAMES) {
    init {
        require(maxDecodedFrames >= 1) { "El presupuesto de decodificación debe ser positivo." }
    }

    var decodedFrames: Int = 0
        private set

    /** Registra una salida de frame decodificada; `false` si el presupuesto ya se agotó. */
    fun countDecodedFrame(): Boolean {
        decodedFrames += 1
        return decodedFrames <= maxDecodedFrames
    }
}

/**
 * Presupuesto del **bucle** para lo que no son frames: salidas sin payload y vueltas totales.
 *
 * [DecodeBudget] acota el trabajo útil (frames decodificados) y [CodecCapturePolicy.MAX_OUTPUT_WAIT_US]
 * acota la espera, pero un decodificador que entrega `EMPTY`/`CODEC_CONFIG` en bucle no consume
 * ninguno de los dos: la salida está disponible de inmediato y no es un frame. Este contador cierra
 * ese hueco sin tocar la decodificación válida (un frame nunca se cuenta aquí) y es puro Kotlin, así
 * que el bucle real y las pruebas JVM comparten la misma decisión.
 */
class DecoderLoopBudget(
    val maxEmptyOutputs: Int = CodecCapturePolicy.MAX_EMPTY_OUTPUT_BUFFERS,
    val maxIterations: Int = CodecCapturePolicy.MAX_DECODER_ITERATIONS,
) {
    init {
        require(maxEmptyOutputs >= 1) { "El presupuesto de salidas vacías debe ser positivo." }
        require(maxIterations >= maxEmptyOutputs) { "El presupuesto de iteraciones debe cubrir las salidas vacías." }
    }

    var emptyOutputs: Int = 0
        private set

    var iterations: Int = 0
        private set

    /** Registra una vuelta del bucle; `false` si ya se superó el tope total. */
    fun onIteration(): Boolean {
        iterations += 1
        return iterations <= maxIterations
    }

    /** Registra una salida sin payload de frame; `false` si ya se superó el tope. */
    fun onEmptyOutput(): Boolean {
        emptyOutputs += 1
        return emptyOutputs <= maxEmptyOutputs
    }
}

/**
 * Vista inmutable y mínima del índice que necesita el productor (evita duplicar PTS).
 *
 * El array de PTS **no se comparte con el llamador**: el constructor copia el array recibido y
 * [frameTimesUs] devuelve otra copia, así que mutar un array retenido por fuera no puede cambiar los
 * PTS que esta instantánea acredita ni los de una solicitud ya sellada con ella. Para leer un PTS sin
 * copiar el array completo está [ptsAt], que es la vía de la solicitud y de la validación por frame.
 *
 * La igualdad es de instancia (`class`, no `data class`): dos instantáneas con los mismos valores
 * describen la misma metadata, pero eso no las convierte en prueba de identidad de contenido; esa
 * prueba es [FrameSourceIdentity] y la acuña la sesión.
 */
class VideoFrameIndexSnapshot(
    frameTimesUs: LongArray,
    val durationUs: Long,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val declaredRotationDegrees: Int,
) {

    /** Copia privada: nadie fuera de esta instancia puede escribir los PTS acreditados. */
    private val frameTimes: LongArray = frameTimesUs.copyOf()

    init {
        require(frameTimes.isNotEmpty()) { "El índice no puede estar vacío." }
        VideoFrameSafety.checkedArea(encodedWidth, encodedHeight)
    }

    /** Copia de los PTS del índice; modificarla no altera esta instantánea. */
    val frameTimesUs: LongArray get() = frameTimes.copyOf()

    val frameCount: Int get() = frameTimes.size

    /** PTS del frame [index], sin copiar el array entero en cada lectura. */
    fun ptsAt(index: Int): Long {
        require(index in frameTimes.indices) {
            "El frame $index está fuera del índice sellado (${frameTimes.size} frames)."
        }
        return frameTimes[index]
    }

    val medianDeltaUs: Long
        get() = if (frameTimes.size < 2) 0L else {
            val deltas = LongArray(frameTimes.size - 1) { frameTimes[it + 1] - frameTimes[it] }
            deltas.sort()
            if (deltas.size % 2 == 1) deltas[deltas.size / 2] else (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2
        }
}

/**
 * Metadato entero de `MediaFormat` con estado explícito. Ausente e ilegible no se confunden entre
 * sí y ninguno se degrada a un valor supuesto: los dos bloquean el fallback de layout sin `Image`.
 */
internal sealed interface ExplicitInt {
    /** La clave no está presente en el formato. */
    object Absent : ExplicitInt

    /** La clave está presente pero no se puede leer como entero. */
    object Unreadable : ExplicitInt

    /** Valor declarado por el decodificador (puede ser <= 0: el plan lo rechaza). */
    data class Declared(val value: Int) : ExplicitInt
}

/** Plan del fallback NV12 sin `Image`: layout derivado y bytes reales de cada región. */
internal data class RawNv12OutputLayout(
    val layout: Yuv420OutputLayout,
    /** Bytes de luma: `stride * sliceHeight` (incluye el padding vertical declarado). */
    val yBytes: Long,
    /** Bytes de croma intercalado UV: `stride * ceil(sliceHeight/2)`. */
    val chromaBytes: Long,
) {
    /** Span mínimo que debe haber producido el códec desde `BufferInfo.offset`. */
    val requiredSpanBytes: Long get() = yBytes + chromaBytes
}

/** Resultado de derivar el layout sin `Image`: convertible o bloqueado con motivo tipado. */
internal sealed interface RawNv12OutputPlan {
    data class Valid(val layout: RawNv12OutputLayout) : RawNv12OutputPlan
    data class Unsupported(val detail: String) : RawNv12OutputPlan
}

/**
 * Diseño del buffer de salida YUV420: tamaño, crop visible y strides reales. Se valida **antes** de
 * leer píxeles: si el crop no es par o excede el buffer, o si los strides no cubren el muestreo
 * visible, se bloquea en lugar de inventar píxeles.
 *
 * Un ancho visible impar se acepta: en 4:2:0 la última columna comparte la muestra de croma de la
 * columna oculta adyacente (vecino más cercano), que es una lectura bien definida del buffer; lo que
 * se exige es que el row stride de croma cubra `ceil(ancho/2)` muestras. La conversión de color no se
 * certifica contra ningún render.
 */
data class Yuv420OutputLayout(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vRowStride: Int,
    val vPixelStride: Int,
) {
    // Aritmética en Long: un crop con valores extremos declarados por el decodificador no debe
    // desbordar a un ancho positivo pequeño que parezca convertible. Un span no representable se
    // expresa como 0 para que [rejectionReason] lo bloquee.
    private fun inclusiveSpan(first: Int, last: Int): Int {
        val span = last.toLong() - first.toLong() + 1L
        return if (span < 1L || span > Int.MAX_VALUE.toLong()) 0 else span.toInt()
    }

    val visibleWidth: Int get() = inclusiveSpan(cropLeft, cropRight)
    val visibleHeight: Int get() = inclusiveSpan(cropTop, cropBottom)

    /** Muestras de croma por fila que exige el ancho visible (4:2:0, redondeo hacia arriba). */
    val chromaColumns: Int get() = (visibleWidth + 1) / 2

    /** Motivo de rechazo, o `null` si el diseño es convertible sin adivinar. */
    fun rejectionReason(): String? {
        if (bufferWidth < 1 || bufferHeight < 1 || visibleWidth < 1 || visibleHeight < 1) {
            return "Dimensiones o crop no válidos ($bufferWidth×$bufferHeight, crop $cropLeft..$cropRight)."
        }
        if (cropLeft < 0 || cropTop < 0 || cropRight >= bufferWidth || cropBottom >= bufferHeight) {
            return "El crop excede el buffer de salida."
        }
        if (cropLeft % 2 != 0 || cropTop % 2 != 0) {
            return "Crop impar en YUV420: el muestreo de croma no está alineado."
        }
        if (yPixelStride != 1 || uPixelStride !in listOf(1, 2) || vPixelStride != uPixelStride) {
            return "Strides de croma no soportados (y=$yPixelStride u=$uPixelStride v=$vPixelStride)."
        }
        val lumaSpan = (visibleWidth - 1) * yPixelStride + 1
        if (yRowStride < lumaSpan) {
            return "Row stride de luma ($yRowStride) menor que el ancho visible ($visibleWidth)."
        }
        val chromaSpan = (chromaColumns - 1) * uPixelStride + 1
        if (uRowStride < chromaSpan || vRowStride < chromaSpan) {
            return "Row stride de croma (u=$uRowStride v=$vRowStride) menor que el muestreo visible ($chromaColumns muestras)."
        }
        return null
    }

    /**
     * Convierte el crop visible a ARGB con la matriz/rango [profile] (por defecto BT.601 limitado,
     * la suposición documentada cuando el formato no declara nada). Devuelve `null` si el layout no es
     * convertible o los buffers no alcanzan; nunca rellena con negro lo que no puede leer.
     */
    fun toArgb(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        profile: YuvColorProfile = YuvColorProfile.BT601_LIMITED,
        maxAllocationBytes: Long = VideoFrameSafety.MAX_WORKING_BYTES,
    ): IntArray? {
        if (rejectionReason() != null) return null
        // Presupuesto ANTES de asignar: convertir dimensiones imposibles en null, no en OOM.
        val arrayBytes = FrameFreezeBudget.argbArrayBytes(visibleWidth, visibleHeight) ?: return null
        if (arrayBytes > maxAllocationBytes) return null
        // Cada plano puede llegar con `position()` no cero (MediaCodec usa a veces un prefijo de
        // alineación) y `limit()` marca el final de la región legible. `slice()` normaliza la
        // ventana [position, limit) a [0, remaining): los índices calculados desde el layout son
        // relativos al **inicio real** del plano (el prefijo se respeta, no se pierde) y ningún
        // índice puede leer fuera de `limit`. Se valida en Long porque rowStride × fila desborda Int.
        val yPlane = y.slice()
        val uPlane = u.slice()
        val vPlane = v.slice()
        val yEnd = yPlane.limit().toLong()
        val uEnd = uPlane.limit().toLong()
        val vEnd = vPlane.limit().toLong()
        val lastLumaRow = cropTop.toLong() + visibleHeight - 1L
        val lastLumaColumn = cropLeft.toLong() + visibleWidth - 1L
        if (!inPlane(lastLumaRow * yRowStride + lastLumaColumn * yPixelStride, yEnd)) return null
        val lastChromaRow = lastLumaRow / 2L
        val lastChromaColumn = (visibleWidth - 1) / 2
        if (!inPlane(lastChromaRow * uRowStride + lastChromaColumn.toLong() * uPixelStride, uEnd)) return null
        if (!inPlane(lastChromaRow * vRowStride + lastChromaColumn.toLong() * vPixelStride, vEnd)) return null
        val pixelCount = Math.multiplyExact(visibleWidth, visibleHeight) // área ya acotada por la envolvente
        val out = IntArray(pixelCount)
        var outIndex = 0
        for (row in 0 until visibleHeight) {
            val bufferY = cropTop + row
            val yRowBase = bufferY.toLong() * yRowStride + cropLeft
            val chromaRow = (bufferY / 2).toLong()
            val uRowBase = chromaRow * uRowStride + (cropLeft / 2).toLong() * uPixelStride
            val vRowBase = chromaRow * vRowStride + (cropLeft / 2).toLong() * vPixelStride
            for (column in 0 until visibleWidth step 2) {
                val yIndex = yRowBase + column.toLong() * yPixelStride
                if (!inPlane(yIndex, yEnd)) return null
                val chromaIndex = (column / 2).toLong()
                val uIndex = uRowBase + chromaIndex * uPixelStride
                val vIndex = vRowBase + chromaIndex * vPixelStride
                if (!inPlane(uIndex, uEnd) || !inPlane(vIndex, vEnd)) return null
                val uValue = uPlane.get(uIndex.toInt()).toInt() and 0xFF
                val vValue = vPlane.get(vIndex.toInt()).toInt() and 0xFF
                out[outIndex++] = profile.toArgb(lightness(yPlane.get(yIndex.toInt())), uValue, vValue)
                if (column + 1 < visibleWidth) {
                    val secondYIndex = yIndex + yPixelStride
                    if (!inPlane(secondYIndex, yEnd)) return null
                    out[outIndex++] = profile.toArgb(lightness(yPlane.get(secondYIndex.toInt())), uValue, vValue)
                }
            }
        }
        return if (outIndex == out.size) out else null
    }

    private fun inPlane(index: Long, end: Long): Boolean =
        index >= 0L && index < end

    private fun lightness(value: Byte): Int = value.toInt() and 0xFF

    companion object {
        /** Formatos que esta capa sabe leer por [Image] o por buffer plano/semiplanar. */
        private val SUPPORTED_COLOR_FORMATS = setOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        )

        fun isSupportedColorFormat(colorFormat: Int): Boolean = colorFormat in SUPPORTED_COLOR_FORMATS

        /**
         * Veredicto del **fallback de buffer plano** (sin objeto [Image]) para [colorFormat].
         *
         * Solo el semiplanar NV12 se acepta: `KEY_STRIDE`/`KEY_SLICE_HEIGHT` declaran el stride de
         * luma y el croma intercalado usa ese mismo stride con paso 2, de modo que el layout es
         * derivable y el span se comprueba contra el buffer antes de leer.
         *
         * El planar I420 (y el "flexible" sin `Image`) se **bloquea**: `MediaCodec` no declara el
         * row stride ni el offset del plano U/V, y suponer `stride/2` y planos contiguos entregaría
         * píxeles no verificados como si fueran el frame. La ruta correcta para esos formatos es
         * `Image`, que sí declara `rowStride`/`pixelStride`/`cropRect` por plano. Devuelve `null` si
         * el layout es demostrable y un motivo tipado si no.
         */
        fun rawFallbackRejection(colorFormat: Int): String? = when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> null
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ->
                "Fallback de buffer planar (I420) bloqueado: MediaCodec no declara stride ni offset del plano U/V; " +
                    "se necesitaría un Image con planes comprobables."
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ->
                "Fallback de buffer flexible bloqueado: sin Image no hay stride/offset de croma declarados que demuestren el layout."
            else -> "Color format de salida no soportado (0x${colorFormat.toString(16)})."
        }

        /**
         * Fallback NV12 **fail-closed**: exige `KEY_STRIDE` y `KEY_SLICE_HEIGHT` explícitos y válidos.
         *
         * Antes se sustituían los metadatos ausentes o `<= 0` por `width`/`height`. Sin `Image` eso no
         * demuestra dónde empieza el croma: un buffer con filas de luma de padding se leería como si
         * el padding fuese UV y el frame se aceptaría con el PTS correcto pero píxeles inventados. El
         * layout sólo se deriva de valores declarados; ausente, ilegible o no positivo bloquea.
         *
         * El span exigido se mide sobre el bloque **realmente producido** (`BufferInfo.offset`/
         * `size`), nunca sobre `capacity()`: los bytes de padding de la capacidad no son del frame.
         */
        internal fun fromDeclaredBuffer(
            width: Int,
            height: Int,
            colorFormat: Int,
            declaredStride: ExplicitInt,
            declaredSliceHeight: ExplicitInt,
            crop: CropWindow?,
        ): RawNv12OutputPlan {
            rawFallbackRejection(colorFormat)?.let { return RawNv12OutputPlan.Unsupported(it) }
            if (width <= 0 || height <= 0) {
                return RawNv12OutputPlan.Unsupported("El formato de salida no declara dimensiones válidas ($width×$height).")
            }
            val stride = when (declaredStride) {
                ExplicitInt.Absent -> return RawNv12OutputPlan.Unsupported(
                    "Fallback NV12 bloqueado: el formato no declara KEY_STRIDE y sin él no se demuestra dónde empieza el croma.",
                )
                ExplicitInt.Unreadable -> return RawNv12OutputPlan.Unsupported(
                    "Fallback NV12 bloqueado: KEY_STRIDE está presente pero no es legible como entero.",
                )
                is ExplicitInt.Declared -> declaredStride.value
            }
            val sliceHeight = when (declaredSliceHeight) {
                ExplicitInt.Absent -> return RawNv12OutputPlan.Unsupported(
                    "Fallback NV12 bloqueado: el formato no declara KEY_SLICE_HEIGHT y sin él no se demuestra el tamaño de la luma.",
                )
                ExplicitInt.Unreadable -> return RawNv12OutputPlan.Unsupported(
                    "Fallback NV12 bloqueado: KEY_SLICE_HEIGHT está presente pero no es legible como entero.",
                )
                is ExplicitInt.Declared -> declaredSliceHeight.value
            }
            if (stride <= 0) {
                return RawNv12OutputPlan.Unsupported("KEY_STRIDE debe ser positivo (declarado $stride).")
            }
            if (sliceHeight <= 0) {
                return RawNv12OutputPlan.Unsupported("KEY_SLICE_HEIGHT debe ser positivo (declarado $sliceHeight).")
            }
            if (stride < width) {
                return RawNv12OutputPlan.Unsupported("Row stride ($stride) menor que el ancho declarado ($width).")
            }
            if (stride % 2 != 0) {
                return RawNv12OutputPlan.Unsupported("Row stride impar ($stride): el muestreo 4:2:0 no está alineado.")
            }
            if (sliceHeight < height) {
                return RawNv12OutputPlan.Unsupported("El buffer declara $sliceHeight filas y el frame $height.")
            }
            val layout = Yuv420OutputLayout.fromBuffer(width, height, colorFormat, stride, sliceHeight, crop)
            layout.rejectionReason()?.let { return RawNv12OutputPlan.Unsupported(it) }
            val chromaRows = (sliceHeight + 1) / 2
            val yBytes = stride.toLong() * sliceHeight.toLong()
            val chromaBytes = stride.toLong() * chromaRows.toLong()
            val requiredSpan = yBytes + chromaBytes
            if (requiredSpan > Int.MAX_VALUE.toLong()) {
                return RawNv12OutputPlan.Unsupported("El layout declarado no cabe en un buffer de salida.")
            }
            return RawNv12OutputPlan.Valid(
                RawNv12OutputLayout(layout = layout, yBytes = yBytes, chromaBytes = chromaBytes),
            )
        }

        /**
         * Valida la ventana realmente producida por el códec: `[offset, offset+size)` debe caber en la
         * capacidad y cubrir el layout declarado. Devuelve el motivo tipado o `null` si es legible.
         */
        internal fun producedSpanRejection(offset: Int, size: Int, capacity: Int, requiredSpanBytes: Long): String? {
            if (offset < 0) return "El buffer declara un offset negativo ($offset)."
            if (size <= 0) return "El códec no produjo bytes para el frame ($size)."
            val end = offset.toLong() + size.toLong()
            if (end > capacity.toLong()) {
                return "La ventana producida ($offset+$size) excede la capacidad del buffer ($capacity)."
            }
            if (size.toLong() < requiredSpanBytes) {
                return "El códec produjo $size bytes y el layout declarado exige $requiredSpanBytes desde el offset $offset."
            }
            return null
        }

        /** Construye el layout desde un [Image] de salida del códec (formato flexible). */
        fun fromImage(image: Image): Yuv420OutputLayout {
            val crop = image.cropRect
            val planes = image.planes
            require(planes.size >= 3) { "El plano de salida no tiene tres planos YUV." }
            return Yuv420OutputLayout(
                bufferWidth = image.width,
                bufferHeight = image.height,
                cropLeft = crop.left,
                cropTop = crop.top,
                // cropRect ya usa límites exclusivos, así que el último píxel es right/bottom - 1.
                cropRight = crop.right - 1,
                cropBottom = crop.bottom - 1,
                yRowStride = planes[0].rowStride,
                yPixelStride = planes[0].pixelStride,
                uRowStride = planes[1].rowStride,
                uPixelStride = planes[1].pixelStride,
                vRowStride = planes[2].rowStride,
                vPixelStride = planes[2].pixelStride,
            )
        }

        /** Layout de un buffer plano/semiplanar sin objeto [Image]. */
        fun fromBuffer(
            width: Int,
            height: Int,
            colorFormat: Int,
            stride: Int,
            sliceHeight: Int,
            crop: CropWindow?,
        ): Yuv420OutputLayout {
            val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            // Semiplanar (NV12): U y V se intercalan en la misma fila del buffer, así que el row
            // stride de croma es el stride completo y el pixel stride 2. Planar: MediaCodec no
            // expone el stride del plano U/V; se asume stride/2 y se valida que cubra el muestreo
            // visible (suposición documentada, no una garantía del formato).
            val chromaRowStride = if (semiPlanar) stride else (stride + 1) / 2
            val chromaPixelStride = if (semiPlanar) 2 else 1
            return Yuv420OutputLayout(
                bufferWidth = width,
                bufferHeight = height,
                cropLeft = crop?.left ?: 0,
                cropTop = crop?.top ?: 0,
                // [CropWindow] es inclusivo, igual que KEY_CROP_RIGHT/KEY_CROP_BOTTOM: el último
                // píxel visible es `right`, no `right - 1`.
                cropRight = crop?.right ?: (width - 1),
                cropBottom = crop?.bottom ?: (height - 1),
                yRowStride = stride,
                yPixelStride = 1,
                uRowStride = chromaRowStride,
                uPixelStride = chromaPixelStride,
                vRowStride = chromaRowStride,
                vPixelStride = chromaPixelStride,
            )
        }
    }
}

/**
 * Ventana de crop en la semántica **inclusiva** de `MediaFormat.KEY_CROP_RIGHT`/`KEY_CROP_BOTTOM`:
 * `right`/`bottom` son la última columna/fila visibles. `android.graphics.Rect` usa límites
 * **exclusivos**, así que mezclar ambas convenciones pierde una columna/fila (o la inventa).
 */
data class CropWindow(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0) { "El crop no puede empezar antes de la primera muestra." }
        require(right >= left && bottom >= top) { "Un crop inclusivo exige right>=left y bottom>=top." }
    }
}

/**
 * Interpretación de las claves de crop del decodificador. `Absent` (el buffer completo es el frame),
 * `Present` (ventana inclusiva validada) e `Invalid` (declarada pero no interpretable) se distinguen
 * explícitamente: una declaración inválida **no** se degrada a "sin crop" ni se recorta a ciegas.
 */
sealed interface CropDeclaration {
    /** Ninguna clave de crop presente: el frame visible es el buffer completo. */
    object Absent : CropDeclaration

    data class Present(val window: CropWindow) : CropDeclaration

    /** Claves presentes pero no interpretables: el llamador debe bloquear. */
    data class Invalid(val detail: String) : CropDeclaration

    companion object {
        /**
         * Interpreta los valores declarados; `null` significa que la clave no está presente.
         *
         * - Ninguna clave → [Absent].
         * - `crop-right`/`crop-bottom` presentes → ventana inclusiva; `crop-left`/`crop-top`
         *   ausentes se toman como `0` (el origen del buffer).
         * - Sólo bordes izquierdo/superior, origen negativo, orden invertido o un límite que no cabe
         *   en un rectángulo exclusivo → [Invalid].
         *
         * Un crop de una sola columna/fila es válido en semántica inclusiva (`right == left`).
         */
        fun ofDeclared(left: Int?, top: Int?, right: Int?, bottom: Int?): CropDeclaration {
            if (left == null && top == null && right == null && bottom == null) return Absent
            if (right == null || bottom == null) {
                return Invalid("El crop declara bordes izquierdo/superior sin derecho/inferior.")
            }
            val cropLeft = left ?: 0
            val cropTop = top ?: 0
            if (cropLeft < 0 || cropTop < 0) {
                return Invalid("El crop declara un origen negativo ($cropLeft,$cropTop).")
            }
            if (right < cropLeft || bottom < cropTop) {
                return Invalid("El crop inclusivo está invertido ($cropLeft..$right, $cropTop..$bottom).")
            }
            if (right == Int.MAX_VALUE || bottom == Int.MAX_VALUE) {
                return Invalid("El crop no es representable como rectángulo exclusivo ($right,$bottom).")
            }
            return Present(CropWindow(cropLeft, cropTop, right, bottom))
        }
    }
}

/** Perfil de conversión YUV→ARGB decidido por lo que el formato **declara**, no por una suposición fija. */
enum class YuvColorProfile {
    BT601_LIMITED,
    BT709_LIMITED,
    BT601_FULL,
    BT709_FULL,
    ;

    /** Convierte un píxel YUV (8 bits por muestra) a ARGB opaco con la matriz/rango de este perfil. */
    fun toArgb(y: Int, u: Int, v: Int): Int {
        val d = u - 128
        val e = v - 128
        val (red, green, blue) = when (this) {
            BT601_LIMITED -> {
                val c = (y - 16).coerceAtLeast(0)
                Triple(298 * c + 409 * e, 298 * c - 100 * d - 208 * e, 298 * c + 516 * d)
            }
            BT709_LIMITED -> {
                val c = (y - 16).coerceAtLeast(0)
                Triple(298 * c + 459 * e, 298 * c - 54 * d - 137 * e, 298 * c + 541 * d)
            }
            BT601_FULL -> Triple(
                256 * y + 359 * e,
                256 * y - 88 * d - 183 * e,
                256 * y + 454 * d,
            )
            BT709_FULL -> Triple(
                256 * y + 403 * e,
                256 * y - 48 * d - 120 * e,
                256 * y + 475 * d,
            )
        }
        val r = ((red + 128) shr 8).coerceIn(0, 255)
        val g = ((green + 128) shr 8).coerceIn(0, 255)
        val b = ((blue + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Traduce los metadatos de color declarados por el contenedor o por el decodificador a un perfil de
 * conversión soportado, o a `null` si no puede convertirse sin inventar la representación.
 *
 * - Rango/estándar ausentes: se asume BT.601 de rango limitado (suposición documentada y única que
 *   mantiene el comportamiento anterior para formatos sin metadatos de color). El valor `0` se trata
 *   como ausente: es el "unspecified" de Codec2 y no declara matriz ni rango. La resolución
 *   salida→entrada se hace en [resolveDeclared], que aplica esa misma normalización antes del
 *   fallback para que un `0` declarado por la salida no silencie lo declarado por la entrada.
 * - BT.601 (PAL/NTSC) y BT.709, limitado o completo: se convierten con su matriz y rango.
 * - HDR (PQ/HLG), BT.2020, transferencias distintas de SDR y valores desconocidos: `null` (bloqueo).
 */
object YuvColorContract {
    /** El formato no declara la clave. */
    const val UNSPECIFIED = Int.MIN_VALUE

    /** La clave está presente pero no es un entero legible: no se adivina. */
    const val UNREADABLE = Int.MIN_VALUE + 1

    /**
     * Valor efectivo de una clave de color: el declarado por la **salida** del decodificador si
     * declara algo, y si no el declarado por el track de entrada.
     *
     * La salida puede declarar la clave con valor `0` (el "unspecified" de Codec2). Ese `0` no
     * declara matriz, transferencia ni rango, así que se normaliza a [UNSPECIFIED] **antes** del
     * fallback: no puede silenciar un BT.709/full/HDR que sí declare la entrada. Un valor
     * [UNREADABLE] sí se propaga para que [profileFor] bloquee en lugar de adivinar.
     */
    fun resolveDeclared(outputValue: Int, inputValue: Int): Int = when (outputValue) {
        UNSPECIFIED, 0 -> inputValue
        else -> outputValue
    }

    fun profileFor(colorStandard: Int, colorTransfer: Int, colorRange: Int): YuvColorProfile? {
        if (colorStandard == UNREADABLE || colorTransfer == UNREADABLE || colorRange == UNREADABLE) return null
        val standard = if (colorStandard == 0) UNSPECIFIED else colorStandard
        val transfer = if (colorTransfer == 0) UNSPECIFIED else colorTransfer
        val range = if (colorRange == 0) UNSPECIFIED else colorRange
        if (transfer != UNSPECIFIED && transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO) return null
        val matrix709 = when (standard) {
            UNSPECIFIED,
            MediaFormat.COLOR_STANDARD_BT601_PAL,
            MediaFormat.COLOR_STANDARD_BT601_NTSC,
            -> false
            MediaFormat.COLOR_STANDARD_BT709 -> true
            else -> return null
        }
        val full = when (range) {
            UNSPECIFIED, MediaFormat.COLOR_RANGE_LIMITED -> false
            MediaFormat.COLOR_RANGE_FULL -> true
            else -> return null
        }
        return when {
            matrix709 && full -> YuvColorProfile.BT709_FULL
            matrix709 -> YuvColorProfile.BT709_LIMITED
            full -> YuvColorProfile.BT601_FULL
            else -> YuvColorProfile.BT601_LIMITED
        }
    }
}

/**
 * Productor real de imágenes congeladas: `MediaExtractor` + `MediaCodec`.
 *
 * Garantías:
 * - Los píxeles se leen **del mismo buffer de salida** cuyo `presentationTimeUs` se usa como PTS
 *   reportado; la generación se acuña y consume en esa iteración.
 * - Solo se entrega [FrozenFrameImage] si el PTS coincide exactamente con el solicitado, la fuente y
 *   el frame coinciden con la evidencia, y la orientación es demostrable.
 * - Fail-closed: si el PTS se pasa, no se alcanza dentro del presupuesto, el layout no es convertible
 *   o el códec falla, se devuelve [FrameFreezeResult.Blocked]. **Nunca** se cae a
 *   `MediaMetadataRetriever` ni se devuelve otro fotograma.
 * - `MediaCodec`, `MediaExtractor` y las imágenes intermedias se liberan en `finally`, también al
 *   cancelar o fallar.
 *
 * - La identidad de la fuente ([FrameFreezeRequest.sourceIdentity]) la acuña
 *   [FrozenFrameSourceSession] al sellar el snapshot privado; el productor exige que la solicitud
 *   lleve esa identidad y lee el descriptor del snapshot, no la ruta/URI original. Así, reemplazar la
 *   fuente o que otro editor escriba encima no atribuye el índice a otro contenido. Aun así, el
 *   snapshot no acredita el estado externo del proveedor en el instante posterior a la copia ni
 *   detecta una lectura mixta si otro proceso escribe durante la copia: índice y captura usan esa
 *   lectura, no "lo que la URI diga ahora".
 *
 * Límites: la ruta MediaCodec se ejecutó en un AVD API 35 con decodificador goldfish (evidencia de
 * 2026-11-10); esta revisión no re-ejecuta AVD. Hardware físico, API 26–27, HDR y fidelidad de color
 * frente a un render de referencia **no** están demostrados. El presupuesto de captura cuenta matriz
 * ARGB + bitmap decodificado + copias congeladas del peor caso: 1080p cabe; 1440p y 4K se rechazan
 * con [FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED] y **no** se declaran soportados.
 */
/**
 * Reserva **de proceso** del presupuesto de trabajo de las capturas **en vuelo** (64 MiB por
 * proceso, no por captura).
 *
 * Varias sesiones pueden capturar a la vez en el mismo proceso y cada una comprobaba el presupuesto
 * de forma aislada: dos capturas 1080p válidas por separado podían sumar el doble del presupuesto.
 * Aquí la reserva se acumula y se concede solo si la suma cabe, **sin bloquear ni esperar** a la
 * captura en curso: si no cabe, el llamador devuelve un rechazo tipado
 * ([FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED]) y la UI puede reintentar. La liberación es
 * idempotente y se ejecuta en `finally`, así que una excepción o una cancelación no dejan bytes
 * reservados.
 *
 * ## Alcance real del límite (leer antes de citarlo como tope del proceso)
 *
 * El contador cubre **solo el trabajo de las capturas que están en vuelo**: la matriz ARGB de la
 * conversión, el bitmap decodificado y las copias congeladas del peor caso de cada captura
 * concurrente. No es un tope de la memoria total del proceso:
 *
 * - La reserva se libera en el `finally` de `capture`, es decir **antes** de que el llamador reciba
 *   el [FrameFreezeResult.Frozen]; la imagen devuelta ya no está contabilizada.
 * - Nada aquí limita cuántas [FrozenFrameImage] retenga la UI ni la memoria de otros componentes
 *   (códec, extractor, bitmaps de presentación). La gestión de imágenes retenidas es responsabilidad
 *   de la integración (cerrar cada imagen con [FrozenFrameImage.close]) y **sigue siendo una barrera
 *   abierta**: este presupuesto no la cubre.
 * - Tampoco se apoya en memoria libre del sistema: dos capturas cabiendo en 64 MiB no garantizan que
 *   la asignación real no lance [OutOfMemoryError]; ese caso se convierte en rechazo tipado en la
 *   ruta que asigna, no aquí.
 *
 * No toca permisos, sesiones de fuente ni snapshots: solo contadores en memoria del proceso. Al morir
 * el proceso el contador desaparece con él.
 *
 * Es Kotlin puro para poder probar de forma determinista la concurrencia, la liberación y el
 * reintento en JVM.
 */
internal object FrameCaptureReservations {

    private val reservedBytes = AtomicLong(0L)

    /** Bytes actualmente reservados por capturas en curso (diagnóstico y pruebas). */
    fun currentReservedBytes(): Long = reservedBytes.get()

    /** Bytes que aún caben en el presupuesto de proceso para capturas en vuelo. */
    fun remainingBytes(limitBytes: Long = VideoFrameSafety.MAX_WORKING_BYTES): Long =
        (limitBytes - reservedBytes.get()).coerceAtLeast(0L)

    /**
     * Intenta reservar [bytes] de forma atómica y no bloqueante; `null` si no caben.
     *
     * Una petición mayor que el presupuesto completo se rechaza sin tocar el contador. El bucle CAS
     * resuelve la carrera entre dos capturas simultáneas: solo una suma se aplica y la otra ve el
     * total actualizado.
     */
    fun tryAcquire(bytes: Long, limitBytes: Long = VideoFrameSafety.MAX_WORKING_BYTES): Reservation? {
        if (bytes <= 0L || bytes > limitBytes) return null
        while (true) {
            val current = reservedBytes.get()
            val next = current + bytes
            if (next > limitBytes) return null
            if (reservedBytes.compareAndSet(current, next)) return Reservation(bytes) { released ->
                reservedBytes.addAndGet(-released)
            }
        }
    }

    /** Reserva concedida; [release] es idempotente. */
    class Reservation internal constructor(
        private val bytes: Long,
        private val releaser: (Long) -> Unit,
    ) {

        private val released = AtomicBoolean(false)

        val isReleased: Boolean get() = released.get()

        fun release() {
            if (released.compareAndSet(false, true)) releaser(bytes)
        }
    }
}

class CodecFrozenFrameProducer(
    private val policy: FrameFreezePolicy = FrameFreezePolicy(),
) {

    internal fun capture(
        source: FrozenFrameSourceSession,
        request: FrameFreezeRequest,
        cancellationCheck: () -> Boolean = { false },
    ): FrameFreezeResult {
        if (!source.isOpen) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.SOURCE_SESSION_INVALID,
                "La sesión de fuente está cerrada.",
            )
        }
        // La identidad de la solicitud no se acepta como prueba: debe ser la acuñada por la sesión
        // y el resto de metadatos debe coincidir con el índice sellado del mismo snapshot privado.
        source.binding.validate(request)?.let { return it }
        if (cancellationCheck()) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.CANCELLED,
                "La captura se canceló antes de abrir el snapshot.",
            )
        }
        val descriptor = when (val opened = source.openSnapshotDescriptor()) {
            is SnapshotDescriptorOpen.Opened -> opened.descriptor
            // Sesión cerrada, snapshot purgado de caché o fichero con otra longitud: rechazo
            // tipado, nunca una excepción de estado que escape a la UI.
            is SnapshotDescriptorOpen.Blocked -> return opened.result
        }
        // El presupuesto de 64 MiB acota las capturas **en vuelo** del proceso, no la memoria total
        // del proceso ni las imágenes retenidas después: varias sesiones pueden congelar a la vez y sus
        // matrices ARGB + bitmaps + copias congeladas se suman mientras dura la captura. La reserva es
        // no bloqueante (nunca espera a la otra captura) y se libera en `finally`, también al fallar o
        // cancelar, de modo que un reintento puede volver a entrar; la imagen entregada queda fuera de
        // la contabilidad y su cierre corresponde a quien la retiene.
        val requiredBytes = FrameFreezeBudget.captureRequiredBytes(
            decodedWidth = request.encodedWidth,
            decodedHeight = request.encodedHeight,
            frozenWidth = request.encodedWidth,
            frozenHeight = request.encodedHeight,
        ) ?: run {
            descriptor.close()
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "Las dimensiones ${request.encodedWidth}x${request.encodedHeight} no son presupuestables.",
            )
        }
        val reservation = FrameCaptureReservations.tryAcquire(requiredBytes) ?: run {
            descriptor.close()
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "Otra captura en curso ocupa el presupuesto de capturas en vuelo del proceso: esta necesitaría $requiredBytes " +
                    "bytes y quedan ${FrameCaptureReservations.remainingBytes()} de ${VideoFrameSafety.MAX_WORKING_BYTES}.",
            )
        }
        return try {
            descriptor.use { snapshotDescriptor ->
                captureFromSnapshot(source, snapshotDescriptor, request, cancellationCheck)
            }
        } finally {
            // Liberación incondicional: excepción, cancelación o retorno normal.
            reservation.release()
        }
    }

    private fun captureFromSnapshot(
        source: FrozenFrameSourceSession,
        snapshotDescriptor: ParcelFileDescriptor,
        request: FrameFreezeRequest,
        cancellationCheck: () -> Boolean,
    ): FrameFreezeResult {
        val index = source.index
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var interrupted: FrameFreezeResult.Blocked? = null
        try {
            // El descriptor pertenece al snapshot privado sellado: la misma longitud exacta que se
            // indexó. No se lee la ruta ni la URI original, que pueden haber cambiado.
            extractor.setDataSource(snapshotDescriptor.fileDescriptor, 0L, source.snapshotLengthBytes)
            val trackIndex = findVideoTrack(extractor)
                ?: return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                    "El archivo no contiene un track de vídeo.",
                )
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                    "El track de vídeo no declara MIME.",
                )
            if (!mime.startsWith("video/")) {
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                    "El MIME del track no es de vídeo ($mime).",
                )
            }
            val formatWidth = format.optionalInt(MediaFormat.KEY_WIDTH)
            val formatHeight = format.optionalInt(MediaFormat.KEY_HEIGHT)
            if (formatWidth != request.encodedWidth || formatHeight != request.encodedHeight) {
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                    "El contenedor declara ${formatWidth}x$formatHeight y el índice ${request.encodedWidth}x${request.encodedHeight}.",
                )
            }
            val formatRotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.optionalInt(MediaFormat.KEY_ROTATION)
            } else {
                request.declaredRotationDegrees
            }
            if (formatRotation != request.declaredRotationDegrees) {
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                    "La rotación declarada ($formatRotation) no coincide con la solicitada (${request.declaredRotationDegrees}).",
                )
            }
            // Duración: el índice la tomó del mismo snapshot sellado que se acaba de abrir. Una
            // diferencia mayor que un segundo o dos frames indica que el descriptor no corresponde al
            // índice; se bloquea en lugar de atribuir ese PTS a otro contenido.
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val containerDurationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
                val toleranceUs = maxOf(1_000_000L, 2L * maxOf(1L, index.medianDeltaUs))
                if (containerDurationUs != null &&
                    index.durationUs > 0L &&
                    kotlin.math.abs(containerDurationUs - index.durationUs) > toleranceUs
                ) {
                    return FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.SOURCE_METADATA_MISMATCH,
                        "La duración del contenedor (${containerDurationUs}µs) no coincide con la del índice (${index.durationUs}µs).",
                    )
                }
            }

            extractor.selectTrack(trackIndex)
            extractor.seekTo(request.assignedPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            // Se configura SIN superficie (`format, null, null, 0`): la salida llega por
            // getOutputImage/getOutputBuffer y el framework no compone ninguna transformación de
            // superficie. Esa configuración es la evidencia explícita de orientación que se pasa al
            // planificador y a la política; sin ella, 180 y cuadrado 90/270 se bloquean.
            codec.configure(format, null, null, 0)
            codec.start()
            val orientationEvidence = FrameOrientationEvidence.bufferModeWithoutSurface()

            val info = MediaCodec.BufferInfo()
            val decodeBudget = DecodeBudget()
            // Presupuesto del bucle: salidas sin payload y vueltas totales. [DecodeBudget] y
            // MAX_OUTPUT_WAIT_US no cubren un decodificador que entrega EMPTY en bucle.
            val loopBudget = DecoderLoopBudget()
            var inputDone = false
            var sawEndOfStream = false
            var waitedUs = 0L
            var pendingPixels: IntArray? = null
            var pendingWidth = 0
            var pendingHeight = 0
            var pendingPtsUs = 0L
            var pendingGeneration: CodecOutputGeneration? = null
            // Se evalúa dentro de readArgb, antes de asignar la matriz ARGB: cancelación, orientación
            // y presupuesto completo (matriz + bitmap decodificado + copias congeladas del peor caso).
            val allocationGate: (Int, Int) -> FrameFreezeResult.Blocked? = { width, height ->
                if (cancellationCheck()) {
                    FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.CANCELLED,
                        "La captura se canceló antes de asignar la imagen.",
                    )
                } else {
                    val orientation = try {
                        FrameOrientationPlanner.plan(
                            encodedWidth = request.encodedWidth,
                            encodedHeight = request.encodedHeight,
                            declaredRotationDegrees = request.declaredRotationDegrees,
                            decodedWidth = width,
                            decodedHeight = height,
                            evidence = orientationEvidence,
                        )
                    } catch (_: IllegalArgumentException) {
                        FrameOrientationPlan.Undetermined(
                            FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
                        )
                    }
                    when {
                        orientation !is FrameOrientationPlan.Determined -> FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                            "La orientación de ${width}x$height no puede demostrarse antes de asignar la imagen.",
                        )
                        !FrameFreezeBudget.isWithinCaptureBudget(
                            decodedWidth = width,
                            decodedHeight = height,
                            frozenWidth = orientation.orientedWidth,
                            frozenHeight = orientation.orientedHeight,
                        ) -> {
                            val required = FrameFreezeBudget.captureRequiredBytes(
                                width,
                                height,
                                orientation.orientedWidth,
                                orientation.orientedHeight,
                            )
                            FrameFreezeResult.Blocked(
                                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                                "La conversión y las copias congeladas de ${width}x$height requieren " +
                                    "${required ?: -1L} bytes y el presupuesto es ${VideoFrameSafety.MAX_WORKING_BYTES}.",
                            )
                        }
                        else -> null
                    }
                }
            }

            while (true) {
                if (cancellationCheck()) {
                    interrupted = FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.CANCELLED,
                        "La captura se canceló antes de verificar el frame.",
                    )
                    break
                }
                if (!loopBudget.onIteration()) {
                    interrupted = FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED,
                        "El bucle del decodificador superó ${loopBudget.maxIterations} iteraciones sin resolver el PTS.",
                    )
                    break
                }

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    waitedUs += 10_000L
                    // El track ya cerró: esperar hasta el tope de espera solo retrasaría un bloqueo
                    // que ya está decidido.
                    if (sawEndOfStream) {
                        interrupted = FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                            "El track terminó sin el PTS solicitado (${request.assignedPtsUs}µs).",
                        )
                        break
                    }
                    if (waitedUs > CodecCapturePolicy.MAX_OUTPUT_WAIT_US) {
                        interrupted = FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED,
                            "El decodificador no entregó salida dentro del presupuesto de espera.",
                        )
                        break
                    }
                    continue
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    val colorFormat = outputFormat.optionalInt(MediaFormat.KEY_COLOR_FORMAT)
                    if (!Yuv420OutputLayout.isSupportedColorFormat(colorFormat)) {
                        interrupted = FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                            "Color format de salida no soportado (0x${colorFormat.toString(16)}).",
                        )
                        break
                    }
                    continue
                }
                if (outputIndex < 0) continue

                val ptsUs = info.presentationTimeUs
                when (CodecCapturePolicy.classifyOutputBuffer(info.flags, info.size)) {
                    CodecOutputBufferKind.CODEC_CONFIG -> {
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (CodecCapturePolicy.endsStream(info.flags)) {
                            inputDone = true
                            interrupted = FrameFreezeResult.Blocked(
                                FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                                "El track terminó sin el PTS solicitado.",
                            )
                            break
                        }
                        // Una salida CODEC_CONFIG tampoco es un frame: cuenta en el presupuesto de
                        // salidas sin payload para que un decodificador que la repita no gire sin fin.
                        if (!loopBudget.onEmptyOutput()) {
                            interrupted = FrameFreezeResult.Blocked(
                                FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED,
                                "El decodificador entregó más de ${loopBudget.maxEmptyOutputs} salidas sin frame.",
                            )
                            break
                        }
                        continue
                    }
                    CodecOutputBufferKind.END_OF_STREAM_WITHOUT_PAYLOAD -> {
                        // Un EOS sin píxeles no es un frame: no consume presupuesto de decodificación
                        // ni se compara por PTS (podría coincidir por azar) ni se lee como imagen.
                        codec.releaseOutputBuffer(outputIndex, false)
                        interrupted = FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                            "El track terminó en ${ptsUs}µs sin el PTS solicitado (${request.assignedPtsUs}µs).",
                        )
                        break
                    }
                    CodecOutputBufferKind.EMPTY -> {
                        // Un buffer sin payload no acredita ningún frame ni gasta [DecodeBudget], pero
                        // sí el presupuesto de salidas vacías: si el decodificador las repite, se
                        // bloquea en vez de girar indefinidamente.
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (!loopBudget.onEmptyOutput()) {
                            interrupted = FrameFreezeResult.Blocked(
                                FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED,
                                "El decodificador entregó más de ${loopBudget.maxEmptyOutputs} salidas vacías sin frame.",
                            )
                            break
                        }
                        continue
                    }
                    CodecOutputBufferKind.FRAME -> Unit
                }
                if (CodecCapturePolicy.endsStream(info.flags)) sawEndOfStream = true
                if (!decodeBudget.countDecodedFrame()) {
                    codec.releaseOutputBuffer(outputIndex, false)
                    interrupted = FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.DECODE_BUDGET_EXCEEDED,
                        "El PTS solicitado no se alcanzó en ${decodeBudget.decodedFrames} frames decodificados desde el sync.",
                    )
                    break
                }

                when (CodecCapturePolicy.decideOutputPts(ptsUs, request.assignedPtsUs)) {
                    CodecCapturePolicy.OutputPtsDecision.KEEP_DECODING -> {
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (sawEndOfStream) {
                            interrupted = FrameFreezeResult.Blocked(
                                FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                                "El último frame del track se decodificó en ${ptsUs}µs sin alcanzar ${request.assignedPtsUs}µs.",
                            )
                            break
                        }
                        continue
                    }
                    CodecCapturePolicy.OutputPtsDecision.PASSED_TARGET -> {
                        codec.releaseOutputBuffer(outputIndex, false)
                        interrupted = FrameFreezeResult.Blocked(
                            FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                            "La decodificación pasó de ${request.assignedPtsUs}µs a ${ptsUs}µs sin coincidir.",
                        )
                        break
                    }
                    CodecCapturePolicy.OutputPtsDecision.CAPTURE -> Unit
                }

                // Mismo índice de buffer: la generación se acuña antes de liberar la salida y se
                // consume al construir la evidencia; los píxeles se copian en esta iteración.
                val generation = CodecOutputGeneration.mint(outputIndex)
                val captured = readArgb(codec, outputIndex, info, format, allocationGate, cancellationCheck)
                codec.releaseOutputBuffer(outputIndex, false)
                if (captured is CodecPixelCapture.Blocked) {
                    interrupted = captured.blocked
                    break
                }
                if (captured is CodecPixelCapture.Unsupported) {
                    interrupted = FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                        captured.detail,
                    )
                    break
                }
                val converted = captured as CodecPixelCapture.Argb
                pendingPixels = converted.pixels
                pendingWidth = converted.width
                pendingHeight = converted.height
                pendingPtsUs = ptsUs
                pendingGeneration = generation
                break
            }

            interrupted?.let {
                pendingPixels = null
                return it
            }
            // La matriz ARGB ya está asignada; el bitmap creado a partir de ella se presupuesta y su
            // asignación se protege para convertir un OOM en rechazo tipado.
            var capturedPixels: IntArray? = pendingPixels
            pendingPixels = null
            val generation = pendingGeneration
            if (capturedPixels == null || generation == null) {
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                    "No se capturó ningún buffer con el PTS solicitado.",
                )
            }
            if (cancellationCheck()) {
                capturedPixels = null
                return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.CANCELLED,
                    "La captura se canceló antes de asignar el bitmap.",
                )
            }
            val bitmapBytes = FrameFreezeBudget.argbArrayBytes(pendingWidth, pendingHeight)
                ?: run {
                    capturedPixels = null
                    return FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                        "Las dimensiones capturadas no son presupuestables.",
                    )
                }
            val bitmapAllocation = guardedAllocation(bitmapBytes) {
                Bitmap.createBitmap(capturedPixels!!, pendingWidth, pendingHeight, Bitmap.Config.ARGB_8888)
            }
            capturedPixels = null
            val bitmap = when (bitmapAllocation) {
                is FrameAllocation.Allocated -> bitmapAllocation.value
                is FrameAllocation.Unavailable -> return FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                    bitmapAllocation.detail,
                )
            }
            return try {
                // Tras la creación del bitmap (paso costoso) y antes de congelar: una cancelación
                // pendiente no debe seguir asignando copias.
                CodecCaptureCancellation.blockedIfCancelled(
                    cancellationCheck,
                    "La captura se canceló antes de congelar la imagen.",
                )?.let { return it }
                val evidence = FrameTemporalEvidence.fromCodecOutputBuffer(
                    generation = generation,
                    sourceIdentity = source.identity,
                    frameIndex = request.frameIndex,
                    assignedPtsUs = request.assignedPtsUs,
                    codecOutputPtsUs = pendingPtsUs,
                )
                val frozen = policy.freeze(bitmap, request, evidence, orientationEvidence)
                // Puerta final: una cancelación tardía libera la imagen congelada y bloquea; nunca se
                // entrega una imagen que ya no se estaba esperando.
                when (val delivery = CodecCaptureCancellation.deliver(
                    value = (frozen as? FrameFreezeResult.Frozen)?.image,
                    cancellationCheck = cancellationCheck,
                    release = { image -> image.close() },
                )) {
                    is CaptureDelivery.Deliver -> frozen
                    is CaptureDelivery.Cancelled -> delivery.blocked
                }
            } finally {
                bitmap.recycle()
            }
        } catch (error: OutOfMemoryError) {
            // Un OOM de las asignaciones propias se rechaza; no se etiqueta una imagen parcial ni se
            // pretende haber limitado la memoria interna opaca del decodificador.
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "No se pudo asignar memoria para la captura (${error.javaClass.simpleName}).",
            )
        } catch (error: Exception) {
            // Fail-closed: un error del códec nunca se convierte en una imagen "aproximada", y una
            // cancelación se reporta como tal en vez de como fallo del decodificador.
            return CodecCaptureCancellation.failureOf(error)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Conveniencia de política: el congelado solo se delega en [FrozenFrameFreezer]. */
    private fun FrameFreezePolicy.freeze(
        bitmap: Bitmap,
        request: FrameFreezeRequest,
        evidence: FrameTemporalEvidence,
        orientationEvidence: FrameOrientationEvidence?,
    ): FrameFreezeResult =
        FrozenFrameFreezer.freeze(bitmap, request, evidence, this, orientationEvidence)

    private fun readArgb(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        inputFormat: MediaFormat,
        allocationGate: (width: Int, height: Int) -> FrameFreezeResult.Blocked?,
        cancellationCheck: () -> Boolean,
    ): CodecPixelCapture {
        val outputFormat = codec.outputFormat
        val colorProfile = YuvColorContract.profileFor(
            colorStandard = resolveColorValue(outputFormat, inputFormat, MediaFormat.KEY_COLOR_STANDARD),
            colorTransfer = resolveColorValue(outputFormat, inputFormat, MediaFormat.KEY_COLOR_TRANSFER),
            colorRange = resolveColorValue(outputFormat, inputFormat, MediaFormat.KEY_COLOR_RANGE),
        ) ?: return CodecPixelCapture.Unsupported(
            "Metadatos de color no soportados (estándar/transferencia/rango): se bloquea en lugar de " +
                "convertir con una matriz supuesta.",
        )

        // Preferimos Image: expone planes con rowStride/pixelStride y cropRect reales.
        val image = try {
            codec.getOutputImage(outputIndex)
        } catch (_: IllegalStateException) {
            null
        }
        if (image != null) {
            try {
                val layout = try {
                    Yuv420OutputLayout.fromImage(image)
                } catch (error: IllegalArgumentException) {
                    return CodecPixelCapture.Unsupported("Salida de códec sin tres planos YUV.")
                }
                val reason = layout.rejectionReason()
                if (reason != null) return CodecPixelCapture.Unsupported(reason)
                val planes = image.planes
                val converted = convertWithBudget(
                    layout = layout,
                    y = planes[0].buffer,
                    u = planes[1].buffer,
                    v = planes[2].buffer,
                    profile = colorProfile,
                    allocationGate = allocationGate,
                )
                return cancelledAfterConversion(converted, cancellationCheck)
            } finally {
                image.close()
            }
        }

        val outputBuffer = codec.getOutputBuffer(outputIndex)
            ?: return CodecPixelCapture.Unsupported("El códec no expuso buffer ni imagen de salida.")
        val colorFormat = outputFormat.optionalInt(MediaFormat.KEY_COLOR_FORMAT)
        if (!Yuv420OutputLayout.isSupportedColorFormat(colorFormat)) {
            return CodecPixelCapture.Unsupported("Color format de salida no soportado (0x${colorFormat.toString(16)}).")
        }
        // Sin Image, el layout solo es demostrable para NV12 semiplanar. En planar/flexible los
        // strides y offsets del croma no están declarados: se bloquea en vez de entregar píxeles
        // aproximados como si fueran el frame verificado.
        Yuv420OutputLayout.rawFallbackRejection(colorFormat)?.let { return CodecPixelCapture.Unsupported(it) }
        val width = outputFormat.optionalInt(MediaFormat.KEY_WIDTH).takeIf { it > 0 }
            ?: inputFormat.optionalInt(MediaFormat.KEY_WIDTH).takeIf { it > 0 }
            ?: return CodecPixelCapture.Unsupported("El formato de salida no declara ancho.")
        val height = outputFormat.optionalInt(MediaFormat.KEY_HEIGHT).takeIf { it > 0 }
            ?: inputFormat.optionalInt(MediaFormat.KEY_HEIGHT).takeIf { it > 0 }
            ?: return CodecPixelCapture.Unsupported("El formato de salida no declara alto.")
        val crop = when (val declaration = decodeCropDeclaration(outputFormat)) {
            CropDeclaration.Absent -> null
            is CropDeclaration.Present -> declaration.window
            // Una declaración de crop inválida no se degrada a "frame completo": el buffer no está
            // mapeado y se bloquea en lugar de recortar con una suposición.
            is CropDeclaration.Invalid -> return CodecPixelCapture.Unsupported(
                "Crop de salida inválido: ${declaration.detail}",
            )
        }
        // Metadatos explícitos y válidos, o se bloquea: inventar stride/sliceHeight con width/height
        // leería el padding de luma como croma y aceptaría píxeles incorrectos con PTS correcto.
        val plan = when (
            val derived = Yuv420OutputLayout.fromDeclaredBuffer(
                width = width,
                height = height,
                colorFormat = colorFormat,
                declaredStride = outputFormat.explicitInt(MediaFormat.KEY_STRIDE),
                declaredSliceHeight = outputFormat.explicitInt(MediaFormat.KEY_SLICE_HEIGHT),
                crop = crop,
            )
        ) {
            is RawNv12OutputPlan.Unsupported -> return CodecPixelCapture.Unsupported(derived.detail)
            is RawNv12OutputPlan.Valid -> derived.layout
        }
        val layout = plan.layout

        // El span se mide sobre la ventana realmente producida (offset/size del BufferInfo), no sobre
        // capacity(): el padding de la capacidad no es contenido del frame.
        Yuv420OutputLayout.producedSpanRejection(
            offset = info.offset,
            size = info.size,
            capacity = outputBuffer.capacity(),
            requiredSpanBytes = plan.requiredSpanBytes,
        )?.let { return CodecPixelCapture.Unsupported(it) }
        val offset = info.offset
        val base = outputBuffer.duplicate().apply {
            position(offset)
            limit(offset + info.size)
        }
        // Cada plano se recorta a su propia región del layout: la luma a `stride*sliceHeight`, el
        // croma intercalado a las filas reales. Así ninguna lectura puede caer en padding.
        val yPlane = base.slice().apply { limit(plan.yBytes.toInt()) }
        val uPlane = base.duplicate()
            .apply { position((offset.toLong() + plan.yBytes).toInt()) }
            .slice()
            .apply { limit(plan.chromaBytes.toInt()) }
        val vPlane = base.duplicate()
            .apply { position((offset.toLong() + plan.yBytes + 1L).toInt()) }
            .slice()
            .apply { limit((plan.chromaBytes - 1L).toInt()) }
        val argb = when (val converted = convertWithBudget(
            layout = layout,
            y = yPlane,
            u = uPlane,
            v = vPlane,
            profile = colorProfile,
            allocationGate = allocationGate,
        )) {
            is CodecPixelCapture.Argb -> converted.pixels
            else -> return converted
        }
        // La conversión es el paso más caro: si la captura se canceló mientras se hacía, no se
        // devuelven los píxeles ni se sigue con el bitmap.
        CodecCaptureCancellation.blockedIfCancelled(
            cancellationCheck,
            "La captura se canceló durante la conversión de píxeles.",
        )?.let { return CodecPixelCapture.Blocked(it) }
        return CodecPixelCapture.Argb(argb, layout.visibleWidth, layout.visibleHeight)
    }

    private fun cancelledAfterConversion(result: CodecPixelCapture, cancellationCheck: () -> Boolean): CodecPixelCapture {
        if (result !is CodecPixelCapture.Argb) return result
        val blocked = CodecCaptureCancellation.blockedIfCancelled(
            cancellationCheck,
            "La captura se canceló durante la conversión de píxeles.",
        ) ?: return result
        return CodecPixelCapture.Blocked(blocked)
    }

    /**
     * Puerta única de asignación ARGB: evalúa cancelación/orientación/presupuesto completo antes de
     * crear la matriz, y convierte un `OutOfMemoryError` real en rechazo tipado. Se ejecuta mientras
     * el buffer o la [Image] del decodificador siguen abiertos.
     */
    private fun convertWithBudget(
        layout: Yuv420OutputLayout,
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        profile: YuvColorProfile,
        allocationGate: (width: Int, height: Int) -> FrameFreezeResult.Blocked?,
    ): CodecPixelCapture {
        allocationGate(layout.visibleWidth, layout.visibleHeight)?.let { return CodecPixelCapture.Blocked(it) }
        val arrayBytes = FrameFreezeBudget.argbArrayBytes(layout.visibleWidth, layout.visibleHeight)
            ?: return CodecPixelCapture.Blocked(
                FrameFreezeResult.Blocked(
                    FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                    "El crop visible no es presupuestable para ARGB.",
                ),
            )
        return when (val allocation = guardedAllocation(arrayBytes) {
            layout.toArgb(y, u, v, profile, maxAllocationBytes = arrayBytes)
        }) {
            is FrameAllocation.Allocated -> CodecPixelCapture.Argb(
                allocation.value,
                layout.visibleWidth,
                layout.visibleHeight,
            )
            is FrameAllocation.Unavailable -> when (allocation.failure) {
                FrameAllocationFailure.OUT_OF_MEMORY,
                FrameAllocationFailure.BUDGET_EXCEEDED,
                -> CodecPixelCapture.Blocked(
                    FrameFreezeResult.Blocked(
                        FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                        allocation.detail,
                    ),
                )
                FrameAllocationFailure.REFUSED -> CodecPixelCapture.Unsupported(allocation.detail)
            }
        }
    }

    /**
     * Metadatos de color: el formato de **salida** del decodificador manda; si no declara la clave se
     * usa el del track de entrada. `UNSPECIFIED` si ninguno la declara y `UNREADABLE` si está presente
     * pero no es un entero: en ese caso [YuvColorContract] bloquea en lugar de adivinar.
     */
    private fun resolveColorValue(outputFormat: MediaFormat, inputFormat: MediaFormat, key: String): Int =
        YuvColorContract.resolveDeclared(
            outputValue = declaredColorValue(outputFormat, key),
            inputValue = declaredColorValue(inputFormat, key),
        )

    private fun declaredColorValue(format: MediaFormat, key: String): Int =
        if (!format.containsKey(key)) {
            YuvColorContract.UNSPECIFIED
        } else {
            runCatching { format.getInteger(key) }.getOrNull() ?: YuvColorContract.UNREADABLE
        }

    /**
     * Claves de crop del decodificador. `MediaFormat.KEY_CROP_RIGHT`/`KEY_CROP_BOTTOM` son
     * **inclusivos** (última columna/fila visibles), a diferencia de `android.graphics.Rect`. La
     * conversión se hace una sola vez, en [CropDeclaration], y ausente/inválido no se confunden:
     * una declaración presente pero ilegible o incoherente bloquea la captura en lugar de recortar
     * a ciegas o de usar el buffer completo como si no hubiera crop.
     */
    private fun decodeCropDeclaration(format: MediaFormat): CropDeclaration {
        val values = arrayOfNulls<Int>(CROP_KEYS.size)
        for ((index, key) in CROP_KEYS.withIndex()) {
            if (!format.containsKey(key)) continue
            values[index] = runCatching { format.getInteger(key) }.getOrNull()
                ?: return CropDeclaration.Invalid("La clave de crop $key está presente pero no es un entero.")
        }
        return CropDeclaration.ofDeclared(values[0], values[1], values[2], values[3])
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return index
        }
        return null
    }

    private fun MediaFormat.optionalInt(key: String, missingValue: Int = -1): Int =
        if (containsKey(key)) {
            runCatching { getInteger(key) }.getOrDefault(missingValue)
        } else {
            missingValue
        }

    /**
     * Metadato entero con estado explícito: ausente, ilegible o declarado. Un valor ilegible no se
     * confunde con "no declarado", pero ambos bloquean el fallback de layout.
     */
    private fun MediaFormat.explicitInt(key: String): ExplicitInt {
        if (!containsKey(key)) return ExplicitInt.Absent
        val value = try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
        return if (value == null) ExplicitInt.Unreadable else ExplicitInt.Declared(value)
    }
}

/** Resultado interno de leer los píxeles de un buffer de salida concreto. */
sealed interface CodecPixelCapture {
    data class Argb(val pixels: IntArray, val width: Int, val height: Int) : CodecPixelCapture
    data class Unsupported(val detail: String) : CodecPixelCapture
    data class Blocked(val blocked: FrameFreezeResult.Blocked) : CodecPixelCapture
}

/** Resultado de la puerta final de entrega: o se entrega, o la cancelación tardía libera el recurso. */
internal sealed interface CaptureDelivery<out T> {
    data class Deliver<T>(val value: T?) : CaptureDelivery<T>
    data class Cancelled(val blocked: FrameFreezeResult.Blocked) : CaptureDelivery<Nothing>
}

/**
 * Puertas de cancelación del ciclo de captura. Se comprueban en pasos costosos (antes de asignar la
 * matriz ARGB, durante la conversión, antes de congelar) y **antes de entregar** el resultado: una
 * cancelación nunca se convierte en una imagen entregada ni en un éxito.
 */
internal object CodecCaptureCancellation {

    /** Bloqueo de cancelación, o `null` si la captura sigue viva. */
    fun blockedIfCancelled(cancellationCheck: () -> Boolean, detail: String): FrameFreezeResult.Blocked? =
        if (cancellationCheck()) {
            FrameFreezeResult.Blocked(FrameFreezeRejectionReason.CANCELLED, detail)
        } else {
            null
        }

    /**
     * Puerta final: si hay cancelación pendiente, libera [value] con [release] y devuelve bloqueo. Un
     * resultado que no es una imagen (p. ej. ya bloqueado) se devuelve intacto.
     */
    fun <T : Any> deliver(
        value: T?,
        cancellationCheck: () -> Boolean,
        release: (T) -> Unit,
    ): CaptureDelivery<T> {
        if (value == null) return CaptureDelivery.Deliver(null)
        if (!cancellationCheck()) return CaptureDelivery.Deliver(value)
        runCatching { release(value) }
        return CaptureDelivery.Cancelled(
            FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.CANCELLED,
                "La captura se canceló después de congelar la imagen: no se entrega.",
            ),
        )
    }

    /**
     * Un [CancellationException] conserva su motivo: no se reporta como fallo del decodificador ni
     * como éxito. Cualquier otro error sigue siendo fail-closed.
     */
    fun failureOf(error: Throwable): FrameFreezeResult.Blocked =
        if (error is CancellationException) {
            FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.CANCELLED,
                "La captura se canceló durante la decodificación.",
            )
        } else {
            FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.UNSUPPORTED_CODEC_OUTPUT,
                "El decodificador falló: ${error.javaClass.simpleName}.",
            )
        }
}

/** Claves de crop leídas del formato de salida, en el orden de [CropDeclaration.ofDeclared]. */
private val CROP_KEYS = listOf("crop-left", "crop-top", MediaFormat.KEY_CROP_RIGHT, MediaFormat.KEY_CROP_BOTTOM)
