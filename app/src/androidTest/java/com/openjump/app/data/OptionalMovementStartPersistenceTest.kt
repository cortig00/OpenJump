package com.openjump.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.backup.ManualBackupCodec
import com.openjump.app.data.backup.ManualBackupRepository
import com.openjump.app.data.export.CsvDataExportWriter
import com.openjump.app.data.export.DataExportRepository
import com.openjump.app.data.export.JsonDataExportWriter
import com.openjump.app.math.ProtocolCalculator
import com.openjump.app.protocol.AthleteAnthropometrics
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.EventKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.protocol.StoredMeasurement
import com.openjump.app.protocol.VideoSource
import com.openjump.app.ui.result.BilateralResultPresenter
import com.openjump.app.ui.result.ResultPresenter
import com.openjump.app.ui.result.TimelineSegmentKind
import java.io.StringWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/repository/codec/writer integration; only disposable in-memory databases are used. */
@RunWith(AndroidJUnit4::class)
class OptionalMovementStartPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: JumpDatabase
    private lateinit var repository: JumpRepository

    @Before fun createDatabase() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        repository = JumpRepository(database.jumpDao())
        database.athleteDao().insert(AthleteEntity(displayName = "Jump fixture", createdAt = 1L, updatedAt = 1L))
        Unit
    }

    @After fun closeDatabase() = database.close()

    @Test fun basicProtocolsKeepFlightEstimatesThroughHistoryBackupAndBothExportWriters() = runBlocking {
        val ids = listOf(ProtocolId.CMJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL).map { protocol ->
            val draft = draft(protocol, "basic-${protocol.name}", anthropometrics = AthleteAnthropometrics(80.0, 180.0))
            val result = ProtocolCalculator.compute(draft)
            val metrics = result.allMetrics.associateBy { it.key }
            val heightM = 9.80665 * 0.6 * 0.6 / 8.0
            val velocity = 9.80665 * 0.6 / 2.0
            assertEquals(heightM * 100.0, metrics.getValue(MetricKey.HEIGHT_CM).value, 1e-9)
            assertEquals(600.0, metrics.getValue(MetricKey.FLIGHT_TIME_MS).value, 0.0)
            assertEquals(velocity, metrics.getValue(MetricKey.TAKEOFF_VELOCITY_MPS).value, 1e-9)
            assertEquals(80.0 * 9.80665 * heightM, metrics.getValue(MetricKey.ESTIMATED_POTENTIAL_ENERGY_J).value, 1e-9)
            assertEquals(0.5 * 80.0 * velocity * velocity, metrics.getValue(MetricKey.ESTIMATED_TAKEOFF_KINETIC_ENERGY_J).value, 1e-9)
            assertEquals(80.0 * velocity, metrics.getValue(MetricKey.ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS).value, 1e-9)
            assertEquals(100.0 * heightM * 100.0 / 180.0, metrics.getValue(MetricKey.RELATIVE_JUMP_HEIGHT_PERCENT).value, 1e-9)
            if (protocol == ProtocolId.CMJ) {
                val power = 60.7 * heightM * 100.0 + 45.3 * 80.0 - 2_055.0
                assertEquals(power, metrics.getValue(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W).value, 1e-9)
                assertEquals(power / 80.0, metrics.getValue(MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG).value, 1e-9)
            } else {
                assertFalse(MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W in metrics)
            }
            val id = repository.save(draft, result, detectedFps = 120, dateTime = protocol.ordinal.toLong())
            assertBasic(requireNotNull(repository.measurementById(id)))
            id
        }
        assertEquals(3, repository.history().first().size)
        val export = DataExportRepository(database)
        val document = export.snapshot()
        val exportedKeys = document.measurements.flatMap { it.attempts }.flatMap { it.metrics }.map { it.key }
        assertFalse("TIME_TO_TAKEOFF_MS" in exportedKeys)
        assertFalse("RSI_MOD" in exportedKeys)
        val json = StringWriter().also { export.writeJson(it) }.toString()
        val csv = StringWriter().also { export.writeCsv(it) }.toString()
        listOf(json, csv, JsonDataExportWriter.write(document), CsvDataExportWriter.write(document)).forEach { text ->
            assertTrue(text.contains("FLIGHT_TIME_MS"))
            assertTrue(text.contains("ESTIMATED_TAKEOFF_MOMENTUM_KG_MPS"))
            assertFalse(text.contains("TIME_TO_TAKEOFF_MS"))
            assertFalse(text.contains("RSI_MOD"))
        }
        withRestoredDatabase { restored ->
            val restoredRepository = JumpRepository(restored.jumpDao())
            assertEquals(3, restoredRepository.history().first().size)
            ids.forEach { assertBasic(requireNotNull(restoredRepository.measurementById(it))) }
        }
    }

    @Test fun legacyThreeEventAndBasicResultsCoexistWithoutConsultingTheCurrentMarkingMode() = runBlocking {
        // Both drafts deliberately have the default OFF snapshot: historical events, not a current
        // setting, remain authoritative for their saved metrics and timing sensitivity.
        val basic = draft(ProtocolId.CMJ, "new-basic")
        val legacy = draft(ProtocolId.CMJ, "old-advanced", startUs = 100_000L)
        val basicId = repository.save(basic, ProtocolCalculator.compute(basic), 120, 1L)
        val legacyId = repository.save(legacy, ProtocolCalculator.compute(legacy), 120, 2L)
        assertBasic(requireNotNull(repository.measurementById(basicId)))
        assertAdvanced(requireNotNull(repository.measurementById(legacyId)))
        val export = DataExportRepository(database).snapshot()
        val exportedKeys = export.measurements.sortedBy { it.dateTimeUtc }.map { measurement ->
            // The curated export carries the primary once at the root, not again in the attempt.
            listOf(requireNotNull(measurement.primaryMetric).key) + measurement.attempts.single().metrics.map { it.key }
        }
        assertEquals(
            listOf(
                setOf("HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS"),
                setOf("HEIGHT_CM", "FLIGHT_TIME_MS", "TAKEOFF_VELOCITY_MPS", "TIME_TO_TAKEOFF_MS", "RSI_MOD"),
            ),
            exportedKeys.map { it.toSet() },
        )
        assertEquals(listOf(3, 5), exportedKeys.map { it.size })
        withRestoredDatabase { restored ->
            val restoredRepository = JumpRepository(restored.jumpDao())
            assertBasic(requireNotNull(restoredRepository.measurementById(basicId)))
            assertAdvanced(requireNotNull(restoredRepository.measurementById(legacyId)))
        }
    }

    @Test fun twoEventBilateralComparisonSavesReopensAndRestoresEveryLeg() = runBlocking {
        val children = listOf(MeasurementSide.LEFT, MeasurementSide.RIGHT).map { side ->
            val draft = draft(ProtocolId.UNILATERAL, "bilateral-${side.name}", side = side)
            BilateralAttemptSnapshot(side, 1, draft, ProtocolCalculator.compute(draft), 120)
        }
        val comparison = BilateralComparison("two-event-bilateral", 1L, 1, children)
        val id = repository.saveBilateral(comparison)
        fun assertComparison(stored: StoredMeasurement) {
            assertEquals(2, stored.attempts.size)
            assertTrue(stored.attempts.all { it.events.size == 2 && it.metrics.size == 3 })
            val model = BilateralResultPresenter.presentStored(stored)
            assertEquals(0.0, model.best.asymmetryPercent, 0.0)
            model.attempts.forEach { attempt ->
                assertNull(attempt.warning)
                assertEquals(listOf(TimelineSegmentKind.FLIGHT), attempt.timeline!!.segments.map { it.kind })
                assertEquals(2, attempt.secondaryMetrics.size)
            }
        }
        assertComparison(requireNotNull(repository.measurementById(id)))
        withRestoredDatabase { restored ->
            assertComparison(requireNotNull(JumpRepository(restored.jumpDao()).measurementById(id)))
        }
    }

    private fun draft(
        protocol: ProtocolId,
        key: String,
        startUs: Long? = null,
        side: MeasurementSide = MeasurementSide.LEFT,
        anthropometrics: AthleteAnthropometrics = AthleteAnthropometrics(),
    ): MeasurementDraft {
        val times = buildList {
            startUs?.let { add(EventType.MOVEMENT_START to it) }
            add(EventType.TAKEOFF to 700_000L)
            add(EventType.LANDING to 1_300_000L)
        }
        return MeasurementDraft(
            sessionKey = key,
            protocolId = protocol,
            athleteId = 1L,
            athleteAnthropometrics = anthropometrics,
            setup = ProtocolSetup(side = side.takeIf { protocol == ProtocolId.UNILATERAL }),
            source = VideoSource.IMPORTED,
            events = times.mapIndexed { index, (type, pts) ->
                val event = EventKey(type)
                event to EventMark(event, pts, index + 1, pts - 10_000L, pts + 10_000L)
            }.toMap(),
        )
    }

    private fun assertBasic(stored: StoredMeasurement) {
        assertEquals(2, stored.attempts.single().events.size)
        assertFalse(stored.attempts.single().metrics.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS || it.key == MetricKey.RSI_MOD })
        val model = ResultPresenter.presentStored(stored)
        assertNull(model.warning)
        assertEquals(listOf(TimelineSegmentKind.FLIGHT), model.timeline!!.segments.map { it.kind })
        assertEquals(listOf(MetricKey.FLIGHT_TIME_MS, MetricKey.TAKEOFF_VELOCITY_MPS), model.secondaryMetrics.map { it.key })
        assertNotNull(model.temporalSensitivity)
        assertFalse(model.temporalSensitivity!!.ranges.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS || it.key == MetricKey.RSI_MOD })
    }

    private fun assertAdvanced(stored: StoredMeasurement) {
        assertEquals(3, stored.attempts.single().events.size)
        val metrics = stored.attempts.single().metrics.associateBy { it.key }
        assertEquals(600.0, metrics.getValue(MetricKey.TIME_TO_TAKEOFF_MS).value, 0.0)
        assertEquals(0.44129925 / 0.6, metrics.getValue(MetricKey.RSI_MOD).value, 1e-12)
        val model = ResultPresenter.presentStored(stored)
        assertNull(model.warning)
        assertEquals(listOf(TimelineSegmentKind.PREPARATION, TimelineSegmentKind.FLIGHT), model.timeline!!.segments.map { it.kind })
        assertNotNull(model.temporalSensitivity)
        assertTrue(model.temporalSensitivity!!.ranges.any { it.key == MetricKey.TIME_TO_TAKEOFF_MS })
    }

    private suspend fun withRestoredDatabase(check: suspend (JumpDatabase) -> Unit) {
        val backup = ManualBackupRepository(database.manualBackupDao()).snapshot()
        val decoded = ManualBackupCodec.decode(ManualBackupCodec.encode(backup))
        val restored = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        try {
            ManualBackupRepository(restored.manualBackupDao()).restore(decoded)
            check(restored)
        } finally {
            restored.close()
        }
    }
}
