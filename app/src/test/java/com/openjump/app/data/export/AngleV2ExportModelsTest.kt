package com.openjump.app.data.export

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice puro del export V2 de ángulos: modelo, ensamblado desde el agregado persistido,
 * validación y canonicalización. No hay documento, ni schema, ni DAO, ni escritores: estas pruebas
 * fijan la verdad del agregado (PTS `Long` exactos, asignado/reportado separados, geometría por
 * fotograma, orden determinista y ausencia total de identidad privada de fuente e imagen) antes de
 * conectarlo.
 */
class AngleV2ExportModelsTest {

    @Test
    fun keepsAssignedAndReportedPtsAsExactLongsAboveTwoPow53() {
        // 9007199254740993 = 2^53+1: el siguiente Long no es representable en Double.
        val assigned = 9_007_199_254_740_993L
        val reported = 9_007_199_254_740_997L
        assertNotEquals("un Double no puede representar 2^53+1", assigned, assigned.toDouble().toLong())

        val exported = rootEntity().toExportAngleV2Analysis(
            frames = listOf(frameEntity(id = 3, assignedPtsUs = assigned, reportedPtsUs = reported)),
            marks = emptyList(),
        ).validatedV2().canonicalizedV2Analysis()

        val frame = exported.frames.single()
        assertEquals(assigned, frame.assignedPtsUs)
        assertEquals(reported, frame.reportedPtsUs)
        assertEquals(1487, frame.assignedFrameIndex)

        val json = exported.json()
        assertTrue("el PTS asignado viaja como Long exacto: $json", json.contains("\"assignedPtsUs\": $assigned"))
        assertTrue("el PTS reportado viaja como Long exacto: $json", json.contains("\"reportedPtsUs\": $reported"))
        assertFalse("ningún PTS se publica en notación científica: $json", json.contains("E7"))
    }

    @Test
    fun unverifiedReportedValuesStayNullAndAreNeverCopiedFromAssigned() {
        val exported = rootEntity().toExportAngleV2Analysis(
            frames = listOf(
                frameEntity(
                    id = 3,
                    assignedFrameIndex = 1487,
                    assignedPtsUs = 9_007_199_254_740_993L,
                    reportedFrameIndex = null,
                    reportedPtsUs = null,
                ),
            ),
            marks = emptyList(),
        ).validatedV2().canonicalizedV2Analysis()

        val frame = exported.frames.single()
        assertNull("lo no verificado no se fabrica desde el asignado", frame.reportedFrameIndex)
        assertNull("lo no verificado no se fabrica desde el asignado", frame.reportedPtsUs)
        assertEquals(1487, frame.assignedFrameIndex)
        assertEquals(9_007_199_254_740_993L, frame.assignedPtsUs)

        val json = exported.json()
        assertTrue("el null viaja explícito: $json", json.contains("\"reportedFrameIndex\": null"))
        assertTrue("el null viaja explícito: $json", json.contains("\"reportedPtsUs\": null"))

        // Y cuando la extracción sí reporta, se conserva el valor reportado, distinto del asignado.
        val verified = frameEntity(
            id = 3,
            assignedFrameIndex = 1487,
            reportedFrameIndex = 1490,
            assignedPtsUs = 9_007_199_254_740_993L,
            reportedPtsUs = 9_007_199_254_741_010L,
        ).toExportAngleV2Frame(marks = emptyList())
        assertEquals(1487, verified.assignedFrameIndex)
        assertEquals(1490, verified.reportedFrameIndex)
        assertEquals(9_007_199_254_740_993L, verified.assignedPtsUs)
        assertEquals(9_007_199_254_741_010L, verified.reportedPtsUs)
    }

    @Test
    fun duplicateMarkOrdinalsFailInsideAFrameButAreAllowedAcrossFrames() {
        val marks = listOf(
            mark(id = "11", ordinal = 0),
            mark(id = "12", ordinal = 1),
        )
        val leftFrame = frame(id = "3", ordinal = 0, marks = marks)
        val rightFrame = frame(id = "4", ordinal = 1, marks = marks.map { it.copy(id = "2${it.id}") })

        // El mismo ordinal en DOS fotogramas distintos es legítimo: la unicidad de Room es
        // (frameId, ordinal). Un emisor que la tratara como global perdería datos reales.
        val acrossFrames = analysisV2(frames = listOf(leftFrame, rightFrame)).validatedV2()
        assertEquals(2, acrossFrames.frames.size)
        assertEquals(listOf(0, 1), acrossFrames.frames.map { it.marks.map { mark -> mark.ordinal } }.flatten().distinct())

        // Dentro de un mismo fotograma, el ordinal repetido se rechaza (falla en cerrado).
        val repeatedInsideFrame = analysisV2(
            frames = listOf(leftFrame.copy(marks = listOf(mark(id = "11", ordinal = 0), mark(id = "13", ordinal = 0)))),
        )
        assertTrue(fails(repeatedInsideFrame) is IllegalArgumentException)
        assertTrue(fails(repeatedInsideFrame)!!.message.orEmpty().contains("repeats mark ordinal"))

        // El id de marca es su identidad persistente (PK global): repetirlo entre fotogramas de la
        // misma raíz también es ambiguo.
        val repeatedMarkId = analysisV2(
            frames = listOf(leftFrame, rightFrame.copy(marks = listOf(mark(id = "11", ordinal = 0)))),
        )
        assertTrue(fails(repeatedMarkId) is IllegalArgumentException)
        assertTrue(fails(repeatedMarkId)!!.message.orEmpty().contains("repeats mark id"))

        // Los fotogramas sí exigen ordinal e id únicos en la raíz (índice (rootId, ordinal)).
        assertTrue(fails(analysisV2(frames = listOf(leftFrame, rightFrame.copy(ordinal = 0)))) is IllegalArgumentException)
        assertTrue(fails(analysisV2(frames = listOf(leftFrame, rightFrame.copy(id = "3")))) is IllegalArgumentException)
    }

    @Test
    fun recalculatesDegreesWithTheExistingGeometryAndNeverPublishesAFakeZero() {
        // Geometría válida (90°) con grados obsoletos persistidos en el modelo: se recalculan.
        val stale = mark(id = "11", ordinal = 0, ax = 10.0, ay = 0.0, bx = 0.0, by = 0.0, cx = 0.0, cy = 10.0)
            .copy(degrees = 180.0)
        val measured = analysisV2(frames = listOf(frame(id = "3", marks = listOf(stale))))
            .canonicalizedV2Analysis()
            .frames.single().marks.single()
        assertEquals(90.0, measured.degrees!!, 0.0)
        assertNull(measured.invalidReason)

        // Geometría inválida con puntos finitos: null + motivo tipado, nunca 0°.
        val coincident = mark(id = "12", ordinal = 1, ax = 1.0, ay = 1.0, bx = 1.0, by = 1.0, cx = 2.0, cy = 2.0)
        val invalid = analysisV2(frames = listOf(frame(id = "3", marks = listOf(coincident))))
            .validatedV2().canonicalizedV2Analysis()
            .frames.single().marks.single()
        assertNull(invalid.degrees)
        assertEquals("COINCIDENT_VERTEX", invalid.invalidReason)

        val json = analysisV2(frames = listOf(frame(id = "3", marks = listOf(stale, coincident))))
            .validatedV2().canonicalizedV2Analysis().json()
        assertTrue("los grados derivados se publican sin redondear: $json", json.contains("\"degrees\": 90.0"))
        assertTrue("el motivo es tipado: $json", json.contains("\"invalidReason\": \"COINCIDENT_VERTEX\""))
        assertFalse(json.contains("\"degrees\": 180.0"))
        assertFalse(json.contains("\"degrees\": 0.0"))
    }

    @Test
    fun canonicalOrderIsDeterministicIdempotentAndIndependentOfInputOrder() {
        val early = analysisV2(id = "10", createdAtUtc = "2026-03-01T00:00:00Z")
        val late = analysisV2(
            id = "9",
            createdAtUtc = "2026-03-01T00:00:00.001Z",
            frames = listOf(
                frame(id = "30", ordinal = 2, marks = listOf(mark(id = "300", ordinal = 2), mark(id = "100", ordinal = 0))),
                frame(id = "20", ordinal = 0, marks = listOf(mark(id = "200", ordinal = 1), mark(id = "201", ordinal = 0))),
            ),
        )

        val forward = listOf(late, early).canonicalizedV2Analyses()
        val backward = listOf(early, late).canonicalizedV2Analyses()

        // Ordena por instante (no por texto ISO) y luego por id, como el keyset (createdAt, id).
        assertEquals(listOf("10", "9"), forward.map { it.id })
        assertEquals(listOf("20", "30"), forward.last().frames.map { it.id })
        assertEquals(listOf("201", "200"), forward.last().frames.first().marks.map { it.id })
        assertEquals(listOf("100", "300"), forward.last().frames.last().marks.map { it.id })
        assertEquals(forward.json(), backward.json())
        assertEquals("canonicalizar dos veces no cambia nada", forward.json(), forward.canonicalizedV2Analyses().json())
    }

    @Test
    fun validatedRejectsImpossibleGeometryIndicesAndIdentifiers() {
        fun failsOn(vararg frames: ExportAngleV2Frame): Throwable? = fails(analysisV2(frames = frames.toList()))

        val baseMark = mark(id = "11", ordinal = 0)
        assertTrue(fails(analysisV2(id = " ")) is IllegalArgumentException)
        assertTrue(fails(analysisV2(analysisFormat = " ")) is IllegalArgumentException)
        assertTrue(fails(analysisV2(createdAtUtc = " ")) is IllegalArgumentException)
        assertTrue(fails(analysisV2(updatedAtUtc = " ")) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = " ", marks = listOf(baseMark))) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", ordinal = -1)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", assignedFrameIndex = -1)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", reportedFrameIndex = -1)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", assignedPtsUs = -1L)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", reportedPtsUs = -1L)) is IllegalArgumentException)
        assertTrue("las dimensiones codificadas deben ser positivas", failsOn(frame(id = "3", encodedWidth = 0)) is IllegalArgumentException)
        assertTrue("las dimensiones de presentación deben ser positivas", failsOn(frame(id = "3", presentationHeight = 0)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", geometryVersion = 0)) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", marks = listOf(baseMark.copy(name = " ")))) is IllegalArgumentException)
        assertTrue(failsOn(frame(id = "3", marks = listOf(baseMark.copy(pointBY = Double.NaN)))) is IllegalArgumentException)
        assertTrue(
            "grados fuera de [0, 180] no se exportan",
            failsOn(frame(id = "3", marks = listOf(baseMark.copy(degrees = 181.0)))) is IllegalArgumentException,
        )
        assertTrue(
            "una marca no puede ser medible e inválida a la vez",
            failsOn(
                frame(id = "3", marks = listOf(baseMark.copy(degrees = 90.0, invalidReason = "COINCIDENT_VERTEX"))),
            ) is IllegalArgumentException,
        )
        // La raíz es válida con 0 fotogramas y el fotograma con 0 marcas: son estados reales.
        assertEquals(0, analysisV2().validatedV2().frames.size)
        assertEquals(0, analysisV2(frames = listOf(frame(id = "3"))).validatedV2().frames.single().marks.size)
    }

    @Test
    fun serializedAggregateCarriesNoSourceOrImageIdentity() {
        val sessionKey = "content://media/external/video/media/4242?frame=1487"
        val imageKey = "8f14e45fceea167a5a36dedd4bea2543"
        val imageSha256 = "e3b0c44298fc1c149afbf4c8996fb924"
        val manifest = rootEntity(sessionKey = sessionKey).toExportAngleV2Analysis(
            frames = listOf(
                frameEntity(id = 3, imageKey = imageKey, imageSha256 = imageSha256, imageByteCount = 654_321, imageMimeType = "image/png"),
            ),
            marks = listOf(markEntity(id = 11, frameId = 3)),
        ).validatedV2().canonicalizedV2Analysis()

        assertEquals("la raíz se correlaciona por su id anónimo", "7", manifest.id)
        assertEquals(ANGLE_VIDEO_REFERENCE_ANALYSIS_ROOT, manifest.video.kind)
        assertEquals(1, manifest.frames.single().marks.size)

        val json = manifest.json()
        assertFalse("el sessionKey deriva de la URI del vídeo: $json", json.contains("sessionKey"))
        assertFalse("la URI nunca se publica: $json", json.contains("content://"))
        assertFalse("la clave opaca del almacén no se publica: $json", json.contains(imageKey))
        assertFalse("el hash del contenido no se publica: $json", json.contains(imageSha256))
        assertFalse("ningún campo del fichero anotado se publica: $json", json.contains("image"))
        assertFalse(json.contains("sha256", ignoreCase = true))
        // Lo que sí viaja: identidad persistente, formato, geometría y marcas.
        assertTrue(json.contains("\"analysisFormat\": \"$ANGLE_ANALYSIS_V2_FORMAT\""))
        assertTrue(json.contains("\"id\": \"3\""))
        assertTrue(json.contains("\"name\": \"Rodilla 11\""))
    }

    @Test
    fun persistedFormatAthleteAndFrameGeometryTravelVerbatim() {
        // Una raíz V2 puede colisionar en id con `angle_analyses.id`: lo que la distingue es la
        // colección + el formato persistido, que se declara tal cual (no se fuerza a la constante).
        val exported = rootEntity(id = 7, format = "ANGLE_MULTI_FRAME_V2", athleteId = null).toExportAngleV2Analysis(
            frames = listOf(
                frameEntity(id = 3, ordinal = 0, rotationDegrees = 90, encodedWidth = 1920, encodedHeight = 1080, presentationWidth = 1080, presentationHeight = 1920),
                frameEntity(id = 4, ordinal = 1, rotationDegrees = 0, encodedWidth = 1080, encodedHeight = 1920, presentationWidth = 1080, presentationHeight = 1920),
            ),
            marks = emptyList(),
        ).validatedV2().canonicalizedV2Analysis()

        assertEquals("ANGLE_MULTI_FRAME_V2", exported.analysisFormat)
        assertNull("un atleta ausente no se inventa", exported.athleteId)
        assertEquals(listOf(90, 0), exported.frames.map { it.rotationDegrees })
        assertEquals(listOf(1920, 1080), exported.frames.map { it.encodedWidth })
        assertEquals(listOf(1080, 1080), exported.frames.map { it.presentationWidth })
        assertEquals(listOf(1, 1), exported.frames.map { it.geometryVersion })

        val json = exported.json()
        assertTrue(json.contains("\"analysisFormat\": \"ANGLE_MULTI_FRAME_V2\""))
        assertTrue(json.contains("\"athleteId\": null"))
        assertTrue(json.contains("\"rotationDegrees\": 90"))
        assertTrue(json.contains("\"encodedWidth\": 1920"))
    }

    @Test
    fun emptyCollectionsArePublishedExplicitlyInsteadOfDroppingTheRoot() {
        val rootWithoutFrames = analysisV2(id = "7", frames = emptyList()).validatedV2().canonicalizedV2Analysis()
        assertTrue("la raíz sin fotogramas se publica como lista vacía: ${rootWithoutFrames.json()}", rootWithoutFrames.json().contains("\"frames\": []"))

        val frameWithoutMarks = analysisV2(id = "7", frames = listOf(frame(id = "3"))).validatedV2().canonicalizedV2Analysis()
        assertEquals(1, frameWithoutMarks.frames.size)
        assertTrue(frameWithoutMarks.json().contains("\"marks\": []"))

        // Ensamblador: una raíz sin filas de fotogramas tampoco desaparece.
        val assembled = rootEntity().toExportAngleV2Analysis(frames = emptyList(), marks = emptyList())
        assertEquals(0, assembled.validatedV2().frames.size)
    }

    @Test
    fun assemblerRejectsRowsThatDoNotBelongToTheRootOrFrame() {
        val foreignFrame = frameEntity(id = 9, rootId = 99)
        assertTrue(
            "un fotograma de otra raíz no puede colarse",
            runCatching { rootEntity().toExportAngleV2Analysis(frames = listOf(foreignFrame), marks = emptyList()) }
                .exceptionOrNull() is IllegalArgumentException,
        )

        val frame = frameEntity(id = 3)
        val foreignMark = markEntity(id = 11, frameId = 99)
        assertTrue(
            "una marca de otro fotograma no puede colarse",
            runCatching { rootEntity().toExportAngleV2Analysis(frames = listOf(frame), marks = listOf(foreignMark)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            "el ensamblador de fotograma valida la pertenencia de sus marcas",
            runCatching { frame.toExportAngleV2Frame(marks = listOf(foreignMark)) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun fails(analysis: ExportAngleV2Analysis): Throwable? =
        runCatching { analysis.validatedV2() }.exceptionOrNull()

    private fun ExportAngleV2Analysis.json(): String =
        json.encodeToString(ExportAngleV2Analysis.serializer(), this)

    private fun List<ExportAngleV2Analysis>.json(): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ExportAngleV2Analysis.serializer()), this)

    private fun analysisV2(
        id: String = "7",
        analysisFormat: String = ANGLE_ANALYSIS_V2_FORMAT,
        createdAtUtc: String = "2026-03-01T00:00:00Z",
        updatedAtUtc: String = createdAtUtc,
        athleteId: String? = "42",
        frames: List<ExportAngleV2Frame> = emptyList(),
    ) = ExportAngleV2Analysis(
        id = id,
        analysisFormat = analysisFormat,
        createdAtUtc = createdAtUtc,
        updatedAtUtc = updatedAtUtc,
        revision = 3,
        athleteId = athleteId,
        frames = frames,
    )

    private fun frame(
        id: String,
        ordinal: Int = 0,
        assignedFrameIndex: Int = 1487,
        reportedFrameIndex: Int? = null,
        assignedPtsUs: Long = 9_007_199_254_740_993L,
        reportedPtsUs: Long? = null,
        encodedWidth: Int = 1920,
        encodedHeight: Int = 1080,
        presentationWidth: Int = 1080,
        presentationHeight: Int = 1920,
        rotationDegrees: Int = 90,
        geometryVersion: Int = 1,
        marks: List<ExportAngleMark> = emptyList(),
    ) = ExportAngleV2Frame(
        id = id,
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        marks = marks,
    )

    private fun mark(
        id: String,
        ordinal: Int,
        ax: Double = 10.0,
        ay: Double = 0.0,
        bx: Double = 0.0,
        by: Double = 0.0,
        cx: Double = 0.0,
        cy: Double = 10.0,
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

    // --- Filas persistidas: los valores privados existen aquí y sólo aquí. ---

    private fun rootEntity(
        id: Long = 7,
        format: String = ANGLE_ANALYSIS_V2_FORMAT,
        sessionKey: String = "content://media/external/video/media/4242?frame=1487",
        athleteId: Long? = 42,
    ) = AngleAnalysisV2RootEntity(
        id = id,
        analysisFormat = format,
        sessionKey = sessionKey,
        athleteId = athleteId,
        createdAt = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(),
        updatedAt = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli(),
        revision = 3,
    )

    private fun frameEntity(
        id: Long,
        rootId: Long = 7,
        ordinal: Int = 0,
        assignedFrameIndex: Int = 1487,
        reportedFrameIndex: Int? = null,
        assignedPtsUs: Long = 9_007_199_254_740_993L,
        reportedPtsUs: Long? = null,
        encodedWidth: Int = 1920,
        encodedHeight: Int = 1080,
        presentationWidth: Int = 1080,
        presentationHeight: Int = 1920,
        rotationDegrees: Int = 90,
        geometryVersion: Int = 1,
        imageKey: String = "8f14e45fceea167a5a36dedd4bea2543",
        imageSha256: String = "e3b0c44298fc1c149afbf4c8996fb924",
        imageByteCount: Long = 654_321,
        imageMimeType: String = "image/png",
    ) = AngleAnalysisV2FrameEntity(
        id = id,
        rootId = rootId,
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedPtsUs,
        reportedPtsUs = reportedPtsUs,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotationDegrees,
        geometryVersion = geometryVersion,
        imageKey = imageKey,
        imageWidth = presentationWidth,
        imageHeight = presentationHeight,
        imageByteCount = imageByteCount,
        imageSha256 = imageSha256,
        imageMimeType = imageMimeType,
    )

    private fun markEntity(
        id: Long,
        frameId: Long,
        ordinal: Int = 0,
        ax: Double = 10.0,
        ay: Double = 0.0,
        bx: Double = 0.0,
        by: Double = 0.0,
        cx: Double = 0.0,
        cy: Double = 10.0,
    ) = AngleAnalysisV2MarkEntity(
        id = id,
        frameId = frameId,
        ordinal = ordinal,
        name = "Rodilla $id",
        colorArgb = 0xFF3366CC.toInt(),
        visible = true,
        pointAX = ax,
        pointAY = ay,
        pointBX = bx,
        pointBY = by,
        pointCX = cx,
        pointCY = cy,
    )

    private companion object {
        /** Mismo contrato de serialización que el escritor de export (`encodeDefaults`). */
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = true
        }
    }
}
