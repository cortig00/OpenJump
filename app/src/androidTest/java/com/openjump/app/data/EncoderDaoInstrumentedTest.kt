package com.openjump.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncoderDaoInstrumentedTest {
    private lateinit var database: JumpDatabase
    private lateinit var dao: EncoderDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            JumpDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.encoderDao()
        runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "Test athlete", createdAt = 1L, updatedAt = 1L))
            database.athleteDao().insert(AthleteEntity(displayName = "Other athlete", createdAt = 1L, updatedAt = 1L))
        }
    }

    @After
    fun close() = database.close()

    private fun simpleRecord(
        key: String,
        exercise: String,
        loadKg: Double,
        bestMcv: Double?,
        dateTime: Long,
    ) = PersistableEncoderSession(
        session = EncoderSessionEntity(
            sessionKey = key,
            athleteId = 1L,
            exercise = exercise,
            loadKg = loadKg,
            dateTime = dateTime,
            source = "IMPORTED",
            videoUri = null,
            observedFps = null,
            nominalFps = null,
            captureFps = null,
            timingMode = "REAL_TIME",
            slowMotionFactor = 1.0,
            calibrationFrameIndex = 0,
            calibrationAX = 0.0,
            calibrationAY = 0.0,
            calibrationBX = 1.0,
            calibrationBY = 0.0,
            referenceLengthM = 1.0,
            metersPerPixel = 1.0,
            analysisVersion = 1,
            minimumVelocityMps = 0.1,
            noiseMultiplier = 4.0,
            exitRatio = 0.5,
            minimumDirectionHoldUs = 1L,
            stationaryDurationUs = 1L,
            pauseDurationUs = 1L,
            minimumPhaseDurationUs = 1L,
            minimumPhaseDisplacementM = 0.01,
            romOutlierFraction = 0.2,
            minimumMedianConfidence = 0.5,
            maximumUncertainFraction = 0.15,
            smoothingWindowSamples = 5,
            velocityEnterMps = null,
            velocityExitMps = null,
            totalRepetitions = 0,
            validRepetitions = 0,
            bestMcv = bestMcv,
            setVelocityLoss = null,
        ),
        samples = emptyList(),
        repetitions = emptyList(),
    )

    @Test
    fun encoderSeriesSeparateExerciseAndLoadWithStrictTolerance() = runBlocking {
        val squat60 = dao.save(simpleRecord("series-squat-60", "SQUAT", 60.0, 0.70, 100L))
        val squat60Equivalent = dao.save(simpleRecord("series-squat-60-equivalent", "SQUAT", 60.0000005, 0.80, 200L))
        val squat100 = dao.save(simpleRecord("series-squat-100", "SQUAT", 100.0, 0.90, 300L))
        val squatOutsideTolerance = dao.save(simpleRecord("series-squat-outside", "SQUAT", 60.000002, 0.95, 400L))
        val bench60 = dao.save(simpleRecord("series-bench-60", "BENCH_PRESS", 60.0, 0.85, 500L))
        val tieFirst = dao.save(simpleRecord("series-squat-tie-first", "SQUAT", 70.0, 0.77, 700L))
        dao.save(simpleRecord("series-squat-tie-second", "SQUAT", 70.0, 0.77, 700L))
        dao.save(simpleRecord("series-squat-60-invalid", "SQUAT", 60.0, null, 600L))

        val records = EncoderRepository(dao).athleteEncoderRecords(1L).first()
        assertEquals(5, records.size)
        assertEquals(setOf("BENCH_PRESS", "SQUAT"), records.map { it.exercise }.toSet())
        val squatRecords = records.filter { it.exercise == "SQUAT" }
        assertEquals(4, squatRecords.size)
        assertEquals(listOf(60.0, 60.000002, 70.0, 100.0), squatRecords.map { it.loadKg })
        assertEquals(squat60Equivalent, squatRecords.first { it.loadKg < 60.000001 }.sessionId)
        assertEquals(squatOutsideTolerance, squatRecords.first { it.loadKg == 60.000002 }.sessionId)

        val sixtyStats = dao.athleteEncoderStats(1L, "SQUAT", 60.0)
        assertEquals(2, sixtyStats.count)
        assertEquals(0.75, sixtyStats.average!!, 1e-9)
        assertEquals(0.80, sixtyStats.best!!, 1e-9)
        assertEquals(squat60Equivalent, sixtyStats.recentId)
        assertEquals(2, dao.athleteEncoderStats(1L, "SQUAT", 59.9999996).count)
        assertEquals(1, dao.athleteEncoderStats(1L, "SQUAT", 60.0000004).count)
        assertEquals(1, dao.athleteEncoderStats(1L, "SQUAT", 60.000002).count)
        assertEquals(1, dao.athleteEncoderStats(1L, "SQUAT", 100.0).count)
        assertEquals(1, dao.athleteEncoderStats(1L, "BENCH_PRESS", 60.0).count)
        assertEquals(squatOutsideTolerance, dao.athleteEncoderStats(1L, "SQUAT", 60.000002).recentId)
        assertEquals(2, dao.athleteEncoderEvolution(1L, "SQUAT", 60.0).size)
        assertEquals(listOf(squat60, squat60Equivalent), dao.athleteEncoderEvolution(1L, "SQUAT", 60.0).map { it.id }.reversed())
        val bestRecords = EncoderRepository(dao).athleteEncoderRecords(1L).first()
        assertEquals(bench60, bestRecords.first { it.exercise == "BENCH_PRESS" }.sessionId)
        assertEquals(tieFirst, bestRecords.first { it.loadKg == 70.0 }.sessionId)
    }

    @Test
    fun strictToleranceBoundarySeparatesRepositoryStatsAndEvolution() = runBlocking {
        val aId = dao.save(simpleRecord("boundary-a", "SQUAT", 0.0, 0.80, 100L))
        val bId = dao.save(simpleRecord("boundary-b", "SQUAT", 0.000001, 0.90, 200L))

        val records = EncoderRepository(dao).athleteEncoderRecords(1L).first()
        assertEquals(listOf(0.0, 0.000001), records.map { it.loadKg })
        assertEquals(listOf(aId, bId), records.map { it.sessionId })

        val aStats = dao.athleteEncoderStats(1L, "SQUAT", 0.0)
        assertEquals(1, aStats.count)
        assertEquals(aId, aStats.recentId)
        assertEquals(listOf(aId), dao.athleteEncoderEvolution(1L, "SQUAT", 0.0).map { it.id })

        val bStats = dao.athleteEncoderStats(1L, "SQUAT", 0.000001)
        assertEquals(1, bStats.count)
        assertEquals(bId, bStats.recentId)
        assertEquals(listOf(bId), dao.athleteEncoderEvolution(1L, "SQUAT", 0.000001).map { it.id })
    }

    @Test
    fun greedyLoadClustersQualifyStatsAndEvolutionByAnchor() = runBlocking {
        val aId = dao.save(simpleRecord("greedy-a", "SQUAT", 60.0, 0.85, 100L))
        val bId = dao.save(simpleRecord("greedy-b", "SQUAT", 60.0000009, 0.85, 200L))
        val cId = dao.save(simpleRecord("greedy-c", "SQUAT", 60.0000018, 0.95, 300L))

        val records = EncoderRepository(dao).athleteEncoderRecords(1L).first()
        assertEquals(listOf(60.0, 60.0000018), records.map { it.loadKg })
        assertEquals(aId, records[0].sessionId)
        assertEquals(cId, records[1].sessionId)

        val aStats = dao.athleteEncoderStats(1L, "SQUAT", 60.0)
        assertEquals(2, aStats.count)
        assertEquals(0.85, aStats.average!!, 1e-9)
        assertEquals(0.85, aStats.best!!, 1e-9)
        assertEquals(bId, aStats.recentId)
        assertEquals(listOf(aId, bId), dao.athleteEncoderEvolution(1L, "SQUAT", 60.0).map { it.id }.reversed())

        val cStats = dao.athleteEncoderStats(1L, "SQUAT", 60.0000018)
        assertEquals(1, cStats.count)
        assertEquals(cId, cStats.recentId)
        assertEquals(listOf(cId), dao.athleteEncoderEvolution(1L, "SQUAT", 60.0000018).map { it.id })
    }

    @Test
    fun sessionSamplesRepetitionsAndValidityRoundTripIdempotently() = runBlocking {
        val session = EncoderSessionEntity(
            sessionKey = "encoder-test",
            athleteId = 1L,
            exercise = "BENCH_PRESS",
            loadKg = 80.0,
            dateTime = 123L,
            source = "IMPORTED",
            videoUri = "content://encoder/1",
            observedFps = 120.0,
            nominalFps = 120.0,
            captureFps = null,
            timingMode = "SLOW_MOTION",
            slowMotionFactor = 4.0,
            calibrationFrameIndex = 2,
            calibrationAX = 10.0,
            calibrationAY = 20.0,
            calibrationBX = 110.0,
            calibrationBY = 20.0,
            referenceLengthM = 0.5,
            metersPerPixel = 0.005,
            analysisVersion = 1,
            minimumVelocityMps = 0.04,
            noiseMultiplier = 4.0,
            exitRatio = 0.5,
            minimumDirectionHoldUs = 50_000L,
            stationaryDurationUs = 300_000L,
            pauseDurationUs = 150_000L,
            minimumPhaseDurationUs = 150_000L,
            minimumPhaseDisplacementM = 0.02,
            romOutlierFraction = 0.20,
            minimumMedianConfidence = 0.50,
            maximumUncertainFraction = 0.15,
            smoothingWindowSamples = 11,
            velocityEnterMps = 0.04,
            velocityExitMps = 0.02,
            totalRepetitions = 1,
            validRepetitions = 1,
            bestMcv = 0.8,
            setVelocityLoss = null,
            notes = "encoder note",
        )
        val record = PersistableEncoderSession(
            session = session,
            samples = listOf(
                EncoderSampleEntity(
                    sessionId = 0,
                    ordinal = 0,
                    frameIndex = 2,
                    sourcePtsUs = 33_333L,
                    physicalTimeUs = 0L,
                    rawPixelX = 20.0,
                    rawPixelY = 30.0,
                    rawMetricX = 0.0,
                    rawMetricY = 0.0,
                    smoothedMetricX = 0.0,
                    smoothedMetricY = 0.0,
                    velocityXMps = 0.0,
                    velocityYMps = 0.8,
                    confidence = 0.9,
                    trackingStatus = "TRACKING",
                    observationKind = "MEASURED",
                    fitResidualM = 0.001,
                    gapBeforeUs = null,
                ),
            ),
            repetitions = listOf(
                PersistableEncoderRepetition(
                    repetition = EncoderRepetitionEntity(
                        sessionId = 0,
                        ordinal = 0,
                        startSample = 0,
                        endSample = 0,
                        eccentricStartSample = 0,
                        eccentricEndSample = 0,
                        concentricStartSample = 0,
                        concentricEndSample = 0,
                        quality = "VALID",
                        reasons = "",
                    ),
                    metrics = listOf(
                        EncoderRepMetricEntity(0, "MCV", 0.8, "METER_PER_SECOND", "VALID", null),
                        EncoderRepMetricEntity(0, "PEAK_VELOCITY", null, "METER_PER_SECOND", "INVALID", "PTS_GAP"),
                    ),
                ),
            ),
        )

        val firstId = dao.save(record)
        val secondId = dao.save(record)
        val latestId = dao.save(record.copy(session = session.copy(sessionKey = "encoder-latest", bestMcv = 0.9, notes = "latest note")))
        dao.save(record.copy(session = session.copy(sessionKey = "encoder-invalid", dateTime = 999L, bestMcv = null)))
        database.athleteDao().setArchived(1L, archivedAt = 10L, updatedAt = 10L)
        val retryAfterArchive = dao.save(record)
        val conflicting = record.copy(session = session.copy(athleteId = 2L))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { runBlocking { dao.save(conflicting) } }
        val loaded = requireNotNull(dao.sessionWithData(firstId))

        assertEquals(firstId, secondId)
        assertEquals(firstId, retryAfterArchive)
        assertEquals(1, loaded.samples.size)
        assertEquals(1, loaded.repetitions.size)
        assertEquals(2, loaded.repetitions.single().metrics.size)
        assertEquals("encoder note", loaded.session.notes)
        val repository = EncoderRepository(dao)
        assertTrue(repository.updateNotes(firstId, "  changed  "))
        assertEquals("changed", dao.sessionWithData(firstId)!!.session.notes)
        assertEquals("latest note", dao.sessionWithData(latestId)!!.session.notes)
        assertTrue(repository.updateNotes(firstId, "   "))
        assertNull(dao.sessionWithData(firstId)!!.session.notes)
        assertNull(loaded.repetitions.single().metrics.first { it.key == "PEAK_VELOCITY" }.value)
        assertEquals(3, dao.athleteSummary(1L).first().total)
        assertEquals(1, EncoderRepository(dao).athleteEncoderRecords(1L).first().size)
        assertEquals(2, dao.athleteEncoderEvolution(1L, "BENCH_PRESS", 80.0).size)
        val stats = dao.athleteEncoderStats(1L, "BENCH_PRESS", 80.0)
        assertEquals(2, stats.count)
        assertEquals(0.85, stats.average!!, 1e-9)
        assertEquals(0.9, stats.best!!, 1e-9)
        assertEquals(0.9, stats.recent!!, 1e-9)
        assertEquals(latestId, stats.recentId)
        assertEquals(80.0, EncoderRepository(dao).athleteEncoderRecords(1L).first().single().loadKg, 1e-9)

        assertEquals(true, dao.deleteSession(firstId))
        assertNull(dao.sessionWithData(firstId))
        assertEquals(2, dao.athleteSummary(1L).first().total)
        assertEquals(false, dao.deleteSession(firstId))
    }
}
