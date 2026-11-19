package com.openjump.app.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.AngleAnalysisEntity
import com.openjump.app.data.AngleMarkEntity
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import java.io.StringWriter
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exportación de Ángulos: paginación keyset de raíces, detalles con IDs estables, PTS exacto,
 * atletas sólo-Ángulos en `athletePage`/`athleteRows`, ausencia de datos privados del vídeo en la
 * proyección y equivalencia byte a byte entre el repositorio streaming y el documento canónico
 * cuando hay más de una página de raíces.
 */
@RunWith(AndroidJUnit4::class)
class AngleExportDaoInstrumentedTest {
    private lateinit var database: JumpDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            JumpDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun anglePageUsesKeysetCursorAndKeepsTheExactFramePts() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Angle only", createdAt = 1, updatedAt = 1))
        val first = insertAnalysis(athleteId, sessionKey = "a", createdAt = 10, framePtsUs = 9_007_199_254_740_993L)
        val second = insertAnalysis(athleteId, sessionKey = "b", createdAt = 20, framePtsUs = 4L)
        val third = insertAnalysis(athleteId, sessionKey = "c", createdAt = 30, framePtsUs = 0L)

        val page = database.dataExportDao().anglePage(afterCreatedAt = null, afterIdText = "", limit = 2)
        assertEquals(listOf(10L, 20L), page.map { it.createdAt })
        assertEquals(listOf(first, second), page.map { it.id })
        // El PTS no pasa por Double: 2^53+1 se conserva entero.
        assertEquals(9_007_199_254_740_993L, page.first().sourcePtsUs)

        val next = database.dataExportDao()
            .anglePage(afterCreatedAt = page.last().createdAt, afterIdText = page.last().idText, limit = 2)
        assertEquals(listOf(third), next.map { it.id })
        assertEquals(0L, next.single().sourcePtsUs)

        assertTrue(database.dataExportDao().anglePage(null, "", limit = 10).isNotEmpty())
    }

    @Test
    fun anglePageTieBreaksOnIdSoEqualCreatedAtRowsAreNotLost() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Ties", createdAt = 1, updatedAt = 1))
        val ids = List(3) { index -> insertAnalysis(athleteId, sessionKey = "tie-$index", createdAt = 7, framePtsUs = index.toLong()) }

        val firstPage = database.dataExportDao().anglePage(null, "", limit = 2)
        assertEquals(ids.take(2), firstPage.map { it.id })

        val secondPage = database.dataExportDao()
            .anglePage(firstPage.last().createdAt, firstPage.last().idText, limit = 2)
        assertEquals(listOf(ids.last()), secondPage.map { it.id })
    }

    @Test
    fun angleDetailPageReturnsStableMarkIdsInOrdinalOrderAndKeepsRootsWithoutMarks() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Detail", createdAt = 1, updatedAt = 1))
        val withMarks = insertAnalysis(athleteId, sessionKey = "with-marks", createdAt = 10, framePtsUs = 5L)
        val empty = insertAnalysis(athleteId, sessionKey = "empty", createdAt = 20, framePtsUs = 6L)
        val secondMark = insertMark(withMarks, ordinal = 2, name = "Tobillo", visible = false)
        val firstMark = insertMark(withMarks, ordinal = 0, name = "Rodilla", visible = true, rightAngle = true)

        val rows = database.dataExportDao()
            .angleDetailPage(listOf(withMarks, empty), null, "", afterMarkOrdinal = -1, afterMarkId = -1L, limit = 10)

        assertEquals(listOf(firstMark, secondMark), rows.filter { it.analysisId == withMarks }.map { it.markId })
        assertEquals(listOf("Rodilla", "Tobillo"), rows.filter { it.analysisId == withMarks }.map { it.markName })
        assertNull("una raíz sin marcas sigue viajando: $rows", rows.single { it.analysisId == empty }.markId)
        assertTrue(rows.all { it.frameIndex == 1487 })
        assertTrue(rows.all { it.sourcePtsUs in setOf(5L, 6L) })
    }

    @Test
    fun athletePageAndRowsIncludeAngleOnlyAthletesAndSkipUnrelatedOnes() = runBlocking {
        val angleOnly = database.athleteDao().insert(AthleteEntity(displayName = "Angle only", createdAt = 1, updatedAt = 1))
        val unrelated = database.athleteDao().insert(AthleteEntity(displayName = "Nothing", createdAt = 1, updatedAt = 1))
        insertAnalysis(angleOnly, sessionKey = "only-angle", createdAt = 10, framePtsUs = 1L)

        assertEquals(listOf(angleOnly), database.dataExportDao().athletePage(null, limit = 10).map { it.id })
        assertEquals(listOf(angleOnly), database.dataExportDao().athleteRows().map { it.id })
        assertFalse(database.dataExportDao().athleteRows().any { it.id == unrelated })
    }

    @Test
    fun exportedAngleDocumentDerivesDegreesAndCarriesNoPrivateVideoData() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Privacy", createdAt = 1, updatedAt = 1))
        val analysisId = insertAnalysis(
            athleteId,
            sessionKey = "privacy",
            createdAt = 10,
            framePtsUs = 9_007_199_254_740_993L,
            videoUri = "content://private/secret.mp4",
            sourceIdentity = "private-source-identity",
            sourceHash = "sha256:abc",
        )
        insertMark(analysisId, ordinal = 0, name = "Rodilla", visible = true, rightAngle = true)

        val dao = database.dataExportDao()
        val root = dao.anglePage(null, "", limit = 10).single()
        val details = dao.angleDetailPage(listOf(root.id), null, "", -1, -1L, limit = 10)
        val document = DataExportDocument(
            exportedAt = "2026-03-04T05:06:07Z",
            angleAnalyses = listOf(root.toExportAngleAnalysis(details)),
        ).validated().canonicalized()

        val exported = document.angleAnalyses.single()
        assertEquals(ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT, exported.video.kind)
        assertEquals(9_007_199_254_740_993L, exported.sourcePtsUs)
        assertEquals(90.0, exported.marks.single().degrees!!, 0.0)
        assertEquals(true, exported.marks.single().visible)
        assertEquals("Rodilla", exported.marks.single().name)

        val json = JsonDataExportWriter.write(document)
        assertFalse("la URI privada no puede llegar al JSON: $json", json.contains("content://"))
        assertFalse(json.contains("secret.mp4"))
        assertFalse("la identidad de la fuente no puede llegar al JSON: $json", json.contains("private-source-identity"))
        assertFalse("el hash del contenido no puede llegar al JSON: $json", json.contains("sha256:abc"))
        assertFalse(json.contains("sourceHash"))
        assertFalse("el sessionKey deriva de la URI y tampoco se exporta: $json", json.contains("sessionKey"))
        assertFalse("la clave privada de sesión no puede aparecer: $json", json.contains("\"privacy\""))
        assertTrue(json.contains("\"sourcePtsUs\": 9007199254740993"))
        assertTrue(json.contains("\"schemaVersion\": 6"))

        // El camino CSV tampoco publica identidad privada del vídeo y conserva el PTS exacto.
        val csv = CsvDataExportWriter.write(document)
        assertFalse(csv.contains("content://"))
        assertFalse(csv.contains("private-source-identity"))
        assertFalse(csv.contains("sha256:abc"))
        assertTrue(csv.contains("9007199254740993"))
    }

    /**
     * Más raíces que una página: el repositorio streaming no puede perder ninguna raíz ni sus
     * marcas, y el JSON debe coincidir byte a byte con el documento canónico equivalente.
     */
    @Test
    fun streamingRepositoryExportsEveryAngleRootBeyondAPageAndMatchesTheCanonicalDocument() = runBlocking {
        val athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Bulk", createdAt = 1, updatedAt = 1))
        val total = DATA_EXPORT_PAGE_SIZE + 44
        val insertedAnalysisIds = linkedSetOf<Long>()
        repeat(total) { index ->
            val analysisId = insertAnalysis(
                athleteId,
                sessionKey = "bulk-$index",
                createdAt = index.toLong(),
                framePtsUs = 9_007_199_254_740_993L - index,
            )
            insertedAnalysisIds += analysisId
            // Una raíz sin hijos (index 3) debe viajar igualmente.
            if (index != 3) {
                val markCount = if (index % 7 == 0) 2 else 1
                repeat(markCount) { markIndex ->
                    insertMark(
                        analysisId,
                        ordinal = markIndex,
                        name = "Marca $index-$markIndex",
                        visible = markIndex == 0,
                        rightAngle = markIndex == 0,
                    )
                }
            }
        }

        val exportedAt = Instant.parse("2026-01-01T00:00:00Z")
        val dao = database.dataExportDao()
        val repositoryJson = StringWriter()
            .also { DataExportRepository(database).writeJson(it, exportedAt) }
            .toString()

        // Documento canónico montado con las MISMAS consultas paginadas del repositorio.
        val athletes = buildList {
            var cursor: String? = null
            while (true) {
                val page = dao.athletePage(cursor, DATA_EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { row ->
                    add(
                        ExportAthlete(
                            row.id.toString(), row.displayName,
                            row.birthDate?.let { java.time.LocalDate.ofEpochDay(it).toString() }, row.sex,
                        ),
                    )
                }
                cursor = page.last().id.toString()
            }
        }
        val analyses = buildList {
            var afterCreatedAt: Long? = null
            var afterId = ""
            while (true) {
                val roots = dao.anglePage(afterCreatedAt, afterId, DATA_EXPORT_PAGE_SIZE)
                if (roots.isEmpty()) break
                roots.forEach { root ->
                    val details = dao.angleDetailPage(listOf(root.id), null, "", -1, -1L, 100)
                    add(root.toExportAngleAnalysis(details))
                }
                val last = roots.last()
                afterCreatedAt = last.createdAt
                afterId = last.idText
            }
        }
        val canonicalJson = JsonDataExportWriter.write(
            DataExportDocument(exportedAt = exportedAt.toString(), athletes = athletes, angleAnalyses = analyses),
        )
        assertEquals(total, analyses.size)
        assertEquals("el streaming no puede divergir del documento canónico", canonicalJson, repositoryJson)
        assertFalse(repositoryJson.contains("sessionKey"))
        assertFalse(repositoryJson.contains("sourceHash"))
        assertFalse(repositoryJson.contains("sourceIdentity"))
        assertFalse(repositoryJson.contains("content://"))

        val csv = StringWriter().also { DataExportRepository(database).writeCsv(it, exportedAt) }.toString()
        // El CSV es formato largo: una fila por ATRIBUTO. Una raíz de ángulos publica exactamente
        // los atributos de ANGLE_ANALYSIS, así que contar filas no cuenta raíces: se exigen las dos
        // cosas, una fila por atributo y exactamente `total` raíces distintas (la 3 no tiene marcas
        // y también tiene que viajar).
        val csvLines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        val header = csvColumns(csvLines.first())
        val typeColumn = header.indexOf("observation_type")
        val idColumn = header.indexOf("measurement_id")
        val keyColumn = header.indexOf("metric_key")
        assertTrue("faltan columnas de identidad del CSV: $header", typeColumn >= 0 && idColumn >= 0 && keyColumn >= 0)
        val analysisRows = csvLines.drop(1)
            .map(::csvColumns)
            .filter { it[typeColumn] == "ANGLE_ANALYSIS" }
            .groupBy { it[idColumn] }
        assertEquals(
            "cada raíz publica una fila por atributo (formato largo)",
            total * ANGLE_ANALYSIS_ATTRIBUTE_KEYS.size,
            analysisRows.values.sumOf { it.size },
        )
        assertEquals(
            "no se pierde ni se duplica ninguna raíz exportada",
            insertedAnalysisIds.mapTo(linkedSetOf()) { it.toString() },
            analysisRows.keys,
        )
        assertEquals("exactamente $total raíces distintas", total, analysisRows.size)
        analysisRows.forEach { (rootId, rows) ->
            assertEquals(
                "la raíz $rootId publica cada atributo una sola vez",
                ANGLE_ANALYSIS_ATTRIBUTE_KEYS,
                rows.map { it[keyColumn] }.toSet(),
            )
            assertEquals("la raíz $rootId no repite atributos", rows.size, ANGLE_ANALYSIS_ATTRIBUTE_KEYS.size)
        }
        assertTrue("las marcas ocultas también se exportan", csv.contains("HIDDEN"))
        assertTrue(csv.contains("9007199254740993"))
        assertFalse(csv.contains("sessionKey"))
        assertFalse(csv.contains("content://"))
    }

    /** Columnas de una línea CSV respetando las celdas entrecomilladas del escritor. */
    private fun csvColumns(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                quoted && character == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index += 1
                }

                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    cells += current.toString()
                    current.setLength(0)
                }

                else -> current.append(character)
            }
            index += 1
        }
        cells += current.toString()
        return cells
    }

    private suspend fun insertAnalysis(
        athleteId: Long,
        sessionKey: String,
        createdAt: Long,
        framePtsUs: Long,
        videoUri: String = "content://private/angle.mp4",
        sourceIdentity: String = "private-source",
        sourceHash: String? = null,
    ): Long = database.angleDao().insertAnalysis(
        AngleAnalysisEntity(
            sessionKey = sessionKey,
            athleteId = athleteId,
            createdAt = createdAt,
            updatedAt = createdAt,
            revision = 0,
            videoUri = videoUri,
            sourceIdentity = sourceIdentity,
            sourceHash = sourceHash,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1080,
            presentationHeight = 1920,
            rotationDegrees = 90,
            frameIndex = 1487,
            sourcePtsUs = framePtsUs,
            geometryVersion = 1,
        ),
    )

    private suspend fun insertMark(
        analysisId: Long,
        ordinal: Int,
        name: String,
        visible: Boolean,
        rightAngle: Boolean = false,
    ): Long {
        val mark = if (rightAngle) {
            AngleMarkEntity(
                analysisId = analysisId, ordinal = ordinal, name = name, colorArgb = 0xFF3366CC.toInt(),
                visible = visible,
                pointAX = 10.0, pointAY = 0.0, pointBX = 0.0, pointBY = 0.0, pointCX = 0.0, pointCY = 10.0,
            )
        } else {
            AngleMarkEntity(
                analysisId = analysisId, ordinal = ordinal, name = name, colorArgb = 0xFF993333.toInt(),
                visible = visible,
                pointAX = 1.0, pointAY = 2.0, pointBX = 3.0, pointBY = 4.0, pointCX = 5.0, pointCY = 6.0,
            )
        }
        database.angleDao().insertMarks(listOf(mark))
        return database.angleDao().marksForAnalysis(analysisId).single { it.ordinal == ordinal }.id
    }

    private companion object {
        /**
         * Atributos que `DataExportWriters.angleObservations` publica por raíz `ANGLE_ANALYSIS`
         * (una observación por atributo: el CSV es formato largo, no una fila por raíz).
         */
        val ANGLE_ANALYSIS_ATTRIBUTE_KEYS = setOf(
            "FRAME_INDEX",
            "SOURCE_PTS",
            "ENCODED_WIDTH",
            "ENCODED_HEIGHT",
            "PRESENTATION_WIDTH",
            "PRESENTATION_HEIGHT",
            "ROTATION_DEGREES",
            "GEOMETRY_VERSION",
            "REVISION",
            "UPDATED_AT",
            "VIDEO_REFERENCE_KIND",
        )
    }
}
