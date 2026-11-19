package com.openjump.app.data.export

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Modelo del agregado de ángulos multi-fotograma (V2) tal y como se exporta.
 *
 * Conectado desde el schema 7: [DataExportDocument.angleV2] publica una lista de
 * [ExportAngleV2Analysis] (omitida del JSON cuando está vacía), `DataExportRepository.write` recorre
 * el agregado en la misma transacción de lectura que el resto de familias y `DataExportStreamWriter`
 * lo emite con `angleV2(...)`; el CSV usa la familia `angleV2` con los tipos `ANGLE_V2_ANALYSIS`,
 * `ANGLE_V2_FRAME` y `ANGLE_V2_MARK` (`AngleV2ExportWriters.kt`). Las raíces V1 siguen viajando en
 * [DataExportDocument.angleAnalyses] sin mezclarse con éstas.
 *
 * Identidad de tipo. Una raíz V2 puede compartir `id` numérico con un `angle_analyses.id`
 * (`AngleAnalysisV2Entities.kt:11-15`), así que el número **no** es la identidad: lo es la
 * colección del documento más el discriminador persistido [ExportAngleV2Analysis.analysisFormat].
 * Ese campo viaja 1:1 con la columna, sin forzarse ni normalizarse: el export declara el formato
 * que encontró, no adivina el que espera.
 *
 * Privacidad. El agregado persistido guarda `sessionKey` (derivado de la URI del vídeo), la clave
 * opaca del almacén de imágenes (`imageKey`), el SHA-256, el tipo MIME y el tamaño del bitmap
 * anotado. **Ninguno de esos datos existe en este modelo**: no hay campo que los represente, así
 * que ningún camino de serialización puede publicarlos. La correlación con el medio se hace por la
 * identidad anónima de la raíz, igual que en el contrato V1 ([ExportAngleVideoReference]).
 *
 * Lo que sí se conserva del agregado real:
 *  - la raíz, con 0 o más fotogramas;
 *  - las marcas reutilizando [ExportAngleMark] (misma identidad, ordinal, nombre, color,
 *    visibilidad, puntos y grados derivados con [ExportAngleMark.measured]);
 *  - la geometría de presentación **de cada fotograma**, que es el sistema de sus marcas;
 *  - los PTS `Long` en microsegundos asignados y reportados **por separado**, con `null` cuando la
 *    extracción no los verificó. El export nunca copia el asignado al reportado.
 */

/**
 * Raíz exportable de un análisis de ángulos multi-fotograma sobre un único vídeo.
 *
 * [id] es la identidad anónima de la raíz (`angle_analysis_v2_roots.id` en texto). [frames] puede
 * estar vacía: una raíz sin fotogramas se exporta fielmente como `[]` en vez de desaparecer.
 *
 * El vídeo no se exporta ni se describe: [video] sólo declara que la correlación se hace por la
 * clave estable de la raíz.
 */
@Serializable
data class ExportAngleV2Analysis(
    /** Identidad anónima de la raíz (`angle_analysis_v2_roots.id` en texto). No contiene la URI. */
    val id: String,
    /**
     * Discriminador de tipo/versión persistido (`angle_analysis_v2_roots.analysisFormat`).
     * Obligatorio y sin valor por defecto: lo declara la fila, no el export.
     */
    val analysisFormat: String,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val revision: Int,
    val athleteId: String? = null,
    val video: ExportAngleVideoReference = ExportAngleVideoReference(),
    /** Fotogramas de la raíz; `[]` es un estado válido y se publica como tal. */
    val frames: List<ExportAngleV2Frame> = emptyList(),
)

/**
 * Fotograma exportable: la unidad que tiene imagen anotada y marcas.
 *
 * **Asignado vs reportado.** [assignedFrameIndex]/[assignedPtsUs] son lo que el editor pidió
 * capturar; [reportedFrameIndex]/[reportedPtsUs] son lo que la extracción reportó para el bitmap
 * anotado. Viajan separados y los reportados pueden ser `null`; el export no fabrica un valor
 * observado a partir del asignado (contrato de captura, `AngleAnalysisV2Entities.kt:82-87`).
 *
 * **Exactitud.** Los PTS son `Long` de microsegundos: `9007199254740993` (2^53+1) no es
 * representable en `Double`, así que convertirlos perdería el entero. Un PTS `null` no se
 * sustituye por `0`.
 *
 * **Geometría.** [encodedWidth]/[encodedHeight], [presentationWidth]/[presentationHeight],
 * [rotationDegrees] y [geometryVersion] son las columnas del fotograma, no de la raíz: dos
 * fotogramas del mismo vídeo pueden haberse presentado con tamaño u orientación distintos y las
 * coordenadas de las marcas sólo son comparables dentro de su fotograma.
 *
 * **Imagen.** No hay campo para la clave del almacén, el SHA-256, el MIME ni el tamaño: el export
 * no publica digests ni identificadores de contenido.
 */
@Serializable
data class ExportAngleV2Frame(
    /** Identidad persistente del fotograma (`angle_analysis_v2_frames.id` en texto). */
    val id: String,
    /** Posición 0..N-1 dentro de la raíz. */
    val ordinal: Int,
    /** Índice de frame solicitado/asignado por el editor. */
    val assignedFrameIndex: Int,
    /** Índice reportado por la extracción; `null` si no se verificó (nunca el asignado). */
    val reportedFrameIndex: Int? = null,
    /** PTS asignado en microsegundos, `Long` exacto. */
    val assignedPtsUs: Long,
    /** PTS reportado en microsegundos, `Long` exacto; `null` si no se verificó. */
    val reportedPtsUs: Long? = null,
    /** Dimensiones tal y como vienen codificadas en el fichero. */
    val encodedWidth: Int,
    val encodedHeight: Int,
    /** Sistema de coordenadas de las marcas de este fotograma (píxeles post-rotación). */
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    /** Versión del modelo geométrico con el que se midieron las coordenadas de este fotograma. */
    val geometryVersion: Int,
    /** Marcas del fotograma; `[]` es un estado válido y se publica como tal. */
    val marks: List<ExportAngleMark> = emptyList(),
)

/**
 * Convierte una marca persistida en marca exportable y recalcula sus grados con
 * [AngleGeometry][com.openjump.app.angles.AngleGeometry] a través de [ExportAngleMark.measured].
 *
 * La tabla no guarda grados, así que el único valor publicado es el derivado de los puntos
 * guardados: un ángulo no medible queda en `null` con un motivo tipado, nunca en un 0° falso.
 */
internal fun AngleAnalysisV2MarkEntity.toExportAngleMark(): ExportAngleMark = ExportAngleMark(
    id = id.toString(),
    ordinal = ordinal,
    name = name,
    visible = visible,
    colorArgb = colorArgb,
    pointAX = pointAX,
    pointAY = pointAY,
    pointBX = pointBX,
    pointBY = pointBY,
    pointCX = pointCX,
    pointCY = pointCY,
).measured()

/**
 * Construye el fotograma exportable a partir de la fila persistida y de las marcas que le
 * pertenecen (en el orden estable de la consulta: `ordinal`, luego `id`).
 *
 * Deliberadamente **no** se copian las columnas de imagen; ver la nota de privacidad del fichero.
 */
internal fun AngleAnalysisV2FrameEntity.toExportAngleV2Frame(
    marks: List<AngleAnalysisV2MarkEntity>,
): ExportAngleV2Frame {
    require(marks.all { it.frameId == id }) {
        "angle V2 mark rows must belong to frame $id"
    }
    return ExportAngleV2Frame(
        id = id.toString(),
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        // Copia literal de la columna: si la extracción no verificó el valor, viaja `null`.
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        marks = marks.map { it.toExportAngleMark() },
    )
}

/**
 * Construye la raíz exportable a partir de la fila persistida de la raíz y de las filas planas de
 * sus fotogramas y marcas (misma forma que entregará la consulta keyset).
 *
 * Falla en cerrado si alguna fila no pertenece a la raíz/fotograma indicado, igual que el
 * ensamblador V1 (`ExportAngleRootRow.toExportAngleAnalysis`): un fotograma de otra raíz produciría
 * un agregado con apariencia válida y contenido ajeno.
 *
 * Ni el `sessionKey` de la raíz ni las columnas de imagen de los fotogramas llegan al modelo.
 */
internal fun AngleAnalysisV2RootEntity.toExportAngleV2Analysis(
    frames: List<AngleAnalysisV2FrameEntity>,
    marks: List<AngleAnalysisV2MarkEntity>,
): ExportAngleV2Analysis {
    require(frames.all { it.rootId == id }) {
        "angle V2 frame rows must belong to root $id"
    }
    val frameIds = frames.map { it.id }.toSet()
    require(marks.all { it.frameId in frameIds }) {
        "angle V2 mark rows must belong to a frame of root $id"
    }
    val marksByFrame = marks.groupBy { it.frameId }
    return ExportAngleV2Analysis(
        id = id.toString(),
        // Copia literal de la columna: declarar el formato persistido, no sustituirlo por la constante.
        analysisFormat = analysisFormat,
        createdAtUtc = Instant.ofEpochMilli(createdAt).toString(),
        updatedAtUtc = Instant.ofEpochMilli(updatedAt).toString(),
        revision = revision,
        athleteId = athleteId?.toString(),
        video = ExportAngleVideoReference(),
        frames = frames.map { frame -> frame.toExportAngleV2Frame(marksByFrame[frame.id].orEmpty()) },
    )
}

/** Aplica [ExportAngleMark.measured] a todas las marcas de todos los fotogramas. */
internal fun ExportAngleV2Analysis.measuredV2Marks(): ExportAngleV2Analysis = copy(
    frames = frames.map { frame -> frame.copy(marks = frame.marks.map { it.measured() }) },
)

/**
 * Forma canónica de una raíz V2: fotogramas por (`ordinal`, `id`), marcas por (`ordinal`, `id`) y
 * grados recalculados desde los puntos guardados.
 *
 * Es **idempotente**, así que el camino canónico y el streaming publican exactamente la misma raíz
 * aunque el llamador entregue los fotogramas o las marcas en otro orden. El orden de marcas es el
 * mismo que usa V1 (`ExportAngleAnalysis.canonicalizedAngle`) y el de fotogramas sigue el orden
 * estable de la raíz, no los índices del vídeo (que son observaciones, no orden).
 */
internal fun ExportAngleV2Analysis.canonicalizedV2Analysis(): ExportAngleV2Analysis = copy(
    frames = frames.sortedWith(compareBy({ it.ordinal }, { it.id })).map { frame ->
        frame.copy(marks = frame.marks.sortedWith(compareBy({ it.ordinal }, { it.id })))
    },
).measuredV2Marks()

/**
 * Forma canónica de una lista de raíces V2: raíces por instante de `createdAt` y luego por `id`
 * (mismo criterio que el keyset `createdAt, id` del resto de familias), cada una canonizada con
 * [canonicalizedV2Analysis].
 *
 * El instante se compara como instante, no como texto ISO: `"...:00Z"` y `"...:00.001Z"` no
 * ordenan lexicográficamente igual que sus milisegundos. Idempotente y ajena al orden de entrada.
 */
internal fun List<ExportAngleV2Analysis>.canonicalizedV2Analyses(): List<ExportAngleV2Analysis> =
    sortedWith(
        compareBy<ExportAngleV2Analysis>(
            { runCatching { Instant.parse(it.createdAtUtc) }.getOrNull() },
            { it.id },
        ),
    ).map { it.canonicalizedV2Analysis() }

/**
 * Valida una raíz V2 exportable. Es el espejo de `DataExportDocument.validated` para el agregado
 * V2: mismas reglas para la raíz y las marcas que V1, adaptadas a que la unicidad de **ordinales e
 * ids de marca es por fotograma** (el índice único de Room es `(frameId, ordinal)`), no por raíz.
 *
 * Diferencias deliberadas con la frontera de escritura (`AngleAggregateRules`), para no rechazar
 * filas que el export debe poder declarar:
 *  - un fotograma puede no tener marcas y una raíz puede no tener fotogramas: se exporta `[]`;
 *  - `geometryVersion` sólo exige `>= 1` (V1 ya exporta así) en vez de exigir la versión vigente;
 *  - la rotación no se restringe a las soportadas ni se re-deriva de las dimensiones: el export
 *    declara la geometría persistida, no la revalida contra el extractor.
 *
 * Falla en cerrado (`IllegalArgumentException`) en vez de publicar un documento ambiguo.
 */
internal fun ExportAngleV2Analysis.validatedV2(): ExportAngleV2Analysis {
    require(id.isNotBlank()) { "angle V2 analysis id must not be blank" }
    require(analysisFormat.isNotBlank()) {
        "angle V2 analysis $id analysisFormat must not be blank"
    }
    require(createdAtUtc.isNotBlank()) {
        "angle V2 analysis $id createdAtUtc must not be blank"
    }
    require(updatedAtUtc.isNotBlank()) {
        "angle V2 analysis $id updatedAtUtc must not be blank"
    }
    require(video.kind.isNotBlank()) {
        "angle V2 analysis $id video kind must not be blank"
    }
    val frameIds = mutableSetOf<String>()
    val frameOrdinals = mutableSetOf<Int>()
    // La identidad de la marca es su id persistente (PK de `angle_analysis_v2_marks`), así que un id
    // repetido en dos fotogramas de la misma raíz también es ambiguo, no sólo dentro de un fotograma.
    val rootMarkIds = mutableSetOf<String>()
    frames.forEach { frame ->
        require(frame.id.isNotBlank()) {
            "angle V2 analysis $id frame id must not be blank"
        }
        require(frameIds.add(frame.id)) {
            "angle V2 analysis $id repeats frame id ${frame.id}"
        }
        require(frame.ordinal >= 0) {
            "angle V2 analysis $id frame ${frame.id} ordinal must be >= 0"
        }
        require(frameOrdinals.add(frame.ordinal)) {
            "angle V2 analysis $id repeats frame ordinal ${frame.ordinal}"
        }
        require(frame.assignedFrameIndex >= 0) {
            "angle V2 analysis $id frame ${frame.id} assignedFrameIndex must be >= 0"
        }
        frame.reportedFrameIndex?.let { reported ->
            require(reported >= 0) {
                "angle V2 analysis $id frame ${frame.id} reportedFrameIndex must be >= 0"
            }
        }
        require(frame.assignedPtsUs >= 0L) {
            "angle V2 analysis $id frame ${frame.id} assignedPtsUs must be >= 0"
        }
        frame.reportedPtsUs?.let { reported ->
            require(reported >= 0L) {
                "angle V2 analysis $id frame ${frame.id} reportedPtsUs must be >= 0"
            }
        }
        require(frame.encodedWidth > 0 && frame.encodedHeight > 0) {
            "angle V2 analysis $id frame ${frame.id} encoded dimensions must be positive"
        }
        require(frame.presentationWidth > 0 && frame.presentationHeight > 0) {
            "angle V2 analysis $id frame ${frame.id} presentation dimensions must be positive"
        }
        require(frame.geometryVersion >= 1) {
            "angle V2 analysis $id frame ${frame.id} geometryVersion must be >= 1"
        }
        val markOrdinals = mutableSetOf<Int>()
        frame.marks.forEach { mark ->
            require(mark.id.isNotBlank()) {
                "angle V2 analysis $id frame ${frame.id} mark id must not be blank"
            }
            require(rootMarkIds.add(mark.id)) {
                "angle V2 analysis $id repeats mark id ${mark.id}"
            }
            require(mark.ordinal >= 0) {
                "angle V2 analysis $id frame ${frame.id} mark ${mark.id} ordinal must be >= 0"
            }
            // El ordinal es único POR FOTOGRAMA: dos fotogramas pueden repetirlo sin ambigüedad.
            require(markOrdinals.add(mark.ordinal)) {
                "angle V2 analysis $id frame ${frame.id} repeats mark ordinal ${mark.ordinal}"
            }
            require(mark.name.isNotBlank()) {
                "angle V2 analysis $id frame ${frame.id} mark ${mark.id} name must not be blank"
            }
            requireFinite(mark.pointAX, "angle V2 mark ${mark.id} pointAX")
            requireFinite(mark.pointAY, "angle V2 mark ${mark.id} pointAY")
            requireFinite(mark.pointBX, "angle V2 mark ${mark.id} pointBX")
            requireFinite(mark.pointBY, "angle V2 mark ${mark.id} pointBY")
            requireFinite(mark.pointCX, "angle V2 mark ${mark.id} pointCX")
            requireFinite(mark.pointCY, "angle V2 mark ${mark.id} pointCY")
            require(mark.degrees == null || mark.invalidReason == null) {
                "angle V2 mark ${mark.id} cannot be measurable and invalid at once"
            }
            mark.degrees?.let { degrees ->
                require(degrees.isFinite() && degrees >= 0.0 && degrees <= 180.0) {
                    "angle V2 mark ${mark.id} degrees must be finite within [0, 180], was $degrees"
                }
            }
        }
    }
    return this
}
