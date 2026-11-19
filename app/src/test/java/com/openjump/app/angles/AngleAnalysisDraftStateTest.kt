package com.openjump.app.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM (sin emulador, sin Room, sin reproductor, sin ficheros y sin UI) de
 * [AngleAnalysisDraftState].
 *
 * Cubre el contrato puro de la sesión multi-fotograma: una sola fuente y generación por análisis,
 * N fotogramas con identidad estable e independiente del orden, un [AngleEditorState] por fotograma
 * con sus borradores A/B, selecciones y ángulos preservados al cambiar/reordenar/eliminar, imágenes
 * congeladas como referencias opacas ligadas a su ancla, adopción de imagen antes de marcar,
 * operaciones asíncronas acotadas por fuente/generación/fotograma/revisión, confirmaciones
 * explícitas para acciones destructivas y la consulta pura de finalización (sin guardado).
 *
 * No prueba píxeles, PTS reales, autenticación de captura, persistencia ni limpieza: eso pertenece al
 * adaptador de captura y a las capas de datos/UI, fuera de este slice.
 */
class AngleAnalysisDraftStateTest {

    private val source = "video:content://openjump/jump-7"
    private val otherSource = "video:content://openjump/jump-8"
    private val generation = 900L
    private val defaults = AngleEditorDefaults(
        namePrefix = "Ángulo",
        colorArgb = 0xFF3366CC.toInt(),
    )

    private fun frameId(value: Long) = AngleFrameId(value)

    private fun anchor(index: Int, pts: Long = index.toLong() * 33_333L) =
        AngleFrameAnchor(source, frameIndex = index, sourcePtsUs = pts)

    private fun image(
        anchor: AngleFrameAnchor,
        token: String = "img-${anchor.frameIndex}",
    ) = AngleFrozenImage(token = token, anchor = anchor)

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun confirmation(frame: AngleFrameAnchor) =
        AngleFrameConfirmation(frame = frame, generation = generation, paused = true)

    private fun state(): AngleAnalysisDraftState =
        AngleAnalysisDraftState.initial(source, generation, defaults)

    private fun applied(outcome: AngleAnalysisDraftOutcome): AngleAnalysisDraftState =
        (outcome as? AngleAnalysisDraftOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun rejected(outcome: AngleAnalysisDraftOutcome): AngleAnalysisDraftRejection =
        (outcome as? AngleAnalysisDraftOutcome.Rejected)?.reason
            ?: throw AssertionError("Esperaba Rejected, llegó Applied")

    private fun withFrame(
        state: AngleAnalysisDraftState,
        frameId: AngleFrameId,
        anchor: AngleFrameAnchor,
    ): AngleAnalysisDraftState = applied(state.addFrame(frameId, anchor))

    private fun withImage(
        state: AngleAnalysisDraftState,
        frameId: AngleFrameId,
        image: AngleFrozenImage,
    ): AngleAnalysisDraftState = applied(
        state.adoptFrozenImage(requireNotNull(state.expectationFor(frameId)), image),
    )

    private fun editorOf(state: AngleAnalysisDraftState, frameId: AngleFrameId): AngleEditorState =
        requireNotNull(state.frame(frameId)).editor

    /**
     * Coloca puntos en un fotograma aplicando el resultado del editor con la expectativa vigente,
     * igual que haría el ViewModel: el estado se reconstruye tras cada punto.
     */
    private fun place(
        state: AngleAnalysisDraftState,
        frameId: AngleFrameId,
        vararg points: AnglePoint,
    ): AngleAnalysisDraftState {
        var current = state
        points.forEach { point ->
            val frame = requireNotNull(current.frame(frameId))
            val editorOutcome = frame.editor.placePoint(point, confirmation(frame.anchor))
            current = applied(
                current.applyEditorOutcome(
                    requireNotNull(current.expectationFor(frameId)),
                    editorOutcome,
                ),
            )
        }
        return current
    }

    private fun completeAngle(
        state: AngleAnalysisDraftState,
        frameId: AngleFrameId,
    ): AngleAnalysisDraftState = place(
        state,
        frameId,
        p(100.0, 100.0),
        p(200.0, 100.0),
        p(200.0, 200.0),
    )

    // ---------- 1 fotograma / estado inicial ----------

    @Test
    fun `un analisis empieza sin fotogramas y bloquea la finalizacion`() {
        val state = state()

        assertEquals(source, state.sourceIdentity)
        assertEquals(generation, state.generation)
        assertEquals(0, state.frameCount)
        assertNull(state.activeFrameId)
        assertNull(state.activeFrame())
        assertFalse(state.hasUnsavedData)
        assertNull(state.expectationFor(frameId(1)))
        assertEquals(
            AngleAnalysisFinalization.Blocked(
                blockers = listOf(AngleFinalizationBlocker.NoFrames),
            ),
            state.finalization(),
        )
        assertEquals(
            AngleAnalysisDraftRejection.UnknownFrame(frameId(1)),
            rejected(state.selectFrame(frameId(1))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AngleAnalysisDraftState.initial("   ", generation, defaults)
        }
        assertThrows(IllegalArgumentException::class.java) { AngleFrameId(0L) }
    }

    @Test
    fun `un unico fotograma con imagen y un angulo completo esta listo para finalizar`() {
        val id = frameId(1)
        val frameAnchor = anchor(42)
        var state = withFrame(state(), id, frameAnchor)
        state = withImage(state, id, image(frameAnchor))
        state = completeAngle(state, id)

        val frame = requireNotNull(state.frame(id))
        assertEquals(1, state.frameCount)
        assertEquals(1, frame.editor.angles.size)
        assertEquals(frameAnchor, frame.editor.anchor)
        assertTrue(frame.editor.draft.points.isEmpty())
        assertTrue(state.hasUnsavedData)
        assertTrue(state.requiresDiscardConfirmation(id))

        val ready = state.finalization() as? AngleAnalysisFinalization.Ready
            ?: throw AssertionError("Esperaba Ready, llegó ${state.finalization()}")
        assertEquals(source, ready.sourceIdentity)
        assertEquals(generation, ready.generation)
        val only = ready.frames.single()
        assertEquals(id, only.id)
        assertEquals(frameAnchor, only.anchor)
        assertEquals(frame.image, only.image)
        assertEquals(frame.editor.angles, only.angles)
        assertEquals(1, only.angles.size)
    }

    // ---------- N fotogramas: cambio, retorno y reordenación ----------

    @Test
    fun `cambiar de fotograma y volver conserva borradores A B y angulos`() {
        val first = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), first, anchor(10))
        state = withImage(state, first, image(anchor(10)))
        state = withFrame(state, second, anchor(20))
        state = withImage(state, second, image(anchor(20)))

        state = place(state, first, p(10.0, 10.0), p(20.0, 10.0))
        state = completeAngle(state, second)
        val firstDraft = editorOf(state, first).draft
        val secondAngles = editorOf(state, second).angles
        assertEquals(2, firstDraft.points.size)
        assertEquals(1, secondAngles.size)

        state = applied(state.selectFrame(second))
        assertEquals(second, state.activeFrameId)
        assertEquals(second, requireNotNull(state.activeFrame()).id)
        state = applied(state.selectFrame(first))
        assertEquals(first, state.activeFrameId)

        assertEquals(firstDraft, editorOf(state, first).draft)
        assertEquals(secondAngles, editorOf(state, second).angles)
        assertTrue(editorOf(state, first).angles.isEmpty())
        assertEquals(
            AngleAnalysisFinalization.Blocked(
                blockers = listOf(
                    AngleFinalizationBlocker.PendingDraft(frameId = first, placedPoints = 2),
                    AngleFinalizationBlocker.NoCompleteAngle(first),
                ),
            ),
            state.finalization(),
        )

        // Agregar un tercer fotograma no altera la selección ni los borradores existentes.
        state = withFrame(state, frameId(3), anchor(30))
        assertEquals(first, state.activeFrameId)
        assertEquals(firstDraft, editorOf(state, first).draft)
    }

    @Test
    fun `reordenar conserva identidades, borradores, seleccion y expectativas`() {
        val a = frameId(1)
        val b = frameId(2)
        val c = frameId(3)
        var state = withFrame(state(), a, anchor(10))
        state = withFrame(state, b, anchor(20))
        state = withFrame(state, c, anchor(30))
        state = withImage(state, a, image(anchor(10)))
        state = withImage(state, b, image(anchor(20)))
        state = withImage(state, c, image(anchor(30)))
        state = place(state, a, p(10.0, 10.0))
        state = applied(state.selectFrame(b))

        val before = state.frames.associateBy { it.id }
        val expectationBefore = requireNotNull(state.expectationFor(a))

        state = applied(state.moveFrame(c, 0))
        assertEquals(listOf(c, a, b), state.frames.map { it.id })
        assertEquals(b, state.activeFrameId)
        before.forEach { (id, frame) -> assertEquals(frame, state.frame(id)) }

        assertEquals(
            AngleAnalysisDraftRejection.InvalidFramePosition(requested = -1, frameCount = 3),
            rejected(state.moveFrame(a, -1)),
        )
        assertEquals(
            AngleAnalysisDraftRejection.InvalidFramePosition(requested = 3, frameCount = 3),
            rejected(state.moveFrame(a, 3)),
        )
        assertEquals(
            AngleAnalysisDraftRejection.UnknownFrame(frameId(99)),
            rejected(state.moveFrame(frameId(99), 0)),
        )
        assertSame(state, applied(state.moveFrame(b, 2)))

        // Reordenar no invalida una respuesta pendiente: la revisión del fotograma no cambia.
        val editor = editorOf(state, a)
        val secondPoint = editor.placePoint(p(20.0, 10.0), confirmation(anchor(10)))
        state = applied(state.applyEditorOutcome(expectationBefore, secondPoint))
        assertEquals(2, editorOf(state, a).draft.points.size)
        assertEquals(before.getValue(a).revision + 1, requireNotNull(state.frame(a)).revision)
    }

    // ---------- Fuente, ids y anclas ----------

    @Test
    fun `no se puede agregar un fotograma de otra fuente`() {
        val initial = state()
        val foreign = AngleFrameAnchor(otherSource, frameIndex = 5, sourcePtsUs = 5_000L)

        assertEquals(
            AngleAnalysisDraftRejection.ForeignSource(
                expectedSourceIdentity = source,
                receivedSourceIdentity = otherSource,
            ),
            rejected(initial.addFrame(frameId(1), foreign)),
        )
        // El rechazo no dejó rastro: el mismo id se puede registrar con un ancla de la fuente correcta.
        val accepted = applied(initial.addFrame(frameId(1), anchor(5)))
        assertEquals(1, accepted.frameCount)
        assertEquals(anchor(5), requireNotNull(accepted.frame(frameId(1))).anchor)
    }

    @Test
    fun `un id de fotograma no se puede repetir`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(10))

        assertEquals(
            AngleAnalysisDraftRejection.DuplicateFrameId(id),
            rejected(state.addFrame(id, anchor(11))),
        )
        assertEquals(1, state.frameCount)
        // El intento duplicado tampoco registró su ancla.
        state = withFrame(state, frameId(2), anchor(11))
        assertEquals(listOf(id, frameId(2)), state.frames.map { it.id })
    }

    @Test
    fun `un ancla repetida se rechaza indicando el fotograma existente`() {
        val id = frameId(1)
        val repeated = anchor(42)
        val state = withFrame(state(), id, repeated)

        assertEquals(
            AngleAnalysisDraftRejection.DuplicateFrameAnchor(
                existingFrameId = id,
                anchor = repeated,
            ),
            rejected(state.addFrame(frameId(2), repeated)),
        )
        // Mismo frameIndex con otro PTS declarado es otra ancla y se admite.
        val sameIndexOtherPts = applied(state.addFrame(frameId(2), anchor(42, pts = 1_400_000L)))
        assertEquals(2, sameIndexOtherPts.frameCount)
    }

    // ---------- Imagen congelada / adopción ----------

    @Test
    fun `no se puede anclar geometria sin imagen congelada adoptada`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        val edit = editorOf(state, id)
            .placePoint(p(10.0, 10.0), confirmation(anchor(42)))

        assertEquals(
            AngleAnalysisDraftRejection.ImageNotAdopted(id),
            rejected(state.applyEditorOutcome(requireNotNull(state.expectationFor(id)), edit)),
        )
        assertNull(editorOf(state, id).anchor)
        assertTrue(editorOf(state, id).draft.points.isEmpty())

        state = withImage(state, id, image(anchor(42)))
        state = applied(
            state.applyEditorOutcome(requireNotNull(state.expectationFor(id)), edit),
        )
        assertEquals(anchor(42), editorOf(state, id).anchor)
        assertEquals(1, editorOf(state, id).draft.points.size)
    }

    @Test
    fun `una imagen solo se adopta en su propio fotograma y una sola vez`() {
        val first = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), first, anchor(10))
        state = withFrame(state, second, anchor(20))

        assertEquals(
            AngleAnalysisDraftRejection.ImageBoundToOtherFrame(
                expected = anchor(10),
                received = anchor(20),
            ),
            rejected(
                state.adoptFrozenImage(
                    requireNotNull(state.expectationFor(first)),
                    image(anchor(20)),
                ),
            ),
        )

        state = withImage(state, first, image(anchor(10), token = "shared"))
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageAlreadyUsed(
                token = "shared",
                frameId = first,
            ),
            rejected(
                state.adoptFrozenImage(
                    requireNotNull(state.expectationFor(second)),
                    AngleFrozenImage("shared", anchor(20)),
                ),
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.ImageAlreadyAdopted(first),
            rejected(
                state.adoptFrozenImage(
                    requireNotNull(state.expectationFor(first)),
                    AngleFrozenImage("another", anchor(10)),
                ),
            ),
        )
        assertNull(requireNotNull(state.frame(second)).image)
        assertThrows(IllegalArgumentException::class.java) {
            AngleFrozenImage("   ", anchor(10))
        }
    }

    @Test
    fun `reemplazar la imagen exige confirmacion y no admite una imagen ajena`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42), token = "captured"))
        val expectation = requireNotNull(state.expectationFor(id))

        assertEquals(
            AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                AngleDiscardReason.IMAGE_REPLACEMENT,
            ),
            rejected(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("new", anchor(42)),
                    AngleDiscardConfirmation.NOT_CONFIRMED,
                ),
            ),
        )
        assertEquals("captured", requireNotNull(state.frame(id)).image?.token)
        assertEquals(
            AngleAnalysisDraftRejection.ImageBoundToOtherFrame(
                expected = anchor(42),
                received = anchor(43),
            ),
            rejected(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("new", anchor(43)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        val replaced = applied(
            state.replaceFrozenImage(
                expectation,
                AngleFrozenImage("new", anchor(42)),
                AngleDiscardConfirmation.CONFIRMED,
            ),
        )
        assertEquals("new", requireNotNull(replaced.frame(id)).image?.token)
        assertEquals(
            requireNotNull(state.frame(id)).revision + 1,
            requireNotNull(replaced.frame(id)).revision,
        )

        val withoutImage = withFrame(state(), frameId(2), anchor(50))
        assertEquals(
            AngleAnalysisDraftRejection.NoImageToReplace(frameId(2)),
            rejected(
                withoutImage.replaceFrozenImage(
                    requireNotNull(withoutImage.expectationFor(frameId(2))),
                    AngleFrozenImage("x", anchor(50)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
    }

    // ---------- Respuestas tardías, generación y ancla ----------

    @Test
    fun `una respuesta tardia con una revision anterior no se aplica`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        val staleExpectation = requireNotNull(state.expectationFor(id))
        state = withImage(state, id, image(anchor(42)))

        val edit = editorOf(state, id)
            .placePoint(p(10.0, 10.0), confirmation(anchor(42)))
        assertEquals(
            AngleAnalysisDraftRejection.StaleFrameRevision(
                frameId = id,
                expectedRevision = 0L,
                actualRevision = 1L,
            ),
            rejected(state.applyEditorOutcome(staleExpectation, edit)),
        )
        assertNull(editorOf(state, id).anchor)
        assertTrue(editorOf(state, id).draft.points.isEmpty())

        // Con la expectativa vigente la misma acción sí se aplica.
        state = applied(
            state.applyEditorOutcome(requireNotNull(state.expectationFor(id)), edit),
        )
        assertEquals(1, editorOf(state, id).draft.points.size)
    }

    @Test
    fun `un resultado de otra generacion, otra fuente u otro ancla no se aplica`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42)))
        val expectation = requireNotNull(state.expectationFor(id))

        val otherGeneration = AngleEditorState.initial(source, generation + 1L, defaults)
        val generationOutcome = otherGeneration.placePoint(
            p(10.0, 10.0),
            AngleFrameConfirmation(anchor(42), generation + 1L, paused = true),
        )
        assertEquals(
            AngleAnalysisDraftRejection.StaleGeneration(
                expectedGeneration = generation,
                receivedGeneration = generation + 1L,
            ),
            rejected(state.applyEditorOutcome(expectation, generationOutcome)),
        )

        val foreignEditor = AngleEditorState.initial(otherSource, generation, defaults)
        val foreignOutcome = foreignEditor.placePoint(
            p(10.0, 10.0),
            AngleFrameConfirmation(
                AngleFrameAnchor(otherSource, 42, 1_399_986L),
                generation,
                paused = true,
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.ForeignSource(
                expectedSourceIdentity = source,
                receivedSourceIdentity = otherSource,
            ),
            rejected(state.applyEditorOutcome(expectation, foreignOutcome)),
        )

        val otherAnchorEditor = AngleEditorState.initial(source, generation, defaults)
        val otherAnchorOutcome = otherAnchorEditor.placePoint(
            p(10.0, 10.0),
            AngleFrameConfirmation(anchor(43), generation, paused = true),
        )
        assertEquals(
            AngleAnalysisDraftRejection.FrameAnchorMismatch(
                frameId = id,
                expected = anchor(42),
                received = anchor(43),
            ),
            rejected(state.applyEditorOutcome(expectation, otherAnchorOutcome)),
        )

        // Las expectativas de otra sesión/fotograma se rechazan antes de tocar el estado.
        assertEquals(
            AngleAnalysisDraftRejection.StaleGeneration(generation, generation + 5L),
            rejected(
                state.discardFrameDraft(
                    expectation.copy(generation = generation + 5L),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.ForeignSource(source, otherSource),
            rejected(
                state.discardFrameDraft(
                    expectation.copy(sourceIdentity = otherSource),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.UnknownFrame(frameId(9)),
            rejected(
                state.discardFrameDraft(
                    expectation.copy(frameId = frameId(9)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertNull(editorOf(state, id).anchor)
        assertTrue(editorOf(state, id).draft.points.isEmpty())
    }

    // ---------- Acciones destructivas ----------

    @Test
    fun `eliminar un fotograma con datos exige confirmacion explicita`() {
        val first = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), first, anchor(10))
        state = withFrame(state, second, anchor(20))
        state = withImage(state, first, image(anchor(10), token = "image-10"))
        state = completeAngle(state, first)
        state = applied(state.selectFrame(first))
        val firstExpectation = requireNotNull(state.expectationFor(first))

        assertEquals(
            AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                AngleDiscardReason.FRAME_REMOVAL,
            ),
            rejected(state.removeFrame(firstExpectation, AngleDiscardConfirmation.NOT_CONFIRMED)),
        )
        // El rechazo no perdió nada: la misma expectativa sigue vigente y los datos están intactos.
        assertEquals(firstExpectation, state.expectationFor(first))
        assertEquals(2, state.frameCount)
        assertEquals(1, editorOf(state, first).angles.size)
        assertEquals(first, state.activeFrameId)

        val removed = applied(
            state.removeFrame(firstExpectation, AngleDiscardConfirmation.CONFIRMED),
        )
        assertEquals(listOf(second), removed.frames.map { it.id })
        assertNull(removed.activeFrameId)
        assertNull(removed.expectationFor(first))
        assertFalse(removed.requiresDiscardConfirmation(first))

        // Ni el id ni el token de la imagen eliminada se reutilizan para otro contenido.
        assertEquals(
            AngleAnalysisDraftRejection.RetiredFrameId(first),
            rejected(removed.addFrame(first, anchor(10))),
        )
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageRetired("image-10"),
            rejected(
                removed.adoptFrozenImage(
                    requireNotNull(removed.expectationFor(second)),
                    AngleFrozenImage("image-10", anchor(20)),
                ),
            ),
        )
    }

    @Test
    fun `un fotograma vacio se puede quitar sin confirmacion`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(10))
        assertFalse(state.requiresDiscardConfirmation(id))

        state = applied(
            state.removeFrame(
                requireNotNull(state.expectationFor(id)),
                AngleDiscardConfirmation.NOT_CONFIRMED,
            ),
        )
        assertEquals(0, state.frameCount)
        assertFalse(state.hasUnsavedData)
        assertNull(state.activeFrameId)
    }

    @Test
    fun `descartar un borrador A B exige confirmacion y conserva angulos y ancla`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42)))
        state = completeAngle(state, id)
        state = place(state, id, p(300.0, 300.0), p(400.0, 300.0))

        val before = requireNotNull(state.frame(id))
        assertEquals(2, before.editor.draft.points.size)
        assertEquals(1, before.editor.angles.size)
        assertEquals(
            AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                AngleDiscardReason.DRAFT_WITH_POINTS,
            ),
            rejected(
                state.discardFrameDraft(
                    requireNotNull(state.expectationFor(id)),
                    AngleDiscardConfirmation.NOT_CONFIRMED,
                ),
            ),
        )
        assertEquals(before, state.frame(id))

        val cleared = applied(
            state.discardFrameDraft(
                requireNotNull(state.expectationFor(id)),
                AngleDiscardConfirmation.CONFIRMED,
            ),
        )
        val after = requireNotNull(cleared.frame(id))
        assertTrue(after.editor.draft.points.isEmpty())
        assertEquals(before.editor.angles, after.editor.angles)
        assertEquals(before.editor.anchor, after.editor.anchor)
        assertEquals(before.revision + 1L, after.revision)

        // Un borrador ya vacío se descarta sin confirmación y sin crear estado nuevo.
        assertSame(
            cleared,
            applied(
                cleared.discardFrameDraft(
                    requireNotNull(cleared.expectationFor(id)),
                    AngleDiscardConfirmation.NOT_CONFIRMED,
                ),
            ),
        )
    }

    // ---------- Finalización y propagación del editor ----------

    @Test
    fun `la finalizacion enumera huecos, borradores pendientes y analisis vacio`() {
        assertEquals(
            AngleAnalysisFinalization.Blocked(
                blockers = listOf(AngleFinalizationBlocker.NoFrames),
            ),
            state().finalization(),
        )

        val missing = frameId(1)
        val pending = frameId(2)
        val complete = frameId(3)
        var state = withFrame(state(), missing, anchor(10))
        state = withFrame(state, pending, anchor(20))
        state = withImage(state, pending, image(anchor(20)))
        state = completeAngle(state, pending)
        state = place(state, pending, p(300.0, 300.0))
        state = withFrame(state, complete, anchor(30))
        state = withImage(state, complete, image(anchor(30)))
        state = completeAngle(state, complete)

        val framesBefore = state.frames
        assertEquals(
            AngleAnalysisFinalization.Blocked(
                blockers = listOf(
                    AngleFinalizationBlocker.MissingImage(missing),
                    AngleFinalizationBlocker.NoCompleteAngle(missing),
                    AngleFinalizationBlocker.PendingDraft(
                        frameId = pending,
                        placedPoints = 1,
                    ),
                ),
            ),
            state.finalization(),
        )
        assertSame(framesBefore, state.frames)

        state = withImage(state, missing, image(anchor(10)))
        state = completeAngle(state, missing)
        state = applied(
            state.discardFrameDraft(
                requireNotNull(state.expectationFor(pending)),
                AngleDiscardConfirmation.CONFIRMED,
            ),
        )
        val ready = state.finalization() as? AngleAnalysisFinalization.Ready
            ?: throw AssertionError("Esperaba Ready, llegó ${state.finalization()}")
        assertEquals(listOf(missing, pending, complete), ready.frames.map { it.id })
        assertTrue(ready.frames.all { it.angles.isNotEmpty() })
        assertTrue(ready.frames.all { it.image.anchor == it.anchor })
    }

    @Test
    fun `un rechazo del editor se propaga sin cambiar la sesion`() {
        val first = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), first, anchor(10))
        state = withFrame(state, second, anchor(20))
        state = withImage(state, first, image(anchor(10)))
        state = withImage(state, second, image(anchor(20)))
        state = completeAngle(state, first)

        val expectation = requireNotNull(state.expectationFor(first))
        val angleId = editorOf(state, first).angles.single().id
        val blankRename = editorOf(state, first).rename(angleId, "   ")

        assertEquals(
            AngleAnalysisDraftRejection.EditorRejected(AngleEditorRejection.BlankName),
            rejected(state.applyEditorOutcome(expectation, blankRename)),
        )
        assertEquals(expectation, state.expectationFor(first))
        assertEquals(angleId, editorOf(state, first).angles.single().id)
        assertTrue(editorOf(state, second).draft.points.isEmpty())
    }

    // ---------- Sustitución de imagen: nunca mueve geometría ----------

    @Test
    fun `reemplazar la imagen con un angulo completo se rechaza y conserva imagen y angulos`() {
        val id = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), id, anchor(42))
        state = withFrame(state, second, anchor(50))
        state = withImage(state, id, image(anchor(42), token = "captured"))
        state = completeAngle(state, id)

        val before = requireNotNull(state.frame(id))
        val expectation = requireNotNull(state.expectationFor(id))
        assertEquals("captured", before.image?.token)
        assertEquals(1, before.editor.angles.size)

        // La falta de confirmación se informa antes que el bloqueo por geometría: no cambia el
        // motivo que el contrato previo ya devolvía a un llamante sin confirmar.
        assertEquals(
            AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                AngleDiscardReason.IMAGE_REPLACEMENT,
            ),
            rejected(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("other-capture", anchor(42)),
                    AngleDiscardConfirmation.NOT_CONFIRMED,
                ),
            ),
        )

        // Con comportamiento previo esta llamada devolvía Applied y dejaba los tres puntos del
        // ángulo medidos sobre otra imagen: el test es rojo contra esa implementación.
        assertEquals(
            AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry(
                frameId = id,
                completeAngles = 1,
                draftPoints = 0,
            ),
            rejected(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("other-capture", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(before, state.frame(id))
        assertEquals(expectation, state.expectationFor(id))
        assertEquals("captured", requireNotNull(state.frame(id)).image?.token)
        assertEquals(before.editor.angles, editorOf(state, id).angles)
        assertEquals(before.revision, requireNotNull(state.frame(id)).revision)

        // El token vigente no se retiró al rechazar: sigue vivo en su fotograma, no «eliminado».
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageAlreadyUsed(
                token = "captured",
                frameId = id,
            ),
            rejected(
                state.adoptFrozenImage(
                    requireNotNull(state.expectationFor(second)),
                    AngleFrozenImage("captured", anchor(50)),
                ),
            ),
        )
        assertNull(requireNotNull(state.frame(second)).image)
    }

    @Test
    fun `reemplazar la imagen con puntos A B pendientes se rechaza y conserva el borrador`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42), token = "captured"))
        state = place(state, id, p(100.0, 100.0))

        var before = requireNotNull(state.frame(id))
        assertEquals(1, before.editor.draft.points.size)
        assertEquals(
            AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry(
                frameId = id,
                completeAngles = 0,
                draftPoints = 1,
            ),
            rejected(
                state.replaceFrozenImage(
                    requireNotNull(state.expectationFor(id)),
                    AngleFrozenImage("other-capture", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(before, state.frame(id))

        // Con A y B ya colocados (dos puntos) tampoco se sustituye la imagen.
        state = place(state, id, p(200.0, 100.0))
        before = requireNotNull(state.frame(id))
        assertEquals(2, before.editor.draft.points.size)
        assertEquals(
            AngleAnalysisDraftRejection.ImageReplacementBlockedByGeometry(
                frameId = id,
                completeAngles = 0,
                draftPoints = 2,
            ),
            rejected(
                state.replaceFrozenImage(
                    requireNotNull(state.expectationFor(id)),
                    AngleFrozenImage("other-capture", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(before, state.frame(id))
        assertEquals(before.editor.draft, editorOf(state, id).draft)
        assertEquals(before.editor.anchor, editorOf(state, id).anchor)
        assertEquals(before.revision, requireNotNull(state.frame(id)).revision)
        assertEquals("captured", requireNotNull(state.frame(id)).image?.token)
    }

    @Test
    fun `reemplazar la imagen sin geometria retira el token anterior`() {
        val id = frameId(1)
        val second = frameId(2)
        var state = withFrame(state(), id, anchor(42))
        state = withFrame(state, second, anchor(50))
        state = withImage(state, id, image(anchor(42), token = "captured"))

        val replaced = applied(
            state.replaceFrozenImage(
                requireNotNull(state.expectationFor(id)),
                AngleFrozenImage("recaptured", anchor(42)),
                AngleDiscardConfirmation.CONFIRMED,
            ),
        )
        assertEquals("recaptured", requireNotNull(replaced.frame(id)).image?.token)
        assertEquals(
            requireNotNull(state.frame(id)).revision + 1L,
            requireNotNull(replaced.frame(id)).revision,
        )
        assertTrue(editorOf(replaced, id).angles.isEmpty())
        assertTrue(editorOf(replaced, id).draft.points.isEmpty())

        // El token sustituido no se puede usar en otro fotograma ni volver a este.
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageRetired("captured"),
            rejected(
                replaced.adoptFrozenImage(
                    requireNotNull(replaced.expectationFor(second)),
                    AngleFrozenImage("captured", anchor(50)),
                ),
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageRetired("captured"),
            rejected(
                replaced.replaceFrozenImage(
                    requireNotNull(replaced.expectationFor(id)),
                    AngleFrozenImage("captured", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals("recaptured", requireNotNull(replaced.frame(id)).image?.token)
        assertNull(requireNotNull(replaced.frame(second)).image)
    }

    @Test
    fun `reemplazar por la misma imagen sigue siendo un no-op que exige confirmacion`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42), token = "captured"))
        state = completeAngle(state, id)
        val expectation = requireNotNull(state.expectationFor(id))
        val before = requireNotNull(state.frame(id))

        assertEquals(
            AngleAnalysisDraftRejection.DiscardConfirmationRequired(
                AngleDiscardReason.IMAGE_REPLACEMENT,
            ),
            rejected(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("captured", anchor(42)),
                    AngleDiscardConfirmation.NOT_CONFIRMED,
                ),
            ),
        )
        // Misma imagen: no cambia nada, así que el bloqueo por geometría no aplica y el estado se
        // conserva tal cual (sin avanzar revisión ni retirar el token).
        assertSame(
            state,
            applied(
                state.replaceFrozenImage(
                    expectation,
                    AngleFrozenImage("captured", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals(before, state.frame(id))
        assertEquals(expectation, state.expectationFor(id))

        state = withFrame(state, frameId(2), anchor(50))
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageAlreadyUsed(
                token = "captured",
                frameId = id,
            ),
            rejected(
                state.adoptFrozenImage(
                    requireNotNull(state.expectationFor(frameId(2))),
                    AngleFrozenImage("captured", anchor(50)),
                ),
            ),
        )
    }

    @Test
    fun `una respuesta de captura con revision anterior no resucita la imagen sustituida`() {
        val id = frameId(1)
        var state = withFrame(state(), id, anchor(42))
        state = withImage(state, id, image(anchor(42), token = "captured"))
        val staleExpectation = requireNotNull(state.expectationFor(id))

        state = applied(
            state.replaceFrozenImage(
                staleExpectation,
                AngleFrozenImage("recaptured", anchor(42)),
                AngleDiscardConfirmation.CONFIRMED,
            ),
        )

        // La respuesta tardía de la captura anterior se rechaza por revisión y no toca nada.
        assertEquals(
            AngleAnalysisDraftRejection.StaleFrameRevision(
                frameId = id,
                expectedRevision = staleExpectation.revision,
                actualRevision = staleExpectation.revision + 1L,
            ),
            rejected(
                state.adoptFrozenImage(
                    staleExpectation,
                    AngleFrozenImage("captured", anchor(42)),
                ),
            ),
        )

        // Con la expectativa vigente tampoco se re-adopta ni se reasigna la imagen sustituida: el
        // fotograma ya tiene imagen y el token viejo está retirado.
        val freshExpectation = requireNotNull(state.expectationFor(id))
        assertEquals(
            AngleAnalysisDraftRejection.ImageAlreadyAdopted(id),
            rejected(
                state.adoptFrozenImage(
                    freshExpectation,
                    AngleFrozenImage("captured", anchor(42)),
                ),
            ),
        )
        assertEquals(
            AngleAnalysisDraftRejection.FrozenImageRetired("captured"),
            rejected(
                state.replaceFrozenImage(
                    freshExpectation,
                    AngleFrozenImage("captured", anchor(42)),
                    AngleDiscardConfirmation.CONFIRMED,
                ),
            ),
        )
        assertEquals("recaptured", requireNotNull(state.frame(id)).image?.token)
        assertEquals(freshExpectation, state.expectationFor(id))
    }
}
