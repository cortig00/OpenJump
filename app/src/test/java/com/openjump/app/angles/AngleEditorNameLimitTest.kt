package com.openjump.app.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del límite de nombre en el editor ([AngleEditorState.rename]).
 *
 * Fija tres cosas: el límite exacto sale aceptado (incluidos nombres no ASCII y emoji, que se
 * conservan tal cual), una unidad más se rechaza con [AngleEditorRejection.NameTooLong] en vez de
 * truncarse, y el rechazo deja intacto el nombre anterior. También conserva el orden de las reglas:
 * un nombre en blanco sigue siendo [AngleEditorRejection.BlankName] aunque además sea larguísimo.
 */
class AngleEditorNameLimitTest {

    private val max = AngleMarkNameContract.MAX_CODE_UNITS
    private val source = "video:content://openjump/jump-1"
    private val anchor = AngleFrameAnchor(source, frameIndex = 42, sourcePtsUs = 1_000_000L)
    private val defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFF3366CC.toInt())

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun confirm() = AngleFrameConfirmation(frame = anchor, generation = GENERATION, paused = true)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun rejected(outcome: AngleEditorOutcome): AngleEditorRejection =
        (outcome as? AngleEditorOutcome.Rejected)?.reason
            ?: throw AssertionError("Esperaba Rejected, llegó Applied")

    private fun editorWithOneAngle(): AngleEditorState {
        val initial = AngleEditorState.initial(source, GENERATION, defaults)
        val afterA = applied(initial.placePoint(p(100.0, 100.0), confirm()))
        val afterB = applied(afterA.placePoint(p(200.0, 100.0), confirm()))
        return applied(afterB.placePoint(p(200.0, 200.0), confirm()))
    }

    @Test
    fun `renombrar en el limite exacto se aplica y conserva el nombre sin recortar`() {
        val state = editorWithOneAngle()
        val id = state.angles.single().id
        val atLimit = "ñ".repeat(max)

        val renamed = applied(state.rename(id, atLimit))

        assertEquals(atLimit, renamed.angles.single().name)
        assertEquals(max, renamed.angles.single().name.length)
    }

    @Test
    fun `renombrar por encima del limite se rechaza con NameTooLong y no toca el nombre anterior`() {
        val state = editorWithOneAngle()
        val id = state.angles.single().id
        val previousName = state.angles.single().name

        val outcome = state.rename(id, "ñ".repeat(max + 1))

        assertEquals(
            AngleEditorRejection.NameTooLong(maxCodeUnits = max, actualCodeUnits = max + 1),
            rejected(outcome),
        )
        // El estado de partida es inmutable: el nombre anterior sigue intacto y no se truncó nada.
        assertEquals(previousName, state.angles.single().name)
        assertEquals("Ángulo 1", state.angles.single().name)
    }

    @Test
    fun `el limite se mide en unidades utf16 y el emoji cuenta dos`() {
        val state = editorWithOneAngle()
        val id = state.angles.single().id
        val fitsExactly = "😀".repeat(max / 2)

        assertEquals(fitsExactly, applied(state.rename(id, fitsExactly)).angles.single().name)
        assertEquals(
            AngleEditorRejection.NameTooLong(maxCodeUnits = max, actualCodeUnits = max + 2),
            rejected(state.rename(id, "😀".repeat(max / 2 + 1))),
        )
    }

    @Test
    fun `un nombre en blanco sigue dando BlankName aunque sea larguisimo`() {
        val state = editorWithOneAngle()
        val id = state.angles.single().id

        assertEquals(AngleEditorRejection.BlankName, rejected(state.rename(id, "   ")))
        // El orden de reglas no cambia: en blanco gana a demasiado largo.
        assertEquals(AngleEditorRejection.BlankName, rejected(state.rename(id, " ".repeat(max + 10))))
    }

    @Test
    fun `tras un rechazo por longitud un nombre valido sigue aplicandose`() {
        val state = editorWithOneAngle()
        val id = state.angles.single().id
        rejected(state.rename(id, "x".repeat(max + 1)))

        val renamed = applied(state.rename(id, "Rodilla ñ 😀 Ω"))

        assertEquals("Rodilla ñ 😀 Ω", renamed.angles.single().name)
        assertEquals(14, renamed.angles.single().name.length)
        assertTrue(renamed.angles.single().name.isNotBlank())
    }

    private companion object {
        const val GENERATION = 7L
    }
}
