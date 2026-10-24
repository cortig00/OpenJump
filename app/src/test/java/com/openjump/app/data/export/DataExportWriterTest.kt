package com.openjump.app.data.export

import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3 (export schema 5): versionado del documento, procedencia B0 viajando 1:1 en JSON y ruta
 * entera EXACTA de los PTS (nunca por `Double`). El CSV de procedencia y la igualdad
 * snapshot/streaming de la familia F-V-P se cubren en [DataExportWritersTest].
 *
 * El esquema 5 no promete byte-igualdad con documentos 3: cambia `schemaVersion`. Lo que aqui se
 * fija es el valor/forma de cada campo nuevo, no una compatibilidad de lectores antiguos.
 */
class DataExportWriterTest {

    @Test
    fun emitsSchemaVersionFiveAndCanonicalizesLegacyDocumentsToFive() {
        assertEquals(5, DATA_EXPORT_SCHEMA_VERSION)
        val json = JsonDataExportWriter.write(document(fvp = listOf(knownProvenanceAssessment())))
        assertTrue(json.contains("\"schemaVersion\": 5"))
        assertFalse("el escritor no puede seguir emitiendo 3", json.contains("\"schemaVersion\": 3"))
        assertEquals(5, document().copy(schemaVersion = 1).canonicalized().schemaVersion)
        assertEquals(5, document().copy(schemaVersion = 2).canonicalized().schemaVersion)
        assertEquals(5, document().copy(schemaVersion = 3).canonicalized().schemaVersion)
        assertEquals(
            "openjump-graph-export",
            Json.parseToJsonElement(json).jsonObject["contract"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun acceptsLegacyOneTwoThreeAndFiveAndRejectsFourAndUnknownVersions() {
        listOf(1, 2, 3, 5).forEach { version ->
            assertEquals(
                version,
                document(fvp = listOf(knownProvenanceAssessment())).copy(schemaVersion = version).validated().schemaVersion,
            )
        }

        // 4 es la colision historica con Motion: rechazo propio y explicito, no "version rara".
        val motion = runCatching { document().copy(schemaVersion = 4).validated() }.exceptionOrNull()
        assertTrue("4 debe rechazarse: $motion", motion is IllegalArgumentException)
        val message = motion?.message.orEmpty()
        assertTrue("el mensaje debe nombrar Motion: $message", message.contains("Motion"))
        assertTrue("el mensaje debe decir que 4 es historico: $message", message.contains("historically"))
        assertTrue(runCatching { JsonDataExportWriter.write(document().copy(schemaVersion = 4)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CsvDataExportWriter.write(document().copy(schemaVersion = 4)) }.exceptionOrNull() is IllegalArgumentException)

        val unsupported = runCatching { document().copy(schemaVersion = 6).validated() }.exceptionOrNull()
        assertTrue(unsupported is IllegalArgumentException)
        assertTrue(
            "un 6 no puede confundirse con el rechazo de Motion: ${unsupported?.message}",
            unsupported!!.message.orEmpty().contains("unsupported export schemaVersion 6"),
        )
        listOf(-1, 0, 99).forEach { version ->
            assertTrue(
                "la version $version no puede aceptarse",
                runCatching { document().copy(schemaVersion = version).validated() }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun jsonCarriesTheTwentyProvenanceColumnsOneToOne() {
        val json = JsonDataExportWriter.write(document(fvp = listOf(knownProvenanceAssessment())))
        val entry = Json.parseToJsonElement(json).jsonObject["fvp"]!!.jsonArray.single().jsonObject

        // Raiz (5 columnas Room).
        assertEquals("2026-03-04T05:06:07Z", entry["acquiredAtUtc"]!!.jsonPrimitive.content)
        assertEquals("SAME_ACQUISITION", entry["acquisitionContextKind"]!!.jsonPrimitive.content)
        assertEquals("ctx-1", entry["acquisitionContextKey"]!!.jsonPrimitive.content)
        assertEquals("WEIGHED", entry["bodyMassMeasurement"]!!.jsonPrimitive.content)
        assertEquals("2026-03-04T05:06:08Z", entry["bodyMassMeasuredAtUtc"]!!.jsonPrimitive.content)

        val condition = entry["conditions"]!!.jsonArray.single().jsonObject
        // Condicion (2 columnas Room).
        assertEquals("NOMINAL", condition["addedLoadSource"]!!.jsonPrimitive.content)
        assertEquals("2026-03-04T05:06:09Z", condition["addedLoadMeasuredAtUtc"]!!.jsonPrimitive.content)

        val attempt = condition["attempts"]!!.jsonArray.single().jsonObject
        // Intento (13 columnas Room): fechas ISO-8601 UTC, ids como String, enums como texto.
        assertEquals("2026-03-04T05:06:10Z", attempt["sourceRecordedAtUtc"]!!.jsonPrimitive.content)
        assertEquals("77", attempt["sourceAthleteId"]!!.jsonPrimitive.content)
        assertTrue("sourceAthleteId es identificador textual, no numero", attempt["sourceAthleteId"]!!.jsonPrimitive.isString)
        assertEquals("2026-03-04T05:06:11Z", attempt["acquiredAtUtc"]!!.jsonPrimitive.content)
        assertEquals("2026-03-04T05:06:12Z", attempt["reuseConfirmedAtUtc"]!!.jsonPrimitive.content)
        assertEquals("fvp-reuse-1", attempt["reuseConfirmationVersion"]!!.jsonPrimitive.content)
        assertEquals("IDENTITY_VERIFIED", attempt["timeMapping"]!!.jsonPrimitive.content)
        assertEquals("ev-1", attempt["timeMappingEvidenceKey"]!!.jsonPrimitive.content)
        listOf(
            "acquiredAtUtc", "acquisitionContextKind", "acquisitionContextKey", "bodyMassMeasurement",
            "bodyMassMeasuredAtUtc", "addedLoadSource", "addedLoadMeasuredAtUtc", "sourceRecordedAtUtc",
            "sourceAthleteId", "reuseConfirmedAtUtc", "reuseConfirmationVersion", "takeoffPtsUs",
            "takeoffPreviousPtsUs", "takeoffNextPtsUs", "landingPtsUs", "landingPreviousPtsUs",
            "landingNextPtsUs", "timeMapping", "timeMappingEvidenceKey",
        ).forEach { name ->
            val present = entry.containsKey(name) || condition.containsKey(name) || attempt.containsKey(name)
            assertTrue("falta la columna de procedencia $name en el JSON", present)
        }
        assertEquals("intento con su propio acquiredAtUtc", "2026-03-04T05:06:11Z", attempt["acquiredAtUtc"]!!.jsonPrimitive.content)
        assertEquals("raiz con su propio acquiredAtUtc", "2026-03-04T05:06:07Z", entry["acquiredAtUtc"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonKeepsUnknownEnumsAndNullTimestampsAsPersisted() {
        val json = JsonDataExportWriter.write(document(fvp = listOf(knownProvenanceAssessment(), unknownProvenanceAssessment())))
        val entries = Json.parseToJsonElement(json).jsonObject["fvp"]!!.jsonArray.map { it.jsonObject }
        val known = entries.single { it["id"]!!.jsonPrimitive.content == "5" }
        val unknown = entries.single { it["id"]!!.jsonPrimitive.content == "6" }

        // `UNKNOWN` declarado se emite tal cual: no es ausencia y el escritor no lo rellena.
        assertEquals("UNKNOWN", unknown["acquisitionContextKind"]!!.jsonPrimitive.content)
        assertEquals("UNKNOWN", unknown["bodyMassMeasurement"]!!.jsonPrimitive.content)
        assertEquals("SAME_ACQUISITION", known["acquisitionContextKind"]!!.jsonPrimitive.content)
        assertEquals("WEIGHED", known["bodyMassMeasurement"]!!.jsonPrimitive.content)
        // Nullable null: viaja como null explicito (`explicitNulls = true`), nunca inventado.
        assertTrue(unknown["acquiredAtUtc"] is JsonNull)
        assertTrue(unknown["acquisitionContextKey"] is JsonNull)
        assertTrue(unknown["bodyMassMeasuredAtUtc"] is JsonNull)
        assertEquals("null", unknown["acquiredAtUtc"].toString())
        val unknownCondition = unknown["conditions"]!!.jsonArray.single().jsonObject
        assertEquals("UNKNOWN", unknownCondition["addedLoadSource"]!!.jsonPrimitive.content)
        assertTrue(unknownCondition["addedLoadMeasuredAtUtc"] is JsonNull)
        val unknownAttempt = unknownCondition["attempts"]!!.jsonArray.single().jsonObject
        assertEquals("UNKNOWN", unknownAttempt["timeMapping"]!!.jsonPrimitive.content)
        listOf(
            "sourceRecordedAtUtc", "sourceAthleteId", "acquiredAtUtc", "reuseConfirmedAtUtc",
            "reuseConfirmationVersion", "takeoffPtsUs", "takeoffPreviousPtsUs", "takeoffNextPtsUs",
            "landingPtsUs", "landingPreviousPtsUs", "landingNextPtsUs", "timeMappingEvidenceKey",
        ).forEach { name ->
            assertTrue("$name deberia ser null explicito, no ausente", unknownAttempt.containsKey(name))
            assertTrue("$name deberia ser null", unknownAttempt[name] is JsonNull)
        }
    }

    @Test
    fun jsonPtsLongsAreExactDecimalTokensWithoutDoubleOrExponent() {
        val json = JsonDataExportWriter.write(document(fvp = listOf(knownProvenanceAssessment())))
        val attempt = Json.parseToJsonElement(json).jsonObject["fvp"]!!.jsonArray.single()
            .jsonObject["conditions"]!!.jsonArray.single().jsonObject["attempts"]!!.jsonArray.single().jsonObject

        val expected = listOf(
            "takeoffPtsUs" to "9007199254740993",
            "takeoffPreviousPtsUs" to "9007199254740992",
            "takeoffNextPtsUs" to "9007199254740994",
            "landingPtsUs" to "10000000",
            "landingPreviousPtsUs" to "9999999",
            "landingNextPtsUs" to "10000001",
        )
        expected.forEach { (name, token) ->
            val primitive = attempt.getValue(name).jsonPrimitive
            assertFalse("$name no puede ser un string JSON", primitive.isString)
            assertEquals("$name debe conservar el token decimal exacto", token, primitive.content)
            assertFalse("$name no puede llevar punto decimal", primitive.content.contains('.'))
            assertFalse("$name no puede usar exponente", primitive.content.contains('E') || primitive.content.contains('e'))
        }
        // Discriminante del probe21: la ruta Double devolveria 9007199254740992 / 1.0E7.
        assertTrue(json.contains("\"takeoffPtsUs\": 9007199254740993"))
        assertTrue(json.contains("\"landingPtsUs\": 10000000"))
        assertFalse(json.contains("9.007199254740992E15"))
        assertFalse(json.contains("9007199254740993.0"))
        assertFalse(json.contains("1.0E7"))

        // Frontera 2^53-1 del probe21: tambien debe viajar como token entero exacto.
        val boundary = knownProvenanceAssessment()
        val boundaryCondition = boundary.conditions.single()
        val boundaryAttempt = boundaryCondition.attempts.single()
            .copy(takeoffPreviousPtsUs = 9_007_199_254_740_991L)
        val boundaryJson = JsonDataExportWriter.write(
            document(
                fvp = listOf(
                    boundary.copy(
                        conditions = listOf(boundaryCondition.copy(attempts = listOf(boundaryAttempt))),
                    ),
                ),
            ),
        )
        assertTrue(
            "2^53-1 debe conservarse exacto",
            boundaryJson.contains("\"takeoffPreviousPtsUs\": 9007199254740991"),
        )
        assertFalse(boundaryJson.contains("9007199254740991.0"))
    }

    @Test
    fun csvHeaderStillHasTheCanonicalTwentyNineColumns() {
        assertEquals(
            listOf(
                "schema_version", "observation_type", "measurement_family", "measurement_id",
                "date_time_utc", "athlete_id", "athlete_name", "athlete_birth_date", "athlete_sex",
                "testing_session_id", "testing_group_name", "protocol_id", "exercise", "attempt_ordinal",
                "repetition_ordinal", "side", "load_kg", "drop_height_cm", "distance_cm", "analysis_version",
                "repetition_quality", "quality_reasons", "metric_key", "metric_value", "metric_unit",
                "metric_validity", "metric_reason", "is_primary", "notes",
            ),
            CsvDataExportWriter.header,
        )
    }

    @Test
    fun streamingJsonMatchesSnapshotForDocumentWithProvenance() = runBlocking {
        val document = document(fvp = listOf(knownProvenanceAssessment(), unknownProvenanceAssessment()))
        val expected = JsonDataExportWriter.write(document)
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply {
            begin(document.exportedAt)
            endAthletes()
            document.canonicalized().fvp.forEach { fvp(it) }
            finish()
        }
        assertEquals(expected, output.toString())
        assertTrue(expected.contains("\"schemaVersion\": 5"))
        assertTrue(expected.contains("\"takeoffPtsUs\": 9007199254740993"))
    }

    private fun document(
        fvp: List<ExportFvpAssessment> = emptyList(),
    ) = DataExportDocument(exportedAt = EXPORTED_AT, fvp = fvp)

    private fun knownProvenanceAssessment() = assessment("5", "2026-03-04T05:06:00Z").copy(
        acquiredAtUtc = "2026-03-04T05:06:07Z",
        acquisitionContextKind = "SAME_ACQUISITION",
        acquisitionContextKey = "ctx-1",
        bodyMassMeasurement = "WEIGHED",
        bodyMassMeasuredAtUtc = "2026-03-04T05:06:08Z",
        conditions = listOf(
            ExportFvpCondition(
                ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0, totalMassKg = 80.0,
                pushOffM = 0.4, selectedHeightM = 0.42, selectedAttemptOrdinal = 0,
                addedLoadSource = "NOMINAL",
                addedLoadMeasuredAtUtc = "2026-03-04T05:06:09Z",
                attempts = listOf(
                    ExportFvpAttempt(
                        ordinal = 0, heightM = 0.42, method = "FLIGHT_TIME_VIDEO", valid = true,
                        sourceRecordedAtUtc = "2026-03-04T05:06:10Z",
                        sourceAthleteId = "77",
                        acquiredAtUtc = "2026-03-04T05:06:11Z",
                        reuseConfirmedAtUtc = "2026-03-04T05:06:12Z",
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
                ),
            ),
        ),
    )

    /** Todo desconocido: enums en `UNKNOWN` y todos los nullable en null (sin derivar nada). */
    private fun unknownProvenanceAssessment() = assessment("6", "2026-03-04T05:07:00Z")

    private fun assessment(id: String, dateTimeUtc: String) = ExportFvpAssessment(
        id = id, sessionKey = "fvp-$id", protocolId = "CMJ", dateTimeUtc = dateTimeUtc,
        bodyMassKg = 80.0, bodyMassSource = "PROFILE", pushOffMode = "COMMON", pushOffM = 0.4,
        pushOffSource = "MEASURED", algorithmVersion = "fvp-poc-1.0", methodVersion = "fvp-app-1.0",
        conditionCount = 1, f0N = 1_200.0, slopeFv = -300.0, sfvNsPerM = 300.0, vMinMps = 0.2,
        vMaxMps = 1.6, fMinN = 400.0, fMaxN = 1_800.0, warningsCsv = "LOW_R2",
        conditions = listOf(
            ExportFvpCondition(
                ordinal = 0, conditionKey = "0kg", addedLoadKg = 0.0, totalMassKg = 80.0,
                pushOffM = 0.4, selectedHeightM = 0.42, selectedAttemptOrdinal = 0,
                attempts = listOf(ExportFvpAttempt(ordinal = 0, heightM = 0.42, method = "MANUAL", valid = true)),
            ),
        ),
    )

    private companion object {
        const val EXPORTED_AT = "2026-01-01T00:00:00Z"
    }
}
