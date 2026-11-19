package com.openjump.app.ui.angles

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleAnalysisFinalization
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Slice 1 del flujo multi-fotograma instrumentado: captura real de dos frames, marcado de varios
 * ángulos, composición de la pantalla real y contrato del **gate de producto apagado**.
 *
 * Lo que este test demuestra y lo que no:
 *
 * - **Demuestra** que el `AngleEditorViewModel` real, con la fuente congelada real
 *   ([com.openjump.app.video.angles.FrozenFrameSources]) y Room **en memoria**, captura dos frames
 *   distintos del fixture, conserva sus imágenes, acepta geometría sobre cada uno y produce un plan
 *   de finalización `Ready`. Después compone [AngleMultiFrameEditorScreen] con ese estado real y
 *   comprueba que, con el gate de producto tal como está compilado
 *   ([ANGLE_MULTI_FRAME_SAVE_UI_ENABLED] = `false`), el botón de guardado está deshabilitado, se
 *   muestra su motivo y la pantalla **no invoca** la acción de guardado: no se inserta ninguna raíz
 *   V2, no se publica ningún fichero de imagen y el estado de guardado sigue `Idle`.
 * - **No demuestra** todavía el guardado efectivo, el historial ni el compartir: eso son slices
 *   posteriores y este test no toca el gate de producción ni escribe datos. La decisión pura con el
 *   gate activado se consulta **solo como función** (`angleMultiFrameSaveAvailability(gateEnabled =
 *   true, …)`), sin pulsar el botón, para acreditar que el único bloqueo es el gate y no un plan de
 *   finalización incompleto.
 *
 * Aislamiento: base Room en memoria, raíz de imágenes con UUID en `cacheDir` y copia propia del
 * fixture, todo borrado al terminar. Nunca se abre la base de la app ni se ejecuta `AppNav`.
 *
 * Los nombres de los `@Test` deben ser `camelCase` sin espacios: las lambdas del cuerpo generan
 * clases anónimas cuyo `SimpleName` hereda el nombre del método, y con `minSdk = 26` el DEX
 * resultante es < 040, que prohíbe espacios en `SimpleName` (fallo de `dexBuilderDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class AngleMultiFrameFlowGateInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var harness: AngleMultiFrameFlowHarness
    private var saveCalls = 0

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness()
        saveCalls = 0
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    @Test
    fun dosFramesConVariosAngulosSeComponenYGateDeProduccionDesactivaElGuardado() {
        harness.openVideo()
        harness.openMultiFrameSession()

        // --- captura real de dos frames distintos y marcado de varios ángulos --------------------
        val firstFrame = harness.captureFrame(0)
        val secondFrame = harness.captureFrame(1)

        val firstAngle = harness.markAngle(firstFrame)
        harness.renameAngle(firstFrame, firstAngle, "Rodilla derecha")
        val secondAngle = harness.markAngle(secondFrame, AngleMultiFrameFlowHarness.SECOND_ANGLE)
        harness.setAngleVisible(secondFrame, secondAngle, visible = false)
        harness.setAngleVisible(secondFrame, secondAngle, visible = true)
        harness.moveMarker(secondFrame, secondAngle, AngleMarker.A, AnglePoint(44.0, 44.0))

        val state = harness.sessionState()
        assertEquals(2, state.frames.size)
        assertEquals(2, state.frames.sumOf { it.editor.angles.size })
        assertEquals("Rodilla derecha", harness.frame(firstFrame).editor.angles.single().name)
        assertTrue(
            "Las imágenes congeladas deben seguir retenidas mientras la sesión vive.",
            state.retainedBytes > 0L,
        )
        assertTrue(harness.frame(firstFrame).canEdit)
        assertTrue(harness.frame(secondFrame).canEdit)

        // El plan de finalización real ya está listo: el único bloqueo es el gate de producto.
        val finalization = state.finalization
        assertTrue("La finalización debía estar lista, era $finalization", finalization is AngleAnalysisFinalization.Ready)
        assertEquals(
            2,
            (finalization as AngleAnalysisFinalization.Ready).frames.sumOf { it.angles.size },
        )

        val readyState = AngleMultiFrameState.Ready(state)
        val gateOff = angleMultiFrameSaveAvailability(
            gateEnabled = false,
            multiFrame = readyState,
            saveState = AngleMultiFrameSaveState.Idle,
        )
        assertTrue("Con el gate apagado la acción no puede estar disponible: $gateOff", gateOff is AngleMultiFrameSaveAvailability.Blocked)
        assertEquals(
            R.string.angle_multi_frame_save_gate,
            (gateOff as AngleMultiFrameSaveAvailability.Blocked).reason.messageRes,
        )
        assertEquals(
            "Una consulta pura con el gate activado sí sería Ready: el bloqueo no es de datos.",
            AngleMultiFrameSaveAvailability.Ready,
            angleMultiFrameSaveAvailability(
                gateEnabled = true,
                multiFrame = readyState,
                saveState = AngleMultiFrameSaveState.Idle,
            ),
        )

        // --- composición de la pantalla real con el estado y las acciones reales -----------------
        rule.setContent {
            OpenJumpTheme {
                AngleMultiFrameEditorScreen(
                    multiFrame = readyState,
                    actions = harness.editorActions { saveCalls += 1 },
                    onBackToViewer = {},
                    onClose = {},
                    // El llamante de producción no pasa `saveAvailability`: se usa el valor por
                    // defecto, calculado con la constante de producto (apagada).
                    saveState = AngleMultiFrameSaveState.Idle,
                )
            }
        }

        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SCREEN_TAG).assertExists()
        rule.onNodeWithText(context.getString(R.string.angle_multi_frame_count, 2)).assertExists()
        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SAVE_TAG).assertIsNotEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_multi_frame_save_gate)).assertExists()
        rule.onNodeWithText(context.getString(R.string.angle_multi_frame_save_saved)).assertDoesNotExist()

        // Pulsar un botón deshabilitado no puede llegar a la acción: no hay guardado ni éxito falso.
        rule.onNodeWithTag(ANGLE_MULTI_FRAME_SAVE_TAG).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("La pantalla no debe invocar onSaveMultiFrame con el gate apagado.", 0, saveCalls)
        assertEquals(AngleMultiFrameSaveState.Idle, harness.saveState())
        assertEquals("No se puede insertar ninguna raíz V2 sin guardado real.", 0L, harness.v2RootCount())
        assertEquals(
            "El almacén de imágenes no debe publicar ficheros: no hubo render ni guardado.",
            0,
            harness.publishedImageFileCount(),
        )
        assertEquals(
            "Los dos fotogramas siguen en memoria tras la pulsación bloqueada.",
            2,
            harness.sessionState().frames.size,
        )
    }

    @Test
    fun gateApagadoBloqueaTambienAntesDeCapturarYNoCreaEstadoDeExito() {
        harness.openVideo()
        harness.openMultiFrameSession()

        val empty = harness.sessionState()
        val blocked = angleMultiFrameSaveAvailability(
            gateEnabled = false,
            multiFrame = AngleMultiFrameState.Ready(empty),
            saveState = AngleMultiFrameSaveState.Idle,
        )

        assertTrue(blocked is AngleMultiFrameSaveAvailability.Blocked)
        assertEquals(
            R.string.angle_multi_frame_save_gate,
            (blocked as AngleMultiFrameSaveAvailability.Blocked).reason.messageRes,
        )
        assertEquals(0L, harness.v2RootCount())
        assertEquals(AngleMultiFrameSaveState.Idle, harness.saveState())
    }
}
