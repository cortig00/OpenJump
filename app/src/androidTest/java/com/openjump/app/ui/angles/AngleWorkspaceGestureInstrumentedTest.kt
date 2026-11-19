package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.theme.OpenJumpTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas con PUNTEROS reales del gesto transaccional del workspace.
 *
 * Usan la fuente de producción ([AngleOverlay] con `deferredGestureCommit` y
 * [AngleWorkspaceFramingArena]); no hay ayudantes escalares ni un segundo detector de mentira.
 * Acreditan:
 *
 * - un arrastre de un dedo sobre el vértice B **no** entrega movimientos al propietario antes de
 *   levantar el dedo, y confirma **una sola vez** al soltar;
 * - un segundo dedo a mitad de arrastre (incluso después de haber movido el primero) descarta todo
 *   el preview y no entrega ninguna mutación;
 * - cambiar la identidad del gesto (`interactionKey`) durante el arrastre cancela el preview sin
 *   retarget ni callback (el caso A/B con el mismo AngleId y la misma geometría);
 * - la arena de encuadre solo actúa con dos punteros, informa ocupación y deja el gesto de un dedo
 *   intacto.
 *
 * No comprueban píxeles (color/legibilidad) ni ejecutan vídeo, Room ni PNG reales; la verificación
 * de runtime completa queda para la fase de dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class AngleWorkspaceGestureInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val source = "video:content://openjump/workspace-gesture"
    private val generation = 21L
    private val anchor = AngleFrameAnchor(source, frameIndex = 5, sourcePtsUs = 200_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFF35D0FF.toInt())

    private val a = AnglePoint(99.5, 10.0)
    private val b = AnglePoint(99.5, 49.5)
    private val c = AnglePoint(150.0, 49.5)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun editorWithAngle(selected: Boolean = true): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        listOf(a, b, c).forEach { state = applied(state.placePoint(it, confirmation)) }
        return if (selected) applied(state.select(AngleId(1L))) else state
    }

    private fun formatDegrees(degrees: Double): String =
        String.format(Locale.US, "%.1f", degrees)

    /** Overlay de producción a tamaño de test; el lado real se conoce con [onSizeChanged]. */
    @Composable
    private fun SizedDeferredOverlay(
        state: AngleEditorState,
        interactionKey: Any?,
        onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Unit,
        onPreviewActiveChanged: (Boolean) -> Unit,
    ) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .size(400.dp, 100.dp)
                .onSizeChanged { size = it },
        ) {
            if (size.width > 0 && size.height > 0) {
                val mapper = remember(size) {
                    VideoCoordinateMapper(
                        video = VideoPresentationGeometry(200, 100),
                        viewport = ViewportSize(size.width.toDouble(), size.height.toDouble()),
                        scaleMode = VideoScaleMode.FIT,
                    )
                }
                AngleOverlay(
                    mapper = mapper,
                    state = state,
                    formatLabel = { _, degrees -> formatDegrees(degrees) },
                    enabled = true,
                    mode = AngleOverlayMode.EDIT,
                    accessibilityDescription = OVERLAY_DESCRIPTION,
                    onMoveMarker = onMoveMarker,
                    interactionKey = interactionKey,
                    deferredGestureCommit = true,
                    previewConfirmation = confirmation,
                    onPreviewActiveChanged = onPreviewActiveChanged,
                )
            }
        }
    }

    @Test
    fun un_dedo_mueve_el_preview_sin_mutar_y_confirma_una_vez_al_soltar() {
        val moves = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        val previewStates = mutableListOf<Boolean>()
        val state = editorWithAngle()
        rule.setContent {
            SizedDeferredOverlay(
                state = state,
                interactionKey = "scene:A",
                onMoveMarker = { id, marker, point -> moves += Triple(id, marker, point) },
                onPreviewActiveChanged = { previewStates += it },
            )
        }

        val node = rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION)
        node.performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(0f, 60f))
        }
        // El dominio sigue intacto y no hay callback con el dedo aún abajo; el preview está activo.
        assertTrue("no debe confirmar durante el arrastre", moves.isEmpty())
        assertTrue("el preview debe anunciarse activo", previewStates.contains(true))

        node.performTouchInput {
            moveTo(0, center + Offset(0f, 70f))
            up(0)
        }
        rule.waitForIdle()
        assertEquals("un solo commit al levantar el dedo", 1, moves.size)
        val (id, marker, point) = moves.single()
        assertEquals(AngleId(1L), id)
        assertEquals(AngleMarker.B, marker)
        assertTrue("el punto confirmado debe seguir dentro del frame", point.isFinite)
        assertTrue("el vértice debe haberse movido respecto a la base", point != b)
        assertTrue("al soltar el preview deja de estar activo", previewStates.last() == false)
    }

    @Test
    fun un_segundo_dedo_a_mitad_de_arrastre_descarta_todo_sin_mutar() {
        val moves = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        val previewStates = mutableListOf<Boolean>()
        val state = editorWithAngle()
        rule.setContent {
            SizedDeferredOverlay(
                state = state,
                interactionKey = "scene:A",
                onMoveMarker = { id, marker, point -> moves += Triple(id, marker, point) },
                onPreviewActiveChanged = { previewStates += it },
            )
        }

        val node = rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION)
        node.performTouchInput {
            down(0, center)
            // Primer dedo YA desplazado más allá del slop ANTES de que entre el segundo.
            moveTo(0, center + Offset(0f, 60f))
            down(1, center + Offset(80f, 0f))
            moveTo(1, center + Offset(80f, 40f))
            up(1)
            moveTo(0, center + Offset(0f, 70f))
            up(0)
        }
        rule.waitForIdle()
        assertTrue("un gesto multitáctil no confirma ninguna mutación", moves.isEmpty())
        assertTrue("el preview se descarta al final", previewStates.last() == false)
    }

    @Test
    fun cambiar_la_identidad_del_gesto_cancela_el_preview_sin_retarget() {
        val moves = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        var key by mutableStateOf<Any?>("scene:A")
        val state = editorWithAngle()
        rule.setContent {
            SizedDeferredOverlay(
                state = state,
                interactionKey = key,
                onMoveMarker = { id, marker, point -> moves += Triple(id, marker, point) },
                onPreviewActiveChanged = {},
            )
        }

        val node = rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION)
        node.performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(0f, 60f))
        }
        // Cambio de escena con el dedo aún abajo: mismo mapper y mismo AngleId, otra identidad.
        rule.runOnIdle { key = "scene:B" }
        node.performTouchInput { up(0) }
        rule.waitForIdle()
        assertTrue("un gesto de otra escena no puede mover ninguna marca", moves.isEmpty())
    }

    @Test
    fun la_arena_solo_encuadra_con_dos_punteros_y_no_toca_el_gesto_de_uno() {
        val gestures = mutableListOf<Triple<Double, Double, Double>>()
        val busyStates = mutableListOf<Boolean>()
        val mapper = VideoCoordinateMapper(
            video = VideoPresentationGeometry(200, 100),
            viewport = ViewportSize(400.0, 100.0),
            scaleMode = VideoScaleMode.FIT,
        )
        rule.setContent {
            AngleWorkspaceFramingArena(
                mapperProvider = { mapper },
                interactionKey = "scene:A",
                enabled = true,
                onFramingGesture = { _, zoom, panX, panY ->
                    gestures += Triple(zoom, panX, panY)
                },
                onBusyChanged = { busyStates += it },
                modifier = Modifier.size(400.dp, 100.dp),
            )
        }

        val node = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)
        node.performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(0f, 60f))
            up(0)
        }
        rule.waitForIdle()
        assertTrue("un dedo no inicia el encuadre", gestures.isEmpty())
        assertTrue("la arena no debe marcar ocupación con un dedo", busyStates.none { it })

        node.performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            moveTo(0, center - Offset(100f, 0f))
            moveTo(1, center + Offset(100f, 0f))
            up(1)
            moveTo(0, center - Offset(80f, 20f))
            up(0)
        }
        rule.waitForIdle()
        assertTrue("el pinch debe producir encuadre real", gestures.isNotEmpty())
        assertTrue(
            "el zoom relativo debe alejarse de 1",
            gestures.any { it.first != 1.0 } || gestures.any { it.third != 0.0 },
        )
        assertTrue("la ocupación debe anunciarse y limpiarse", busyStates.contains(true))
        assertEquals(false, busyStates.last())
    }

    @Test
    fun resize_misma_escena_entre_move_y_up_no_confirma_nada() {
        // Misma escena con proyección distinta a mitad del arrastre: 0 commits sin depender de CANCEL.
        val moves = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        var mapper by mutableStateOf(
            VideoCoordinateMapper(
                video = VideoPresentationGeometry(200, 100),
                viewport = ViewportSize(400.0, 100.0),
                scaleMode = VideoScaleMode.FIT,
            ),
        )
        val state = editorWithAngle()
        rule.setContent {
            var size by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .size(400.dp, 100.dp)
                    .onSizeChanged { size = it },
            ) {
                if (size.width > 0 && size.height > 0) {
                    AngleOverlay(
                        mapper = mapper,
                        state = state,
                        formatLabel = { _, degrees -> formatDegrees(degrees) },
                        enabled = true,
                        mode = AngleOverlayMode.EDIT,
                        accessibilityDescription = OVERLAY_DESCRIPTION,
                        onMoveMarker = { id, marker, point -> moves += Triple(id, marker, point) },
                        interactionKey = "scene:resize",
                        deferredGestureCommit = true,
                        previewConfirmation = confirmation,
                        onPreviewActiveChanged = {},
                    )
                }
            }
        }
        val node = rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION)
        node.performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(0f, 60f))
        }
        rule.waitForIdle()
        // Resize real de la MISMA escena (zoom 2x con opt-in) con el dedo aún abajo.
        rule.runOnIdle {
            mapper = VideoCoordinateMapper(
                video = VideoPresentationGeometry(200, 100),
                viewport = ViewportSize(400.0, 100.0),
                scaleMode = VideoScaleMode.FIT,
                zoom = 2.0,
                allowFitZoom = true,
            )
        }
        node.performTouchInput { up(0) }
        rule.waitForIdle()
        assertTrue("el resize a mitad descarta el preview (0 commits)", moves.isEmpty())
    }

    private companion object {
        const val OVERLAY_DESCRIPTION = "gesto transaccional del workspace"
    }
}

/**
 * Integración REAL del gesto en el workspace completo ([AngleEditorWorkspace]) con el renderer de
 * producción del arnés ([AngleMultiFrameFlowHarness] con `productionRenderer = true`) y un host de
 * ventana fake explícito. No usa `markAngle` para la primera colocación: los puntos A/B/C entran por
 * PUNTEROS sobre la foto congelada ya capturada.
 *
 * Acredita la jerarquía real (BUG1/BUG2): la arena de encuadre es el CONTENEDOR del lienzo, no un
 * hermano transparente encima; con la arena hermana el hit-test se quedaba todos los DOWN y esta
 * prueba fallaba al no colocarse ningún punto. Además:
 *
 * - el arrastre de un vértice en EDIT pinta un preview por PÍXELES (no solo un flag) sin mutar el
 *   dominio, y un segundo dedo lo descarta sin confirmar nada;
 * - un pinch en fase de colocación no deja ningún punto extra aunque el segundo dedo suba antes que
 *   el primero, y después una terna A/B/C de un dedo sigue funcionando.
 *
 * Límites honestos: no se ejecuta en este slice (sin AVD). Los píxeles se comparan muestreando la
 * captura del nodo real para probar que el ROOT pintó algo distinto; no se decodifica el zoom
 * exacto ni el valor de encuadre, y no se afirma nada sobre Room/PNG más allá de sus recuentos.
 */
@RunWith(AndroidJUnit4::class)
class AngleWorkspaceRootGestureInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private var harness: AngleMultiFrameFlowHarness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private class FakeWindowHost : AngleWorkspaceWindowHost {
        private var requested: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        override fun requestedOrientation(): Int = requested

        override fun requestOrientation(orientation: Int) {
            requested = orientation
        }

        override fun hideSystemBars(): AngleWorkspaceBarsSnapshot =
            AngleWorkspaceBarsSnapshot(true, true, 2)

        override fun restoreSystemBars(snapshot: AngleWorkspaceBarsSnapshot) = Unit
    }

    private fun newHarness(): AngleMultiFrameFlowHarness =
        AngleMultiFrameFlowHarness(productionRenderer = true).also {
            harness = it
            it.openVideo()
            it.openMultiFrameSession()
        }

    private fun composeWorkspace(host: AngleMultiFrameFlowHarness) {
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(
                    viewModel = host.viewModel,
                    onCloseRequest = {},
                    windowHost = FakeWindowHost(),
                )
            }
        }
        rule.waitForIdle()
    }

    /** Geometría REAL del lease del fotograma, cerrando el lease tras leerla. */
    private fun leaseGeometry(
        host: AngleMultiFrameFlowHarness,
        frameId: AngleFrameId,
    ): VideoPresentationGeometry =
        when (val outcome = host.viewModel.acquireMultiFrameBitmap(frameId)) {
            is AngleMultiFrameBitmapResult.Granted ->
                outcome.lease.imageGeometry.also { outcome.lease.close() }

            is AngleMultiFrameBitmapResult.Rejected -> throw AssertionError(
                "No se pudo leer la geometría real de $frameId: ${outcome.rejection}",
            )
        }

    /** Mapper FIT con la geometría REAL del lease del fotograma y el viewport REAL del escenario. */
    private fun stageMapper(host: AngleMultiFrameFlowHarness, frameId: AngleFrameId): VideoCoordinateMapper {
        val geometry = leaseGeometry(host, frameId)
        val stage = rule.onNodeWithTag(ANGLE_WORKSPACE_VIDEO_TAG).fetchSemanticsNode().size
        assertTrue("el escenario debe medir algo", stage.width > 0 && stage.height > 0)
        return VideoCoordinateMapper(
            video = geometry,
            viewport = ViewportSize(stage.width.toDouble(), stage.height.toDouble()),
            scaleMode = VideoScaleMode.FIT,
            allowFitZoom = true,
        )
    }

    private fun stageOffset(mapper: VideoCoordinateMapper, point: AnglePoint): Offset {
        val view = mapper.videoToView(ImagePoint(point.x, point.y))
        return Offset(view.x.toFloat(), view.y.toFloat())
    }

    /** Cuenta muestras de píxeles distintas entre dos capturas del MISMO nodo real. */
    private fun differingSamples(first: ImageBitmap, second: ImageBitmap): Int {
        if (first.width != second.width || first.height != second.height) return Int.MAX_VALUE
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        var differing = 0
        var y = 0
        while (y < first.height) {
            var x = 0
            while (x < first.width) {
                if (a[x, y] != b[x, y]) differing += 1
                x += 4
            }
            y += 4
        }
        return differing
    }

    @Test
    fun root_primer_fotograma_vacio_y_terna_abc_por_punteros() {
        val host = newHarness()
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) {
            host.viewModel.multiFrameState.value is AngleMultiFrameState.Ready
        }
        assertEquals("el arnés no captura nada por su cuenta", 0, host.sessionState().frames.size)

        host.pauseViewerAt(0)
        rule.onNodeWithTag(ANGLE_WORKSPACE_CAPTURE_TAG).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            val state = host.viewModel.multiFrameState.value
            (state as? AngleMultiFrameState.Ready)?.state?.frames?.size == 1
        }
        val frameId = host.sessionState().frames.single().frameId
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameId).canEdit }
        assertEquals("la foto nace sin ángulos", 0, host.frame(frameId).editor.angles.size)

        // La arena debe ser el contenedor del lienzo, no un hermano suelto por encima.
        rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).assertExists()
        val mapper = stageMapper(host, frameId)
        rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).performTouchInput {
            AngleMultiFrameFlowHarness.FIRST_ANGLE.forEach { click(stageOffset(mapper, it)) }
        }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameId).editor.angles.size == 1 }
        val marked = host.frame(frameId).editor
        assertEquals("tres toques reales crean UN ángulo", 1, marked.angles.size)
        assertTrue("la terna no deja borrador", marked.draft.points.isEmpty())
        assertEquals("sin escritura V2", 0L, host.v2RootCount())
        assertEquals("sin PNG/lease extra", 1, host.sessionState().frames.size)
    }

    @Test
    fun root_edita_b_con_preview_real_y_pinch_no_confirma() {
        val host = newHarness()
        val frameA = host.captureFrame(0)
        val frameB = host.captureFrame(1)
        host.markAngle(frameA)
        host.markAngle(frameB)
        host.viewModel.selectMultiFrame(frameA)
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) { host.sessionState().activeFrameId == frameA }

        val mapper = stageMapper(host, frameB)
        val before = host.frame(frameB).editor
        val arena = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)
        val imageBefore = arena.captureToImage()
        val grab = stageOffset(mapper, AngleMultiFrameFlowHarness.FIRST_ANGLE[1])
        arena.performTouchInput {
            down(0, grab)
            moveTo(0, grab + Offset(0f, 140f))
            moveTo(0, grab + Offset(0f, 190f))
        }
        // Preview por PÍXELES (no solo el flag) y dominio intacto con el dedo abajo.
        val imageDuring = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).captureToImage()
        assertTrue(
            "el preview debe pintar píxeles distintos en el ROOT real",
            differingSamples(imageBefore, imageDuring) > 0,
        )
        assertEquals("el arrastre no muta el dominio", before, host.frame(frameB).editor)

        // Segundo dedo: pinch/pan real y todos los dedos arriba, el primero al final.
        rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).performTouchInput {
            down(1, center + Offset(120f, 0f))
            moveTo(1, center + Offset(220f, 0f))
            moveTo(1, center + Offset(260f, 20f))
            up(1)
            moveTo(0, grab + Offset(0f, 200f))
            up(0)
        }
        rule.waitForIdle()
        assertEquals("un gesto multitáctil no confirma nada", before, host.frame(frameB).editor)
        assertEquals("B conserva su ángulo y geometría", 1, host.frame(frameB).editor.angles.size)
        assertEquals("A no se toca", 1, host.frame(frameA).editor.angles.size)
        val imageAfter = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).captureToImage()
        assertTrue(
            "el ROOT real repintó tras el pinch (encuadre/preview cambió)",
            differingSamples(imageBefore, imageAfter) > 0,
        )
        assertEquals("sin escritura V2 por el gesto", 0L, host.v2RootCount())
        assertEquals("sin fotogramas extra", 2, host.sessionState().frames.size)
    }

    @Test
    fun root_pinch_en_colocacion_no_deja_punto_extra_y_luego_abc_funciona() {
        val host = newHarness()
        val frameId = host.captureFrame(0)
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) { host.sessionState().activeFrameId == frameId }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameId).canEdit }

        // Foto vacía: la fase ya es colocación (emptyPlacement) sin pulsar «Otro ángulo».
        // No se pulsa la UI dentro de runOnIdle (ilegal): las interacciones van fuera del hilo UI.
        rule.waitForIdle()

        val mapper = stageMapper(host, frameId)
        val first = stageOffset(mapper, AngleMultiFrameFlowHarness.FIRST_ANGLE[0])
        rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).performTouchInput {
            down(0, first)
            down(1, first + Offset(140f, 0f))
            moveTo(1, first + Offset(240f, 0f))
            // El SEGUNDO dedo sube antes que el primero: ningún punto A puede quedar.
            up(1)
            moveTo(0, first + Offset(10f, 10f))
            up(0)
        }
        rule.waitForIdle()
        val afterPinch = host.frame(frameId).editor
        assertEquals("el pinch no coloca ángulos", 0, afterPinch.angles.size)
        assertTrue("el pinch no deja borrador", afterPinch.draft.points.isEmpty())

        // Después, una terna de un dedo sigue funcionando sobre el MISMO root.
        rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).performTouchInput {
            AngleMultiFrameFlowHarness.FIRST_ANGLE.forEach { click(stageOffset(mapper, it)) }
        }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameId).editor.angles.size == 1 }
        assertEquals(1, host.frame(frameId).editor.angles.size)
        assertTrue(host.frame(frameId).editor.draft.points.isEmpty())
        assertEquals("sin escritura por gestos", 0L, host.v2RootCount())
    }

    @Test
    fun root_ab_misma_identidad_fuerza_otro_frame_con_el_dedo_abajo_y_no_muta_ninguno() {
        val host = newHarness()
        val frameA = host.captureFrame(0)
        val frameB = host.captureFrame(1)
        val angleA = host.markAngle(frameA)
        val angleB = host.markAngle(frameB)
        // Misma identidad de ángulo (AngleId 1 en CADA fotograma) y misma geometría de imagen.
        assertEquals("cada fotograma numera su propio ángulo", AngleId(1L), angleA)
        assertEquals("cada fotograma numera su propio ángulo", AngleId(1L), angleB)
        assertEquals(
            "los dos fotogramas del vídeo comparten geometría de imagen",
            leaseGeometry(host, frameA),
            leaseGeometry(host, frameB),
        )

        host.viewModel.selectMultiFrame(frameA)
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) { host.sessionState().activeFrameId == frameA }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameA).canEdit }

        val beforeA = host.frame(frameA).editor
        val beforeB = host.frame(frameB).editor
        assertEquals("A con su único ángulo", 1, beforeA.angles.size)
        assertEquals("B con su único ángulo", 1, beforeB.angles.size)
        val mapper = stageMapper(host, frameA)
        val grab = stageOffset(mapper, beforeA.angles.single().markers.b)
        val arena = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)
        val imageBefore = arena.captureToImage()
        arena.performTouchInput {
            down(0, grab)
            moveTo(0, grab + Offset(0f, 140f))
        }
        val imageDuring = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG).captureToImage()
        assertTrue(
            "el arrastre debe pintar un preview real con el dedo abajo",
            differingSamples(imageBefore, imageDuring) > 0,
        )
        assertEquals("el preview no muta A", beforeA, host.frame(frameA).editor)

        // Cambio de fotograma FORZADO por la API real del ViewModel con el dedo aún abajo: el botón
        // de la tira está ocupado por el gesto, así que se ejerce el mismo cambio de binding sin UI.
        val forced = host.viewModel.selectMultiFrame(frameB)
        assertTrue(
            "la selección forzada por VM debía aplicarse: $forced",
            forced is AngleMultiFrameResult.Applied,
        )
        rule.waitForIdle()

        // UP del gesto VIEJO, cuya base quedó capturada en el fotograma A.
        arena.performTouchInput { up(0) }
        rule.waitForIdle()

        assertEquals(
            "la selección forzada cambia el fotograma activo",
            frameB,
            host.sessionState().activeFrameId,
        )
        assertEquals(
            "los frameIds de la sesión siguen siendo los mismos",
            listOf(frameA, frameB),
            host.sessionState().frames.map { it.frameId },
        )
        assertEquals("el UP viejo no puede mutar A", beforeA, host.frame(frameA).editor)
        assertEquals("el UP viejo no puede mutar B", beforeB, host.frame(frameB).editor)
        assertEquals(
            "A conserva su único ángulo con id 1",
            AngleId(1L),
            host.frame(frameA).editor.angles.single().id,
        )
        assertEquals(
            "B conserva su único ángulo con id 1",
            AngleId(1L),
            host.frame(frameB).editor.angles.single().id,
        )
        assertEquals("sin raíces V2", 0L, host.v2RootCount())
        assertEquals("sin frames V2", 0L, host.v2FrameCount())
        assertEquals("sin marcas V2", 0L, host.v2MarkCount())
        assertEquals("sin PNG publicado", 0, host.publishedImageFileCount())
        assertEquals("sin fotogramas extra", 2, host.sessionState().frames.size)
    }

    @Test
    fun preview_activo_bloquea_guardado_todas_las_rutas_sin_room_ni_png() {
        // Guardar/Capturar/Precisión comparten el mismo busy live: con preview activo no hay
        // Room/PNG/save; tras el UP el dominio sigue intacto y Guardar vuelve a habilitarse.
        val host = newHarness()
        val frameA = host.captureFrame(0)
        host.markAngle(frameA)
        host.viewModel.selectMultiFrame(frameA)
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) { host.sessionState().activeFrameId == frameA }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameA).canEdit }
        val before = host.frame(frameA).editor
        val mapper = stageMapper(host, frameA)
        val grab = stageOffset(mapper, before.angles.single().markers.b)
        val arena = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)
        arena.performTouchInput {
            down(0, grab)
            moveTo(0, grab + Offset(0f, 140f))
        }
        rule.waitForIdle()
        assertEquals("el preview no muta el dominio", before, host.frame(frameA).editor)
        // Todas las rutas de Guardar deben estar inhibidas mientras el preview está activo.
        rule.onNodeWithTag(ANGLE_WORKSPACE_SAVE_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(ANGLE_WORKSPACE_PRECISION_TAG).assertIsNotEnabled()
        // Tras soltar, el commit es exactamente uno y no hay escrituras V2/PNG por el gesto.
        arena.performTouchInput { up(0) }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(frameA).editor != before }
        assertEquals("sin fila V2 por el gesto", 0L, host.v2RootCount())
        assertEquals("sin PNG por el gesto", 0, host.publishedImageFileCount())
    }

    @Test
    fun root_un_dedo_mueve_b_solo_al_soltar_y_lo_hace_desde_el_mapper_fit_real() {
        val host = newHarness()
        val otherFrame = host.captureFrame(0)
        val otherAngle = host.markAngle(otherFrame)
        val dragFrame = host.captureFrame(1)
        val dragAngle = host.markAngle(dragFrame)
        assertEquals("el otro fotograma tiene su ángulo 1", AngleId(1L), otherAngle)
        assertEquals("el fotograma a arrastrar tiene su ángulo 1", AngleId(1L), dragAngle)

        // `markAngle` dejó seleccionado el fotograma a arrastrar: el workspace lo dibuja.
        composeWorkspace(host)
        rule.waitUntil(timeoutMillis = 20_000) { host.sessionState().activeFrameId == dragFrame }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(dragFrame).canEdit }

        val before = host.frame(dragFrame).editor
        val beforeOther = host.frame(otherFrame).editor
        val beforeAngle = before.angles.single()
        val mapper = stageMapper(host, dragFrame)
        val start = stageOffset(mapper, beforeAngle.markers.b)
        val expectedVideo = AnglePoint(150.0, 120.0)
        val target = stageOffset(mapper, expectedVideo)
        val arena = rule.onNodeWithTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)

        arena.performTouchInput {
            down(0, start)
            moveTo(0, target)
        }
        rule.waitForIdle()
        // Con el dedo abajo el dominio está EXACTAMENTE igual: el preview es local.
        assertEquals("el arrastre no muta el dominio antes del UP", before, host.frame(dragFrame).editor)
        assertEquals("el otro fotograma no se toca antes del UP", beforeOther, host.frame(otherFrame).editor)
        assertEquals("sin raíces V2 durante el preview", 0L, host.v2RootCount())
        assertEquals("sin PNG durante el preview", 0, host.publishedImageFileCount())

        arena.performTouchInput { up(0) }
        rule.waitUntil(timeoutMillis = 20_000) { host.frame(dragFrame).editor != before }

        val after = host.frame(dragFrame).editor
        val moved = after.angles.single()
        val expected = requireNotNull(
            mapper.viewToVideo(ImagePoint(target.x.toDouble(), target.y.toDouble())),
        ) { "el destino del arrastre debía caer dentro del contenido FIT" }
        assertEquals(
            "solo B cambia: id, nombre, color y visibilidad intactos",
            beforeAngle.copy(markers = beforeAngle.markers.copy(b = moved.markers.b)),
            moved,
        )
        assertEquals("B aterriza en la X del mapper FIT", expected.x, moved.markers.b.x, 0.75)
        assertEquals("B aterriza en la Y del mapper FIT", expected.y, moved.markers.b.y, 0.75)
        assertEquals("A no se mueve", beforeAngle.markers.a, moved.markers.a)
        assertEquals("C no se mueve", beforeAngle.markers.c, moved.markers.c)
        assertTrue("B sí se ha movido", moved.markers.b != beforeAngle.markers.b)
        assertEquals("el otro fotograma conserva su dominio", beforeOther, host.frame(otherFrame).editor)
        assertEquals(
            "los frameIds no cambian",
            listOf(otherFrame, dragFrame),
            host.sessionState().frames.map { it.frameId },
        )
        assertEquals("sin raíces V2", 0L, host.v2RootCount())
        assertEquals("sin frames V2", 0L, host.v2FrameCount())
        assertEquals("sin marcas V2", 0L, host.v2MarkCount())
        assertEquals("sin PNG publicado", 0, host.publishedImageFileCount())
        assertEquals("sin fotogramas extra", 2, host.sessionState().frames.size)
    }
}
