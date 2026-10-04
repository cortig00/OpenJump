package com.openjump.app.data

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderAnalysisWarnings
import com.openjump.app.encoder.EncoderAnalysisConfig
import com.openjump.app.encoder.EncoderDraft
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderMetric
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderMetricUnit
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.encoder.MetricPoint
import com.openjump.app.measurement.MetricCalibration
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality
import com.openjump.app.encoder.StoredEncoderSession
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.encoder.VideoTimingMode
import com.openjump.app.protocol.NoteNormalizer
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EncoderRepository(
    private val dao: EncoderDao,
    private val onSuccessfulDelete: suspend () -> Unit = {},
) {
    suspend fun save(
        draft: EncoderDraft,
        analysis: EncoderAnalysis,
        index: VideoFrameIndex,
        dateTime: Long = System.currentTimeMillis(),
    ): Long {
        require(draft.athleteId > 0L) { "La sesión necesita un atleta seleccionado." }
        val source = draft.source ?: VideoSource.UNKNOWN
        val session = EncoderSessionEntity(
            sessionKey = draft.sessionKey,
            athleteId = draft.athleteId,
            testingSessionId = draft.testingSessionId,
            exercise = analysis.setup.exercise.name,
            loadKg = analysis.setup.loadKg,
            dateTime = dateTime,
            source = source.name,
            videoUri = draft.videoUri,
            observedFps = analysis.effectiveFps,
            nominalFps = index.nominalFrameRate,
            captureFps = index.metadataCaptureFrameRate,
            timingMode = analysis.timingDecision.mode.name,
            slowMotionFactor = analysis.timingDecision.slowMotionFactor,
            calibrationFrameIndex = analysis.calibration.frameIndex,
            calibrationAX = analysis.calibration.pointA.x,
            calibrationAY = analysis.calibration.pointA.y,
            calibrationBX = analysis.calibration.pointB.x,
            calibrationBY = analysis.calibration.pointB.y,
            referenceLengthM = analysis.calibration.referenceLengthM,
            metersPerPixel = analysis.calibration.metersPerPixel,
            analysisVersion = analysis.config.version,
            minimumVelocityMps = analysis.config.minimumVelocityMps,
            noiseMultiplier = analysis.config.noiseMultiplier,
            exitRatio = analysis.config.exitRatio,
            minimumDirectionHoldUs = analysis.config.minimumDirectionHoldUs,
            stationaryDurationUs = analysis.config.stationaryDurationUs,
            pauseDurationUs = analysis.config.pauseDurationUs,
            minimumPhaseDurationUs = analysis.config.minimumPhaseDurationUs,
            minimumPhaseDisplacementM = analysis.config.minimumPhaseDisplacementM,
            romOutlierFraction = analysis.config.romOutlierFraction,
            minimumMedianConfidence = analysis.config.minimumMedianConfidence,
            maximumUncertainFraction = analysis.config.maximumUncertainFraction,
            smoothingWindowSamples = analysis.smoothingWindowSamples,
            velocityEnterMps = analysis.velocityEnterMps,
            velocityExitMps = analysis.velocityExitMps,
            totalRepetitions = analysis.repetitions.size,
            validRepetitions = analysis.validRepetitions.size,
            bestMcv = analysis.bestMcv,
            setVelocityLoss = analysis.setVelocityLoss,
            notes = NoteNormalizer.normalize(draft.notes),
        )
        return dao.save(
            PersistableEncoderSession(
                session = session,
                samples = analysis.samples.map { it.toEntity(0) },
                repetitions = analysis.repetitions.map { repetition ->
                    PersistableEncoderRepetition(
                        repetition = repetition.toEntity(0),
                        metrics = repetition.metrics.map { it.toEntity(0) },
                    )
                },
                sessionPlateDiameterCm = draft.sessionPlateDiameterCm,
            ),
        )
    }

    suspend fun sessionById(id: Long): StoredEncoderSession? {
        val relation = dao.sessionWithData(id) ?: return null
        val athleteName = relation.session.athleteId?.let { dao.athleteName(it) }
        return relation.toStored(athleteName)
    }

    suspend fun updateNotes(id: Long, notes: String?): Boolean =
        dao.updateNotes(id, NoteNormalizer.normalize(notes)) == 1

    suspend fun deleteSession(id: Long): Boolean {
        val deleted = dao.deleteSession(id)
        if (deleted) {
            try {
                onSuccessfulDelete()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A provider cleanup failure cannot reverse a committed Room deletion.
            }
        }
        return deleted
    }

    fun recentFive(): Flow<List<RecentEncoderSession>> = dao.recentFive()
    fun recentFiveForAthlete(athleteId: Long): Flow<List<RecentEncoderSession>> = dao.recentFiveForAthlete(athleteId)

    fun history(): Flow<List<RecentEncoderSession>> = dao.history()
    suspend fun historyPage(query: HistoryQuery, cursor: AthleteHistoryCursor?, limit: Int): List<AthleteHistoryPage> =
        dao.historyPage(
            // Search text is normalized here, where localized exercise labels can be mapped to
            // the canonical enum persisted in Room without changing the schema.
            search = query.search?.let { search ->
                EncoderExercise.canonicalSearchValue(search) ?: search.normalizeForSearch()
            },
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
            limit = limit,
        )
    fun athleteSummary(athleteId: Long): Flow<AthleteEncoderSummary> = dao.athleteSummary(athleteId)
    fun athleteEncoderRecords(athleteId: Long): Flow<List<AthleteEncoderRecord>> =
        dao.athleteEncoderRecordCandidates(athleteId).map(::bestEncoderRecords)
    suspend fun athleteEncoderStats(athleteId: Long, exercise: String, loadKg: Double): AthleteSeriesStats =
        dao.athleteEncoderStats(athleteId, exercise, loadKg)

    suspend fun athleteEncoderEvolution(athleteId: Long, exercise: String, loadKg: Double): List<AthleteEvolutionPoint> =
        dao.athleteEncoderEvolution(athleteId, exercise, loadKg).asReversed()
    suspend fun athleteHistoryPage(
        athleteId: Long,
        cursor: AthleteHistoryCursor?,
    ): List<AthleteHistoryPage> = dao.athleteHistoryPage(
        athleteId,
        cursor?.dateTime,
        cursor?.id ?: 0L,
    )

    private fun EncoderSample.toEntity(sessionId: Long) = EncoderSampleEntity(
        sessionId = sessionId,
        ordinal = ordinal,
        frameIndex = frameIndex,
        sourcePtsUs = sourcePtsUs,
        physicalTimeUs = physicalTimeUs,
        rawPixelX = rawPixelPoint?.x,
        rawPixelY = rawPixelPoint?.y,
        rawMetricX = rawMetricPoint?.x,
        rawMetricY = rawMetricPoint?.y,
        smoothedMetricX = smoothedMetricPoint?.x,
        smoothedMetricY = smoothedMetricPoint?.y,
        velocityXMps = velocityXMps,
        velocityYMps = velocityYMps,
        confidence = confidence,
        trackingStatus = trackingStatus.name,
        observationKind = observationKind.name,
        fitResidualM = fitResidualM,
        gapBeforeUs = gapBeforeUs,
    )

    private fun EncoderRepetition.toEntity(sessionId: Long) = EncoderRepetitionEntity(
        sessionId = sessionId,
        ordinal = ordinal,
        startSample = startSample,
        endSample = endSample,
        eccentricStartSample = eccentricPhase.startSample,
        eccentricEndSample = eccentricPhase.endSample,
        concentricStartSample = concentricPhase.startSample,
        concentricEndSample = concentricPhase.endSample,
        quality = quality.name,
        reasons = reasons.joinToString(",") { it.name },
    )

    private fun EncoderMetric.toEntity(repetitionId: Long) = EncoderRepMetricEntity(
        repetitionId = repetitionId,
        key = key.name,
        value = value,
        unit = unit.name,
        validity = validity.name,
        reason = reason?.name,
    )

    private fun EncoderSessionWithData.toStored(athleteName: String? = null): StoredEncoderSession {
        val calibration = MetricCalibration(
            frameIndex = session.calibrationFrameIndex,
            pointA = ImagePoint(session.calibrationAX, session.calibrationAY),
            pointB = ImagePoint(session.calibrationBX, session.calibrationBY),
            referenceLengthM = session.referenceLengthM,
            metersPerPixel = session.metersPerPixel,
        )
        val storedSamples = samples.sortedBy { it.ordinal }.map { it.toDomain() }
        val storedRepetitions = repetitions.sortedBy { it.repetition.ordinal }.map { relation ->
            relation.repetition.toDomain(relation.metrics)
        }
        val analysis = EncoderAnalysis(
            setup = EncoderSetup(EncoderExercise.valueOf(session.exercise), session.loadKg),
            timingDecision = VideoTimingDecision(
                VideoTimingMode.valueOf(session.timingMode),
                session.slowMotionFactor,
            ),
            calibration = calibration,
            samples = storedSamples,
            repetitions = storedRepetitions,
            effectiveFps = session.observedFps,
            smoothingWindowSamples = session.smoothingWindowSamples,
            velocityEnterMps = session.velocityEnterMps,
            velocityExitMps = session.velocityExitMps,
            // Warnings are derived from the durable timing/sample/repetition data. This keeps
            // old Room v4 rows reopenable without adding a schema column or migration.
            warnings = EncoderAnalysisWarnings.derive(
                timingDecision = VideoTimingDecision(
                    VideoTimingMode.valueOf(session.timingMode),
                    session.slowMotionFactor,
                ),
                samples = storedSamples,
                repetitions = storedRepetitions,
            ),
            config = EncoderAnalysisConfig(
                version = session.analysisVersion,
                minimumVelocityMps = session.minimumVelocityMps,
                noiseMultiplier = session.noiseMultiplier,
                exitRatio = session.exitRatio,
                minimumDirectionHoldUs = session.minimumDirectionHoldUs,
                stationaryDurationUs = session.stationaryDurationUs,
                pauseDurationUs = session.pauseDurationUs,
                minimumPhaseDurationUs = session.minimumPhaseDurationUs,
                minimumPhaseDisplacementM = session.minimumPhaseDisplacementM,
                romOutlierFraction = session.romOutlierFraction,
                minimumMedianConfidence = session.minimumMedianConfidence,
                maximumUncertainFraction = session.maximumUncertainFraction,
            ),
        )
        return StoredEncoderSession(
            id = session.id,
            dateTime = session.dateTime,
            athleteId = session.athleteId,
            athleteName = athleteName,
            source = runCatching { VideoSource.valueOf(session.source) }.getOrDefault(VideoSource.UNKNOWN),
            videoUri = session.videoUri,
            analysis = analysis,
            notes = session.notes,
        )
    }

    private fun EncoderSampleEntity.toDomain() = EncoderSample(
        ordinal = ordinal,
        frameIndex = frameIndex,
        sourcePtsUs = sourcePtsUs,
        physicalTimeUs = physicalTimeUs,
        rawPixelPoint = point(rawPixelX, rawPixelY),
        rawMetricPoint = metricPoint(rawMetricX, rawMetricY),
        smoothedMetricPoint = metricPoint(smoothedMetricX, smoothedMetricY),
        velocityXMps = velocityXMps,
        velocityYMps = velocityYMps,
        confidence = confidence,
        trackingStatus = TrackingStatus.valueOf(trackingStatus),
        observationKind = ObservationKind.valueOf(observationKind),
        fitResidualM = fitResidualM,
        gapBeforeUs = gapBeforeUs,
    )

    private fun EncoderRepetitionEntity.toDomain(metricEntities: List<EncoderRepMetricEntity>) = EncoderRepetition(
        ordinal = ordinal,
        startSample = startSample,
        endSample = endSample,
        eccentricPhase = MovementPhase(MovementDirection.DOWN, eccentricStartSample, eccentricEndSample),
        concentricPhase = MovementPhase(MovementDirection.UP, concentricStartSample, concentricEndSample),
        quality = RepetitionQuality.valueOf(quality),
        reasons = reasons.split(',').filter { it.isNotBlank() }.map { QualityReason.valueOf(it) }.toSet(),
        metrics = metricEntities.map { metric ->
            EncoderMetric(
                key = EncoderMetricKey.valueOf(metric.key),
                value = metric.value,
                unit = EncoderMetricUnit.valueOf(metric.unit),
                validity = MetricValidity.valueOf(metric.validity),
                reason = metric.reason?.let(QualityReason::valueOf),
            )
        },
    )

    private fun point(x: Double?, y: Double?): ImagePoint? = if (x != null && y != null) ImagePoint(x, y) else null
    private fun metricPoint(x: Double?, y: Double?): MetricPoint? = if (x != null && y != null) MetricPoint(x, y) else null
}
