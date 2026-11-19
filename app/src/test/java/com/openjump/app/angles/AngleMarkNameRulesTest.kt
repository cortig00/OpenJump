package com.openjump.app.angles

import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.AngleAggregateRules
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleMarkDraft
import com.openjump.app.data.NEW_ANGLE_MARK_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del límite de nombre en la frontera de persistencia ([AngleAggregateRules]).
 *
 * Cubre que el mismo límite del editor y del backup se exige ANTES de escribir: un nombre en el
 * límite exacto se acepta (también con letras no ASCII y emoji), uno por encima se rechaza con un
 * `IllegalArgumentException` explícito y la edición completa se rechaza sin aplicar nada. Nada se
 * trunca: el nombre aceptado conserva exactamente sus unidades.
 */
class AngleMarkNameRulesTest {

    private val max = AngleMarkNameContract.MAX_CODE_UNITS

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun mark(
        ordinal: Int,
        id: Long = NEW_ANGLE_MARK_ID,
        name: String = "Marca $ordinal",
    ) = AngleMarkDraft(
        id = id,
        ordinal = ordinal,
        name = name,
        colorArgb = 0xFF3366CC.toInt(),
        pointA = p(100.0, 100.0),
        pointB = p(200.0, 200.0),
        pointC = p(300.0, 100.0),
    )

    private fun draft(marks: List<AngleMarkDraft>) = AngleAnalysisDraft(
        sessionKey = "angle-session",
        athleteId = 7L,
        encodedWidth = 1920,
        encodedHeight = 1080,
        presentationWidth = 1920,
        presentationHeight = 1080,
        rotationDegrees = 0,
        frameIndex = 42,
        sourcePtsUs = 1_000_000L,
        geometryVersion = ANGLE_GEOMETRY_VERSION,
        marks = marks,
    )

    private fun assertRejected(block: () -> Unit, expectedMessagePart: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(
            "mensaje inesperado: ${error.message}",
            error.message.orEmpty().contains(expectedMessagePart),
        )
    }

    @Test
    fun `un nombre en el limite exacto se acepta al crear el analisis`() {
        val atLimit = "ñ".repeat(max)
        AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, name = atLimit), mark(1))))
    }

    @Test
    fun `un nombre por encima del limite se rechaza con la unidad y el maximo explicitos`() {
        val overLimit = "ñ".repeat(max + 1)
        assertRejected(
            { AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, name = overLimit), mark(1)))) },
            "unidades UTF-16",
        )
        assertRejected(
            { AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, name = overLimit), mark(1)))) },
            max.toString(),
        )
    }

    @Test
    fun `nombres validos no ascii se aceptan y se conservan sin truncar`() {
        val name = "Rodilla ñ 😀 Ω 文"
        val plan = AngleAggregateRules.planUpdate(
            existingMarks = listOf(mark(0, id = 11L, name = "Cadera")),
            requestedMarks = listOf(mark(0, id = 11L, name = name)),
            removedMarkIds = emptySet(),
            presentationWidth = 1920,
            presentationHeight = 1080,
        )
        assertEquals(name, plan.updatedMarks.single().name)
        assertEquals(AngleMarkNameContract.codeUnits(name), plan.updatedMarks.single().name.length)
    }

    @Test
    fun `una marca nueva por encima del limite rechaza la edicion completa`() {
        assertRejected(
            {
                AngleAggregateRules.planUpdate(
                    existingMarks = listOf(mark(0, id = 11L, name = "Cadera")),
                    requestedMarks = listOf(mark(1, name = "ñ".repeat(max + 1))),
                    removedMarkIds = emptySet(),
                    presentationWidth = 1920,
                    presentationHeight = 1080,
                )
            },
            "unidades UTF-16",
        )
    }

    @Test
    fun `actualizar una marca existente por encima del limite rechaza la edicion completa`() {
        assertRejected(
            {
                AngleAggregateRules.planUpdate(
                    existingMarks = listOf(mark(0, id = 11L, name = "Cadera")),
                    requestedMarks = listOf(mark(0, id = 11L, name = "ñ".repeat(max + 1))),
                    removedMarkIds = emptySet(),
                    presentationWidth = 1920,
                    presentationHeight = 1080,
                )
            },
            "unidades UTF-16",
        )
    }

    @Test
    fun `el limite se mide en unidades utf16 no en caracteres visibles`() {
        // 2048 emojis = 4096 unidades: cabe exacto.
        AngleAggregateRules.requireNewAnalysis(
            draft(marks = listOf(mark(0, name = "😀".repeat(max / 2)), mark(1))),
        )
        // 2049 emojis = 4098 unidades: se rechaza.
        assertRejected(
            {
                AngleAggregateRules.requireNewAnalysis(
                    draft(marks = listOf(mark(0, name = "😀".repeat(max / 2 + 1)), mark(1))),
                )
            },
            "unidades UTF-16",
        )
    }

    @Test
    fun `el nombre vacio sigue rechazandose antes que la longitud`() {
        assertRejected(
            { AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, name = "   "), mark(1)))) },
            "nombre no vacío",
        )
    }
}
