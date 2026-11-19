package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement

/**
 * Fuente persistida de una fila de la familia Ángulos.
 *
 * `angle_analyses` (una raíz por fotograma) y `angle_analysis_v2_roots` (una raíz multi-fotograma)
 * son tablas DISTINTAS con secuencias de `id` independientes: los números pueden coincidir y un
 * `Long` suelto no identifica una fila. Esta fuente forma parte de la identidad de la fila junto al
 * `id`, y es lo que mantiene únicos la clave de lista, el `kind` del cursor keyset y el destino de
 * navegación de cada familia.
 */
enum class AngleHistorySource {
    /** Raíz legacy de `angle_analyses`: puede tener vídeo y se consulta en el detalle legacy. */
    LEGACY,

    /** Raíz multi-fotograma de `angle_analysis_v2_roots`: sin vídeo y con imágenes privadas. */
    MULTI_FRAME,
}

/**
 * Fila de la familia Ángulos en HISTORY: un análisis guardado, sin valores derivados.
 *
 * [markCount] es el número de marcas persistidas de la raíz (lo que HISTORY muestra como número de
 * medidas) y [videoUri] es la única vía para reabrir el análisis en el editor, así que ambos se
 * proyectan desde la raíz y no se recalculan en la UI. En una raíz [AngleHistorySource.MULTI_FRAME]
 * el vídeo no se persiste por diseño (el vínculo con el medio son las imágenes privadas de sus
 * fotogramas), así que [videoUri] es siempre `null`; [markCount] cuenta las marcas de TODOS sus
 * fotogramas.
 *
 * La fila NO decide si se puede consultar: la navegación va siempre al detalle guardado de su propia
 * fuente, que lee la raíz sin abrir vídeo. Una URI ausente o sin permiso (por ejemplo tras restaurar
 * una copia) ya no bloquea el acceso al resultado; sólo limita la acción de editar, que el detalle
 * resuelve con sus propias comprobaciones.
 *
 * [source] tiene valor por defecto `LEGACY` porque todas las filas anteriores a la integración V2
 * son, literalmente, raíces legacy: el valor por defecto no inventa una fuente, la declara.
 */
data class AngleAnalysisHistoryEntry(
    val id: Long,
    val dateTime: Long,
    val athleteId: Long?,
    val athleteName: String?,
    val markCount: Int,
    val videoUri: String?,
    val source: AngleHistorySource = AngleHistorySource.LEGACY,
    /**
     * Generación de restauración ([com.openjump.app.data.angles.AngleMediaRestoreEpoch]) que estaba
     * vigente cuando la MISMA sección crítica que leyó esta fila la obtuvo, o `null` si la fila no
     * se leyó con esa exclusión (toda fila legacy).
     *
     * Es la autoridad mínima de un borrado V2: la prueba exacta de la raíz se prepara con este
     * token, así que una restauración posterior la invalida (`Stale`) en vez de borrar la raíz que
     * ocupó el mismo número. `null` en una fila multi-fotograma significa «observación sin
     * generación» y no habilita ningún borrado: no se rellena a posteriori ni se serializa.
     */
    val restoreToken: Long? = null,
)

sealed interface MeasurementHistoryItem {
    val id: Long
    val dateTime: Long

    data class Jump(val measurement: RecentMeasurement) : MeasurementHistoryItem {
        override val id: Long = measurement.id
        override val dateTime: Long = measurement.dateTime
    }

    data class Encoder(val session: RecentEncoderSession) : MeasurementHistoryItem {
        override val id: Long = session.id
        override val dateTime: Long = session.dateTime
    }

    data class Angle(val entry: AngleAnalysisHistoryEntry) : MeasurementHistoryItem {
        override val id: Long = entry.id
        override val dateTime: Long = entry.dateTime
    }
}

fun mergeMeasurementHistory(
    jumps: List<RecentMeasurement>,
    encoderSessions: List<RecentEncoderSession>,
    limit: Int? = null,
    angleAnalyses: List<AngleAnalysisHistoryEntry> = emptyList(),
): List<MeasurementHistoryItem> {
    require(limit == null || limit >= 0)
    val merged = (jumps.map(MeasurementHistoryItem::Jump) +
        encoderSessions.map(MeasurementHistoryItem::Encoder) +
        angleAnalyses.map(MeasurementHistoryItem::Angle))
        .sortedWith(
            compareByDescending<MeasurementHistoryItem> { it.dateTime }
                .thenByDescending { it.id }
                .thenBy { it.kindOrder() },
        )
    return limit?.let(merged::take) ?: merged
}

/**
 * Orden de desempate entre familias cuando `dateTime` e `id` coinciden.
 *
 * Es un orden EXPLÍCITO y no alfabético: el alfabético dejaría `ANGLE` antes que `ENCODER` y
 * rompería la frontera del cursor keyset de `EncoderDao.historyPage` (que sólo puede emitir
 * `id < :cursorId`).
 *
 * Las dos fuentes de Ángulos van al final y en este orden: `ANGLE` (legacy) y después `ANGLE_V2`.
 * Así los predicados de saltos y encoder siguen siendo correctos sin tocarlos, y el de la consulta
 * V2 es el único que necesita `id = :cursorId` para las otras tres familias. Un `id` igual en
 * `angle_analyses` y en `angle_analysis_v2_roots` queda resuelto por este orden, nunca por el número.
 */
private fun MeasurementHistoryItem.kindOrder(): Int = when (this) {
    is MeasurementHistoryItem.Encoder -> 0
    is MeasurementHistoryItem.Jump -> 1
    is MeasurementHistoryItem.Angle -> when (entry.source) {
        AngleHistorySource.LEGACY -> 2
        AngleHistorySource.MULTI_FRAME -> 3
    }
}

/**
 * Clave de fuente persistida, la misma que viaja en `AthleteHistoryCursor.kind`.
 *
 * `'ANGLE'` y `'ANGLE_V2'` son posiciones DISTINTAS del orden global aunque compartan `id`: el
 * cursor de una no puede consumir la página de la otra. Estos literales son el contrato con las
 * fronteras SQL de `JumpDao.historyPage`, `JumpDao.angleHistoryPage` y `JumpDao.angleV2HistoryPage`:
 * se cambian juntos.
 */
internal fun MeasurementHistoryItem.kindKey(): String = when (this) {
    is MeasurementHistoryItem.Jump -> "JUMP"
    is MeasurementHistoryItem.Encoder -> "ENCODER"
    is MeasurementHistoryItem.Angle -> when (entry.source) {
        AngleHistorySource.LEGACY -> "ANGLE"
        AngleHistorySource.MULTI_FRAME -> "ANGLE_V2"
    }
}

/**
 * Clave estable de fila para `LazyColumn`.
 *
 * La fuente forma parte de la clave: `angle-7` y `angle-v2-7` son filas distintas de tablas distintas
 * y pueden coexistir en la misma página. Una clave por `id` a secas provocaría claves duplicadas y
 * `IllegalArgumentException` en cuanto ambas familias tuvieran filas numeradas igual.
 */
internal fun MeasurementHistoryItem.historyRowKey(): String = when (this) {
    is MeasurementHistoryItem.Jump -> "jump-$id"
    is MeasurementHistoryItem.Encoder -> "encoder-$id"
    is MeasurementHistoryItem.Angle -> when (entry.source) {
        AngleHistorySource.LEGACY -> "angle-$id"
        AngleHistorySource.MULTI_FRAME -> "angle-v2-$id"
    }
}
