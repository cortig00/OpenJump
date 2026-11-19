package com.openjump.app.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM (sin emulador, sin Room, sin reproductor) de [AngleEditorState].
 *
 * Cubre el contrato puro del editor: ancla fijada por el primer punto y única, confirmación de
 * frame en pausa con fuente/generación vigentes, borrador A→B→C con creación inmediata al completar
 * una terna válida, varios ángulos independientes sin límite funcional, presentación
 * (nombre/color/visibilidad) y selección, borrado/cancelación, arrastre de puntos existentes,
 * motivos tipados para ternas degeneradas y puntos no finitos, y ausencia de reglas de zoom/límites
 * de frame (que pertenecen a la capa de presentación).
 */
class AngleEditorStateTest {

    private val source = "video:content://openjump/jump-1"
    private val anchor = AngleFrameAnchor(source, frameIndex = 42, sourcePtsUs = 1_000_000L)
    private val otherFrame = AngleFrameAnchor(source, frameIndex = 43, sourcePtsUs = 1_033_333L)
    private val defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFF3366CC.toInt())

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun confirm(
        frame: AngleFrameAnchor = anchor,
        generation: Long = GENERATION,
        paused: Boolean = true,
    ) = AngleFrameConfirmation(frame = frame, generation = generation, paused = paused)

    private fun editor(): AngleEditorState = AngleEditorState.initial(source, GENERATION, defaults)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun rejected(outcome: AngleEditorOutcome): AngleEditorRejection =
        (outcome as? AngleEditorOutcome.Rejected)?.reason
            ?: throw AssertionError("Esperaba Rejected, llegó Applied")

    private fun completeAngle(
        state: AngleEditorState,
        a: AnglePoint = p(100.0, 100.0),
        b: AnglePoint = p(200.0, 100.0),
        c: AnglePoint = p(200.0, 200.0),
    ): AngleEditorState {
        val afterA = applied(state.placePoint(a, confirm()))
        val afterB = applied(afterA.placePoint(b, confirm()))
        return applied(afterB.placePoint(c, confirm()))
    }

    // ---------- Flujo normal A → B → C ----------

    @Test
    fun `el primer punto fija el ancla y el tercero crea el angulo de inmediato`() {
        val initial = editor()
        assertNull(initial.anchor)
        assertTrue(initial.angles.isEmpty())
        assertTrue(initial.draft.points.isEmpty())
        assertNull(initial.selectedAngleId)

        val a = p(100.0, 100.0)
        val b = p(200.0, 100.0)
        val c = p(200.0, 200.0)

        val afterA = applied(initial.placePoint(a, confirm()))
        assertEquals(anchor, afterA.anchor)
        assertEquals(listOf(a), afterA.draft.points)
        assertTrue(afterA.angles.isEmpty())

        val afterB = applied(afterA.placePoint(b, confirm()))
        assertEquals(anchor, afterB.anchor)
        assertEquals(listOf(a, b), afterB.draft.points)
        assertTrue(afterB.angles.isEmpty())

        val afterC = applied(afterB.placePoint(c, confirm()))
        assertEquals(anchor, afterC.anchor)
        assertTrue(afterC.draft.points.isEmpty())
        // Sin modo colocación, completar el ángulo lo deja seleccionado (minimiza pasos).
        assertEquals(AngleId(1L), afterC.selectedAngleId)

        val angle = afterC.angles.single()
        assertEquals(AngleId(1L), angle.id)
        assertEquals("Ángulo 1", angle.name)
        assertEquals(defaults.colorArgb, angle.colorArgb)
        assertTrue(angle.visible)
        assertEquals(AngleMarkers(a, b, c), angle.markers)
        assertEquals(90.0, measuredDegrees(angle), 1e-9)
    }

    @Test
    fun `el primer punto puede fijar el ancla en cualquier frame confirmado`() {
        val otherSourceFrame = AngleFrameAnchor(source, frameIndex = 7, sourcePtsUs = 233_333L)
        val state = applied(editor().placePoint(p(10.0, 10.0), confirm(frame = otherSourceFrame)))
        assertEquals(otherSourceFrame, state.anchor)
        assertTrue(state.showsGeometryAt(otherSourceFrame))
        assertFalse(state.showsGeometryAt(anchor))
    }

    @Test
    fun `al completar el angulo queda seleccionado y selectOnComplete false lo evita`() {
        val done = completeAngle(editor())
        assertEquals(AngleId(1L), done.selectedAngleId)

        val placement = applied(editor().placePoint(p(100.0, 100.0), confirm()))
            .let { applied(it.placePoint(p(200.0, 100.0), confirm())) }
            .let { applied(it.placePoint(p(200.0, 200.0), confirm(), selectOnComplete = false)) }
        assertNull(placement.selectedAngleId)
        assertEquals(AngleId(1L), placement.angles.single().id)

        // Al completar un segundo ángulo en modo edición, la selección pasa al nuevo.
        val second = applied(done.placePoint(p(300.0, 300.0), confirm()))
            .let { applied(it.placePoint(p(400.0, 300.0), confirm())) }
            .let { applied(it.placePoint(p(400.0, 400.0), confirm())) }
        assertEquals(AngleId(2L), second.selectedAngleId)
        assertEquals(listOf(AngleId(1L), AngleId(2L)), second.angles.map { it.id })
    }

    @Test
    fun `un C a menos de 24dp de A completa el angulo sin rechazo`() {
        val a = p(100.0, 100.0)
        val state = completeAngle(
            editor(),
            a = a,
            b = p(200.0, 100.0),
            // 3px de separación: muy por debajo del radio táctil de 24dp, pero A != C.
            c = p(103.0, 100.0),
        )
        val angle = state.angles.single()
        assertEquals(AngleMarkers(a, p(200.0, 100.0), p(103.0, 100.0)), angle.markers)
        // A y C están del mismo lado de B: 0° medibles, pero la terna es válida.
        assertEquals(0.0, measuredDegrees(angle), 1e-9)
        assertEquals(AngleId(1L), state.selectedAngleId)
    }

    // ---------- Varios ángulos independientes ----------

    @Test
    fun `varios angulos son independientes y conservan ids estables`() {
        val one = completeAngle(editor())
        val two = completeAngle(
            one,
            a = p(300.0, 300.0),
            b = p(400.0, 300.0),
            c = p(400.0, 400.0),
        )
        val three = completeAngle(
            two,
            a = p(500.0, 500.0),
            b = p(600.0, 500.0),
            c = p(600.0, 400.0),
        )

        assertEquals(listOf(AngleId(1L), AngleId(2L), AngleId(3L)), three.angles.map { it.id })
        assertEquals(listOf("Ángulo 1", "Ángulo 2", "Ángulo 3"), three.angles.map { it.name })
        assertEquals(3, three.angles.size)

        val movedSecond = applied(
            three.moveMarker(AngleId(2L), AngleMarker.C, p(450.0, 350.0), confirm()),
        )
        assertEquals(three.angles[0], movedSecond.angles[0])
        assertEquals(three.angles[2], movedSecond.angles[2])
        assertEquals(AngleId(2L), movedSecond.angles[1].id)
        assertNotEquals(three.angles[1].markers, movedSecond.angles[1].markers)

        val renamedSecond = applied(movedSecond.rename(AngleId(2L), "Rodilla"))
        assertEquals("Rodilla", renamedSecond.angles[1].name)
        assertEquals("Ángulo 1", renamedSecond.angles[0].name)
        assertEquals("Ángulo 3", renamedSecond.angles[2].name)
        assertEquals(three.angles[0], renamedSecond.angles[0])
        assertEquals(three.angles[2], renamedSecond.angles[2])
    }

    @Test
    fun `no hay limite funcional de angulos`() {
        var state = editor()
        repeat(50) { index ->
            val offset = index.toDouble()
            state = completeAngle(
                state,
                a = p(100.0 + offset, 100.0),
                b = p(200.0 + offset, 100.0),
                c = p(200.0 + offset, 200.0),
            )
        }
        assertEquals(50, state.angles.size)
        assertEquals((1L..50L).map(::AngleId), state.angles.map { it.id })
        assertEquals("Ángulo 50", state.angles.last().name)
    }

    // ---------- Borrador parcial y arrastre ----------

    @Test
    fun `se puede colocar parcialmente y arrastrar marcas del borrador`() {
        val a = p(100.0, 100.0)
        val afterA = applied(editor().placePoint(a, confirm()))

        val movedA = applied(afterA.moveDraftMarker(AngleMarker.A, p(120.0, 130.0), confirm()))
        assertEquals(listOf(p(120.0, 130.0)), movedA.draft.points)

        val afterB = applied(movedA.placePoint(p(200.0, 100.0), confirm()))
        val movedB = applied(afterB.moveDraftMarker(AngleMarker.B, p(220.0, 110.0), confirm()))
        assertEquals(listOf(p(120.0, 130.0), p(220.0, 110.0)), movedB.draft.points)

        val done = applied(movedB.placePoint(p(200.0, 200.0), confirm()))
        assertTrue(done.draft.points.isEmpty())
        assertEquals(
            AngleMarkers(p(120.0, 130.0), p(220.0, 110.0), p(200.0, 200.0)),
            done.angles.single().markers,
        )
    }

    @Test
    fun `no se puede arrastrar una marca del borrador que no se ha colocado`() {
        val initial = editor()
        assertEquals(
            AngleEditorRejection.DraftMarkerNotPlaced(AngleMarker.A),
            rejected(initial.moveDraftMarker(AngleMarker.A, p(1.0, 1.0), confirm())),
        )

        val afterA = applied(initial.placePoint(p(100.0, 100.0), confirm()))
        assertEquals(
            AngleEditorRejection.DraftMarkerNotPlaced(AngleMarker.B),
            rejected(afterA.moveDraftMarker(AngleMarker.B, p(1.0, 1.0), confirm())),
        )
        assertEquals(
            AngleEditorRejection.DraftMarkerNotPlaced(AngleMarker.C),
            rejected(afterA.moveDraftMarker(AngleMarker.C, p(1.0, 1.0), confirm())),
        )

        val done = completeAngle(editor())
        assertEquals(
            AngleEditorRejection.DraftMarkerNotPlaced(AngleMarker.A),
            rejected(done.moveDraftMarker(AngleMarker.A, p(1.0, 1.0), confirm())),
        )
    }

    @Test
    fun `un punto no finito no entra ni en el borrador ni al completar`() {
        val initial = editor()
        assertEquals(
            AngleEditorRejection.NonFinitePoint,
            rejected(initial.placePoint(p(Double.NaN, 10.0), confirm())),
        )
        assertNull(initial.anchor)
        assertTrue(initial.draft.points.isEmpty())

        val afterA = applied(initial.placePoint(p(100.0, 100.0), confirm()))
        assertEquals(
            AngleEditorRejection.NonFinitePoint,
            rejected(afterA.placePoint(p(10.0, Double.POSITIVE_INFINITY), confirm())),
        )
        assertEquals(1, afterA.draft.points.size)
    }

    // ---------- Ternas degeneradas y geometría ----------

    @Test
    fun `una terna degenerada rechaza el tercer punto sin crear nada`() {
        val a = p(100.0, 100.0)
        val afterA = applied(editor().placePoint(a, confirm()))
        val afterB = applied(afterA.placePoint(a, confirm()))
        assertEquals(listOf(a, a), afterB.draft.points)
        assertTrue(afterB.angles.isEmpty())

        assertEquals(
            AngleEditorRejection.InvalidGeometry(AngleInvalidReason.COINCIDENT_VERTEX),
            rejected(afterB.placePoint(p(200.0, 200.0), confirm())),
        )
    }

    @Test
    fun `un C igual a B tambien es una terna degenerada`() {
        val b = p(200.0, 100.0)
        val afterA = applied(editor().placePoint(p(100.0, 100.0), confirm()))
        val afterB = applied(afterA.placePoint(b, confirm()))
        assertEquals(
            AngleEditorRejection.InvalidGeometry(AngleInvalidReason.COINCIDENT_VERTEX),
            rejected(afterB.placePoint(b, confirm())),
        )
    }

    @Test
    fun `A igual a C es un cero valido y la terna colineal mide 180`() {
        val a = p(100.0, 100.0)
        val zero = completeAngle(editor(), a = a, b = p(200.0, 200.0), c = a)
        assertEquals(0.0, measuredDegrees(zero.angles.single()), 1e-9)

        val flat = completeAngle(
            editor(),
            a = p(100.0, 100.0),
            b = p(50.0, 100.0),
            c = p(0.0, 100.0),
        )
        assertEquals(180.0, measuredDegrees(flat.angles.single()), 1e-9)
    }

    @Test
    fun `arrastrar una marca conserva la presentacion y rechaza geometria invalida`() {
        val state = completeAngle(editor())
        val original = state.angles.single()

        val moved = applied(
            state.moveMarker(AngleId(1L), AngleMarker.B, p(210.0, 90.0), confirm()),
        )
        val angle = moved.angles.single()
        assertEquals(original.id, angle.id)
        assertEquals(original.name, angle.name)
        assertEquals(original.colorArgb, angle.colorArgb)
        assertEquals(original.visible, angle.visible)
        assertEquals(AngleMarkers(p(100.0, 100.0), p(210.0, 90.0), p(200.0, 200.0)), angle.markers)

        assertEquals(
            AngleEditorRejection.InvalidGeometry(AngleInvalidReason.COINCIDENT_VERTEX),
            rejected(moved.moveMarker(AngleId(1L), AngleMarker.A, p(210.0, 90.0), confirm())),
        )
        assertEquals(angle, moved.angles.single())

        assertEquals(
            AngleEditorRejection.NonFinitePoint,
            rejected(moved.moveMarker(AngleId(1L), AngleMarker.C, p(1.0, Double.NaN), confirm())),
        )
        assertEquals(angle, moved.angles.single())

        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(9L)),
            rejected(moved.moveMarker(AngleId(9L), AngleMarker.A, p(1.0, 1.0), confirm())),
        )
    }

    // ---------- Confirmación de frame ----------

    @Test
    fun `editar exige frame pausado de la fuente y generacion vigentes`() {
        val anchored = applied(editor().placePoint(p(100.0, 100.0), confirm()))

        val foreign = AngleFrameAnchor("video:content://openjump/otro", 42, 1_000_000L)
        assertEquals(
            AngleEditorRejection.ForeignSource(
                expectedSourceIdentity = source,
                receivedSourceIdentity = foreign.sourceIdentity,
            ),
            rejected(anchored.placePoint(p(200.0, 100.0), confirm(frame = foreign))),
        )

        assertEquals(
            AngleEditorRejection.StaleGeneration(
                expectedGeneration = GENERATION,
                receivedGeneration = GENERATION + 1L,
            ),
            rejected(
                anchored.placePoint(
                    p(200.0, 100.0),
                    confirm(generation = GENERATION + 1L),
                ),
            ),
        )

        assertEquals(
            AngleEditorRejection.FrameNotPaused,
            rejected(anchored.placePoint(p(200.0, 100.0), confirm(paused = false))),
        )

        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = otherFrame),
            rejected(anchored.placePoint(p(200.0, 100.0), confirm(frame = otherFrame))),
        )
    }

    @Test
    fun `un supuesto frame falso no puede editar`() {
        val anchored = applied(editor().placePoint(p(100.0, 100.0), confirm()))
        val sameIndexOtherPts = AngleFrameAnchor(source, frameIndex = 42, sourcePtsUs = 1_000_001L)
        val samePtsOtherIndex = AngleFrameAnchor(source, frameIndex = 43, sourcePtsUs = 1_000_000L)

        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = sameIndexOtherPts),
            rejected(anchored.placePoint(p(200.0, 100.0), confirm(frame = sameIndexOtherPts))),
        )
        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = samePtsOtherIndex),
            rejected(anchored.placePoint(p(200.0, 100.0), confirm(frame = samePtsOtherIndex))),
        )
        // El estado original sigue intacto: el ancla no se movió y el borrador tiene solo A.
        assertEquals(anchor, anchored.anchor)
        assertEquals(1, anchored.draft.points.size)
        assertTrue(anchored.angles.isEmpty())
    }

    @Test
    fun `antes del primer punto se rechaza fuente generacion y pausa pero no el frame`() {
        val initial = editor()
        val foreign = AngleFrameAnchor("video:content://openjump/otro", 0, 0L)
        assertEquals(
            AngleEditorRejection.ForeignSource(source, foreign.sourceIdentity),
            rejected(initial.placePoint(p(1.0, 1.0), confirm(frame = foreign))),
        )
        assertEquals(
            AngleEditorRejection.StaleGeneration(GENERATION, GENERATION - 1L),
            rejected(initial.placePoint(p(1.0, 1.0), confirm(generation = GENERATION - 1L))),
        )
        assertEquals(
            AngleEditorRejection.FrameNotPaused,
            rejected(initial.placePoint(p(1.0, 1.0), confirm(paused = false))),
        )
        assertNull(initial.anchor)

        // Cualquier frame confirmado puede convertirse en el ancla del análisis.
        assertEquals(otherFrame, applied(initial.placePoint(p(1.0, 1.0), confirm(frame = otherFrame))).anchor)
    }

    @Test
    fun `la prioridad del rechazo es fuente generacion pausa ancla`() {
        val anchored = applied(editor().placePoint(p(100.0, 100.0), confirm()))
        val foreign = AngleFrameAnchor("video:content://openjump/otro", 99, 9L)
        assertEquals(
            AngleEditorRejection.ForeignSource(source, foreign.sourceIdentity),
            rejected(
                anchored.placePoint(
                    p(1.0, 1.0),
                    confirm(
                        frame = foreign,
                        generation = GENERATION + 5L,
                        paused = false,
                    ),
                ),
            ),
        )
        assertEquals(
            AngleEditorRejection.StaleGeneration(GENERATION, GENERATION + 5L),
            rejected(
                anchored.placePoint(
                    p(1.0, 1.0),
                    confirm(
                        frame = otherFrame,
                        generation = GENERATION + 5L,
                        paused = false,
                    ),
                ),
            ),
        )
        assertEquals(
            AngleEditorRejection.FrameNotPaused,
            rejected(anchored.placePoint(p(1.0, 1.0), confirm(frame = otherFrame, paused = false))),
        )
    }

    @Test
    fun `navegar a otro frame oculta geometria sin invalidar y volver al ancla permite editar`() {
        val initial = editor()
        assertFalse(initial.showsGeometryAt(anchor))

        val afterA = applied(initial.placePoint(p(100.0, 100.0), confirm()))
        assertTrue(afterA.showsGeometryAt(anchor))
        assertFalse(afterA.showsGeometryAt(otherFrame))
        assertFalse(afterA.showsGeometryAt(AngleFrameAnchor(source, 42, 1_000_001L)))
        assertFalse(afterA.showsGeometryAt(AngleFrameAnchor("video:content://openjump/otro", 42, 1_000_000L)))

        // Navegar al otro frame no permite editar y no borra el borrador parcial.
        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = otherFrame),
            rejected(afterA.placePoint(p(200.0, 100.0), confirm(frame = otherFrame))),
        )
        assertEquals(1, afterA.draft.points.size)

        // Al volver al ancla, una confirmación en pausa reanuda la edición.
        val afterB = applied(afterA.placePoint(p(200.0, 100.0), confirm()))
        assertEquals(2, afterB.draft.points.size)
        val done = applied(afterB.placePoint(p(200.0, 200.0), confirm()))
        assertEquals(1, done.angles.size)
    }

    @Test
    fun `las acciones de geometria de un angulo existente tambien exigen confirmacion`() {
        val state = completeAngle(editor())
        assertEquals(
            AngleEditorRejection.FrameNotPaused,
            rejected(
                state.moveMarker(AngleId(1L), AngleMarker.A, p(1.0, 1.0), confirm(paused = false)),
            ),
        )
        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = otherFrame),
            rejected(
                state.moveMarker(
                    AngleId(1L),
                    AngleMarker.A,
                    p(1.0, 1.0),
                    confirm(frame = otherFrame),
                ),
            ),
        )
        assertEquals(
            AngleEditorRejection.StaleGeneration(GENERATION, GENERATION + 1L),
            rejected(
                state.moveMarker(
                    AngleId(1L),
                    AngleMarker.A,
                    p(1.0, 1.0),
                    confirm(generation = GENERATION + 1L),
                ),
            ),
        )
        // Sin cambios en las marcas originales.
        assertEquals(
            AngleMarkers(p(100.0, 100.0), p(200.0, 100.0), p(200.0, 200.0)),
            state.angles.single().markers,
        )
    }

    // ---------- Nombre, color, visibilidad y selección ----------

    @Test
    fun `nombre color y visibilidad se editan por id sin tocar las marcas`() {
        val state = completeAngle(editor())
        val id = AngleId(1L)
        val original = state.angles.single()

        val renamed = applied(state.rename(id, "Cadera"))
        assertEquals("Cadera", renamed.angles.single().name)
        assertEquals(id, renamed.angles.single().id)
        assertEquals(original.markers, renamed.angles.single().markers)
        assertEquals(original.colorArgb, renamed.angles.single().colorArgb)
        assertTrue(renamed.angles.single().visible)

        assertEquals(
            AngleEditorRejection.BlankName,
            rejected(renamed.rename(id, "   ")),
        )
        assertEquals(AngleEditorRejection.BlankName, rejected(renamed.rename(id, "")))
        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(99L)),
            rejected(renamed.rename(AngleId(99L), "Otra")),
        )

        val colored = applied(renamed.setColor(id, 0xFF00FF00.toInt()))
        assertEquals(0xFF00FF00.toInt(), colored.angles.single().colorArgb)
        assertEquals(original.markers, colored.angles.single().markers)

        val hidden = applied(colored.setVisible(id, false))
        assertFalse(hidden.angles.single().visible)
        assertEquals(original.markers, hidden.angles.single().markers)
        assertEquals(90.0, measuredDegrees(hidden.angles.single()), 1e-9)
        assertTrue(hidden.showsGeometryAt(anchor))
        assertNull("ocultar el seleccionado limpia la selección", hidden.selectedAngleId)

        // Ocultar no borra geometría: el ajuste por id sigue disponible en esta capa, pero la
        // superposición no expone asas de un ángulo oculto ni permite seleccionarlo.
        val movedHidden = applied(hidden.moveMarker(id, AngleMarker.A, p(90.0, 90.0), confirm()))
        assertEquals(AngleMarkers(p(90.0, 90.0), p(200.0, 100.0), p(200.0, 200.0)), movedHidden.angles.single().markers)
        assertFalse(movedHidden.angles.single().visible)

        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(99L)),
            rejected(hidden.setColor(AngleId(99L), 0)),
        )
        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(99L)),
            rejected(hidden.setVisible(AngleId(99L), true)),
        )
    }

    @Test
    fun `la seleccion es explicita y se conserva al editar nombre color y marcas`() {
        val state = completeAngle(completeAngle(editor()))
        // El último completado queda seleccionado; se limpia para probar la selección explícita.
        assertEquals(AngleId(2L), state.selectedAngleId)
        val cleared = applied(state.select(null))
        assertNull(cleared.selectedAngleId)

        val selected = applied(cleared.select(AngleId(2L)))
        assertEquals(AngleId(2L), selected.selectedAngleId)
        assertEquals(AngleId(2L), applied(selected.rename(AngleId(2L), "Tobillo")).selectedAngleId)
        assertEquals(
            AngleId(2L),
            applied(selected.setColor(AngleId(2L), 0xFF112233.toInt())).selectedAngleId,
        )
        assertEquals(
            AngleId(2L),
            applied(selected.moveMarker(AngleId(2L), AngleMarker.C, p(450.0, 350.0), confirm()))
                .selectedAngleId,
        )

        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(99L)),
            rejected(selected.select(AngleId(99L))),
        )
        assertNull(applied(selected.select(null)).selectedAngleId)
    }

    @Test
    fun `ocultar el angulo seleccionado limpia la seleccion y seleccionar uno oculto se rechaza`() {
        val state = completeAngle(completeAngle(editor()))
        assertEquals(AngleId(2L), state.selectedAngleId)

        val hidden = applied(state.setVisible(AngleId(2L), false))
        assertFalse(hidden.angles[1].visible)
        assertNull("un ángulo oculto no puede seguir seleccionado", hidden.selectedAngleId)
        assertEquals(
            HiddenAngle(AngleId(2L)),
            rejected(hidden.select(AngleId(2L))),
        )

        // Mostrarlo de nuevo permite seleccionarlo; ocultar uno no seleccionado no toca la selección.
        val shown = applied(hidden.setVisible(AngleId(2L), true))
        val reselected = applied(shown.select(AngleId(2L)))
        assertEquals(AngleId(2L), reselected.selectedAngleId)
        val hiddenOther = applied(reselected.setVisible(AngleId(1L), false))
        assertEquals(AngleId(2L), hiddenOther.selectedAngleId)
        assertFalse(hiddenOther.angles[0].visible)
    }

    @Test
    fun `cualquier angulo visible se selecciona por id aunque comparta ubicacion con otro`() {
        val shared = completeAngle(completeAngle(editor()))
        assertEquals(2, shared.angles.size)
        assertEquals(shared.angles[0].markers, shared.angles[1].markers)

        // La lista puede seleccionar el de abajo aunque la superposición solo alcance al de arriba.
        assertEquals(AngleId(1L), applied(shared.select(AngleId(1L))).selectedAngleId)
        assertEquals(AngleId(2L), applied(shared.select(AngleId(2L))).selectedAngleId)
    }

    // ---------- Borrado y cancelación ----------

    @Test
    fun `borrar un angulo limpia su seleccion y conserva la de otro`() {
        val state = completeAngle(completeAngle(editor()))
        val selectedSecond = applied(state.select(AngleId(2L)))

        val withoutFirst = applied(selectedSecond.deleteAngle(AngleId(1L)))
        assertEquals(listOf(AngleId(2L)), withoutFirst.angles.map { it.id })
        assertEquals(AngleId(2L), withoutFirst.selectedAngleId)

        val empty = applied(withoutFirst.deleteAngle(AngleId(2L)))
        assertTrue(empty.angles.isEmpty())
        assertNull(empty.selectedAngleId)
        assertEquals(anchor, empty.anchor)
        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(2L)),
            rejected(empty.deleteAngle(AngleId(2L))),
        )
    }

    @Test
    fun `borrar todos los angulos no libera el ancla ni reutiliza ids ni nombres`() {
        val empty = applied(completeAngle(editor()).deleteAngle(AngleId(1L)))
        assertTrue(empty.angles.isEmpty())
        assertEquals(anchor, empty.anchor)

        assertEquals(
            AngleEditorRejection.OtherFrame(anchor = anchor, rendered = otherFrame),
            rejected(empty.placePoint(p(1.0, 1.0), confirm(frame = otherFrame))),
        )

        val next = completeAngle(empty)
        assertEquals(AngleId(2L), next.angles.single().id)
        assertEquals("Ángulo 2", next.angles.single().name)
    }

    @Test
    fun `cancelar el borrador lo vacia sin tocar angulos ancla ni seleccion`() {
        val withAngle = applied(completeAngle(editor()).select(AngleId(1L)))
        val partial = applied(withAngle.placePoint(p(10.0, 10.0), confirm()))
        assertEquals(1, partial.draft.points.size)

        val canceled = applied(partial.cancelDraft())
        assertTrue(canceled.draft.points.isEmpty())
        assertEquals(1, canceled.angles.size)
        assertEquals(anchor, canceled.anchor)
        assertEquals(AngleId(1L), canceled.selectedAngleId)

        val idempotent = applied(canceled.cancelDraft())
        assertTrue(idempotent.draft.points.isEmpty())

        // Reiniciar el borrador no consume id ni ordinal de nombre.
        val restarted = completeAngle(canceled)
        assertEquals(listOf(AngleId(1L), AngleId(2L)), restarted.angles.map { it.id })
        assertEquals("Ángulo 2", restarted.angles.last().name)
    }

    // ---------- Rehidratación pura (sin Room ni Android) ----------

    private fun persistedAngle(
        id: Long,
        name: String,
        a: AnglePoint = p(100.0, 100.0),
        b: AnglePoint = p(200.0, 100.0),
        c: AnglePoint = p(200.0, 200.0),
        visible: Boolean = true,
    ) = Angle(
        id = AngleId(id),
        name = name,
        colorArgb = 0xFF102030.toInt(),
        visible = visible,
        markers = AngleMarkers(a, b, c),
    )

    private fun rehydrated(
        anchor: AngleFrameAnchor? = this.anchor,
        angles: List<Angle> = emptyList(),
        draft: AngleDraft = AngleDraft(),
        selectedAngleId: AngleId? = null,
        externallyPersisted: Set<AngleId> = emptySet(),
    ): AngleEditorState = AngleEditorState.rehydrate(
        sourceIdentity = source,
        generation = GENERATION,
        anchor = anchor,
        angles = angles,
        defaults = defaults,
        draft = draft,
        selectedAngleId = selectedAngleId,
        externallyPersistedAngleIds = externallyPersisted,
    )

    @Test
    fun `rehidratar conserva ancla ids y orden y el siguiente angulo no colisiona`() {
        val first = persistedAngle(1L, "Ángulo 1")
        val second = persistedAngle(
            2L,
            "Ángulo 2",
            a = p(300.0, 300.0),
            b = p(400.0, 300.0),
            c = p(400.0, 400.0),
        )
        val state = rehydrated(
            angles = listOf(first, second),
            externallyPersisted = setOf(AngleId(1L), AngleId(2L)),
        )

        assertEquals(anchor, state.anchor)
        assertEquals(listOf(first, second), state.angles)
        assertEquals(listOf(AngleId(1L), AngleId(2L)), state.angles.map { it.id })
        assertTrue(state.showsGeometryAt(anchor))
        assertFalse(state.showsGeometryAt(otherFrame))
        assertNull(state.selectedAngleId)
        assertEquals(setOf(AngleId(1L), AngleId(2L)), state.externallyPersistedAngleIds)

        // Añadir un ángulo nuevo usa el siguiente id y ordinal libres: sin colisión con los dos
        // rehidratados, y el nuevo no queda marcado como persistido.
        val extended = completeAngle(state)
        assertEquals(listOf(AngleId(1L), AngleId(2L), AngleId(3L)), extended.angles.map { it.id })
        assertEquals("Ángulo 3", extended.angles.last().name)
        assertEquals(AngleId(3L), extended.selectedAngleId)
        assertEquals(setOf(AngleId(1L), AngleId(2L)), extended.externallyPersistedAngleIds)
    }

    @Test
    fun `rehidratar avanza ids y ordinales aunque no sean contiguos`() {
        val state = rehydrated(
            angles = listOf(
                persistedAngle(5L, "Ángulo 1"),
                persistedAngle(
                    9L,
                    "Ángulo 4",
                    a = p(300.0, 300.0),
                    b = p(400.0, 300.0),
                    c = p(400.0, 400.0),
                ),
            ),
        )
        val extended = completeAngle(state)
        assertEquals(AngleId(10L), extended.angles.last().id)
        // "Ángulo 1" y "Ángulo 4" ya existen: el menor ordinal libre es 2.
        assertEquals("Ángulo 2", extended.angles.last().name)
        assertEquals(listOf("Ángulo 1", "Ángulo 4", "Ángulo 2"), extended.angles.map { it.name })
    }

    @Test
    fun `rehidratar un estado vacio es valido y sin ancla no admite geometria`() {
        val empty = rehydrated(anchor = null)
        assertNull(empty.anchor)
        assertTrue(empty.angles.isEmpty())
        assertTrue(empty.draft.points.isEmpty())
        assertEquals(AngleId(1L), completeAngle(empty).angles.single().id)

        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(anchor = null, angles = listOf(persistedAngle(1L, "Ángulo 1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(anchor = null, draft = AngleDraft(listOf(p(1.0, 1.0))))
        }
    }

    @Test
    fun `rehidratar valida duplicados fuente ancla seleccion y agotamiento de ids`() {
        // Ids locales duplicados: rompería la identidad estable que usa la UI.
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(angles = listOf(persistedAngle(1L, "A"), persistedAngle(1L, "B")))
        }
        // Ancla de otra fuente.
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(
                anchor = AngleFrameAnchor("video:content://openjump/otro", 1, 1L),
                angles = emptyList(),
            )
        }
        // Selección inexistente.
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(
                angles = listOf(persistedAngle(1L, "Ángulo 1")),
                selectedAngleId = AngleId(2L),
            )
        }
        // Marcados como persistidos que no están en la lista.
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(externallyPersisted = setOf(AngleId(1L)))
        }
        // Espacio de ids agotado: no se puede avanzar sin desbordar.
        assertThrows(IllegalArgumentException::class.java) {
            rehydrated(angles = listOf(persistedAngle(Long.MAX_VALUE, "Ángulo 1")))
        }
    }

    @Test
    fun `rehidratar normaliza la seleccion de un oculto y conserva borrador con ancla`() {
        val state = rehydrated(
            angles = listOf(
                persistedAngle(1L, "Ángulo 1"),
                persistedAngle(
                    2L,
                    "Ángulo 2",
                    a = p(300.0, 300.0),
                    b = p(400.0, 300.0),
                    c = p(400.0, 400.0),
                    visible = false,
                ),
            ),
            draft = AngleDraft(listOf(p(10.0, 10.0))),
            selectedAngleId = AngleId(2L),
        )
        assertNull("seleccionar un oculto no es admisible", state.selectedAngleId)
        assertEquals(listOf(p(10.0, 10.0)), state.draft.points)
        assertEquals(anchor, state.anchor)
        assertEquals(HiddenAngle(AngleId(2L)), rejected(state.select(AngleId(2L))))
        assertEquals(AngleId(1L), applied(state.select(AngleId(1L))).selectedAngleId)
    }

    @Test
    fun `marcar persistido externamente es idempotente y se limpia al borrar`() {
        val state = completeAngle(editor())
        assertTrue(state.externallyPersistedAngleIds.isEmpty())

        val marked = applied(state.markExternallyPersisted(AngleId(1L)))
        assertEquals(setOf(AngleId(1L)), marked.externallyPersistedAngleIds)
        assertEquals(
            setOf(AngleId(1L)),
            applied(marked.markExternallyPersisted(AngleId(1L))).externallyPersistedAngleIds,
        )
        assertEquals(
            AngleEditorRejection.UnknownAngle(AngleId(9L)),
            rejected(marked.markExternallyPersisted(AngleId(9L))),
        )
        assertTrue(applied(marked.deleteAngle(AngleId(1L))).externallyPersistedAngleIds.isEmpty())
    }

    // ---------- Fuera de alcance: zoom, límites y seguridad ----------

    @Test
    fun `el estado no valida limites de frame ni zoom solo finitud`() {
        // Coordenadas de presentación arbitrarias (p. ej. una imagen ampliada) fuera de 0..w/h.
        val state = completeAngle(
            editor(),
            a = p(-1_000.0, -1_000.0),
            b = p(0.0, -1_000.0),
            c = p(0.0, 0.0),
        )
        assertEquals(90.0, measuredDegrees(state.angles.single()), 1e-9)
        assertTrue(
            rejected(
                editor().placePoint(
                    p(Double.NEGATIVE_INFINITY, 0.0),
                    confirm(),
                ),
            ) is AngleEditorRejection.NonFinitePoint,
        )
    }

    // ---------- Invariantes de los tipos ----------

    @Test
    fun `los tipos rechazan identidades anclas y borradores invalidos`() {
        assertThrows(IllegalArgumentException::class.java) {
            AngleEditorState.initial(" ", GENERATION, defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AngleFrameAnchor("", frameIndex = 0, sourcePtsUs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AngleFrameAnchor(source, frameIndex = -1, sourcePtsUs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AngleFrameAnchor(source, frameIndex = 0, sourcePtsUs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) { AngleId(0L) }
        assertThrows(IllegalArgumentException::class.java) {
            AngleDraft(listOf(p(1.0, 1.0), p(2.0, 2.0), p(3.0, 3.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AngleDraft(listOf(p(Double.NaN, 1.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AngleEditorDefaults(namePrefix = " ", colorArgb = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Angle(
                id = AngleId(1L),
                name = " ",
                colorArgb = 0,
                visible = true,
                markers = AngleMarkers(p(1.0, 1.0), p(2.0, 2.0), p(3.0, 3.0)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Angle(
                id = AngleId(1L),
                name = "Válido",
                colorArgb = 0,
                visible = true,
                markers = AngleMarkers(p(1.0, 1.0), p(1.0, 1.0), p(3.0, 3.0)),
            )
        }
    }

    @Test
    fun `la generacion y la fuente quedan fijadas al crear el estado`() {
        val state = editor()
        assertEquals(GENERATION, state.generation)
        assertEquals(source, state.sourceIdentity)
        assertNull(state.anchor)
    }

    // ---------- Helpers ----------

    private fun measuredDegrees(angle: Angle): Double {
        val measurement = angle.measurement
        assertTrue("Esperaba una medición válida, fue $measurement", measurement is AngleMeasurement.Measured)
        return (measurement as AngleMeasurement.Measured).degrees
    }

    private companion object {
        const val GENERATION = 7L
    }
}
