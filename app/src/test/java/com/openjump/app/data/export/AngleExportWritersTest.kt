package com.openjump.app.data.export

import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 2 de la exportación de Ángulos: los escritores publican la familia nueva reutilizando la
 * infraestructura streaming existente (un solo sistema: los dos caminos serializan el mismo modelo
 * canónico), con PTS `Long` exacto, grados recalculados, marcas ocultas incluidas y sin identidad
 * privada de la fuente.
 */
class AngleExportWritersTest {

    @Test
    fun streamingJsonAnglesMatchTheCanonicalWriter() = runBlocking {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            athletes = listOf(ExportAthlete("42", "Atleta Ω", "1980-01-02", "F")),
            angleAnalyses = listOf(
                analysis(id = "7", marks = listOf(rightAngleMark(id = "11", ordinal = 0), hiddenMark(id = "12", ordinal = 1))),
                analysis(id = "9", createdAtUtc = "2026-03-02T00:00:00Z", marks = emptyList()),
            ),
        )

        val expected = JsonDataExportWriter.write(document)
        assertEquals(expected, streamJson(document))
        assertTrue(expected.contains("\"angleAnalyses\": ["))
        assertTrue("el documento conserva las dos raíces: $expected", expected.contains("\"id\": \"9\""))
        assertTrue("una raíz sin marcas publica la lista vacía: $expected", expected.contains("\"marks\": []"))
    }

    @Test
    fun streamingJsonClosesOpenMeasurementsBeforeTheAngleSection() = runBlocking {
        // Un timestamp epoch de 2 ms viaja como "1970-01-01T00:00:00.002Z" en ambos caminos.
        val row = ExportMeasurementRow(
            2, 2, "2", null, null, null, null, null, null,
            protocolId = "CMJ", primaryMetricKey = "HEIGHT", primaryMetricValue = 2.0, primaryMetricUnit = "CENTIMETER",
        )
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            measurements = listOf(
                ExportMeasurement(
                    "jump", "2", "1970-01-01T00:00:00.002Z", protocolId = "CMJ",
                    primaryMetric = ExportMetric("HEIGHT", 2.0, "CENTIMETER", ordinal = 0, isPrimary = true),
                ),
            ),
            angleAnalyses = listOf(analysis()),
        )
        val output = StringWriter()
        StreamingJsonDataExportWriter(output).apply {
            begin(EXPORTED_AT)
            endAthletes()
            beginJump(row)
            endJump()
            angle(analysis())
            finish()
        }

        assertEquals(JsonDataExportWriter.write(document), output.toString())
        assertTrue(output.toString().indexOf("\"measurements\": [") < output.toString().indexOf("\"angleAnalyses\": ["))
    }

    @Test
    fun angleSectionIsEmittedAfterMeasurementsAndFvp() = runBlocking {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            fvp = listOf(fvpAssessment()),
            angleAnalyses = listOf(analysis()),
        )
        val output = streamJson(document)

        assertEquals(JsonDataExportWriter.write(document), output)
        val measurements = output.indexOf("\"measurements\": []")
        val fvp = output.indexOf("\"fvp\": [")
        val angles = output.indexOf("\"angleAnalyses\": [")
        assertTrue("orden measurements < fvp < angleAnalyses: $measurements/$fvp/$angles", measurements < fvp)
        assertTrue("orden measurements < fvp < angleAnalyses", fvp < angles)
    }

    @Test
    fun jsonKeepsTheExactFramePtsAndTheComputedDegrees() = runBlocking {
        val json = JsonDataExportWriter.write(
            DataExportDocument(exportedAt = EXPORTED_AT, angleAnalyses = listOf(analysis())),
        )

        // 2^53 + 1 no es representable en Double: el export debe conservarlo entero.
        assertTrue("PTS exacto: $json", json.contains("\"sourcePtsUs\": 9007199254740993"))
        assertTrue("grados derivados sin redondear: $json", json.contains("\"degrees\": 90.0"))
        assertEquals(json, streamJson(DataExportDocument(exportedAt = EXPORTED_AT, angleAnalyses = listOf(analysis()))))
    }

    @Test
    fun csvAnglesAreOneObservationPerAttributeWithTheStableHeader() {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(analysis(marks = listOf(rightAngleMark(), hiddenMark()))),
        )
        val csv = CsvDataExportWriter.write(document)

        assertEquals(29, CsvDataExportWriter.header.size)
        assertEquals(CsvDataExportWriter.header.joinToString(","), csv.lineSequence().first())
        val rows = CsvDataExportWriter.observations(document)
        assertTrue("todas las filas nuevas declaran la familia: $rows", rows.all { it.measurementFamily == ANGLE_MEASUREMENT_FAMILY })
        assertTrue(rows.all { it.measurementId == "7" })
        assertTrue(rows.all { it.isPrimary.not() })

        val byKey = rows.associateBy { "${it.observationType}|${it.metricKey}|${it.repetitionOrdinal ?: -1}" }
        val frame = byKey.getValue("ANGLE_ANALYSIS|FRAME_INDEX|-1")
        assertEquals(1487.0, frame.metricValue!!, 0.0)
        assertEquals(ANGLE_COUNT_UNIT, frame.metricUnit)
        // PTS exacto por `metricLongValue`: el CSV escribe el decimal canónico del Long.
        val pts = byKey.getValue("ANGLE_ANALYSIS|SOURCE_PTS|-1")
        assertEquals(9_007_199_254_740_993L, pts.metricLongValue)
        assertEquals(ANGLE_MICROSECOND_UNIT, pts.metricUnit)
        assertTrue("PTS exacto en la línea CSV: ${CsvDataExportWriter.csvLine(pts)}", CsvDataExportWriter.csvLine(pts).split(',')[23] == "9007199254740993")
        assertEquals("2026-03-01T00:00:00Z", byKey.getValue("ANGLE_ANALYSIS|UPDATED_AT|-1").metricReason)
        assertEquals(ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT, byKey.getValue("ANGLE_ANALYSIS|VIDEO_REFERENCE_KIND|-1").metricReason)
        assertEquals(1920.0, byKey.getValue("ANGLE_ANALYSIS|ENCODED_WIDTH|-1").metricValue!!, 0.0)
        assertEquals(90.0, byKey.getValue("ANGLE_ANALYSIS|ROTATION_DEGREES|-1").metricValue!!, 0.0)

        val markId = byKey.getValue("ANGLE_MARK|MARK_ID|0")
        assertEquals("11", markId.metricReason)
        assertEquals("Rodilla", byKey.getValue("ANGLE_MARK|MARK_NAME|0").metricReason)
        assertEquals("VISIBLE", byKey.getValue("ANGLE_MARK|VISIBLE|0").metricValidity)
        assertEquals(ANGLE_BOOLEAN_UNIT, byKey.getValue("ANGLE_MARK|VISIBLE|0").metricUnit)
        assertEquals(90.0, byKey.getValue("ANGLE_MARK|DEGREES|0").metricValue!!, 0.0)
        assertEquals(10.0, byKey.getValue("ANGLE_MARK|POINT_A_X|0").metricValue!!, 0.0)
        assertEquals(10.0, byKey.getValue("ANGLE_MARK|POINT_C_Y|0").metricValue!!, 0.0)
        assertNull(byKey["ANGLE_MARK|INVALID_REASON|0"])

        // Ocultos incluidos: la visibilidad es un atributo, no un filtro.
        assertEquals("HIDDEN", byKey.getValue("ANGLE_MARK|VISIBLE|1").metricValidity)
        assertEquals("12", byKey.getValue("ANGLE_MARK|MARK_ID|1").metricReason)
        // Color ARGB exacto (int con signo) sin pasar por Double.
        val color = byKey.getValue("ANGLE_MARK|COLOR_ARGB|0")
        assertEquals(rightAngleMark().colorArgb.toLong(), color.metricLongValue)
        assertEquals(ANGLE_ARGB_UNIT, color.metricUnit)
        assertTrue("ARGB exacto en la línea CSV: ${CsvDataExportWriter.csvLine(color)}", CsvDataExportWriter.csvLine(color).split(',')[23] == rightAngleMark().colorArgb.toString())
        assertEquals(2, rows.count { it.observationType == "ANGLE_MARK" && it.metricKey == "MARK_ID" })
    }

    @Test
    fun streamingCsvAnglesMatchTheCanonicalWriter() = runBlocking {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(
                analysis(id = "9", createdAtUtc = "2026-03-02T00:00:00Z", marks = listOf(hiddenMark(id = "20", ordinal = 0))),
                analysis(id = "7", createdAtUtc = "2026-03-01T00:00:00Z", marks = listOf(rightAngleMark(id = "11", ordinal = 0))),
            ),
        )

        val expected = CsvDataExportWriter.write(document)
        assertEquals(expected, streamCsv(document))
        // El CSV canónico ordena las raíces por instante e id, igual que el keyset del repositorio.
        val firstDataRow = expected.lineSequence().drop(1).first()
        assertTrue(firstDataRow.startsWith("$DATA_EXPORT_SCHEMA_VERSION,ANGLE_ANALYSIS,angle,7,"))
    }

    @Test
    fun csvEscapesFormulaPrefixesAndQuotesInMarkNames() {
        val name = "=2+2, \"cuádriceps\"\nlinea2"
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(analysis(marks = listOf(rightAngleMark(name = name)))),
        )

        val rows = CsvDataExportWriter.observations(document)
        val markName = rows.single { it.metricKey == "MARK_NAME" }
        assertEquals(name, markName.metricReason)
        // La celda se endurece contra inyección de fórmulas y se cita por coma/comilla/salto.
        val line = CsvDataExportWriter.csvLine(markName)
        assertTrue("fórmula neutralizada: $line", line.contains("\"'=2+2, \"\"cuádriceps\"\"\nlinea2\""))

        val csv = CsvDataExportWriter.write(document)
        assertTrue(csv.contains("\"'=2+2, \"\"cuádriceps\"\"\nlinea2\""))
        assertEquals(csv, runBlocking { streamCsv(document) })
    }

    @Test
    fun unmeasurableMarkPublishesInvalidReasonInsteadOfFakeZeroDegrees() {
        val coincident = ExportAngleMark(
            id = "13", ordinal = 0, name = "Vertex coincidente", visible = true,
            colorArgb = 0xFF993333.toInt(),
            pointAX = 1.0, pointAY = 1.0, pointBX = 1.0, pointBY = 1.0, pointCX = 2.0, pointCY = 2.0,
        )
        val rows = CsvDataExportWriter.observations(
            DataExportDocument(exportedAt = EXPORTED_AT, angleAnalyses = listOf(analysis(marks = listOf(coincident)))),
        )

        assertNull("un ángulo indefinido no publica 0°", rows.singleOrNull { it.metricKey == "DEGREES" })
        assertEquals("COINCIDENT_VERTEX", rows.single { it.metricKey == "INVALID_REASON" }.metricReason)
    }

    @Test
    fun angleExportCarriesNoPrivateVideoIdentityOnEitherPath() = runBlocking {
        val document = DataExportDocument(exportedAt = EXPORTED_AT, angleAnalyses = listOf(analysis()))

        listOf(JsonDataExportWriter.write(document), streamJson(document)).forEach { json ->
            assertTrue(json.contains("\"kind\": \"ANALYSIS_ROOT\""))
            assertFalse("URI del vídeo: $json", json.contains("videoUri"))
            assertFalse("identidad de la fuente: $json", json.contains("sourceIdentity"))
            assertFalse("hash del contenido: $json", json.contains("sourceHash"))
            assertFalse("sessionKey derivado de la URI: $json", json.contains("sessionKey"))
            assertFalse(json.contains("content://"))
        }
        listOf(CsvDataExportWriter.write(document), streamCsv(document)).forEach { csv ->
            assertTrue(csv.contains("VIDEO_REFERENCE_KIND"))
            assertFalse(csv.contains("sourceIdentity"))
            assertFalse(csv.contains("sourceHash"))
            assertFalse(csv.contains("sessionKey"))
            assertFalse(csv.contains("content://"))
        }
    }

    @Test
    fun anglesAndMarksAreDeterministicRegardlessOfInputOrder() = runBlocking {
        val first = analysis(id = "7", createdAtUtc = "2026-03-01T00:00:00Z", marks = listOf(rightAngleMark(id = "11", ordinal = 0), hiddenMark(id = "12", ordinal = 1)))
        val second = analysis(id = "9", createdAtUtc = "2026-03-02T00:00:00Z", marks = listOf(hiddenMark(id = "20", ordinal = 0)))
        val ordered = DataExportDocument(exportedAt = EXPORTED_AT, angleAnalyses = listOf(first, second))
        val shuffled = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(second.copy(marks = second.marks.reversed()), first.copy(marks = first.marks.reversed())),
        )

        assertEquals(JsonDataExportWriter.write(ordered), JsonDataExportWriter.write(shuffled))
        assertEquals(CsvDataExportWriter.write(ordered), CsvDataExportWriter.write(shuffled))
        assertEquals(streamJson(ordered), streamJson(shuffled))
        assertEquals(streamCsv(ordered), streamCsv(shuffled))
    }

    @Test
    fun angleRootWithoutMarksStillExportsFrameAndPts() = runBlocking {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(analysis(id = "9", marks = emptyList())),
        )

        val json = JsonDataExportWriter.write(document)
        assertTrue(json.contains("\"marks\": []"))
        assertTrue(json.contains("\"sourcePtsUs\": 9007199254740993"))
        val rows = CsvDataExportWriter.observations(document)
        assertTrue(rows.all { it.observationType == "ANGLE_ANALYSIS" })
        assertEquals("9007199254740993", rows.single { it.metricKey == "SOURCE_PTS" }.metricLongValue.toString())
        assertEquals(CsvDataExportWriter.write(document), streamCsv(document))
    }

    private suspend fun streamJson(document: DataExportDocument): String {
        val canonical = document.validated().canonicalized()
        val output = StringWriter()
        val writer = StreamingJsonDataExportWriter(output)
        writer.begin(canonical.exportedAt)
        canonical.athletes.forEach { writer.athlete(it) }
        writer.endAthletes()
        canonical.fvp.forEach { writer.fvp(it) }
        canonical.angleAnalyses.forEach { writer.angle(it) }
        writer.finish()
        return output.toString()
    }

    private suspend fun streamCsv(document: DataExportDocument): String {
        val canonical = document.validated().canonicalized()
        val output = StringWriter()
        val writer = StreamingCsvDataExportWriter(output)
        writer.begin(canonical.exportedAt)
        canonical.athletes.forEach { writer.athlete(it) }
        writer.endAthletes()
        canonical.fvp.forEach { writer.fvp(it) }
        canonical.angleAnalyses.forEach { writer.angle(it) }
        writer.finish()
        return output.toString()
    }

    private fun analysis(
        id: String = "7",
        createdAtUtc: String = "2026-03-01T00:00:00Z",
        marks: List<ExportAngleMark> = listOf(rightAngleMark()),
    ) = ExportAngleAnalysis(
        id = id,
        createdAtUtc = createdAtUtc,
        updatedAtUtc = createdAtUtc,
        revision = 3,
        athleteId = "42",
        frameIndex = 1487,
        sourcePtsUs = 9_007_199_254_740_993L,
        encodedWidth = 1920,
        encodedHeight = 1080,
        presentationWidth = 1080,
        presentationHeight = 1920,
        rotationDegrees = 90,
        geometryVersion = 1,
        marks = marks,
    )

    private fun rightAngleMark(
        id: String = "11",
        ordinal: Int = 0,
        name: String = "Rodilla",
    ) = ExportAngleMark(
        id = id, ordinal = ordinal, name = name, visible = true,
        colorArgb = 0xFF3366CC.toInt(),
        pointAX = 10.0, pointAY = 0.0, pointBX = 0.0, pointBY = 0.0, pointCX = 0.0, pointCY = 10.0,
    )

    private fun hiddenMark(id: String = "12", ordinal: Int = 1) = ExportAngleMark(
        id = id, ordinal = ordinal, name = "Tobillo oculto", visible = false,
        colorArgb = 0xFF993333.toInt(),
        pointAX = 1.0, pointAY = 2.0, pointBX = 3.0, pointBY = 4.0, pointCX = 5.0, pointCY = 6.0,
    )

    private fun fvpAssessment() = ExportFvpAssessment(
        id = "fvp-7",
        sessionKey = "fvp-session",
        protocolId = "CMJ",
        dateTimeUtc = "2026-03-01T00:00:00Z",
        bodyMassKg = 80.0,
        bodyMassSource = "PROFILE",
        pushOffMode = "COMMON",
        pushOffM = 0.4,
        pushOffSource = "MEASURED",
        algorithmVersion = "fvp-poc-1.0",
        methodVersion = "fvp-app-1.0",
        conditionCount = 1,
        f0N = 1_200.0,
        slopeFv = -300.0,
        sfvNsPerM = 300.0,
        vMinMps = 0.2,
        vMaxMps = 1.6,
        fMinN = 400.0,
        fMaxN = 1_800.0,
        warningsCsv = "",
    )

    private companion object {
        const val EXPORTED_AT = "2026-03-04T05:06:07Z"
    }
}
