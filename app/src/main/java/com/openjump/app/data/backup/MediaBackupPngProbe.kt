package com.openjump.app.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openjump.app.video.VideoFrameSafety
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probador de PNG del backup con imagenes (slice G3c): valida el contenido **antes** de que una
 * restauracion futura lo publique en Room.
 *
 * Por que existe: [MediaBackupArchiveReader] sella bytes **opacos** (tamano, CRC-32 y SHA-256) y
 * declara dimensiones en el manifiesto, pero no demuestra que la entrada sea un PNG ni que sus
 * dimensiones reales sean las declaradas. Un contenedor hostil puede sellar bytes con una cabecera
 * IHDR que declare 60 000 x 60 000: el SHA y el tamano cuadrarian y un decodificador perderia la
 * memoria del proceso. Este componente cierra ese hueco sin tocar Room, sin UI y sin SAF.
 *
 * Contrato que este fichero hace estructural:
 *  1. **Sin rutas ni URIs**: la unica entrada es un `openStream: () -> InputStream` que el
 *     coordinador ata al fichero **privado de staging** que el lector ya verifico. No hay API de
 *     `File`, `Uri`, `file://` ni nombre libre, y el probador nunca escribe, transforma ni publica
 *     bytes: solo lee y cierra los flujos que abre.
 *  2. **Nada del llamante se cree**: ni el MIME, ni las dimensiones, ni el tamano, ni el SHA. El
 *     MIME declarado tiene que ser `image/png` (rechazo barato) y ademas los bytes tienen que ser
 *     un PNG real: firma de 8 bytes, primer chunk `IHDR` de longitud 13, CRC-32 del `IHDR`
 *     correcto y combinacion de profundidad/tipo de color legal.
 *  3. **Presupuesto antes del full decode**: las dimensiones del `IHDR` (leidas de los bytes, no
 *     del manifiesto) se validan contra [MediaBackupPngBudget], que nunca puede ser mas permisivo
 *     que [VideoFrameSafety]; cada dimension, el area y el coste ARGB_8888 se calculan sin
 *     multiplicar valores sin acotar. Solo despues se pide `inJustDecodeBounds`, se contrasta con
 *     las esperadas y, por ultimo, se hace el full decode.
 *  4. **PNG realmente decodificado y dimension real confirmada**: el bitmap tiene que existir y
 *     medir exactamente lo esperado (cabecera, bounds y pixeles). Se libera siempre en `finally`,
 *     tanto si el probe termina bien como si falla despues de decodificar.
 *  5. **Integridad re-verificada**: antes de decodificar se lee el fichero entero y se comprueban
 *     tamano y SHA-256 contra lo que el lector sello; la lectura del full decode se acota al tamano
 *     sellado y se vuelve a digerir el fichero completo. Un fichero sustituido o recortado entre
 *     pasadas no se puede decodificar como si fuera el verificado.
 *  6. **`OutOfMemoryError` tipado**: un agotamiento de memoria de la plataforma durante bounds o
 *     full decode se mapea a [MediaBackupPngProbeException.MemoryExhausted]; nunca se propaga un
 *     `OutOfMemoryError` crudo ni se informa exito.
 *  7. **Cancelacion diferenciada**: si el llamante pasa [MediaBackupArchiveCancellation] (o
 *     interrumpe el hilo), el fallo es [MediaBackupPngProbeException.Canceled] y se comprueba antes
 *     de abrir cada flujo, entre bloques leidos y durante el drenaje del decode.
 *  8. **Nunca en el hilo principal**: [probe] es bloqueante y lee el fichero hasta tres veces (una
 *     para integridad/cabecera, una para bounds y una para pixeles). El KDoc lo exige y
 *     [probeAsync] ofrece el adaptador con `Dispatchers.IO`; el coordinador es quien decide el
 *     dispatcher. La cancelacion de la corrutina **no** interrumpe el cuerpo bloqueante: para eso
 *     esta la senal cooperativa.
 *
 * Riesgo TOCTOU que este componente **no** puede eliminar y que hay que asumir explicitamente:
 *  - El probe no bloquea el fichero ni el directorio. Re-verifica tamano y SHA-256 en su primera
 *    pasada y vuelve a digerir el fichero en la pasada de pixeles, asi que detecta cualquier
 *    sustitucion anterior o simultanea a esas lecturas; una escritura posterior a la validacion y
 *    anterior a la publicacion queda fuera de su alcance.
 *  - El probe tampoco puede impedir que otro proceso con acceso de escritura al staging cambie el
 *    fichero entre pasadas, bloquee una lectura o la haga lenta: la E/S puede bloquear y este
 *    componente no lo evita. Lo unico que garantiza es que, si los bytes leidos no son los
 *    sellados, la operacion falla de forma tipada en vez de decodificar contenido no verificado.
 *    Que el staging sea privado y no compartido lo garantiza el coordinador.
 *  - Cuando el coordinador publique en Room debera **volver a comprobar** tamano y SHA-256 del
 *    fichero que realmente entregue al almacen privado (o reutilizar
 *    [MediaBackupPreparedImport.copyImageTo], que ya lo hace durante la transferencia). Este probe
 *    no sustituye esa validacion final.
 *  - El probe no demuestra por si mismo que el staging sea privado: esa garantia la aporta el
 *    coordinador al construir el `openStream` sobre el fichero que devolvio el lector. Por eso no
 *    existe aqui ninguna API que acepte una ruta, una URI o un nombre de fichero.
 *
 * Fuera de alcance: Room, almacen privado, SAF, UI, restauracion, escritura de bytes y cualquier
 * log con rutas, claves o contenido.
 */
class MediaBackupPngProbe internal constructor(
    private val budget: MediaBackupPngBudget,
    private val decoder: MediaBackupPngDecoder,
) {

    /** Probador de produccion: presupuesto por defecto y decodificador real de la plataforma. */
    constructor(budget: MediaBackupPngBudget = MediaBackupPngBudget()) : this(budget, AndroidMediaBackupPngDecoder)

    /**
     * Valida el PNG staged que [openStream] reabre y devuelve su identidad **confirmada**.
     *
     * [openStream] se invoca hasta tres veces y debe abrir el **mismo** fichero privado de staging
     * del lector; cada flujo que devuelve lo cierra este metodo (el llamante no debe reutilizarlos).
     * [expectation] son las dimensiones y el sello que declara el manifiesto y que el lector ya
     * verifico: aqui se vuelven a contrastar contra los bytes reales, no se asumen.
     *
     * Lanza [MediaBackupPngProbeException] si algo no cuadra. Es bloqueante: no llamarlo desde el
     * hilo principal (ver [probeAsync]).
     */
    fun probe(
        expectation: MediaBackupPngExpectation,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
    ): MediaBackupPngVerified {
        if (expectation.mimeType != MEDIA_BACKUP_IMAGE_MIME_TYPE) {
            throw MediaBackupPngProbeException.MimeRejected(
                "media backup image ${expectation.imageKey} declares mimeType '${expectation.mimeType}', " +
                    "expected '$MEDIA_BACKUP_IMAGE_MIME_TYPE'",
            )
        }
        if (expectation.byteCount > budget.maxEncodedBytes) {
            throw MediaBackupPngProbeException.BudgetExceeded(
                "media backup image ${expectation.imageKey} declares ${expectation.byteCount} bytes, " +
                    "above the ${budget.maxEncodedBytes}-byte encoded budget",
            )
        }
        checkCanceled(cancellation)
        val expectedDimensions = acceptedDimensions(
            MediaBackupPngSafety.checkDimensions(expectation.width.toLong(), expectation.height.toLong(), budget),
            expectation.imageKey,
        )
        val header = readSealedHeader(expectation, openStream, cancellation)
        val headerDimensions = acceptedDimensions(
            MediaBackupPngSafety.checkDimensions(header.width, header.height, budget),
            expectation.imageKey,
        )
        requireMatchingDimensions(MediaBackupPngStage.HEADER, expectedDimensions, headerDimensions.width, headerDimensions.height)
        checkCanceled(cancellation)
        val bounds = decodeBounds(openStream, cancellation)
        requireMatchingDimensions(MediaBackupPngStage.BOUNDS, expectedDimensions, bounds.width, bounds.height)
        checkCanceled(cancellation)
        val verified = decodePixels(expectation, expectedDimensions, openStream, cancellation)
        checkCanceled(cancellation)
        return verified
    }

    /**
     * Adaptador suspendido del probe: ejecuta [probe] en [dispatcher] para que el coordinador no
     * haga E/S ni decodificacion en el hilo principal.
     *
     * La cancelacion de la corrutina no interrumpe el cuerpo bloqueante; propaga [cancellation] (o
     * interrumpe el hilo) para abortar de forma cooperativa.
     */
    suspend fun probeAsync(
        expectation: MediaBackupPngExpectation,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation = MediaBackupArchiveCancellation.NEVER,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): MediaBackupPngVerified = withContext(dispatcher) {
        probe(expectation, openStream, cancellation)
    }

    // --- Pasada 1: integridad sellada + cabecera PNG -------------------------------------------

    private fun readSealedHeader(
        expectation: MediaBackupPngExpectation,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupPngHeader = withSourceStream(openStream) { stream ->
        val digest = MessageDigest.getInstance(SHA256)
        val prefix = ByteArray(PNG_HEADER_BYTES)
        var prefixLength = 0
        var observed = 0L
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            checkCanceled(cancellation)
            val count = readBlock(stream, buffer, cancellation)
            if (count < 0) break
            observed = addObserved(observed, count, expectation.imageKey)
            if (observed > expectation.byteCount) {
                throw MediaBackupPngProbeException.IntegrityFailed(
                    "media backup image ${expectation.imageKey} produced more than the sealed " +
                        "${expectation.byteCount} bytes",
                )
            }
            if (observed > budget.maxEncodedBytes) {
                throw MediaBackupPngProbeException.BudgetExceeded(
                    "media backup image ${expectation.imageKey} exceeds the ${budget.maxEncodedBytes}-byte encoded budget",
                )
            }
            digest.update(buffer, 0, count)
            if (prefixLength < prefix.size) {
                val copied = minOf(count, prefix.size - prefixLength)
                System.arraycopy(buffer, 0, prefix, prefixLength, copied)
                prefixLength += copied
            }
        }
        if (observed != expectation.byteCount) {
            throw MediaBackupPngProbeException.IntegrityFailed(
                "media backup image ${expectation.imageKey} produced $observed bytes, " +
                    "expected the sealed ${expectation.byteCount}",
            )
        }
        val observedSha256 = toLowerHex(digest.digest())
        if (!observedSha256.equals(expectation.sha256, ignoreCase = true)) {
            throw MediaBackupPngProbeException.IntegrityFailed(
                "media backup image ${expectation.imageKey} does not match the sealed SHA-256",
            )
        }
        if (prefixLength < PNG_HEADER_BYTES) {
            throw MediaBackupPngProbeException.Truncated(
                "media backup image ${expectation.imageKey} ends before the complete PNG header",
            )
        }
        parseHeader(prefix, expectation.imageKey)
    }

    // --- Pasada 2: bounds sin asignar pixeles ---------------------------------------------------

    private fun decodeBounds(
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupPngBounds = withSourceStream(openStream) { stream ->
        checkCanceled(cancellation)
        val bounds = try {
            decoder.decodeBounds(stream)
        } catch (failure: MediaBackupPngProbeException) {
            throw failure
        } catch (failure: OutOfMemoryError) {
            throw MediaBackupPngProbeException.MemoryExhausted(MediaBackupPngStage.BOUNDS, failure)
        } catch (failure: IOException) {
            throw MediaBackupPngProbeException.SourceFailed(failure)
        } catch (failure: RuntimeException) {
            throw MediaBackupPngProbeException.DecodeFailed(
                "platform could not read the staged PNG bounds: ${failure.message}",
                failure,
            )
        }
        bounds ?: throw MediaBackupPngProbeException.DecodeFailed(
            "platform returned no bounds for the staged PNG",
        )
        bounds
    }

    // --- Pasada 3: full decode acotado, dimension real y digest ---------------------------------

    private fun decodePixels(
        expectation: MediaBackupPngExpectation,
        expectedDimensions: MediaBackupPngDimensions,
        openStream: () -> InputStream,
        cancellation: MediaBackupArchiveCancellation,
    ): MediaBackupPngVerified = withSourceStream(openStream) { stream ->
        val sealed = SealedPngStream(stream, expectation.byteCount, cancellation)
        var bitmap: Bitmap? = null
        try {
            bitmap = try {
                decoder.decodePixels(sealed)
            } catch (failure: MediaBackupPngProbeException) {
                throw failure
            } catch (failure: OutOfMemoryError) {
                throw MediaBackupPngProbeException.MemoryExhausted(MediaBackupPngStage.DECODED, failure)
            } catch (failure: IOException) {
                throw MediaBackupPngProbeException.SourceFailed(failure)
            } catch (failure: RuntimeException) {
                throw MediaBackupPngProbeException.DecodeFailed(
                    "platform could not decode the staged PNG: ${failure.message}",
                    failure,
                )
            }
            sealed.failure?.let { throw it }
            val decoded = bitmap ?: throw MediaBackupPngProbeException.DecodeFailed(
                "platform returned no bitmap for the staged PNG",
            )
            requireMatchingDimensions(MediaBackupPngStage.DECODED, expectedDimensions, decoded.width, decoded.height)
            sealed.drain()
            sealed.failure?.let { throw it }
            val observedSha256 = sealed.settle(expectation)
            MediaBackupPngVerified(
                imageKey = expectation.imageKey,
                width = decoded.width,
                height = decoded.height,
                area = expectedDimensions.area,
                decodedBytes = expectedDimensions.decodedBytes,
                byteCount = sealed.observedByteCount,
                sha256 = observedSha256,
            )
        } finally {
            // El bitmap se libera siempre: exito, dimensiones falsas, cancelacion o E/S.
            try {
                bitmap?.recycle()
            } catch (failure: RuntimeException) {
                // Un fallo de reciclado no puede enmascarar el resultado tipado del probe.
            }
        }
    }

    private fun acceptedDimensions(
        check: MediaBackupPngDimensionCheck,
        imageKey: String,
    ): MediaBackupPngDimensions = when (check) {
        is MediaBackupPngDimensionCheck.Accepted -> check.dimensions
        is MediaBackupPngDimensionCheck.Rejected -> throw MediaBackupPngProbeException.DimensionsRejected(
            check.reason,
            "media backup image $imageKey declares ${check.width}x${check.height}, rejected by " +
                "the ${check.reason} rule",
        )
    }

    private fun requireMatchingDimensions(
        stage: MediaBackupPngStage,
        expected: MediaBackupPngDimensions,
        observedWidth: Int,
        observedHeight: Int,
    ) {
        if (observedWidth != expected.width || observedHeight != expected.height) {
            throw MediaBackupPngProbeException.DimensionMismatch(
                stage = stage,
                expectedWidth = expected.width,
                expectedHeight = expected.height,
                observedWidth = observedWidth.toLong(),
                observedHeight = observedHeight.toLong(),
            )
        }
    }
}

/**
 * Dimensiones y sello que el manifiesto declara para una imagen y que el lector ya verifico contra
 * los bytes del contenedor. El probe **no** las cree: las contrasta con el PNG real.
 *
 * [width] y [height] no se exigen positivas aqui a proposito: un manifiesto hostil debe poder
 * expresarse para que el probe lo rechace con [MediaBackupPngProbeException.DimensionsRejected] en
 * vez de con un `IllegalArgumentException` del constructor.
 */
data class MediaBackupPngExpectation(
    val imageKey: String,
    val width: Int,
    val height: Int,
    val byteCount: Long,
    val sha256: String,
    val mimeType: String = MEDIA_BACKUP_IMAGE_MIME_TYPE,
) {
    init {
        require(imageKey.isNotEmpty()) { "imageKey no puede estar vacia." }
        require(byteCount >= 1L) { "byteCount debe ser positivo." }
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { it.isHexDigit() }) {
            "sha256 debe ser hexadecimal de $SHA256_HEX_LENGTH caracteres."
        }
    }
}

/**
 * Expectativa de una imagen a partir de su fotograma del manifiesto: las dimensiones declaradas son
 * `imageWidth`/`imageHeight` y el sello `imageByteCount`/`imageSha256`. Funcion pura, sin IO.
 */
fun MediaBackupFrame.pngExpectation(): MediaBackupPngExpectation = MediaBackupPngExpectation(
    imageKey = imageKey,
    width = imageWidth,
    height = imageHeight,
    byteCount = imageByteCount,
    sha256 = imageSha256,
    mimeType = imageMimeType,
)

/**
 * Presupuesto **explicito** del probe. Los valores por defecto son los de [VideoFrameSafety] y
 * [MEDIA_BACKUP_MAX_IMAGE_BYTES]; el llamante solo puede acotar mas, nunca ampliar el sobre de
 * procesamiento ya fijado por la app.
 *
 * [maxEncodedBytes] acota el fichero comprimido antes de decodificar; [maxDimension],
 * [maxPixelArea] y [maxDecodedBytes] acotan lo que el full decode podria llegar a asignar.
 */
class MediaBackupPngBudget(
    val maxDimension: Int = VideoFrameSafety.MAX_DIMENSION,
    val maxPixelArea: Long = VideoFrameSafety.MAX_FRAME_AREA,
    val maxDecodedBytes: Long = VideoFrameSafety.MAX_WORKING_BYTES,
    val maxEncodedBytes: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES,
) {
    init {
        require(maxDimension in 1..VideoFrameSafety.MAX_DIMENSION) {
            "maxDimension debe estar en 1..${VideoFrameSafety.MAX_DIMENSION}."
        }
        require(maxPixelArea in 1..VideoFrameSafety.MAX_FRAME_AREA) {
            "maxPixelArea debe estar en 1..${VideoFrameSafety.MAX_FRAME_AREA}."
        }
        require(maxDecodedBytes in 1..VideoFrameSafety.MAX_WORKING_BYTES) {
            "maxDecodedBytes debe estar en 1..${VideoFrameSafety.MAX_WORKING_BYTES}."
        }
        require(maxEncodedBytes in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "maxEncodedBytes debe estar en 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES."
        }
    }
}

/** Dimensiones aceptadas por el presupuesto, con area y coste ARGB_8888 ya calculados. */
data class MediaBackupPngDimensions(
    val width: Int,
    val height: Int,
    val area: Long,
    val decodedBytes: Long,
)

/** Motivo tipado por el que el presupuesto rechaza unas dimensiones declaradas. */
enum class MediaBackupPngDimensionRejection {
    /** Ancho o alto menor o igual que cero (el `IHDR` es unsigned, asi que solo el ancho 0 cae aqui). */
    NON_POSITIVE,

    /** Una dimension supera `maxDimension`. Se comprueba antes de multiplicar: sin overflow. */
    DIMENSION_TOO_LARGE,

    /** El area supera `maxPixelArea`. */
    AREA_TOO_LARGE,

    /** El coste en bytes de pixeles supera `maxDecodedBytes`. */
    DECODED_BYTES_TOO_LARGE,
}

/** Resultado puro de [MediaBackupPngSafety.checkDimensions]. */
sealed class MediaBackupPngDimensionCheck {
    data class Accepted(val dimensions: MediaBackupPngDimensions) : MediaBackupPngDimensionCheck()

    data class Rejected(
        val reason: MediaBackupPngDimensionRejection,
        val width: Long,
        val height: Long,
    ) : MediaBackupPngDimensionCheck()
}

/**
 * Reglas puras de dimensiones del probe, sin IO y sin `android.graphics`: se pueden probar en JVM.
 *
 * El orden de las comprobaciones es parte del contrato anti-overflow: primero se acota cada
 * dimension, despues se multiplica (`16384 * 16384` cabe de sobra en `Long`) y solo entonces se
 * calcula el coste. Un valor de 32 bits sin signo como `0xFFFF_FFFF` se rechaza por dimension, no
 * se multiplica jamas. Las dimensiones de un `IHDR` de 32 bits sin signo pueden superar `Int.MAX`
 * y no deben truncarse a `Int` antes de validarse.
 */
object MediaBackupPngSafety {

    /** Bytes por pixel del full decode: ARGB_8888, el formato que pide el decodificador. */
    const val BYTES_PER_PIXEL: Long = 4L

    fun checkDimensions(
        width: Long,
        height: Long,
        budget: MediaBackupPngBudget,
    ): MediaBackupPngDimensionCheck {
        if (width <= 0L || height <= 0L) {
            return MediaBackupPngDimensionCheck.Rejected(MediaBackupPngDimensionRejection.NON_POSITIVE, width, height)
        }
        if (width > budget.maxDimension.toLong() || height > budget.maxDimension.toLong()) {
            return MediaBackupPngDimensionCheck.Rejected(MediaBackupPngDimensionRejection.DIMENSION_TOO_LARGE, width, height)
        }
        val area = width * height
        if (area > budget.maxPixelArea) {
            return MediaBackupPngDimensionCheck.Rejected(MediaBackupPngDimensionRejection.AREA_TOO_LARGE, width, height)
        }
        val decodedBytes = area * BYTES_PER_PIXEL
        if (decodedBytes > budget.maxDecodedBytes) {
            return MediaBackupPngDimensionCheck.Rejected(
                MediaBackupPngDimensionRejection.DECODED_BYTES_TOO_LARGE,
                width,
                height,
            )
        }
        return MediaBackupPngDimensionCheck.Accepted(
            MediaBackupPngDimensions(width.toInt(), height.toInt(), area, decodedBytes),
        )
    }
}

/** Etapa del probe en la que se observo una dimension distinta de la esperada. */
enum class MediaBackupPngStage { HEADER, BOUNDS, DECODED }

/** Identidad **confirmada** de un PNG staged: cabecera, bounds y pixeles coinciden con lo esperado. */
data class MediaBackupPngVerified(
    val imageKey: String,
    val width: Int,
    val height: Int,
    val area: Long,
    val decodedBytes: Long,
    val byteCount: Long,
    val sha256: String,
)

/**
 * Fallos del probe. Todos implican que el PNG **no** se puede publicar; [Canceled] distingue la
 * cancelacion pedida por el llamante de un contenido invalido.
 */
sealed class MediaBackupPngProbeException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** El manifiesto declara un MIME distinto de `image/png`; no se abre ningun flujo. */
    class MimeRejected(detail: String) : MediaBackupPngProbeException(detail)

    /** Los bytes no son un PNG: firma, primer chunk, CRC del `IHDR` o combinacion de color ilegal. */
    class HeaderRejected(detail: String) : MediaBackupPngProbeException(detail)

    /** El flujo termina antes de la cabecera PNG completa. */
    class Truncated(detail: String) : MediaBackupPngProbeException(detail)

    /** Dimensiones fuera del presupuesto (declaradas por el manifiesto o leidas del `IHDR`). */
    class DimensionsRejected(
        val reason: MediaBackupPngDimensionRejection,
        detail: String,
    ) : MediaBackupPngProbeException(detail)

    /** Una etapa observo dimensiones distintas de las esperadas (cabecera, bounds o pixeles). */
    class DimensionMismatch(
        val stage: MediaBackupPngStage,
        val expectedWidth: Int,
        val expectedHeight: Int,
        val observedWidth: Long,
        val observedHeight: Long,
    ) : MediaBackupPngProbeException(
        "staged PNG ${stage.name.lowercase(Locale.ROOT)} dimensions ${observedWidth}x$observedHeight " +
            "do not match the expected ${expectedWidth}x$expectedHeight",
    )

    /** El fichero o su coste declarado superan el presupuesto del probe antes de decodificar. */
    class BudgetExceeded(detail: String) : MediaBackupPngProbeException(detail)

    /** Tamano o SHA-256 distintos del sello del lector, o el fichero crecio durante la lectura. */
    class IntegrityFailed(detail: String) : MediaBackupPngProbeException(detail)

    /** No se pudo abrir o leer el staging (el flujo es del llamante y puede fallar). */
    class SourceFailed(cause: Throwable?) :
        MediaBackupPngProbeException("staged PNG could not be read: ${cause?.message}", cause)

    /** La plataforma no pudo decodificar los bytes como imagen (PNG corrupto o truncado). */
    class DecodeFailed(detail: String, cause: Throwable? = null) : MediaBackupPngProbeException(detail, cause)

    /** `OutOfMemoryError` de la plataforma durante bounds o full decode, mapeado de forma tipada. */
    class MemoryExhausted(val stage: MediaBackupPngStage, cause: OutOfMemoryError?) : MediaBackupPngProbeException(
        "platform ran out of memory while probing the staged PNG during ${stage.name.lowercase(Locale.ROOT)}",
        cause,
    )

    /** Cancelacion pedida por el llamante o por interrupcion del hilo. */
    data object Canceled : MediaBackupPngProbeException("media backup PNG probe canceled")
}

// --- Decodificador de plataforma (inyectable solo desde pruebas del modulo) --------------------

/** Bounds que la plataforma reporta para un PNG, sin asignar pixeles. */
internal data class MediaBackupPngBounds(val width: Int, val height: Int)

/**
 * Puerto del decodificador real. Existe para que las pruebas instrumentadas cuenten llamadas,
 * observen el bitmap devuelto y comprueben su reciclado sin cambiar el camino de produccion.
 */
internal interface MediaBackupPngDecoder {
    /** `inJustDecodeBounds`: devuelve `null` si la plataforma no reconoce los bytes. */
    fun decodeBounds(stream: InputStream): MediaBackupPngBounds?

    /** Full decode ARGB_8888: devuelve `null` si la plataforma no puede decodificar. */
    fun decodePixels(stream: InputStream): Bitmap?
}

/** Decodificador real: [BitmapFactory] con bounds primero y full decode ARGB_8888 despues. */
internal object AndroidMediaBackupPngDecoder : MediaBackupPngDecoder {

    override fun decodeBounds(stream: InputStream): MediaBackupPngBounds? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeStream(stream, null, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        return MediaBackupPngBounds(width, height)
    }

    override fun decodePixels(stream: InputStream): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            inMutable = false
        }
        return BitmapFactory.decodeStream(stream, null, options)
    }
}

// --- Flujo acotado con digest -----------------------------------------------------------------

/**
 * Envuelve el flujo del full decode: cuenta y digiere **todos** los bytes, corta en el tamano
 * sellado y consulta la cancelacion. Se acota al tamano que el lector verifico, de modo que una
 * sustitucion por un fichero mayor no puede alimentar al decodificador sin limite; `skip` se
 * implementa leyendo para que el digest no pierda bytes.
 */
private class SealedPngStream(
    input: InputStream,
    private val sealedByteCount: Long,
    private val cancellation: MediaBackupArchiveCancellation,
) : FilterInputStream(input) {

    private val digest = MessageDigest.getInstance(SHA256)
    private var skipBuffer: ByteArray? = null

    var observedByteCount: Long = 0L
        private set

    var failure: MediaBackupPngProbeException? = null
        private set

    override fun read(): Int {
        checkCanceled()
        val value = super.read()
        if (value >= 0) {
            account(1)
            digest.update(value.toByte())
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCanceled()
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            account(count)
            digest.update(buffer, offset, count)
        }
        return count
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        val buffer = skipBuffer ?: ByteArray(SKIP_BUFFER_BYTES).also { skipBuffer = it }
        var skipped = 0L
        while (skipped < byteCount) {
            val request = minOf(buffer.size.toLong(), byteCount - skipped).toInt()
            val count = read(buffer, 0, request)
            if (count < 0) break
            skipped += count
        }
        return skipped
    }

    /** Lee hasta el final para que el digest cubra el fichero entero, no solo lo que consumio el decodificador. */
    fun drain() {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) return
            if (count == 0 && read() < 0) return
        }
    }

    /** Comprueba tamano y SHA-256 observados contra la expectativa y devuelve el SHA observado. */
    fun settle(expectation: MediaBackupPngExpectation): String {
        if (observedByteCount != sealedByteCount) {
            throw MediaBackupPngProbeException.IntegrityFailed(
                "media backup image ${expectation.imageKey} produced $observedByteCount bytes during decode, " +
                    "expected the sealed $sealedByteCount",
            )
        }
        val observedSha256 = toLowerHex(digest.digest())
        if (!observedSha256.equals(expectation.sha256, ignoreCase = true)) {
            throw MediaBackupPngProbeException.IntegrityFailed(
                "media backup image ${expectation.imageKey} changed after the reader sealed it (SHA-256 mismatch)",
            )
        }
        return observedSha256
    }

    private fun account(count: Int) {
        observedByteCount = try {
            Math.addExact(observedByteCount, count.toLong())
        } catch (overflow: ArithmeticException) {
            fail(
                MediaBackupPngProbeException.IntegrityFailed(
                    "staged PNG produced an impossible byte count during decode",
                ),
            )
        }
        if (observedByteCount > sealedByteCount) {
            fail(
                MediaBackupPngProbeException.IntegrityFailed(
                    "staged PNG produced more than the sealed $sealedByteCount bytes during decode",
                ),
            )
        }
    }

    private fun checkCanceled() {
        if (Thread.currentThread().isInterrupted || cancellation.isCanceled()) {
            fail(MediaBackupPngProbeException.Canceled)
        }
    }

    private fun fail(failure: MediaBackupPngProbeException): Nothing {
        this.failure = failure
        throw failure
    }
}

// --- Cabecera PNG -----------------------------------------------------------------------------

private data class MediaBackupPngHeader(val width: Long, val height: Long)

/**
 * Parsea la firma y el primer chunk `IHDR` de los bytes **reales**. No se acepta un `IHDR` con
 * longitud distinta de 13, CRC incorrecto, tipo de color/profundidad ilegal, ni compresion, filtro
 * o entrelazado desconocidos: cualquier aspecto que no se pueda probar se rechaza.
 */
private fun parseHeader(prefix: ByteArray, imageKey: String): MediaBackupPngHeader {
    if (!PNG_SIGNATURE.indices.all { prefix[it] == PNG_SIGNATURE[it] }) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey does not start with the PNG signature",
        )
    }
    if (readUInt32(prefix, 8) != PNG_IHDR_LENGTH.toLong()) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey declares an unexpected first chunk length",
        )
    }
    if (prefix[12] != 'I'.code.toByte() || prefix[13] != 'H'.code.toByte() ||
        prefix[14] != 'D'.code.toByte() || prefix[15] != 'R'.code.toByte()
    ) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey does not start with an IHDR chunk",
        )
    }
    val crc = CRC32()
    crc.update(prefix, 12, 4 + PNG_IHDR_LENGTH)
    if (crc.value != readUInt32(prefix, 29)) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey has a corrupt IHDR CRC-32",
        )
    }
    val bitDepth = prefix[24].toInt() and 0xFF
    val colorType = prefix[25].toInt() and 0xFF
    val compression = prefix[26].toInt() and 0xFF
    val filter = prefix[27].toInt() and 0xFF
    val interlace = prefix[28].toInt() and 0xFF
    val legalDepths = when (colorType) {
        0 -> intArrayOf(1, 2, 4, 8, 16)
        2 -> intArrayOf(8, 16)
        3 -> intArrayOf(1, 2, 4, 8)
        4 -> intArrayOf(8, 16)
        6 -> intArrayOf(8, 16)
        else -> intArrayOf()
    }
    if (bitDepth !in legalDepths) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey has an illegal PNG bit depth/color type combination",
        )
    }
    if (compression != 0 || filter != 0 || interlace !in 0..1) {
        throw MediaBackupPngProbeException.HeaderRejected(
            "media backup image $imageKey declares unsupported PNG compression, filter or interlace",
        )
    }
    return MediaBackupPngHeader(readUInt32(prefix, 16), readUInt32(prefix, 20))
}

// --- Utilidades -------------------------------------------------------------------------------

/**
 * Abre un flujo del staging, ejecuta [block] y lo cierra **siempre**. Si el cierre falla y no habia
 * otro fallo en curso se informa como [MediaBackupPngProbeException.SourceFailed]; si ya se estaba
 * propagando un fallo tipado (cancelacion incluida) ese fallo manda y el de cierre se descarta.
 */
private fun <T> withSourceStream(openStream: () -> InputStream, block: (InputStream) -> T): T {
    val stream = openSource(openStream)
    var primary: Throwable? = null
    try {
        return block(stream)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            stream.close()
        } catch (failure: IOException) {
            if (primary == null) throw MediaBackupPngProbeException.SourceFailed(failure)
        }
    }
}

private fun openSource(openStream: () -> InputStream): InputStream = try {
    openStream()
} catch (failure: MediaBackupPngProbeException) {
    throw failure
} catch (failure: IOException) {
    throw MediaBackupPngProbeException.SourceFailed(failure)
}

private fun readBlock(
    stream: InputStream,
    buffer: ByteArray,
    cancellation: MediaBackupArchiveCancellation,
): Int {
    while (true) {
        checkCanceled(cancellation)
        val count = try {
            stream.read(buffer)
        } catch (failure: IOException) {
            throw MediaBackupPngProbeException.SourceFailed(failure)
        }
        if (count > 0) return count
        if (count < 0) return -1
        // Flujo que devuelve 0: se fuerza un byte para no girar en vacio.
        val single = try {
            stream.read()
        } catch (failure: IOException) {
            throw MediaBackupPngProbeException.SourceFailed(failure)
        }
        if (single < 0) return -1
        buffer[0] = single.toByte()
        return 1
    }
}

private fun addObserved(observed: Long, count: Int, imageKey: String): Long = try {
    Math.addExact(observed, count.toLong())
} catch (overflow: ArithmeticException) {
    throw MediaBackupPngProbeException.IntegrityFailed(
        "media backup image $imageKey produced an impossible byte count",
    )
}

private fun checkCanceled(cancellation: MediaBackupArchiveCancellation) {
    if (Thread.currentThread().isInterrupted || cancellation.isCanceled()) {
        throw MediaBackupPngProbeException.Canceled
    }
}

private fun readUInt32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

private fun toLowerHex(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val SHA256 = "SHA-256"
private const val SHA256_HEX_LENGTH = 64
private const val PNG_IHDR_LENGTH = 13
private const val PNG_HEADER_BYTES = 8 + 8 + PNG_IHDR_LENGTH + 4
private const val STREAM_BUFFER_BYTES = 64 * 1024
private const val SKIP_BUFFER_BYTES = 4 * 1024
private const val HEX_DIGITS = "0123456789abcdef"
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
