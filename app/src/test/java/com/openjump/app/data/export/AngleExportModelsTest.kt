package com.openjump.app.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 1 de la exportación de Ángulos: contrato de documento (schema 7), modelo exportable de
 * raíz+marcas con grados derivados por [com.openjump.app.angles.AngleGeometry] y PTS exacto, y
 * validación/orden deterministas. Escritores, repositorio y CSV pertenecen al slice siguiente.
 *
 * El schema 7 publica el agregado multi-fotograma en la colección de nivel superior `angleV2`; la
 * familia V1 ([ExportAngleAnalysis]) conserva su forma y no se funde con esa colección.
 */
class AngleExportModelsTest {

    @Test
    fun schemaSevenOmitsBothAngleCollectionsWhenEmpty() {
        val json = JsonDataExportWriter.write(DataExportDocument(exportedAt = EXPORTED_AT))

        assertEquals(7, DATA_EXPORT_SCHEMA_VERSION)
        assertTrue("el documento declara el schema 7: $json", json.contains("\"schemaVersion\": 7"))
        assertFalse("una colección vacía no gana la clave angleAnalyses: $json", json.contains("\"angleAnalyses\""))
        assertFalse("una colección vacía no gana la clave angleV2: $json", json.contains("\"angleV2\""))
    }

    @Test
    fun schemaSevenPublishesTheV2AggregateSeparatelyFromTheLegacyCollection() {
        val document = DataExportDocument(
            exportedAt = EXPORTED_AT,
            angleAnalyses = listOf(analysis(id = "7")),
            angleV2 = listOf(v2Analysis(id = "7")),
        ).validated().canonicalized()
        val json = JsonDataExportWriter.write(document)

        // Las dos familias comparten número de id a propósito: la identidad es la colección, no el
        // id, así que ninguna de las dos puede desaparecer ni absorber a la otra.
        assertTrue("la colección V2 se publica desde el schema 7: $json", json.contains("\"angleV2\""))
        assertTrue("la colección V1 sigue viajando: $json", json.contains("\"angleAnalyses\""))
        assertTrue(json.contains("\"analysisFormat\": \"MULTI_FRAME_V1\""))
        assertEquals("7", document.angleAnalyses.single().id)
        assertEquals("7", document.angleV2.single().id)

        val decoded = readJson.decodeFromString(DataExportDocument.serializer(), json)
        assertEquals(document, decoded)
        assertEquals(1, decoded.angleV2.size)
        assertEquals(1, decoded.angleAnalyses.size)
    }

    @Test
    fun acceptsVersionsSevenSixFiveThreeTwoOneAndRejectsFourAndUnknown() {
        listOf(1, 2, 3, 5, 6, 7).forEach { version ->
            assertEquals(version, document().copy(schemaVersion = version).validated().schemaVersion)
        }

        val motion = runCatching { document().copy(schemaVersion = 4).validated() }.exceptionOrNull()
        assertTrue("4 sigue siendo la colisión histórica con Motion: $motion", motion is IllegalArgumentException)
        assertTrue(motion!!.message.orEmpty().contains("Motion"))

        listOf(0, 8, 99).forEach { version ->
            assertTrue(
                "la versión $version no puede aceptarse",
                runCatching { document().copy(schemaVersion = version).validated() }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun derivesDegreesWithAngleGeometryAndKeepsTheExactFramePts() {
        val right = mark(id = "11", ordinal = 0, ax = 10.0, ay = 0.0, bx = 0.0, by = 0.0, cx = 0.0, cy = 10.0)
        val document = document(analysis(marks = listOf(right)))

        assertEquals(90.0, document.validated().canonicalized().angleAnalyses.single().marks.single().degrees!!, 0.0)

        val json = JsonDataExportWriter.write(document)
        // 9007199254740993 no es representable en Double: el export debe conservarlo entero.
        assertTrue("el PTS viaja como Long exacto: $json", json.contains("\"sourcePtsUs\": 9007199254740993"))
        assertTrue("los grados derivados se publican sin redondear: $json", json.contains("\"degrees\": 90.0"))
        assertTrue(json.contains("\"frameIndex\": 1487"))
        assertTrue(json.contains("\"geometryVersion\": 1"))
    }

    @Test
    fun unmeasurableMarkPublishesTypedReasonInsteadOfFakeZeroDegrees() {
        val coincident = mark(id = "12", ordinal = 1, ax = 1.0, ay = 1.0, bx = 1.0, by = 1.0, cx = 2.0, cy = 2.0)
        val exported = document(analysis(marks = listOf(coincident)))
            .validated().canonicalized().angleAnalyses.single().marks.single()

        assertNull("un ángulo indefinido no puede publicar 0°", exported.degrees)
        assertEquals("COINCIDENT_VERTEX", exported.invalidReason)

        val json = JsonDataExportWriter.write(document(analysis(marks = listOf(coincident))))
        assertTrue(json.contains("\"invalidReason\": \"COINCIDENT_VERTEX\""))
    }

    @Test
    fun validatedRejectsBlankIdsDuplicateOrdinalsAndImpossibleMarks() {
        fun fails(analysis: ExportAngleAnalysis): Throwable? =
            runCatching { document(analysis).validated() }.exceptionOrNull()

        assertTrue(fails(analysis().copy(id = " ")) is IllegalArgumentException)
        assertTrue(fails(analysis().copy(frameIndex = -1)) is IllegalArgumentException)
        assertTrue(fails(analysis().copy(presentationWidth = 0)) is IllegalArgumentException)
        assertTrue(fails(analysis().copy(geometryVersion = 0)) is IllegalArgumentException)
        assertTrue(
            "dos marcas no pueden compartir ordinal",
            fails(analysis(marks = listOf(mark(id = "1", ordinal = 0), mark(id = "2", ordinal = 0)))) is IllegalArgumentException,
        )
        assertTrue(
            "dos marcas no pueden compartir id",
            fails(analysis(marks = listOf(mark(id = "1", ordinal = 0), mark(id = "1", ordinal = 1)))) is IllegalArgumentException,
        )
        assertTrue(
            "una marca sin nombre no se exporta",
            fails(analysis(marks = listOf(mark(id = "1", ordinal = 0).copy(name = " ")))) is IllegalArgumentException,
        )
        assertTrue(
            "una coordenada NaN no se exporta",
            fails(analysis(marks = listOf(mark(id = "1", ordinal = 0).copy(pointBY = Double.NaN)))) is IllegalArgumentException,
        )
        assertTrue(
            "grados fuera de [0, 180] no se exportan",
            fails(analysis(marks = listOf(mark(id = "1", ordinal = 0).copy(degrees = 181.0)))) is IllegalArgumentException,
        )
        assertTrue(
            "una marca no puede ser medible e inválida a la vez",
            fails(
                analysis(marks = listOf(mark(id = "1", ordinal = 0).copy(degrees = 90.0, invalidReason = "COINCIDENT_VERTEX"))),
            ) is IllegalArgumentException,
        )
    }

    @Test
    fun canonicalizedSortsAnalysesByCreatedAtAndMarksByOrdinal() {
        val late = analysis(
            id = "9",
            createdAtUtc = "2026-03-02T00:00:00Z",
            marks = listOf(mark(id = "30", ordinal = 2), mark(id = "10", ordinal = 0)),
        )
        val early = analysis(
            id = "10",
            createdAtUtc = "2026-03-01T00:00:00Z",
            marks = listOf(mark(id = "20", ordinal = 1), mark(id = "21", ordinal = 0)),
        )

        val canonical = document(late, early).validated().canonicalized()
        assertEquals(listOf("10", "9"), canonical.angleAnalyses.map { it.id })
        assertEquals(listOf("21", "20"), canonical.angleAnalyses.first().marks.map { it.id })
        assertEquals(listOf("10", "30"), canonical.angleAnalyses.last().marks.map { it.id })

        // El orden no depende del orden de entrada: dos documentos barajados producen el mismo JSON.
        assertEquals(JsonDataExportWriter.write(document(late, early)), JsonDataExportWriter.write(document(early, late)))
    }

    @Test
    fun canonicalOrderFollowsTheInstantNotTheIsoText() {
        // El keyset del repositorio ordena por `createdAt` (milisegundos). El texto ISO de un
        // instante sin fracción ("...:00Z") es lexicográficamente MAYOR que uno con fracción
        // ("...:00.001Z"), así que comparar los textos invertiría estas dos raíces respecto al
        // streaming. La forma canónica debe ordenar como los milisegundos para que ambos caminos
        // publiquen la misma lista.
        val zero = analysis(id = "1", createdAtUtc = "2026-03-01T00:00:00Z")
        val oneMilli = analysis(id = "2", createdAtUtc = "2026-03-01T00:00:00.001Z")

        val canonical = document(oneMilli, zero).canonicalized()

        assertEquals(listOf("1", "2"), canonical.angleAnalyses.map { it.id })
        assertEquals(JsonDataExportWriter.write(document(zero, oneMilli)), JsonDataExportWriter.write(document(oneMilli, zero)))
    }

    @Test
    fun videoReferenceCarriesOnlyTheAnonymousAnalysisRootKey() {
        val json = JsonDataExportWriter.write(document(analysis()))

        assertTrue(json.contains("\"kind\": \"ANALYSIS_ROOT\""))
        assertTrue("la correlación usa la clave estable de la raíz: $json", json.contains("\"id\": \"7\""))
        assertFalse("el hash del contenido nunca se exporta: $json", json.contains("sourceHash"))
        assertFalse("la URI del vídeo nunca se exporta: $json", json.contains("videoUri"))
        assertFalse("la identidad lógica de la fuente nunca se exporta: $json", json.contains("sourceIdentity"))
        assertFalse("el sessionKey se deriva de la URI y tampoco se exporta: $json", json.contains("sessionKey"))
        assertFalse(json.contains("content://"))
        assertEquals(ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT, analysis().video.kind)

        // El camino CSV (canónico) tampoco publica ninguna identidad privada de la fuente.
        val csv = CsvDataExportWriter.write(document(analysis()))
        assertTrue(csv.contains("VIDEO_REFERENCE_KIND"))
        assertFalse(csv.contains("sourceHash"))
        assertFalse(csv.contains("sourceIdentity"))
        assertFalse(csv.contains("content://"))
    }

    @Test
    fun documentRoundTripsThroughTheJsonReader() {
        val canonical = document(analysis(marks = listOf(mark(id = "11", ordinal = 0, bx = 0.0, by = 0.0))))
            .validated().canonicalized()
        val json = JsonDataExportWriter.write(canonical)

        val decoded = readJson.decodeFromString(DataExportDocument.serializer(), json)

        assertEquals(canonical, decoded)
        assertEquals("7", Json.parseToJsonElement(json).jsonObject["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("ANALYSIS_ROOT", decoded.angleAnalyses.single().video.kind)
        assertEquals("11", decoded.angleAnalyses.single().marks.single().id)
        assertEquals(9_007_199_254_740_993L, decoded.angleAnalyses.single().sourcePtsUs)
    }

    private fun document(vararg angleAnalyses: ExportAngleAnalysis) = DataExportDocument(
        exportedAt = EXPORTED_AT,
        angleAnalyses = angleAnalyses.toList(),
    )

    private fun v2Analysis(
        id: String = "7",
        createdAtUtc: String = "2026-03-01T00:00:00Z",
    ) = ExportAngleV2Analysis(
        id = id,
        analysisFormat = "MULTI_FRAME_V1",
        createdAtUtc = createdAtUtc,
        updatedAtUtc = createdAtUtc,
        revision = 2,
        athleteId = "42",
        frames = listOf(
            ExportAngleV2Frame(
                id = "101",
                ordinal = 0,
                assignedFrameIndex = 1487,
                reportedFrameIndex = 1487,
                assignedPtsUs = 9_007_199_254_740_993L,
                reportedPtsUs = 9_007_199_254_740_993L,
                encodedWidth = 1920,
                encodedHeight = 1080,
                presentationWidth = 1080,
                presentationHeight = 1920,
                rotationDegrees = 90,
                geometryVersion = 1,
                marks = listOf(mark(id = "201", ordinal = 0, ax = 10.0, cy = 10.0)),
            ),
        ),
    )

    private fun analysis(
        id: String = "7",
        createdAtUtc: String = "2026-03-01T00:00:00Z",
        marks: List<ExportAngleMark> = emptyList(),
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

    private fun mark(
        id: String,
        ordinal: Int,
        ax: Double = 0.0,
        ay: Double = 0.0,
        bx: Double = 0.0,
        by: Double = 0.0,
        cx: Double = 0.0,
        cy: Double = 0.0,
    ) = ExportAngleMark(
        id = id,
        ordinal = ordinal,
        name = "Rodilla $id",
        visible = ordinal % 2 == 0,
        colorArgb = 0xFF3366CC.toInt(),
        pointAX = ax,
        pointAY = ay,
        pointBX = bx,
        pointBY = by,
        pointCX = cx,
        pointCY = cy,
    )

    private companion object {
        const val EXPORTED_AT = "2026-03-04T05:06:07Z"

        val readJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
