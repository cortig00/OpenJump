package com.openjump.app.data.export

import java.time.Instant
import java.io.StringWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExportWritersTest {
    private val athlete = ExportAthlete("9007199254740993", "=Unsafe, \"Name\"\n", "2000-01-02", "F")

    @Test
    fun jsonIsVersionedHierarchicalAndKeepsTextExact() {
        val document = DataExportDocument(
            exportedAt = Instant.parse("2026-08-30T21:53:00Z").toString(),
            athletes = listOf(athlete),
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "7", dateTimeUtc = "2026-08-30T21:00:00Z",
                    athleteId = athlete.id, protocolId = "CMJ",
                    primaryMetric = ExportMetric("HEIGHT_CM", 42.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                    attempts = listOf(
                        ExportJumpAttempt(
                            ExportJumpContext(side = "LEFT", ordinal = 0),
                            metrics = listOf(
                                ExportMetric(
                                    "ESTIMATED_PEAK_POWER_SAYERS_W",
                                    4_118.4,
                                    "WATT",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val json = JsonDataExportWriter.write(document)
        assertTrue(json.contains("openjump-graph-export"))
        assertTrue(json.contains("videoDataIncluded"))
        assertTrue(json.contains("=Unsafe, \\\"Name\\\"\\n"))
        assertTrue(json.contains("ESTIMATED_PEAK_POWER_SAYERS_W"))
        assertTrue(json.contains("WATT"))
        val csv = CsvDataExportWriter.write(document)
        assertTrue(csv.contains("ESTIMATED_PEAK_POWER_SAYERS_W"))
        assertTrue(csv.contains("WATT"))
        assertFalse(json.contains("videoUri"))
        assertFalse(json.contains("encoder_samples"))
    }

    @Test
    fun rootPrimarySurvivesMissingAttemptsAndLegacyNullAthlete() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "legacy", dateTimeUtc = "2026-01-01T00:00:00Z",
                    primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", isPrimary = true),
                ),
            ),
        )
        val json = JsonDataExportWriter.write(document)
        val rows = CsvDataExportWriter.observations(document)
        assertTrue(json.contains("\"primaryMetric\""))
        assertTrue(json.contains("\"athleteId\": null"))
        assertEquals(1, rows.size)
        assertEquals("legacy", rows.single().measurementId)
        assertEquals(null, rows.single().attemptOrdinal)
        assertTrue(rows.single().isPrimary)
    }

    @Test
    fun sparseAttemptOrdinalsRetainTheirOwnContext() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "sparse", dateTimeUtc = "2026-01-01T00:00:00Z",
                    attempts = listOf(
                        ExportJumpAttempt(ExportJumpContext(side = "RIGHT", ordinal = 2, dropHeightCm = 20.0), listOf(ExportMetric("M2", 2.0, "U"))),
                        ExportJumpAttempt(ExportJumpContext(side = "LEFT", ordinal = 5, distanceCm = 40.0), listOf(ExportMetric("M5", 5.0, "U"))),
                    ),
                ),
            ),
        )
        val rows = CsvDataExportWriter.observations(document)
        assertEquals("RIGHT", rows.single { it.attemptOrdinal == 2 }.side)
        assertTrue(rows.single { it.attemptOrdinal == 2 }.dropHeightCm == 20.0)
        assertEquals("LEFT", rows.single { it.attemptOrdinal == 5 }.side)
        assertTrue(rows.single { it.attemptOrdinal == 5 }.distanceCm == 40.0)
    }

    @Test
    fun sparseOrdinalExactDuplicateProvidesPrimaryContext() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "sparse-primary", dateTimeUtc = "2026-01-01T00:00:00Z",
                    primaryMetric = ExportMetric("HEIGHT", 2.0, "CENTIMETER", isPrimary = true),
                    attempts = listOf(
                        ExportJumpAttempt(
                            ExportJumpContext(side = "RIGHT", ordinal = 2, dropHeightCm = 20.0, distanceCm = 40.0),
                            listOf(ExportMetric("HEIGHT", 2.0, "CENTIMETER", ordinal = 0)),
                        ),
                    ),
                ),
            ),
        )
        val canonical = document.canonicalized()
        val normalized = canonical.measurements.single()
        assertEquals(2, normalized.primaryMetricContext?.ordinal)
        assertEquals(canonical, canonical.canonicalized())
        val row = CsvDataExportWriter.observations(document).single()
        assertEquals(2, row.attemptOrdinal)
        assertEquals("RIGHT", row.side)
        assertTrue(row.dropHeightCm == 20.0)
        assertTrue(row.distanceCm == 40.0)
    }

    @Test
    fun exactPrimaryDuplicateIsRemovedButLaterSameKeyRemainsInJsonAndCsv() {
        val primary = ExportMetric("HEIGHT", 2.0, "CENTIMETER", isPrimary = true)
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "2", dateTimeUtc = "2026-01-02T00:00:00Z", primaryMetric = primary,
                    attempts = listOf(
                        ExportJumpAttempt(
                            ExportJumpContext(side = "LEFT", ordinal = 0, dropHeightCm = 15.0, distanceCm = 25.0),
                            listOf(
                                ExportMetric("HEIGHT", 2.0, "CENTIMETER", ordinal = 0),
                                ExportMetric("HEIGHT", 3.0, "CENTIMETER", ordinal = 1),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val normalized = document.canonicalized().measurements.single()
        assertEquals(1, normalized.attempts.single().metrics.size)
        assertEquals(1, normalized.attempts.single().metrics.single().ordinal)
        assertEquals(2, JsonDataExportWriter.write(document).split("\"key\": \"HEIGHT\"").size - 1)
        val rows = CsvDataExportWriter.observations(document).filter { it.metricKey == "HEIGHT" }
        assertEquals(listOf(2.0, 3.0), rows.map { it.metricValue })
        assertEquals(listOf(0, 0), rows.map { it.attemptOrdinal })
        assertEquals(2, rows.size)
        assertEquals("LEFT", rows.first().side)
        assertTrue(rows.first().dropHeightCm == 15.0)
        assertTrue(rows.first().distanceCm == 25.0)
    }

    @Test
    fun csvQuotesTextAndHardensFormulaWithoutChangingJson() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z", athletes = listOf(athlete),
            measurements = listOf(
                ExportMeasurement(
                    kind = "encoder", id = "2", dateTimeUtc = "2026-01-01T00:00:00Z",
                    athleteId = athlete.id, exercise = "PRESS", loadKg = 20.0,
                    sessionSummaries = listOf(ExportEncoderSummary("BEST_MCV", 1.2, "METER_PER_SECOND")),
                ),
            ),
        )
        val csv = CsvDataExportWriter.write(document)
        assertTrue(csv.contains("'=Unsafe, \"\"Name\"\"\n"))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(29, csv.lineSequence().first().split(',').size)
    }

    @Test
    fun csvHardensFormulaPrefixesAfterLeadingControlsButPreservesNumericNegativesAndText() {
        listOf("\n=1+1", "\r\t-1+1", "\u0001@SUM(A1)", " \t+cmd", "\t=1+1").forEach { unsafe ->
            assertTrue(CsvDataExportWriter.cell(unsafe).removeSurrounding("\"").startsWith("'"))
        }
        assertEquals("-1.25", CsvDataExportWriter.cell(-1.25))
        assertEquals("'\n=1+1", CsvDataExportWriter.cell("\n=1+1").removeSurrounding("\""))
        assertEquals("\"line1\nline2, \"\"quoted\"\"\"", CsvDataExportWriter.cell("line1\nline2, \"quoted\""))
    }

    @Test
    fun observationsAreDeterministicallySorted() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(kind = "jump", id = "2", dateTimeUtc = "2026-01-02T00:00:00Z", primaryMetric = ExportMetric("HEIGHT", 2.0, "CM", isPrimary = true)),
                ExportMeasurement(kind = "jump", id = "1", dateTimeUtc = "2026-01-01T00:00:00Z", primaryMetric = ExportMetric("HEIGHT", 1.0, "CM", isPrimary = true)),
            ),
        )
        val rows = CsvDataExportWriter.observations(document)
        assertEquals(listOf("1", "2"), rows.map { it.measurementId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteMetricFailsExport() {
        JsonDataExportWriter.write(
            DataExportDocument(
                exportedAt = "2026-01-01T00:00:00Z",
                measurements = listOf(ExportMeasurement(kind = "encoder", id = "1", dateTimeUtc = "2026-01-01T00:00:00Z", sessionSummaries = listOf(ExportEncoderSummary("BEST_MCV", Double.NaN, "METER_PER_SECOND")))),
            ),
        )
    }

    @Test
    fun streamingJsonEmptyMatchesLegacyExactly() = runBlocking {
        val expected = JsonDataExportWriter.write(DataExportDocument(exportedAt = "2026-01-01T00:00:00Z"))
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply {
            begin("2026-01-01T00:00:00Z")
            endAthletes()
            finish()
        }
        assertEquals(expected, output.toString())
    }

    @Test
    fun streamingJsonJumpWithoutChildrenMatchesLegacyExactly() = runBlocking {
        val row = ExportMeasurementRow(7, 2, "7", null, null, null, null, null, null, protocolId = "CMJ", primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 30.0, primaryMetricUnit = "CENTIMETER")
        val expected = JsonDataExportWriter.write(DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", measurements = listOf(ExportMeasurement("jump", "7", "1970-01-01T00:00:00.002Z", protocolId = "CMJ", primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0, isPrimary = true)))))
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply { begin("2026-01-01T00:00:00Z"); endAthletes(); beginJump(row); endJump(); finish() }
        assertEquals(expected, output.toString())
    }

    @Test
    fun streamingJsonJumpChildrenMatchesLegacyExactly() = runBlocking {
        val row = ExportMeasurementRow(7, 2, "7", null, null, null, null, null, null, protocolId = "CMJ", primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 30.0, primaryMetricUnit = "CENTIMETER")
        val detail = ExportJumpDetailRow(2, 7, "7", 8, 1, "LEFT", 10.0, null, 1, 2, "FLIGHT", 400.0, "MILLISECOND")
        val expected = JsonDataExportWriter.write(
            DataExportDocument(
                exportedAt = "2026-01-01T00:00:00Z",
                measurements = listOf(
                    ExportMeasurement(
                        "jump", "7", "1970-01-01T00:00:00.002Z", protocolId = "CMJ",
                        primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                        attempts = listOf(
                            ExportJumpAttempt(
                                ExportJumpContext("LEFT", 1, 10.0),
                                listOf(ExportMetric("FLIGHT", 400.0, "MILLISECOND", ordinal = 2)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply { begin("2026-01-01T00:00:00Z"); endAthletes(); beginJump(row); beginJumpAttempt(detail); jumpMetric(detail); endJumpAttempt(); endJump(); finish() }
        assertEquals(expected, output.toString())
    }

    @Test
    fun streamingJsonEmptyQualityReasonsMatchesLegacyExactly() = runBlocking {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "encoder", id = "10", dateTimeUtc = "2026-01-01T00:00:00Z",
                    repetitions = listOf(ExportEncoderRepetition(ordinal = 0, quality = "VALID", metrics = listOf(ExportMetric("MCV", 1.0, "METER_PER_SECOND")))),
                ),
            ),
        )
        assertEquals(JsonDataExportWriter.write(document), streamDocument(document, json = true))
    }

    @Test
    fun streamingCsvMergesPrimaryAtLegacyMetricKeyPosition() = runBlocking {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "7", dateTimeUtc = "2026-01-01T00:00:00Z", protocolId = "CMJ",
                    primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                    attempts = listOf(ExportJumpAttempt(ExportJumpContext(ordinal = 0), listOf(
                        ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0),
                        ExportMetric("FLIGHT_TIME_MS", 400.0, "MILLISECOND", ordinal = 1),
                    ))),
                ),
            ),
        ).canonicalized()
        val legacyKeys = CsvDataExportWriter.observations(document).map { it.metricKey }
        assertEquals(listOf("FLIGHT_TIME_MS", "HEIGHT_CM"), legacyKeys)
        val streamedKeys = streamDocument(document, json = false).lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',')[22] }.toList()
        assertEquals(legacyKeys, streamedKeys)
    }

    @Test
    fun streamingCsvSortsEncoderSummariesByLegacyMetricKeyOrder() = runBlocking {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "encoder", id = "10", dateTimeUtc = "2026-01-01T00:00:00Z",
                    sessionSummaries = listOf(
                        ExportEncoderSummary("TOTAL_REPETITIONS", 2.0, "COUNT"),
                        ExportEncoderSummary("VALID_REPETITIONS", 2.0, "COUNT"),
                        ExportEncoderSummary("BEST_MCV", 1.2, "METER_PER_SECOND"),
                        ExportEncoderSummary("SET_VELOCITY_LOSS", 4.0, "PERCENT"),
                    ),
                ),
            ),
        ).canonicalized()
        val legacyKeys = CsvDataExportWriter.observations(document).map { it.metricKey }
        assertEquals(listOf("BEST_MCV", "SET_VELOCITY_LOSS", "TOTAL_REPETITIONS", "VALID_REPETITIONS"), legacyKeys)
        val streamedKeys = streamDocument(document, json = false)
            .lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',')[22] }.toList()
        assertEquals(legacyKeys, streamedKeys)
        assertEquals(CsvDataExportWriter.write(document), streamDocument(document, json = false))
    }

    @Test
    fun streamingCsvEmptyMatchesLegacyExactly() = runBlocking {
        val expected = CsvDataExportWriter.write(DataExportDocument(exportedAt = "2026-01-01T00:00:00Z"))
        val output = StringWriter()
        StreamingCsvDataExportWriter(output).apply {
            begin("2026-01-01T00:00:00Z")
            endAthletes()
            finish()
        }
        assertEquals(expected, output.toString())
    }

    @Test
    fun streamingWritersMatchLegacyForCompleteMixedDocument() = runBlocking {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            athletes = listOf(
                ExportAthlete("10", "Zoë, \"jump\"\\n", null, null),
                ExportAthlete("2", "Atleta Ω", "1980-01-02", "F"),
            ),
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "2", dateTimeUtc = "2026-01-01T00:00:00Z", athleteId = "2",
                    protocolId = "CMJ", primaryMetric = ExportMetric("HEIGHT", 2.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                    attempts = listOf(ExportJumpAttempt(ExportJumpContext("LEFT", 3, 12.0, null), listOf(
                        ExportMetric("HEIGHT", 2.0, "CENTIMETER", ordinal = 0),
                        ExportMetric("HEIGHT", 3.0, "CENTIMETER", ordinal = 1),
                    ))),
                ),
                ExportMeasurement(
                    kind = "encoder", id = "10", dateTimeUtc = "2026-01-01T00:00:00Z", athleteId = "10",
                    exercise = "PRESS\nΩ", loadKg = 80.0, analysisVersion = 3,
                    sessionSummaries = listOf(ExportEncoderSummary("SET_VELOCITY_LOSS", 4.0, "PERCENT"), ExportEncoderSummary("BEST_MCV", 1.2, "METER_PER_SECOND")),
                    repetitions = listOf(ExportEncoderRepetition(2, "VALID", listOf("z", "a"), listOf(ExportMetric("MCV", null, "METER_PER_SECOND", "INVALID", "sin dato")))),
                ),
            ),
        ).canonicalized()
        val json = streamDocument(document, json = true)
        val csv = streamDocument(document, json = false)
        assertEquals(JsonDataExportWriter.write(document), json)
        assertEquals(CsvDataExportWriter.write(document), csv)
    }

    @Test
    fun streamingPreservesEqualTimestampLexicalRootsAndEncoderMultiplicity() = runBlocking {
        val measurements = listOf(
            ExportMeasurement("jump", "2", "2026-01-01T00:00:00Z", protocolId = "CMJ", primaryMetric = ExportMetric("HEIGHT", 2.0, "CM", ordinal = 0, isPrimary = true)),
            ExportMeasurement("encoder", "10", "2026-01-01T00:00:00Z", exercise = "PRESS", sessionSummaries = listOf(ExportEncoderSummary("TOTAL_REPETITIONS", 2.0, "COUNT")), repetitions = listOf(
                ExportEncoderRepetition(0, "VALID", metrics = listOf(ExportMetric("MCV", 1.0, "MPS"), ExportMetric("ROM", 0.4, "M"))),
                ExportEncoderRepetition(1, "UNCERTAIN", metrics = listOf(ExportMetric("MCV", null, "MPS"))),
            )),
            ExportMeasurement("jump", "100", "2026-01-01T00:00:00Z", protocolId = "SJ", primaryMetric = ExportMetric("HEIGHT", 100.0, "CM", ordinal = 0, isPrimary = true)),
            ExportMeasurement("encoder", "2", "2026-01-01T00:00:00Z", exercise = "SQUAT", repetitions = listOf(ExportEncoderRepetition(0, "VALID", metrics = listOf(ExportMetric("MCV", 2.0, "MPS"))))),
            ExportMeasurement("jump", "10", "2026-01-01T00:00:00Z", protocolId = "SJ", primaryMetric = ExportMetric("HEIGHT", 10.0, "CM", ordinal = 0, isPrimary = true)),
        )
        val document = DataExportDocument("openjump-graph-export", 1, "2026-01-01T00:00:00Z", measurements = measurements).canonicalized()
        assertEquals(JsonDataExportWriter.write(document), streamDocument(document, json = true))
        assertEquals(CsvDataExportWriter.write(document), streamDocument(document, json = false))
        assertEquals(8, CsvDataExportWriter.observations(document).size)
    }

    @Test
    fun streamingWriterSplitsLargeFieldsIntoAtMost8192Characters() = runBlocking {
        val writes = mutableListOf<Int>()
        val output = object : StringWriter() {
            override fun write(value: String) {
                writes += value.length
                super.write(value)
            }
        }
        val row = ExportMeasurementRow(1, 1, "1", null, null, null, null, null, null, kind = "jump", protocolId = "CMJ", primaryMetricKey = "K".repeat(20_000), primaryMetricValue = 1.0, primaryMetricUnit = "U")
        StreamingCsvDataExportWriter(output).apply { begin("2026-01-01T00:00:00Z"); endAthletes(); beginJump(row); endJump(); finish() }
        assertTrue(writes.size > 2)
        assertTrue(writes.all { it <= 8192 })
        assertTrue(output.toString().contains("K".repeat(20_000)))
    }

    @Test
    fun cancellableChunkWriterStopsBetweenBoundedChunks() = runBlocking {
        val chunks = mutableListOf<String>()
        val child = launch {
            val childJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            var cancelled = false
            try {
                writeExportChunks("abcdefghij", chunkSize = 2) {
                    chunks += it
                    if (!cancelled) {
                        cancelled = true
                        childJob.cancel()
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected: the next checkpoint observes cancellation.
            }
        }
        child.join()
        assertEquals(listOf("ab"), chunks)
    }

    @Test
    fun documentWithoutFvpOmitsTheCollectionAndStillEmitsSchema5() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            measurements = listOf(
                ExportMeasurement(
                    kind = "jump", id = "7", dateTimeUtc = "2026-01-01T00:00:00Z", protocolId = "CMJ",
                    primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                ),
            ),
        )
        val json = JsonDataExportWriter.write(document)
        assertFalse(json.contains("\"fvp\""))
        assertTrue("el schema global es 5 tambien sin FVP", json.contains("\"schemaVersion\": 5"))
        assertEquals(json, JsonDataExportWriter.write(document.copy(fvp = emptyList())))
        assertEquals(CsvDataExportWriter.write(document), CsvDataExportWriter.write(document.copy(fvp = emptyList())))
    }

    @Test
    fun streamingJsonFvpMatchesLegacyExactly() = runBlocking {
        val document = DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", fvp = listOf(fvpAssessment()))
        val expected = JsonDataExportWriter.write(document)
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply {
            begin("2026-01-01T00:00:00Z")
            endAthletes()
            fvp(fvpAssessment())
            finish()
        }
        assertEquals(expected, output.toString())
        assertTrue(expected.contains("\"fvp\": ["))
    }

    @Test
    fun streamingJsonFvpAfterJumpsMatchesLegacyExactly() = runBlocking {
        val jump = ExportMeasurement(
            kind = "jump", id = "7", dateTimeUtc = "2026-01-01T00:00:00Z", athleteId = "2", protocolId = "CMJ",
            primaryMetric = ExportMetric("HEIGHT_CM", 30.0, "CENTIMETER", ordinal = 0, isPrimary = true),
        )
        val document = DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", fvp = listOf(fvpAssessment()), measurements = listOf(jump))
        val expected = JsonDataExportWriter.write(document)
        val row = ExportMeasurementRow(7, Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), "7", 2, null, null, null, null, null, protocolId = "CMJ", primaryMetricKey = "HEIGHT_CM", primaryMetricValue = 30.0, primaryMetricUnit = "CENTIMETER")
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply {
            begin("2026-01-01T00:00:00Z")
            endAthletes()
            beginJump(row)
            endJump()
            fvp(fvpAssessment())
            finish()
        }
        assertEquals(expected, output.toString())
        assertTrue(expected.indexOf("\"measurements\": [") < expected.indexOf("\"fvp\": ["))
    }

    @Test
    fun streamingCsvFvpRowsMatchLegacyAndKeepTheHeader() = runBlocking {
        val document = DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", fvp = listOf(fvpAssessment()))
        val expected = CsvDataExportWriter.write(document)
        val output = StringWriter()
        StreamingCsvDataExportWriter(output).apply {
            begin("2026-01-01T00:00:00Z")
            endAthletes()
            fvp(fvpAssessment())
            finish()
        }
        assertEquals(expected, output.toString())
        assertEquals(29, expected.lineSequence().first().split(',').size)
        val rows = expected.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertTrue(rows.all { it[2] == "fvp" })
        assertTrue(rows.all { it[0] == DATA_EXPORT_SCHEMA_VERSION.toString() })
        // Una fila por atributo: `metric_key` es la clave del atributo (indice 22).
        val byAttribute = rows.associateBy { "${it[22]}|${it[13]}|${it[14]}" }
        assertEquals(34, rows.count { it[1] == "FVP_ASSESSMENT" })
        assertEquals(17, rows.count { it[1] == "FVP_CONDITION" })
        assertEquals(31, rows.count { it[1] == "FVP_ATTEMPT" })
        assertEquals("1200.0", byAttribute.getValue("F0||")[23])
        assertEquals("NEWTON", byAttribute.getValue("F0||")[24])
        // Par de pendiente COHERENTE (sfvNsPerM = -slopeFv): el CSV debe publicar el signo real y
        // no el par imposible que traia el fixture (regresion W3.1).
        assertEquals("-300.0", byAttribute.getValue("SLOPE_FV||")[23])
        assertEquals("300.0", byAttribute.getValue("SFV||")[23])
        assertEquals("LOW_R2", byAttribute.getValue("WARNINGS||")[26])
        assertEquals("fvp-5", byAttribute.getValue("SESSION_KEY||")[26])
        assertEquals("TEXT", byAttribute.getValue("SESSION_KEY||")[24])
        assertEquals("2", byAttribute.getValue("BODY_MASS||")[5])
        assertEquals("CMJ", byAttribute.getValue("BODY_MASS||")[11])
        assertEquals("fvp note", byAttribute.getValue("BODY_MASS||")[28])
        // Condición e intento: ordinales, carga, método, validez, exclusión y vínculos de origen.
        assertEquals("0kg", byAttribute.getValue("CONDITION_KEY||0")[26])
        assertEquals("", byAttribute.getValue("CONDITION_KEY||0")[13])
        assertEquals("0.0", byAttribute.getValue("CONDITION_KEY||0")[16])
        assertEquals("20kg", byAttribute.getValue("CONDITION_KEY||1")[26])
        assertEquals("0.42", byAttribute.getValue("HEIGHT|0|0")[23])
        assertEquals("FLIGHT_TIME_VIDEO", byAttribute.getValue("METHOD|0|0")[26])
        assertEquals("MANUAL", byAttribute.getValue("METHOD|1|0")[26])
        assertEquals("VALID", byAttribute.getValue("VALID|0|0")[25])
        assertEquals("INVALID", byAttribute.getValue("VALID|1|0")[25])
        assertEquals("SHORT_FLIGHT", byAttribute.getValue("EXCLUSION_REASON|1|0")[26])
        assertEquals("7", byAttribute.getValue("SOURCE_ASSESSMENT_ID|0|0")[26])
        assertEquals("CMJ", byAttribute.getValue("SOURCE_PROTOCOL|0|0")[26])
        assertEquals("0", byAttribute.getValue("HEIGHT|0|1")[13])
        assertEquals("120.0", byAttribute.getValue("SOURCE_FPS|0|1")[23])
        // Procedencia B0 en CSV (esquema 5): una fila por columna Room, texto ISO en `metric_reason`.
        assertEquals("2026-01-01T00:00:05Z", byAttribute.getValue("ACQUIRED_AT||")[26])
        assertEquals("TEXT", byAttribute.getValue("ACQUIRED_AT||")[24])
        assertEquals("SAME_ACQUISITION", byAttribute.getValue("ACQUISITION_CONTEXT_KIND||")[26])
        assertEquals("ctx-1", byAttribute.getValue("ACQUISITION_CONTEXT_KEY||")[26])
        assertEquals("WEIGHED", byAttribute.getValue("BODY_MASS_MEASUREMENT||")[26])
        assertEquals("2026-01-01T00:00:06Z", byAttribute.getValue("BODY_MASS_MEASURED_AT||")[26])
        assertEquals("NOMINAL", byAttribute.getValue("ADDED_LOAD_SOURCE||0")[26])
        assertEquals("2026-01-01T00:00:07Z", byAttribute.getValue("ADDED_LOAD_MEASURED_AT||0")[26])
        // `UNKNOWN` declarado SI se emite; el nullable null se omite (solo la fecha desaparece).
        assertEquals("UNKNOWN", byAttribute.getValue("ADDED_LOAD_SOURCE||1")[26])
        assertFalse(rows.any { it[22] == "ADDED_LOAD_MEASURED_AT" && it[14] == "1" })
        assertEquals("2026-01-01T00:00:08Z", byAttribute.getValue("SOURCE_RECORDED_AT|0|0")[26])
        assertEquals("77", byAttribute.getValue("SOURCE_ATHLETE_ID|0|0")[26])
        assertEquals("2026-01-01T00:00:09Z", byAttribute.getValue("ATTEMPT_ACQUIRED_AT|0|0")[26])
        assertEquals("2026-01-01T00:00:10Z", byAttribute.getValue("REUSE_CONFIRMED_AT|0|0")[26])
        assertEquals("fvp-reuse-1", byAttribute.getValue("REUSE_CONFIRMATION_VERSION|0|0")[26])
        assertEquals("IDENTITY_VERIFIED", byAttribute.getValue("TIME_MAPPING|0|0")[26])
        assertEquals("ev-1", byAttribute.getValue("TIME_MAPPING_EVIDENCE_KEY|0|0")[26])
        assertEquals("UNKNOWN", byAttribute.getValue("TIME_MAPPING|1|0")[26])
        // PTS exactos: entero decimal en `metric_value`, nunca por Double ni en notacion cientifica.
        val pts = listOf("TAKE_OFF_PTS" to "9007199254740993", "TAKE_OFF_PREVIOUS_PTS" to "9007199254740992",
            "TAKE_OFF_NEXT_PTS" to "9007199254740994", "LANDING_PTS" to "10000000",
            "LANDING_PREVIOUS_PTS" to "9999999", "LANDING_NEXT_PTS" to "10000001")
        pts.forEach { (key, expected) ->
            assertEquals("la fila $key debe llevar el entero exacto", expected, byAttribute.getValue("$key|0|0")[23])
            assertEquals("la unidad de $key es microsegundos", "MICROSECOND", byAttribute.getValue("$key|0|0")[24])
            assertFalse("$key no puede viajar en notacion cientifica", byAttribute.getValue("$key|0|0")[23].contains('E'))
        }
        assertFalse(expected.contains("9.007199254740992E15"))
        assertFalse(expected.contains("1.0E7"))
        // Omission: el intento 1 (y el de la condicion 1) no declara procedencia nullable.
        assertFalse(rows.any { it[22] == "TAKE_OFF_PTS" && it[13] == "1" })
        assertFalse(rows.any { it[22] == "SOURCE_ATHLETE_ID" && it[13] == "1" })
        assertFalse(rows.any { it[22] == "REUSE_CONFIRMATION_VERSION" && it[13] == "1" })
        assertFalse(rows.any { it[22] == "TIME_MAPPING_EVIDENCE_KEY" && it[13] == "1" })
        assertEquals("UNKNOWN", byAttribute.getValue("TIME_MAPPING|0|1")[26])
    }

    /**
     * B3 (esquema 5): `metricLongValue` gana a `metricValue` en la columna `metric_value` y se
     * renderiza como decimal exacto; las magnitudes fisicas siguen usando el Double de siempre.
     */
    @Test
    fun csvRendersLongProvenanceExactlyAndKeepsDoubleMetricsUnchanged() {
        val model = fvpAssessment()
        val rows = CsvDataExportWriter.fvpObservations(model)
        val takeOff = rows.single { it.metricKey == "TAKE_OFF_PTS" }
        assertEquals(9_007_199_254_740_993L, takeOff.metricLongValue)
        assertEquals(null, takeOff.metricValue)
        assertEquals("MICROSECOND", takeOff.metricUnit)
        assertEquals("9007199254740993", with(CsvDataExportWriter) { takeOff.toCsv() }.split(',')[23])

        val landing = rows.single { it.metricKey == "LANDING_PTS" }
        assertEquals(10_000_000L, landing.metricLongValue)
        assertEquals("10000000", with(CsvDataExportWriter) { landing.toCsv() }.split(',')[23])

        val bodyMass = rows.single { it.metricKey == "BODY_MASS" }
        assertEquals(null, bodyMass.metricLongValue)
        assertEquals("80.0", with(CsvDataExportWriter) { bodyMass.toCsv() }.split(',')[23])
        assertEquals("9007199254740993", CsvDataExportWriter.cell(9_007_199_254_740_993L))
        assertEquals("10000000", CsvDataExportWriter.cell(10_000_000L))
    }

    @Test
    fun fvpNonFiniteDatabaseValuesBecomeNullAndAreNeverWritten() {
        val root = ExportFvpAssessmentRow(
            id = 5, idText = "5", sessionKey = "fvp-5", athleteId = null, testingSessionId = null,
            protocolId = "CMJ", dateTime = 2, bodyMassKg = 80.0, bodyMassSource = "PROFILE",
            pushOffMode = "COMMON", pushOffM = 0.4, pushOffSource = "MEASURED", pushOffLandmark = null,
            pushOffLandmarkHeightCm = Double.NaN, pushOffProtocol = null, pushOffMeasuredAt = null,
            pushOffCrouchCm = null, pushOffExtendedCm = Double.POSITIVE_INFINITY, sourceFps = Double.NaN,
            algorithmVersion = "v", methodVersion = "m", conditionCount = 1, f0N = 1.0, slopeFv = -1.0,
            sfvNsPerM = 1.0, v0Mps = Double.NaN, pmaxW = null, r2 = Double.NEGATIVE_INFINITY,
            vMinMps = 0.0, vMaxMps = 1.0, fMinN = 0.0, fMaxN = 1.0, f0RelNPerKg = null,
            pmaxRelWPerKg = null, warningsCsv = "", notes = null,
            acquiredAt = null, acquisitionContextKind = "UNKNOWN", acquisitionContextKey = null,
            bodyMassMeasurement = "UNKNOWN", bodyMassMeasuredAt = null,
        )
        val detail = ExportFvpDetailRow(
            parentDateTime = 2, assessmentId = 5, parentIdText = "5", conditionId = 9, conditionOrdinal = 0,
            conditionKey = "0kg", addedLoadKg = 0.0, totalMassKg = 80.0, pushOffM = 0.4,
            selectedHeightM = 0.42, selectedAttemptOrdinal = null, attemptId = null, attemptOrdinal = null,
            heightM = null, method = null, flightTimeS = null, sourceFps = null, sourceAssessmentId = null,
            sourceAttemptOrdinal = null, sourceProtocolId = null, attemptValid = null, exclusionReason = null,
            attemptNotes = null,
            addedLoadSource = "UNKNOWN", addedLoadMeasuredAt = null,
            sourceRecordedAt = null, sourceAthleteId = null, acquiredAt = null, reuseConfirmedAt = null,
            reuseConfirmationVersion = null, takeoffPtsUs = null, takeoffPreviousPtsUs = null,
            takeoffNextPtsUs = null, landingPtsUs = null, landingPreviousPtsUs = null,
            landingNextPtsUs = null, timeMapping = "UNKNOWN", timeMappingEvidenceKey = null,
        )
        val model = fvpAssessments(listOf(root), listOf(detail)).single()
        assertEquals(null, model.sourceFps)
        assertEquals(null, model.pushOffLandmarkHeightCm)
        assertEquals(null, model.pushOffExtendedCm)
        assertEquals(null, model.v0Mps)
        assertEquals(null, model.r2)
        val document = DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", fvp = listOf(model))
        val json = JsonDataExportWriter.write(document)
        val csv = CsvDataExportWriter.write(document)
        listOf(json, csv).forEach { text ->
            assertFalse(text.contains("NaN"))
            assertFalse(text.contains("Infinity"))
        }
        // 17 filas de evaluacion + 2 de procedencia de raiz + las 7 de la unica condicion
        // (sin ordinal de intento seleccionado, con `ADDED_LOAD_SOURCE` declarado UNKNOWN).
        assertEquals(listOf("CONDITION_KEY", "ORDINAL", "ADDED_LOAD", "TOTAL_MASS", "PUSH_OFF", "SELECTED_HEIGHT", "ADDED_LOAD_SOURCE"),
            CsvDataExportWriter.fvpObservations(model).drop(19).map { it.metricKey })
        assertEquals("UNKNOWN", CsvDataExportWriter.fvpObservations(model).single { it.metricKey == "ADDED_LOAD_SOURCE" }.metricReason)
    }

    /**
     * P2 (Wave 4a): la finitud no basta. Un par `slopeFv`/`sfvNsPerM` finito pero contradictorio
     * (documento editado a mano o construido por otra via) se RECHAZA al validar y al escribir:
     * no puede publicarse en JSON ni en CSV. Es la misma invariante dura que exige `FvpDao.save`.
     */
    @Test
    fun fvpFiniteMismatchedSlopeAndSfvAreRefusedByDocumentValidation() {
        val document = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            fvp = listOf(fvpAssessment().copy(slopeFv = -300.0, sfvNsPerM = 299.0)),
        )

        val validateFailure = runCatching { document.validated() }.exceptionOrNull()
        assertTrue("validated() debe rechazar el par finito contradictorio: $validateFailure", validateFailure is IllegalArgumentException)
        val jsonFailure = runCatching { JsonDataExportWriter.write(document) }.exceptionOrNull()
        assertTrue("el JSON debe rechazarlo: $jsonFailure", jsonFailure is IllegalArgumentException)
        val csvFailure = runCatching { CsvDataExportWriter.write(document) }.exceptionOrNull()
        assertTrue("el CSV debe rechazarlo: $csvFailure", csvFailure is IllegalArgumentException)

        // Control negativo: el MISMO documento con el par coherente si se valida y se escribe.
        val coherent = DataExportDocument(
            exportedAt = "2026-01-01T00:00:00Z",
            fvp = listOf(fvpAssessment()),
        ).validated()
        assertEquals(300.0, coherent.fvp.single().sfvNsPerM, 0.0)
        assertTrue(JsonDataExportWriter.write(coherent).contains("-300.0"))
    }

    /**
     * P2 (Wave 4a): el camino STREAMING ensambla las filas de Room directamente
     * ([fvpAssessments]) sin pasar por `validated()`. Una fila finita contradictoria (creada con
     * `insertAssessmentRaw`, que salta la validacion) hace fallar el ensamblado: la exportacion se
     * rechaza en vez de emitir un par imposible en JSON/CSV.
     */
    @Test
    fun fvpStreamingRefusesFiniteMismatchedSlopeAndSfvRows() {
        val root = ExportFvpAssessmentRow(
            id = 5, idText = "5", sessionKey = "fvp-5", athleteId = null, testingSessionId = null,
            protocolId = "CMJ", dateTime = 2, bodyMassKg = 80.0, bodyMassSource = "PROFILE",
            pushOffMode = "COMMON", pushOffM = 0.4, pushOffSource = "MEASURED", pushOffLandmark = null,
            pushOffLandmarkHeightCm = null, pushOffProtocol = null, pushOffMeasuredAt = null,
            pushOffCrouchCm = null, pushOffExtendedCm = null, sourceFps = 240.0,
            algorithmVersion = "v", methodVersion = "m", conditionCount = 1, f0N = 1.0,
            slopeFv = -300.0, sfvNsPerM = 299.0, v0Mps = null, pmaxW = null, r2 = null,
            vMinMps = 0.0, vMaxMps = 1.0, fMinN = 0.0, fMaxN = 1.0, f0RelNPerKg = null,
            pmaxRelWPerKg = null, warningsCsv = "", notes = null,
            acquiredAt = null, acquisitionContextKind = "UNKNOWN", acquisitionContextKey = null,
            bodyMassMeasurement = "UNKNOWN", bodyMassMeasuredAt = null,
        )

        val failure = runCatching { fvpAssessments(listOf(root), emptyList()) }.exceptionOrNull()
        assertTrue(
            "el ensamblado streaming debe rechazar la fila contradictoria: $failure",
            failure is IllegalArgumentException,
        )
        // Control negativo: la misma fila con `sfvNsPerM = -slopeFv` si se ensambla.
        assertEquals(300.0, fvpAssessments(listOf(root.copy(sfvNsPerM = 300.0)), emptyList()).single().sfvNsPerM, 0.0)
    }

    @Test
    fun fvpCollectionIsCanonicalizedDeterministically() {
        val later = fvpAssessment().copy(id = "2", dateTimeUtc = "2026-01-02T00:00:00Z")
        val earlier = fvpAssessment().copy(id = "10", dateTimeUtc = "2026-01-02T00:00:00Z")
        val document = DataExportDocument(exportedAt = "2026-01-01T00:00:00Z", fvp = listOf(later, earlier)).canonicalized()
        assertEquals(listOf("10", "2"), document.fvp.map { it.id })
        assertEquals(document, document.canonicalized())
        val unsorted = document.copy(fvp = listOf(fvpAssessment().copy(conditions = fvpAssessment().conditions.reversed())))
        assertEquals(unsorted.canonicalized().fvp, unsorted.canonicalized().canonicalized().fvp)
        assertEquals(listOf(0, 1), unsorted.canonicalized().fvp.single().conditions.map { it.ordinal })
    }

    private fun fvpAssessment() = ExportFvpAssessment(
        id = "5", sessionKey = "fvp-5", protocolId = "CMJ", dateTimeUtc = "2026-01-01T00:00:00Z",
        bodyMassKg = 80.0, bodyMassSource = "PROFILE", pushOffMode = "COMMON", pushOffM = 0.4,
        pushOffSource = "MEASURED", algorithmVersion = "fvp-poc-1.0", methodVersion = "fvp-app-1.0",
        conditionCount = 2, f0N = 1_200.0, slopeFv = -300.0, sfvNsPerM = 300.0, v0Mps = 1.4,
        vMinMps = 0.2, vMaxMps = 1.6, fMinN = 400.0, fMaxN = 1_800.0, warningsCsv = "LOW_R2",
        athleteId = "2", pushOffLandmark = "ASIS", pushOffLandmarkHeightCm = 92.5,
        pushOffProtocol = "LANDMARK", pushOffMeasuredAtUtc = "2026-01-01T00:00:00Z",
        pushOffCrouchCm = 30.0, pushOffExtendedCm = 122.5, sourceFps = 240.0, f0RelNPerKg = 15.0,
        pmaxW = 900.0, pmaxRelWPerKg = 11.25, r2 = 0.98, notes = "fvp note",
        // Procedencia B0 conocida (las cinco columnas de la raiz).
        acquiredAtUtc = "2026-01-01T00:00:05Z",
        acquisitionContextKind = "SAME_ACQUISITION",
        acquisitionContextKey = "ctx-1",
        bodyMassMeasurement = "WEIGHED",
        bodyMassMeasuredAtUtc = "2026-01-01T00:00:06Z",
        conditions = listOf(
            ExportFvpCondition(
                0, "0kg", 0.0, 80.0, 0.4, 0.42, 0,
                addedLoadSource = "NOMINAL",
                addedLoadMeasuredAtUtc = "2026-01-01T00:00:07Z",
                attempts = listOf(
                    ExportFvpAttempt(
                        0, 0.42, "FLIGHT_TIME_VIDEO", true, flightTimeS = 0.6, sourceFps = 240.0,
                        sourceAssessmentId = "7", sourceAttemptOrdinal = 0, sourceProtocolId = "CMJ",
                        sourceRecordedAtUtc = "2026-01-01T00:00:08Z", sourceAthleteId = "77",
                        acquiredAtUtc = "2026-01-01T00:00:09Z", reuseConfirmedAtUtc = "2026-01-01T00:00:10Z",
                        reuseConfirmationVersion = "fvp-reuse-1",
                        takeoffPtsUs = 9_007_199_254_740_993L,
                        takeoffPreviousPtsUs = 9_007_199_254_740_992L,
                        takeoffNextPtsUs = 9_007_199_254_740_994L,
                        landingPtsUs = 10_000_000L,
                        landingPreviousPtsUs = 9_999_999L,
                        landingNextPtsUs = 10_000_001L,
                        timeMapping = "IDENTITY_VERIFIED",
                        timeMappingEvidenceKey = "ev-1",
                    ),
                    ExportFvpAttempt(1, 0.35, "MANUAL", false, exclusionReason = "SHORT_FLIGHT"),
                ),
            ),
            // Condicion 1: solo el enum declarado (`UNKNOWN`); la fecha nullable queda null.
            ExportFvpCondition(1, "20kg", 20.0, 100.0, 0.4, 0.31, 0, attempts = listOf(ExportFvpAttempt(0, 0.31, "IMPORTED", true, sourceFps = 120.0))),
        ),
    )

    private suspend fun streamDocument(document: DataExportDocument, json: Boolean): String {
        val canonical = document.validated().canonicalized()
        val output = StringWriter()
        val target: DataExportStreamWriter = if (json) StreamingJsonDataExportWriter(output) else StreamingCsvDataExportWriter(output)
        target.begin(canonical.exportedAt)
        canonical.athletes.forEach { target.athlete(it) }
        target.endAthletes()
        canonical.measurements.forEach { measurement ->
            val millis = Instant.parse(measurement.dateTimeUtc).toEpochMilli()
            val primaryContext = measurement.primaryMetricContext
            val primaryAttemptId = measurement.attempts.indexOfFirst { it.context == primaryContext }.takeIf { it >= 0 }?.toLong()
            val row = ExportMeasurementRow(
                id = measurement.id.toLong(), dateTime = millis, idText = measurement.id,
                athleteId = measurement.athleteId?.toLong(), testingSessionId = measurement.testingSessionId?.toLong(),
                athleteName = canonical.athletes.firstOrNull { it.id == measurement.athleteId }?.name,
                athleteBirthDate = canonical.athletes.firstOrNull { it.id == measurement.athleteId }?.birthDate?.let { java.time.LocalDate.parse(it).toEpochDay() },
                athleteSex = canonical.athletes.firstOrNull { it.id == measurement.athleteId }?.sex,
                groupNameSnapshot = measurement.groupNameSnapshot, kind = measurement.kind,
                protocolId = measurement.protocolId, exercise = measurement.exercise, loadKg = measurement.loadKg,
                analysisVersion = measurement.analysisVersion, primaryMetricKey = measurement.primaryMetric?.key,
                primaryMetricValue = measurement.primaryMetric?.value, primaryMetricUnit = measurement.primaryMetric?.unit,
                primaryAttemptId = primaryAttemptId, primarySide = primaryContext?.side, primaryOrdinal = primaryContext?.ordinal,
                primaryDropHeightCm = primaryContext?.dropHeightCm, primaryDistanceCm = primaryContext?.distanceCm,
                totalRepetitions = measurement.sessionSummaries.firstOrNull { it.key == "TOTAL_REPETITIONS" }?.value?.toInt(),
                validRepetitions = measurement.sessionSummaries.firstOrNull { it.key == "VALID_REPETITIONS" }?.value?.toInt(),
                bestMcv = measurement.sessionSummaries.firstOrNull { it.key == "BEST_MCV" }?.value,
                setVelocityLoss = measurement.sessionSummaries.firstOrNull { it.key == "SET_VELOCITY_LOSS" }?.value,
            )
            if (measurement.kind == "jump") {
                target.beginJump(row)
                measurement.attempts.forEachIndexed { index, attempt ->
                    val context = ExportJumpDetailRow(millis, row.id, row.idText, index.toLong(), attempt.context.ordinal, attempt.context.side, attempt.context.dropHeightCm, attempt.context.distanceCm, 0, null, null, null, null)
                    target.beginJumpAttempt(context)
                    attempt.metrics.forEach { metric ->
                        target.jumpMetric(context.copy(metricPresent = 1, metricOrdinal = metric.ordinal, metricKey = metric.key, metricValue = metric.value, metricUnit = metric.unit))
                    }
                    target.endJumpAttempt()
                }
                target.endJump()
            } else {
                target.beginEncoder(row)
                measurement.repetitions.forEach { repetition ->
                    target.beginEncoderRepetition(ExportEncoderDetailRow(millis, row.id, row.idText, repetition.ordinal.toLong(), repetition.ordinal, repetition.quality, repetition.qualityReasons.joinToString(","), 1, null, null, null, null, null))
                    repetition.metrics.forEach { metric -> target.encoderMetric(ExportEncoderDetailRow(millis, row.id, row.idText, repetition.ordinal.toLong(), repetition.ordinal, repetition.quality, repetition.qualityReasons.joinToString(","), 1, metric.key, metric.value, metric.unit, metric.validity, metric.reason)) }
                    target.endEncoderRepetition()
                }
                target.endEncoder()
            }
        }
        target.finish()
        return output.toString()
    }
}
