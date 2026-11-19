package com.openjump.app.data.export

import com.openjump.app.angles.AngleGeometry
import com.openjump.app.angles.AngleMarkers
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AnglePoint
import java.time.Instant
import kotlinx.serialization.Serializable

const val DATA_EXPORT_CONTRACT = "openjump-graph-export"
/**
 * `7` publica el agregado de ángulos multi-fotograma (V2) como colección de nivel superior
 * ([DataExportDocument.angleV2]). Respecto a `6` sólo cambia eso y el número de versión: las
 * familias jump/encoder/F-V-P/ángulos V1 conservan su forma, así que una exportación sin datos V2
 * es idéntica a la de `6` salvo `schemaVersion`.
 */
const val DATA_EXPORT_SCHEMA_VERSION = 7
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION = 1
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_2 = 2
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_3 = 3
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_5 = 5
private const val LEGACY_DATA_EXPORT_SCHEMA_VERSION_6 = 6

/**
 * `4` NO es una version F-V-P: el historico la uso para la estructura del documento Motion
 * (2->3, 3->4, 4->2 y FVP2->3 en los refs locales). Reutilizar ese numero haria ambigua la
 * identidad del documento, asi que B0/probe21 propuso `5` y aqui se rechaza `4` de forma explicita.
 */
private const val HISTORICAL_MOTION_DATA_EXPORT_SCHEMA_VERSION = 4

/** Versiones de schema que [DataExportDocument.validated] acepta como documento del grafo. */
private val ACCEPTED_DATA_EXPORT_SCHEMA_VERSIONS = setOf(
    DATA_EXPORT_SCHEMA_VERSION,
    LEGACY_DATA_EXPORT_SCHEMA_VERSION_6,
    LEGACY_DATA_EXPORT_SCHEMA_VERSION_5,
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
    /**
     * Colección de nivel superior de análisis manuales de ángulos (schema 6). Igual que [fvp], el
     * valor por defecto se omite del JSON para que un documento sin ángulos no gane la clave.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val angleAnalyses: List<ExportAngleAnalysis> = emptyList(),
    /**
     * Colección de nivel superior del agregado de ángulos multi-fotograma (V2, schema 7). Igual que
     * [fvp] y [angleAnalyses], el valor por defecto se omite del JSON: un documento sin raíces V2 no
     * gana la clave `angleV2`.
     *
     * Convive con [angleAnalyses] (V1, un único frame por raíz) sin fundirlas: una raíz V2 puede
     * compartir `id` numérico con una de V1, así que la identidad de tipo es la colección más el
     * discriminador persistido [ExportAngleV2Analysis.analysisFormat]. Ni el vídeo ni las imágenes
     * anotadas se describen aquí: ver la política de privacidad de [ExportAngleV2Analysis].
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val angleV2: List<ExportAngleV2Analysis> = emptyList(),
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

/** `kind` de [ExportAngleVideoReference] cuando el vídeo se identifica por la raíz del análisis. */
const val ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT = "ANALYSIS_ROOT"

/**
 * Referencia al vídeo de un análisis de ángulos **sin datos privados de la fuente**.
 *
 * La política de exportación no publica `video_uri`, `source_identity`, el hash del contenido ni
 * el `session_key` del editor (que la app deriva de la URI y del frame): rutas, nombres de fichero
 * y hashes pueden actuar como identificadores personales. La correlación se hace SOLO por la
 * identidad estable y anónima de la raíz ([ExportAngleAnalysis.id]); este objeto únicamente
 * declara cómo debe interpretarse esa identidad.
 *
 * Limitación documentada: el receptor puede emparejar el análisis con su vídeo sólo si ya dispone
 * de él y conoce a qué raíz corresponde; el export no permite reconstruir la ruta ni el nombre del
 * fichero, así que no sirve para localizar el original.
 */
@Serializable
data class ExportAngleVideoReference(
    val kind: String = ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT,
)

/**
 * Raíz exportable de un análisis manual de ángulos 2D: un único frame y su tiempo canónico,
 * la geometría de presentación con la que se midieron las marcas y las marcas en sí.
 *
 * El PTS del frame viaja como `Long` de microsegundos EXACTO (`sourcePtsUs`): convertirlo a
 * `Double` perdería enteros por encima de 2^53. La tabla de origen no guarda resultados
 * derivados, así que los grados se recalculan al exportar con [AngleGeometry] (ver
 * [canonicalizedAngle] y [ExportAngleMark.measured]).
 *
 * El `session_key` del editor NO se exporta: la app lo deriva de la URI del vídeo y del frame, así
 * que reidentifica la fuente privada. La clave estable del análisis es [id].
 */
@Serializable
data class ExportAngleAnalysis(
    /** Identidad estable y anónima de la raíz (`angle_analyses.id` en texto). No contiene la URI. */
    val id: String,
    val createdAtUtc: String,
    val updatedAtUtc: String,
    val revision: Int,
    val athleteId: String? = null,
    /** Frame canónico del análisis dentro del vídeo. */
    val frameIndex: Int,
    /** PTS del frame canónico en microsegundos, `Long` exacto. */
    val sourcePtsUs: Long,
    /** Dimensiones tal y como vienen codificadas en el fichero. */
    val encodedWidth: Int,
    val encodedHeight: Int,
    /** Sistema de coordenadas de las marcas (píxeles de presentación, post-rotación). */
    val presentationWidth: Int,
    val presentationHeight: Int,
    val rotationDegrees: Int,
    val geometryVersion: Int,
    val video: ExportAngleVideoReference = ExportAngleVideoReference(),
    val marks: List<ExportAngleMark> = emptyList(),
)

/**
 * Marca manual exportable: tres puntos A, B (vértice) y C en píxeles de presentación del frame
 * de su raíz, más el ángulo derivado cuando es medible.
 *
 * [degrees] es el ángulo sin redondear en [0, 180]; si el ángulo no está definido viaja `null`
 * con un [invalidReason] tipado (`AngleInvalidReason.name`), nunca un 0° falso.
 */
@Serializable
data class ExportAngleMark(
    /** Identidad persistente de la marca (`angle_marks.id` en texto). */
    val id: String,
    val ordinal: Int,
    val name: String,
    val visible: Boolean,
    /** Color ARGB empaquetado tal como lo persiste Android (entero con signo). */
    val colorArgb: Int,
    val pointAX: Double,
    val pointAY: Double,
    val pointBX: Double,
    val pointBY: Double,
    val pointCX: Double,
    val pointCY: Double,
    val degrees: Double? = null,
    val invalidReason: String? = null,
)

/**
 * Recalcula los grados de la marca con [AngleGeometry] (sin redondear). Un ángulo no medible
 * deja `degrees` en `null` y publica el motivo tipado en `invalidReason`.
 */
internal fun ExportAngleMark.measured(): ExportAngleMark = when (
    val measurement = AngleGeometry.measure(
        AngleMarkers(AnglePoint(pointAX, pointAY), AnglePoint(pointBX, pointBY), AnglePoint(pointCX, pointCY)),
    )
) {
    is AngleMeasurement.Measured -> copy(degrees = measurement.degrees, invalidReason = null)
    is AngleMeasurement.Invalid -> copy(degrees = null, invalidReason = measurement.reason.name)
}

/** Aplica [measured] a todas las marcas en el orden actual de la lista. */
internal fun ExportAngleAnalysis.measuredAngles(): ExportAngleAnalysis = copy(marks = marks.map { it.measured() })

/**
 * Forma canónica de una raíz de ángulos: marcas en el orden estable de la consulta (`ordinal`,
 * luego `id`) y grados recalculados. Es idempotente, así que los caminos canónico y streaming
 * publican exactamente la misma raíz aunque el llamador entregue las marcas en otro orden.
 */
internal fun ExportAngleAnalysis.canonicalizedAngle(): ExportAngleAnalysis =
    copy(marks = marks.sortedWith(compareBy({ it.ordinal }, { it.id }))).measuredAngles()

/**
 * Construye la raíz exportable a partir de la fila keyset y de las filas planas de sus marcas.
 * Las marcas se derivan aquí (la tabla no guarda grados) y viajan en el orden estable de la
 * consulta: `ordinal`, luego `id`.
 */
internal fun ExportAngleRootRow.toExportAngleAnalysis(markRows: List<ExportAngleDetailRow>): ExportAngleAnalysis {
    require(markRows.all { it.analysisId == id }) {
        "angle detail rows must belong to analysis $idText"
    }
    return ExportAngleAnalysis(
        id = idText,
        createdAtUtc = Instant.ofEpochMilli(createdAt).toString(),
        updatedAtUtc = Instant.ofEpochMilli(updatedAt).toString(),
        revision = revision,
        athleteId = athleteId?.toString(),
        frameIndex = frameIndex,
        sourcePtsUs = sourcePtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        // Ni URI, ni `source_identity`, ni hash de contenido: solo la clave anónima de la raíz.
        video = ExportAngleVideoReference(),
        marks = markRows.filter { it.markId != null }.map { row ->
            ExportAngleMark(
                id = requireNotNull(row.markId).toString(),
                ordinal = requireNotNull(row.markOrdinal) { "angle mark ${row.markId} has no ordinal" },
                name = requireNotNull(row.markName) { "angle mark ${row.markId} has no name" },
                visible = row.markVisible ?: true,
                colorArgb = requireNotNull(row.markColorArgb) { "angle mark ${row.markId} has no color" },
                pointAX = requireNotNull(row.pointAX) { "angle mark ${row.markId} has no pointAX" },
                pointAY = requireNotNull(row.pointAY) { "angle mark ${row.markId} has no pointAY" },
                pointBX = requireNotNull(row.pointBX) { "angle mark ${row.markId} has no pointBX" },
                pointBY = requireNotNull(row.pointBY) { "angle mark ${row.markId} has no pointBY" },
                pointCX = requireNotNull(row.pointCX) { "angle mark ${row.markId} has no pointCX" },
                pointCY = requireNotNull(row.pointCY) { "angle mark ${row.markId} has no pointCY" },
            ).measured()
        },
    )
}

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
    angleAnalyses.forEach { analysis ->
        require(analysis.id.isNotBlank()) { "angle analysis id must not be blank" }
        require(analysis.createdAtUtc.isNotBlank()) {
            "angle analysis ${analysis.id} createdAtUtc must not be blank"
        }
        require(analysis.updatedAtUtc.isNotBlank()) {
            "angle analysis ${analysis.id} updatedAtUtc must not be blank"
        }
        require(analysis.frameIndex >= 0) {
            "angle analysis ${analysis.id} frameIndex must be >= 0"
        }
        require(analysis.encodedWidth > 0 && analysis.encodedHeight > 0) {
            "angle analysis ${analysis.id} encoded dimensions must be positive"
        }
        require(analysis.presentationWidth > 0 && analysis.presentationHeight > 0) {
            "angle analysis ${analysis.id} presentation dimensions must be positive"
        }
        require(analysis.geometryVersion >= 1) {
            "angle analysis ${analysis.id} geometryVersion must be >= 1"
        }
        require(analysis.video.kind.isNotBlank()) {
            "angle analysis ${analysis.id} video kind must not be blank"
        }
        val ordinals = mutableSetOf<Int>()
        val markIds = mutableSetOf<String>()
        analysis.marks.forEach { mark ->
            require(mark.id.isNotBlank()) {
                "angle analysis ${analysis.id} mark id must not be blank"
            }
            require(markIds.add(mark.id)) {
                "angle analysis ${analysis.id} repeats mark id ${mark.id}"
            }
            require(mark.ordinal >= 0) {
                "angle analysis ${analysis.id} mark ${mark.id} ordinal must be >= 0"
            }
            require(ordinals.add(mark.ordinal)) {
                "angle analysis ${analysis.id} repeats mark ordinal ${mark.ordinal}"
            }
            require(mark.name.isNotBlank()) {
                "angle analysis ${analysis.id} mark ${mark.id} name must not be blank"
            }
            requireFinite(mark.pointAX, "angle mark ${mark.id} pointAX")
            requireFinite(mark.pointAY, "angle mark ${mark.id} pointAY")
            requireFinite(mark.pointBX, "angle mark ${mark.id} pointBX")
            requireFinite(mark.pointBY, "angle mark ${mark.id} pointBY")
            requireFinite(mark.pointCX, "angle mark ${mark.id} pointCX")
            requireFinite(mark.pointCY, "angle mark ${mark.id} pointCY")
            require(mark.degrees == null || mark.invalidReason == null) {
                "angle mark ${mark.id} cannot be measurable and invalid at once"
            }
            mark.degrees?.let { degrees ->
                require(degrees.isFinite() && degrees >= 0.0 && degrees <= 180.0) {
                    "angle mark ${mark.id} degrees must be finite within [0, 180], was $degrees"
                }
            }
        }
    }
    // Agregado V2: valida con su propio espejo (`validatedV2`), que exige las mismas invariantes a
    // la raíz, a cada fotograma y a las marcas de cada fotograma (ordinal único POR fotograma).
    angleV2.forEach { analysis -> analysis.validatedV2() }
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
    // Mismo orden que el keyset de `angle_analyses(createdAt, id)` del path streaming: el instante
    // se compara como instante (no como texto ISO), porque "...:00Z" y "...:00.001Z" no ordenan
    // lexicográficamente igual que sus milisegundos. Así ambos caminos publican la misma lista.
    angleAnalyses = angleAnalyses.sortedWith(
        compareBy<ExportAngleAnalysis>(
            { runCatching { Instant.parse(it.createdAtUtc) }.getOrNull() },
            { it.id },
        ),
    ).map { analysis ->
        // Los grados NO se persisten: se recalculan aquí con AngleGeometry, de modo que cualquier
        // camino que canonicice el documento (writer JSON incluido) publica la medición derivada.
        analysis.canonicalizedAngle()
    },
    // Raíces V2 por instante de `createdAt` y luego por `id` (mismo criterio que el keyset
    // `createdAt, id` del repositorio), con fotogramas y marcas en su orden estable y los grados
    // recalculados: el camino canónico y el streaming publican la misma lista.
    angleV2 = angleV2.canonicalizedV2Analyses(),
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
