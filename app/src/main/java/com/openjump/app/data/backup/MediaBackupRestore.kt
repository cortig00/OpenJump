package com.openjump.app.data.backup

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity

/**
 * Traduccion **pura** (sin Room y sin IO) de la seccion `angleV2` de un [MediaBackupManifest] ya
 * validado a las filas del agregado multi-fotograma, usada por
 * [ManualBackupDao.replaceAllWithAngleMedia].
 *
 * Reglas que esta traduccion hace estructurales:
 *  - **campo a campo y con los ids exactos del manifiesto**: ningun id se regenera, se renumera ni
 *    se descarta, y `athleteId == null` se conserva como `null` (una raiz sin dueno es un estado
 *    real del proyecto, no un dato perdido);
 *  - **asignado frente a reportado por separado**: los indices y PTS asignados se copian tal cual y
 *    los reportados `null` siguen `null`, nunca se rellenan desde los asignados;
 *  - se copian **sin reordenar ni filtrar**: el orden del manifiesto es el que valido el contrato
 *    (ordinales `0..n-1` dentro de cada padre) y el orden de insercion es el de estos `List`;
 *  - la integridad de imagen (clave opaca, dimensiones, tamano, SHA-256 y MIME) viaja a la fila sin
 *    interpretarse: esta capa **no** abre ficheros y no puede comprobar que el PNG exista.
 *
 * Esta funcion **no valida**: el unico punto de entrada que la usa exige haber llamado antes a
 * [MediaBackupManifest.validate], que es quien rechaza ids duplicados/no positivos, relaciones
 * huerfanas, ordinales incompletos, geometria invalida o una imagen declarada que no cuadre con su
 * fotograma.
 */
internal data class MediaBackupAngleV2Rows(
    val roots: List<AngleAnalysisV2RootEntity>,
    val frames: List<AngleAnalysisV2FrameEntity>,
    val marks: List<AngleAnalysisV2MarkEntity>,
) {
    /** Conteo esperado tras restaurar, comparable con el que devuelve la base. */
    val counts: AngleAnalysisV2RowCounts
        get() = AngleAnalysisV2RowCounts(roots.size, frames.size, marks.size)
}

/** Filas del agregado V2 en el orden del manifiesto, listas para insertarse padres antes que hijos. */
internal fun MediaBackupAngleV2.restoreRows(): MediaBackupAngleV2Rows = MediaBackupAngleV2Rows(
    roots = roots.map { it.entity() },
    frames = frames.map { it.entity() },
    marks = marks.map { it.entity() },
)

internal fun MediaBackupRoot.entity() = AngleAnalysisV2RootEntity(
    id = id,
    analysisFormat = analysisFormat,
    sessionKey = sessionKey,
    athleteId = athleteId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision,
)

internal fun MediaBackupFrame.entity() = AngleAnalysisV2FrameEntity(
    id = id,
    rootId = rootId,
    ordinal = ordinal,
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
    imageKey = imageKey,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    imageByteCount = imageByteCount,
    imageSha256 = imageSha256,
    imageMimeType = imageMimeType,
)

internal fun MediaBackupMark.entity() = AngleAnalysisV2MarkEntity(
    id = id,
    frameId = frameId,
    ordinal = ordinal,
    name = name,
    colorArgb = colorArgb,
    visible = visible,
    pointAX = pointAX,
    pointAY = pointAY,
    pointBX = pointBX,
    pointBY = pointBY,
    pointCX = pointCX,
    pointCY = pointCY,
)
