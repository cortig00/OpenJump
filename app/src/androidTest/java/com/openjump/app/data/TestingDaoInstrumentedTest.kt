package com.openjump.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestingDaoInstrumentedTest {
    private lateinit var database: JumpDatabase
    private lateinit var athletes: AthleteRepository
    private lateinit var groups: GroupRepository
    private lateinit var testing: TestingRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        athletes = AthleteRepository(database.athleteDao())
        groups = GroupRepository(database.groupDao())
        testing = TestingRepository(database.testingDao(), database.groupDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun rosterIsFrozenAndNavigationCanSkipAndGoBack() = runBlocking {
        val first = athletes.create("First")
        val second = athletes.create("Second")
        val group = groups.createWithMemberships("Squad", athleteIds = listOf(first, second))
        val session = testing.create(group, TestingFamily.JUMP, protocolId = "SJ", targetAttempts = 2)

        groups.replaceMemberships(group, listOf(second))
        assertEquals(listOf(first, second), testing.detail(session)!!.participants.sortedBy { it.ordinal }.map { it.athleteId })

        assertEquals(second, testing.next(session)!!.athleteId)
        assertEquals(first, testing.back(session)!!.athleteId)
        assertEquals(second, testing.skip(session, first)!!.athleteId)
        assertEquals(true, testing.progress(session).first { it.participant.athleteId == first }.isOmitted)
    }

    @Test
    fun jumpSaveValidatesRosterAndAutoCompletesSingleParticipantSession() = runBlocking {
        val owner = athletes.create("Owner")
        val outsider = athletes.create("Outsider")
        val group = groups.createWithMemberships("Jumpers", athleteIds = listOf(owner))
        val session = testing.create(group, TestingFamily.JUMP, protocolId = "SJ", targetAttempts = 1)
        val result = ProtocolResult(
            ProtocolId.SJ,
            MetricValue(MetricKey.HEIGHT_CM, 30.0, MetricUnit.CENTIMETER),
            emptyList(),
        )
        val repository = JumpRepository(database.jumpDao())

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    MeasurementDraft("outsider", ProtocolId.SJ, outsider, testingSessionId = session),
                    result,
                    120,
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    MeasurementDraft("wrong-protocol", ProtocolId.CMJ, owner, testingSessionId = session),
                    result,
                    120,
                )
            }
        }
        repository.save(
            MeasurementDraft("valid", ProtocolId.SJ, owner, testingSessionId = session),
            result,
            120,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(
                    MeasurementDraft("excess", ProtocolId.SJ, owner, testingSessionId = session),
                    result,
                    120,
                )
            }
        }

        assertEquals(1, testing.progress(session).single().completedAttempts)
        assertEquals(TestingStatus.COMPLETED.name, testing.byId(session)!!.status)
    }

    @Test
    fun encoderSaveCountsProgressAndRejectsJumpFamily() = runBlocking {
        val owner = athletes.create("Encoder")
        val group = groups.createWithMemberships("Lifters", athleteIds = listOf(owner))
        val encoderSession = testing.create(
            group,
            TestingFamily.ENCODER,
            exercise = "SQUAT",
            loadKg = 80.0,
            plateDiameterCm = 45.0,
            targetAttempts = 1,
        )
        val jumpSession = testing.create(group, TestingFamily.JUMP, protocolId = "SJ", targetAttempts = 1)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { database.encoderDao().save(encoderRecord(owner, jumpSession, "wrong-family")) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.encoderDao().save(
                    encoderRecord(owner, encoderSession, "wrong-load").copy(
                        session = encoderRecord(owner, encoderSession, "wrong-load").session.copy(loadKg = 81.0),
                    ),
                )
            }
        }
        database.encoderDao().save(encoderRecord(owner, encoderSession, "encoder-valid"))

        assertEquals(1, testing.progress(encoderSession).single().completedAttempts)
        assertEquals(TestingStatus.COMPLETED.name, testing.byId(encoderSession)!!.status)
    }

    private fun encoderRecord(athleteId: Long, testingSessionId: Long, key: String) =
        PersistableEncoderSession(
            session = EncoderSessionEntity(
                sessionKey = key,
                athleteId = athleteId,
                testingSessionId = testingSessionId,
                exercise = "SQUAT",
                loadKg = 80.0,
                dateTime = 1L,
                source = "IMPORTED",
                videoUri = null,
                observedFps = 30.0,
                nominalFps = 30.0,
                captureFps = null,
                timingMode = "REAL_TIME",
                slowMotionFactor = 1.0,
                calibrationFrameIndex = 0,
                calibrationAX = 0.0,
                calibrationAY = 0.0,
                calibrationBX = 1.0,
                calibrationBY = 0.0,
                referenceLengthM = 1.0,
                metersPerPixel = 0.01,
                analysisVersion = 1,
                minimumVelocityMps = 0.04,
                noiseMultiplier = 4.0,
                exitRatio = 0.5,
                minimumDirectionHoldUs = 50_000,
                stationaryDurationUs = 300_000,
                pauseDurationUs = 150_000,
                minimumPhaseDurationUs = 150_000,
                minimumPhaseDisplacementM = 0.02,
                romOutlierFraction = 0.2,
                minimumMedianConfidence = 0.5,
                maximumUncertainFraction = 0.15,
                smoothingWindowSamples = 5,
                velocityEnterMps = 0.1,
                velocityExitMps = 0.05,
                totalRepetitions = 1,
                validRepetitions = 1,
                bestMcv = 0.8,
                setVelocityLoss = null,
            ),
            samples = emptyList(),
            repetitions = emptyList(),
            sessionPlateDiameterCm = 45.0,
        )
}
