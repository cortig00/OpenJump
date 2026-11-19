package com.openjump.app.data.backup

import com.openjump.app.angles.AngleGeometry
import com.openjump.app.angles.AngleMarkNameContract
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.angles.AngleImageKey

/**
 * Validacion del manifiesto con imagenes, **antes** de escribir el contenedor o de aceptar uno.
 *
 * Contrato completo (todo se comprueba, nada se repara ni se rellena):
 *  1. versiones reconocidas: contrato, [MediaBackupManifest.formatVersion] y
 *     [MediaBackupManifest.sourceRoomSchemaVersion] (Room 14, el esquema en el que nacen las tablas
 *     V2); discriminador de raiz igual a [ANGLE_ANALYSIS_V2_FORMAT] y `geometryVersion >= 1`;
 *  2. el documento legacy se valida con **su** validacion ([ManualBackupDocument.validate]) y no
 *     puede declarar medios ni preferencias: el JSON v1 nunca contiene imagenes y aqui no se finge
 *     lo contrario;
 *  3. ids y claves unicos (raices, fotogramas, marcas, `sessionKey`, `imageKey`), positivos cuando
 *     son identidades persistentes;
 *  4. relaciones no huérfanas: fotograma -> raiz existente, marca -> fotograma existente, imagen ->
 *     exactamente un fotograma, y todo fotograma -> su entrada de imagen declarada;
 *  5. el dueno de una raiz, si lo declara, existe en `legacy.tables.athletes`; `null` se conserva
 *     como `null` (nunca se inventa un atleta);
 *  6. cardinalidad: al menos un fotograma por raiz y al menos una marca (angulo) por fotograma, con
 *     ordinales **completos y consecutivos** `0..n-1` dentro de cada padre (el mismo criterio que el
 *     contrato legacy usa para sus hijos);
 *  7. cadenas acotadas con los contratos existentes: nombre de marca segun
 *     [AngleMarkNameContract] y presupuesto de texto del preflight; nada de campos de ruta/URI
 *     porque el contrato no los tiene;
 *  8. geometria: puntos finitos dentro de los pixeles de presentacion del fotograma
 *     (`[0, w-1] x [0, h-1]`), terna A/B/C medible ([AngleGeometry]), dimensiones positivas y
 *     coherencia tamano/rotacion (90/270 intercambian ejes);
 *  9. integridad de imagen: clave canonica, MIME `image/png`, byteCount positivo y acotado por el
 *     contrato del almacen, SHA-256 canonico en minuscula, coincidencia exacta entre la fila del
 *     fotograma y la entrada declarada, y sumas sin overflow dentro del tope total;
 * 10. estado asignado vs reportado: ambos indices/PTS son no negativos y el reportado puede ser
 *     `null` (desconocido), nunca "prestado" del asignado por la validacion.
 *
 * Devuelve el propio manifiesto para encadenar, igual que [ManualBackupDocument.validate].
 */
fun MediaBackupManifest.validate(): MediaBackupManifest {
    MediaBackupManifestPreflight.checkManifest(this)
    require(contract == MEDIA_BACKUP_CONTRACT) { "Not an OpenJump media backup" }
    require(formatVersion == MEDIA_BACKUP_FORMAT_VERSION) { "Unsupported media backup version" }
    require(sourceRoomSchemaVersion == MEDIA_BACKUP_ROOM_VERSION) {
        "Unsupported Room schema version for a media backup"
    }
    require(createdAtEpochMillis >= 0L) { "media backup createdAtEpochMillis must be >= 0" }
    // El payload legacy se valida con su contrato: presupuesto de 8 MiB, relaciones, versiones...
    val validatedLegacy = legacy.validate()
    require(validatedLegacy === legacy) { "Legacy document validation must not rewrite the backup" }
    require(!legacy.mediaIncluded && !legacy.preferencesIncluded) {
        "The v$MANUAL_BACKUP_FORMAT_VERSION document never carries media or preferences; images travel in the media section"
    }
    val v2 = angleV2
    // --- Identidades -----------------------------------------------------------------------------
    val athleteIds = legacy.tables.athletes.mapTo(HashSet()) { it.id }
    val rootIds = unique(v2.roots.map { it.id }, "angle V2 root id")
    val frameIds = unique(v2.frames.map { it.id }, "angle V2 frame id")
    val markIds = unique(v2.marks.map { it.id }, "angle V2 mark id")
    rootIds.forEach { require(it > 0L) { "angle V2 root id must be positive" } }
    frameIds.forEach { require(it > 0L) { "angle V2 frame id must be positive" } }
    markIds.forEach { require(it > 0L) { "angle V2 mark id must be positive" } }
    unique(v2.roots.map { it.sessionKey }, "angle V2 root sessionKey")
    unique(v2.frames.map { it.imageKey }, "angle V2 frame imageKey")
    // Una imagen pertenece a un unico fotograma: dos referencias duplicadas son un error.
    unique(v2.images.map { it.imageKey }, "angle V2 image key")
    // --- Raices ----------------------------------------------------------------------------------
    v2.roots.forEach { root ->
        require(root.analysisFormat == ANGLE_ANALYSIS_V2_FORMAT) {
            "angle V2 root ${root.id} has an unsupported analysisFormat: '${root.analysisFormat}'"
        }
        require(root.sessionKey.isNotBlank()) { "angle V2 root ${root.id} sessionKey must not be blank" }
        // Dueno opcional: `null` es un estado real (convencion del proyecto) y se conserva; un valor
        // debe existir en el documento legacy, nunca se inventa un atleta al restaurar.
        require(root.athleteId == null || root.athleteId in athleteIds) {
            "angle V2 root ${root.id} references an athlete that the backup does not carry"
        }
        require(root.createdAt >= 0L && root.updatedAt >= 0L) {
            "angle V2 root ${root.id} timestamps must be >= 0"
        }
        require(root.revision >= 0) { "angle V2 root ${root.id} revision must be >= 0" }
    }
    val framesByRoot = v2.frames.groupBy { it.rootId }
    v2.roots.forEach { root ->
        val frames = framesByRoot[root.id].orEmpty()
        require(frames.isNotEmpty()) { "angle V2 root ${root.id} has no frames" }
        requireConsecutiveOrdinals(frames.map { it.ordinal }, "angle V2 root ${root.id} frame")
    }
    // --- Fotogramas ------------------------------------------------------------------------------
    v2.frames.forEach { frame ->
        require(frame.rootId in rootIds) { "angle V2 frame ${frame.id} references an unknown root" }
        require(frame.ordinal >= 0) { "angle V2 frame ${frame.id} ordinal must be >= 0" }
        // Asignado vs reportado: el asignado existe siempre; el reportado puede ser desconocido y no
        // se rellena con el asignado.
        require(frame.assignedFrameIndex >= 0) {
            "angle V2 frame ${frame.id} assignedFrameIndex must be >= 0"
        }
        require(frame.reportedFrameIndex == null || frame.reportedFrameIndex >= 0) {
            "angle V2 frame ${frame.id} reportedFrameIndex must be >= 0 when reported"
        }
        require(frame.assignedPtsUs >= 0L) { "angle V2 frame ${frame.id} assignedPtsUs must be >= 0" }
        require(frame.reportedPtsUs == null || frame.reportedPtsUs >= 0L) {
            "angle V2 frame ${frame.id} reportedPtsUs must be >= 0 when reported"
        }
        require(frame.encodedWidth > 0 && frame.encodedHeight > 0) {
            "angle V2 frame ${frame.id} encoded dimensions must be positive"
        }
        require(frame.presentationWidth > 0 && frame.presentationHeight > 0) {
            "angle V2 frame ${frame.id} presentation dimensions must be positive"
        }
        require(frame.rotationDegrees in ANGLE_V2_ROTATION_DEGREES) {
            "angle V2 frame ${frame.id} has an unsupported rotationDegrees: ${frame.rotationDegrees}"
        }
        require(frame.geometryVersion >= 1) {
            "angle V2 frame ${frame.id} geometryVersion must be >= 1"
        }
        requirePresentationGeometry(frame)
        require(AngleImageKey.isCanonical(frame.imageKey)) {
            "angle V2 frame ${frame.id} imageKey must be a canonical opaque key ([0-9a-f]{${AngleImageKey.HEX_LENGTH}})"
        }
        require(frame.imageMimeType == MEDIA_BACKUP_IMAGE_MIME_TYPE) {
            "angle V2 frame ${frame.id} has an unsupported imageMimeType: '${frame.imageMimeType}'"
        }
        require(frame.imageWidth > 0 && frame.imageHeight > 0) {
            "angle V2 frame ${frame.id} image dimensions must be positive"
        }
        require(frame.imageByteCount in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "angle V2 frame ${frame.id} imageByteCount must be in 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES"
        }
        require(isCanonicalSha256(frame.imageSha256)) {
            "angle V2 frame ${frame.id} imageSha256 must be 64 lowercase hexadecimal characters"
        }
    }
    // --- Marcas ----------------------------------------------------------------------------------
    val marksByFrame = v2.marks.groupBy { it.frameId }
    v2.frames.forEach { frame ->
        val marks = marksByFrame[frame.id].orEmpty()
        require(marks.isNotEmpty()) { "angle V2 frame ${frame.id} has no marks" }
        requireConsecutiveOrdinals(marks.map { it.ordinal }, "angle V2 frame ${frame.id} mark")
        marks.forEach { mark -> requireMarkGeometry(mark, frame) }
    }
    v2.marks.forEach { mark ->
        require(mark.frameId in frameIds) { "angle V2 mark ${mark.id} references an unknown frame" }
        require(mark.ordinal >= 0) { "angle V2 mark ${mark.id} ordinal must be >= 0" }
        require(mark.name.isNotBlank()) { "angle V2 mark ${mark.id} name must not be blank" }
        // Mismo contrato compartido que usa el editor y el repositorio: un nombre aceptado alli
        // cabe aqui, y uno mas largo se rechaza en vez de truncarse en silencio.
        require(AngleMarkNameContract.fits(mark.name)) {
            "angle V2 mark ${mark.id} name exceeds ${AngleMarkNameContract.MAX_CODE_UNITS} code units"
        }
    }
    // --- Imagenes: biyeccion exacta fotograma <-> entrada ----------------------------------------
    val imagesByKey = v2.images.associateBy { it.imageKey }
    val framesByImageKey = v2.frames.associateBy { it.imageKey }
    v2.frames.forEach { frame ->
        require(imagesByKey.containsKey(frame.imageKey)) {
            "angle V2 frame ${frame.id} references an image that the manifest does not carry"
        }
    }
    v2.images.forEach { image ->
        require(AngleImageKey.isCanonical(image.imageKey)) {
            "media backup image key must be a canonical opaque key ([0-9a-f]{${AngleImageKey.HEX_LENGTH}})"
        }
        require(image.byteCount in 1..MEDIA_BACKUP_MAX_IMAGE_BYTES) {
            "media backup image byteCount must be in 1..$MEDIA_BACKUP_MAX_IMAGE_BYTES"
        }
        require(isCanonicalSha256(image.sha256)) {
            "media backup image sha256 must be 64 lowercase hexadecimal characters"
        }
        val frame = framesByImageKey[image.imageKey]
            ?: throw IllegalArgumentException(
                "media backup image ${image.imageKey} is not referenced by any frame",
            )
        require(frame.imageByteCount == image.byteCount && frame.imageSha256 == image.sha256) {
            "media backup image ${image.imageKey} does not match the frame ${frame.id} declaration " +
                "(frame byteCount=${frame.imageByteCount}, sha256=${frame.imageSha256})"
        }
    }
    require(v2.images.size <= MEDIA_BACKUP_MAX_IMAGES) {
        "media backup declares more than $MEDIA_BACKUP_MAX_IMAGES images"
    }
    // Suma acotada sin overflow: el contenedor no puede declarar mas bytes que el almacen privado.
    var totalImageBytes = 0L
    v2.images.forEach { image ->
        totalImageBytes = try {
            Math.addExact(totalImageBytes, image.byteCount)
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("media backup image bytes overflow", overflow)
        }
        require(totalImageBytes <= MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES) {
            "media backup declares more than $MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES image bytes"
        }
    }
    return this
}

/**
 * Rotaciones que puede declarar un fotograma V2: las mismas que produce el extractor
 * ([com.openjump.app.data.backup] valida lo mismo para el agregado legacy de un solo frame).
 */
private val ANGLE_V2_ROTATION_DEGREES = setOf(0, 90, 180, 270)

/** SHA-256 canonico: 64 hex en minuscula. La forma exacta evita comparaciones ambiguas. */
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private fun isCanonicalSha256(value: String): Boolean = SHA256_HEX.matches(value)

/**
 * La geometria de presentacion es coherente con la rotacion: 0/180 conservan los ejes codificados y
 * 90/270 los intercambian. Las marcas viven en el sistema post-rotacion, asi que una fila
 * incoherente restauraria coordenadas contra un frame que el extractor nunca produjo.
 */
private fun requirePresentationGeometry(frame: MediaBackupFrame) {
    if (frame.rotationDegrees % 180 == 0) {
        require(
            frame.presentationWidth == frame.encodedWidth &&
                frame.presentationHeight == frame.encodedHeight,
        ) {
            "angle V2 frame ${frame.id} rotation ${frame.rotationDegrees} cannot swap encoded dimensions"
        }
    } else {
        require(
            frame.presentationWidth == frame.encodedHeight &&
                frame.presentationHeight == frame.encodedWidth,
        ) {
            "angle V2 frame ${frame.id} rotation ${frame.rotationDegrees} must swap encoded dimensions"
        }
    }
}

/**
 * Geometria de una marca contra el fotograma padre: puntos finitos, dentro de
 * `[0, presentationWidth-1] x [0, presentationHeight-1]` y con un angulo medible por la misma
 * definicion que usa la UI ([AngleGeometry]). A == C con B distinto es un 0 grados legitimo; A == B
 * o C == B no tienen lado y se rechazan.
 */
private fun requireMarkGeometry(mark: MediaBackupMark, frame: MediaBackupFrame) {
    val a = requirePoint(mark.id, "pointA", mark.pointAX, mark.pointAY, frame)
    val b = requirePoint(mark.id, "pointB", mark.pointBX, mark.pointBY, frame)
    val c = requirePoint(mark.id, "pointC", mark.pointCX, mark.pointCY, frame)
    when (val measurement = AngleGeometry.measure(a, b, c)) {
        is AngleMeasurement.Measured -> Unit
        is AngleMeasurement.Invalid -> throw IllegalArgumentException(
            "angle V2 mark ${mark.id} has no measurable angle: ${measurement.reason}",
        )
    }
}

private fun requirePoint(
    markId: Long,
    label: String,
    x: Double,
    y: Double,
    frame: MediaBackupFrame,
): AnglePoint {
    require(x.isFinite() && y.isFinite()) { "angle V2 mark $markId $label must be finite" }
    require(
        x >= 0.0 && x <= (frame.presentationWidth - 1).toDouble() &&
            y >= 0.0 && y <= (frame.presentationHeight - 1).toDouble(),
    ) {
        "angle V2 mark $markId $label must be inside the " +
            "${frame.presentationWidth}x${frame.presentationHeight} presentation frame"
    }
    return AnglePoint(x, y)
}

/** Ordinales de los hijos de un padre: exactamente `0..n-1`, sin huecos ni repeticiones. */
private fun requireConsecutiveOrdinals(ordinals: List<Int>, label: String) {
    require(ordinals.sorted() == ordinals.indices.toList()) {
        "$label ordinals must be complete and consecutive from 0"
    }
}

private fun <T> unique(values: List<T>, field: String): Set<T> {
    require(values.distinct().size == values.size) { "Duplicate $field" }
    return values.toSet()
}
