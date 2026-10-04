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
            stream.finish()
        }
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
