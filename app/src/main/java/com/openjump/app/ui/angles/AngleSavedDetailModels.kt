package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleGeometry
import com.openjump.app.angles.AngleInvalidReason
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.AngleAnalysisSnapshot
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.ui.history.HistoryDeleteFamily
import com.openjump.app.ui.history.HistoryDeleteTarget
import java.text.NumberFormat
import java.util.Locale

/**
 * Vista de SOLO LECTURA de un análisis de ángulos guardado, independiente del vídeo.
 *
 * El detalle existe para que un resultado persistido siga siendo consultable cuando su vídeo ya no
 * se puede abrir (URI ausente tras una restauración, permiso revocado, fichero movido) o cuando el
 * usuario quiere revisar las cifras sin cargar el reproductor. Por eso:
 *
 * - la fuente es la MISMA raíz transaccional (`angle_analyses` + `angle_marks`) que usa el editor,
 *   leída con `AngleRepository.snapshot`; no hay datastore, caché ni export paralelo;
 * - el valor derivado se recalcula desde los puntos guardados con la geometría vigente, igual que
 *   el editor, y NUNCA se persiste aquí;
 * - esta capa no dibuja geometría sobre ningún vídeo: si el vídeo no es válido no hay editor, y
 *   abrir el editor es una acción explícita que reutiliza la ruta y el ViewModel existentes.
 *
 * La pantalla es Kotlin puro: los tests JVM pueden verificar la política sin instrumentación.
 */
sealed interface AngleSavedDetailState {

    /** Lectura en curso; no se muestra ningún dato parcial. */
    data object Loading : AngleSavedDetailState

    /** El análisis no existe (borrado en otra pantalla o en otro proceso). */
    data object Missing : AngleSavedDetailState

    /** La lectura falló; los datos siguen guardados y se puede reintentar. */
    data object Error : AngleSavedDetailState

    /** Foto consistente de la raíz y sus marcas. */
    data class Loaded(val content: AngleSavedDetailContent) : AngleSavedDetailState
}

/** Marca guardada proyectada para lectura: nombre, visibilidad, puntos y medición derivada. */
data class AngleSavedDetailMark(
    val id: Long,
    val ordinal: Int,
    val name: String,
    val visible: Boolean,
    val pointA: AnglePoint,
    val pointB: AnglePoint,
    val pointC: AnglePoint,
    val measurement: AngleMeasurement,
) {
    /** Grados sin redondear, o `null` si el ángulo guardado no es medible. */
    val degrees: Double? get() = (measurement as? AngleMeasurement.Measured)?.degrees

    /** Motivo tipado de "no medible"; se muestra en vez de un 0° falso. */
    val invalidReason: AngleInvalidReason?
        get() = (measurement as? AngleMeasurement.Invalid)?.reason
}

/**
 * Contenido completo del detalle guardado.
 *
 * [videoUri] y [athleteId] son los metadatos persistidos de la raíz. NO habilitan ninguna acción:
 * por el contrato D2 un análisis legacy sin imagen anotada se consulta y no reabre ni edita sobre el
 * vídeo, así que el detalle muestra un aviso de "imagen no disponible" en vez de un botón de
 * edición. Se proyectan igualmente para que la vista pueda explicar de qué análisis se trata sin
 * inventar datos.
 */
data class AngleSavedDetailContent(
    val analysisId: Long,
    val createdAt: Long,
    val frameIndex: Int,
    val sourcePtsUs: Long,
    val rotationDegrees: Int,
    val videoUri: String?,
    val athleteId: Long?,
    val marks: List<AngleSavedDetailMark>,
) {
    val visibleMarkCount: Int get() = marks.count { it.visible }

    /**
     * `true` si la raíz persistida conserva URI de vídeo no vacía y dueño válido.
     *
     * Es un dato PERSISTIDO (describe el análisis guardado), no una acción: por el contrato D2 este
     * detalle ya no ofrece editar ni reabrir el vídeo, así que la propiedad no habilita ningún
     * botón. Se mantiene porque la vista la usa para explicar el estado del análisis y porque
     * resume una condición que el editor exige; no debe volver a convertirse en una vía de
     * navegación sin una decisión de producto explícita.
     */
    val canEditWithVideo: Boolean get() = !videoUri.isNullOrBlank() && (athleteId ?: 0L) > 0L
}

/**
 * Proyecta la foto de Room al estado de la pantalla.
 *
 * `null` significa "no existe" ([AngleSavedDetailState.Missing]); un snapshot con cero marcas es un
 * análisis válido sin ángulos (la raíz se guarda con al menos una marca, pero el detalle no debe
 * inventar datos si la regla cambia: se muestra la lista vacía).
 */
fun angleSavedDetailState(snapshot: AngleAnalysisSnapshot?): AngleSavedDetailState =
    if (snapshot == null) {
        AngleSavedDetailState.Missing
    } else {
        AngleSavedDetailState.Loaded(
            AngleSavedDetailContent(
                analysisId = snapshot.analysis.id,
                createdAt = snapshot.analysis.createdAt,
                frameIndex = snapshot.analysis.frameIndex,
                sourcePtsUs = snapshot.analysis.sourcePtsUs,
                rotationDegrees = snapshot.analysis.rotationDegrees,
                videoUri = snapshot.analysis.videoUri,
                athleteId = snapshot.analysis.athleteId,
                marks = snapshot.marks
                    .sortedWith(compareBy<AngleMarkEntity> { it.ordinal }.thenBy { it.id })
                    .map(::savedMark),
            ),
        )
    }

private fun savedMark(mark: AngleMarkEntity): AngleSavedDetailMark = angleSavedMark(
    id = mark.id,
    ordinal = mark.ordinal,
    name = mark.name,
    visible = mark.visible,
    pointAX = mark.pointAX,
    pointAY = mark.pointAY,
    pointBX = mark.pointBX,
    pointBY = mark.pointBY,
    pointCX = mark.pointCX,
    pointCY = mark.pointCY,
)

/**
 * Construye la marca de lectura desde columnas sueltas: lo comparten la familia legacy
 * (`angle_marks`) y la multi-fotograma (`angle_analysis_v2_marks`), que miden igual.
 */
internal fun angleSavedMark(
    id: Long,
    ordinal: Int,
    name: String,
    visible: Boolean,
    pointAX: Double,
    pointAY: Double,
    pointBX: Double,
    pointBY: Double,
    pointCX: Double,
    pointCY: Double,
): AngleSavedDetailMark {
    val a = AnglePoint(pointAX, pointAY)
    val b = AnglePoint(pointBX, pointBY)
    val c = AnglePoint(pointCX, pointCY)
    return AngleSavedDetailMark(
        id = id,
        ordinal = ordinal,
        name = name,
        visible = visible,
        pointA = a,
        pointB = b,
        pointC = c,
        measurement = AngleGeometry.measure(a, b, c),
    )
}

/**
 * Objetivo de borrado del detalle: la MISMA familia + id que usa HISTORY.
 *
 * El detalle no abre un camino de borrado nuevo: entrega el objetivo a la confirmación existente y
 * el borrado lo ejecuta `AngleRepository.delete`, que arrastra las marcas por `ON DELETE CASCADE` y
 * no toca el fichero de vídeo.
 */
fun angleSavedDeleteTarget(analysisId: Long): HistoryDeleteTarget {
    require(analysisId > 0L) { "El borrado de un análisis exige un id guardado, era $analysisId." }
    return HistoryDeleteTarget(HistoryDeleteFamily.ANGLE, analysisId)
}

/** Grados con un decimal en el locale de la app, igual que el editor. */
fun formatAngleSavedDegrees(degrees: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        format(degrees)
    }

/** Coordenada de un punto guardado en píxeles de presentación, sin decimales. */
fun formatAngleSavedPoint(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = 0
        format(value)
    }
