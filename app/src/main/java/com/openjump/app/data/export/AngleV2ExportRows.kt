package com.openjump.app.data.export

import java.time.Instant

/**
 * Ensamblado del agregado de ángulos multi-fotograma (V2) desde las **filas planas** que devuelven
 * las consultas keyset de [DataExportDao] (`angleV2Page` + `angleV2DetailPage`), no desde las
 * entidades Room.
 *
 * Por qué existe este camino además del de entidades ([AngleAnalysisV2RootEntity][com.openjump.app.data.AngleAnalysisV2RootEntity]
 * → [ExportAngleV2Analysis]): el export nunca debe poder publicar `sessionKey`, `imageKey` ni el
 * SHA-256 del bitmap, así que la lectura real no proyecta esas columnas y estas funciones sólo
 * reciben lo que la proyección declaró. Un ensamblador que aceptara entidades dejaría la política de
 * privacidad en manos del modelo; aquí está en la consulta (como en la familia V1).
 *
 * Lo que se preserva del agregado persistido:
 *  - `Long` de microsegundos EXACTOS en `assignedPtsUs`/`reportedPtsUs` (nunca `Double`);
 *  - asignado y reportado **por separado**, con `null` cuando la extracción no verificó el valor;
 *  - geometría y marcas de CADA fotograma (el sistema de coordenadas es el del fotograma);
 *  - los grados derivados de los puntos guardados ([ExportAngleMark.measured]).
 */

/**
 * Ensambla todas las raíces de una página keyset con sus fotogramas y marcas.
 *
 * [details] puede traer filas de varias raíces de la página (es lo que devuelve
 * [DataExportDao.angleV2DetailPage] para `parentIds`): se agrupan por `rootId`. Una raíz sin
 * fotogramas no tiene filas y se emite con `frames: []`; un fotograma sin marcas llega con
 * `markId` NULL y se emite con `marks: []`.
 *
 * Falla en cerrado si una fila de detalle no pertenece a ninguna de [roots]: publicarla como hija de
 * otra raíz produciría un agregado con apariencia válida y contenido ajeno.
 *
 * El orden de las raíces es el de [roots] (el de la consulta) y el de fotogramas/marcas el de
 * [details]; no se reordena aquí. El repositorio aplica
 * [canonicalizedV2Analysis] antes de emitir, de modo que el orden publicado no depende del llamador.
 */
internal fun angleV2Analyses(
    roots: List<ExportAngleV2RootRow>,
    details: List<ExportAngleV2DetailRow>,
): List<ExportAngleV2Analysis> {
    val rootIds = roots.map { it.id }.toSet()
    require(details.all { it.rootId in rootIds }) {
        "angle V2 detail rows must belong to a root of the page"
    }
    val detailsByRoot = details.groupBy { it.rootId }
    return roots.map { root -> root.toExportAngleV2Analysis(detailsByRoot[root.id].orEmpty()) }
}

/**
 * Construye la raíz exportable a partir de la fila keyset y de sus filas planas de fotograma+marca.
 *
 * Las columnas de la raíz viajan 1:1 con la fila: `analysisFormat` es el formato PERSISTIDO (no se
 * sustituye por la constante vigente), `createdAt`/`updatedAt` se publican como instante UTC y
 * `sessionKey` no existe en el modelo. El vídeo se identifica sólo por la clave anónima de la raíz
 * ([ExportAngleVideoReference]).
 */
internal fun ExportAngleV2RootRow.toExportAngleV2Analysis(
    detailRows: List<ExportAngleV2DetailRow>,
): ExportAngleV2Analysis {
    require(detailRows.all { it.rootId == id }) {
        "angle V2 detail rows must belong to root $idText"
    }
    // `groupBy` conserva el orden de aparición: los fotogramas salen en el orden estable de la
    // consulta (`ordinal`, `id`) sin un paso extra de ordenación.
    val rowsByFrame = detailRows.groupBy { it.frameId }
    return ExportAngleV2Analysis(
        id = idText,
        analysisFormat = analysisFormat,
        createdAtUtc = Instant.ofEpochMilli(createdAt).toString(),
        updatedAtUtc = Instant.ofEpochMilli(updatedAt).toString(),
        revision = revision,
        athleteId = athleteId?.toString(),
        video = ExportAngleVideoReference(),
        frames = rowsByFrame.values.map { rows -> rows.first().toExportAngleV2Frame(rows) },
    )
}

/**
 * Construye el fotograma exportable a partir de la fila plana (columnas del fotograma, repetidas
 * idénticamente en cada una de sus marcas) y de todas sus filas.
 *
 * `reportedFrameIndex`/`reportedPtsUs` se copian literalmente: si la extracción no los verificó,
 * viajan `null` y el export NO los rellena con el asignado. Un `assignedPtsUs` próximo a 2^53 se
 * conserva entero porque la fila lo trae como `Long`.
 */
internal fun ExportAngleV2DetailRow.toExportAngleV2Frame(
    rows: List<ExportAngleV2DetailRow>,
): ExportAngleV2Frame {
    require(rows.all { it.frameId == frameId }) {
        "angle V2 frame rows must belong to frame $frameId"
    }
    return ExportAngleV2Frame(
        id = frameId.toString(),
        ordinal = frameOrdinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        // Un fotograma sin marcas llega con una única fila `markId` NULL: viaja con `marks: []`.
        marks = rows.filter { it.markId != null }.map { it.toExportAngleMark() },
    )
}

/**
 * Convierte la fila plana de marca en marca exportable y deriva sus grados con
 * [ExportAngleMark.measured] (la tabla no guarda grados: se recalculan de los puntos guardados).
 *
 * Como en V1, un ángulo no medible queda con `degrees = null` y un `invalidReason` tipado, nunca con
 * un 0° falso.
 */
internal fun ExportAngleV2DetailRow.toExportAngleMark(): ExportAngleMark = ExportAngleMark(
    id = requireNotNull(markId) { "angle V2 frame $frameId has a mark row without id" }.toString(),
    ordinal = requireNotNull(markOrdinal) { "angle V2 mark $markId has no ordinal" },
    name = requireNotNull(markName) { "angle V2 mark $markId has no name" },
    visible = markVisible ?: true,
    colorArgb = requireNotNull(markColorArgb) { "angle V2 mark $markId has no color" },
    pointAX = requireNotNull(pointAX) { "angle V2 mark $markId has no pointAX" },
    pointAY = requireNotNull(pointAY) { "angle V2 mark $markId has no pointAY" },
    pointBX = requireNotNull(pointBX) { "angle V2 mark $markId has no pointBX" },
    pointBY = requireNotNull(pointBY) { "angle V2 mark $markId has no pointBY" },
    pointCX = requireNotNull(pointCX) { "angle V2 mark $markId has no pointCX" },
    pointCY = requireNotNull(pointCY) { "angle V2 mark $markId has no pointCY" },
).measured()
