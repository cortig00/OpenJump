package com.openjump.app.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.AssessmentEntity
import com.openjump.app.data.AttemptEntity
import com.openjump.app.data.AttemptMetricEntity
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.EncoderRepMetricEntity
import com.openjump.app.data.EncoderRepetitionEntity
import com.openjump.app.data.EncoderSampleEntity
import com.openjump.app.data.EncoderSessionEntity
import com.openjump.app.data.FvpAssessmentEntity
import com.openjump.app.data.FvpAttemptEntity
import com.openjump.app.data.FvpConditionEntity
import com.openjump.app.data.JumpDatabase
import java.io.StringWriter
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataExportDaoInstrumentedTest {
    private lateinit var database: JumpDatabase
    private val selectCount = java.util.concurrent.atomic.AtomicInteger()

    @Before
    fun setup() {
        selectCount.set(0)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            JumpDatabase::class.java,
        ).setQueryCallback({ sql, _ -> if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) selectCount.incrementAndGet() }, java.util.concurrent.Executor { it.run() })
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun snapshotIsCuratedAndExcludesSamplesAndVideoColumns() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Archived athlete", birthDate = 10, sex = "X", archivedAt = 20, createdAt = 1, updatedAt = 1))
        val assessmentId = database.jumpDao().insertAssessment(
            AssessmentEntity(sessionKey = "jump", athleteId = athleteId, protocolId = "CMJ", dateTime = 2, primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 30.0, primaryMetricUnit = "CENTIMETER"),
        )
        val attemptId = database.jumpDao().insertAttempt(AttemptEntity(assessmentId = assessmentId, ordinal = 0, side = null, source = "IMPORTED", videoUri = "content://private", detectedFps = 120, dropHeightCm = null, distanceCm = null))
        database.jumpDao().insertMetrics(listOf(AttemptMetricEntity(attemptId, "FLIGHT_TIME_MS", 0, 400.0, "MILLISECOND")))

        val sessionId = database.encoderDao().insertSession(
            EncoderSessionEntity(
                sessionKey = "encoder", athleteId = athleteId, exercise = "BENCH_PRESS", loadKg = 80.0, dateTime = 3,
                source = "IMPORTED", videoUri = "content://private-encoder", observedFps = 120.0, nominalFps = 120.0,
                captureFps = null, timingMode = "NORMAL", slowMotionFactor = 1.0, calibrationFrameIndex = 0,
                calibrationAX = 0.0, calibrationAY = 0.0, calibrationBX = 1.0, calibrationBY = 0.0,
                referenceLengthM = 1.0, metersPerPixel = 1.0, analysisVersion = 1, minimumVelocityMps = 0.1,
                noiseMultiplier = 1.0, exitRatio = 0.5, minimumDirectionHoldUs = 1, stationaryDurationUs = 1,
                pauseDurationUs = 1, minimumPhaseDurationUs = 1, minimumPhaseDisplacementM = 0.1,
                romOutlierFraction = 0.2, minimumMedianConfidence = 0.5, maximumUncertainFraction = 0.5,
                smoothingWindowSamples = 5, velocityEnterMps = null, velocityExitMps = null,
                totalRepetitions = 1, validRepetitions = 1, bestMcv = 1.2, setVelocityLoss = null,
            ),
        )
        val repetitionId = database.encoderDao().insertRepetition(
            EncoderRepetitionEntity(sessionId = sessionId, ordinal = 0, startSample = 0, endSample = 1, eccentricStartSample = 0, eccentricEndSample = 0, concentricStartSample = 0, concentricEndSample = 1, quality = "VALID", reasons = ""),
        )
        database.encoderDao().insertMetrics(listOf(EncoderRepMetricEntity(repetitionId, "MCV", 1.2, "METER_PER_SECOND", "VALID", null)))
        database.encoderDao().insertSamples(listOf(EncoderSampleEntity(sessionId, 0, 0, 0, null, null, null, null, null, null, null, null, null, 1.0, "TRACKING", "POINT", null, null)))

        val document = DataExportRepository(database).snapshot()
        assertEquals(listOf(athleteId), document.let { listOf(it.athletes.single().id.toLong()) })
        assertEquals(2, document.measurements.size)
        assertTrue(document.measurements.flatMap { it.repetitions }.flatMap { it.metrics }.none { it.key == "encoder_samples" })
        assertFalse(JsonDataExportWriter.write(document).contains("content://private"))
        assertFalse(JsonDataExportWriter.write(document).contains("encoder_samples"))
    }

    @Test
    fun streamingJsonAndCsvMatchLegacyForSameFixture() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Unicode Ω", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1))
        val assessmentId = database.jumpDao().insertAssessment(AssessmentEntity(sessionKey = "equivalence", athleteId = athleteId, protocolId = "CMJ", dateTime = 10, primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 2.0, primaryMetricUnit = "CENTIMETER"))
        val attemptId = database.jumpDao().insertAttempt(AttemptEntity(assessmentId = assessmentId, ordinal = 0, side = "LEFT", source = "IMPORTED", videoUri = null, detectedFps = 30, dropHeightCm = null, distanceCm = null))
        database.jumpDao().insertMetrics(listOf(
            AttemptMetricEntity(attemptId, "HEIGHT_CM", 0, 2.0, "CENTIMETER"),
            AttemptMetricEntity(attemptId, "FLIGHT_TIME_MS", 1, 400.0, "MILLISECOND"),
        ))
        val repository = DataExportRepository(database)
        val legacy = repository.snapshot().copy(exportedAt = "2026-01-01T00:00:00Z")
        val expectedJson = JsonDataExportWriter.write(legacy)
        val expectedCsv = CsvDataExportWriter.write(legacy)
        val actualJson = StringWriter().also { repository.writeJson(it, Instant.parse(legacy.exportedAt)) }.toString()
        val actualCsv = StringWriter().also { repository.writeCsv(it, Instant.parse(legacy.exportedAt)) }.toString()
        assertEquals(expectedJson, actualJson)
        assertEquals(expectedCsv, actualCsv)
    }

    @Test
    fun pagingRetainsMoreThan600RootsAndDetailRows() = runBlocking {
        val assessments = (0 until 601).map { index ->
            AssessmentEntity(sessionKey = "page-$index", protocolId = "CMJ", dateTime = index.toLong(), primaryMetricKey = "ROOT_$index", primaryMetricValue = index.toDouble(), primaryMetricUnit = "UNIT")
        }
        var detailAttemptId = 0L
        assessments.forEach { assessment ->
            val assessmentId = database.jumpDao().insertAssessment(assessment)
            val attemptId = database.jumpDao().insertAttempt(AttemptEntity(assessmentId = assessmentId, ordinal = 0, side = null, source = "IMPORTED", videoUri = null, detectedFps = 30, dropHeightCm = null, distanceCm = null))
            if (assessmentId == 1L) {
                detailAttemptId = attemptId
                database.jumpDao().insertMetrics((0 until 601).map { metric ->
                    AttemptMetricEntity(attemptId, "DETAIL_$metric", metric, metric.toDouble(), "UNIT")
                })
            }
        }
        assertTrue(detailAttemptId > 0)
        // Ignore fixture/setup reads; this measures only the export operation.
        selectCount.set(0)
        val output = StringWriter()
        DataExportRepository(database).writeJson(output, Instant.parse("2026-01-01T00:00:00Z"))
        val measurements = Json.parseToJsonElement(output.toString()).jsonObject["measurements"]!!.jsonArray
        assertEquals(601, measurements.size)
        assertEquals((1L..601L).map(Long::toString), measurements.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val details = measurements.first().jsonObject["attempts"]!!.jsonArray.single()
            .jsonObject["metrics"]!!.jsonArray
        assertEquals(601, details.size)
        assertEquals((0..600).map { "DETAIL_$it" }, details.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals((0..600).map { it.toDouble().toString() }, details.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        // Three root pages plus three detail pages (and the athlete page), not one SELECT per root.
        assertTrue(selectCount.get() < 50)
    }

    @Test
    fun streamingExportIncludesFvpFamilyWithItsPagedChildren() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "FVP athlete", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1))
        val assessmentId = database.fvpDao().insertAssessmentRaw(fvpAssessmentEntity(athleteId, "fvp-key", 5L, conditionCount = 2))
        val loaded = database.fvpDao().insertConditionRaw(FvpConditionEntity(assessmentId = assessmentId, ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0, totalMassKg = 80.0, pushOffM = 0.4, selectedHeightM = 0.42, selectedAttemptOrdinal = 1))
        val loaded20 = database.fvpDao().insertConditionRaw(FvpConditionEntity(assessmentId = assessmentId, ordinal = 1, conditionKey = "20kg", addedLoadKg = 20.0, totalMassKg = 100.0, pushOffM = 0.4, selectedHeightM = 0.31, selectedAttemptOrdinal = null))
        database.fvpDao().insertAttemptRaw(FvpAttemptEntity(conditionId = loaded, ordinal = 0, heightM = 0.35, method = "MANUAL", valid = false, exclusionReason = "SHORT_FLIGHT"))
        database.fvpDao().insertAttemptRaw(FvpAttemptEntity(conditionId = loaded, ordinal = 1, heightM = 0.42, method = "FLIGHT_TIME_VIDEO", flightTimeS = 0.6, sourceFps = 240.0, sourceAssessmentId = 201L, sourceAttemptOrdinal = 0, sourceProtocolId = "CMJ", valid = true))
        val exportedAt = Instant.parse("2026-01-01T00:00:00Z")
        val json = StringWriter().also { DataExportRepository(database).writeJson(it, exportedAt) }.toString()
        val document = Json.parseToJsonElement(json).jsonObject
        // El atleta aparece aunque solo tenga filas F-V-P (EXISTS ampliado en athletePage).
        assertEquals(listOf(athleteId.toString()), document["athletes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(0, document["measurements"]!!.jsonArray.size)
        val entry = document["fvp"]!!.jsonArray.single().jsonObject
        assertEquals(assessmentId.toString(), entry["id"]!!.jsonPrimitive.content)
        assertEquals("fvp-key", entry["sessionKey"]!!.jsonPrimitive.content)
        assertEquals(athleteId.toString(), entry["athleteId"]!!.jsonPrimitive.content)
        assertEquals("80.0", entry["bodyMassKg"]!!.jsonPrimitive.content)
        assertEquals("MEASURED", entry["pushOffSource"]!!.jsonPrimitive.content)
        assertEquals("LOW_R2", entry["warningsCsv"]!!.jsonPrimitive.content)
        assertEquals("2", entry["conditionCount"]!!.jsonPrimitive.content)
        // Par de pendiente coherente (sfvNsPerM = -slopeFv) tal como lo escribe la app (W3.1).
        assertEquals("-300.0", entry["slopeFv"]!!.jsonPrimitive.content)
        assertEquals("300.0", entry["sfvNsPerM"]!!.jsonPrimitive.content)
        // El intercepto V0 debe viajar en el export (regresion: se mapeaba siempre a null).
        assertEquals("1.4", entry["v0Mps"]!!.jsonPrimitive.content)
        val conditions = entry["conditions"]!!.jsonArray
        assertEquals(listOf("0kg", "20kg"), conditions.map { it.jsonObject["conditionKey"]!!.jsonPrimitive.content })
        assertEquals(listOf("0.0", "20.0"), conditions.map { it.jsonObject["addedLoadKg"]!!.jsonPrimitive.content })
        assertEquals(listOf("0.42", "0.31"), conditions.map { it.jsonObject["selectedHeightM"]!!.jsonPrimitive.content })
        val attempts = conditions.first().jsonObject["attempts"]!!.jsonArray
        assertEquals(listOf("MANUAL", "FLIGHT_TIME_VIDEO"), attempts.map { it.jsonObject["method"]!!.jsonPrimitive.content })
        assertEquals("false", attempts.first().jsonObject["valid"]!!.jsonPrimitive.content)
        assertEquals("SHORT_FLIGHT", attempts.first().jsonObject["exclusionReason"]!!.jsonPrimitive.content)
        assertEquals("201", attempts.last().jsonObject["sourceAssessmentId"]!!.jsonPrimitive.content)
        assertEquals(0, conditions.last().jsonObject["attempts"]!!.jsonArray.size)

        val csv = StringWriter().also { DataExportRepository(database).writeCsv(it, exportedAt) }.toString()
        assertEquals(29, csv.lineSequence().first().split(',').size)
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it[2] == "fvp" })
        assertTrue(rows.all { it[0] == DATA_EXPORT_SCHEMA_VERSION.toString() })
        assertEquals("fvp-key", rows.single { it[22] == "SESSION_KEY" }[26])
        assertTrue(rows.any { it[22] == "HEIGHT" && it[13] == "0" && it[14] == "0" && it[23] == "0.35" })
        assertTrue(rows.any { it[22] == "METHOD" && it[13] == "0" && it[14] == "0" && it[26] == "MANUAL" })
        assertEquals("0kg", rows.single { it[22] == "CONDITION_KEY" && it[14] == "0" }[26])
        assertEquals("20kg", rows.single { it[22] == "CONDITION_KEY" && it[14] == "1" }[26])
        // Fila V0 presente en el CSV (antes se omitia en silencio).
        assertTrue("el CSV debe incluir la fila V0", rows.any { it[22] == "V0" && it[23] == "1.4" })
        // El CSV publica el par de pendiente de la fila, con el signo real de cada columna.
        assertEquals("-300.0", rows.single { it[22] == "SLOPE_FV" }[23])
        assertEquals("300.0", rows.single { it[22] == "SFV" }[23])
    }

    /**
     * P2 (Wave 4a): una fila F-V-P finita pero CONTRADICTORIA (`sfvNsPerM != -slopeFv`) creada con
     * `insertAssessmentRaw` (que salta la validacion de `FvpDao.save`) no puede salir al JSON: la
     * exportacion se rechaza con `IllegalArgumentException` en vez de publicar el par imposible.
     *
     * Se comprueba primero el control negativo (la misma fila con el par coherente SI se exporta)
     * para que el rechazo no pueda confundirse con un fallo del fixture.
     */
    @Test
    fun streamingExportRefusesFiniteMismatchedSlopeAndSfvRows() = runBlocking {
        val athleteId = database.athleteDao().insert(
            AthleteEntity(displayName = "FVP mismatch", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1),
        )
        val exportedAt = Instant.parse("2026-01-01T00:00:00Z")
        database.fvpDao().insertAssessmentRaw(fvpAssessmentEntity(athleteId, "fvp-good-sign", 6L))
        val coherent = StringWriter().also { DataExportRepository(database).writeJson(it, exportedAt) }.toString()
        assertTrue("la fila coherente si se exporta", coherent.contains("fvp-good-sign"))

        // Misma fila con el par de signo invertido por una via que salta la validacion del DAO.
        database.fvpDao().insertAssessmentRaw(
            fvpAssessmentEntity(athleteId, "fvp-bad-sign", 7L).copy(sfvNsPerM = 299.0),
        )
        val failure = runCatching {
            DataExportRepository(database).writeJson(StringWriter(), exportedAt)
        }.exceptionOrNull()
        assertTrue(
            "el export debe rechazar la fila contradictoria en vez de escribirla: $failure",
            failure is IllegalArgumentException,
        )
    }

    @Test
    fun fvpKeysetPagingRetainsMoreThanOnePageOfRoots() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "FVP paging", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1))
        val expectedIds = mutableListOf<String>()
        repeat(300) { index ->
            val assessmentId = database.fvpDao().insertAssessmentRaw(fvpAssessmentEntity(athleteId, "fvp-page-$index", index.toLong()))
            expectedIds += assessmentId.toString()
            val conditionId = database.fvpDao().insertConditionRaw(FvpConditionEntity(assessmentId = assessmentId, ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0, totalMassKg = 80.0, pushOffM = 0.4, selectedHeightM = 0.4, selectedAttemptOrdinal = null))
            database.fvpDao().insertAttemptRaw(FvpAttemptEntity(conditionId = conditionId, ordinal = 0, heightM = 0.4, method = "MANUAL", valid = true))
        }
        // Ignora las lecturas del fixture; mide solo la exportación.
        selectCount.set(0)
        val output = StringWriter()
        DataExportRepository(database).writeJson(output, Instant.parse("2026-01-01T00:00:00Z"))
        val fvp = Json.parseToJsonElement(output.toString()).jsonObject["fvp"]!!.jsonArray
        assertEquals(300, fvp.size)
        assertEquals(expectedIds, fvp.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(fvp.all { it.jsonObject["conditions"]!!.jsonArray.size == 1 })
        assertTrue(fvp.all { it.jsonObject["conditions"]!!.jsonArray.single().jsonObject["attempts"]!!.jsonArray.size == 1 })
        // Dos páginas de raíces + páginas de detalle, no un SELECT por raíz.
        assertTrue(selectCount.get() < 50)
    }

    /**
     * B3 (esquema 5): la procedencia B0 viaja en las dos proyecciones paginadas. Con `limit` pequeño la
     * caminata keyset no puede perder ni repetir filas, y cada página conserva los valores exactos
     * (fechas epoch ms, ids y los PTS `Long` sin pasar por `Double`).
     */
    @Test
    fun fvpProvenanceKeysetPagingWithSmallLimitHasNoLossAndExactValuesPerPage() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "FVP provenance", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1))
        val assessmentIds = mutableListOf<Long>()
        repeat(5) { index ->
            val assessmentId = database.fvpDao().insertAssessmentRaw(
                fvpAssessmentEntity(athleteId, "fvp-prov-$index", index.toLong()).copy(
                    acquiredAt = 1_000L + index,
                    acquisitionContextKind = "SAME_ACQUISITION",
                    acquisitionContextKey = "ctx-$index",
                    bodyMassMeasurement = "WEIGHED",
                    bodyMassMeasuredAt = 2_000L + index,
                ),
            )
            assessmentIds += assessmentId
            val conditionId = database.fvpDao().insertConditionRaw(
                FvpConditionEntity(
                    assessmentId = assessmentId, ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0,
                    totalMassKg = 80.0, pushOffM = 0.4, selectedHeightM = 0.4, selectedAttemptOrdinal = 0,
                    addedLoadSource = "NOMINAL", addedLoadMeasuredAt = 3_000L + index,
                ),
            )
            database.fvpDao().insertAttemptRaw(
                FvpAttemptEntity(
                    conditionId = conditionId, ordinal = 0, heightM = 0.4, method = "IMPORTED", valid = true,
                    sourceRecordedAt = 4_000L + index, sourceAthleteId = 70L + index,
                    acquiredAt = 5_000L + index, reuseConfirmedAt = 6_000L + index,
                    reuseConfirmationVersion = "fvp-reuse-1",
                    takeoffPtsUs = 9_007_199_254_740_993L - index,
                    takeoffPreviousPtsUs = 9_007_199_254_740_992L - index,
                    takeoffNextPtsUs = 9_007_199_254_740_994L - index,
                    landingPtsUs = 10_000_000L + index,
                    landingPreviousPtsUs = 9_999_999L + index,
                    landingNextPtsUs = 10_000_001L + index,
                    timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = "ev-$index",
                ),
            )
        }

        // Raices: páginas de 2,2,1 con el `limit` pequeño; ninguna fila perdida ni repetida.
        val dao = database.dataExportDao()
        val rootPages = mutableListOf<List<ExportFvpAssessmentRow>>()
        var afterDate: Long? = null
        var afterId = ""
        while (true) {
            val page = dao.fvpPage(afterDate, afterId, limit = 2)
            if (page.isEmpty()) break
            rootPages += page
            val last = page.last()
            afterDate = last.dateTime
            afterId = last.idText
        }
        assertEquals(listOf(2, 2, 1), rootPages.map { it.size })
        val roots = rootPages.flatten()
        assertEquals(5, roots.size)
        assertEquals(listOf("1", "2", "3", "4", "5"), roots.map { it.idText })
        roots.forEach { row ->
            val index = row.idText.toInt() - 1
            assertEquals(1_000L + index, row.acquiredAt)
            assertEquals("SAME_ACQUISITION", row.acquisitionContextKind)
            assertEquals("ctx-$index", row.acquisitionContextKey)
            assertEquals("WEIGHED", row.bodyMassMeasurement)
            assertEquals(2_000L + index, row.bodyMassMeasuredAt)
        }

        // Detalles condición+intento: mismas garantías de paginación y valores exactos.
        val detailPages = mutableListOf<List<ExportFvpDetailRow>>()
        var detailDate: Long? = null
        var detailId = ""
        var conditionOrdinal = -1
        var conditionId = -1L
        var attemptOrdinal = -1
        var attemptId = -1L
        while (true) {
            val page = dao.fvpDetailPage(
                assessmentIds, detailDate, detailId, conditionOrdinal, conditionId, attemptOrdinal, attemptId, limit = 2,
            )
            if (page.isEmpty()) break
            detailPages += page
            val last = page.last()
            detailDate = last.parentDateTime
            detailId = last.parentIdText
            conditionOrdinal = last.conditionOrdinal
            conditionId = last.conditionId
            attemptOrdinal = last.attemptOrdinal ?: -1
            attemptId = last.attemptId ?: -1L
        }
        assertEquals(listOf(2, 2, 1), detailPages.map { it.size })
        val details = detailPages.flatten()
        details.forEach { row ->
            val index = row.parentIdText.toInt() - 1
            assertEquals("NOMINAL", row.addedLoadSource)
            assertEquals(3_000L + index, row.addedLoadMeasuredAt)
            assertEquals(4_000L + index, row.sourceRecordedAt)
            assertEquals(70L + index, row.sourceAthleteId)
            assertEquals(5_000L + index, row.acquiredAt)
            assertEquals(6_000L + index, row.reuseConfirmedAt)
            assertEquals("fvp-reuse-1", row.reuseConfirmationVersion)
            assertEquals(9_007_199_254_740_993L - index, row.takeoffPtsUs)
            assertEquals(9_007_199_254_740_992L - index, row.takeoffPreviousPtsUs)
            assertEquals(9_007_199_254_740_994L - index, row.takeoffNextPtsUs)
            assertEquals(10_000_000L + index, row.landingPtsUs)
            assertEquals(9_999_999L + index, row.landingPreviousPtsUs)
            assertEquals(10_000_001L + index, row.landingNextPtsUs)
            assertEquals("IDENTITY_VERIFIED", row.timeMapping)
            assertEquals("ev-$index", row.timeMappingEvidenceKey)
        }
    }

    /**
     * B3 (esquema 5): documento con más de una página de raíces F-V-P (300 > `DATA_EXPORT_PAGE_SIZE`).
     * Los enteros PTS y la procedencia deben sobrevivir al streaming sin pérdida y sin pasar por
     * `Double`: `9007199254740993` no es representable y `10^7` se degradar\u00eda a `1.0E7`.
     */
    @Test
    fun streamingExportKeepsFvpProvenanceAcrossMoreThanOneRootPage() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "FVP paging provenance", birthDate = null, sex = null, archivedAt = null, createdAt = 1, updatedAt = 1))
        repeat(300) { index ->
            val assessmentId = database.fvpDao().insertAssessmentRaw(
                fvpAssessmentEntity(athleteId, "fvp-prov-page-$index", index.toLong()).copy(
                    acquiredAt = 1_000L + index,
                    acquisitionContextKind = "HISTORICAL_COMPOSITE",
                    acquisitionContextKey = "ctx-page-$index",
                    bodyMassMeasurement = "DECLARED",
                    bodyMassMeasuredAt = 2_000L + index,
                ),
            )
            val conditionId = database.fvpDao().insertConditionRaw(
                FvpConditionEntity(
                    assessmentId = assessmentId, ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0,
                    totalMassKg = 80.0, pushOffM = 0.4, selectedHeightM = 0.4, selectedAttemptOrdinal = 0,
                    addedLoadSource = "WEIGHED", addedLoadMeasuredAt = 3_000L + index,
                ),
            )
            database.fvpDao().insertAttemptRaw(
                FvpAttemptEntity(
                    conditionId = conditionId, ordinal = 0, heightM = 0.4, method = "IMPORTED", valid = true,
                    sourceRecordedAt = 4_000L + index, sourceAthleteId = 70L + index,
                    acquiredAt = 5_000L + index, reuseConfirmedAt = 6_000L + index,
                    reuseConfirmationVersion = "fvp-reuse-1",
                    takeoffPtsUs = 9_007_199_254_740_993L,
                    takeoffPreviousPtsUs = 9_007_199_254_740_992L,
                    takeoffNextPtsUs = 9_007_199_254_740_994L,
                    landingPtsUs = 10_000_000L,
                    landingPreviousPtsUs = 9_999_999L,
                    landingNextPtsUs = 10_000_001L,
                    timeMapping = "IDENTITY_VERIFIED", timeMappingEvidenceKey = "ev-page-$index",
                ),
            )
        }
        val exportedAt = Instant.parse("2026-01-01T00:00:00Z")
        val repository = DataExportRepository(database)

        val json = StringWriter().also { repository.writeJson(it, exportedAt) }.toString()
        val fvp = Json.parseToJsonElement(json).jsonObject["fvp"]!!.jsonArray
        assertEquals(300, fvp.size)
        // Orden canónico por (dateTime, idText) = orden de inserción; sin huecos entre páginas.
        assertEquals((0 until 300).map { "fvp-prov-page-$it" }, fvp.map { it.jsonObject["sessionKey"]!!.jsonPrimitive.content })
        fvp.forEachIndexed { index, element ->
            val entry = element.jsonObject
            assertEquals("ctx-page-$index", entry["acquisitionContextKey"]!!.jsonPrimitive.content)
            assertEquals(1_000L + index, Instant.parse(entry["acquiredAtUtc"]!!.jsonPrimitive.content).toEpochMilli())
            val condition = entry["conditions"]!!.jsonArray.single().jsonObject
            assertEquals("WEIGHED", condition["addedLoadSource"]!!.jsonPrimitive.content)
            val attempt = condition["attempts"]!!.jsonArray.single().jsonObject
            assertEquals("fvp-reuse-1", attempt["reuseConfirmationVersion"]!!.jsonPrimitive.content)
            assertEquals("ev-page-$index", attempt["timeMappingEvidenceKey"]!!.jsonPrimitive.content)
            // Entero exacto en las dos páginas (contenido = token decimal, nunca Double).
            assertEquals("9007199254740993", attempt["takeoffPtsUs"]!!.jsonPrimitive.content)
            assertEquals("10000000", attempt["landingPtsUs"]!!.jsonPrimitive.content)
            assertFalse(attempt["takeoffPtsUs"]!!.jsonPrimitive.isString)
        }
        assertFalse(json.contains("9.007199254740992E15"))
        assertFalse(json.contains("1.0E7"))
        assertTrue(json.contains("\"takeoffPtsUs\": 9007199254740993"))

        val csv = StringWriter().also { repository.writeCsv(it, exportedAt) }.toString()
        assertEquals(29, csv.lineSequence().first().split(',').size)
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertEquals(300, rows.count { it[22] == "TAKE_OFF_PTS" })
        assertTrue(rows.filter { it[22] == "TAKE_OFF_PTS" }.all { it[23] == "9007199254740993" && it[24] == "MICROSECOND" })
        assertEquals(300, rows.count { it[22] == "LANDING_PTS" })
        assertTrue(rows.filter { it[22] == "LANDING_PTS" }.all { it[23] == "10000000" })
        assertEquals(300, rows.count { it[22] == "ACQUISITION_CONTEXT_KIND" })
        assertTrue(rows.filter { it[22] == "ACQUISITION_CONTEXT_KEY" }.all { it[26].startsWith("ctx-page-") })
        assertFalse(csv.contains("9.007199254740992E15"))
        assertFalse(csv.contains("1.0E7"))
    }

    private fun fvpAssessmentEntity(athleteId: Long, sessionKey: String, dateTime: Long, conditionCount: Int = 1) = FvpAssessmentEntity(
        sessionKey = sessionKey, athleteId = athleteId, protocolId = "CMJ", dateTime = dateTime,
        bodyMassKg = 80.0, bodyMassSource = "PROFILE", pushOffMode = "COMMON", pushOffM = 0.4,
        pushOffSource = "MEASURED", sourceFps = 240.0, algorithmVersion = "fvp-poc-1.0",
        methodVersion = "fvp-app-1.0", f0N = 1_200.0, slopeFv = -300.0, sfvNsPerM = 300.0,
        v0Mps = 1.4, pmaxW = 900.0, r2 = 0.98, conditionCount = conditionCount, vMinMps = 0.2,
        vMaxMps = 1.6, fMinN = 400.0, fMaxN = 1_800.0, f0RelNPerKg = 15.0,
        pmaxRelWPerKg = 11.25, warningsCsv = "LOW_R2",
    )

    @Test
    fun mixedPagedJsonAndCsvPreserveCompleteCrossFamilyFixture() = runBlocking {
        val dateTime = 42_000L
        suspend fun insertJump(id: Long, primaryKey: String) {
            val assessmentId = database.jumpDao().insertAssessment(
                AssessmentEntity(id = id, sessionKey = "mixed-jump-$id", protocolId = "CMJ", dateTime = dateTime, primaryMetricKey = primaryKey, primaryMetricValue = id.toDouble(), primaryMetricUnit = "UNIT"),
            )
            if (id == 2L) {
                val attemptId = database.jumpDao().insertAttempt(AttemptEntity(assessmentId = assessmentId, ordinal = 0, side = "LEFT", source = "IMPORTED", videoUri = null, detectedFps = 30, dropHeightCm = null, distanceCm = null))
                database.jumpDao().insertMetrics((0..256).map { index ->
                    AttemptMetricEntity(attemptId, "DETAIL_${index.toString().padStart(3, '0')}", index, index.toDouble(), "UNIT")
                })
            }
        }
        listOf(2L, 10L, 100L).forEach { insertJump(it, "PRIMARY_${it}") }

        fun encoderSession(id: Long, exercise: String, total: Int): EncoderSessionEntity = EncoderSessionEntity(
            id = id, sessionKey = "mixed-encoder-$id", exercise = exercise, loadKg = id.toDouble(), dateTime = dateTime,
            source = "IMPORTED", videoUri = null, observedFps = 120.0, nominalFps = 120.0, captureFps = null,
            timingMode = "NORMAL", slowMotionFactor = 1.0, calibrationFrameIndex = 0,
            calibrationAX = 0.0, calibrationAY = 0.0, calibrationBX = 1.0, calibrationBY = 0.0,
            referenceLengthM = 1.0, metersPerPixel = 1.0, analysisVersion = 1, minimumVelocityMps = 0.1,
            noiseMultiplier = 1.0, exitRatio = 0.5, minimumDirectionHoldUs = 1, stationaryDurationUs = 1,
            pauseDurationUs = 1, minimumPhaseDurationUs = 1, minimumPhaseDisplacementM = 0.1,
            romOutlierFraction = 0.2, minimumMedianConfidence = 0.5, maximumUncertainFraction = 0.5,
            smoothingWindowSamples = 5, velocityEnterMps = null, velocityExitMps = null,
            totalRepetitions = total, validRepetitions = total, bestMcv = id.toDouble(), setVelocityLoss = null,
        )
        database.encoderDao().insertSession(encoderSession(2L, "SQUAT", 0))
        val encoder10 = database.encoderDao().insertSession(encoderSession(10L, "BENCH_PRESS", 2))
        database.encoderDao().insertSession(encoderSession(100L, "DEADLIFT", 0))
        val repetition10 = database.encoderDao().insertRepetition(
            EncoderRepetitionEntity(sessionId = encoder10, ordinal = 0, startSample = 0, endSample = 1, eccentricStartSample = 0, eccentricEndSample = 0, concentricStartSample = 0, concentricEndSample = 1, quality = "VALID", reasons = ""),
        )
        database.encoderDao().insertMetrics(listOf(
            EncoderRepMetricEntity(repetition10, "MCV", 1.0, "MPS", "VALID", null),
            EncoderRepMetricEntity(repetition10, "ROM", 0.4, "M", "VALID", null),
        ))
        database.encoderDao().insertRepetition(
            EncoderRepetitionEntity(sessionId = encoder10, ordinal = 1, startSample = 1, endSample = 2, eccentricStartSample = 1, eccentricEndSample = 1, concentricStartSample = 1, concentricEndSample = 2, quality = "UNCERTAIN", reasons = "NOISY"),
        )

        val repository = DataExportRepository(database)
        val exportedAt = Instant.parse("2026-01-01T00:00:00Z")
        val expectedDocument = repository.snapshot().copy(exportedAt = exportedAt.toString())
        val expectedJson = JsonDataExportWriter.write(expectedDocument)
        val expectedCsv = CsvDataExportWriter.write(expectedDocument)
        val actualJson = StringWriter().also { repository.writeJson(it, exportedAt) }.toString()
        val actualCsv = StringWriter().also { repository.writeCsv(it, exportedAt) }.toString()
        assertEquals(expectedJson, actualJson)
        assertEquals(expectedCsv, actualCsv)

        val jsonMeasurements = Json.parseToJsonElement(actualJson).jsonObject["measurements"]!!.jsonArray
        assertEquals(listOf("encoder:10", "encoder:100", "encoder:2", "jump:10", "jump:100", "jump:2"), jsonMeasurements.map {
            "${it.jsonObject["kind"]!!.jsonPrimitive.content}:${it.jsonObject["id"]!!.jsonPrimitive.content}"
        })
        assertEquals(0, jsonMeasurements.single { it.jsonObject["kind"]!!.jsonPrimitive.content == "jump" && it.jsonObject["id"]!!.jsonPrimitive.content == "10" }.jsonObject["attempts"]!!.jsonArray.size)
        assertEquals(0, jsonMeasurements.single { it.jsonObject["kind"]!!.jsonPrimitive.content == "encoder" && it.jsonObject["id"]!!.jsonPrimitive.content == "100" }.jsonObject["repetitions"]!!.jsonArray.size)
        val jumpDetails = jsonMeasurements.last().jsonObject["attempts"]!!.jsonArray.single().jsonObject["metrics"]!!.jsonArray
        assertEquals(257, jumpDetails.size)
        assertEquals((0..256).map { "DETAIL_${it.toString().padStart(3, '0')}" }, jumpDetails.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        val encoderRepetitions = jsonMeasurements.first().jsonObject["repetitions"]!!.jsonArray
        assertEquals(listOf("MCV", "ROM"), encoderRepetitions.first().jsonObject["metrics"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals(0, encoderRepetitions.last().jsonObject["metrics"]!!.jsonArray.size)

        val csvRows = actualCsv.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertEquals(listOf("PRIMARY_2", "DETAIL_000", "DETAIL_001"), csvRows.filter { it[2] == "jump" && it[3] == "2" }.take(3).map { it[22] })
        assertEquals(listOf("PRIMARY_2") + (0..256).map { "DETAIL_${it.toString().padStart(3, '0')}" }, csvRows.filter { it[2] == "jump" && it[3] == "2" }.map { it[22] })
        assertEquals(listOf("encoder:10", "encoder:100", "encoder:2", "jump:10", "jump:100", "jump:2"), csvRows.map { "${it[2]}:${it[3]}" }.distinct())
    }
}
