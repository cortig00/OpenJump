package com.openjump.app.ui.angles

import androidx.compose.runtime.Immutable
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2StoredAggregate
import com.openjump.app.data.angles.AngleImageIntegrity

/**
 * Vista de SOLO LECTURA de un análisis de ángulos multi-fotograma guardado.
 *
 * Es deliberadamente independiente de la familia legacy (`AngleSavedDetail*` lee
 * `angle_analyses`): `angle_analyses.id` y `angle_analysis_v2_roots.id` son secuencias
 * independientes y pueden coincidir numéricamente, así que la identidad incluye la TABLA y este
 * detalle sólo acepta ids de `angle_analysis_v2_roots` (ver [AngleV2SavedDetailState]).
 *
 * El resultado guardado es inmutable: esta capa no ofrece editar, añadir fotogramas ni borrar, no
 * abre ni requiere el vídeo original y no escribe nada en Room. Los datos que muestra son los
 * persistidos (todos los fotogramas en su orden estable, sus marcas y sus mediciones recalculadas con
 * la geometría vigente); la imagen anotada de cada fotograma se publica aparte ([AngleV2SavedImage])
 * porque su verificación es E/S, no datos, y compartirla sólo publica una copia verificada
 * ([AngleV2FrameShareResult]) sin mutar la fila ni el fichero del almacén.
 */
sealed interface AngleV2SavedDetailState {

    /** Lectura en curso; no se muestra ningún dato parcial. */
    data object Loading : AngleV2SavedDetailState

    /** No existe una raíz V2 con ese id (borrada, restaurada o id de otra tabla). */
    data object Missing : AngleV2SavedDetailState

    /** La lectura falló; los datos siguen guardados y se puede reintentar. */
    data object Error : AngleV2SavedDetailState

    /** Foto consistente de la raíz y de todos sus fotogramas con sus marcas. */
    data class Loaded(val content: AngleV2SavedDetailContent) : AngleV2SavedDetailState
}

/**
 * Identidad de PRESENTACIÓN del atleta dueño de una raíz V2: nombre y clave de avatar juntos.
 *
 * Es un tipo sólo de UI: no es una entidad, no se escribe y no tiene relación con el atleta
 * seleccionado global. Existe para que el nombre y el avatar se resuelvan en UNA sola consulta y se
 * publiquen en la MISMA foto de contenido; [name] y [avatarKey] son `null` cuando no hay dato
 * utilizable, no cuando el atleta no existe.
 */
data class AngleV2SavedAthleteIdentity(val name: String?, val avatarKey: String?)

/** Contenido completo del detalle V2: raíz + fotogramas ordenados + marcas de cada fotograma. */
@Immutable
data class AngleV2SavedDetailContent(
    val rootId: Long,
    val createdAt: Long,
    val athleteId: Long?,
    val frames: List<AngleV2SavedDetailFrame>,
    /**
     * Nombre legible del atleta **dueño de esta raíz** ([athleteId]), resuelto por lectura y nunca
     * por el atleta seleccionado global. `null` significa "no disponible" (sin dueño, nombre en
     * blanco o lectura fallida), no "sin atleta": la pantalla no debe inventar un nombre.
     *
     * Es una propiedad de presentación con valor por defecto para no romper a los llamantes que ya
     * construían el contenido sin ella; no forma parte de la identidad persistida.
     */
    val athleteName: String? = null,
    /**
     * Clave del avatar propio del atleta dueño de esta raíz ([athleteId]), leída de la MISMA fila de
     * atleta que [athleteName] y en la misma foto de lectura. `null` significa "sin avatar o no
     * disponible": la pantalla usa el respaldo por iniciales del componente existente, nunca el
     * icono genérico ni el avatar del atleta seleccionado global.
     *
     * Propiedad de presentación con valor por defecto; no se persiste ni cambia la identidad del
     * contenido guardado.
     */
    val athleteAvatarKey: String? = null,
) {
    /** Marcas totales de la raíz (todos los fotogramas), el mismo conteo que muestra HISTORY. */
    val markCount: Int get() = frames.sumOf { it.marks.size }

    /** Marcas visibles de la raíz; el resto se listan igual, marcadas como ocultas. */
    val visibleMarkCount: Int get() = frames.sumOf { it.marks.count(AngleSavedDetailMark::visible) }

    /** Todos los ángulos guardados, en orden de fotograma y de marca. */
    val marks: List<AngleSavedDetailMark> get() = frames.flatMap(AngleV2SavedDetailFrame::marks)
}

/**
 * Fotograma guardado proyectado para consulta.
 *
 * [imageWidth]/[imageHeight]/[imageByteCount]/[imageSha256]/[imageMimeType] son la integridad
 * DECLARADA por la fila: son lo que permite comprobar que el PNG del almacén sigue siendo el que se
 * guardó ([integrity]) sin abrir el vídeo. Si no coinciden, el detalle informa de imagen no
 * disponible y conserva las mediciones.
 *
 * [assignedFrameIndex] es el índice que el editor pidió capturar y es el que se muestra (base uno,
 * igual que el editor y el detalle legacy). [reportedFrameIndex] sólo se expone para poder avisar de
 * una diferencia **verificada** por la extracción; `null` significa "no verificado", no "el mismo".
 */
data class AngleV2SavedDetailFrame(
    val ordinal: Int,
    val frameId: Long,
    val assignedFrameIndex: Int,
    val reportedFrameIndex: Int?,
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val imageKey: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageByteCount: Long,
    val imageSha256: String,
    val imageMimeType: String,
    val marks: List<AngleSavedDetailMark>,
) {
    /** Integridad esperada del PNG: tamaño + SHA-256 declarados por la fila. */
    val integrity: AngleImageIntegrity get() = AngleImageIntegrity(imageByteCount, imageSha256)

    /** Número de fotograma que ve el usuario (base uno), igual que el resto de la app. */
    val displayFrameNumber: Int get() = angleSavedFrameDisplayNumber(assignedFrameIndex)
}

/**
 * Proyecta la instantánea transaccional de Room al estado de la pantalla.
 *
 * `null` (no existe esa raíz V2) es [AngleV2SavedDetailState.Missing]; una raíz sin fotogramas no
 * debería existir, pero se proyecta igualmente sin inventar contenido: la pantalla muestra el aviso
 * de lista vacía en vez de un error falso.
 */
fun angleV2SavedDetailState(
    aggregate: AngleAnalysisV2StoredAggregate?,
): AngleV2SavedDetailState {
    if (aggregate == null) return AngleV2SavedDetailState.Missing
    val frames = aggregate.frames
        .sortedWith(compareBy({ it.frame.ordinal }, { it.frame.id }))
        .map { stored ->
            val frame = stored.frame
            AngleV2SavedDetailFrame(
                ordinal = frame.ordinal,
                frameId = frame.id,
                assignedFrameIndex = frame.assignedFrameIndex,
                reportedFrameIndex = frame.reportedFrameIndex,
                presentationWidth = frame.presentationWidth,
                presentationHeight = frame.presentationHeight,
                rotationDegrees = frame.rotationDegrees,
                imageKey = frame.imageKey,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                imageByteCount = frame.imageByteCount,
                imageSha256 = frame.imageSha256,
                imageMimeType = frame.imageMimeType,
                marks = stored.marks
                    .sortedWith(compareBy<AngleAnalysisV2MarkEntity>({ it.ordinal }, { it.id }))
                    .map(::angleV2SavedMark),
            )
        }
    return AngleV2SavedDetailState.Loaded(
        AngleV2SavedDetailContent(
            rootId = aggregate.root.id,
            createdAt = aggregate.root.createdAt,
            athleteId = aggregate.root.athleteId,
            frames = frames,
        ),
    )
}

/**
 * Marca V2 → misma forma de lectura que la marca legacy ([AngleSavedDetailMark]): la pantalla no
 * necesita dos modelos para pintar nombre, visibilidad, puntos y grados.
 */
internal fun angleV2SavedMark(mark: AngleAnalysisV2MarkEntity): AngleSavedDetailMark =
    angleSavedMark(
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
 * Identidad de la petición de imagen de un fotograma guardado dentro de una raíz concreta.
 *
 * Es la identidad de la PETICIÓN de UI: no es autoridad del almacén ni clave de persistencia, y no
 * contiene rutas, URI ni bytes. [imageByteCount] + [imageSha256] son la integridad DECLARADA por la
 * fila y [imageWidth]/[imageHeight]/[imageMimeType] su forma declarada; junto con [rootId],
 * [frameId] e [imageKey] permiten distinguir una imagen preparada para otro fotograma, otra raíz u
 * otra integridad sin abrir el fichero. La raíz forma parte de la identidad porque un `frameId` o
 * una `imageKey` repetidos no deben retargetear la imagen de otra raíz.
 */
data class AngleV2SavedImageRequest(
    val rootId: Long,
    val frameId: Long,
    val imageKey: String,
    val imageByteCount: Long,
    val imageSha256: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageMimeType: String,
)

/**
 * Identidad de petición de imagen de este fotograma dentro de [rootId].
 *
 * Toma los metadatos **exactos** declarados por el fotograma cargado: no normaliza, no deriva y no
 * completa nada desde otra fuente. Ver [AngleV2SavedImageRequest].
 */
fun AngleV2SavedDetailFrame.imageRequest(rootId: Long): AngleV2SavedImageRequest =
    AngleV2SavedImageRequest(
        rootId = rootId,
        frameId = frameId,
        imageKey = imageKey,
        imageByteCount = imageByteCount,
        imageSha256 = imageSha256,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageMimeType = imageMimeType,
    )

/**
 * Contraste OBSERVADO entre el índice que el editor asignó y el que la extracción reportó.
 *
 * [UNVERIFIED] no significa "coincide": significa que la fila no reportó índice, así que no hay
 * nada que contrastar. [MISMATCH] es una diferencia verificada y debe poder avisarse junto a la
 * imagen, sin cambiar el número que se muestra.
 */
enum class AngleV2FrameExtractionObservation {
    MATCH,
    UNVERIFIED,
    MISMATCH,
}

/**
 * Observación de extracción de un fotograma guardado.
 *
 * Compara el índice reportado con el asignado; **no** es una comparación de PTS ni de tiempo: la
 * proyección de presentación de este detalle no expone los timestamps, así que aquí no se puede
 * afirmar nada sobre ellos.
 */
fun angleV2FrameExtractionObservation(
    assignedFrameIndex: Int,
    reportedFrameIndex: Int?,
): AngleV2FrameExtractionObservation = when {
    reportedFrameIndex == null -> AngleV2FrameExtractionObservation.UNVERIFIED
    reportedFrameIndex == assignedFrameIndex -> AngleV2FrameExtractionObservation.MATCH
    else -> AngleV2FrameExtractionObservation.MISMATCH
}

/** Observación de extracción de este fotograma; ver [angleV2FrameExtractionObservation]. */
val AngleV2SavedDetailFrame.extractionObservation: AngleV2FrameExtractionObservation
    get() = angleV2FrameExtractionObservation(assignedFrameIndex, reportedFrameIndex)

/**
 * Presupuesto de decodificación de una imagen ya verificada (tamaño + SHA-256).
 *
 * Un PNG guardado puede tener hasta 12 MiB y dimensiones muy grandes; decodificarlo a resolución
 * completa para una miniatura de consulta agotaría la memoria. Estos topes acotan el bitmap
 * resultante y NO cambian el archivo: se muestrea al decodificar (`inSampleSize`).
 */
object AngleV2SavedImageBudget {

    /** Lado máximo del bitmap mostrado, en píxeles. */
    const val MAX_DIMENSION: Int = 1280

    /** Píxeles máximos del bitmap mostrado (ARGB_8888 ≈ 4 B/píxel → ≤ ~5,6 MiB por fotograma visible). */
    const val MAX_PIXELS: Long = 1_400_000L

    /** Tope defensivo del muestreo: más allá se considera que la imagen no es mostrabe. */
    const val MAX_SAMPLE_SIZE: Int = 4096
}

/**
 * `inSampleSize` (potencia de dos) que cabe en [AngleV2SavedImageBudget], o `null` si ni con el
 * muestreo máximo se puede acotar (dimensiones absurdas o inválidas).
 *
 * Es una función pura y determinista: la política se puede verificar sin decodificar ni tocar disco.
 * Se calcula con las dimensiones REALES leídas de la cabecera de la imagen, no con las declaradas
 * por la fila, para que un documento no confiable no pueda forzar una decodificación gigante.
 */
fun angleV2ImageSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int = AngleV2SavedImageBudget.MAX_DIMENSION,
    maxPixels: Long = AngleV2SavedImageBudget.MAX_PIXELS,
    maxSampleSize: Int = AngleV2SavedImageBudget.MAX_SAMPLE_SIZE,
): Int? {
    require(maxDimension > 0) { "El lado máximo debe ser positivo, era $maxDimension." }
    require(maxPixels > 0L) { "El máximo de píxeles debe ser positivo, era $maxPixels." }
    require(maxSampleSize >= 1) { "El muestreo máximo debe ser positivo, era $maxSampleSize." }
    if (width <= 0 || height <= 0) return null
    var sample = 1
    while (sample <= maxSampleSize / 2 && !fits(width, height, sample, maxDimension, maxPixels)) {
        sample *= 2
    }
    return if (fits(width, height, sample, maxDimension, maxPixels)) sample else null
}

private fun fits(width: Int, height: Int, sample: Int, maxDimension: Int, maxPixels: Long): Boolean {
    val scaledWidth = width / sample
    val scaledHeight = height / sample
    if (scaledWidth <= 0 || scaledHeight <= 0) return false
    if (scaledWidth > maxDimension || scaledHeight > maxDimension) return false
    return scaledWidth.toLong() * scaledHeight.toLong() <= maxPixels
}
