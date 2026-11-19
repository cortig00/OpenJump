package com.openjump.app.ui.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Guard JVM de la presentación del detalle V2: identidad de la petición de imagen, contraste
 * observado de la extracción y compatibilidad del nombre del dueño.
 *
 * Son pruebas de comportamiento sobre las funciones puras de producción, sin Room, sin Android y
 * sin inspección de texto fuente: el mismo `frame` de fixture se proyecta en peticiones y
 * observaciones y se comprueba qué componentes cambian la identidad y cuáles no.
 */
class AngleV2SavedDetailPresentationTest {

    // --- identidad de la petición de imagen ------------------------------------------------------

    @Test
    fun `la peticion copia exactamente los metadatos declarados del fotograma`() {
        val frame = frame()
        val request = frame.imageRequest(rootId = 7L)
        assertEquals(7L, request.rootId)
        assertEquals(frame.frameId, request.frameId)
        assertEquals(frame.imageKey, request.imageKey)
        assertEquals(frame.imageByteCount, request.imageByteCount)
        assertEquals(frame.imageSha256, request.imageSha256)
        assertEquals(frame.imageWidth, request.imageWidth)
        assertEquals(frame.imageHeight, request.imageHeight)
        assertEquals(frame.imageMimeType, request.imageMimeType)
    }

    @Test
    fun `sin cambios la identidad de peticion es la misma`() {
        val frame = frame()
        assertEquals(
            "La misma raíz y el mismo fotograma deben pedir la misma imagen.",
            frame.imageRequest(rootId = 7L),
            frame.imageRequest(rootId = 7L),
        )
    }

    @Test
    fun `cada componente declarado cambia la identidad de la peticion`() {
        val frame = frame()
        val base = frame.imageRequest(rootId = 7L)

        // Raíz y fotograma: el mismo contenido de otra raíz no es la misma petición.
        assertNotEquals("rootId", base, base.copy(rootId = 8L))
        assertNotEquals("frameId", base, base.copy(frameId = base.frameId + 1L))
        assertNotEquals("imageKey", base, base.copy(imageKey = "1".repeat(32)))
        assertNotEquals("imageSha256", base, base.copy(imageSha256 = "f".repeat(64)))
        assertNotEquals("imageByteCount", base, base.copy(imageByteCount = base.imageByteCount + 1L))
        assertNotEquals("imageWidth", base, base.copy(imageWidth = base.imageWidth + 1))
        assertNotEquals("imageHeight", base, base.copy(imageHeight = base.imageHeight + 1))
        assertNotEquals("imageMimeType", base, base.copy(imageMimeType = "image/jpeg"))
    }

    @Test
    fun `la identidad de peticion ignora lo que no declara`() {
        val frame = frame()
        // Presentación, rotación, orden, marcas e índices observados no forman parte de la identidad
        // DECLARADA del fichero: cambiarlos no debe pedir otra imagen.
        val altered = frame.copy(
            ordinal = frame.ordinal + 3,
            assignedFrameIndex = frame.assignedFrameIndex + 5,
            reportedFrameIndex = 99,
            presentationWidth = frame.presentationWidth + 10,
            presentationHeight = frame.presentationHeight + 10,
            rotationDegrees = 90,
            marks = listOf(mark(id = 5L)),
        )
        assertEquals(frame.imageRequest(rootId = 7L), altered.imageRequest(rootId = 7L))
    }

    // --- observación de la extracción ------------------------------------------------------------

    @Test
    fun `sin indice reportado la extraccion queda sin verificar y no se presenta como coincidencia`() {
        assertEquals(
            AngleV2FrameExtractionObservation.UNVERIFIED,
            angleV2FrameExtractionObservation(assignedFrameIndex = 3, reportedFrameIndex = null),
        )
        assertNull("La fila del fixture no reporta índice.", frame().reportedFrameIndex)
        assertEquals(AngleV2FrameExtractionObservation.UNVERIFIED, frame().extractionObservation)
    }

    @Test
    fun `indice reportado igual al asignado es coincidencia`() {
        assertEquals(
            AngleV2FrameExtractionObservation.MATCH,
            angleV2FrameExtractionObservation(assignedFrameIndex = 3, reportedFrameIndex = 3),
        )
    }

    @Test
    fun `indice reportado distinto es discrepancia verificada`() {
        assertEquals(
            AngleV2FrameExtractionObservation.MISMATCH,
            angleV2FrameExtractionObservation(assignedFrameIndex = 3, reportedFrameIndex = 4),
        )
    }

    @Test
    fun `una discrepancia no cambia el numero mostrado asignado`() {
        val frame = frame(assignedFrameIndex = 4, reportedFrameIndex = 9)
        assertEquals(AngleV2FrameExtractionObservation.MISMATCH, frame.extractionObservation)
        assertEquals(
            "El número visible sigue saliendo del índice ASIGNADO, no del reportado.",
            angleSavedFrameDisplayNumber(4),
            frame.displayFrameNumber,
        )
        assertEquals(5, frame.displayFrameNumber)
    }

    // --- nombre del dueño y compatibilidad del modelo ---------------------------------------------

    @Test
    fun `el nombre del atleta es opcional y por defecto no se inventa`() {
        val withoutName = content()
        assertNull("El valor por defecto es 'sin nombre', no un texto de relleno.", withoutName.athleteName)
        assertEquals(
            "Construir el contenido sin nombre sigue siendo válido.",
            withoutName,
            content(athleteName = null),
        )
        assertNotEquals(
            "El nombre del dueño forma parte de la foto publicada.",
            withoutName,
            withoutName.copy(athleteName = "Ada"),
        )
        assertEquals("Ada", withoutName.copy(athleteName = "Ada").athleteName)
    }

    @Test
    fun `el contenido conserva sus datos derivados con y sin nombre`() {
        val frames = listOf(frame(), frame(ordinal = 1, frameId = 2L, imageKey = "1".repeat(32)))
        val named = content(frames = frames, athleteName = "Ada").copy(athleteName = "Ada")
        assertEquals(2, named.frames.size)
        assertEquals(frames.sumOf { it.marks.size }, named.markCount)
        assertSame(frames, named.frames)
    }

    // --- presupuesto de imagen sin cambios -------------------------------------------------------

    @Test
    fun `el presupuesto de imagen no cambia`() {
        assertEquals(1280, AngleV2SavedImageBudget.MAX_DIMENSION)
        assertEquals(1_400_000L, AngleV2SavedImageBudget.MAX_PIXELS)
        assertEquals(4096, AngleV2SavedImageBudget.MAX_SAMPLE_SIZE)
        assertEquals(1, angleV2ImageSampleSize(width = 800, height = 600))
        assertEquals(
            "4000x3000 necesita muestreo 4 para caber en 1280 px de lado.",
            4,
            angleV2ImageSampleSize(width = 4000, height = 3000),
        )
        assertNull(angleV2ImageSampleSize(width = 0, height = 600))
    }

    // --- fixtures --------------------------------------------------------------------------------

    private fun frame(
        ordinal: Int = 0,
        frameId: Long = 1L,
        assignedFrameIndex: Int = 0,
        reportedFrameIndex: Int? = null,
        imageKey: String = "0".repeat(32),
    ): AngleV2SavedDetailFrame = AngleV2SavedDetailFrame(
        ordinal = ordinal,
        frameId = frameId,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        presentationWidth = 1080,
        presentationHeight = 1920,
        rotationDegrees = 0,
        imageKey = imageKey,
        imageWidth = 1080,
        imageHeight = 1920,
        imageByteCount = 24_576L,
        imageSha256 = "a".repeat(64),
        imageMimeType = "image/png",
        marks = listOf(mark(id = frameId)),
    )

    private fun mark(id: Long): AngleSavedDetailMark = angleSavedMark(
        id = id,
        ordinal = 0,
        name = "Rodilla",
        visible = true,
        pointAX = 10.0,
        pointAY = 10.0,
        pointBX = 20.0,
        pointBY = 10.0,
        pointCX = 10.0,
        pointCY = 20.0,
    )

    private fun content(
        frames: List<AngleV2SavedDetailFrame> = listOf(frame()),
        athleteName: String? = null,
    ): AngleV2SavedDetailContent = AngleV2SavedDetailContent(
        rootId = 7L,
        createdAt = 1_700_000_000_000L,
        athleteId = 42L,
        frames = frames,
        athleteName = athleteName,
    )
}
