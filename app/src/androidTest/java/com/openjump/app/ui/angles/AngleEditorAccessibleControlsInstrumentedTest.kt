package com.openjump.app.ui.angles

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del **camino accesible de la pantalla real** del editor de ángulos.
 *
 * Componen [AngleEditorContent] (la misma parte visual que usa [AngleEditorScreen] en producción, no
 * la capa aislada) y comprueban que TalkBack/Switch Access puede colocar A/B/C del borrador y ajustar
 * las tres marcas de un ángulo existente con controles semánticos:
 *
 * - los puntos confirmados van en coordenadas de imagen, dentro de los límites del vídeo;
 * - los pasos son los del cursor accesible (2 % del lado menor, mínimo 4 px);
 * - sin frame confirmado o con la geometría de otro frame los controles están apagados y se explica
 *   el motivo (nunca se confirma un punto que las guardas rechazarían);
 * - la acción personalizada de la capa abre el panel, que es la vía de descubrimiento en TalkBack;
 * - los objetivos táctiles cumplen 48dp.
 *
 * No abren ViewModel, no ejecutan vídeo real y no validan aspecto píxel a píxel.
 */
@RunWith(AndroidJUnit4::class)
class AngleEditorAccessibleControlsInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "video:content://openjump/angle-accessible-instrumented"
    private val generation = 7L
    private val anchor = AngleFrameAnchor(source, frameIndex = 3, sourcePtsUs = 100_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val otherFrameConfirmation = AngleFrameConfirmation(
        AngleFrameAnchor(source, frameIndex = 4, sourcePtsUs = 133_000L),
        generation,
        paused = true,
    )
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFE53935.toInt())
    private val geometry = VideoPresentationGeometry(200, 100)

    private val a = AnglePoint(20.0, 20.0)
    private val b = AnglePoint(50.0, 50.0)
    private val c = AnglePoint(80.0, 20.0)

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun emptyEditor(): AngleEditorState =
        AngleEditorState.initial(source, generation, defaults)

    private fun editorWithAngle(): AngleEditorState {
        var state = emptyEditor()
        listOf(a, b, c).forEach { point -> state = applied(state.placePoint(point, confirmation)) }
        return applied(state.select(AngleId(1L)))
    }

    private fun noopActions(): AngleEditorContentActions = AngleEditorContentActions(
        onSelectAngle = {},
        onPlacePoint = { false },
        onMoveMarker = { _, _, _ -> false },
        onMoveDraftMarker = { _, _ -> false },
        onCancelDraft = {},
        onSetVisible = { _, _ -> },
        onRename = { _, _ -> },
        onSetColor = { _, _ -> },
        onDelete = {},
        onSave = {},
        onCancel = {},
    )

    private fun setContent(
        editor: AngleEditorState?,
        confirmation: AngleFrameConfirmation? = this.confirmation,
        actions: AngleEditorContentActions = noopActions(),
    ) {
        rule.setContent {
            OpenJumpTheme {
                AngleEditorContent(
                    editor = editor,
                    videoState = AngleVideoState.Ready(
                        frameCount = 12,
                        frameIndex = anchor.frameIndex,
                        sourcePtsUs = anchor.sourcePtsUs,
                    ),
                    saveState = AngleSaveState.Idle,
                    displayState = FrameDisplayState(
                        requestedIndex = anchor.frameIndex,
                        renderedIndex = anchor.frameIndex,
                        renderedPtsUs = anchor.sourcePtsUs,
                        mode = ViewerTransportMode.PAUSED,
                    ),
                    player = null,
                    videoGeometry = geometry,
                    loading = false,
                    errorMessage = null,
                    confirmation = confirmation,
                    actions = actions,
                    onRefreshFrame = {},
                    transport = null,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun expandPanel() {
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_TOGGLE_TAG).performClick()
        rule.waitForIdle()
    }

    private fun startPlacement() {
        rule.onNodeWithText(context.getString(R.string.angle_add)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun colocar_a_desde_los_controles_accesibles_usa_coordenadas_de_imagen() {
        val placed = mutableListOf<AnglePoint>()
        setContent(
            editor = emptyEditor(),
            actions = noopActions().copy(onPlacePoint = { point -> placed += point; true }),
        )
        startPlacement()
        expandPanel()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG)
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.waitForIdle()
        assertEquals(1, placed.size)
        val point = placed.single()
        assertTrue("el punto debe ser finito: $point", point.isFinite)
        assertTrue("x dentro de la imagen: ${point.x}", point.x in 0.0..199.0)
        assertTrue("y dentro de la imagen: ${point.y}", point.y in 0.0..99.0)
        // Sin avanzar por su cuenta: el siguiente punto sigue siendo A hasta que el estado cambie.
        rule.onNodeWithText(context.getString(R.string.angle_accessible_place, "A")).assertExists()
    }

    @Test
    fun los_pasos_mueven_el_cursor_la_distancia_del_cursor_accesible() {
        val placed = mutableListOf<AnglePoint>()
        setContent(
            editor = emptyEditor(),
            actions = noopActions().copy(onPlacePoint = { point -> placed += point; true }),
        )
        startPlacement()
        expandPanel()
        // Geometría 200x100 → paso = 4 px; el cursor parte del centro (100, 50).
        rule.onNodeWithTag(angleAccessibleStepTag("right")).performClick()
        rule.onNodeWithTag(angleAccessibleStepTag("right")).performClick()
        rule.onNodeWithTag(angleAccessibleStepTag("up")).performClick()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(AnglePoint(108.0, 46.0)), placed)
    }

    @Test
    fun ajustar_la_marca_b_de_un_angulo_existente_emite_move_marker() {
        val moves = mutableListOf<Triple<AngleId, AngleMarker, AnglePoint>>()
        setContent(
            editor = editorWithAngle(),
            actions = noopActions().copy(
                onMoveMarker = { id, marker, point -> moves += Triple(id, marker, point); true },
            ),
        )
        expandPanel()
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.B)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(angleAccessibleStepTag("right")).performClick()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, moves.size)
        val (id, marker, point) = moves.single()
        assertEquals(AngleId(1L), id)
        assertEquals(AngleMarker.B, marker)
        // El cursor parte de la posición real de B (50, 50) y avanza un paso de 4 px.
        assertEquals(AnglePoint(54.0, 50.0), point)
    }

    @Test
    fun sin_frame_confirmado_los_controles_estan_apagados_y_lo_explican() {
        setContent(editor = editorWithAngle(), confirmation = null)
        expandPanel()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(angleAccessibleStepTag("left")).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_accessible_blocked)).assertExists()
    }

    @Test
    fun con_la_geometria_de_otro_frame_no_se_puede_ajustar() {
        // El frame renderizado no es el ancla del análisis: la capa no dibuja geometría y el panel
        // debe apagarse en vez de mover marcas de un frame con el que no coinciden.
        setContent(editor = editorWithAngle(), confirmation = otherFrameConfirmation)
        expandPanel()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_accessible_blocked)).assertExists()
    }

    @Test
    fun un_rechazo_del_editor_no_avanza_ni_aparenta_haber_colocado_el_punto() {
        var calls = 0
        setContent(
            editor = emptyEditor(),
            actions = noopActions().copy(
                onPlacePoint = { _ ->
                    calls += 1
                    // Simula la guarda del ViewModel: el frame cambió y el punto no se aplica.
                    false
                },
            ),
        )
        startPlacement()
        expandPanel()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, calls)
        // El panel sigue ofreciendo A: nunca anuncia un progreso que el estado no refleja.
        rule.onNodeWithText(context.getString(R.string.angle_accessible_place, "A")).assertExists()
        rule.onAllNodesWithText(context.getString(R.string.angle_accessible_place, "B"))
            .assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun la_accion_personalizada_de_la_capa_abre_el_panel_accesible() {
        setContent(editor = editorWithAngle())
        // Panel plegado: solo se ve el botón de mostrar.
        rule.onNodeWithText(context.getString(R.string.angle_accessible_show)).assertExists()
        rule.onNodeWithContentDescription(
            label = context.getString(R.string.angle_overlay_description, 1, 1, ""),
            substring = true,
        ).performCustomAccessibilityActionWithLabel(
            context.getString(R.string.angle_accessible_action),
        )
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).assertExists()
        rule.onNodeWithText(context.getString(R.string.angle_accessible_hide)).assertExists()
    }

    @Test
    fun los_objetivos_tactiles_del_panel_cumplen_48dp() {
        setContent(editor = editorWithAngle())
        expandPanel()
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_TOGGLE_TAG).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).assertHeightIsAtLeast(48.dp)
        AngleMarker.entries.forEach { marker ->
            rule.onNodeWithTag(angleAccessibleMarkerTag(marker)).assertHeightIsAtLeast(48.dp)
        }
        listOf("left", "up", "down", "right").forEach { direction ->
            rule.onNodeWithTag(angleAccessibleStepTag(direction)).assertHeightIsAtLeast(48.dp)
        }
    }
}
