package com.openjump.app.data.export

import kotlinx.serialization.Serializable

const val DATA_EXPORT_CONTRACT = "openjump-graph-export"
const val DATA_EXPORT_SCHEMA_VERSION = 2
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION = 1

@Serializable
data class DataExportDocument(
    val contract: String = DATA_EXPORT_CONTRACT,
    val schemaVersion: Int = DATA_EXPORT_SCHEMA_VERSION,
    val exportedAt: String,
    val canonicalUnits: Boolean = true,
    val videoDataIncluded: Boolean = false,
    val athletes: List<ExportAthlete> = emptyList(),
    val measurements: List<ExportMeasurement> = emptyList(),
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

/** Canonical, deterministic observation used by the long CSV writer. */
data class CsvObservation(
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
)

internal fun requireFinite(value: Double?, field: String): Double? {
    require(value == null || value.isFinite()) { "$field must be finite" }
    return value
}

internal fun DataExportDocument.validated(): DataExportDocument {
    require(contract == DATA_EXPORT_CONTRACT)
    require(schemaVersion == DATA_EXPORT_SCHEMA_VERSION || schemaVersion == LEGACY_DATA_EXPORT_SCHEMA_VERSION)
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
    return this
}

internal fun DataExportDocument.canonicalized(): DataExportDocument = copy(
    schemaVersion = DATA_EXPORT_SCHEMA_VERSION,
    athletes = athletes.sortedBy { it.id },
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
