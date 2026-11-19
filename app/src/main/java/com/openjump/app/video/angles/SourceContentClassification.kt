package com.openjump.app.video.angles

/**
 * Política de tipado de los fallos del indexador sobre una fuente ya copiada a un snapshot privado.
 *
 * ## Qué demostró la ejecución instrumentada del 2026-11-10
 *
 * Al indexar 4096 bytes `0x5A`, `MediaExtractor.setDataSource(fd)` lanzó `IOException` y el
 * `logcat` del AVD mostró `E NuMediaExtractor: initMediaExtractor: failed to create MediaExtractor`.
 * Ese mensaje **no** acredita que el contenido no sea un vídeo: es un fallo de instanciación del
 * extractor y puede deberse al propio servicio de extractores, a un contenedor que ese dispositivo no
 * soporta (la lista de formatos del sistema no es la de este código) o a un fallo de E/S.
 *
 * ## Por qué no se usa una lista de firmas de contenedor
 *
 * Una revisión anterior intentaba desambiguar con una sonda de cabecera: si los bytes no coincidían
 * con ninguna firma conocida, el fallo se tipaba como [SourceOpenFailure.INVALID_VIDEO] "porque el
 * contenido no era un vídeo". El razonamiento es inválido: la lista es incompleta (un formato
 * compatible no listado produciría exactamente la misma evidencia), el fallo de
 * `initMediaExtractor` no habla de los bytes y una cabecera no reconocida tampoco descarta una E/S o
 * un extractor no disponible. Por eso no queda sonda de cabecera: no hay evidencia local que
 * convierta un `IOException` del extractor en un veredicto sobre el contenido.
 *
 * ## Contrato verificable
 *
 * - **Longitud del snapshot** distinta de la sellada: fallo de almacenamiento, nunca de contenido
 *   ([SourceIndexFailurePolicy.integrityIssue]).
 * - **Autorización**: la denegación del proveedor se tipa en la apertura, antes de este punto
 *   ([SourceOpenFailure.PERMISSION_DENIED]).
 * - **Ausencia de track de vídeo** declarada por el extractor (`IllegalArgumentException` con
 *   mensaje de track): los bytes ya se entregaron y el extractor afirma que no hay pista de vídeo
 *   utilizable, así que sí es un veredicto sobre el contenido ([SourceOpenFailure.INVALID_VIDEO]).
 * - **Límites del índice** declarados por el propio indexador ([SourceOpenFailure.SOURCE_TOO_LARGE]).
 * - **Cualquier otro fallo**, `IOException` incluida: ambiguo. Se devuelve
 *   [SourceOpenFailure.SOURCE_UNAVAILABLE] con la clase y el mensaje reales del error y una
 *   declaración explícita de que no se atribuye al contenido ni al formato.
 *
 * Ninguna de estas ramas entrega píxeles: sirven para elegir el fallo tipado, no para capturar.
 */
internal data class IndexFailureVerdict(
    val failure: SourceOpenFailure,
    val detail: String,
)

/**
 * Política de tipado de un fallo del indexador. Pura y probada en JVM: decide si un fallo se puede
 * atribuir al contenido, a un límite del índice o a nada demostrable (ambiguo).
 */
internal object SourceIndexFailurePolicy {

    /**
     * Declaración neutral del caso ambiguo: enumera las causas que Android no permite separar
     * (contenido no soportado o inválido, fallo del extractor, E/S) y declara que no se atribuye el
     * fallo al contenido ni al formato. No afirma ninguno de esos diagnósticos.
     */
    private const val AMBIGUOUS_NOTE =
        "Android no permite distinguir un contenido no soportado o inválido de un fallo del " +
            "extractor o de E/S: no se atribuye al contenido ni al formato."

    /**
     * Comprueba que el snapshot conserva la longitud sellada antes de indexarlo.
     *
     * Devuelve el detalle del problema si hay discrepancia (y entonces el fallo es de almacenamiento,
     * nunca de contenido) o `null` si coincide.
     */
    fun integrityIssue(expectedLengthBytes: Long, actualLengthBytes: Long): String? {
        if (expectedLengthBytes == actualLengthBytes) return null
        return "El snapshot sellado mide $actualLengthBytes bytes y se selló con $expectedLengthBytes: " +
            "la escritura en caché no se conservó íntegra (fallo de almacenamiento, no del contenido)."
    }

    /**
     * Tipa [error] lanzado por el indexador (o por el productor de índice) sobre bytes ya entregados.
     *
     * Sólo dos familias son un veredicto demostrable sobre el contenido: la ausencia de track de
     * vídeo declarada por el extractor y los límites del índice. Cualquier otra excepción —incluida
     * toda `IOException` del extractor— es ambigua y se queda en
     * [SourceOpenFailure.SOURCE_UNAVAILABLE] sin acusar al contenido ni al formato.
     */
    fun verdict(error: Throwable): IndexFailureVerdict {
        val evidence = "${error.javaClass.simpleName}: ${error.message ?: "sin mensaje"}"
        if (error is IllegalArgumentException) {
            val message = error.message
            // `VideoFrameIndexer` señala la ausencia de track con `require`; es un veredicto sobre
            // los bytes entregados al extractor, no sobre el tamaño de la fuente.
            if (message?.contains("track", ignoreCase = true) == true) {
                return IndexFailureVerdict(
                    SourceOpenFailure.INVALID_VIDEO,
                    "El extractor recibió los bytes y no encontró un track de vídeo utilizable ($evidence).",
                )
            }
            if (isLimitMessage(message)) {
                return IndexFailureVerdict(
                    SourceOpenFailure.SOURCE_TOO_LARGE,
                    "El contenido supera un límite del índice ($evidence).",
                )
            }
        }
        return IndexFailureVerdict(
            SourceOpenFailure.SOURCE_UNAVAILABLE,
            "El indexador falló con $evidence. $AMBIGUOUS_NOTE",
        )
    }

    private fun isLimitMessage(message: String?): Boolean {
        if (message == null) return false
        return message.contains("limit", ignoreCase = true) ||
            message.contains("límite", ignoreCase = true) ||
            message.contains("limite", ignoreCase = true)
    }
}
