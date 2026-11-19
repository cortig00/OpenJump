package com.openjump.app.data.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

/**
 * Codec estricto del manifiesto con imagenes.
 *
 * Es un contrato **nuevo y separado** del [ManualBackupCodec] v1: aqui `ignoreUnknownKeys = false`,
 * sin comentarios ni comas finales, sin valores especiales de coma flotante y con el mismo orden
 * canonico en cada escritura (el orden de colecciones se fija por id/ordinal/clave, no por el orden
 * de llegada). El documento legacy anidado conserva su propio codec y su propio limite de 8 MiB: la
 * serializacion del manifiesto no amplia el tamano del JSON legacy, solo lo envuelve.
 *
 * Presupuestos (todos finitos y comprobados **durante** la operacion, nunca despues de reservar la
 * memoria):
 *  - emision: [MEDIA_BACKUP_MAX_MANIFEST_BYTES] se aplica en el flujo de salida mientras se
 *    serializa ([BoundedManifestOutputStream]); al superarlo se lanza sin haber escrito nada en el
 *    destino externo ([encodeTo]) y sin haber crecido un `ByteArrayOutputStream` sin limite;
 *  - documento legacy: los bytes del JSON v1 que viajan dentro del manifiesto se miden con
 *    [ManualBackupCodec.encode], que ya acota su emision a [MANUAL_BACKUP_MAX_BYTES] y reaplica el
 *    contrato v1 (estructura, duplicados, NaN, relaciones). Un legacy que el codec v1 no escribiria
 *    nunca se acepta aqui, ni al leer ni al escribir;
 *  - entrada: [decode] corta la lectura en el presupuesto y trata `declaredLength` como longitud
 *    **exacta** (ver su KDoc).
 *
 * Este slice **no** escribe imagenes ni contenedores ZIP: [MediaBackupManifest.archivePlan] es la
 * unica API que describe lo que el componente ZIP debera escribir, y es pura (no toca disco).
 */
@OptIn(ExperimentalSerializationApi::class)
object MediaBackupManifestCodec {

    /** Mensaje unico del presupuesto del manifiesto (emision, lectura y preflight). */
    internal const val MANIFEST_TOO_LARGE = "Media backup manifest is too large"

    /** Mensaje del documento legacy que no cabe en su presupuesto de bytes v1. */
    internal const val LEGACY_TOO_LARGE = "Media backup legacy document is too large"

    /** Mensaje de una lectura que no coincide con la longitud declarada por el contenedor. */
    internal const val LENGTH_MISMATCH = "Media backup manifest length does not match the declared length"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        allowComments = false
    }

    /**
     * Serializa el manifiesto **despues** de comprobarlo: preflight estructural, validacion completa
     * (incluido el documento legacy y sus bytes v1) y orden canonico. Nada se escribe si falla y
     * la salida se acota a [MEDIA_BACKUP_MAX_MANIFEST_BYTES] **mientras** se emite.
     */
    fun encode(manifest: MediaBackupManifest): ByteArray = encode(manifest, MediaBackupCodecLimits())

    internal fun encode(manifest: MediaBackupManifest, limits: MediaBackupCodecLimits): ByteArray {
        // Bound caller-owned documents before validate() builds indexes and sorted copies.
        MediaBackupManifestPreflight.checkManifest(manifest, limits.preflight)
        val validated = manifest.validate()
        requireLegacyDocumentFits(validated.legacy, limits)
        val output = BoundedManifestOutputStream(limits.maxManifestBytes)
        json.encodeToStream(MediaBackupManifest.serializer(), validated.deterministic(), output)
        return output.toByteArray()
    }

    fun encodeTo(manifest: MediaBackupManifest, output: OutputStream): Unit =
        encodeTo(manifest, output, MediaBackupCodecLimits())

    /**
     * Escribe el manifiesto en [output] **solo** cuando ya esta validado y serializado completo
     * dentro del presupuesto: un fallo de validacion o de tamano lanza antes de tocar el destino, de
     * modo que el contenedor nunca recibe un JSON truncado a medias.
     */
    internal fun encodeTo(
        manifest: MediaBackupManifest,
        output: OutputStream,
        limits: MediaBackupCodecLimits,
    ) {
        output.write(encode(manifest, limits))
    }

    fun decode(bytes: ByteArray): MediaBackupManifest = decode(bytes, MediaBackupCodecLimits())

    internal fun decode(bytes: ByteArray, limits: MediaBackupCodecLimits): MediaBackupManifest {
        require(bytes.size.toLong() <= limits.maxManifestBytes) { MANIFEST_TOO_LARGE }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Media backup manifest is not valid UTF-8", error)
        }
        return decode(text, limits)
    }

    /**
     * Lee el manifiesto de un flujo acotado (la entrada del contenedor en el siguiente componente).
     *
     * `declaredLength` es la longitud **exacta** de la entrada, no una cota superior. Es lo que un
     * lector ZIP conoce (`ZipEntry.size` es autoritativo) y lo que permite distinguir una entrada
     * truncada de un JSON invalido: si el flujo entrega menos o mas bytes que los declarados, se
     * lanza [LENGTH_MISMATCH] en vez de decodificar algo que no es la entrada declarada. Se
     * comprueba antes de leer que quepa en [MEDIA_BACKUP_MAX_MANIFEST_BYTES] y el total se corta
     * durante la lectura, asi que un flujo sin longitud tampoco puede crecer sin limite. Un
     * llamador que no conozca la longitud exacta pasa `null`.
     */
    fun decode(input: InputStream, declaredLength: Long? = null): MediaBackupManifest =
        decode(input, declaredLength, MediaBackupCodecLimits())

    internal fun decode(
        input: InputStream,
        declaredLength: Long?,
        limits: MediaBackupCodecLimits,
    ): MediaBackupManifest {
        require(declaredLength == null || declaredLength >= 0L) { "Invalid media backup manifest length" }
        require(declaredLength == null || declaredLength <= limits.maxManifestBytes) { MANIFEST_TOO_LARGE }
        val output = BoundedManifestOutputStream(limits.maxManifestBytes)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            // El flujo acotado lanza al superar el presupuesto: no se acumula mas alla del tope.
            output.write(buffer, 0, count)
        }
        require(declaredLength == null || declaredLength == total) { LENGTH_MISMATCH }
        return decode(output.toByteArray(), limits)
    }

    fun decode(jsonText: String): MediaBackupManifest = decode(jsonText, MediaBackupCodecLimits())

    internal fun decode(jsonText: String, limits: MediaBackupCodecLimits): MediaBackupManifest {
        MediaBackupManifestPreflight.check(jsonText, limits.preflightLimits)
        val decoded = json.decodeFromString(MediaBackupManifest.serializer(), jsonText)
        val validated = decoded.validate()
        requireLegacyDocumentFits(validated.legacy, limits)
        return validated
    }

    /**
     * El documento legacy viaja **completo** dentro del manifiesto, asi que su presupuesto v1 se
     * aplica tambien aqui. Se mide con [ManualBackupCodec.encode]: el codec v1 ya corta su propia
     * emision en [MANUAL_BACKUP_MAX_BYTES] y reaplica su contrato completo (estructura, duplicados,
     * NaN, relaciones), de modo que este gate no puede aceptar un legacy que el contrato v1 no
     * escribiria ni relajar sus limites. El array medido es transitorio y no se retiene: la memoria
     * extra queda acotada por el presupuesto legacy, no por el tamano del documento de entrada.
     */
    private fun requireLegacyDocumentFits(legacy: ManualBackupDocument, limits: MediaBackupCodecLimits) {
        val size = ManualBackupCodec.encode(legacy).size.toLong()
        require(size <= limits.maxLegacyBytes) { LEGACY_TOO_LARGE }
    }

    /** Orden canonico de escritura: estable e independiente del orden de llegada. */
    private fun MediaBackupManifest.deterministic() = copy(
        angleV2 = angleV2.copy(
            roots = angleV2.roots.sortedBy { it.id },
            frames = angleV2.frames.sortedWith(compareBy({ it.rootId }, { it.ordinal }, { it.id })),
            marks = angleV2.marks.sortedWith(compareBy({ it.frameId }, { it.ordinal }, { it.id })),
            images = angleV2.images.sortedBy { it.imageKey },
        ),
    )
}

/**
 * Salida acotada: comprueba el presupuesto **antes** de escribir cada bloque, de modo que la
 * serializacion falla en cuanto lo rebasa en vez de crecer hasta el tamano del documento.
 */
private class BoundedManifestOutputStream(private val maxBytes: Long) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    private var written = 0L

    override fun write(value: Int) {
        require(written < maxBytes) { MediaBackupManifestCodec.MANIFEST_TOO_LARGE }
        bytes.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && written <= maxBytes - length) {
            MediaBackupManifestCodec.MANIFEST_TOO_LARGE
        }
        bytes.write(buffer, offset, length)
        written += length
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
