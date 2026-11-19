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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de interacción real de [AngleOverlay].
 *
 * Solo cubren el contrato de plataforma que los tests JVM no pueden comprobar: publicación de
 * semántica/acción accesible, entrega de toques al llamante, ENCUADRAR/deshabilitado sin entrega y
 * arrastre de un asa activa. No validan el aspecto visual (color, grosor, legibilidad sobre vídeo)
 * ni ejecutan vídeo real.
 */
@RunWith(AndroidJUnit4::class)
class AngleOverlayInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val source = "video:content://openjump/angles-overlay-instrumented"
    private val generation = 11L
    private val anchor = AngleFrameAnchor(source, frameIndex = 3, sourcePtsUs = 100_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFFF6E40.toInt())

    private val a = AnglePoint(99.5, 10.0)
    private val b = AnglePoint(99.5, 49.5)
    private val c = AnglePoint(150.0, 49.5)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun emptyEditor(): AngleEditorState = AngleEditorState.initial(source, generation, defaults)

    private fun editorWithAngle(selected: Boolean = true): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        listOf(a, b, c).forEach { state = applied(state.placePoint(it, confirmation)) }
        return if (selected) applied(state.select(AngleId(1L))) else state
    }

    @Test
    fun publica_descripcion_y_accion_accesible_aportadas_por_el_llamante() {
        var requested = 0
        val state = editorWithAngle()
        rule.setContent {
            AngleOverlay(
                mapper = remember {
                    VideoCoordinateMapper(VideoPresentationGeometry(200, 100), ViewportSize(400.0, 100.0))
                },
                state = state,
                formatLabel = { _, degrees -> formatDegrees(degrees) },
                modifier = Modifier.size(400.dp, 100.dp),
                accessibilityDescription = "descripción localizada",
                accessibleControlsLabel = "ajustar puntos",
                onAccessibleControlsRequested = { requested += 1 },
            )
        }

        rule.onNodeWithContentDescription("descripción localizada").assertExists()
        val actions = rule.onNodeWithContentDescription("descripción localizada")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(1, actions.size)
        assertEquals("ajustar puntos", actions.single().label)
        assertTrue(actions.single().action())
        assertEquals(1, requested)
    }

    @Test
    fun un_toque_en_el_video_coloca_un_punto_y_el_letterbox_no_lo_hace() {
        val placed = mutableListOf<AnglePoint>()
        val state = emptyEditor()
        rule.setContent {
            SizedAngleOverlay(
                state = state,
                mode = AngleOverlayMode.PLACEMENT,
                onPlacePoint = { placed += it },
            )
        }

        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput {
            click(Offset(10f, center.y))
        }
        assertTrue("el letterbox no debe colocar puntos", placed.isEmpty())

        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput {
            click(center)
        }
        assertEquals(1, placed.size)
        // El centro del contenido FIT coincide con el centro del vídeo, así que el resultado no
        // depende de la densidad del dispositivo ni del tamaño exacto en píxeles del Box.
        assertEquals(99.5, placed.single().x, 1e-6)
        assertEquals(49.5, placed.single().y, 1e-6)
    }

    @Test
    fun en_modo_colocacion_el_toque_sobre_el_asa_del_seleccionado_coloca_y_no_arrastra() {
        val placed = mutableListOf<AnglePoint>()
        val moved = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        val state = editorWithAngle(selected = true)
        rule.setContent {
            SizedAngleOverlay(
                state = state,
                mode = AngleOverlayMode.PLACEMENT,
                onPlacePoint = { placed += it },
                onMoveMarker = { id, marker, point -> moved += Triple(id, marker, point) },
            )
        }

        // El centro del Box coincide con el vértice B del ángulo, que en EDIT sería un asa activa.
        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput { click(center) }
        assertEquals(1, placed.size)
        assertEquals(99.5, placed.single().x, 1e-6)
        assertEquals(49.5, placed.single().y, 1e-6)
        assertTrue("colocar no debe arrastrar asas", moved.isEmpty())

        // Un arrastre en colocación tampoco mueve asas ni consume el gesto.
        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput {
            swipe(start = center, end = center + Offset(30f, 20f), durationMillis = 200)
        }
        assertEquals("un arrastre no coloca puntos", 1, placed.size)
        assertTrue(moved.isEmpty())
    }

    @Test
    fun en_encuadrar_deshabilitado_o_frame_equivocado_la_capa_no_entrega_toques() {
        val placed = mutableListOf<AnglePoint>()
        val state = emptyEditor()
        var mode by mutableStateOf(AngleOverlayMode.FRAMING)
        var enabled by mutableStateOf(true)
        var showsGeometry by mutableStateOf(true)
        rule.setContent {
            SizedAngleOverlay(
                state = state,
                enabled = enabled,
                mode = mode,
                showsGeometry = showsGeometry,
                onPlacePoint = { placed += it },
            )
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput { click(center) }
        assertTrue("ENCUADRAR no debe consumir ni entregar toques", placed.isEmpty())

        mode = AngleOverlayMode.PLACEMENT
        rule.waitForIdle()
        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput { click(center) }
        assertEquals(1, placed.size)

        enabled = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput { click(center) }
        assertEquals("deshabilitado no debe entregar toques", 1, placed.size)

        enabled = true
        showsGeometry = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput { click(center) }
        assertEquals("un frame que no es el ancla no debe entregar toques", 1, placed.size)
    }

    @Test
    fun arrastrar_el_asa_b_del_angulo_seleccionado_emite_on_move_marker_y_no_coloca_puntos() {
        val moved = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        val placed = mutableListOf<AnglePoint>()
        val state = editorWithAngle(selected = true)
        rule.setContent {
            SizedAngleOverlay(
                state = state,
                onPlacePoint = { placed += it },
                onMoveMarker = { id, marker, point -> moved += Triple(id, marker, point) },
            )
        }

        rule.onNodeWithContentDescription(OVERLAY_DESCRIPTION).performTouchInput {
            swipe(start = center, end = center + Offset(30f, 20f), durationMillis = 200)
        }

        assertTrue("el arrastre debe emitir al menos un movimiento", moved.isNotEmpty())
        assertTrue(moved.all { it.first == AngleId(1L) && it.second == AngleMarker.B })
        assertTrue(moved.any { it.third != b })
        assertTrue("arrastrar un asa no coloca un punto nuevo", placed.isEmpty())
    }

    /**
     * Overlay con el mismo tamaño que su mapper: el lado en píxeles se conoce con [onSizeChanged],
     * de modo que las coordenadas táctiles del test coinciden con el viewport del mapper real.
     */
    @Composable
    private fun SizedAngleOverlay(
        state: AngleEditorState,
        enabled: Boolean = true,
        mode: AngleOverlayMode = AngleOverlayMode.EDIT,
        showsGeometry: Boolean = true,
        onPlacePoint: ((AnglePoint) -> Unit)? = null,
        onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Unit = { _, _, _ -> },
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
                    enabled = enabled,
                    mode = mode,
                    showsGeometry = showsGeometry,
                    accessibilityDescription = OVERLAY_DESCRIPTION,
                    onPlacePoint = onPlacePoint,
                    onMoveMarker = onMoveMarker,
                )
            }
        }
    }

    private fun formatDegrees(degrees: Double): String =
        String.format(Locale.US, "%.1f", degrees)

    private companion object {
        const val OVERLAY_DESCRIPTION = "overlay de ángulos"
    }
}
