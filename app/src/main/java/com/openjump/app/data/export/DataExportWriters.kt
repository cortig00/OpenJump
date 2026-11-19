package com.openjump.app.data.export

import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val exportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
}

object JsonDataExportWriter {
    fun write(document: DataExportDocument): String = exportJson.encodeToString(document.validated().canonicalized())
}

object CsvDataExportWriter {
    val header: List<String> = listOf(
        "schema_version", "observation_type", "measurement_family", "measurement_id",
        "date_time_utc", "athlete_id", "athlete_name", "athlete_birth_date", "athlete_sex",
        "testing_session_id", "testing_group_name", "protocol_id", "exercise", "attempt_ordinal",
        "repetition_ordinal", "side", "load_kg", "drop_height_cm", "distance_cm", "analysis_version",
        "repetition_quality", "quality_reasons", "metric_key", "metric_value", "metric_unit",
        "metric_validity", "metric_reason", "is_primary", "notes",
    )

    fun write(document: DataExportDocument): String {
        val valid = document.validated().canonicalized()
        return buildString {
            append(header.joinToString(",", transform = ::csvCell)).append("\r\n")
            observations(valid).forEach { row -> append(row.toCsv()).append("\r\n") }
        }
    }

    fun observations(document: DataExportDocument): List<CsvObservation> {
        val canonical = document.validated().canonicalized()
        val athleteById = canonical.athletes.associateBy { it.id }
        val measurements = canonical.measurements.flatMap { measurement ->
            val athlete = measurement.athleteId?.let(athleteById::get)
            val common: (String, ExportJumpContext?, Int?, String?, String?, ExportMetric) -> CsvObservation =
                { type, context, repetitionOrdinal, quality, reasons, metric ->
                    CsvObservation(type, measurement.kind, measurement.id, measurement.dateTimeUtc,
                        measurement.athleteId, athlete?.name, athlete?.birthDate, athlete?.sex,
                        measurement.testingSessionId, measurement.groupNameSnapshot, measurement.protocolId,
                        measurement.exercise, context?.ordinal, repetitionOrdinal, context?.side,
                        measurement.loadKg, context?.dropHeightCm, context?.distanceCm, measurement.analysisVersion,
                        quality, reasons, metric.key, metric.value, metric.unit, metric.validity, metric.reason, metric.isPrimary, measurement.notes)
                }
            val roots = listOfNotNull(measurement.primaryMetric).map { common("JUMP_METRIC", measurement.primaryMetricContext, null, null, null, it) }
            val jumps = measurement.attempts.flatMap { attempt -> attempt.metrics.map { common("JUMP_METRIC", attempt.context, null, null, null, it) } }
            val summaries = measurement.sessionSummaries.map { common("ENCODER_SESSION_SUMMARY", null, null, null, null, ExportMetric(it.key, it.value, it.unit)) }
            val repetitions = measurement.repetitions.flatMap { repetition ->
                val reasons = repetition.qualityReasons.joinToString("|")
                repetition.metrics.map { common("ENCODER_REPETITION_METRIC", null, repetition.ordinal, repetition.quality, reasons, it) }
            }
            roots + jumps + summaries + repetitions
        }.sortedWith(compareBy<CsvObservation>({ it.dateTimeUtc }, { it.measurementFamily }, { it.measurementId },
            { observationOrder(it.observationType) }, { it.attemptOrdinal ?: -1 }, { it.repetitionOrdinal ?: -1 }, { it.metricKey }, { it.metricUnit }))
        // La familia F-V-P se pagina con su propio cursor, así que sus filas van después de
        // jump/encoder (mismo orden en el escritor streaming y en este camino canónico).
        // Los ángulos (V1 y V2) también tienen cursor propio y van al final, igual que en el JSON.
        return measurements + canonical.fvp.flatMap { fvpObservations(it) } +
            canonical.angleAnalyses.flatMap { angleObservations(it) } +
            canonical.angleV2.flatMap { angleV2Observations(it) }
    }

    /**
     * Filas F-V-P en formato largo: la cabecera de 29 columnas no cambia, así que cada atributo es
     * una observación. `measurement_family = "fvp"` y `observation_type` es FVP_ASSESSMENT,
     * FVP_CONDITION o FVP_ATTEMPT. Los atributos numéricos usan `metric_key`/`metric_value`/
     * `metric_unit`; los textuales usan `metric_key` con el texto en `metric_reason` y
     * `metric_unit = "TEXT"`. `repetition_ordinal` lleva el ordinal de la condición y
     * `attempt_ordinal` el del intento; las filas de condición/intento propagan en `load_kg` la
     * carga añadida de su condición y en `notes` las notas de la evaluación.
     */
    internal fun fvpObservations(assessment: ExportFvpAssessment): List<CsvObservation> {
        fun row(
            type: String,
            key: String,
            value: Double? = null,
            unit: String? = null,
            validity: String? = null,
            reason: String? = null,
            attemptOrdinal: Int? = null,
            repetitionOrdinal: Int? = null,
            loadKg: Double? = null,
            longValue: Long? = null,
        ) = CsvObservation(
            observationType = type,
            measurementFamily = FVP_MEASUREMENT_FAMILY,
            measurementId = assessment.id,
            dateTimeUtc = assessment.dateTimeUtc,
            athleteId = assessment.athleteId,
            athleteName = null,
            athleteBirthDate = null,
            athleteSex = null,
            testingSessionId = assessment.testingSessionId,
            testingGroupName = null,
            protocolId = assessment.protocolId,
            exercise = null,
            attemptOrdinal = attemptOrdinal,
            repetitionOrdinal = repetitionOrdinal,
            side = null,
            loadKg = loadKg,
            dropHeightCm = null,
            distanceCm = null,
            analysisVersion = null,
            repetitionQuality = null,
            qualityReasons = null,
            metricKey = key,
            metricValue = value,
            metricUnit = unit ?: FVP_TEXT_UNIT,
            metricValidity = validity,
            metricReason = reason,
            isPrimary = false,
            notes = assessment.notes,
            metricLongValue = longValue,
        )
        fun text(type: String, key: String, value: String, attemptOrdinal: Int? = null, repetitionOrdinal: Int? = null, loadKg: Double? = null) =
            row(type, key, reason = value, attemptOrdinal = attemptOrdinal, repetitionOrdinal = repetitionOrdinal, loadKg = loadKg)
        /** Fila de un PTS [µs] exacto: viaja por `metricLongValue`, nunca por `metricValue`. */
        fun pts(type: String, key: String, value: Long, attemptOrdinal: Int? = null, repetitionOrdinal: Int? = null, loadKg: Double? = null) =
            row(type, key, unit = FVP_MICROSECOND_UNIT, attemptOrdinal = attemptOrdinal, repetitionOrdinal = repetitionOrdinal, loadKg = loadKg, longValue = value)

        val assessmentRows = buildList {
            add(row("FVP_ASSESSMENT", "BODY_MASS", assessment.bodyMassKg, "KILOGRAM"))
            add(row("FVP_ASSESSMENT", "PUSH_OFF", assessment.pushOffM, "METER"))
            assessment.sourceFps?.let { add(row("FVP_ASSESSMENT", "SOURCE_FPS", it, "FRAMES_PER_SECOND")) }
            add(row("FVP_ASSESSMENT", "CONDITION_COUNT", assessment.conditionCount.toDouble(), "COUNT"))
            add(row("FVP_ASSESSMENT", "F0", assessment.f0N, "NEWTON"))
            assessment.f0RelNPerKg?.let { add(row("FVP_ASSESSMENT", "F0_REL", it, "NEWTON_PER_KILOGRAM")) }
            add(row("FVP_ASSESSMENT", "SLOPE_FV", assessment.slopeFv, "NEWTON_PER_METER_PER_SECOND"))
            add(row("FVP_ASSESSMENT", "SFV", assessment.sfvNsPerM, "NEWTON_PER_METER_PER_SECOND"))
            assessment.v0Mps?.let { add(row("FVP_ASSESSMENT", "V0", it, "METER_PER_SECOND")) }
            assessment.pmaxW?.let { add(row("FVP_ASSESSMENT", "PMAX", it, "WATT")) }
            assessment.pmaxRelWPerKg?.let { add(row("FVP_ASSESSMENT", "PMAX_REL", it, "WATT_PER_KILOGRAM")) }
            assessment.r2?.let { add(row("FVP_ASSESSMENT", "R2", it, "RATIO")) }
            add(row("FVP_ASSESSMENT", "V_MIN", assessment.vMinMps, "METER_PER_SECOND"))
            add(row("FVP_ASSESSMENT", "V_MAX", assessment.vMaxMps, "METER_PER_SECOND"))
            add(row("FVP_ASSESSMENT", "F_MIN", assessment.fMinN, "NEWTON"))
            add(row("FVP_ASSESSMENT", "F_MAX", assessment.fMaxN, "NEWTON"))
            assessment.pushOffLandmarkHeightCm?.let { add(row("FVP_ASSESSMENT", "PUSH_OFF_LANDMARK_HEIGHT", it, "CENTIMETER")) }
            assessment.pushOffCrouchCm?.let { add(row("FVP_ASSESSMENT", "PUSH_OFF_CROUCH", it, "CENTIMETER")) }
            assessment.pushOffExtendedCm?.let { add(row("FVP_ASSESSMENT", "PUSH_OFF_EXTENDED", it, "CENTIMETER")) }
            add(text("FVP_ASSESSMENT", "SESSION_KEY", assessment.sessionKey))
            add(text("FVP_ASSESSMENT", "BODY_MASS_SOURCE", assessment.bodyMassSource))
            add(text("FVP_ASSESSMENT", "PUSH_OFF_MODE", assessment.pushOffMode))
            add(text("FVP_ASSESSMENT", "PUSH_OFF_SOURCE", assessment.pushOffSource))
            assessment.pushOffLandmark?.let { add(text("FVP_ASSESSMENT", "PUSH_OFF_LANDMARK", it)) }
            assessment.pushOffProtocol?.let { add(text("FVP_ASSESSMENT", "PUSH_OFF_PROTOCOL", it)) }
            assessment.pushOffMeasuredAtUtc?.let { add(text("FVP_ASSESSMENT", "PUSH_OFF_MEASURED_AT", it)) }
            add(text("FVP_ASSESSMENT", "ALGORITHM_VERSION", assessment.algorithmVersion))
            add(text("FVP_ASSESSMENT", "METHOD_VERSION", assessment.methodVersion))
            add(text("FVP_ASSESSMENT", "WARNINGS", assessment.warningsCsv))
            // Procedencia B0 (esquema 5): una fila por columna Room. Los enums se emiten siempre
            // (`UNKNOWN` es un valor declarado, no ausencia); solo se omiten los nullable null.
            assessment.acquiredAtUtc?.let { add(text("FVP_ASSESSMENT", "ACQUIRED_AT", it)) }
            add(text("FVP_ASSESSMENT", "ACQUISITION_CONTEXT_KIND", assessment.acquisitionContextKind))
            assessment.acquisitionContextKey?.let { add(text("FVP_ASSESSMENT", "ACQUISITION_CONTEXT_KEY", it)) }
            add(text("FVP_ASSESSMENT", "BODY_MASS_MEASUREMENT", assessment.bodyMassMeasurement))
            assessment.bodyMassMeasuredAtUtc?.let { add(text("FVP_ASSESSMENT", "BODY_MASS_MEASURED_AT", it)) }
        }
        val conditionRows = assessment.conditions.sortedBy { it.ordinal }.flatMap { condition ->
            val ordinal = condition.ordinal
            val load = condition.addedLoadKg
            buildList {
                add(text("FVP_CONDITION", "CONDITION_KEY", condition.conditionKey, repetitionOrdinal = ordinal, loadKg = load))
                add(row("FVP_CONDITION", "ORDINAL", ordinal.toDouble(), "COUNT", repetitionOrdinal = ordinal, loadKg = load))
                add(row("FVP_CONDITION", "ADDED_LOAD", load, "KILOGRAM", repetitionOrdinal = ordinal, loadKg = load))
                add(row("FVP_CONDITION", "TOTAL_MASS", condition.totalMassKg, "KILOGRAM", repetitionOrdinal = ordinal, loadKg = load))
                add(row("FVP_CONDITION", "PUSH_OFF", condition.pushOffM, "METER", repetitionOrdinal = ordinal, loadKg = load))
                add(row("FVP_CONDITION", "SELECTED_HEIGHT", condition.selectedHeightM, "METER", repetitionOrdinal = ordinal, loadKg = load))
                condition.selectedAttemptOrdinal?.let { add(row("FVP_CONDITION", "SELECTED_ATTEMPT_ORDINAL", it.toDouble(), "COUNT", repetitionOrdinal = ordinal, loadKg = load)) }
                add(text("FVP_CONDITION", "ADDED_LOAD_SOURCE", condition.addedLoadSource, repetitionOrdinal = ordinal, loadKg = load))
                condition.addedLoadMeasuredAtUtc?.let { add(text("FVP_CONDITION", "ADDED_LOAD_MEASURED_AT", it, repetitionOrdinal = ordinal, loadKg = load)) }
                condition.attempts.sortedBy { it.ordinal }.forEach { attempt ->
                    val attemptOrdinal = attempt.ordinal
                    add(row("FVP_ATTEMPT", "HEIGHT", attempt.heightM, "METER", attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load))
                    add(text("FVP_ATTEMPT", "METHOD", attempt.method, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load))
                    attempt.flightTimeS?.let { add(row("FVP_ATTEMPT", "FLIGHT_TIME", it, "SECOND", attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceFps?.let { add(row("FVP_ATTEMPT", "SOURCE_FPS", it, "FRAMES_PER_SECOND", attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    add(row("FVP_ATTEMPT", "VALID", unit = "BOOLEAN", validity = if (attempt.valid) "VALID" else "INVALID", attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load))
                    attempt.exclusionReason?.let { add(text("FVP_ATTEMPT", "EXCLUSION_REASON", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceAssessmentId?.let { add(text("FVP_ATTEMPT", "SOURCE_ASSESSMENT_ID", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceAttemptOrdinal?.let { add(row("FVP_ATTEMPT", "SOURCE_ATTEMPT_ORDINAL", it.toDouble(), "COUNT", attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceProtocolId?.let { add(text("FVP_ATTEMPT", "SOURCE_PROTOCOL", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.notes?.let { add(text("FVP_ATTEMPT", "ATTEMPT_NOTES", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceRecordedAtUtc?.let { add(text("FVP_ATTEMPT", "SOURCE_RECORDED_AT", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.sourceAthleteId?.let { add(text("FVP_ATTEMPT", "SOURCE_ATHLETE_ID", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.acquiredAtUtc?.let { add(text("FVP_ATTEMPT", "ATTEMPT_ACQUIRED_AT", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.reuseConfirmedAtUtc?.let { add(text("FVP_ATTEMPT", "REUSE_CONFIRMED_AT", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.reuseConfirmationVersion?.let { add(text("FVP_ATTEMPT", "REUSE_CONFIRMATION_VERSION", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.takeoffPtsUs?.let { add(pts("FVP_ATTEMPT", "TAKE_OFF_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.takeoffPreviousPtsUs?.let { add(pts("FVP_ATTEMPT", "TAKE_OFF_PREVIOUS_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.takeoffNextPtsUs?.let { add(pts("FVP_ATTEMPT", "TAKE_OFF_NEXT_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.landingPtsUs?.let { add(pts("FVP_ATTEMPT", "LANDING_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.landingPreviousPtsUs?.let { add(pts("FVP_ATTEMPT", "LANDING_PREVIOUS_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    attempt.landingNextPtsUs?.let { add(pts("FVP_ATTEMPT", "LANDING_NEXT_PTS", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                    add(text("FVP_ATTEMPT", "TIME_MAPPING", attempt.timeMapping, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load))
                    attempt.timeMappingEvidenceKey?.let { add(text("FVP_ATTEMPT", "TIME_MAPPING_EVIDENCE_KEY", it, attemptOrdinal = attemptOrdinal, repetitionOrdinal = ordinal, loadKg = load)) }
                }
            }
        }
        return assessmentRows + conditionRows
    }

    /**
     * Filas de un análisis de ángulos en formato largo: la cabecera de 29 columnas no cambia, así
     * que cada atributo es una observación. `measurement_family = "angle"` y `observation_type` es
     * `ANGLE_ANALYSIS` (una vez por raíz) o `ANGLE_MARK` (una vez por atributo de marca).
     *
     * Identidad: `measurement_id` es la clave estable y anónima de la raíz (nunca el `sessionKey`
     * del editor, que se deriva de la URI del vídeo) y `repetition_ordinal` el ordinal de la marca.
     * `metric_key` es siempre la clave del atributo; los textuales llevan el texto en
     * `metric_reason` con `metric_unit = "TEXT"`. Los enteros que no caben con exactitud en un
     * `Double` (PTS en microsegundos) y los empaquetados ARGB viajan por `metric_value` como
     * decimal exacto. Las marcas ocultas se incluyen: la visibilidad es un atributo
     * (`VISIBLE`/`HIDDEN`), no un filtro. Los grados se publican ya calculados; una marca no
     * medible publica `INVALID_REASON` en lugar de un 0° falso.
     */
    internal fun angleObservations(analysis: ExportAngleAnalysis): List<CsvObservation> {
        fun row(
            type: String,
            key: String,
            value: Double? = null,
            unit: String = ANGLE_TEXT_UNIT,
            validity: String? = null,
            reason: String? = null,
            markOrdinal: Int? = null,
            longValue: Long? = null,
        ) = CsvObservation(
            observationType = type,
            measurementFamily = ANGLE_MEASUREMENT_FAMILY,
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
            attemptOrdinal = null,
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
        fun text(type: String, key: String, value: String, markOrdinal: Int? = null) =
            row(type, key, reason = value, markOrdinal = markOrdinal)
        fun number(type: String, key: String, value: Int, unit: String, markOrdinal: Int? = null) =
            row(type, key, value.toDouble(), unit, markOrdinal = markOrdinal)
        fun pixel(type: String, key: String, value: Double, markOrdinal: Int? = null) =
            row(type, key, value, ANGLE_PIXEL_UNIT, markOrdinal = markOrdinal)
        fun exact(type: String, key: String, value: Long, unit: String, markOrdinal: Int? = null) =
            row(type, key, unit = unit, markOrdinal = markOrdinal, longValue = value)

        // Misma forma canónica que el JSON (marcas por ordinal y grados derivados): los dos
        // caminos publican las mismas filas aunque el llamador entregue otro orden.
        val canonical = analysis.canonicalizedAngle()
        val analysisRows = listOf(
            number("ANGLE_ANALYSIS", "FRAME_INDEX", canonical.frameIndex, ANGLE_COUNT_UNIT),
            // PTS exacto: 9007199254740993 no es representable en Double.
            exact("ANGLE_ANALYSIS", "SOURCE_PTS", canonical.sourcePtsUs, ANGLE_MICROSECOND_UNIT),
            number("ANGLE_ANALYSIS", "ENCODED_WIDTH", canonical.encodedWidth, ANGLE_PIXEL_UNIT),
            number("ANGLE_ANALYSIS", "ENCODED_HEIGHT", canonical.encodedHeight, ANGLE_PIXEL_UNIT),
            number("ANGLE_ANALYSIS", "PRESENTATION_WIDTH", canonical.presentationWidth, ANGLE_PIXEL_UNIT),
            number("ANGLE_ANALYSIS", "PRESENTATION_HEIGHT", canonical.presentationHeight, ANGLE_PIXEL_UNIT),
            number("ANGLE_ANALYSIS", "ROTATION_DEGREES", canonical.rotationDegrees, ANGLE_DEGREE_UNIT),
            number("ANGLE_ANALYSIS", "GEOMETRY_VERSION", canonical.geometryVersion, ANGLE_COUNT_UNIT),
            number("ANGLE_ANALYSIS", "REVISION", canonical.revision, ANGLE_COUNT_UNIT),
            text("ANGLE_ANALYSIS", "UPDATED_AT", canonical.updatedAtUtc),
            // Única referencia al vídeo: cómo interpretar la clave anónima de la raíz.
            text("ANGLE_ANALYSIS", "VIDEO_REFERENCE_KIND", canonical.video.kind),
        )
        val markRows = canonical.marks.flatMap { mark ->
            buildList {
                add(text("ANGLE_MARK", "MARK_ID", mark.id, mark.ordinal))
                add(text("ANGLE_MARK", "MARK_NAME", mark.name, mark.ordinal))
                add(number("ANGLE_MARK", "ORDINAL", mark.ordinal, ANGLE_COUNT_UNIT, mark.ordinal))
                add(
                    row(
                        "ANGLE_MARK", "VISIBLE", unit = ANGLE_BOOLEAN_UNIT,
                        validity = if (mark.visible) "VISIBLE" else "HIDDEN", markOrdinal = mark.ordinal,
                    ),
                )
                add(exact("ANGLE_MARK", "COLOR_ARGB", mark.colorArgb.toLong(), ANGLE_ARGB_UNIT, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_A_X", mark.pointAX, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_A_Y", mark.pointAY, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_B_X", mark.pointBX, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_B_Y", mark.pointBY, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_C_X", mark.pointCX, mark.ordinal))
                add(pixel("ANGLE_MARK", "POINT_C_Y", mark.pointCY, mark.ordinal))
                mark.degrees?.let { add(row("ANGLE_MARK", "DEGREES", it, ANGLE_DEGREE_UNIT, markOrdinal = mark.ordinal)) }
                mark.invalidReason?.let { add(text("ANGLE_MARK", "INVALID_REASON", it, mark.ordinal)) }
            }
        }
        return analysisRows + markRows
    }

    private fun observationOrder(type: String) = when (type) {
        "JUMP_METRIC" -> 0
        "ENCODER_REPETITION_METRIC" -> 1
        "ENCODER_SESSION_SUMMARY" -> 2
        else -> 3
    }

    private fun CsvObservation.internalRow(): List<Any?> = listOf(
        DATA_EXPORT_SCHEMA_VERSION, observationType, measurementFamily, measurementId, dateTimeUtc,
        athleteId, athleteName, athleteBirthDate, athleteSex, testingSessionId, testingGroupName,
        protocolId, exercise, attemptOrdinal, repetitionOrdinal, side, loadKg, dropHeightCm,
        distanceCm, analysisVersion, repetitionQuality, qualityReasons, metricKey,
        // Entero exacto si existe (PTS µs); el Double solo cubre las magnitudes fisicas.
        metricLongValue ?: metricValue,
        metricUnit, metricValidity, metricReason, isPrimary, notes,
    )

    internal fun CsvObservation.toCsv(): String = internalRow().joinToString(",", transform = ::csvCell)

    /** Línea CSV de una observación ya construida (la usa el escritor streaming). */
    internal fun csvLine(observation: CsvObservation): String = observation.toCsv()

    internal fun cell(value: Any?): String = csvCell(value)
    private fun csvCell(value: Any?): String {
        if (value == null) return ""
        val text = when (value) {
            is Boolean -> if (value) "true" else "false"
            is Double -> requireFinite(value, "CSV value").toString()
            is Float -> require(value.isFinite()) { "CSV value must be finite" }.toString()
            else -> value.toString()
        }
        val hardened = if (value is String && text.hasFormulaPrefix()) "'$text" else text
        return if (hardened.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${hardened.replace("\"", "\"\"")}\"" else hardened
    }

    private fun String.hasFormulaPrefix(): Boolean {
        var index = 0
        while (index < length && (this[index].isWhitespace() || Character.isISOControl(this[index]))) index++
        if (index >= length) return false
        return when (this[index]) {
            '=', '+', '-', '@' -> true
            else -> false
        }
    }
}

internal interface DataExportStreamWriter {
    suspend fun begin(exportedAt: String)
    suspend fun athlete(athlete: ExportAthlete)
    suspend fun endAthletes()
    suspend fun beginJump(row: ExportMeasurementRow)
    suspend fun beginJumpAttempt(row: ExportJumpDetailRow)
    suspend fun jumpMetric(row: ExportJumpDetailRow)
    suspend fun endJumpAttempt()
    suspend fun endJump()
    suspend fun beginEncoder(row: ExportMeasurementRow)
    suspend fun beginEncoderRepetition(row: ExportEncoderDetailRow)
    suspend fun encoderMetric(row: ExportEncoderDetailRow)
    suspend fun endEncoderRepetition()
    suspend fun endEncoder()

    /** Raíz F-V-P completa (condiciones e intentos incluidos), ya ensamblada por el repositorio. */
    suspend fun fvp(assessment: ExportFvpAssessment)

    /** Raíz de ángulos completa (marcas incluidas), ya ensamblada y medida por el repositorio. */
    suspend fun angle(analysis: ExportAngleAnalysis)

    /**
     * Raíz del agregado multi-fotograma (V2) completa (fotogramas y marcas incluidos), ya
     * ensamblada y medida por el repositorio. Se emite después de [angle] y nunca comparte
     * colección con él: `angleV2` es la familia multi-frame y `angleAnalyses` la de un frame.
     */
    suspend fun angleV2(analysis: ExportAngleV2Analysis)
    suspend fun finish()
}

internal class StreamingJsonDataExportWriter(delegate: java.io.Writer) : DataExportStreamWriter {
    private val out = CancellableExportWriter(delegate)
    private var firstAthlete = true
    private var firstMeasurement = true
    private var firstAttempt = true
    private var firstMetric = true
    private var firstRepetition = true
    private var firstFvp = true
    private var firstAngle = true
    private var firstAngleV2 = true
    private var measurementsClosed = false
    private var fvpClosed = false
    private var anglesClosed = false
    private var angleV2Closed = false
    private var pendingMetric: String? = null
    private var measurementOpen = false
    private var currentNotes: String? = null

    private suspend fun raw(text: String) = out.write(text)
    private fun str(value: String?) = value?.let(exportJson::encodeToString) ?: "null"
    private fun dbl(value: Double?) = value?.let(exportJson::encodeToString) ?: "null"
    private fun integer(value: Int?) = value?.let(exportJson::encodeToString) ?: "null"
    private suspend fun prop(indent: Int, name: String, value: String, comma: Boolean = true) {
        raw("${" ".repeat(indent)}\"$name\": $value${if (comma) "," else ""}\n")
    }
    private suspend fun metric(metric: ExportMetric, indent: Int, comma: Boolean, inline: Boolean = false) {
        raw("${if (inline) "" else " ".repeat(indent)}{\n")
        prop(indent + 4, "key", str(metric.key)); prop(indent + 4, "value", dbl(metric.value)); prop(indent + 4, "unit", str(metric.unit))
        prop(indent + 4, "validity", str(metric.validity)); prop(indent + 4, "reason", str(metric.reason)); prop(indent + 4, "ordinal", integer(metric.ordinal))
        prop(indent + 4, "isPrimary", metric.isPrimary.toString(), false)
        raw("${" ".repeat(indent)}}${if (comma) ",\n" else "\n"}")
    }
    private fun metricText(metric: ExportMetric, indent: Int): String = buildString {
        append(" ".repeat(indent)).append("{\n")
        fun propText(name: String, value: String, comma: Boolean = true) {
            append(" ".repeat(indent + 4)).append('"').append(name).append("\": ").append(value)
                .append(if (comma) "," else "").append('\n')
        }
        propText("key", str(metric.key)); propText("value", dbl(metric.value)); propText("unit", str(metric.unit))
        propText("validity", str(metric.validity)); propText("reason", str(metric.reason)); propText("ordinal", integer(metric.ordinal))
        propText("isPrimary", metric.isPrimary.toString(), comma = false)
        append(" ".repeat(indent)).append('}').append('\n')
    }
    private suspend fun bufferedMetric(metric: ExportMetric, indent: Int) {
        pendingMetric?.let { raw(it.dropLast(1) + ",\n") }
        pendingMetric = metricText(metric, indent)
    }
    private suspend fun flushBufferedMetric() {
        pendingMetric?.let { raw(it) }
        pendingMetric = null
    }
    private suspend fun context(context: ExportJumpContext, indent: Int, comma: Boolean, inline: Boolean = false) {
        raw("${if (inline) "" else " ".repeat(indent)}{\n")
        prop(indent + 4, "side", str(context.side)); prop(indent + 4, "ordinal", context.ordinal.toString()); prop(indent + 4, "dropHeightCm", dbl(context.dropHeightCm)); prop(indent + 4, "distanceCm", dbl(context.distanceCm), false)
        raw("${" ".repeat(indent)}}${if (comma) ",\n" else "\n"}")
    }

    override suspend fun begin(exportedAt: String) {
        raw("{\n")
        prop(4, "contract", str(DATA_EXPORT_CONTRACT)); prop(4, "schemaVersion", DATA_EXPORT_SCHEMA_VERSION.toString()); prop(4, "exportedAt", str(exportedAt)); prop(4, "canonicalUnits", "true"); prop(4, "videoDataIncluded", "false")
        // The array opener is deferred so an empty export uses the serializer's compact [] form.
    }
    override suspend fun athlete(athlete: ExportAthlete) {
        if (firstAthlete) { raw("    \"athletes\": [\n"); firstAthlete = false } else raw(",\n")
        raw("        {\n"); prop(12, "id", str(athlete.id)); prop(12, "name", str(athlete.name)); prop(12, "birthDate", str(athlete.birthDate)); prop(12, "sex", str(athlete.sex), false); raw("        }")
    }
    override suspend fun endAthletes() { if (firstAthlete) raw("    \"athletes\": [],\n") else raw("\n    ],\n") }

    private suspend fun beginMeasurement(row: ExportMeasurementRow, kind: String) {
        if (firstMeasurement) { raw("    \"measurements\": [\n"); firstMeasurement = false } else raw(",\n")
        raw("        {\n"); prop(12, "kind", str(kind)); prop(12, "id", str(row.idText)); prop(12, "dateTimeUtc", str(row.dateTime.toUtcString())); prop(12, "athleteId", str(row.athleteId?.toString())); prop(12, "testingSessionId", str(row.testingSessionId?.toString())); prop(12, "groupNameSnapshot", str(row.groupNameSnapshot)); prop(12, "protocolId", str(row.protocolId)); prop(12, "exercise", str(row.exercise)); prop(12, "loadKg", dbl(row.loadKg)); prop(12, "analysisVersion", integer(row.analysisVersion))
        measurementOpen = true
        currentNotes = row.notes
    }
    override suspend fun beginJump(row: ExportMeasurementRow) {
        beginMeasurement(row, "jump")
        raw("            \"primaryMetric\": "); metric(ExportMetric(row.primaryMetricKey ?: "", row.primaryMetricValue, row.primaryMetricUnit ?: "", ordinal = 0, isPrimary = true), 12, true, inline = true)
        raw("            \"primaryMetricContext\": ")
        if (row.primaryOrdinal == null) raw("null,\n") else context(ExportJumpContext(row.primarySide, row.primaryOrdinal, row.primaryDropHeightCm, row.primaryDistanceCm), 12, true, inline = true)
        firstAttempt = true
    }
    override suspend fun beginJumpAttempt(row: ExportJumpDetailRow) {
        if (firstAttempt) raw("            \"attempts\": [\n")
        if (!firstAttempt) raw(",\n") else firstAttempt = false
        raw("                {\n"); raw("                    \"context\": "); context(ExportJumpContext(row.side, row.attemptOrdinal, row.dropHeightCm, row.distanceCm), 20, true, inline = true); firstMetric = true
    }
    override suspend fun jumpMetric(row: ExportJumpDetailRow) {
        if (firstMetric) raw("                    \"metrics\": [\n")
        firstMetric = false
        bufferedMetric(ExportMetric(row.metricKey ?: "", row.metricValue, row.metricUnit ?: "", ordinal = row.metricOrdinal), 24)
    }
    override suspend fun endJumpAttempt() {
        if (firstMetric) raw("                    \"metrics\": []\n                }")
        else { flushBufferedMetric(); raw("                    ]\n                }") }
    }
    override suspend fun endJump() {
        if (firstAttempt) raw("            \"attempts\": [],\n            \"sessionSummaries\": [],\n") else raw("\n            ],\n            \"sessionSummaries\": [],\n")
        raw("            \"repetitions\": [],\n")
        prop(12, "notes", str(currentNotes), comma = false)
        raw("        }")
    }

    override suspend fun beginEncoder(row: ExportMeasurementRow) {
        beginMeasurement(row, "encoder")
        raw("            \"primaryMetric\": null,\n            \"primaryMetricContext\": null,\n            \"attempts\": [],\n")
        val summaries = buildList {
            row.totalRepetitions?.let { add(ExportEncoderSummary("TOTAL_REPETITIONS", it.toDouble(), "COUNT")) }
            row.validRepetitions?.let { add(ExportEncoderSummary("VALID_REPETITIONS", it.toDouble(), "COUNT")) }
            row.bestMcv?.let { add(ExportEncoderSummary("BEST_MCV", it, "METER_PER_SECOND")) }
            row.setVelocityLoss?.let { add(ExportEncoderSummary("SET_VELOCITY_LOSS", it, "PERCENT")) }
        }
        if (summaries.isEmpty()) {
            raw("            \"sessionSummaries\": [],\n")
        } else {
            raw("            \"sessionSummaries\": [\n")
            summaries.forEachIndexed { index, s -> if (index > 0) raw(",\n"); raw("                {\n"); prop(20, "key", str(s.key)); prop(20, "value", dbl(s.value)); prop(20, "unit", str(s.unit), false); raw("                }") }
            raw("\n            ],\n")
        }
        firstRepetition = true
    }
    override suspend fun beginEncoderRepetition(row: ExportEncoderDetailRow) {
        if (firstRepetition) raw("            \"repetitions\": [\n")
        if (!firstRepetition) raw(",\n") else firstRepetition = false
        raw("                {\n"); prop(20, "ordinal", row.repetitionOrdinal.toString()); prop(20, "quality", str(row.quality))
        val reasons = row.reasons.split(',').filter(String::isNotBlank).sorted()
        if (reasons.isEmpty()) {
            raw("                    \"qualityReasons\": [],\n")
        } else {
            raw("                    \"qualityReasons\": [\n")
            reasons.forEachIndexed { i, reason -> raw("                        ${str(reason)}${if (i < reasons.lastIndex) "," else ""}\n") }
            raw("                    ],\n")
        }
        firstMetric = true
    }
    override suspend fun encoderMetric(row: ExportEncoderDetailRow) {
        if (firstMetric) raw("                    \"metrics\": [\n")
        firstMetric = false
        bufferedMetric(ExportMetric(row.metricKey ?: "", row.metricValue, row.metricUnit ?: "", row.metricValidity, row.metricReason), 24)
    }
    override suspend fun endEncoderRepetition() {
        if (firstMetric) raw("                    \"metrics\": []\n                }")
        else { flushBufferedMetric(); raw("                    ]\n                }") }
    }
    override suspend fun endEncoder() {
        if (firstRepetition) raw("            \"repetitions\": [],\n") else raw("\n            ],\n")
        prop(12, "notes", str(currentNotes), comma = false)
        raw("        }")
    }

    /**
     * Cierra `measurements` exactamente una vez. `following` deja la coma que separa la sección de
     * la siguiente; los bytes son los mismos que produce el camino canónico, así que el documento
     * es idéntico se emita o no alguna de las familias posteriores.
     */
    private suspend fun closeMeasurements(following: Boolean) {
        if (measurementsClosed) return
        raw(if (firstMeasurement) "    \"measurements\": []" else "\n    ]")
        raw(if (following) ",\n" else "\n")
        measurementsClosed = true
    }

    private suspend fun closeFvp(following: Boolean) {
        if (firstFvp || fvpClosed) return
        raw("\n    ]")
        raw(if (following) ",\n" else "\n")
        fvpClosed = true
    }

    private suspend fun closeAngles(following: Boolean) {
        if (firstAngle || anglesClosed) return
        raw("\n    ]")
        raw(if (following) ",\n" else "\n")
        anglesClosed = true
    }

    private suspend fun closeAngleV2() {
        if (firstAngleV2 || angleV2Closed) return
        raw("\n    ]\n")
        angleV2Closed = true
    }

    /**
     * Cierra `measurements` (si seguía abierta) y emite la raíz F-V-P. El objeto se produce con el
     * mismo `Json` configurado del camino canónico y se re-indenta 8 espacios, de modo que un
     * documento con datos F-V-P es byte a byte idéntico entre el camino legacy y el streaming.
     */
    override suspend fun fvp(assessment: ExportFvpAssessment) {
        closeMeasurements(following = true)
        if (firstFvp) { raw("    \"fvp\": [\n"); firstFvp = false } else raw(",\n")
        raw(
            exportJson.encodeToString(ExportFvpAssessment.serializer(), assessment)
                .lineSequence().joinToString("\n") { "        $it" },
        )
    }

    override suspend fun finish() {
        // Cierra en orden las secciones que sigan abiertas: sin coma final y sin saltos de más,
        // de modo que cada combinación (vacío, sólo una familia, mixta) sigue siendo JSON válido.
        closeMeasurements(following = false)
        closeFvp(following = false)
        closeAngles(following = false)
        closeAngleV2()
        raw("}")
        out.flush()
    }

    /**
     * Cierra `fvp` (si seguía abierta) y emite la raíz de ángulos. Misma indentación y mismo `Json`
     * configurado que el camino canónico, así que con o sin ángulos el documento es idéntico byte a
     * byte entre ambos caminos.
     */
    override suspend fun angle(analysis: ExportAngleAnalysis) {
        closeMeasurements(following = true)
        closeFvp(following = true)
        if (firstAngle) { raw("    \"angleAnalyses\": [\n"); firstAngle = false } else raw(",\n")
        raw(
            exportJson.encodeToString(ExportAngleAnalysis.serializer(), analysis.canonicalizedAngle())
                .lineSequence().joinToString("\n") { "        $it" },
        )
    }

    /**
     * Cierra `angleAnalyses` (si seguía abierta) y emite la raíz V2. La colección va después de
     * todas las anteriores y usa el mismo `Json` configurado: el documento con datos V2 también es
     * byte a byte idéntico entre el camino canónico y el streaming.
     */
    override suspend fun angleV2(analysis: ExportAngleV2Analysis) {
        closeMeasurements(following = true)
        closeFvp(following = true)
        closeAngles(following = true)
        if (firstAngleV2) { raw("    \"angleV2\": [\n"); firstAngleV2 = false } else raw(",\n")
        raw(
            exportJson.encodeToString(ExportAngleV2Analysis.serializer(), analysis.canonicalizedV2Analysis())
                .lineSequence().joinToString("\n") { "        $it" },
        )
    }
}

internal class StreamingCsvDataExportWriter(delegate: java.io.Writer) : DataExportStreamWriter {
    private val out = CancellableExportWriter(delegate)
    private var row: ExportMeasurementRow? = null
    private var primaryMetric: ExportMetric? = null
    private var primaryContext: ExportJumpContext? = null
    private var primaryWritten = false
    private var pendingSummary = mutableListOf<ExportEncoderSummary>()
    private suspend fun line(values: List<Any?>) { out.write(values.joinToString(",", transform = { value -> CsvDataExportWriter.cell(value) })); out.write("\r\n") }
    private suspend fun metric(type: String, measurement: ExportMeasurementRow, context: ExportJumpContext?, repetition: ExportEncoderDetailRow?, quality: String?, reasons: String?, metric: ExportMetric) {
        requireFinite(measurement.loadKg, "loadKg"); requireFinite(metric.value, metric.key); requireFinite(context?.dropHeightCm, "dropHeightCm"); requireFinite(context?.distanceCm, "distanceCm")
        line(listOf(DATA_EXPORT_SCHEMA_VERSION, type, measurement.kind ?: if (measurement.protocolId != null) "jump" else "encoder", measurement.idText, measurement.dateTime.toUtcString(), measurement.athleteId?.toString(), measurement.athleteName, measurement.athleteBirthDate?.let { java.time.LocalDate.ofEpochDay(it).toString() }, measurement.athleteSex, measurement.testingSessionId?.toString(), measurement.groupNameSnapshot, measurement.protocolId, measurement.exercise, context?.ordinal, repetition?.repetitionOrdinal, context?.side, measurement.loadKg, context?.dropHeightCm, context?.distanceCm, measurement.analysisVersion, quality, reasons, metric.key, metric.value, metric.unit, metric.validity, metric.reason, metric.isPrimary, measurement.notes))
    }
    override suspend fun begin(exportedAt: String) { out.write(CsvDataExportWriter.header.joinToString(",", transform = { value -> CsvDataExportWriter.cell(value) })); out.write("\r\n") }
    /** CSV roots already project the only athlete fields needed by observations. */
    override suspend fun athlete(athlete: ExportAthlete) = Unit
    override suspend fun endAthletes() = Unit
    override suspend fun beginJump(row: ExportMeasurementRow) {
        this.row = row
        primaryMetric = row.primaryMetricKey?.let { key ->
            ExportMetric(key, row.primaryMetricValue, row.primaryMetricUnit ?: "", ordinal = 0, isPrimary = true)
        }
        primaryContext = row.primaryOrdinal?.let { ordinal ->
            ExportJumpContext(row.primarySide, ordinal, row.primaryDropHeightCm, row.primaryDistanceCm)
        }
        primaryWritten = false
    }
    override suspend fun beginJumpAttempt(row: ExportJumpDetailRow) = Unit
    private fun comparePrimaryToDetail(detail: ExportJumpDetailRow): Int {
        val primary = checkNotNull(primaryMetric)
        val primaryOrdinal = primaryContext?.ordinal ?: -1
        val ordinalResult = (primaryOrdinal).compareTo(detail.attemptOrdinal)
        if (ordinalResult != 0) return ordinalResult
        val keyResult = primary.key.compareTo(detail.metricKey ?: "")
        return if (keyResult != 0) keyResult else primary.unit.compareTo(detail.metricUnit ?: "")
    }
    private suspend fun writePrimaryIfNeeded() {
        if (primaryWritten) return
        val parent = checkNotNull(row)
        val primary = primaryMetric ?: return
        metric("JUMP_METRIC", parent, primaryContext, null, null, null, primary)
        primaryWritten = true
    }
    override suspend fun jumpMetric(row: ExportJumpDetailRow) {
        val parent = checkNotNull(this.row)
        if (!primaryWritten && primaryMetric != null && comparePrimaryToDetail(row) <= 0) writePrimaryIfNeeded()
        metric("JUMP_METRIC", parent, ExportJumpContext(row.side, row.attemptOrdinal, row.dropHeightCm, row.distanceCm), null, null, null, ExportMetric(row.metricKey ?: "", row.metricValue, row.metricUnit ?: "", ordinal = row.metricOrdinal))
    }
    override suspend fun endJumpAttempt() = Unit
    override suspend fun endJump() {
        writePrimaryIfNeeded()
        row = null
        primaryMetric = null
        primaryContext = null
    }
    override suspend fun beginEncoder(row: ExportMeasurementRow) {
        this.row = row
        pendingSummary = buildList {
            row.totalRepetitions?.let { add(ExportEncoderSummary("TOTAL_REPETITIONS", it.toDouble(), "COUNT")) }
            row.validRepetitions?.let { add(ExportEncoderSummary("VALID_REPETITIONS", it.toDouble(), "COUNT")) }
            row.bestMcv?.let { add(ExportEncoderSummary("BEST_MCV", it, "METER_PER_SECOND")) }
            row.setVelocityLoss?.let { add(ExportEncoderSummary("SET_VELOCITY_LOSS", it, "PERCENT")) }
        }.toMutableList()
    }
    override suspend fun beginEncoderRepetition(row: ExportEncoderDetailRow) { }
    override suspend fun encoderMetric(row: ExportEncoderDetailRow) { val parent = checkNotNull(this.row); metric("ENCODER_REPETITION_METRIC", parent, null, row, row.quality, row.reasons.split(',').filter(String::isNotBlank).sorted().joinToString("|"), ExportMetric(row.metricKey ?: "", row.metricValue, row.metricUnit ?: "", row.metricValidity, row.metricReason)) }
    override suspend fun endEncoderRepetition() = Unit
    override suspend fun endEncoder() {
        val parent = checkNotNull(row)
        pendingSummary.sortedWith(compareBy({ it.key }, { it.unit })).forEach {
            metric("ENCODER_SESSION_SUMMARY", parent, null, null, null, null, ExportMetric(it.key, it.value, it.unit))
        }
        row = null
    }
    override suspend fun fvp(assessment: ExportFvpAssessment) {
        CsvDataExportWriter.fvpObservations(assessment).forEach { observation ->
            out.write(CsvDataExportWriter.csvLine(observation))
            out.write("\r\n")
        }
    }
    override suspend fun angle(analysis: ExportAngleAnalysis) {
        CsvDataExportWriter.angleObservations(analysis).forEach { observation ->
            out.write(CsvDataExportWriter.csvLine(observation))
            out.write("\r\n")
        }
    }
    override suspend fun angleV2(analysis: ExportAngleV2Analysis) {
        // Mismas filas que el camino canónico (`observations`), en el mismo orden: después de la
        // familia de ángulos V1 y con la familia `angleV2` como discriminante.
        angleV2Observations(analysis).forEach { observation ->
            out.write(CsvDataExportWriter.csvLine(observation))
            out.write("\r\n")
        }
    }
    override suspend fun finish() { out.flush() }
}

/** Valor de `measurement_family` de las observaciones F-V-P. */
internal const val FVP_MEASUREMENT_FAMILY = "fvp"

/** Valor de `measurement_family` de las observaciones de análisis de ángulos. */
internal const val ANGLE_MEASUREMENT_FAMILY = "angle"

/** `metric_unit` de las observaciones textuales (el texto viaja en `metric_reason`). */
internal const val ANGLE_TEXT_UNIT = "TEXT"

/** `metric_unit` de un PTS de frame en microsegundos (el entero exacto viaja en `metric_value`). */
internal const val ANGLE_MICROSECOND_UNIT = "MICROSECOND"

/** `metric_unit` de las coordenadas de una marca y de las dimensiones del frame. */
internal const val ANGLE_PIXEL_UNIT = "PIXEL"

/** `metric_unit` de los grados y de la rotación. */
internal const val ANGLE_DEGREE_UNIT = "DEGREE"

/** `metric_unit` de los contadores y ordinales. */
internal const val ANGLE_COUNT_UNIT = "COUNT"

/** `metric_unit` de un color ARGB empaquetado (el entero exacto viaja en `metric_value`). */
internal const val ANGLE_ARGB_UNIT = "ARGB"

/** `metric_unit` de los atributos booleanos (el valor viaja en `metric_validity`). */
internal const val ANGLE_BOOLEAN_UNIT = "BOOLEAN"

/** `metric_unit` de las observaciones F-V-P textuales (el texto viaja en `metric_reason`). */
internal const val FVP_TEXT_UNIT = "TEXT"

/** `metric_unit` de los PTS F-V-P [µs] (el entero exacto viaja en `metric_value`). */
internal const val FVP_MICROSECOND_UNIT = "MICROSECOND"

private fun Long.toUtcString(): String = java.time.Instant.ofEpochMilli(this).toString()
private fun CsvObservation.toCsvLegacy() = ""
fun Double.toCanonicalExportText(): String { require(isFinite()); return String.format(Locale.US, "%.12g", this) }
