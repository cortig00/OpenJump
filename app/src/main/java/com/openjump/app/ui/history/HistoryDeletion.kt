package com.openjump.app.ui.history

import java.util.Locale

/**
 * Familia de raíz persistida que HISTORY puede borrar.
 *
 * Un `id` de `angle_analyses`, uno de `encoder_sessions` y uno de `angle_analysis_v2_roots` son
 * espacios distintos y pueden coincidir numéricamente, así que ninguna operación de borrado
 * interpreta un `Long` suelto: el objetivo viaja siempre con su familia.
 *
 * `ANGLE` significa SIEMPRE y SÓLO una raíz legacy de `angle_analyses`. `ANGLE_V2` es la familia
 * multi-fotograma (`angle_analysis_v2_roots`), con su propia transacción de borrado y su propia
 * reclamación de imágenes privadas: NUNCA se ejecuta con el borrado legacy por id. Las dos
 * familias de ángulos son objetivos independientes aunque compartan número, y el guard de doble
 * toque las trata por separado.
 */
enum class HistoryDeleteFamily { ENCODER, ANGLE, ANGLE_V2 }

/**
 * ¿Puede esta familia ejecutarse por el camino de borrado **legacy** (encoder/ángulo por id)?
 *
 * Es el guard explícito de [HistoryViewModel.deleteRow]: `ANGLE_V2` sólo se borra con una prueba
 * exacta preparada (`AngleV2DeleteProof`) y por el dueño de aplicación, así que el camino genérico
 * falla cerrado en vez de reinterpretar el id contra otra tabla.
 */
internal fun historyLegacyDeleteSupports(family: HistoryDeleteFamily): Boolean =
    family != HistoryDeleteFamily.ANGLE_V2

/**
 * Objetivo de un borrado confirmado desde HISTORY: familia + id de la raíz.
 *
 * [key] es estable y serializable para recordar la confirmación pendiente tras recrear la
 * actividad sin guardar un id ambiguo entre familias.
 *
 * Sólo las familias legacy ([HistoryDeleteFamily.ENCODER], [HistoryDeleteFamily.ANGLE]) se
 * reconstruyen desde [key]: la confirmación legacy no tiene más autoridad que (familia, id). Un
 * objetivo [HistoryDeleteFamily.ANGLE_V2] **nunca** se reconstruye a partir de su clave, porque un
 * id guardado no es una prueba de borrado: la autorización V2 es la instantánea efímera que vive
 * en memoria del ViewModel y no se serializa. Sin prueba se pide confirmación nueva.
 */
data class HistoryDeleteTarget(val family: HistoryDeleteFamily, val id: Long) {

    val key: String get() = "${family.name.lowercase(Locale.ROOT)}-$id"

    companion object {
        /** Reconstruye un objetivo guardado; `null` si la clave no es reconocible. */
        fun fromKey(key: String): HistoryDeleteTarget? {
            val separator = key.indexOf('-')
            if (separator <= 0 || separator == key.lastIndex) return null
            val family = when (key.substring(0, separator)) {
                "encoder" -> HistoryDeleteFamily.ENCODER
                "angle" -> HistoryDeleteFamily.ANGLE
                // `angle_v2-7` NO se acepta: rehidratar un id V2 convertiría un texto guardado en
                // una autorización de borrado de una raíz que quizá ya es otra. Falla cerrado.
                else -> null
            } ?: return null
            val id = key.substring(separator + 1).toLongOrNull() ?: return null
            return if (id > 0L) HistoryDeleteTarget(family, id) else null
        }
    }
}

/**
 * Acción de borrado que ofrece una fila de HISTORY, o `null` si esa familia no se borra desde aquí.
 *
 * - Encoder: sólo sesiones sin repeticiones válidas ni MCV ([isDeletableEmptyEncoderRow], F035).
 * - Ángulos legacy: cualquier análisis persistido en `angle_analyses`. Un análisis con vídeo o
 *   atleta no recuperable sigue siendo un dato guardado y es justo el caso en el que el usuario
 *   necesita poder borrarlo.
 * - Ángulos multi-fotograma (V2): SÍ, con su PROPIA familia, y sólo si la fila trae la generación
 *   de restauración observada al leerla ([AngleAnalysisHistoryEntry.restoreToken]). Sin token no
 *   hay autoridad para preparar la prueba exacta y no se ofrece la acción.
 * - Saltos: nunca. Sus mediciones y su vídeo no se borran desde HISTORY.
 */
internal fun historyDeleteTarget(item: MeasurementHistoryItem): HistoryDeleteTarget? = when (item) {
    is MeasurementHistoryItem.Encoder ->
        if (isDeletableEmptyEncoderRow(item)) {
            HistoryDeleteTarget(HistoryDeleteFamily.ENCODER, item.id)
        } else {
            null
        }

    is MeasurementHistoryItem.Angle -> when (item.entry.source) {
        // Borrar un análisis legacy toca `angle_analyses` por id. Un id V2 nunca llega a esa
        // operación: la fuente decide la familia, así que un número compartido no puede borrar OTRA
        // tabla.
        AngleHistorySource.LEGACY -> HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, item.id)
        // V2: la familia es distinta y el objetivo sólo existe con la generación observada por la
        // misma sección crítica que leyó la página; el executor V2 (prueba + dueño de aplicación)
        // no acepta un id suelto.
        AngleHistorySource.MULTI_FRAME ->
            item.entry.restoreToken?.let { HistoryDeleteTarget(HistoryDeleteFamily.ANGLE_V2, item.id) }
    }

    is MeasurementHistoryItem.Jump -> null
}

/**
 * Guard de borrado **legacy** en vuelo: un segundo toque del mismo objetivo no lanza otro borrado.
 *
 * La familia forma parte de la clave, de modo que dos raíces de familias distintas con el mismo id
 * son objetivos independientes y el borrado de una nunca bloquea ni cancela el de la otra.
 *
 * El borrado V2 confirmado no usa este guard: su exclusión es la prueba en memoria del
 * [HistoryV2DeletionController], que serializa preparar/ejecutar y no tiene clave serializable.
 */
internal class HistoryDeleteGate {
    private val inFlight = LinkedHashSet<HistoryDeleteTarget>()

    /** `true` si el objetivo se registra ahora; `false` si ya tenía un borrado en vuelo. */
    fun begin(target: HistoryDeleteTarget): Boolean = inFlight.add(target)

    fun end(target: HistoryDeleteTarget) {
        inFlight.remove(target)
    }

    fun snapshot(): Set<HistoryDeleteTarget> = inFlight.toSet()
}
