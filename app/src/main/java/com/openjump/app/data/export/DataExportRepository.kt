package com.openjump.app.data.export

import androidx.room.withTransaction
import com.openjump.app.data.JumpDatabase
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streaming analytical export. The legacy DAO snapshot remains available only for compatibility tests. */
class DataExportRepository private constructor(
    private val database: JumpDatabase?,
    private val compatibilityDao: DataExportDao?,
) {
    constructor(database: JumpDatabase) : this(database, null)
    /** Compatibility constructor for existing snapshot-focused tests. */
    constructor(dao: DataExportDao) : this(null, dao)
    private val dao get() = database?.dataExportDao() ?: requireNotNull(compatibilityDao)

    suspend fun snapshot(): DataExportDocument = dao.snapshot().toDocument()

    suspend fun writeJson(writer: Writer, exportedAt: Instant = Instant.now()) = write(writer, exportedAt, StreamingJsonDataExportWriter(writer))
    suspend fun writeCsv(writer: Writer, exportedAt: Instant = Instant.now()) = write(writer, exportedAt, StreamingCsvDataExportWriter(writer))

    private suspend fun write(writer: Writer, exportedAt: Instant, stream: DataExportStreamWriter) {
        requireNotNull(database) { "Streaming export requires JumpDatabase" }.withTransaction {
            stream.begin(exportedAt.toString())
            var athleteCursor: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.athletePage(athleteCursor, DATA_EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { row ->
                    currentCoroutineContext().ensureActive()
                    stream.athlete(ExportAthlete(row.id.toString(), row.displayName, row.birthDate?.let { LocalDate.ofEpochDay(it).toString() }, row.sex))
                }
                athleteCursor = page.last().id.toString()
            }
            stream.endAthletes()
            var cursorDate: Long? = null
            var cursorKind = ""
            var cursorId = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val roots = dao.measurementPage(cursorDate, cursorKind, cursorId, DATA_EXPORT_PAGE_SIZE)
                if (roots.isEmpty()) break
                val jumps = roots.filter { it.kind == "jump" }
                val encoders = roots.filter { it.kind == "encoder" }
                val csv = stream is StreamingCsvDataExportWriter
                val jumpDetails = DetailCursor(jumps.map { it.id }, isJump = true, csv = csv)
                val encoderDetails = DetailCursor(encoders.map { it.id }, isJump = false, csv = false)
                roots.forEach { root ->
                    when (root.kind) {
                        "jump" -> {
                            stream.beginJump(root)
                            writeJumpDetails(root, jumpDetails, stream)
                            stream.endJump()
                        }
                        "encoder" -> {
                            stream.beginEncoder(root)
                            writeEncoderDetails(root, encoderDetails, stream)
                            stream.endEncoder()
                        }
                        else -> error("Unknown export family ${root.kind}")
                    }
                }
                val last = roots.last()
                cursorDate = last.dateTime
                cursorKind = last.kind ?: if (last.protocolId == null) "encoder" else "jump"
                cursorId = last.idText
            }
            // Familia F-V-P: cursor propio, emitida después de jump/encoder (no comparte forma).
            var fvpDate: Long? = null
            var fvpId = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val roots = dao.fvpPage(fvpDate, fvpId, DATA_EXPORT_PAGE_SIZE)
                if (roots.isEmpty()) break
                val models = fvpAssessments(roots, fvpDetailsForPage(roots.map { it.id })).associateBy { it.id }
                roots.forEach { root ->
                    currentCoroutineContext().ensureActive()
                    stream.fvp(models.getValue(root.idText))
                }
                val last = roots.last()
                fvpDate = last.dateTime
                fvpId = last.idText
            }
            stream.finish()
        }
    }

    /** Detalles de una página de raíces F-V-P, por cursor keyset (sin un SELECT por raíz). */
    private suspend fun fvpDetailsForPage(parentIds: List<Long>): List<ExportFvpDetailRow> {
        val details = mutableListOf<ExportFvpDetailRow>()
        var afterDate: Long? = null
        var afterIdText = ""
        var afterConditionOrdinal = -1
        var afterConditionId = -1L
        var afterAttemptOrdinal = -1
        var afterAttemptId = -1L
        while (true) {
            val page = dao.fvpDetailPage(
                parentIds, afterDate, afterIdText, afterConditionOrdinal, afterConditionId,
                afterAttemptOrdinal, afterAttemptId, DATA_EXPORT_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            details += page
            val last = page.last()
            afterDate = last.parentDateTime
            afterIdText = last.parentIdText
            afterConditionOrdinal = last.conditionOrdinal
            afterConditionId = last.conditionId
            afterAttemptOrdinal = last.attemptOrdinal ?: -1
            afterAttemptId = last.attemptId ?: -1L
        }
        return details
    }

    private suspend fun writeJumpDetails(root: ExportMeasurementRow, cursor: DetailCursor, stream: DataExportStreamWriter) {
        var activeAttempt: Long? = null
        cursor.forEach(root.id) { item ->
            val row = item as ExportJumpDetailRow
            if (activeAttempt != row.attemptId) {
                if (activeAttempt != null) stream.endJumpAttempt()
                stream.beginJumpAttempt(row)
                activeAttempt = row.attemptId
            }
            val duplicate = root.primaryAttemptId == row.attemptId && row.metricOrdinal == 0 &&
                row.metricKey == root.primaryMetricKey && row.metricUnit == root.primaryMetricUnit && row.metricValue == root.primaryMetricValue
            if (row.metricPresent != 0 && !duplicate) stream.jumpMetric(row)
        }
        if (activeAttempt != null) stream.endJumpAttempt()
    }

    private suspend fun writeEncoderDetails(root: ExportMeasurementRow, cursor: DetailCursor, stream: DataExportStreamWriter) {
        var activeRepetition: Long? = null
        cursor.forEach(root.id) { item ->
            val row = item as ExportEncoderDetailRow
            if (activeRepetition != row.repetitionId) {
                if (activeRepetition != null) stream.endEncoderRepetition()
                stream.beginEncoderRepetition(row)
                activeRepetition = row.repetitionId
            }
            if (row.metricPresent != 0) stream.encoderMetric(row)
        }
        if (activeRepetition != null) stream.endEncoderRepetition()
    }

    private inner class DetailCursor(
        private val ids: List<Long>,
        private val isJump: Boolean,
        private val csv: Boolean,
    ) {
        private var page = emptyList<Any>()
        private var index = 0
        private var exhausted = ids.isEmpty()
        private var afterDate: Long? = null
        private var afterIdText = ""
        private var afterAttemptOrdinal = -1
        private var afterAttemptId = -1L
        private var afterMetricPresent = -1
        private var afterMetricOrdinal = -1
        private var afterMetricKey = ""
        private var afterMetricUnit = ""
        private var afterRepetitionOrdinal = -1
        private var afterRepetitionId = -1L

        private suspend fun ensure() {
            if (index < page.size || exhausted) return
            page = if (isJump && csv) {
                dao.jumpCsvDetailPage(ids, afterDate, afterIdText, afterAttemptOrdinal, afterMetricKey, afterMetricUnit, afterAttemptId, afterMetricOrdinal, DATA_EXPORT_PAGE_SIZE)
            } else if (isJump) {
                dao.jumpDetailPage(ids, afterDate, afterIdText, afterAttemptOrdinal, afterAttemptId, afterMetricPresent, afterMetricOrdinal, afterMetricKey, afterMetricUnit, DATA_EXPORT_PAGE_SIZE)
            } else {
                dao.encoderDetailPage(ids, afterDate, afterIdText, afterRepetitionOrdinal, afterRepetitionId, afterMetricPresent, afterMetricKey, afterMetricUnit, DATA_EXPORT_PAGE_SIZE)
            }
            index = 0
            if (page.isEmpty()) exhausted = true
        }

        suspend fun forEach(parentId: Long, block: suspend (Any) -> Unit) {
            while (true) {
                ensure()
                if (index >= page.size) return
                val item = page[index]
                val id = if (isJump) (item as ExportJumpDetailRow).assessmentId else (item as ExportEncoderDetailRow).sessionId
                if (id != parentId) return
                index++
                if (isJump) {
                    val row = item as ExportJumpDetailRow
                    afterDate = row.parentDateTime; afterIdText = row.parentIdText; afterAttemptOrdinal = row.attemptOrdinal; afterAttemptId = row.attemptId; afterMetricPresent = row.metricPresent; afterMetricOrdinal = row.metricOrdinal ?: -1; afterMetricKey = row.metricKey ?: ""; afterMetricUnit = row.metricUnit ?: ""
                } else {
                    val row = item as ExportEncoderDetailRow
                    afterDate = row.parentDateTime; afterIdText = row.parentIdText; afterRepetitionOrdinal = row.repetitionOrdinal; afterRepetitionId = row.repetitionId; afterMetricPresent = row.metricPresent; afterMetricKey = row.metricKey ?: ""; afterMetricUnit = row.metricUnit ?: ""
                }
                block(item)
            }
        }
    }
}

private fun DataExportSnapshot.toDocument(): DataExportDocument {
    val testingById = testingSessions.associateBy { it.id }
    val attemptsByAssessment = attempts.groupBy(ExportAttemptRow::assessmentId)
    val jumpMetricsByAttempt = jumpMetrics.groupBy { it.attemptId }
    val encoderRepetitionsBySession = encoderRepetitions.groupBy(ExportEncoderRepetitionRow::sessionId)
    val encoderMetricsByRepetition = encoderMetrics.groupBy(ExportEncoderMetricRow::repetitionId)
    val athletes = athletes.map { ExportAthlete(it.id.toString(), it.displayName, it.birthDate?.let { day -> LocalDate.ofEpochDay(day).toString() }, it.sex) }
    val jumps = assessments.map { a ->
        val attempts = attemptsByAssessment[a.id].orEmpty().map { at -> ExportJumpAttempt(ExportJumpContext(at.side, at.ordinal, at.dropHeightCm, at.distanceCm), jumpMetricsByAttempt[at.id].orEmpty().map { ExportMetric(it.key, it.value, it.unit, ordinal = it.ordinal) }) }
        ExportMeasurement("jump", a.id.toString(), a.dateTime.toUtcString(), a.athleteId?.toString(), a.testingSessionId?.toString(), a.testingSessionId?.let { testingById[it]?.groupNameSnapshot }, a.protocolId, primaryMetric = ExportMetric(a.primaryMetricKey, a.primaryMetricValue, a.primaryMetricUnit, ordinal = 0, isPrimary = true), attempts = attempts, notes = a.notes)
    }
    val encoders = encoderSessions.map { s ->
        val summaries = buildList {
            add(ExportEncoderSummary("TOTAL_REPETITIONS", s.totalRepetitions.toDouble(), "COUNT"))
            add(ExportEncoderSummary("VALID_REPETITIONS", s.validRepetitions.toDouble(), "COUNT"))
            s.bestMcv?.let { add(ExportEncoderSummary("BEST_MCV", it, "METER_PER_SECOND")) }
            s.setVelocityLoss?.let { add(ExportEncoderSummary("SET_VELOCITY_LOSS", it, "PERCENT")) }
        }
        val repetitions = encoderRepetitionsBySession[s.id].orEmpty().map { r ->
            ExportEncoderRepetition(
                r.ordinal,
                r.quality,
                r.reasons.split(',').filter(String::isNotBlank).sorted(),
                encoderMetricsByRepetition[r.id].orEmpty().map {
                    ExportMetric(it.key, it.value, it.unit, it.validity, it.reason)
                },
            )
        }
        ExportMeasurement(
            "encoder", s.id.toString(), s.dateTime.toUtcString(), s.athleteId?.toString(),
            s.testingSessionId?.toString(), s.testingSessionId?.let { testingById[it]?.groupNameSnapshot },
            exercise = s.exercise, loadKg = s.loadKg, analysisVersion = s.analysisVersion,
            sessionSummaries = summaries, repetitions = repetitions, notes = s.notes,
        )
    }
    return DataExportDocument(exportedAt = Instant.now().toString(), athletes = athletes, measurements = jumps + encoders).canonicalized()
}

private fun Long.toUtcString(): String = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toInstant().toString()

/** NaN/Infinity nunca se exportan: se convierten en ausencia (null) al construir el modelo. */
private fun Double?.finiteOrNull(): Double? = if (this == null || isFinite()) this else null

/**
 * Ensambla las raíces F-V-P con sus condiciones e intentos en el modelo del documento. Las filas
 * planas se ordenan por (ordinal de condición, id de condición, ordinal de intento, id de intento).
 *
 * P2 (Wave 4a): cada raíz debe cumplir `sfvNsPerM == -slopeFv`; una fila finita pero contradictoria
 * (creada con `insertAssessmentRaw`, que salta la validacion) hace fallar el ensamblado con
 * `IllegalArgumentException` en vez de emitir un par imposible en JSON/CSV. La exportacion se
 * rechaza entera: no se publica un valor contradictorio.
 */
internal fun fvpAssessments(
    roots: List<ExportFvpAssessmentRow>,
    details: List<ExportFvpDetailRow>,
): List<ExportFvpAssessment> {
    val detailsByAssessment = details.groupBy { it.assessmentId }
    return roots.map { root ->
        requireFvpOppositeSlopeSigns(root.slopeFv, root.sfvNsPerM, "assessment ${root.idText}")
        val ordered = detailsByAssessment[root.id].orEmpty().sortedWith(
            compareBy({ it.conditionOrdinal }, { it.conditionId }, { it.attemptOrdinal ?: -1 }, { it.attemptId ?: -1L }),
        )
        val attemptsByCondition = ordered.filter { it.attemptId != null }.groupBy { it.conditionId }
        ExportFvpAssessment(
            id = root.idText,
            sessionKey = root.sessionKey,
            protocolId = root.protocolId,
            dateTimeUtc = root.dateTime.toUtcString(),
            bodyMassKg = root.bodyMassKg,
            bodyMassSource = root.bodyMassSource,
            pushOffMode = root.pushOffMode,
            pushOffM = root.pushOffM,
            pushOffSource = root.pushOffSource,
            algorithmVersion = root.algorithmVersion,
            methodVersion = root.methodVersion,
            conditionCount = root.conditionCount,
            f0N = root.f0N,
            slopeFv = root.slopeFv,
            sfvNsPerM = root.sfvNsPerM,
            vMinMps = root.vMinMps,
            vMaxMps = root.vMaxMps,
            fMinN = root.fMinN,
            fMaxN = root.fMaxN,
            warningsCsv = root.warningsCsv,
            athleteId = root.athleteId?.toString(),
            testingSessionId = root.testingSessionId?.toString(),
            pushOffLandmark = root.pushOffLandmark,
            pushOffLandmarkHeightCm = root.pushOffLandmarkHeightCm.finiteOrNull(),
            pushOffProtocol = root.pushOffProtocol,
            pushOffMeasuredAtUtc = root.pushOffMeasuredAt?.toUtcString(),
            pushOffCrouchCm = root.pushOffCrouchCm.finiteOrNull(),
            pushOffExtendedCm = root.pushOffExtendedCm.finiteOrNull(),
            sourceFps = root.sourceFps.finiteOrNull(),
            v0Mps = root.v0Mps.finiteOrNull(),
            f0RelNPerKg = root.f0RelNPerKg.finiteOrNull(),
            pmaxW = root.pmaxW.finiteOrNull(),
            pmaxRelWPerKg = root.pmaxRelWPerKg.finiteOrNull(),
            r2 = root.r2.finiteOrNull(),
            notes = root.notes,
            // Procedencia B0 (esquema 5): copia 1:1 de la fila, sin derivar ni normalizar.
            acquiredAtUtc = root.acquiredAt?.toUtcString(),
            acquisitionContextKind = root.acquisitionContextKind,
            acquisitionContextKey = root.acquisitionContextKey,
            bodyMassMeasurement = root.bodyMassMeasurement,
            bodyMassMeasuredAtUtc = root.bodyMassMeasuredAt?.toUtcString(),
            conditions = ordered.map { it.conditionId }.distinct().map { conditionId ->
                val head = ordered.first { it.conditionId == conditionId }
                ExportFvpCondition(
                    ordinal = head.conditionOrdinal,
                    conditionKey = head.conditionKey,
                    addedLoadKg = head.addedLoadKg,
                    totalMassKg = head.totalMassKg,
                    pushOffM = head.pushOffM,
                    selectedHeightM = head.selectedHeightM,
                    selectedAttemptOrdinal = head.selectedAttemptOrdinal,
                    addedLoadSource = head.addedLoadSource,
                    addedLoadMeasuredAtUtc = head.addedLoadMeasuredAt?.toUtcString(),
                    attempts = attemptsByCondition[conditionId].orEmpty().mapNotNull { row ->
                        val ordinal = row.attemptOrdinal ?: return@mapNotNull null
                        val heightM = row.heightM ?: return@mapNotNull null
                        val method = row.method ?: return@mapNotNull null
                        ExportFvpAttempt(
                            ordinal = ordinal,
                            heightM = heightM,
                            method = method,
                            valid = row.attemptValid ?: true,
                            flightTimeS = row.flightTimeS.finiteOrNull(),
                            sourceFps = row.sourceFps.finiteOrNull(),
                            sourceAssessmentId = row.sourceAssessmentId?.toString(),
                            sourceAttemptOrdinal = row.sourceAttemptOrdinal,
                            sourceProtocolId = row.sourceProtocolId,
                            exclusionReason = row.exclusionReason,
                            notes = row.attemptNotes,
                            sourceRecordedAtUtc = row.sourceRecordedAt?.toUtcString(),
                            sourceAthleteId = row.sourceAthleteId?.toString(),
                            acquiredAtUtc = row.acquiredAt?.toUtcString(),
                            reuseConfirmedAtUtc = row.reuseConfirmedAt?.toUtcString(),
                            reuseConfirmationVersion = row.reuseConfirmationVersion,
                            takeoffPtsUs = row.takeoffPtsUs,
                            takeoffPreviousPtsUs = row.takeoffPreviousPtsUs,
                            takeoffNextPtsUs = row.takeoffNextPtsUs,
                            landingPtsUs = row.landingPtsUs,
                            landingPreviousPtsUs = row.landingPreviousPtsUs,
                            landingNextPtsUs = row.landingNextPtsUs,
                            timeMapping = row.timeMapping ?: "UNKNOWN",
                            timeMappingEvidenceKey = row.timeMappingEvidenceKey,
                        )
                    },
                )
            },
        )
    }
}
