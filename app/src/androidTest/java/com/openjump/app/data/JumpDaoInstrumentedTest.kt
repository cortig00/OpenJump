package com.openjump.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.export.CsvDataExportWriter
import com.openjump.app.data.export.DATA_EXPORT_SCHEMA_VERSION
import com.openjump.app.data.export.DataExportRepository
import com.openjump.app.data.export.JsonDataExportWriter
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.ui.comparison.ComparisonSeries
import com.openjump.app.ui.comparison.comparisonJumpStats
import com.openjump.app.ui.comparison.comparisonSeriesOptions
import java.io.StringWriter
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.HorizontalJumpDraft
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.SpatialMarkType
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.video.VideoFrameIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JumpDaoInstrumentedTest {

    private lateinit var database: JumpDatabase
    private lateinit var repository: JumpRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = JumpRepository(database.jumpDao())
        runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "Test athlete", createdAt = 1L, updatedAt = 1L))
            database.athleteDao().insert(AthleteEntity(displayName = "Other athlete", createdAt = 1L, updatedAt = 1L))
        }
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun successfulDeleteReconcilesAfterCommitButFailedDeleteDoesNot() = runBlocking {
        val uri = "content://test/deleted-video"
        val assessmentId = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "uri-delete-jump", athleteId = 1L, protocolId = "SJ", dateTime = 1L,
                primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 1.0, primaryMetricUnit = "CENTIMETER",
            ),
        )
        database.jumpDao().insertAttempt(
            AttemptEntity(
                assessmentId = assessmentId, side = null, source = "IMPORTED", videoUri = uri,
                detectedFps = 0, dropHeightCm = null, distanceCm = null,
            ),
        )
        var callbackCalls = 0
        var referencesAfterCommit: Set<String>? = null
        val deletingRepository = JumpRepository(database.jumpDao()) {
            callbackCalls++
            referencesAfterCommit = database.jumpDao().savedVideoUris().toSet()
        }

        assertTrue(deletingRepository.deleteMeasurement(assessmentId))
        assertEquals(emptySet<String>(), referencesAfterCommit)
        assertFalse(deletingRepository.deleteMeasurement(Long.MAX_VALUE))
        assertEquals(1, callbackCalls)
    }

    @Test
    fun savedVideoReferencesUnionAttemptAndEncoderUrisOnce() = runBlocking {
        val sharedUri = "content://test/shared-video"
        val assessmentId = database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "uri-reference-jump", athleteId = 1L, protocolId = "SJ", dateTime = 1L,
                primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 1.0, primaryMetricUnit = "CENTIMETER",
            ),
        )
        database.jumpDao().insertAttempt(
            AttemptEntity(
                assessmentId = assessmentId, side = null, source = "IMPORTED", videoUri = sharedUri,
                detectedFps = 0, dropHeightCm = null, distanceCm = null,
            ),
        )
        database.encoderDao().insertSession(
            EncoderSessionEntity(
                sessionKey = "uri-reference-encoder", athleteId = 2L, exercise = "BENCH_PRESS", loadKg = 0.0,
                dateTime = 2L, source = "UNKNOWN", videoUri = sharedUri, observedFps = null, nominalFps = null,
                captureFps = null, timingMode = "UNKNOWN", slowMotionFactor = 1.0,
                calibrationFrameIndex = 0, calibrationAX = 0.0, calibrationAY = 0.0,
                calibrationBX = 1.0, calibrationBY = 1.0, referenceLengthM = 1.0, metersPerPixel = 1.0,
                analysisVersion = 1, minimumVelocityMps = 0.04, noiseMultiplier = 4.0, exitRatio = 0.5,
                minimumDirectionHoldUs = 50_000L, stationaryDurationUs = 300_000L, pauseDurationUs = 150_000L,
                minimumPhaseDurationUs = 150_000L, minimumPhaseDisplacementM = 0.02, romOutlierFraction = 0.2,
                minimumMedianConfidence = 0.5, maximumUncertainFraction = 0.15, smoothingWindowSamples = 5,
                velocityEnterMps = null, velocityExitMps = null, totalRepetitions = 0, validRepetitions = 0,
                bestMcv = null, setVelocityLoss = null,
            ),
        )

        assertEquals(listOf(sharedUri), database.jumpDao().savedVideoUris())
    }

    @Test
    fun streamingExportIncludesDistinctRootNotesAndFormulaHardening() = runBlocking {
        val jump = MeasurementDraft(
            "export-note-jump", ProtocolId.SJ, athleteId = 1L,
            notes = "=jump note",
            events = mapOf(
                EventKey(EventType.TAKEOFF) to EventMark(EventKey(EventType.TAKEOFF), 700_000L, 1),
                EventKey(EventType.LANDING) to EventMark(EventKey(EventType.LANDING), 1_300_000L, 2),
            ),
        )
        repository.save(jump, ProtocolCalculator.compute(jump), detectedFps = 120, dateTime = 10L)
        database.encoderDao().insertSession(
            EncoderSessionEntity(
                sessionKey = "export-note-encoder", athleteId = 1L, exercise = "BENCH_PRESS", loadKg = 0.0,
                dateTime = 20L, source = "UNKNOWN", videoUri = null, observedFps = null, nominalFps = null,
                captureFps = null, timingMode = "UNKNOWN", slowMotionFactor = 1.0,
                calibrationFrameIndex = 0, calibrationAX = 0.0, calibrationAY = 0.0,
                calibrationBX = 1.0, calibrationBY = 1.0, referenceLengthM = 1.0, metersPerPixel = 1.0,
                analysisVersion = 1, minimumVelocityMps = 0.04, noiseMultiplier = 4.0, exitRatio = 0.5,
                minimumDirectionHoldUs = 50_000L, stationaryDurationUs = 300_000L, pauseDurationUs = 150_000L,
                minimumPhaseDurationUs = 150_000L, minimumPhaseDisplacementM = 0.02, romOutlierFraction = 0.2,
                minimumMedianConfidence = 0.5, maximumUncertainFraction = 0.15, smoothingWindowSamples = 5,
                velocityEnterMps = null, velocityExitMps = null, totalRepetitions = 0, validRepetitions = 0,
                bestMcv = null, setVelocityLoss = null, notes = "encoder note",
            ),
        )
        val jsonWriter = StringWriter()
        DataExportRepository(database).writeJson(jsonWriter, java.time.Instant.parse("2026-01-01T00:00:00Z"))
        val csvWriter = StringWriter()
        DataExportRepository(database).writeCsv(csvWriter, java.time.Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(jsonWriter.toString().contains("\"schemaVersion\": $DATA_EXPORT_SCHEMA_VERSION"))
        assertTrue(jsonWriter.toString().contains("=jump note"))
        assertTrue(jsonWriter.toString().contains("encoder note"))
        assertTrue(csvWriter.toString().contains("'=jump note"))
        assertTrue(csvWriter.toString().contains(",encoder note\r\n"))
    }

    @Test
    fun notesNormalizeUpdateIsolatedAndPtsNeighborhoodIsDurable() = runBlocking {
        val draft = MeasurementDraft(
            "notes-pts", ProtocolId.SJ, athleteId = 1L, notes = "  original  ",
            events = mapOf(
                EventKey(EventType.TAKEOFF) to EventMark(EventKey(EventType.TAKEOFF), 700_000L, 1),
                EventKey(EventType.LANDING) to EventMark(EventKey(EventType.LANDING), 1_300_000L, 2),
            ),
        )
        val index = VideoFrameIndex(longArrayOf(0L, 700_000L, 1_300_000L, 1_600_000L), 1_600_000L, 1, 1, 0, null)
        val result = ProtocolCalculator.compute(draft)
        val id = repository.save(draft, result, 120, 1L, frameIndex = index)
        val retry = repository.save(draft.copy(notes = "replacement"), result, 120, 2L, frameIndex = index)
        assertEquals(id, retry)
        assertEquals("original", repository.measurementById(id)!!.notes)
        assertEquals(0L, repository.measurementById(id)!!.attempts.single().events.first().previousPtsUs)
        assertEquals(1_600_000L, repository.measurementById(id)!!.attempts.single().events.last().nextPtsUs)
        assertTrue(repository.updateNotes(id, "  updated  "))
        assertEquals("updated", repository.measurementById(id)!!.notes)
        assertTrue(repository.updateNotes(id, "   "))
        assertEquals(null, repository.measurementById(id)!!.notes)
        assertEquals(0L, repository.measurementById(id)!!.attempts.single().events.first().previousPtsUs)
    }

    @Test
    fun authoritativeFrameIndexClearsStaleEdgeNeighbors() = runBlocking {
        val draft = MeasurementDraft(
            "authoritative-pts-edges", ProtocolId.SJ, athleteId = 1L,
            events = mapOf(
                EventKey(EventType.TAKEOFF) to EventMark(
                    EventKey(EventType.TAKEOFF), 700_000L, 0,
                    previousPtsUs = 123_456L,
                ),
                EventKey(EventType.LANDING) to EventMark(
                    EventKey(EventType.LANDING), 1_300_000L, 1,
                    nextPtsUs = 654_321L,
                ),
            ),
        )
        val index = VideoFrameIndex(longArrayOf(700_000L, 1_300_000L), 1_300_000L, 1, 1, 0, null)
        val id = repository.save(draft, ProtocolCalculator.compute(draft), 120, 1L, frameIndex = index)
        val storedEvents = repository.measurementById(id)!!.attempts.single().events
        assertNull(storedEvents.single { it.type == EventType.TAKEOFF }.previousPtsUs)
        assertNull(storedEvents.single { it.type == EventType.LANDING }.nextPtsUs)
    }

    @Test
    fun savedMeasurementRetainsEventsMetricsAndOrdinals() = runBlocking {
        val events = listOf(
            EventType.MOVEMENT_START to 100_000L,
            EventType.TAKEOFF to 700_000L,
            EventType.LANDING to 1_300_000L,
        ).mapIndexed { index, (type, pts) ->
            EventKey(type) to EventMark(EventKey(type), pts, index)
        }.toMap()
        val draft = MeasurementDraft("detail-session", ProtocolId.CMJ, athleteId = 1L, events = events)
        val result = ProtocolResult(
            ProtocolId.CMJ,
            MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
            listOf(
                MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND),
                MetricValue(MetricKey.RSI_MOD, 0.74, MetricUnit.METER_PER_SECOND),
                MetricValue(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W, 4_247.691, MetricUnit.WATT),
            ),
        )

        val id = repository.save(draft, result, detectedFps = 240, dateTime = 20L)
        val stored = repository.measurementById(id)!!

        assertEquals(ProtocolId.CMJ, stored.protocolId)
        assertEquals(20L, stored.dateTime)
        assertEquals(1, stored.attempts.size)
        assertEquals(1, database.jumpDao().athleteSummary(1L).first().total)
        assertEquals(0, stored.attempts.single().ordinal)
        assertEquals(240, stored.attempts.single().detectedFps)
        assertEquals(listOf(100_000L, 700_000L, 1_300_000L), stored.attempts.single().events.map { it.ptsUs })
        assertEquals(4, stored.attempts.single().metrics.size)
        assertEquals(MetricKey.HEIGHT_CM, stored.primaryMetric.key)
        val storedEstimate = stored.attempts.single().metrics.single {
            it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W
        }
        assertEquals(4_247.691, storedEstimate.value, 1e-9)
        assertEquals(MetricUnit.WATT, storedEstimate.unit)
        assertEquals(null, repository.measurementById(id + 99L))
    }

    @Test
    fun calculatedEstimateSurvivesRepositoryRoomAndDataExport() = runBlocking {
        val events = listOf(
            EventKey(EventType.TAKEOFF) to EventMark(EventKey(EventType.TAKEOFF), 700_000L, 0),
            EventKey(EventType.LANDING) to EventMark(EventKey(EventType.LANDING), 1_300_000L, 1),
        ).toMap()
        val draft = MeasurementDraft(
            sessionKey = "estimated-export",
            protocolId = ProtocolId.SJ,
            athleteId = 1L,
            athleteAnthropometrics = AthleteAnthropometrics(weightKg = 80.0, heightCm = 180.0),
            events = events,
        )
        val calculated = ProtocolCalculator.compute(draft)

        repository.save(draft, calculated, detectedFps = 240, dateTime = 25L)
        val document = DataExportRepository(database).snapshot()
        val exportedEstimate = document.measurements.single().attempts.single().metrics.single {
            it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W.storageKey
        }

        assertEquals(MetricUnit.WATT.storageKey, exportedEstimate.unit)
        assertEquals(
            calculated.allMetrics.single {
                it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W
            }.value,
            exportedEstimate.value!!,
            0.0,
        )
        assertTrue(JsonDataExportWriter.write(document).contains("ESTIMATED_PEAK_POWER_SAYERS_W"))
        assertTrue(CsvDataExportWriter.write(document).contains("ESTIMATED_PEAK_POWER_SAYERS_W"))
    }

    @Test
    fun horizontalMeasurementRetainsCalibrationPointsFramesAndVideoContext() = runBlocking {
        val horizontal = HorizontalJumpDraft()
            .markCalibrationPoint(ImagePoint(20.0, 300.0), 3)
            .markCalibrationPoint(ImagePoint(220.0, 300.0), 3)
            .confirmCalibration(1.0)
            .markNextPoint(ImagePoint(50.0, 320.0), 4, 100_000L)
            .markNextPoint(ImagePoint(536.0, 350.0), 42, 700_000L)
        val draft = MeasurementDraft(
            sessionKey = "horizontal-session",
            protocolId = ProtocolId.HORIZONTAL,
            athleteId = 1L,
            videoUri = "content://jump/horizontal",
            horizontalJump = horizontal,
        )
        val result = ProtocolCalculator.compute(draft)

        val id = repository.save(draft, result, detectedFps = 120, dateTime = 30L)
        val stored = repository.measurementById(id)!!
        val attempt = stored.attempts.single()

        assertEquals(2.43, stored.primaryMetric.value, 1e-9)
        assertEquals(120, attempt.detectedFps)
        assertEquals("content://jump/horizontal", attempt.videoUri)
        assertEquals(3, attempt.calibration!!.frameIndex)
        assertEquals(1.0, attempt.calibration!!.referenceLengthM, 1e-9)
        assertEquals(
            listOf(SpatialMarkType.START_POINT, SpatialMarkType.LANDING_HEEL),
            attempt.spatialMarks.map { it.type },
        )
        assertEquals(42, attempt.spatialMarks.last().frameIndex)
        assertEquals(700_000L, attempt.spatialMarks.last().ptsUs)
    }

    @Test
    fun deletingMeasurementRemovesRootAndCascadeChildren() = runBlocking {
        val draft = MeasurementDraft("delete-session", ProtocolId.SJ, athleteId = 1L)
        val result = ProtocolResult(
            ProtocolId.SJ,
            MetricValue(MetricKey.HEIGHT_CM, 30.0, MetricUnit.CENTIMETER),
            listOf(MetricValue(MetricKey.FLIGHT_TIME_MS, 500.0, MetricUnit.MILLISECOND)),
        )
        val id = repository.save(draft, result, detectedFps = 120, dateTime = 10L)

        assertTrue(repository.deleteMeasurement(id))
        assertEquals(null, repository.measurementById(id))
        assertTrue(repository.history().first().isEmpty())
        assertFalse(repository.deleteMeasurement(id))
    }

    @Test
    fun sameSessionWithDifferentOwnerFailsWithoutChildrenDuplication() = runBlocking {
        val draft = MeasurementDraft("conflict-session", ProtocolId.SJ, athleteId = 1L)
        val result = ProtocolResult(
            ProtocolId.SJ,
            MetricValue(MetricKey.HEIGHT_CM, 30.0, MetricUnit.CENTIMETER),
            emptyList(),
        )
        val firstId = repository.save(draft, result, 240, 10L)
        val conflicting = draft.copy(athleteId = 2L)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.save(conflicting, result, 240, 20L) }
        }
        assertEquals(firstId, database.jumpDao().assessmentIdForSession("conflict-session"))
        assertEquals(1, database.jumpDao().assessmentWithAttempts(firstId)!!.attempts.size)
    }

    @Test
    fun unilateralRecordsStaySeparateAndEvolutionIsLimitedChronologically() = runBlocking {
        fun result(value: Double) = ProtocolResult(
            ProtocolId.UNILATERAL,
            MetricValue(MetricKey.HEIGHT_CM, value, MetricUnit.CENTIMETER),
            emptyList(),
        )
        repository.save(
            MeasurementDraft("left-low", ProtocolId.UNILATERAL, 1L, setup = com.openjump.app.protocol.ProtocolSetup(com.openjump.app.protocol.MeasurementSide.LEFT)),
            result(10.0), 120, 100L,
        )
        repository.save(
            MeasurementDraft("left-high", ProtocolId.UNILATERAL, 1L, setup = com.openjump.app.protocol.ProtocolSetup(com.openjump.app.protocol.MeasurementSide.LEFT)),
            result(20.0), 120, 200L,
        )
        repository.save(
            MeasurementDraft("right", ProtocolId.UNILATERAL, 1L, setup = com.openjump.app.protocol.ProtocolSetup(com.openjump.app.protocol.MeasurementSide.RIGHT)),
            result(30.0), 120, 300L,
        )
        val records = database.jumpDao().athleteJumpRecords(1L).first()
        assertEquals(setOf("LEFT", "RIGHT"), records.map { it.side }.toSet())
        assertEquals(setOf(20.0, 30.0), records.map { it.value }.toSet())

        repeat(55) { index ->
            repository.save(
                MeasurementDraft("series-$index", ProtocolId.SJ, 1L),
                ProtocolResult(ProtocolId.SJ, MetricValue(MetricKey.HEIGHT_CM, index.toDouble(), MetricUnit.CENTIMETER), emptyList()),
                120, index.toLong(),
            )
        }
        val series = repository.athleteJumpEvolution(1L, "SJ", null)
        assertEquals(50, series.size)
        assertEquals((5L..54L).toList(), series.map { it.dateTime })

        val fullStats = repository.athleteJumpStats(1L, "SJ", null, "CENTIMETER")
        assertEquals(55, fullStats.count)
        assertEquals(27.0, fullStats.average!!, 1e-9)
        assertEquals(54.0, fullStats.best!!, 1e-9)
        assertEquals(54.0, fullStats.recent!!, 1e-9)
        assertEquals(54L, fullStats.recentDateTime)
        assertEquals(true, fullStats.recentId!! > 0L)

        val leftStats = repository.athleteJumpStats(1L, "UNILATERAL", "LEFT", "CENTIMETER")
        assertEquals(2, leftStats.count)
        assertEquals(20.0, leftStats.best!!, 1e-9)
        assertEquals(30.0, repository.athleteJumpStats(1L, "UNILATERAL", "RIGHT", "CENTIMETER").best!!, 1e-9)
    }

    @Test
    fun personalRecordsIncludeBilateralAttemptsAndSeparateDropBoxesAfterDeletion() = runBlocking {
        val left = repository.save(
            MeasurementDraft("pr-left", ProtocolId.UNILATERAL, 1L,
                setup = com.openjump.app.protocol.ProtocolSetup(side = com.openjump.app.protocol.MeasurementSide.LEFT)),
            ProtocolResult(ProtocolId.UNILATERAL, MetricValue(MetricKey.HEIGHT_CM, 20.0, MetricUnit.CENTIMETER), emptyList()),
            120, 10L,
        )
        val children = listOf("LEFT" to 25.0, "RIGHT" to 19.0).mapIndexed { ordinal, (side, height) ->
            PersistableAttempt(
                AttemptEntity(ordinal = ordinal, side = side, source = "UNKNOWN", videoUri = null,
                    detectedFps = 120, dropHeightCm = null, distanceCm = null),
                emptyList(),
                listOf(AttemptMetricEntity(0, "HEIGHT_CM", 0, height, "CENTIMETER")),
            )
        }
        val bilateral = database.jumpDao().save(PersistableAssessment(
            assessment = AssessmentEntity(sessionKey = "pr-bilateral", athleteId = 1L,
                protocolId = "ASYMMETRY", dateTime = 20L, primaryMetricKey = "ASYMMETRY_PERCENT",
                primaryMetricValue = 24.0, primaryMetricUnit = "PERCENT"),
            attempt = children.first().attempt, events = emptyList(), metrics = children.first().metrics,
            attempts = children,
        ))
        suspend fun saveDrop(key: String, box: Double, rsi: Double, date: Long) = repository.save(
            MeasurementDraft(key, ProtocolId.DROP_JUMP, 1L,
                setup = com.openjump.app.protocol.ProtocolSetup(dropHeightCm = box)),
            ProtocolResult(ProtocolId.DROP_JUMP, MetricValue(MetricKey.RSI, rsi, MetricUnit.METER_PER_SECOND), emptyList()),
            120, date,
        )
        val box30 = saveDrop("pr-drop-30", 30.0, 1.8, 30L)
        saveDrop("pr-drop-40", 40.0, 2.0, 40L)
        val first = repository.personalRecords(1L).first()
        assertEquals(4, first.size)
        val winner = first.single { it.series.side?.name == "LEFT" }
        assertEquals(bilateral, winner.assessmentId)
        assertEquals(0, winner.attemptOrdinal)
        assertTrue(winner.fromBilateral)
        assertEquals(20.0, winner.previousBest!!, 0.0)
        assertEquals(2, repository.recordsWonBy(1L, bilateral).size)
        assertEquals(box30, first.single { it.series.dropHeightCm == 30.0 }.assessmentId)
        val progress = repository.progressSeries(1L).first()
        assertEquals(first.map { it.series }.toSet(), progress.map { it.condition }.toSet())
        assertEquals(listOf(left, bilateral), progress.single { it.condition.side?.name == "LEFT" }.points.map { it.assessmentId })
        assertEquals(0, progress.single { it.condition.side?.name == "LEFT" }.points.last().attemptOrdinal)
        assertEquals(listOf(box30), progress.single { it.condition.dropHeightCm == 30.0 }.points.map { it.assessmentId })
        assertTrue(repository.deleteMeasurement(bilateral))
        assertEquals(left, repository.progressSeries(1L).first().single { it.condition.side?.name == "LEFT" }.personalBest.assessmentId)
        assertEquals(left, repository.personalRecords(1L).first().single { it.series.side?.name == "LEFT" }.assessmentId)
    }

    @Test
    fun comparisonCandidatesSeparateAthletesDropBoxesAndBilateralSidesInRoom() = runBlocking {
        suspend fun saveDrop(owner: Long, key: String, box: Double, rsi: Double, date: Long) = repository.save(
            MeasurementDraft(key, ProtocolId.DROP_JUMP, owner,
                setup = com.openjump.app.protocol.ProtocolSetup(dropHeightCm = box)),
            ProtocolResult(ProtocolId.DROP_JUMP,
                MetricValue(MetricKey.RSI, rsi, MetricUnit.METER_PER_SECOND), emptyList()),
            120, date,
        )
        saveDrop(1L, "team-dj-30", 30.0, 1.2, 10L)
        saveDrop(1L, "team-dj-40", 40.0, 2.0, 20L)
        saveDrop(2L, "other-dj-30", 30.0, 1.8, 30L)
        val attempts = listOf("LEFT" to 25.0, "RIGHT" to 19.0).mapIndexed { ordinal, (side, height) ->
            PersistableAttempt(
                AttemptEntity(ordinal = ordinal, side = side, source = "UNKNOWN", videoUri = null,
                    detectedFps = 120, dropHeightCm = null, distanceCm = null),
                emptyList(),
                listOf(AttemptMetricEntity(0, "HEIGHT_CM", 0, height, "CENTIMETER")),
            )
        }
        database.jumpDao().save(PersistableAssessment(
            assessment = AssessmentEntity(sessionKey = "team-bilateral", athleteId = 1L,
                protocolId = "ASYMMETRY", dateTime = 40L, primaryMetricKey = "ASYMMETRY_PERCENT",
                primaryMetricValue = 24.0, primaryMetricUnit = "PERCENT"),
            attempt = attempts.first().attempt, events = emptyList(), metrics = attempts.first().metrics,
            attempts = attempts,
        ))
        val first = repository.comparisonCandidates(1L).first()
        val second = repository.comparisonCandidates(2L).first()
        val options = comparisonSeriesOptions(listOf(first to emptyList(), second to emptyList()))
            .filterIsInstance<ComparisonSeries.Jump>()
        assertEquals(4, options.size)
        val box30 = options.single { it.dropHeightCm == 30.0 }
        val box40 = options.single { it.dropHeightCm == 40.0 }
        assertEquals(1.2, comparisonJumpStats(first, box30).best!!, 0.0)
        assertEquals(1.8, comparisonJumpStats(second, box30).best!!, 0.0)
        assertEquals(2.0, comparisonJumpStats(first, box40).best!!, 0.0)
        assertEquals(0, comparisonJumpStats(second, box40).count)
        assertEquals(25.0, comparisonJumpStats(first, options.single { it.side == "LEFT" }).best!!, 0.0)
        assertEquals(19.0, comparisonJumpStats(first, options.single { it.side == "RIGHT" }).best!!, 0.0)
        assertEquals(0, comparisonJumpStats(second, options.single { it.side == "LEFT" }).count)
    }

    @Test
    fun recentAthleteFeedFiltersBeforeLimit() = runBlocking {
        repeat(6) { index ->
            repository.save(
                MeasurementDraft("target-recent-$index", ProtocolId.SJ, 1L),
                ProtocolResult(ProtocolId.SJ, MetricValue(MetricKey.HEIGHT_CM, index.toDouble(), MetricUnit.CENTIMETER), emptyList()),
                120, index.toLong(),
            )
            repository.save(
                MeasurementDraft("other-recent-$index", ProtocolId.SJ, 2L),
                ProtocolResult(ProtocolId.SJ, MetricValue(MetricKey.HEIGHT_CM, index.toDouble(), MetricUnit.CENTIMETER), emptyList()),
                120, 1_000L + index,
            )
        }
        val target = database.jumpDao().recentFiveForAthlete(1L).first()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), target.map { it.dateTime })
    }

    @Test
    fun athleteHistoryCursorHandlesEqualTimestampsWithoutDuplicates() = runBlocking {
        repeat(25) { index ->
            repository.save(
                MeasurementDraft("same-time-$index", ProtocolId.SJ, 1L),
                ProtocolResult(ProtocolId.SJ, MetricValue(MetricKey.HEIGHT_CM, index.toDouble(), MetricUnit.CENTIMETER), emptyList()),
                120, 10_000L,
            )
        }
        val first = repository.athleteHistoryPage(1L, null)
        assertEquals(20, first.size)
        val last = first.last()
        val second = repository.athleteHistoryPage(1L, AthleteHistoryCursor(last.dateTime, last.id, last.kind))
        assertEquals(5, second.size)
        assertEquals(25, (first + second).map { it.id }.distinct().size)
    }

    @Test
    fun historyPageAppliesFiltersInSqlAndStaysBounded() = runBlocking {
        val groupId = database.groupDao().insert(GroupEntity(name = "Squad", createdAt = 1L, updatedAt = 1L))
        database.groupDao().insertMemberships(listOf(AthleteGroupCrossRef(groupId, 1L)))
        repeat(75) { index ->
            repository.save(
                MeasurementDraft("bounded-$index", ProtocolId.SJ, athleteId = 1L),
                ProtocolResult(ProtocolId.SJ, MetricValue(MetricKey.HEIGHT_CM, index.toDouble(), MetricUnit.CENTIMETER), emptyList()),
                120,
                1_000L + index,
            )
        }
        database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "legacy-visible", athleteId = null, protocolId = "SJ", dateTime = 10_000L,
                primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 88.0, primaryMetricUnit = "CENTIMETER",
            ),
        )

        val bounded = repository.historyPage(HistoryQuery(groupId = groupId), null, 40)
        assertEquals(40, bounded.size)
        assertEquals(setOf(1L), bounded.map { it.athleteId }.toSet())
        val filtered = repository.historyPage(
            HistoryQuery(athleteId = 1L, dateFrom = 1_050L, dateTo = 1_055L, metricMin = 50.0, metricMax = 55.0),
            null,
            40,
        )
        assertEquals((50L..55L).toSet(), filtered.map { it.primaryMetricValue!!.toLong() }.toSet())
        val visibleLegacy = repository.historyPage(HistoryQuery(), null, 30)
        assertEquals(null, visibleLegacy.first().athleteId)
        val onlyLegacy = repository.historyPage(HistoryQuery(unassignedOnly = true), null, 30)
        assertEquals(1, onlyLegacy.size)
        assertEquals(null, onlyLegacy.single().athleteId)
    }

    @Test
    fun historySearchMatchesAthleteNamesWithoutCaseOrAccents() = runBlocking {
        database.athleteDao().update(
            AthleteEntity(id = 1L, displayName = "César", createdAt = 1L, updatedAt = 1L),
        )
        database.jumpDao().insertAssessment(
            AssessmentEntity(
                sessionKey = "accent-search", athleteId = 1L, protocolId = "SJ", dateTime = 1L,
                primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 42.0, primaryMetricUnit = "CENTIMETER",
            ),
        )

        listOf("César", "Cesar", "CESAR", "cesar").forEach { search ->
            val result = repository.historyPage(HistoryQuery(search = search), null, 40)
            assertEquals("search=$search", listOf("César"), result.map { it.athleteName })
        }
    }

    @Test
    fun sameSessionIsSavedOnlyOnce() = runBlocking {
        val events = listOf(
            EventType.TAKEOFF to 1_000_000L,
            EventType.LANDING to 1_600_000L,
        ).mapIndexed { index, (type, pts) ->
            EventKey(type) to EventMark(EventKey(type), pts, index)
        }.toMap()
        val draft = MeasurementDraft("stable-session", ProtocolId.SJ, athleteId = 1L, events = events)
        val result = ProtocolResult(
            ProtocolId.SJ,
            MetricValue(MetricKey.HEIGHT_CM, 44.13, MetricUnit.CENTIMETER),
            listOf(MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND)),
        )

        val firstId = repository.save(draft, result, detectedFps = 240, dateTime = 10L)
        val secondId = repository.save(draft, result, detectedFps = 240, dateTime = 20L)
        database.athleteDao().setArchived(1L, archivedAt = 30L, updatedAt = 30L)
        val retryAfterArchive = repository.save(draft, result, detectedFps = 240, dateTime = 40L)

        assertEquals(firstId, secondId)
        assertEquals(firstId, retryAfterArchive)
        val recent = repository.recentFive().first()
        assertEquals(1, recent.size)
        assertEquals(10L, recent.single().dateTime)
        assertEquals("SJ", recent.single().protocolId)
    }

    /**
     * B5/A (plan 13, lane A): el selector F-V-P reutiliza el TIEMPO DE VUELO del intento guardado.
     * La consulta lo lee de `attempt_metrics.FLIGHT_TIME_MS` del MISMO intento con LEFT JOIN: cuando
     * la metrica no existe, la fila mapea `flightTimeS = null` (nunca 0.0 ni el valor de otro
     * intento). NO ejecutado en esta estacion: solo se compila el source set instrumentado.
     */
    @Test
    fun reusableMeasurementsPreserveFlightTimeAndYieldNullWhenMetricIsAbsent() = runBlocking {
        val withFlightTime = repository.save(
            MeasurementDraft("reuse-flight-time-present", ProtocolId.SJ, athleteId = 1L),
            ProtocolResult(
                ProtocolId.SJ,
                MetricValue(MetricKey.HEIGHT_CM, 32.5, MetricUnit.CENTIMETER),
                listOf(MetricValue(MetricKey.FLIGHT_TIME_MS, 600.0, MetricUnit.MILLISECOND)),
            ),
            detectedFps = 240,
            dateTime = 10L,
        )
        val withoutFlightTime = repository.save(
            MeasurementDraft("reuse-flight-time-absent", ProtocolId.SJ, athleteId = 1L),
            ProtocolResult(
                ProtocolId.SJ,
                MetricValue(MetricKey.HEIGHT_CM, 30.0, MetricUnit.CENTIMETER),
                emptyList(),
            ),
            detectedFps = 240,
            dateTime = 20L,
        )

        val reusable = repository.reusableMeasurementsFor(athleteId = 1L, protocolId = "SJ", limit = 10)

        val withMetric = reusable.single { it.assessmentId == withFlightTime }
        assertEquals(0, withMetric.attemptOrdinal)
        assertEquals(0.325, withMetric.heightM, 1e-12)
        assertEquals("el tiempo de vuelo persistido sobrevive al mapeo", 0.6, withMetric.flightTimeS!!, 0.0)

        val withoutMetric = reusable.single { it.assessmentId == withoutFlightTime }
        assertNull("un intento sin FLIGHT_TIME_MS no inventa tiempo de vuelo", withoutMetric.flightTimeS)
    }
}
