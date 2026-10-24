package com.openjump.app.data.export

import kotlinx.serialization.Serializable

const val DATA_EXPORT_CONTRACT = "openjump-graph-export"
const val DATA_EXPORT_SCHEMA_VERSION = 5
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION = 1
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_2 = 2
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_3 = 3

/**
 * `4` NO es una version F-V-P: el historico la uso para la estructura del documento Motion
 * (2->3, 3->4, 4->2 y FVP2->3 en los refs locales). Reutilizar ese numero haria ambigua la
 * identidad del documento, asi que B0/probe21 propuso `5` y aqui se rechaza `4` de forma explicita.
 */
private const val HISTORICAL_MOTION_DATA_EXPORT_SCHEMA_VERSION = 4

/** Versiones de schema que [DataExportDocument.validated] acepta como documento del grafo. */
private val ACCEPTED_DATA_EXPORT_SCHEMA_VERSIONS = setOf(
    DATA_EXPORT_SCHEMA_VERSION,
    LEGACY_DATA_EXPORT_SCHEMA_VERSION_3,
    LEGACY_DATA_EXPORT_SCHEMA_VERSION_2,
    LEGACY_DATA_EXPORT_SCHEMA_VERSION,
)

@Serializable
data class DataExportDocument(
    val contract: String = DATA_EXPORT_CONTRACT,
    val schemaVersion: Int = DATA_EXPORT_SCHEMA_VERSION,
    val exportedAt: String,
    val canonicalUnits: Boolean = true,
    val videoDataIncluded: Boolean = false,
    val athletes: List<ExportAthlete> = emptyList(),
    val measurements: List<ExportMeasurement> = emptyList(),
    /**
     * Colección F-V-P de nivel superior. El valor por defecto (`emptyList`) se omite del JSON
     * (`NEVER`), de modo que un documento sin datos F-V-P no gana la clave `fvp`. OJO: desde el
     * schema 5 el número de versión global cambia también para esos documentos; no se promete
     * igualdad byte a byte con exportaciones anteriores.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fvp: List<ExportFvpAssessment> = emptyList(),
)

@Serializable
data class ExportAthlete(
    val id: String,
    val name: String,
    val birthDate: String? = null,
    val sex: String? = null,
)

@Serializable
data class ExportMeasurement(
    val kind: String,
    val id: String,
    val dateTimeUtc: String,
    val athleteId: String? = null,
    val testingSessionId: String? = null,
    val groupNameSnapshot: String? = null,
    val protocolId: String? = null,
    val exercise: String? = null,
    val loadKg: Double? = null,
    val analysisVersion: Int? = null,
    val primaryMetric: ExportMetric? = null,
    val primaryMetricContext: ExportJumpContext? = null,
    val attempts: List<ExportJumpAttempt> = emptyList(),
    val sessionSummaries: List<ExportEncoderSummary> = emptyList(),
    val repetitions: List<ExportEncoderRepetition> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class ExportJumpAttempt(
    val context: ExportJumpContext,
    val metrics: List<ExportMetric> = emptyList(),
)

@Serializable
data class ExportJumpContext(
    val side: String? = null,
    val ordinal: Int,
    val dropHeightCm: Double? = null,
    val distanceCm: Double? = null,
)

@Serializable
data class ExportEncoderSummary(
    val key: String,
    val value: Double? = null,
    val unit: String,
)

@Serializable
data class ExportEncoderRepetition(
    val ordinal: Int,
    val quality: String,
    val qualityReasons: List<String> = emptyList(),
    val metrics: List<ExportMetric> = emptyList(),
)

@Serializable
data class ExportMetric(
    val key: String,
    val value: Double? = null,
    val unit: String,
    val validity: String? = null,
    val reason: String? = null,
    val ordinal: Int? = null,
    val isPrimary: Boolean = false,
)

/**
 * Evaluación F-V-P exportada. Los valores calculados son la foto de auditoría persistida; los
 * que pueden ser NaN se guardan como `null`.
 */
@Serializable
data class ExportFvpAssessment(
    val id: String,
    val sessionKey: String,
    val protocolId: String,
    val dateTimeUtc: String,
    val bodyMassKg: Double,
    val bodyMassSource: String,
    val pushOffMode: String,
    val pushOffM: Double,
    val pushOffSource: String,
    val algorithmVersion: String,
    val methodVersion: String,
    val conditionCount: Int,
    val f0N: Double,
    val slopeFv: Double,
    val sfvNsPerM: Double,
    val v0Mps: Double? = null,
    val vMinMps: Double,
    val vMaxMps: Double,
    val fMinN: Double,
    val fMaxN: Double,
    val warningsCsv: String,
    val athleteId: String? = null,
    val testingSessionId: String? = null,
    val pushOffLandmark: String? = null,
    val pushOffLandmarkHeightCm: Double? = null,
    val pushOffProtocol: String? = null,
    val pushOffMeasuredAtUtc: String? = null,
    val pushOffCrouchCm: Double? = null,
    val pushOffExtendedCm: Double? = null,
    val sourceFps: Double? = null,
    val f0RelNPerKg: Double? = null,
    val pmaxW: Double? = null,
    val pmaxRelWPerKg: Double? = null,
    val r2: Double? = null,
    val notes: String? = null,
    // Procedencia B0 (esquema 5). Un campo por columna Room, 1:1 y sin derivar: el epoch de
    // milisegundos viaja como ISO-8601 UTC (igual que `pushOffMeasuredAtUtc`) y los enums
    // conservan su valor persistido (`UNKNOWN` incluido, nunca rellenado por el escritor).
    val acquiredAtUtc: String? = null,
    val acquisitionContextKind: String = "UNKNOWN",
    val acquisitionContextKey: String? = null,
    val bodyMassMeasurement: String = "UNKNOWN",
    val bodyMassMeasuredAtUtc: String? = null,
    val conditions: List<ExportFvpCondition> = emptyList(),
)

@Serializable
data class ExportFvpCondition(
    val ordinal: Int,
    val conditionKey: String,
    val addedLoadKg: Double,
    val totalMassKg: Double,
    val pushOffM: Double,
    val selectedHeightM: Double,
    val selectedAttemptOrdinal: Int? = null,
    val addedLoadSource: String = "UNKNOWN",
    val addedLoadMeasuredAtUtc: String? = null,
    val attempts: List<ExportFvpAttempt> = emptyList(),
)

@Serializable
data class ExportFvpAttempt(
    val ordinal: Int,
    val heightM: Double,
    val method: String,
    val valid: Boolean,
    val flightTimeS: Double? = null,
    val sourceFps: Double? = null,
    val sourceAssessmentId: String? = null,
    val sourceAttemptOrdinal: Int? = null,
    val sourceProtocolId: String? = null,
    val exclusionReason: String? = null,
    val notes: String? = null,
    val sourceRecordedAtUtc: String? = null,
    val sourceAthleteId: String? = null,
    val acquiredAtUtc: String? = null,
    val reuseConfirmedAtUtc: String? = null,
    val reuseConfirmationVersion: String? = null,
    // PTS [µs] exactos: `Long` y nunca `Double` (9007199254740993 no es representable en Double).
    val takeoffPtsUs: Long? = null,
    val takeoffPreviousPtsUs: Long? = null,
    val takeoffNextPtsUs: Long? = null,
    val landingPtsUs: Long? = null,
    val landingPreviousPtsUs: Long? = null,
    val landingNextPtsUs: Long? = null,
    val timeMapping: String = "UNKNOWN",
    val timeMappingEvidenceKey: String? = null,
)

/** Canonical, deterministic observation used by the long CSV writer. */data class CsvObservation(
    val observationType: String,
    val measurementFamily: String,
    val measurementId: String,
    val dateTimeUtc: String,
    val athleteId: String?,
    val athleteName: String?,
    val athleteBirthDate: String?,
    val athleteSex: String?,
    val testingSessionId: String?,
    val testingGroupName: String?,
    val protocolId: String?,
    val exercise: String?,
    val attemptOrdinal: Int?,
    val repetitionOrdinal: Int?,
    val side: String?,
    val loadKg: Double?,
    val dropHeightCm: Double?,
    val distanceCm: Double?,
    val analysisVersion: Int?,
    val repetitionQuality: String?,
    val qualityReasons: String?,
    val metricKey: String,
    val metricValue: Double?,
    val metricUnit: String,
    val metricValidity: String?,
    val metricReason: String?,
    val isPrimary: Boolean,
    val notes: String? = null,
    /**
     * Valor entero EXACTO de `metric_value`. Cuando esta presente tiene prioridad sobre
     * [metricValue] y se renderiza con `Long.toString()` (decimal canonico, nunca `1.0E7`).
     * Va al final con default para no romper los constructores posicionales existentes.
     */
    val metricLongValue: Long? = null,
)

internal fun requireFinite(value: Double?, field: String): Double? {
    require(value == null || value.isFinite()) { "$field must be finite" }
    return value
}

/**
 * Invariante DURA del perfil F-V (`FvpCalculator.fit` define `sfv = -slope`): un par exportado no
 * puede llevar los dos signos iguales ni magnitudes distintas. Es la misma regla que exige
 * `FvpDao.save` y la restauracion de backups; aqui se falla en cerrado ANTES de escribir JSON/CSV,
 * de modo que un documento editado a mano o una fila que salto la validacion (p. ej.
 * `insertAssessmentRaw`) no pueden publicar un par imposible.
 */
internal fun requireFvpOppositeSlopeSigns(slopeFv: Double, sfvNsPerM: Double, label: String) {
    require(sfvNsPerM == -slopeFv) {
        "fvp $label sfvNsPerM must be exactly -slopeFv (sfvNsPerM=$sfvNsPerM, slopeFv=$slopeFv)"
    }
}

internal fun DataExportDocument.validated(): DataExportDocument {
    require(contract == DATA_EXPORT_CONTRACT)
    require(schemaVersion != HISTORICAL_MOTION_DATA_EXPORT_SCHEMA_VERSION) {
        "export schemaVersion 4 was used historically for the Motion document structure and cannot be read as an OpenJump graph export"
    }
    require(schemaVersion in ACCEPTED_DATA_EXPORT_SCHEMA_VERSIONS) {
        "unsupported export schemaVersion $schemaVersion"
    }
    require(canonicalUnits && !videoDataIncluded)
    athletes.forEach { require(it.id.isNotBlank()) }
    measurements.forEach { measurement ->
        require(measurement.id.isNotBlank())
        requireFinite(measurement.loadKg, "loadKg")
        measurement.primaryMetric?.let { requireFinite(it.value, "primary metric") }
        measurement.attempts.forEach { attempt ->
            requireFinite(attempt.context.dropHeightCm, "dropHeightCm")
            requireFinite(attempt.context.distanceCm, "distanceCm")
            attempt.metrics.forEach { metric -> requireFinite(metric.value, metric.key) }
        }
        measurement.sessionSummaries.forEach { requireFinite(it.value, it.key) }
        measurement.repetitions.forEach { repetition ->
            repetition.metrics.forEach { metric -> requireFinite(metric.value, metric.key) }
        }
    }
    fvp.forEach { assessment ->
        require(assessment.id.isNotBlank())
        require(assessment.sessionKey.isNotBlank())
        require(assessment.protocolId.isNotBlank())
        require(assessment.conditionCount >= 1)
        requireFinite(assessment.bodyMassKg, "fvp bodyMassKg")
        requireFinite(assessment.pushOffM, "fvp pushOffM")
        requireFinite(assessment.pushOffLandmarkHeightCm, "fvp pushOffLandmarkHeightCm")
        requireFinite(assessment.pushOffCrouchCm, "fvp pushOffCrouchCm")
        requireFinite(assessment.pushOffExtendedCm, "fvp pushOffExtendedCm")
        requireFinite(assessment.sourceFps, "fvp sourceFps")
        requireFinite(assessment.f0N, "fvp f0N")
        requireFinite(assessment.f0RelNPerKg, "fvp f0RelNPerKg")
        requireFinite(assessment.slopeFv, "fvp slopeFv")
        requireFinite(assessment.sfvNsPerM, "fvp sfvNsPerM")
        // P2 (Wave 4a): la finitud no basta; el par de signo CONTRARIO es la invariante del motor.
        requireFvpOppositeSlopeSigns(assessment.slopeFv, assessment.sfvNsPerM, "assessment ${assessment.id}")
        requireFinite(assessment.v0Mps, "fvp v0Mps")
        requireFinite(assessment.pmaxW, "fvp pmaxW")
        requireFinite(assessment.pmaxRelWPerKg, "fvp pmaxRelWPerKg")
        requireFinite(assessment.r2, "fvp r2")
        requireFinite(assessment.vMinMps, "fvp vMinMps")
        requireFinite(assessment.vMaxMps, "fvp vMaxMps")
        requireFinite(assessment.fMinN, "fvp fMinN")
        requireFinite(assessment.fMaxN, "fvp fMaxN")
        assessment.conditions.forEach { condition ->
            require(condition.conditionKey.isNotBlank())
            requireFinite(condition.addedLoadKg, "fvp addedLoadKg")
            requireFinite(condition.totalMassKg, "fvp totalMassKg")
            requireFinite(condition.pushOffM, "fvp pushOffM")
            requireFinite(condition.selectedHeightM, "fvp selectedHeightM")
            condition.attempts.forEach { attempt ->
                require(attempt.method.isNotBlank())
                requireFinite(attempt.heightM, "fvp heightM")
                requireFinite(attempt.flightTimeS, "fvp flightTimeS")
                requireFinite(attempt.sourceFps, "fvp sourceFps")
            }
        }
    }
    return this
}

internal fun DataExportDocument.canonicalized(): DataExportDocument = copy(
    schemaVersion = DATA_EXPORT_SCHEMA_VERSION,
    athletes = athletes.sortedBy { it.id },
    fvp = fvp.sortedWith(compareBy({ it.dateTimeUtc }, { it.id })).map { assessment ->
        assessment.copy(
            conditions = assessment.conditions.sortedBy { it.ordinal }.map { condition ->
                condition.copy(attempts = condition.attempts.sortedBy { it.ordinal })
            },
        )
    },
    measurements = measurements.sortedWith(compareBy({ it.dateTimeUtc }, { it.kind }, { it.id })).map { measurement ->
        val sortedAttempts = measurement.attempts.sortedBy { it.context.ordinal }
        val primary = measurement.primaryMetric
        val matchingAttemptIndex = if (primary == null) -1 else sortedAttempts.indexOfFirst { attempt ->
            attempt.metrics.any { metric ->
                metric.ordinal == 0 && metric.key == primary.key &&
                    metric.unit == primary.unit && metric.value == primary.value
            }
        }
        measurement.copy(
            primaryMetricContext = sortedAttempts.getOrNull(matchingAttemptIndex)?.context
                ?: measurement.primaryMetricContext,
            attempts = sortedAttempts.mapIndexed { index, attempt ->
                val metrics = attempt.metrics.sortedWith(compareBy({ it.ordinal ?: -1 }, { it.key }, { it.unit }))
                attempt.copy(metrics = if (index == matchingAttemptIndex) {
                    metrics.removeFirstMatching { metric ->
                        primary?.let {
                            metric.ordinal == 0 && metric.key == it.key &&
                                metric.unit == it.unit && metric.value == it.value
                        } == true
                    }
                } else metrics)
            },
            sessionSummaries = measurement.sessionSummaries.sortedBy {
                listOf("TOTAL_REPETITIONS", "VALID_REPETITIONS", "BEST_MCV", "SET_VELOCITY_LOSS").indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index }
            },
            repetitions = measurement.repetitions.sortedBy { it.ordinal }.map { repetition ->
                repetition.copy(
                    qualityReasons = repetition.qualityReasons.sorted(),
                    metrics = repetition.metrics.sortedWith(compareBy({ it.key }, { it.ordinal ?: -1 }, { it.unit })),
                )
            },
        )
    },
)

private fun <T> List<T>.removeFirstMatching(predicate: (T) -> Boolean): List<T> {
    val index = indexOfFirst(predicate)
    return if (index < 0) this else toMutableList().also { it.removeAt(index) }
}
