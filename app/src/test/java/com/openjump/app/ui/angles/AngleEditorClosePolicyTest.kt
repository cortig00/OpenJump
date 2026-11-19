package com.openjump.app.ui.angles

import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarkers
import com.openjump.app.angles.AnglePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM de la política de cierre del editor de ángulos.
 *
 * Fija el contrato del hueco real: el cierre solo puede salir sin confirmación cuando el contenido
 * vivo es **semánticamente idéntico** al contenido verificado como persistido. No basta con mirar
 * `angles.isNotEmpty()` ni los fotogramas congelados: un borrador parcial, una edición de nombre o
 * color, una baja, o una línea base desconocida también se pierden al cerrar.
 *
 * Contrapartida: seleccionar, navegar por frames o marcar ids persistidos **no** son contenido y no
 * pueden convertir en «sucio» un editor limpio (eso acabaría pidiendo confirmación siempre).
 */
class AngleEditorClosePolicyTest {

    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = COLOR_ARGB)

    private val anchor = AngleFrameAnchor(SOURCE, frameIndex = 2, sourcePtsUs = 80_000L)

    private fun angle(
        id: Long = 1L,
        name: String = "Angle 1",
        colorArgb: Int = COLOR_ARGB,
        visible: Boolean = true,
        offsetX: Double = 0.0,
    ) = Angle(
        id = AngleId(id),
        name = name,
        colorArgb = colorArgb,
        visible = visible,
        markers = AngleMarkers(
            a = AnglePoint(10.0 + offsetX, 10.0),
            b = AnglePoint(10.0, 50.0),
            c = AnglePoint(50.0, 10.0),
        ),
    )

    private fun state(
        angles: List<Angle> = listOf(angle()),
        anchor: AngleFrameAnchor? = this.anchor,
        generation: Long = 1L,
        selectedAngleId: AngleId? = null,
        persisted: Set<AngleId> = emptySet(),
    ): AngleEditorState = AngleEditorState.rehydrate(
        sourceIdentity = SOURCE,
        generation = generation,
        anchor = if (angles.isEmpty()) null else anchor,
        angles = angles,
        defaults = defaults,
        selectedAngleId = selectedAngleId,
        externallyPersistedAngleIds = persisted,
    )

    /** Estado con dos puntos A/B y sin ángulo completo: el borrador no es persistible. */
    private fun draftState(): AngleEditorState {
        val confirmation = AngleFrameConfirmation(anchor, generation = 1L, paused = true)
        var state = AngleEditorState.initial(SOURCE, generation = 1L, defaults = defaults)
        listOf(AnglePoint(10.0, 10.0), AnglePoint(10.0, 50.0)).forEach { point ->
            state = (state.placePoint(point, confirmation) as AngleEditorOutcome.Applied).state
        }
        return state
    }

    private fun decision(
        baseline: AngleEditorContent?,
        current: AngleEditorContent,
        frozenFrameCount: Int = 0,
        saveInFlight: Boolean = false,
    ) = angleEditorCloseDecision(
        saveInFlight = saveInFlight,
        baseline = baseline,
        current = current,
        frozenFrameCount = frozenFrameCount,
    )

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState {
        assertTrue("se esperaba un resultado aplicado: $outcome", outcome is AngleEditorOutcome.Applied)
        return (outcome as AngleEditorOutcome.Applied).state
    }

    @Test
    fun `un editor recien abierto y vacio sale sin confirmacion`() {
        val initial = AngleEditorState.initial(SOURCE, generation = 1L, defaults = defaults)

        assertEquals(
            AngleEditorCloseDecision.Exit,
            decision(angleEditorBaselineContent(initial), angleEditorContent(initial)),
        )
    }

    @Test
    fun `seleccion generacion y ids persistidos no ensucian el contenido`() {
        val baseline = angleEditorBaselineContent(state())
        val selected = state(
            generation = 9L,
            selectedAngleId = AngleId(1),
            persisted = setOf(AngleId(1)),
        )

        // El mismo contenido con otra generación, selección e ids persistidos es la misma edición.
        assertEquals(angleEditorContent(selected), baseline)
        assertEquals(AngleEditorCloseDecision.Exit, decision(baseline, angleEditorContent(selected)))
    }

    @Test
    fun `coordenadas nombre color y visibilidad cuentan como cambios`() {
        val baseline = angleEditorBaselineContent(state())

        val moved = angleEditorContent(state(angles = listOf(angle(offsetX = 4.0))))
        val renamed = angleEditorContent(state(angles = listOf(angle(name = "Cadera"))))
        val recolored = angleEditorContent(state(angles = listOf(angle(colorArgb = 0xFFAA0000.toInt()))))
        val hidden = angleEditorContent(state(angles = listOf(angle(visible = false))))

        assertNotEquals(baseline, moved)
        assertNotEquals(baseline, renamed)
        assertNotEquals(baseline, recolored)
        assertNotEquals(baseline, hidden)
        listOf(moved, renamed, recolored, hidden).forEach { current ->
            val decision = decision(baseline, current)
            assertTrue("un cambio de contenido debe exigir confirmación", decision is AngleEditorCloseDecision.Confirm)
            assertTrue((decision as AngleEditorCloseDecision.Confirm).content.liveUnsavedChanges)
        }
    }

    @Test
    fun `borrar un angulo o todos tras una linea base no vacia exige confirmar`() {
        val twoAngles = state(angles = listOf(angle(id = 1L, name = "Angle 1"), angle(id = 2L, name = "Angle 2")))
        val baseline = angleEditorBaselineContent(twoAngles)

        val oneLeft = angleEditorContent(state(angles = listOf(angle(id = 1L, name = "Angle 1"))))
        val noneLeft = angleEditorContent(state(angles = emptyList()))

        assertNotEquals(baseline, oneLeft)
        assertNotEquals(baseline, noneLeft)
        listOf(oneLeft, noneLeft).forEach { current ->
            assertTrue(decision(baseline, current) is AngleEditorCloseDecision.Confirm)
        }
    }

    @Test
    fun `cancelar el borrador no deja contenido sin guardar aunque conserve el ancla`() {
        val cancelled = applied(draftState().cancelDraft())

        // El estado real conserva el ancla tras cancelar: si la proyección no la normalizara, este
        // caso se vería como una edición sin guardar y pediría confirmación sin motivo.
        assertNotNull("cancelDraft no libera el ancla", cancelled.anchor)
        assertEquals(0, cancelled.draft.points.size)
        assertTrue(cancelled.angles.isEmpty())

        val freshBaseline = angleEditorContent(AngleEditorState.initial(SOURCE, generation = 1L, defaults = defaults))
        val current = angleEditorContent(cancelled)
        assertEquals(AngleEditorContent(), freshBaseline)
        assertEquals("un ancla sin ángulos ni borrador no es contenido", AngleEditorContent(), current)
        assertEquals(AngleEditorCloseDecision.Exit, decision(freshBaseline, current))
        // Sin línea base conocida tampoco hay nada que perder: no hay ángulos ni puntos parciales.
        assertEquals(AngleEditorCloseDecision.Exit, decision(null, current))
        // Empezar otro borrador sí exige confirmar: lo cancelado no era contenido persistido.
        assertTrue(decision(freshBaseline, angleEditorContent(draftState())) is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `un ancla sola no ensucia el cierre ni relaja las bajas`() {
        val anchorOnly = AngleEditorContent(anchor = anchor)

        // La política normaliza también los contenidos que no vienen de la proyección.
        assertEquals(AngleEditorCloseDecision.Exit, decision(AngleEditorContent(), anchorOnly))
        assertEquals(AngleEditorCloseDecision.Exit, decision(null, anchorOnly))

        // Contrapartida: con ángulos en la línea base, quedarse sin ellos sigue exigiendo confirmar
        // aunque el estado vivo conserve su ancla.
        val saved = angleEditorContent(state())
        assertNotEquals(saved, anchorOnly)
        assertTrue(decision(saved, anchorOnly) is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `un borrador parcial siempre exige confirmar aunque no haya fotogramas`() {
        val draft = draftState()
        val current = angleEditorContent(draft)

        assertEquals(2, current.draftPoints)
        assertEquals("el borrador no forma parte de lo persistido", 0, angleEditorBaselineContent(draft).draftPoints)
        assertNotEquals(angleEditorBaselineContent(draft), current)

        val decision = decision(angleEditorBaselineContent(draft), current)
        assertTrue(decision is AngleEditorCloseDecision.Confirm)
        assertEquals(
            AngleEditorDiscardContent(liveUnsavedChanges = true, frozenFrameCount = 0),
            (decision as AngleEditorCloseDecision.Confirm).content,
        )
    }

    @Test
    fun `una linea base desconocida exige confirmar en cuanto hay contenido vivo`() {
        assertEquals(AngleEditorCloseDecision.Exit, decision(null, AngleEditorContent()))

        val withAngle = decision(null, angleEditorContent(state()))
        val withDraft = decision(null, angleEditorContent(draftState()))

        assertTrue(withAngle is AngleEditorCloseDecision.Confirm)
        assertTrue(withDraft is AngleEditorCloseDecision.Confirm)
    }

    @Test
    fun `solo fotogramas congelados exige confirmar sin afirmar cambios vivos`() {
        val decision = decision(AngleEditorContent(), AngleEditorContent(), frozenFrameCount = 2)

        assertEquals(
            AngleEditorCloseDecision.Confirm(
                AngleEditorDiscardContent(liveUnsavedChanges = false, frozenFrameCount = 2),
            ),
            decision,
        )
    }

    @Test
    fun `con contenido vivo y fotogramas el dialogo informa de todo lo que se pierde`() {
        val decision = decision(null, angleEditorContent(draftState()), frozenFrameCount = 3)

        assertEquals(
            AngleEditorCloseDecision.Confirm(
                AngleEditorDiscardContent(liveUnsavedChanges = true, frozenFrameCount = 3),
            ),
            decision,
        )
    }

    @Test
    fun `una escritura en vuelo bloquea la salida incluso sin contenido`() {
        assertEquals(
            AngleEditorCloseDecision.BlockedBySave,
            decision(AngleEditorContent(), AngleEditorContent(), saveInFlight = true),
        )
        assertEquals(
            AngleEditorCloseDecision.BlockedBySave,
            decision(null, angleEditorContent(draftState()), frozenFrameCount = 1, saveInFlight = true),
        )
    }

    @Test
    fun `un contador de fotogramas negativo no inventa una perdida`() {
        assertEquals(AngleEditorCloseDecision.Exit, decision(AngleEditorContent(), AngleEditorContent(), frozenFrameCount = -1))
    }

    private companion object {
        const val SOURCE = "content://openjump/angle-video"
        const val COLOR_ARGB = 0xFF35D0FF.toInt()
    }
}
