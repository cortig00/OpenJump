package com.openjump.app.ui.angles

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorDefaults
import com.openjump.app.angles.AngleEditorOutcome
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameAnchor
import com.openjump.app.angles.AngleFrameConfirmation
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.ui.theme.OpenJumpTheme
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la parte visual del editor de ángulos.
 *
 * Cubren solo lo que los tests JVM no pueden: composición real de [AngleEditorContent], semántica
 * de selección/visibilidad, diálogos con campos de texto y entrega de intenciones al llamante. No
 * ejecutan vídeo real, no abren un ViewModel, no validan aspecto visual ni la alineación píxel a
 * píxel de la capa sobre un player.
 *
 * El estado del editor se construye con la API pública de dominio (`placePoint` + `select`), nunca a
 * mano, para que la lista use grados reales.
 */
@RunWith(AndroidJUnit4::class)
class AngleEditorScreenInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "video:content://openjump/angle-editor-screen-instrumented"
    private val generation = 9L
    private val anchor = AngleFrameAnchor(source, frameIndex = 3, sourcePtsUs = 100_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFE53935.toInt())
    private val angleName = "Angle 1"

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
        listOf(a, b, c).forEach { point ->
            state = applied(state.placePoint(point, confirmation))
        }
        return applied(state.select(AngleId(1L)))
    }

    /**
     * Editor con [points] puntos ya colocados del borrador (A, A+B) y el ancla del frame confirmado.
     *
     * Es el estado que devuelve el ViewModel tras recrear la Activity: el borrador sobrevive fuera
     * de la composición, así que una composición nueva tiene que poder seguir colocando el punto
     * siguiente.
     */
    private fun editorWithDraft(points: List<AnglePoint>): AngleEditorState {
        var state = emptyEditor()
        points.forEach { point ->
            state = applied(state.placePoint(point, confirmation))
        }
        return state
    }

    private fun degreesText(degrees: Double): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val numberFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return context.getString(R.string.angle_degrees_value).format(
            numberFormat.format(degrees),
        )
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
        saveState: AngleSaveState = AngleSaveState.Idle,
        confirmation: AngleFrameConfirmation? = this.confirmation,
        displayState: FrameDisplayState = FrameDisplayState(
            requestedIndex = anchor.frameIndex,
            renderedIndex = anchor.frameIndex,
            renderedPtsUs = anchor.sourcePtsUs,
            mode = ViewerTransportMode.PAUSED,
        ),
        actions: AngleEditorContentActions = noopActions(),
        transport: AngleEditorTransportActions? = null,
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
                    saveState = saveState,
                    displayState = displayState,
                    player = null,
                    videoGeometry = VideoPresentationGeometry(200, 100),
                    loading = false,
                    errorMessage = null,
                    confirmation = confirmation,
                    actions = actions,
                    onRefreshFrame = {},
                    transport = transport,
                )
            }
        }
    }

    @Test
    fun lista_muestra_nombre_y_grados_y_expone_la_seleccion() {
        setContent(editorWithAngle())
        rule.onNodeWithText(angleName).assertExists().assertIsSelected()
        rule.onNodeWithText(degreesText(90.0)).assertExists()
    }

    @Test
    fun el_interruptor_de_visibilidad_emite_set_visible() {
        val changes = mutableListOf<Pair<AngleId, Boolean>>()
        setContent(
            editor = editorWithAngle(),
            actions = noopActions().copy(onSetVisible = { id, visible -> changes += id to visible }),
        )
        rule.onNodeWithContentDescription(
            context.getString(R.string.angle_hide, angleName),
        ).performClick()
        assertEquals(listOf(AngleId(1L) to false), changes)
    }

    @Test
    fun renombrar_desde_la_fila_seleccionada_envia_el_nombre_nuevo() {
        val renames = mutableListOf<Pair<AngleId, String>>()
        setContent(
            editor = editorWithAngle(),
            actions = noopActions().copy(onRename = { id, name -> renames += id to name }),
        )
        rule.onNodeWithText(context.getString(R.string.angle_rename)).performClick()
        rule.onNodeWithText(context.getString(R.string.angle_rename_title)).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("Rodilla")
        rule.onNode(
            hasAnyAncestor(isDialog()) and
                hasText(context.getString(R.string.common_save)),
        ).performClick()
        assertEquals(listOf(AngleId(1L) to "Rodilla"), renames)
    }

    @Test
    fun eliminar_exige_confirmacion_y_no_borra_al_abrir_el_dialogo() {
        val deleted = mutableListOf<AngleId>()
        setContent(
            editor = editorWithAngle(),
            actions = noopActions().copy(onDelete = { deleted += it }),
        )
        rule.onNodeWithText(context.getString(R.string.angle_delete)).performClick()
        rule.onNodeWithText(context.getString(R.string.angle_delete_title)).assertExists()
        assertTrue("abrir el diálogo no debe borrar", deleted.isEmpty())
        rule.onNode(
            hasAnyAncestor(isDialog()) and
                hasText(context.getString(R.string.angle_delete)),
        ).performClick()
        assertEquals(listOf(AngleId(1L)), deleted)
    }

    @Test
    fun guardar_y_cancelar_emiten_sus_acciones_de_salida() {
        var saves = 0
        var cancels = 0
        setContent(
            editor = editorWithAngle(),
            actions = noopActions().copy(
                onSave = { saves += 1 },
                onCancel = { cancels += 1 },
            ),
        )
        rule.onNodeWithText(context.getString(R.string.common_save)).performClick()
        rule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun un_error_de_guardado_se_anuncia_en_texto_localizado() {
        setContent(editorWithAngle(), saveState = AngleSaveState.Failed("detalle técnico"))
        rule.onNodeWithText(context.getString(R.string.angle_save_failed)).assertExists()
    }

    @Test
    fun sin_frame_confirmado_no_se_puede_anadir_y_se_explica_el_motivo() {
        setContent(editorWithAngle(), confirmation = null)
        rule.onNodeWithText(context.getString(R.string.angle_add)).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_frame_waiting)).assertExists()
    }

    @Test
    fun anadir_angulo_entra_en_modo_colocacion_sobre_el_frame_confirmado() {
        setContent(emptyEditor())
        rule.onNodeWithText(context.getString(R.string.angle_add)).performScrollTo().performClick()
        rule.onNodeWithText(
            context.getString(R.string.angle_placement_hint, "A", 1),
        ).assertExists()
    }

    @Test
    fun tras_recrear_con_A_el_editor_arranca_en_colocacion_de_B() {
        // Recrear la Activity reinicia la composición pero no el borrador del ViewModel: el modo
        // inicial se deriva del borrador vivo para no perder el ángulo a medias.
        setContent(editorWithDraft(listOf(a)))
        rule.onNodeWithText(
            context.getString(R.string.angle_placement_hint, "B", 2),
        ).assertExists()
    }

    @Test
    fun tras_recrear_con_A_y_B_el_editor_arranca_en_colocacion_de_C() {
        setContent(editorWithDraft(listOf(a, b)))
        rule.onNodeWithText(
            context.getString(R.string.angle_placement_hint, "C", 3),
        ).assertExists()
        // El descarte explícito del borrador sigue disponible.
        rule.onNodeWithText(context.getString(R.string.angle_cancel_draft)).assertExists()
    }

    @Test
    fun anadir_con_A_y_B_pendientes_no_descarta_el_borrador() {
        var cancels = 0
        setContent(
            editor = editorWithDraft(listOf(a, b)),
            actions = noopActions().copy(onCancelDraft = { cancels += 1 }),
        )

        rule.onNodeWithText(context.getString(R.string.angle_add)).performScrollTo().performClick()

        assertEquals("Añadir no debe descartar A/B", 0, cancels)
        rule.onNodeWithText(
            context.getString(R.string.angle_placement_hint, "C", 3),
        ).assertExists()
    }

    @Test
    fun descartar_el_borrador_es_una_accion_explicita() {
        var cancels = 0
        setContent(
            editor = editorWithDraft(listOf(a, b)),
            actions = noopActions().copy(onCancelDraft = { cancels += 1 }),
        )

        rule.onNodeWithText(context.getString(R.string.angle_cancel_draft)).performScrollTo().performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun sin_frame_confirmado_el_borrador_pendiente_se_conserva_y_no_se_completa() {
        // Reproducción o re-seek: el frame deja de estar confirmado. El borrador A/B no se pierde ni
        // se completa por su cuenta (no se inventa C); la colocación queda deshabilitada hasta que
        // vuelva la confirmación real, y el descarte explícito sigue disponible.
        var places = 0
        var cancels = 0
        setContent(
            editor = editorWithDraft(listOf(a, b)),
            confirmation = null,
            // Reproducción: el visor ya no está en pausa sobre el frame pedido.
            displayState = FrameDisplayState(
                requestedIndex = anchor.frameIndex,
                renderedIndex = anchor.frameIndex + 1,
                renderedPtsUs = anchor.sourcePtsUs + 40_000L,
                mode = ViewerTransportMode.PLAYING,
            ),
            actions = noopActions().copy(
                onPlacePoint = { places += 1; true },
                onCancelDraft = { cancels += 1 },
            ),
        )

        rule.onNodeWithText(context.getString(R.string.angle_add)).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_cancel_draft)).assertExists()
        assertEquals("sin confirmación no se coloca ningún punto", 0, places)
        assertEquals("el borrador no se descarta solo", 0, cancels)
    }

    @Test
    fun sin_acciones_de_transporte_no_se_muestran_controles_falsos() {
        setContent(editorWithAngle())
        rule.onNodeWithContentDescription(
            context.getString(R.string.selector_play_video),
        ).assertDoesNotExist()
    }

    @Test
    fun con_acciones_de_transporte_se_reutilizan_los_controles_del_selector() {
        setContent(
            editor = editorWithAngle(),
            transport = AngleEditorTransportActions(
                onPreviousFrame = {},
                onTogglePlayback = {},
                onNextFrame = {},
            ),
        )
        rule.onNodeWithContentDescription(
            context.getString(R.string.selector_play_video),
        ).assertExists()
    }

    @Test
    fun la_convencion_del_indice_aplica_la_rotacion_no_aplicada() {
        // La pantalla ya no deriva geometría del player: consume la que publica el ViewModel desde
        // el índice real. Este guard fija la convención de rotación que esa geometría debe respetar.
        assertEquals(
            VideoPresentationGeometry(1080, 1920),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 90),
        )
        assertEquals(
            VideoPresentationGeometry(1920, 1080),
            VideoPresentationGeometry.fromEncoded(1920, 1080, 0),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VideoPresentationGeometry.fromEncoded(0, 1080, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoPresentationGeometry.fromEncoded(1920, 1080, 45)
        }
    }
}
