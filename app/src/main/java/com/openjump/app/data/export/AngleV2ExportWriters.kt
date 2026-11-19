package com.openjump.app.data.export

/**
 * Valor de `measurement_family` de las observaciones del agregado multi-fotograma (V2).
 *
 * Distingue la familia en el CSV sin tocar la cabecera de 29 columnas: `angle` sigue siendo el
 * análisis manual de un único frame y `angleV2` el agregado de 0..N fotogramas. Un receptor que no
 * conozca V2 puede ignorar estas filas por familia en vez de confundirlas con las de V1.
 */
internal const val ANGLE_V2_MEASUREMENT_FAMILY = "angleV2"

/**
 * Filas CSV de una raíz del agregado multi-fotograma (V2), en formato largo: misma cabecera de 29
 * columnas, `measurement_family = "angleV2"` y tres tipos de observación que hacen explícita la
 * jerarquía raíz → fotograma → marca:
 *
 *  - `ANGLE_V2_ANALYSIS`: atributos de la raíz (formato persistido, revisión, actualización y cómo
 *    interpretar su clave anónima). Una vez por raíz.
 *  - `ANGLE_V2_FRAME`: atributos del fotograma (índices asignado/reportado, PTS y geometría de
 *    presentación). Una vez por fotograma.
 *  - `ANGLE_V2_MARK`: atributos y puntos de la marca. Una vez por atributo de marca.
 *
 * **Identidad y orden.** `measurement_id` es la clave estable y anónima de la raíz (nunca el
 * `sessionKey` derivado de la URI del vídeo) y `date_time_utc` su instante de creación, igual que
 * en V1. `attempt_ordinal` lleva el ordinal del FOTOGRAMA y `repetition_ordinal` el de la MARCA (en
 * V1 el de la marca viajaba solo en `repetition_ordinal`), de modo que cada fila se identifica por
 * (raíz, fotograma, marca) y conserva el orden de la raíz sin claves adicionales ni columnas
 * nuevas. Las filas de raíz dejan ambos ordinales vacíos; las de fotograma dejan la marca vacía.
 *
 * **Exactitud.** Los PTS viajan por `metricLongValue`, que el escritor renderiza con
 * `Long.toString()` (decimal exacto: un PTS de 2^53+1 no es representable en `Double`); el ARGB
 * empaquetado igual. Los atributos textuales usan `metric_reason` con `metric_unit = "TEXT"`; los
 * booleanos usan `metric_validity`.
 *
 * **Reportado vs asignado.** `REPORTED_FRAME_INDEX`/`REPORTED_PTS` sólo se emiten si la extracción
 * los verificó. Un `null` no produce fila y NUNCA se rellena con el valor asignado: la ausencia es
 * la declaración.
 *
 * **Medios.** No hay filas para la clave del almacén de imágenes, el SHA-256, el MIME ni el tamaño
 * del bitmap: el export analítico no publica digests ni identificadores de contenido. La
 * correlación con el medio se hace por la clave anónima de la raíz
 * (`VIDEO_REFERENCE_KIND`), como en V1.
 *
 * Misma forma canónica que el JSON: se aplica [canonicalizedV2Analysis], así que fotogramas y
 * marcas salen en su orden estable y con los grados derivados aunque el llamador entregue otro
 * orden.
 */
internal fun angleV2Observations(analysis: ExportAngleV2Analysis): List<CsvObservation> {
    fun row(
        type: String,
        key: String,
        value: Double? = null,
        unit: String = ANGLE_TEXT_UNIT,
        validity: String? = null,
        reason: String? = null,
        frameOrdinal: Int? = null,
        markOrdinal: Int? = null,
        longValue: Long? = null,
    ) = CsvObservation(
        observationType = type,
        measurementFamily = ANGLE_V2_MEASUREMENT_FAMILY,
        measurementId = analysis.id,
        dateTimeUtc = analysis.createdAtUtc,
        athleteId = analysis.athleteId,
        athleteName = null,
        athleteBirthDate = null,
        athleteSex = null,
        testingSessionId = null,
        testingGroupName = null,
        protocolId = null,
        exercise = null,
        // El fotograma ocupa `attempt_ordinal` y la marca `repetition_ordinal`.
        attemptOrdinal = frameOrdinal,
        repetitionOrdinal = markOrdinal,
        side = null,
        loadKg = null,
        dropHeightCm = null,
        distanceCm = null,
        analysisVersion = null,
        repetitionQuality = null,
        qualityReasons = null,
        metricKey = key,
        metricValue = value,
        metricUnit = unit,
        metricValidity = validity,
        metricReason = reason,
        isPrimary = false,
        notes = null,
        metricLongValue = longValue,
    )
    fun text(type: String, key: String, value: String, frameOrdinal: Int? = null) =
        row(type, key, reason = value, frameOrdinal = frameOrdinal)
    fun number(type: String, key: String, value: Int, unit: String, frameOrdinal: Int? = null) =
        row(type, key, value.toDouble(), unit, frameOrdinal = frameOrdinal)
    fun pixel(type: String, key: String, value: Int, frameOrdinal: Int) =
        row(type, key, value.toDouble(), ANGLE_PIXEL_UNIT, frameOrdinal = frameOrdinal)
    /** Fila de un entero que no cabe con exactitud en `Double` (PTS µs, ARGB empaquetado). */
    fun exact(type: String, key: String, value: Long, unit: String, frameOrdinal: Int) =
        row(type, key, unit = unit, frameOrdinal = frameOrdinal, longValue = value)
    fun markRow(
        frameOrdinal: Int,
        mark: ExportAngleMark,
        key: String,
        value: Double? = null,
        unit: String = ANGLE_TEXT_UNIT,
        validity: String? = null,
        reason: String? = null,
        longValue: Long? = null,
    ) = row("ANGLE_V2_MARK", key, value, unit, validity, reason, frameOrdinal, mark.ordinal, longValue)

    val canonical = analysis.canonicalizedV2Analysis()
    val analysisRows = listOf(
        // El formato PERSISTIDO distingue una raíz V2 de V1 y de un formato futuro sin adivinar.
        text("ANGLE_V2_ANALYSIS", "ANALYSIS_FORMAT", canonical.analysisFormat),
        number("ANGLE_V2_ANALYSIS", "REVISION", canonical.revision, ANGLE_COUNT_UNIT),
        text("ANGLE_V2_ANALYSIS", "UPDATED_AT", canonical.updatedAtUtc),
        // Única referencia al vídeo: cómo interpretar la clave anónima de la raíz.
        text("ANGLE_V2_ANALYSIS", "VIDEO_REFERENCE_KIND", canonical.video.kind),
    )
    val frameRows = canonical.frames.flatMap { frame ->
        val frameOrdinal = frame.ordinal
        buildList {
            add(text("ANGLE_V2_FRAME", "FRAME_ID", frame.id, frameOrdinal))
            add(number("ANGLE_V2_FRAME", "FRAME_ORDINAL", frameOrdinal, ANGLE_COUNT_UNIT, frameOrdinal))
            add(number("ANGLE_V2_FRAME", "ASSIGNED_FRAME_INDEX", frame.assignedFrameIndex, ANGLE_COUNT_UNIT, frameOrdinal))
            add(exact("ANGLE_V2_FRAME", "ASSIGNED_PTS", frame.assignedPtsUs, ANGLE_MICROSECOND_UNIT, frameOrdinal))
            // Sólo si la extracción los verificó: el export no copia el asignado al reportado.
            frame.reportedFrameIndex?.let {
                add(number("ANGLE_V2_FRAME", "REPORTED_FRAME_INDEX", it, ANGLE_COUNT_UNIT, frameOrdinal))
            }
            frame.reportedPtsUs?.let {
                add(exact("ANGLE_V2_FRAME", "REPORTED_PTS", it, ANGLE_MICROSECOND_UNIT, frameOrdinal))
            }
            add(pixel("ANGLE_V2_FRAME", "ENCODED_WIDTH", frame.encodedWidth, frameOrdinal))
            add(pixel("ANGLE_V2_FRAME", "ENCODED_HEIGHT", frame.encodedHeight, frameOrdinal))
            add(pixel("ANGLE_V2_FRAME", "PRESENTATION_WIDTH", frame.presentationWidth, frameOrdinal))
            add(pixel("ANGLE_V2_FRAME", "PRESENTATION_HEIGHT", frame.presentationHeight, frameOrdinal))
            add(number("ANGLE_V2_FRAME", "ROTATION_DEGREES", frame.rotationDegrees, ANGLE_DEGREE_UNIT, frameOrdinal))
            add(number("ANGLE_V2_FRAME", "GEOMETRY_VERSION", frame.geometryVersion, ANGLE_COUNT_UNIT, frameOrdinal))
            // Marcas del fotograma con las mismas claves que la familia V1: un receptor reutiliza su
            // lector cambiando la familia y añadiendo el ordinal de fotograma.
            frame.marks.forEach { mark ->
                add(markRow(frameOrdinal, mark, "MARK_ID", reason = mark.id))
                add(markRow(frameOrdinal, mark, "MARK_NAME", reason = mark.name))
                add(markRow(frameOrdinal, mark, "ORDINAL", mark.ordinal.toDouble(), ANGLE_COUNT_UNIT))
                // Las marcas ocultas se incluyen: la visibilidad es un atributo, no un filtro.
                add(
                    markRow(
                        frameOrdinal, mark, "VISIBLE",
                        unit = ANGLE_BOOLEAN_UNIT, validity = if (mark.visible) "VISIBLE" else "HIDDEN",
                    ),
                )
                add(markRow(frameOrdinal, mark, "COLOR_ARGB", unit = ANGLE_ARGB_UNIT, longValue = mark.colorArgb.toLong()))
                add(markRow(frameOrdinal, mark, "POINT_A_X", mark.pointAX, ANGLE_PIXEL_UNIT))
                add(markRow(frameOrdinal, mark, "POINT_A_Y", mark.pointAY, ANGLE_PIXEL_UNIT))
                add(markRow(frameOrdinal, mark, "POINT_B_X", mark.pointBX, ANGLE_PIXEL_UNIT))
                add(markRow(frameOrdinal, mark, "POINT_B_Y", mark.pointBY, ANGLE_PIXEL_UNIT))
                add(markRow(frameOrdinal, mark, "POINT_C_X", mark.pointCX, ANGLE_PIXEL_UNIT))
                add(markRow(frameOrdinal, mark, "POINT_C_Y", mark.pointCY, ANGLE_PIXEL_UNIT))
                // Una marca no medible publica el motivo, nunca un 0° falso.
                mark.degrees?.let { add(markRow(frameOrdinal, mark, "DEGREES", it, ANGLE_DEGREE_UNIT)) }
                mark.invalidReason?.let { add(markRow(frameOrdinal, mark, "INVALID_REASON", reason = it)) }
            }
        }
    }
    // `analysisRows` antes de los fotogramas y, dentro de cada fotograma, sus atributos antes que
    // sus marcas: el orden de lectura del JSON y el de este CSV son el mismo.
    return analysisRows + frameRows
}
