package com.openjump.app.ui.angles

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.FrameDisplayState
import com.openjump.app.ui.selector.ViewerTransportMode
import com.openjump.app.ui.theme.OpenJumpTheme
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del visor de ángulos: tamaño ampliado, zoom/reinicio y vuelta atrás.
 *
 * Montan [AngleEditorContent] (la misma parte visual que usa la pantalla real) con el estado
 * elevado, igual que producción, y el [AngleEditorBackHandler] REAL de producción. No abren vídeo,
 * no crean ViewModel ni player, y no validan fidelidad píxel a píxel: solo comportamiento de layout
 * y semántica que las pruebas JVM no pueden medir.
 */
@RunWith(AndroidJUnit4::class)
class AngleViewportInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "video:content://openjump/angle-viewport-instrumented"
    private val generation = 11L
    private val anchor = AngleFrameAnchor(source, frameIndex = 2, sourcePtsUs = 90_000L)
    private val confirmation = AngleFrameConfirmation(anchor, generation, paused = true)
    private val defaults = AngleEditorDefaults(namePrefix = "Angle", colorArgb = 0xFFE53935.toInt())
    private val angleName = "Angle 1"

    private lateinit var expandedState: MutableState<Boolean>
    private var exitCount = 0

    private fun applied(outcome: AngleEditorOutcome): AngleEditorState =
        (outcome as? AngleEditorOutcome.Applied)?.state
            ?: throw AssertionError("Esperaba Applied, llegó $outcome")

    private fun editorWithAngle(): AngleEditorState {
        var state = AngleEditorState.initial(source, generation, defaults)
        listOf(
            AnglePoint(20.0, 20.0),
            AnglePoint(50.0, 50.0),
            AnglePoint(80.0, 20.0),
        ).forEach { point ->
            state = applied(state.placePoint(point, confirmation))
        }
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
        modifier: Modifier = Modifier,
        fontScale: Float = 1f,
    ) {
        expandedState = mutableStateOf(false)
        exitCount = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
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
                        // Vertical: es el caso que pierde alto con un panel 16:9 fijo.
                        videoGeometry = VideoPresentationGeometry(1080, 1920),
                        loading = false,
                        errorMessage = null,
                        confirmation = confirmation,
                        actions = noopActions(),
                        onRefreshFrame = {},
                        modifier = modifier,
                        viewportExpanded = expandedState.value,
                        onViewportExpandedChange = { expandedState.value = it },
                    )
                    // BackHandler real de producción: la precedencia colapsar-antes-de-salir se
                    // prueba aquí, no con una copia en el test.
                    AngleEditorBackHandler(
                        saving = false,
                        multiFrameMode = false,
                        viewportExpanded = expandedState.value,
                        onLeaveMultiFrame = {},
                        onCollapseViewport = { expandedState.value = false },
                        onExit = { exitCount += 1 },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun paneBounds(): Rect =
        rule.onNodeWithTag(ANGLE_VIDEO_PANE_TAG).fetchSemanticsNode().boundsInRoot

    private fun back() {
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
    }

    private fun zoomValueText(zoom: Double): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val numberFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return context.getString(R.string.angle_zoom_value).format(numberFormat.format(zoom))
    }

    @Test
    fun expandir_aumenta_el_visor_y_conserva_el_editor() {
        setContent(editor = editorWithAngle())
        val normalHeight = paneBounds().height
        rule.onNodeWithTag(ANGLE_VIEWPORT_EXPAND_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()

        val expandedHeight = paneBounds().height
        assertTrue(
            "el visor ampliado debe crecer ($normalHeight -> $expandedHeight)",
            expandedHeight > normalHeight,
        )
        // Antes, ampliar ocultaba modo/lista: «Añadir ángulo» debe seguir disponible.
        rule.onNodeWithText(context.getString(R.string.angle_add)).assertExists()

        back()
        assertEquals(0, exitCount)
        assertEquals(normalHeight.toDouble(), paneBounds().height.toDouble(), 1.0)
        // El ángulo no se pierde en el viaje de ida y vuelta.
        rule.onNodeWithText(angleName).assertExists()
    }

    @Test
    fun zoom_sube_y_el_reinicio_vuelve_a_la_imagen_completa() {
        setContent(editor = editorWithAngle())
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_OUT_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_IN_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()

        val zoomedText = zoomValueText(1.5)
        rule.onNodeWithText(zoomedText).assertExists()
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_RESET_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNodeWithText(zoomedText).assertDoesNotExist()
        // 1x vuelve a ser la imagen completa: alejar ya no tiene sentido.
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_OUT_TAG).assertIsNotEnabled()
    }

    @Test
    fun atras_reduce_el_visor_antes_de_plantear_la_salida() {
        setContent(editor = editorWithAngle())
        val normalHeight = paneBounds().height
        rule.onNodeWithTag(ANGLE_VIEWPORT_EXPAND_TAG).performClick()
        rule.waitForIdle()
        assertTrue(paneBounds().height > normalHeight)

        back()
        assertEquals("el primer atrás solo reduce el visor", 0, exitCount)
        assertEquals(normalHeight.toDouble(), paneBounds().height.toDouble(), 1.0)

        // Sin visor ampliado, el siguiente atrás sí plantea la salida normal (no un no-op).
        back()
        assertEquals(1, exitCount)
    }

    @Test
    fun la_barra_sigue_visible_con_poco_alto_y_fuente_grande() {
        setContent(
            editor = editorWithAngle(),
            modifier = Modifier.height(420.dp),
            fontScale = 2f,
        )
        rule.onNodeWithTag(ANGLE_VIEWPORT_EXPAND_TAG).assertIsDisplayed().performClick()
        rule.waitForIdle()

        // La barra queda FUERA del vídeo y no la tapa ni se sale de la pantalla.
        rule.onNodeWithTag(ANGLE_VIEWPORT_EXPAND_TAG).assertIsDisplayed()
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_IN_TAG).assertIsDisplayed()
        rule.onNodeWithTag(ANGLE_VIEWPORT_ZOOM_RESET_TAG).assertIsDisplayed()
        val pane = paneBounds()
        assertTrue("el visor debe caber en el alto disponible (${pane.height})", pane.height <= 420f)
        assertTrue("el visor no debe empujar la barra fuera (${pane.bottom})", pane.bottom <= 420f)
    }
}
