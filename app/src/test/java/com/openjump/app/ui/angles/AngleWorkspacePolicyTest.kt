package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameDraft
import com.openjump.app.angles.AngleFrozenImage
import com.openjump.app.angles.AnglePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla de elegibilidad del workspace vídeo-primero.
 *
 * Fija la política pura que consume la ruta en el siguiente slice: solo un análisis **nuevo**
 * (`routeAnalysisId == 0`) con el gate V2 activo entra al workspace. Legacy (id positivo) y una
 * ruta inválida (id negativo) se quedan en el flujo existente aunque el gate esté encendido, y con
 * el gate apagado todo se comporta como legacy.
 */
class AngleWorkspacePolicyTest {

    @Test
    fun `un analisis nuevo con el gate activo es elegible`() {
        assertTrue(angleWorkspaceEligible(gateEnabled = true, routeAnalysisId = 0L))
    }

    @Test
    fun `sin gate ningun analisis es elegible`() {
        assertFalse(angleWorkspaceEligible(gateEnabled = false, routeAnalysisId = 0L))
        assertFalse(angleWorkspaceEligible(gateEnabled = false, routeAnalysisId = 7L))
        assertFalse(angleWorkspaceEligible(gateEnabled = false, routeAnalysisId = -1L))
    }

    @Test
    fun `una reapertura legacy no es elegible aunque el gate este activo`() {
        assertFalse(angleWorkspaceEligible(gateEnabled = true, routeAnalysisId = 1L))
        assertFalse(angleWorkspaceEligible(gateEnabled = true, routeAnalysisId = 42L))
    }

    @Test
    fun `una ruta con id negativo se rechaza y no se interpreta como analisis nuevo`() {
        assertFalse(angleWorkspaceEligible(gateEnabled = true, routeAnalysisId = -1L))
        assertFalse(angleWorkspaceEligible(gateEnabled = true, routeAnalysisId = Long.MIN_VALUE))
    }
}

/**
 * Tabla de fase del lienzo: la misma función pura que usa el callback del toque en la UI.
 *
 * Cubre el defecto real del primer slice: un fotograma nuevo con cero ángulos entraba en EDIT y no
 * permitía colocar A sin pulsar «Otro ángulo». Aquí se fija que la fase se deriva del editor
 * canónico, no de un `remember` inicial, y que un editor ya completo rechaza el cuarto toque aunque
 * la composición anterior siguiera en PLACEMENT.
 */
class AngleWorkspacePhaseTest {

    private val source = "video:content://openjump/workspace-phase"
    private val anchor = AngleFrameAnchor(source, frameIndex = 7, sourcePtsUs = 233_333L)
    private val defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFFE53935.toInt())
    private val generation = 11L

    private fun confirm() = AngleFrameConfirmation(
        frame = anchor,
        generation = generation,
        paused = true,
    )

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun stateWithDraft(points: Int): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        val a = AnglePoint(20.0, 20.0)
        val b = AnglePoint(80.0, 20.0)
        if (points >= 1) state = applied(state.placePoint(a, confirm()))
        if (points >= 2) state = applied(state.placePoint(b, confirm()))
        return state
    }

    private fun completeState(): AngleEditorState =
        applied(stateWithDraft(2).placePoint(AnglePoint(80.0, 80.0), confirm()))

    @Test
    fun `un fotograma nuevo sin angulos coloca el primer A sin pulsar Otro angulo`() {
        val empty = AngleEditorState.initial(source, generation, defaults)
        assertEquals(AngleOverlayMode.PLACEMENT, angleWorkspaceInteractionMode(empty, false))
        assertTrue(angleWorkspaceAcceptsPlacement(empty, false))
    }

    @Test
    fun `un borrador A o A-B sigue en colocacion y no se descarta`() {
        val one = stateWithDraft(1)
        val two = stateWithDraft(2)
        assertEquals(AngleOverlayMode.PLACEMENT, angleWorkspaceInteractionMode(one, false))
        assertEquals(AngleOverlayMode.PLACEMENT, angleWorkspaceInteractionMode(two, false))
        assertEquals(1, one.draft.points.size)
        assertEquals(2, two.draft.points.size)
        assertTrue(angleWorkspaceAcceptsPlacement(two, false))
    }

    @Test
    fun `con angulos completos la fase es edicion`() {
        val complete = completeState()
        assertEquals(1, complete.angles.size)
        assertTrue(complete.draft.points.isEmpty())
        assertEquals(AngleOverlayMode.EDIT, angleWorkspaceInteractionMode(complete, false))
        assertFalse(angleWorkspaceAcceptsPlacement(complete, false))
    }

    @Test
    fun `Otro angulo explicito vuelve a colocar y conserva el borrador pendiente`() {
        val complete = completeState()
        assertEquals(AngleOverlayMode.PLACEMENT, angleWorkspaceInteractionMode(complete, true))
        assertTrue(angleWorkspaceAcceptsPlacement(complete, true))
        val pendiente = stateWithDraft(2)
        assertEquals(AngleOverlayMode.PLACEMENT, angleWorkspaceInteractionMode(pendiente, true))
        assertEquals(2, pendiente.draft.points.size)
    }

    @Test
    fun `un editor fresco ya completo rechaza el cuarto toque aunque la composicion siga en colocacion`() {
        // La composición vieja creía PLACEMENT (lo que provocaba el doble A). El callback reevalúa
        // con el editor que el ViewModel acaba de publicar: ya hay terna completa y no hay intención.
        val fresh = completeState()
        assertFalse(angleWorkspaceAcceptsPlacement(fresh, addingAngle = false))
    }
}

/**
 * Binding del fotograma **dibujado**: la identidad que valida toda acción de geometría.
 *
 * Cubre el defecto prioritario: un toque calculado con el mapper del fotograma A no puede acabar en
 * B solo porque la sesión ya tenga B seleccionado. Sin binding exacto (mismo id, misma fuente y
 * misma generación) la acción se ignora; nunca se redirige.
 */
class AngleWorkspaceFrameBindingTest {

    private val source = "video:content://openjump/binding"
    private val frameA = AngleFrameId(1L)
    private val frameB = AngleFrameId(2L)

    private fun rendered(
        frameId: AngleFrameId? = frameA,
        sourceIdentity: String? = source,
        generation: Long? = 3L,
        leaseFrameId: AngleFrameId? = frameId,
        leaseOpen: Boolean = true,
    ) = angleRenderedFrameBinding(
        activeFrameId = frameId,
        sourceIdentity = sourceIdentity,
        generation = generation,
        leaseFrameId = leaseFrameId,
        leaseOpen = leaseOpen,
    )

    @Test
    fun `el binding dibujado exige id, fuente, generacion y lease abierto del mismo fotograma`() {
        assertEquals(AngleFrameBinding(frameA, source, 3L), rendered())
        assertEquals(null, rendered(leaseOpen = false))
        assertEquals(null, rendered(leaseFrameId = frameB))
        assertEquals(null, rendered(frameId = null))
        assertEquals(null, rendered(sourceIdentity = null))
        assertEquals(null, rendered(generation = null))
    }

    @Test
    fun `dibujar A y tener B como fresco no produce destino`() {
        val drawn = rendered()
        val fresh = AngleFrameBinding(frameB, source, 3L)
        assertEquals(null, angleFrameActionTarget(drawn, fresh))
    }

    @Test
    fun `el mismo id con otra fuente u otra generacion tampoco produce destino`() {
        val drawn = rendered()
        assertEquals(null, angleFrameActionTarget(drawn, AngleFrameBinding(frameA, "video:otro", 3L)))
        assertEquals(null, angleFrameActionTarget(drawn, AngleFrameBinding(frameA, source, 4L)))
    }

    @Test
    fun `un binding identico si produce destino`() {
        assertEquals(frameA, angleFrameActionTarget(rendered(), rendered()))
    }

    @Test
    fun `sin binding dibujado o sin fotograma fresco no hay destino`() {
        assertEquals(null, angleFrameActionTarget(null, rendered()))
        assertEquals(null, angleFrameActionTarget(rendered(), null))
        // Sesión cerrada o fotograma sin imagen: el binding fresco no existe, no se actúa.
        assertEquals(null, angleFreshFrameBinding(null, frameA))
    }

    @Test
    fun `la etiqueta de instante usa el PTS de la fuente`() {
        assertEquals("0:00", angleFrameTimestampLabel(0L))
        assertEquals("0:07", angleFrameTimestampLabel(7_500_000L))
        assertEquals("1:05", angleFrameTimestampLabel(65_000_000L))
        assertEquals("0:00", angleFrameTimestampLabel(-5L))
    }
}

/**
 * Guardas de **frescura** con estados reales (sin bitmaps ni `ViewModel`).
 *
 * Los tests fabricaban antes un binding B a mano, así que no ejercitaban la derivación real. Aquí se
 * instancian `AngleMultiFrameSessionState`/`AngleMultiFrameItem`/`AngleFrameDraft` reales con
 * `AngleFrozenImage` sin píxeles y la fase de captura `Ready`, que es lo único que [canEdit] mira.
 */
class AngleWorkspaceFreshnessTest {

    private val source = "video:content://openjump/freshness"
    private val generation = 5L
    private val frameA = AngleFrameId(1L)
    private val frameB = AngleFrameId(2L)
    private val defaults = AngleEditorDefaults(namePrefix = "Ángulo", colorArgb = 0xFF1E88E5.toInt())
    private val anchorA = AngleFrameAnchor(source, frameIndex = 3, sourcePtsUs = 100_000L)
    private val anchorB = AngleFrameAnchor(source, frameIndex = 9, sourcePtsUs = 300_000L)

    private fun editor(anchor: AngleFrameAnchor, points: Int = 0): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        val confirmation = AngleFrameConfirmation(frame = anchor, generation = generation, paused = true)
        val a = AnglePoint(20.0, 20.0)
        val b = AnglePoint(80.0, 20.0)
        if (points >= 1) state = (state.placePoint(a, confirmation) as AngleEditorOutcome.Applied).state
        if (points >= 2) state = (state.placePoint(b, confirmation) as AngleEditorOutcome.Applied).state
        return state
    }

    private fun item(
        id: AngleFrameId,
        anchor: AngleFrameAnchor,
        points: Int = 0,
        capture: AngleDraftCapturePhase = AngleDraftCapturePhase.Ready("token-${id.value}"),
        image: AngleFrozenImage? = AngleFrozenImage("frozen-${id.value}", anchor),
    ): AngleMultiFrameItem = AngleMultiFrameItem(
        frame = AngleFrameDraft(
            id = id,
            anchor = anchor,
            image = image,
            editor = editor(anchor, points),
            revision = 0L,
        ),
        isActive = true,
        capture = capture,
        leaseCount = 0,
        retainedBytes = 0L,
    )

    private fun session(
        frames: List<AngleMultiFrameItem>,
        active: AngleFrameId? = frames.firstOrNull()?.frameId,
        closed: Boolean = false,
        version: Long = 1L,
        sourceIdentity: String = source,
        generation: Long = this.generation,
    ): AngleMultiFrameSessionState = AngleMultiFrameSessionState(
        sourceIdentity = sourceIdentity,
        generation = generation,
        sourceFrameCount = 120,
        frames = frames,
        activeFrameId = active,
        retainedBytes = 0L,
        reservedCaptureBytes = 0L,
        droppedLateResults = 0L,
        isClosed = closed,
        finalization = AngleAnalysisFinalization.Blocked(emptyList()),
        version = version,
    )

    @Test
    fun `el binding fresco exige que el fotograma sea el activo y que la sesion no este cerrada`() {
        val state = session(listOf(item(frameA, anchorA), item(frameB, anchorB)), active = frameB)
        assertEquals(null, angleFreshFrameBinding(state, frameA))
        assertEquals(AngleFrameBinding(frameB, source, generation), angleFreshFrameBinding(state, frameB))
        assertEquals(null, angleFreshFrameBinding(state, frameA))
        val closed = session(listOf(item(frameA, anchorA)), active = frameA, closed = true)
        assertEquals(null, angleFreshFrameBinding(closed, frameA))
        // Binding dibujado en A contra un estado activo en B: destino nulo, nunca se reintenta en B.
        val drawn = AngleFrameBinding(frameA, source, generation)
        assertEquals(null, angleFrameActionTarget(drawn, angleFreshFrameBinding(state, drawn.frameId)))
    }

    @Test
    fun `un fotograma sin imagen o con captura en vuelo no tiene binding fresco`() {
        val noImage = item(frameA, anchorA, image = null)
        assertEquals(null, angleFreshFrameBinding(session(listOf(noImage)), frameA))
        val capturing = item(frameA, anchorA, capture = AngleDraftCapturePhase.Capturing(7L))
        assertEquals(null, angleFreshFrameBinding(session(listOf(capturing)), frameA))
    }

    @Test
    fun `la sesion publicada manda y una copia local cerrada nunca resucita`() {
        val published = session(listOf(item(frameA, anchorA)), version = 4L)
        assertEquals(published, freshestMultiFrameSession(AngleMultiFrameState.Ready(published), null))
        val closedApplied = session(listOf(item(frameA, anchorA)), version = 9L, closed = true)
        assertEquals(
            published,
            freshestMultiFrameSession(AngleMultiFrameState.Ready(published), closedApplied),
        )
        assertEquals(null, freshestMultiFrameSession(null, published))
        assertEquals(
            null,
            freshestMultiFrameSession(AngleMultiFrameState.Closed, published),
        )
        assertEquals(
            null,
            freshestMultiFrameSession(AngleMultiFrameState.Opening, published),
        )
    }

    @Test
    fun `dentro de la misma epoca gana la version mayor y otra generacion se ignora`() {
        val published = session(listOf(item(frameA, anchorA)), version = 4L)
        val newer = session(listOf(item(frameA, anchorA)), version = 6L)
        assertEquals(newer, freshestMultiFrameSession(AngleMultiFrameState.Ready(published), newer))
        val older = session(listOf(item(frameA, anchorA)), version = 2L)
        assertEquals(published, freshestMultiFrameSession(AngleMultiFrameState.Ready(published), older))
        val otherGeneration = session(listOf(item(frameA, anchorA)), version = 99L, generation = 6L)
        assertEquals(
            published,
            freshestMultiFrameSession(AngleMultiFrameState.Ready(published), otherGeneration),
        )
        val otherSource = session(listOf(item(frameA, anchorA)), version = 99L, sourceIdentity = "video:otra")
        assertEquals(
            published,
            freshestMultiFrameSession(AngleMultiFrameState.Ready(published), otherSource),
        )
    }

    @Test
    fun `la marca de medicion completa exige angulos y ningun borrador pendiente`() {
        assertFalse(item(frameA, anchorA).hasCompleteMeasurement)
        assertFalse(item(frameA, anchorA, points = 2).hasCompleteMeasurement)
        val completed = item(frameA, anchorA).let { base ->
            var state = editor(anchorA)
            val confirmation = AngleFrameConfirmation(anchorA, generation, paused = true)
            state = (state.placePoint(AnglePoint(10.0, 10.0), confirmation) as AngleEditorOutcome.Applied).state
            state = (state.placePoint(AnglePoint(90.0, 10.0), confirmation) as AngleEditorOutcome.Applied).state
            state = (state.placePoint(AnglePoint(50.0, 90.0), confirmation) as AngleEditorOutcome.Applied).state
            base.copy(frame = base.frame.copy(editor = state))
        }
        assertTrue(completed.hasCompleteMeasurement)
        // Añadir A/B a un fotograma ya medido retira la marca: no se presenta como completo.
        val confirmation = AngleFrameConfirmation(anchorA, generation, paused = true)
        val pending = (completed.editor.placePoint(AnglePoint(5.0, 5.0), confirmation)
            as AngleEditorOutcome.Applied).state
        assertTrue(pending.angles.isNotEmpty())
        assertTrue(pending.draft.points.isNotEmpty())
        val withDraft = completed.copy(frame = completed.frame.copy(editor = pending))
        assertFalse(withDraft.hasCompleteMeasurement)
        assertFalse(completed.copy(capture = AngleDraftCapturePhase.NotCaptured).hasCompleteMeasurement)
    }
}
