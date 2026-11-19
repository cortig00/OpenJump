package com.openjump.app.video.angles

import com.openjump.app.video.VideoFrameSafety

/**
 * Contrato puro (sin Android) para congelar un fotograma de vídeo **antes** de colocar puntos de
 * ángulo. Esta capa no dibuja, no persiste y no reproduce: solo decide, con evidencia tipada, si un
 * bitmap decodificado puede etiquetarse como el fotograma solicitado y qué metadatos deben quedar
 * registrados junto a él.
 *
 * ## Qué significa (y qué no) "congelado"
 *
 * Un frame solo se acepta si los píxeles y el timestamp de presentación provienen del **mismo buffer
 * de salida del decodificador** ([FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH]) y ese PTS
 * coincide con el PTS indexado solicitado. Aun así, ese PTS es **reportado por el decodificador**: no
 * certifica el instante físico de captura del sensor ni la ausencia de reordenación interna del
 * códec. El contrato lo nombra [FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED] y nunca
 * "tiempo observado".
 *
 * Las rutas que no exponen el PTS de la muestra devuelta (por ejemplo
 * `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST)` o `getFrameAtIndex`) se representan
 * como [FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED] y la política las **rechaza**: no
 * existe una etiqueta "captura exacta" para un bitmap ambiguo ni un fallback silencioso a otro
 * fotograma.
 *
 * ## Orientación
 *
 * La rotación declarada por el contenedor no demuestra por sí sola qué hizo el decodificador con los
 * píxeles. [FrameOrientationPlanner] solo resuelve la orientación cuando las dimensiones decodificadas
 * lo demuestran (90/270 sobre frame no cuadrado) o cuando no hay rotación declarada. Rotación 180 y
 * 90/270 sobre frame cuadrado quedan [FrameOrientationPlan.Undetermined]: las dimensiones no permiten
 * distinguir si el decoder rotó, y este contrato prefiere bloquear antes que mostrar una imagen
 * girada como si fuera la correcta.
 *
 * ## Límites conocidos
 *
 * - No se implementa aquí el decodificador MediaCodec que produce la evidencia fuerte: este slice
 *   solo fija el contrato, la política y la identidad visual de verificación. Mientras no exista un
 *   productor [FrameTemporalEvidence] con [FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH],
 *   cualquier intento de congelar termina en [FrameFreezeDecision.Rejected], que es el bloqueo seguro
 *   exigido.
 * - HDR, codecs con timestamps sintetizados y API 26–27 concretos no están demostrados en esta capa.
 * - El digest de contenido ([FrameVisualIdentity]) acredita identidad de píxeles entre lo mostrado y
 *   lo anotado; no acredita el instante temporal.
 */

/** Identidad opaca de una sesión de fuente de vídeo, acuñada al sellar su snapshot privado. */
@JvmInline
value class FrameSourceIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "La identidad de la fuente no puede estar vacía." }
    }
}

/**
 * Solicitud explícita de congelación.
 *
 * [assignedPtsUs] es el PTS que el índice de frames asigna al [frameIndex]; se usa para pedir la
 * decodificación, pero **no es evidencia de lo que el decodificador devolvió**. La evidencia real
 * viaja en [FrameTemporalEvidence].
 *
 * [sourceIdentity] debe ser la identidad acuñada por [FrozenFrameSourceSession]; no es una etiqueta
 * libre del llamador ni una prueba de igualdad de contenido entre fuentes. Una sesión solo acepta
 * solicitudes con su propia identidad y su índice sellado (misma metadata/PTS no basta).
 *
 * Las dimensiones declaradas describen el track de origen. Se aceptan aunque superen la envolvente
 * de proceso ([VideoFrameSafety.MAX_FRAME_AREA]) para poder **rechazarlas con un motivo tipado** en
 * lugar de lanzar desde un constructor al recibir metadatos de un vídeo real; la política y el
 * congelador las bloquean antes de asignar memoria. La envolvente no se relaja.
 */
data class FrameFreezeRequest(
    val sourceIdentity: FrameSourceIdentity,
    val frameIndex: Int,
    val assignedPtsUs: Long,
    val indexFrameCount: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val declaredRotationDegrees: Int,
) {
    init {
        require(indexFrameCount >= 1) { "El índice debe contener al menos un frame." }
        require(frameIndex in 0 until indexFrameCount) { "El frame solicitado está fuera del índice." }
        require(assignedPtsUs >= 0L) { "El PTS asignado no puede ser negativo." }
        require(declaredRotationDegrees in SUPPORTED_ROTATIONS) { "Rotación de vídeo no compatible." }
        require(encodedWidth in 1..VideoFrameSafety.MAX_DIMENSION && encodedHeight in 1..VideoFrameSafety.MAX_DIMENSION) {
            "Dimensiones declaradas de vídeo no válidas."
        }
    }

    private companion object {
        val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

/**
 * Grado de correlación temporal realmente demostrado entre los píxeles congelados y el frame pedido.
 *
 * No es una escala de "calidad": las rutas no verificables no se degradan a una etiqueta menor, se
 * rechazan.
 */
enum class FrameTemporalCorrelation {
    /**
     * Los píxeles y el PTS provienen del mismo buffer de salida del decodificador y ese PTS es
     * exactamente el PTS indexado solicitado. Es la única evidencia aceptada por
     * [FrameFreezePolicy]. Sigue siendo un PTS **reportado por el decodificador**, no un tiempo físico
     * observado.
     */
    CODEC_OUTPUT_BUFFER_PTS_MATCH,

    /** El buffer de salida trae un PTS, pero no coincide con el PTS indexado solicitado. */
    CODEC_OUTPUT_BUFFER_PTS_MISMATCH,

    /**
     * El bitmap se obtuvo con una búsqueda "más cercana" (p. ej. `OPTION_CLOSEST`) que no expone el
     * PTS de la muestra devuelta. Puede parecer correcto, pero no es verificable: se bloquea.
     */
    RETRIEVER_CLOSEST_UNVERIFIED,

    /** La ruta de decodificación no produjo ninguna evidencia temporal. */
    NONE,
}

/**
 * Generación de un buffer de salida concreto del decodificador.
 *
 * Es el único objeto capaz de acreditar «estos píxeles vienen de este buffer, con este PTS». Su
 * constructor es interno y su único punto de acuñación auditado es el bucle MediaCodec de
 * [CodecFrozenFrameProducer]; además es **de un solo uso**, de modo que una evidencia no puede
 * reutilizarse para otra imagen ni reproducirse después. Dentro de un módulo Gradle único (esta app)
 * `internal` no impide que código del mismo módulo acuñe una generación: el contrato es «frontera
 * pública sellada + un único punto de acuñación auditado», no una garantía criptográfica. Lo que sí
 * queda demostrado es que **ningún llamador externo al módulo** puede atribuir un PTS del códec.
 */
class CodecOutputGeneration internal constructor(
    val nonce: Long,
    internal val outputBufferIndex: Int,
) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Consume la generación. Devuelve `false` si ya se usó: la evidencia no se puede replayar. */
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)

    internal companion object {
        private val nextNonce = java.util.concurrent.atomic.AtomicLong(1L)

        internal fun mint(outputBufferIndex: Int): CodecOutputGeneration =
            CodecOutputGeneration(nextNonce.getAndIncrement(), outputBufferIndex)
    }
}

/**
 * Evidencia temporal de una decodificación concreta.
 *
 * El constructor es **privado**: no existe forma pública de atribuir a un bitmap el PTS de un buffer
 * de salida. Las únicas fábricas públicas ([unverifiedRetriever], [none]) describen rutas que la
 * política **siempre** rechaza y no pueden transportar un PTS del códec. La correlación fuerte solo se
 * produce desde [fromCodecOutputBuffer], que exige una [CodecOutputGeneration] de un solo uso acuñada
 * por el puente MediaCodec. Así, un llamador cualquiera no puede fabricar una prueba temporal y
 * obtener una [FrozenFrameImage] con un PTS del que no tiene evidencia.
 *
 * [codecOutputPtsUs] solo puede estar presente cuando los píxeles vienen del buffer de salida de un
 * decodificador que reportó ese PTS. Para las rutas aproximadas vale `null`: no se rellena con el PTS
 * asignado para "parecer" una captura exacta.
 */
class FrameTemporalEvidence private constructor(
    val correlation: FrameTemporalCorrelation,
    val assignedPtsUs: Long,
    val codecOutputPtsUs: Long?,
    val sourceIdentity: FrameSourceIdentity,
    val frameIndex: Int,
    val generationNonce: Long?,
) {
    init {
        require(assignedPtsUs >= 0L)
        require(frameIndex >= 0)
        when (correlation) {
            FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH,
            FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MISMATCH,
            -> requireNotNull(codecOutputPtsUs) {
                "La evidencia del buffer de salida exige el PTS reportado por el decodificador."
            }
            FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED,
            FrameTemporalCorrelation.NONE,
            -> require(codecOutputPtsUs == null) {
                "Una ruta sin PTS observado no puede atribuirse un PTS de salida del decodificador."
            }
        }
        if (correlation == FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH) {
            require(codecOutputPtsUs == assignedPtsUs) {
                "La correlación exacta exige que el PTS de salida coincida con el PTS asignado."
            }
            requireNotNull(generationNonce) { "La correlación exacta exige una generación del códec." }
        }
        if (correlation == FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MISMATCH) {
            require(codecOutputPtsUs != assignedPtsUs) {
                "Un PTS de salida igual al asignado no es un desajuste."
            }
        }
    }

    companion object {
        /**
         * Evidencia de una ruta `MediaMetadataRetriever` que no expone el PTS de la muestra devuelta.
         * Es pública a propósito: no acredita nada y la política la rechaza siempre.
         */
        fun unverifiedRetriever(
            assignedPtsUs: Long,
            sourceIdentity: FrameSourceIdentity,
            frameIndex: Int,
        ): FrameTemporalEvidence = FrameTemporalEvidence(
            correlation = FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED,
            assignedPtsUs = assignedPtsUs,
            codecOutputPtsUs = null,
            sourceIdentity = sourceIdentity,
            frameIndex = frameIndex,
            generationNonce = null,
        )

        /** Evidencia de una ruta que no produjo ninguna información temporal. Siempre rechazada. */
        fun none(
            assignedPtsUs: Long,
            sourceIdentity: FrameSourceIdentity,
            frameIndex: Int,
        ): FrameTemporalEvidence = FrameTemporalEvidence(
            correlation = FrameTemporalCorrelation.NONE,
            assignedPtsUs = assignedPtsUs,
            codecOutputPtsUs = null,
            sourceIdentity = sourceIdentity,
            frameIndex = frameIndex,
            generationNonce = null,
        )

        /**
         * Evidencia fuerte: píxeles y PTS del mismo buffer de salida. Solo el puente MediaCodec puede
         * aportar la [generation]; consumirla impide reutilizarla para otra imagen.
         */
        internal fun fromCodecOutputBuffer(
            generation: CodecOutputGeneration,
            sourceIdentity: FrameSourceIdentity,
            frameIndex: Int,
            assignedPtsUs: Long,
            codecOutputPtsUs: Long,
        ): FrameTemporalEvidence {
            require(generation.consume()) { "La generación del códec ya se consumió." }
            val correlation = if (codecOutputPtsUs == assignedPtsUs) {
                FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH
            } else {
                FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MISMATCH
            }
            return FrameTemporalEvidence(
                correlation = correlation,
                assignedPtsUs = assignedPtsUs,
                codecOutputPtsUs = codecOutputPtsUs,
                sourceIdentity = sourceIdentity,
                frameIndex = frameIndex,
                generationNonce = generation.nonce,
            )
        }
    }
}

/**
 * Evidencia **del productor** sobre si el decodificador pudo aplicar la rotación declarada.
 *
 * Las dimensiones no demuestran la orientación en dos casos: rotación 180 (no cambia tamaño) y 90/270
 * sobre un frame cuadrado (ambas orientaciones miden lo mismo). Este contrato **no** infiere la
 * orientación desde el tamaño: exige una afirmación explícita del productor y, sin ella, se bloquea.
 *
 * [BufferModeWithoutSurface] afirma un hecho de configuración, no de píxeles observados: el productor
 * configuró `MediaCodec` **sin `Surface`** (`configure(format, null, null, 0)`, salida por
 * `getOutputImage`/`getOutputBuffer`). La rotación del contenedor (`MediaFormat.KEY_ROTATION`) es una
 * indicación para decodificadores con superficie, que la aplican al componer la superficie; en modo
 * buffer no hay composición de superficie y los buffers salen en la orientación codificada, así que la
 * congelación debe aplicar la rotación declarada.
 *
 * Límite explícito de esta afirmación: es el **contrato documentado del framework**, no una
 * observación de píxeles. Este slice no ejecuta un dispositivo/AVD, así que no hay verificación
 * empírica de 180 ni de cuadrado 90/270; por eso la certeza viaja etiquetada como
 * [FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM] en la procedencia y existe una
 * prueba instrumentada que la comprobaría en dispositivo. Si esa prueba fallara en hardware, el camino
 * correcto es retirar esta evidencia (volver a bloquear), no reinterpretar las dimensiones.
 */
sealed interface FrameOrientationEvidence {

    /** El decodificador corrió en modo buffer sin superficie: la salida está en la orientación codificada. */
    data object BufferModeWithoutSurface : FrameOrientationEvidence

    companion object {
        /**
         * Evidencia de configuración sin superficie. Único punto de acuñación en producción: el bucle
         * `MediaCodec` de [CodecFrozenFrameProducer], que es quien decide `configure(..., null, ...)`.
         * Es `internal` a propósito para que ninguna capa externa al módulo pueda afirmarla.
         */
        internal fun bufferModeWithoutSurface(): FrameOrientationEvidence = BufferModeWithoutSurface
    }
}

/** Cómo se concluyó la orientación presentada a partir de dimensiones y rotación declarada. */
enum class FrameOrientationCertainty {
    /** El contenedor no declara rotación: la orientación presentada es la codificada. */
    DECLARED_NO_ROTATION,

    /** 90/270 sin cuadrar: el tamaño decodificado demuestra que el decodificador ya rotó. */
    DIMENSIONS_INDICATE_DECODER_APPLIED_ROTATION,

    /** 90/270 sin cuadrar: el tamaño decodificado sigue la orientación codificada; la congelación debe rotar. */
    DIMENSIONS_INDICATE_ENCODED_ORIENTATION,

    /**
     * 180 o 90/270 en frame cuadrado resuelto por evidencia del productor (decodificación en modo
     * buffer sin superficie), no por las dimensiones: la salida está en la orientación codificada y la
     * congelación aplica la rotación declarada. No acredita fidelidad de píxeles frente a una
     * referencia de render.
     */
    PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM,
}

/** Plan de orientación calculado, o motivo explícito por el que no puede probarse. */
sealed interface FrameOrientationPlan {

    data class Determined(
        /** Rotación que la congelación debe aplicar al bitmap decodificado (0 si ya está orientado). */
        val rotationToApplyDegrees: Int,
        val orientedWidth: Int,
        val orientedHeight: Int,
        val certainty: FrameOrientationCertainty,
    ) : FrameOrientationPlan

    data class Undetermined(val reason: UndeterminedReason) : FrameOrientationPlan

    enum class UndeterminedReason {
        /** Rotación 180 no cambia dimensiones: no se puede probar si el decoder la aplicó. */
        ROTATION_180_CANNOT_BE_PROVEN_FROM_METADATA,

        /** Frame cuadrado con rotación 90/270: las dimensiones son idénticas en ambas orientaciones. */
        SQUARE_ROTATED_FRAME_DIMENSIONS_ARE_AMBIGUOUS,

        /** Las dimensiones decodificadas no corresponden ni a la orientación codificada ni a la presentada. */
        DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
    }
}

/** Cálculo determinista de orientación a partir de metadatos, sin tocar píxeles. */
object FrameOrientationPlanner {

    fun plan(
        encodedWidth: Int,
        encodedHeight: Int,
        declaredRotationDegrees: Int,
        decodedWidth: Int,
        decodedHeight: Int,
        evidence: FrameOrientationEvidence? = null,
    ): FrameOrientationPlan {
        VideoFrameSafety.checkedArea(encodedWidth, encodedHeight)
        VideoFrameSafety.checkedArea(decodedWidth, decodedHeight)
        require(declaredRotationDegrees in setOf(0, 90, 180, 270)) {
            "Rotación de vídeo no compatible."
        }
        val swaps = declaredRotationDegrees == 90 || declaredRotationDegrees == 270
        val presentationWidth = if (swaps) encodedHeight else encodedWidth
        val presentationHeight = if (swaps) encodedWidth else encodedHeight

        // Con evidencia de buffer sin superficie el decodificador no aplicó la rotación: los buffers
        // deben medir exactamente la orientación codificada y la congelación rota lo declarado. Si las
        // dimensiones no son las codificadas, la evidencia y los píxeles se contradicen y se bloquea
        // (no se reinterpreta la evidencia para "cuadrar" el tamaño).
        if (evidence == FrameOrientationEvidence.BufferModeWithoutSurface &&
            declaredRotationDegrees != 0
        ) {
            if (decodedWidth != encodedWidth || decodedHeight != encodedHeight) {
                return FrameOrientationPlan.Undetermined(
                    FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
                )
            }
            return FrameOrientationPlan.Determined(
                rotationToApplyDegrees = declaredRotationDegrees,
                orientedWidth = presentationWidth,
                orientedHeight = presentationHeight,
                certainty = FrameOrientationCertainty.PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM,
            )
        }

        return when (declaredRotationDegrees) {
            0 -> when {
                decodedWidth == encodedWidth && decodedHeight == encodedHeight -> FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = 0,
                    orientedWidth = decodedWidth,
                    orientedHeight = decodedHeight,
                    certainty = FrameOrientationCertainty.DECLARED_NO_ROTATION,
                )
                else -> FrameOrientationPlan.Undetermined(
                    FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
                )
            }
            180 -> FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.ROTATION_180_CANNOT_BE_PROVEN_FROM_METADATA,
            )
            else -> when {
                encodedWidth == encodedHeight -> FrameOrientationPlan.Undetermined(
                    FrameOrientationPlan.UndeterminedReason.SQUARE_ROTATED_FRAME_DIMENSIONS_ARE_AMBIGUOUS,
                )
                decodedWidth == presentationWidth && decodedHeight == presentationHeight -> FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = 0,
                    orientedWidth = decodedWidth,
                    orientedHeight = decodedHeight,
                    certainty = FrameOrientationCertainty.DIMENSIONS_INDICATE_DECODER_APPLIED_ROTATION,
                )
                decodedWidth == encodedWidth && decodedHeight == encodedHeight -> FrameOrientationPlan.Determined(
                    rotationToApplyDegrees = declaredRotationDegrees,
                    orientedWidth = presentationWidth,
                    orientedHeight = presentationHeight,
                    certainty = FrameOrientationCertainty.DIMENSIONS_INDICATE_ENCODED_ORIENTATION,
                )
                else -> FrameOrientationPlan.Undetermined(
                    FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
                )
            }
        }
    }
}

/** Presupuesto de memoria para la copia inmutable congelada, sin superar [VideoFrameSafety.MAX_WORKING_BYTES]. */
object FrameFreezeBudget {

    private const val BYTES_PER_PIXEL = 4L

    /**
     * ¿Cabe [width] × [height] en la envolvente de área por frame? **No lanza**: devuelve `false`
     * para dimensiones no positivas, demasiado grandes o con área por encima de
     * [VideoFrameSafety.MAX_FRAME_AREA]. Es la consulta que usan las rutas fail-closed para convertir
     * metadatos de un vídeo real en un rechazo tipado en lugar de una excepción.
     */
    fun isWithinFrameEnvelope(width: Int, height: Int): Boolean =
        width in 1..VideoFrameSafety.MAX_DIMENSION &&
            height in 1..VideoFrameSafety.MAX_DIMENSION &&
            width.toLong() * height.toLong() <= VideoFrameSafety.MAX_FRAME_AREA

    /** Bytes de [copies] copias ARGB_8888 de [width] × [height] más el scratch de una fila. */
    fun requiredBytes(width: Int, height: Int, copies: Int = 1): Long {
        VideoFrameSafety.checkedArea(width, height)
        require(copies >= 1) { "Debe presupuestarse al menos una copia congelada." }
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        val copy = Math.multiplyExact(pixels, BYTES_PER_PIXEL)
        val allCopies = Math.multiplyExact(copy, copies.toLong())
        val row = Math.multiplyExact(maxOf(width, height).toLong(), BYTES_PER_PIXEL)
        return Math.addExact(allCopies, row)
    }

    /**
     * `true` si el bitmap decodificado (que sigue vivo mientras se crea la copia) más
     * [simultaneousFrozenCopies] copias congeladas simultáneas caben en la envolvente de trabajo. Se
     * rechaza antes de asignar. Con rotación y/o un resultado mutable que exige una copia inmutable
     * adicional hay dos copias vivas a la vez: presupuestarlas evita prometer memoria que no se tiene.
     *
     * Consulta **total**: nunca lanza por dimensiones fuera de la envolvente, copias menores que 1 o
     * desbordamiento aritmético; en todos esos casos devuelve `false` (no cabe). Los límites de
     * [VideoFrameSafety] no se relajan: solo cambia la forma de comunicar el rechazo.
     */
    /**
     * Peor caso de copias congeladas simultáneas: la copia orientada/decodificada, la matriz de
     * píxeles de la instantánea inmutable y el bitmap inmutable resultante pueden coexistir con el
     * bitmap decodificado y con la matriz ARGB de la conversión YUV. Presupuestar este máximo evita
     * prometer memoria que la ruta real no tiene.
     */
    const val MAX_SIMULTANEOUS_FROZEN_COPIES = 3

    /**
     * Bytes de la matriz ARGB_8888 que la conversión YUV→RGB necesita para [width] × [height].
     *
     * Consulta **total**: devuelve `null` (no lanza) si las dimensiones no son positivas, superan la
     * envolvente o el producto desborda. Las rutas que asignan deben usar esta función **antes** de
     * crear el array, para convertir un tamaño imposible en rechazo tipado.
     */
    fun argbArrayBytes(width: Int, height: Int): Long? {
        if (!isWithinFrameEnvelope(width, height)) return null
        return try {
            Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), BYTES_PER_PIXEL)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * Presupuesto completo de una captura antes de asignar píxeles: matriz ARGB de la conversión
     * ([argbArrayBytes]), bitmap decodificado creado a partir de ella y las
     * [MAX_SIMULTANEOUS_FROZEN_COPIES] copias congeladas del peor caso (rotación + instantánea
     * inmutable). No incluye buffers internos del décodificador, que esta capa no controla; por eso
     * la envolvente se aplica a las asignaciones propias y no se declara soportado ningún tamaño no
     * verificado en dispositivo.
     *
     * Consulta total: `null` si algún término no es presupuestable con aritmética segura.
     */
    fun captureRequiredBytes(
        decodedWidth: Int,
        decodedHeight: Int,
        frozenWidth: Int,
        frozenHeight: Int,
        simultaneousFrozenCopies: Int = MAX_SIMULTANEOUS_FROZEN_COPIES,
    ): Long? {
        if (simultaneousFrozenCopies < 1) return null
        val conversionArray = argbArrayBytes(decodedWidth, decodedHeight) ?: return null
        val decodedBitmap = argbArrayBytes(decodedWidth, decodedHeight) ?: return null
        if (!isWithinFrameEnvelope(frozenWidth, frozenHeight)) return null
        return try {
            val frozen = requiredBytes(frozenWidth, frozenHeight, simultaneousFrozenCopies)
            Math.addExact(Math.addExact(conversionArray, decodedBitmap), frozen)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** `true` si [captureRequiredBytes] cabe en [VideoFrameSafety.MAX_WORKING_BYTES]. */
    fun isWithinCaptureBudget(
        decodedWidth: Int,
        decodedHeight: Int,
        frozenWidth: Int,
        frozenHeight: Int,
        simultaneousFrozenCopies: Int = MAX_SIMULTANEOUS_FROZEN_COPIES,
    ): Boolean {
        val required = captureRequiredBytes(decodedWidth, decodedHeight, frozenWidth, frozenHeight, simultaneousFrozenCopies)
            ?: return false
        return required <= VideoFrameSafety.MAX_WORKING_BYTES
    }

    fun isWithinBudget(
        decodedAllocationBytes: Long,
        frozenWidth: Int,
        frozenHeight: Int,
        simultaneousFrozenCopies: Int = 1,
    ): Boolean {
        if (decodedAllocationBytes <= 0L) return false
        if (simultaneousFrozenCopies < 1) return false
        if (!isWithinFrameEnvelope(frozenWidth, frozenHeight)) return false
        return try {
            val total = Math.addExact(
                decodedAllocationBytes,
                requiredBytes(frozenWidth, frozenHeight, simultaneousFrozenCopies),
            )
            total <= VideoFrameSafety.MAX_WORKING_BYTES
        } catch (_: ArithmeticException) {
            false
        }
    }
}

/** Resultado de una asignación propia sujeta al presupuesto de memoria. */
internal sealed interface FrameAllocation<out T> {

    data class Allocated<T>(val value: T) : FrameAllocation<T>

    data class Unavailable(
        val failure: FrameAllocationFailure,
        val detail: String,
    ) : FrameAllocation<Nothing>
}

internal enum class FrameAllocationFailure {
    /** La asignación no cabe en el presupuesto o sus dimensiones no son presupuestables. */
    BUDGET_EXCEEDED,

    /** El asignador no pudo entregar el objeto (p. ej. bitmap reciclado o buffers insuficientes). */
    REFUSED,

    /** `OutOfMemoryError` al asignar: se convierte en rechazo tipado, nunca en una imagen parcial. */
    OUT_OF_MEMORY,
}

/**
 * Guarda de asignación usada por las rutas que crean arrays y bitmaps de frame.
 *
 * Comprueba el presupuesto **antes** de llamar a [block], de modo que un tamaño imposible no llega a
 * asignar; si la asignación real lanza [OutOfMemoryError] (otras asignaciones vivas, fragmentación o
 * memoria opaca del decodificador) se devuelve [FrameAllocationFailure.OUT_OF_MEMORY] en lugar de
 * propagar el error. No pretende limitar la memoria interna del códec: solo las asignaciones que
 * esta capa controla y presupuesta.
 */
internal inline fun <T : Any> guardedAllocation(
    requiredBytes: Long,
    maxBytes: Long = VideoFrameSafety.MAX_WORKING_BYTES,
    block: () -> T?,
): FrameAllocation<T> {
    if (requiredBytes <= 0L || requiredBytes > maxBytes) {
        return FrameAllocation.Unavailable(
            FrameAllocationFailure.BUDGET_EXCEEDED,
            "La asignación de $requiredBytes bytes supera el presupuesto de $maxBytes bytes.",
        )
    }
    return try {
        val value = block()
            ?: return FrameAllocation.Unavailable(FrameAllocationFailure.REFUSED, "La asignación no entregó ningún objeto.")
        FrameAllocation.Allocated(value)
    } catch (_: OutOfMemoryError) {
        FrameAllocation.Unavailable(
            FrameAllocationFailure.OUT_OF_MEMORY,
            "No se pudo asignar $requiredBytes bytes de píxeles.",
        )
    }
}

/**
 * Observación completa que la capa Android entrega a [FrameFreezePolicy]: solicitud, evidencia
 * temporal, dimensiones reales del bitmap, presupuesto y digest del contenido congelado.
 */
data class FrameFreezeObservation(
    val request: FrameFreezeRequest,
    val temporal: FrameTemporalEvidence,
    /** Plan calculado por el productor; la política lo recalcula y exige que coincida. */
    val orientation: FrameOrientationPlan.Determined,
    /**
     * Evidencia de orientación que el productor afirma (modo buffer sin superficie) o `null`. La
     * política **vuelve a planificar con la misma evidencia** y exige el mismo resultado: un plan de
     * 180/cuadrado que no venga respaldado por esta evidencia se rechaza.
     */
    val orientationEvidence: FrameOrientationEvidence? = null,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val decodedAllocationBytes: Long,
    /** Identidad de contenido independiente del PTS ([FrameVisualIdentity]). */
    val contentDigest: Long,
)

/** Motivos tipados de rechazo; nunca se sustituyen por un frame alternativo. */
enum class FrameFreezeRejectionReason {
    /**
     * La sesión de fuente ya no es válida para esta solicitud: está cerrada, su snapshot privado no
     * coincide con la longitud sellada, o la identidad de la solicitud no es la que acuñó la sesión.
     * Es un fallo de frontera, no una diferencia de metadatos del contenedor.
     */
    SOURCE_SESSION_INVALID,
    /** La ruta de decodificación no demostró qué muestra devolvió. */
    UNVERIFIED_TEMPORAL_CORRELATION,

    /** El buffer de salida trae un PTS distinto del solicitado. */
    DECODER_PTS_MISMATCH,

    /** La evidencia se construyó para otra solicitud (fuente/frame/PTS). */
    REQUEST_EVIDENCE_MISMATCH,

    /** No puede probarse la orientación presentada con los metadatos disponibles. */
    ORIENTATION_UNDETERMINED,

    /** Copia inmutable + bitmap decodificado superan la envolvente de memoria. */
    MEMORY_BUDGET_EXCEEDED,

    /** La ruta de decodificación no entregó un bitmap utilizable (p. ej. ya reciclado). */
    DECODED_BITMAP_UNAVAILABLE,

    /**
     * El decodificador no entregó un buffer de salida con un diseño (color/stride/crop) que esta capa
     * pueda convertir sin adivinar. Se bloquea en lugar de inventar píxeles.
     */
    UNSUPPORTED_CODEC_OUTPUT,

    /** El PTS solicitado no se alcanzó dentro del presupuesto de decodificación (GOP/duración). */
    DECODE_BUDGET_EXCEEDED,

    /** La captura se canceló antes de obtener una imagen verificada. */
    CANCELLED,

    /** Los metadatos del contenedor no coinciden con el índice/solicitud (fuente cambiada). */
    SOURCE_METADATA_MISMATCH,
}

/** Qué puede afirmarse del tiempo del fotograma congelado. */
enum class FrameTemporalClaim {
    /** Píxeles y PTS vienen del mismo buffer de salida del decodificador; el PTS es reportado. */
    DECODER_OUTPUT_BUFFER_CORRELATED,
}

/**
 * Metadatos de procedencia que acompañan a la imagen congelada. Separar explícitamente
 * [assignedRequestPtsUs] de [decoderReportedOutputPtsUs] evita presentar como observado un tiempo que
 * solo se asignó en la solicitud.
 */
data class FrameCaptureProvenance(
    val schemaVersion: Int,
    val sourceIdentity: FrameSourceIdentity,
    val frameIndex: Int,
    /** PTS tomado del índice para solicitar la decodificación. Asignado, no observado. */
    val assignedRequestPtsUs: Long,
    /** PTS reportado por el decodificador para el buffer que produjo estos píxeles. */
    val decoderReportedOutputPtsUs: Long,
    val temporalClaim: FrameTemporalClaim,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val declaredRotationDegrees: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val rotationAppliedDegrees: Int,
    val frozenWidth: Int,
    val frozenHeight: Int,
    val orientationCertainty: FrameOrientationCertainty,
    /** Identidad de contenido de la imagen congelada (misma imagen para mostrar y anotar). */
    val contentDigest: Long,
) {
    init {
        require(schemaVersion >= 1)
        require(frameIndex >= 0)
        require(assignedRequestPtsUs >= 0L && decoderReportedOutputPtsUs >= 0L)
        require(rotationAppliedDegrees in setOf(0, 90, 180, 270))
        require(frozenWidth >= 1 && frozenHeight >= 1)
        VideoFrameSafety.checkedArea(frozenWidth, frozenHeight)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Resultado de la política: aceptado con procedencia, o bloqueado con motivo tipado. */
sealed interface FrameFreezeDecision {

    data class Accepted(val provenance: FrameCaptureProvenance) : FrameFreezeDecision

    data class Rejected(val reason: FrameFreezeRejectionReason, val detail: String) : FrameFreezeDecision
}

/**
 * Política pura que decide si una observación puede convertirse en imagen congelada etiquetable.
 *
 * Reglas (todas obligatorias):
 * 1. La evidencia temporal debe ser [FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH] con PTS
 *    igual al asignado y a la solicitud. `OPTION_CLOSEST` y cualquier ruta sin PTS de salida se
 *    rechazan aunque el bitmap parezca correcto.
 * 2. La orientación se recalcula desde la solicitud y las dimensiones decodificadas; un plan
 *    aportado por el productor no se cree, debe coincidir. Si no puede probarse, se rechaza.
 * 3. El digest de contenido se conserva para acreditar que se anota la misma imagen que se muestra.
 * 4. La copia inmutable debe caber en el presupuesto junto al bitmap decodificado.
 *
 * La política no crea bitmaps y no tiene fallback: el rechazo es la única alternativa a la evidencia
 * fuerte.
 */
class FrameFreezePolicy {

    fun evaluate(observation: FrameFreezeObservation): FrameFreezeDecision {
        val request = observation.request
        val temporal = observation.temporal
        if (temporal.assignedPtsUs != request.assignedPtsUs) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH,
                "La evidencia temporal se construyó para otro PTS asignado.",
            )
        }
        if (temporal.sourceIdentity != request.sourceIdentity || temporal.frameIndex != request.frameIndex) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.REQUEST_EVIDENCE_MISMATCH,
                "La evidencia temporal pertenece a otra fuente o a otro frame.",
            )
        }
        // Fuente real fuera de la envolvente de proceso: rechazo tipado antes de planificar o
        // presupuestar (el planificador de orientación lanza para áreas fuera de límite).
        if (!FrameFreezeBudget.isWithinFrameEnvelope(request.encodedWidth, request.encodedHeight)) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "La fuente declarada ${request.encodedWidth}×${request.encodedHeight} supera la envolvente de ${VideoFrameSafety.MAX_FRAME_AREA} px.",
            )
        }
        if (observation.decodedWidth < 1 || observation.decodedHeight < 1) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                "El bitmap decodificado no tiene dimensiones válidas.",
            )
        }
        val derivedOrientation = try {
            FrameOrientationPlanner.plan(
                encodedWidth = request.encodedWidth,
                encodedHeight = request.encodedHeight,
                declaredRotationDegrees = request.declaredRotationDegrees,
                decodedWidth = observation.decodedWidth,
                decodedHeight = observation.decodedHeight,
                evidence = observation.orientationEvidence,
            )
        } catch (_: IllegalArgumentException) {
            FrameOrientationPlan.Undetermined(
                FrameOrientationPlan.UndeterminedReason.DECODED_DIMENSIONS_DO_NOT_MATCH_DECLARED_ROTATION,
            )
        }
        if (derivedOrientation is FrameOrientationPlan.Undetermined) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                derivedOrientation.reason.name,
            )
        }
        require(derivedOrientation is FrameOrientationPlan.Determined)
        if (derivedOrientation != observation.orientation) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                "El plan de orientación aportado no coincide con los metadatos decodificados.",
            )
        }

        when (temporal.correlation) {
            FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MATCH -> Unit
            FrameTemporalCorrelation.CODEC_OUTPUT_BUFFER_PTS_MISMATCH -> {
                return FrameFreezeDecision.Rejected(
                    FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                    "El decodificador reportó ${temporal.codecOutputPtsUs}µs para los píxeles; se pidió ${request.assignedPtsUs}µs.",
                )
            }
            FrameTemporalCorrelation.RETRIEVER_CLOSEST_UNVERIFIED,
            FrameTemporalCorrelation.NONE,
            -> {
                return FrameFreezeDecision.Rejected(
                    FrameFreezeRejectionReason.UNVERIFIED_TEMPORAL_CORRELATION,
                    "La ruta de decodificación no demuestra qué muestra devolvió: no se etiqueta como captura exacta.",
                )
            }
        }

        val codecOutputPtsUs = requireNotNull(temporal.codecOutputPtsUs)
        if (codecOutputPtsUs != request.assignedPtsUs) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.DECODER_PTS_MISMATCH,
                "El PTS del buffer de salida no coincide con el solicitado.",
            )
        }

        if (!FrameFreezeBudget.isWithinBudget(
                observation.decodedAllocationBytes,
                derivedOrientation.orientedWidth,
                derivedOrientation.orientedHeight,
            )
        ) {
            return FrameFreezeDecision.Rejected(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "La copia congelada supera el presupuesto de ${VideoFrameSafety.MAX_WORKING_BYTES} bytes.",
            )
        }

        return FrameFreezeDecision.Accepted(
            FrameCaptureProvenance(
                schemaVersion = FrameCaptureProvenance.CURRENT_SCHEMA_VERSION,
                sourceIdentity = request.sourceIdentity,
                frameIndex = request.frameIndex,
                assignedRequestPtsUs = request.assignedPtsUs,
                decoderReportedOutputPtsUs = codecOutputPtsUs,
                temporalClaim = FrameTemporalClaim.DECODER_OUTPUT_BUFFER_CORRELATED,
                encodedWidth = request.encodedWidth,
                encodedHeight = request.encodedHeight,
                declaredRotationDegrees = request.declaredRotationDegrees,
                decodedWidth = observation.decodedWidth,
                decodedHeight = observation.decodedHeight,
                rotationAppliedDegrees = derivedOrientation.rotationToApplyDegrees,
                frozenWidth = derivedOrientation.orientedWidth,
                frozenHeight = derivedOrientation.orientedHeight,
                orientationCertainty = derivedOrientation.certainty,
                contentDigest = observation.contentDigest,
            ),
        )
    }
}
