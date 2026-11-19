package com.openjump.app.data.backup

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity

/**
 * Proyeccion **pura** (sin IO y sin Room) de las filas del agregado multi-fotograma a la seccion
 * `angleV2` del manifiesto ([MediaBackupAngleV2]), usada por
 * [ManualBackupDao.snapshotWithAngleMedia].
 *
 * Reglas que esta proyeccion hace estructurales, para que el manifiesto se pueda validar despues
 * sin reparaciones:
 *  - copia **campo a campo** lo que hay en la fila: ids, ordinales, `athleteId` (incluido `null`,
 *    que es un estado real y no se sustituye por un atleta inventado) y, en cada fotograma, los
 *    indices/PTS **asignados** y **reportados** por separado (un reportado `null` se queda `null`,
 *    nunca se copia del asignado);
 *  - **una imagen por fotograma**, en el orden canonico de clave (`sortedBy imageKey`), que es el
 *    mismo que usa [MediaBackupManifest.archivePlan]; no se agrupan ni se deduplican fotogramas
 *    porque la tabla tiene un indice unico sobre `imageKey` y una duplicacion debe ser un rechazo
 *    en la validacion, no una perdida silenciosa aqui;
 *  - **ninguna ruta, URI ni nombre de fichero**: solo viaja la clave opaca `imageKey` y la
 *    contabilidad `byteCount`/`sha256` de la fila. El nombre de la entrada del contenedor se
 *    deriva con [MediaBackupArchive.imageEntryName].
 */
internal fun mediaBackupAngleV2Snapshot(
    roots: List<AngleAnalysisV2RootEntity>,
    frames: List<AngleAnalysisV2FrameEntity>,
    marks: List<AngleAnalysisV2MarkEntity>,
): MediaBackupAngleV2 = MediaBackupAngleV2(
    roots = roots.map { it.toMediaBackupRoot() },
    frames = frames.map { it.toMediaBackupFrame() },
    marks = marks.map { it.toMediaBackupMark() },
    images = mediaBackupImagesOf(frames),
)

/**
 * Entradas de imagen del manifiesto: **exactamente una** por fotograma, con el tamano y el SHA-256
 * que la fila declara. El orden es el canonico de clave (estable e independiente del orden de
 * lectura), para que dos capturas del mismo estado produzcan el mismo manifiesto.
 */
internal fun mediaBackupImagesOf(frames: List<AngleAnalysisV2FrameEntity>): List<MediaBackupImage> =
    frames
        .sortedBy { it.imageKey }
        .map { frame ->
            MediaBackupImage(
                imageKey = frame.imageKey,
                byteCount = frame.imageByteCount,
                sha256 = frame.imageSha256,
            )
        }

private fun AngleAnalysisV2RootEntity.toMediaBackupRoot() = MediaBackupRoot(
    id = id,
    analysisFormat = analysisFormat,
    sessionKey = sessionKey,
    athleteId = athleteId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision,
)

private fun AngleAnalysisV2FrameEntity.toMediaBackupFrame() = MediaBackupFrame(
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

private fun AngleAnalysisV2MarkEntity.toMediaBackupMark() = MediaBackupMark(
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
