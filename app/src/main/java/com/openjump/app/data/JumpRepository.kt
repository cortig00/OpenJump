package com.openjump.app.data

import com.openjump.app.math.BilateralComparisonCalculator
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.StoredAttempt
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.protocol.SpatialMark
import com.openjump.app.protocol.SpatialMarkType
import com.openjump.app.protocol.StoredMeasurement
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class JumpRepository(
    private val dao: JumpDao,
    private val onSuccessfulDelete: suspend () -> Unit = {},
) {

    suspend fun save(
        draft: MeasurementDraft,
        result: ProtocolResult,
        detectedFps: Int,
        dateTime: Long = System.currentTimeMillis(),
        frameIndex: VideoFrameIndex? = null,
        notes: String? = draft.notes,
    ): Long {
        require(draft.athleteId > 0L) { "La medición necesita un atleta seleccionado." }
        val record = PersistableAssessment(
            assessment = AssessmentEntity(
                sessionKey = draft.sessionKey,
                athleteId = draft.athleteId,
                testingSessionId = draft.testingSessionId,
                protocolId = draft.protocolId.storageKey,
                dateTime = dateTime,
                primaryMetricKey = result.primaryMetric.key.storageKey,
                primaryMetricValue = result.primaryMetric.value,
                primaryMetricUnit = result.primaryMetric.unit.storageKey,
                notes = NoteNormalizer.normalize(notes),
            ),
            attempt = AttemptEntity(
                side = draft.setup.side?.storageKey,
                source = (draft.source ?: VideoSource.UNKNOWN).storageKey,
                videoUri = draft.videoUri,
                detectedFps = detectedFps,
                dropHeightCm = draft.setup.dropHeightCm,
                distanceCm = draft.setup.distanceCm,
            ),
            events = draft.events.values
                .sortedWith(compareBy({ it.key.ordinal }, { it.key.type.ordinal }))
                .map {
                    AttemptEventEntity(
                        attemptId = 0,
                        type = it.key.type.storageKey,
                        ordinal = it.key.ordinal,
                        ptsUs = it.ptsUs,
                        frameIndex = it.frameIndex,
                        previousPtsUs = if (frameIndex != null) frameIndex.previousPtsUs(it.frameIndex, it.ptsUs) else it.previousPtsUs,
                        nextPtsUs = if (frameIndex != null) frameIndex.nextPtsUs(it.frameIndex, it.ptsUs) else it.nextPtsUs,
                    )
                },
            metrics = result.allMetrics.map {
                AttemptMetricEntity(
                    attemptId = 0,
                    key = it.key.storageKey,
                    ordinal = it.ordinal,
                    value = it.value,
                    unit = it.unit.storageKey,
                )
            },
            calibration = draft.horizontalJump?.calibration?.let {
                AttemptCalibrationEntity(
                    attemptId = 0,
                    frameIndex = it.frameIndex,
                    pointAX = it.pointA.x,
                    pointAY = it.pointA.y,
                    pointBX = it.pointB.x,
                    pointBY = it.pointB.y,
                    referenceLengthM = it.referenceLengthM,
                    metersPerPixel = it.metersPerPixel,
                )
            },
            spatialMarks = listOfNotNull(
                draft.horizontalJump?.startPoint,
                draft.horizontalJump?.landingHeel,
            ).map {
                AttemptSpatialMarkEntity(
                    attemptId = 0,
                    type = it.type.storageKey,
                    frameIndex = it.frameIndex,
                    ptsUs = it.ptsUs,
                    pointX = it.point.x,
                    pointY = it.point.y,
                )
            },
        )
        return dao.save(record)
    }

    /** Saves one ASYMMETRY root and all unilateral children in one Room transaction. */
    suspend fun saveBilateral(
        comparison: BilateralComparison,
        dateTime: Long = System.currentTimeMillis(),
        notes: String? = comparison.notes,
    ): Long {
        require(comparison.testingSessionId == null) { "ASYMMETRY no está disponible en Testing." }
        val best = BilateralComparisonCalculator.best(comparison)
        val children = comparison.attempts.mapIndexed { ordinal, snapshot ->
            toPersistableAttempt(snapshot, ordinal)
        }
        val first = children.first()
        return dao.save(
            PersistableAssessment(
                assessment = AssessmentEntity(
                    sessionKey = comparison.sessionKey,
                    athleteId = comparison.athleteId,
                    testingSessionId = null,
                    protocolId = ProtocolId.ASYMMETRY.storageKey,
                    dateTime = dateTime,
                    primaryMetricKey = MetricKey.ASYMMETRY_PERCENT.storageKey,
                    primaryMetricValue = best.asymmetryPercent,
                    primaryMetricUnit = MetricUnit.PERCENT.storageKey,
                    notes = NoteNormalizer.normalize(notes),
                ),
                attempt = first.attempt,
                events = first.events,
                metrics = first.metrics,
                calibration = first.calibration,
                spatialMarks = first.spatialMarks,
                attempts = children,
            ),
        )
    }

    private fun toPersistableAttempt(snapshot: BilateralAttemptSnapshot, ordinal: Int): PersistableAttempt {
        val draft = snapshot.draft
        return PersistableAttempt(
            attempt = AttemptEntity(
                ordinal = ordinal,
                side = snapshot.side.storageKey,
                source = (draft.source ?: VideoSource.UNKNOWN).storageKey,
                videoUri = draft.videoUri,
                detectedFps = snapshot.detectedFps,
                dropHeightCm = draft.setup.dropHeightCm,
                distanceCm = draft.setup.distanceCm,
            ),
            events = draft.events.values
                .sortedWith(compareBy({ it.key.ordinal }, { it.key.type.ordinal }))
                .map {
                    AttemptEventEntity(
                        attemptId = 0,
                        type = it.key.type.storageKey,
                        ordinal = it.key.ordinal,
                        ptsUs = it.ptsUs,
                        frameIndex = it.frameIndex,
                        previousPtsUs = it.previousPtsUs,
                        nextPtsUs = it.nextPtsUs,
                    )
                },
            metrics = snapshot.result.allMetrics.map {
                AttemptMetricEntity(0, it.key.storageKey, it.ordinal, it.value, it.unit.storageKey)
            },
        )
    }

    suspend fun measurementById(assessmentId: Long): StoredMeasurement? {
        val relation = dao.assessmentWithAttempts(assessmentId) ?: return null
        val athleteName = relation.assessment.athleteId?.let { dao.athleteName(it) }
        return relation.toStoredMeasurement(athleteName)
    }

    suspend fun updateNotes(assessmentId: Long, notes: String?): Boolean =
        dao.updateNotes(assessmentId, NoteNormalizer.normalize(notes)) == 1

    suspend fun deleteMeasurement(assessmentId: Long): Boolean {
        val deleted = dao.deleteAssessment(assessmentId)
        if (deleted) reconcileAfterDelete()
        return deleted
    }

    private suspend fun reconcileAfterDelete() {
        try {
            onSuccessfulDelete()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The successful Room deletion remains successful if optional provider cleanup fails.
        }
    }

    fun recentFive(): Flow<List<RecentMeasurement>> = dao.recentFive()
    fun recentFiveForAthlete(athleteId: Long): Flow<List<RecentMeasurement>> = dao.recentFiveForAthlete(athleteId)

    fun history(): Flow<List<RecentMeasurement>> = dao.history()
    suspend fun historyPage(query: HistoryQuery, cursor: AthleteHistoryCursor?, limit: Int): List<AthleteHistoryPage> =
        dao.historyPage(
            search = query.search?.normalizeForSearch(),
            athleteId = query.athleteId,
            unassignedOnly = query.unassignedOnly,
            groupId = query.groupId,
            protocolOrExercise = query.protocolOrExercise,
            dateFrom = query.dateFrom,
            dateTo = query.dateTo,
            metricMin = query.metricMin,
            metricMax = query.metricMax,
            cursorDateTime = cursor?.dateTime,
            cursorId = cursor?.id ?: 0L,
            cursorKind = cursor?.kind ?: "",
            limit = limit,
        )
    fun athleteSummary(athleteId: Long): Flow<AthleteJumpSummary>
        = dao.athleteSummary(athleteId)
    fun athleteProtocolCounts(athleteId: Long): Flow<List<AthleteProtocolCount>> = dao.athleteProtocolCounts(athleteId)
    fun athleteJumpRecords(athleteId: Long): Flow<List<AthleteJumpRecord>> = dao.athleteJumpRecords(athleteId)
    fun comparisonCandidates(athleteId: Long): Flow<List<PersonalRecordCandidate>> =
        dao.personalRecordCandidates(athleteId)

    fun personalRecords(athleteId: Long): Flow<List<PersonalRecord>> =
        dao.personalRecordCandidates(athleteId).map(::calculatePersonalRecords)

    fun progressSeries(athleteId: Long): Flow<List<ProgressSeries>> =
        dao.personalRecordCandidates(athleteId).map(::buildProgressSeries)

    suspend fun recordsWonBy(athleteId: Long, assessmentId: Long): List<PersonalRecord> =
        personalRecords(athleteId).first().filter { it.assessmentId == assessmentId }
    suspend fun athleteJumpStats(
        athleteId: Long,
        protocolId: String,
        side: String?,
        unit: String,
    ): AthleteSeriesStats = dao.athleteJumpStats(athleteId, protocolId, side, unit)

    suspend fun athleteJumpEvolution(athleteId: Long, protocolId: String, side: String?): List<AthleteEvolutionPoint> =
        dao.athleteJumpEvolution(athleteId, protocolId, side).asReversed()
    suspend fun athleteHistoryPage(
        athleteId: Long,
        cursor: AthleteHistoryCursor?,
    ): List<AthleteHistoryPage> = dao.athleteHistoryPage(
        athleteId,
        cursor?.dateTime,
        cursor?.id ?: 0L,
        cursor?.kind ?: "",
    )

    /**
     * Intentos de salto guardados de un protocolo reutilizables como medicion de una condicion
     * F-V-P, del mas reciente al mas antiguo y acotados a `limit` filas.
     *
     * La altura se expone en metros (SI) y los fps son los detectados del video origen; el
     * llamante decide si los acepta (nunca se rellena una carga ni un h_po por defecto). El tiempo
     * de vuelo del intento se expone en segundos SOLO si la metrica persistida existe y es
     * utilizable: un intento sin `FLIGHT_TIME_MS` se mapea a `null` ([flightTimeMsToSeconds]).
     */
    suspend fun reusableMeasurementsFor(
        athleteId: Long,
        protocolId: String,
        limit: Int,
    ): List<ReusableJumpMeasurement> {
        require(limit > 0) { "El limite de mediciones reutilizables debe ser > 0." }
        return dao.reusableMeasurementsFor(athleteId, protocolId, limit).map { row ->
            ReusableJumpMeasurement(
                assessmentId = row.assessmentId,
                attemptOrdinal = row.attemptOrdinal,
                dateTime = row.dateTime,
                heightM = row.heightCm / 100.0,
                flightTimeS = flightTimeMsToSeconds(row.flightTimeMs),
                detectedFps = row.detectedFps,
                side = row.side,
                protocolId = row.protocolId,
                hasVideoUri = row.hasVideoUri,
                notes = row.notes,
            )
        }
    }

    private fun AssessmentWithAttempts.toStoredMeasurement(athleteName: String? = null): StoredMeasurement {
        val primaryKey = requireNotNull(MetricKey.fromStorageKey(assessment.primaryMetricKey)) {
            "La medición guardada usa una métrica no compatible."
        }
        val primaryUnit = requireNotNull(MetricUnit.fromStorageKey(assessment.primaryMetricUnit)) {
            "La medición guardada usa una unidad no compatible."
        }
        val protocolId = requireNotNull(ProtocolId.fromStorageKey(assessment.protocolId)) {
            "La medición guardada usa un protocolo no compatible."
        }
        return StoredMeasurement(
            id = assessment.id,
            dateTime = assessment.dateTime,
            athleteId = assessment.athleteId,
            athleteName = athleteName,
            protocolId = protocolId,
            primaryMetric = MetricValue(primaryKey, assessment.primaryMetricValue, primaryUnit),
            notes = assessment.notes,
            attempts = attempts.sortedBy { it.attempt.ordinal }.map { relation ->
                val attempt = relation.attempt
                StoredAttempt(
                    ordinal = attempt.ordinal,
                    side = MeasurementSide.fromStorageKey(attempt.side),
                    source = VideoSource.fromStorageKey(attempt.source),
                    videoUri = attempt.videoUri,
                    detectedFps = attempt.detectedFps,
                    setup = ProtocolSetup(
                        side = MeasurementSide.fromStorageKey(attempt.side),
                        dropHeightCm = attempt.dropHeightCm,
                        distanceCm = attempt.distanceCm,
                    ),
                    events = relation.events
                        .sortedWith(compareBy<AttemptEventEntity> { it.ordinal }.thenBy { it.ptsUs })
                        .map { event ->
                            StoredEvent(
                                type = requireNotNull(EventType.fromStorageKey(event.type)) {
                                    "La medición guardada contiene un evento no compatible."
                                },
                                ordinal = event.ordinal,
                                ptsUs = event.ptsUs,
                                frameIndex = event.frameIndex,
                                previousPtsUs = event.previousPtsUs,
                                nextPtsUs = event.nextPtsUs,
                            )
                        },
                    metrics = relation.metrics
                        .sortedWith(compareBy<AttemptMetricEntity> { it.ordinal }.thenBy { it.key })
                        .map { metric ->
                            MetricValue(
                                key = requireNotNull(MetricKey.fromStorageKey(metric.key)) {
                                    "La medición guardada contiene una métrica no compatible."
                                },
                                value = metric.value,
                                unit = requireNotNull(MetricUnit.fromStorageKey(metric.unit)) {
                                    "La medición guardada contiene una unidad no compatible."
                                },
                                ordinal = metric.ordinal,
                            )
                        },
                    calibration = relation.calibrations.singleOrNull()?.let {
                        MetricCalibration(
                            frameIndex = it.frameIndex,
                            pointA = ImagePoint(it.pointAX, it.pointAY),
                            pointB = ImagePoint(it.pointBX, it.pointBY),
                            referenceLengthM = it.referenceLengthM,
                            metersPerPixel = it.metersPerPixel,
                        )
                    },
                    spatialMarks = relation.spatialMarks.map { mark ->
                        SpatialMark(
                            type = requireNotNull(SpatialMarkType.fromStorageKey(mark.type)) {
                                "La medición guardada contiene una marca espacial no compatible."
                            },
                            point = ImagePoint(mark.pointX, mark.pointY),
                            frameIndex = mark.frameIndex,
                            ptsUs = mark.ptsUs,
                        )
                    }.sortedBy { it.type.ordinal },
                )
            },
        )
    }
}

/**
 * Tiempo de vuelo persistido de un intento reutilizable [ms] -> segundos, o `null` cuando la
 * evidencia no existe o no es utilizable.
 *
 * Reglas (puras, sin Android ni Room):
 * - `null` (metrica `FLIGHT_TIME_MS` ausente en el intento) -> `null`: la ausencia se propaga como
 *   ausencia, nunca se sustituye por 0.0 ni por el valor de otro intento (D7);
 * - valor no finito (NaN / infinito) -> `null`;
 * - valor <= 0 -> `null`: un tiempo de vuelo no positivo no es una medicion reutilizable;
 * - valor finito y positivo -> `ms / 1000.0` (la metrica se persiste en milisegundos).
 */
internal fun flightTimeMsToSeconds(flightTimeMs: Double?): Double? {
    val ms = flightTimeMs ?: return null
    if (!ms.isFinite() || ms <= 0.0) return null
    return ms / 1000.0
}

/**
 * Intento de salto guardado en OpenJump reutilizable como medicion de una condicion F-V-P.
 *
 * [heightM] esta en metros (SI, convertido desde el cm persistido) y [detectedFps] son los fps
 * efectivos detectados del video origen. [flightTimeS] es el tiempo de vuelo del MISMO intento en
 * segundos, o `null` si el intento guardado no lo tenia: es una medida YA existente, nunca se
 * deriva de la altura ni se rellena por defecto. Sigue sin implicar carga ni h_po.
 */
data class ReusableJumpMeasurement(
    val assessmentId: Long,
    val attemptOrdinal: Int,
    val dateTime: Long,
    val heightM: Double,
    val flightTimeS: Double?,
    val detectedFps: Int,
    val side: String?,
    val protocolId: String,
    val hasVideoUri: Boolean,
    val notes: String?,
)
