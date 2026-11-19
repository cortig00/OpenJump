package com.openjump.app.ui.angles

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AngleMarkNameContract
import com.openjump.app.angles.AnglePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujo funcional del **inspector de precisión** ([AngleWorkspaceInspector]) montado por el
 * workspace real ([AngleEditorWorkspace]) sobre el `AngleEditorViewModel` y la sesión congelada del
 * arnés ([AngleMultiFrameFlowHarness]).
 *
 * Acredita, con estado REAL de la sesión, lo que exige este slice:
 *
 * 1. la terna A/B/C se coloca con las **mismas acciones del inspector** (cursor + confirmar), no
 *    llamando a `markAngle` del arnés: el panel mueve el cursor y el llamante aplica el punto con
 *    sus guardas de fotograma;
 * 2. ajustar B **no mueve A** y, con dos puntos A/B pendientes, se ajustan ambos sin descartar el
 *    borrador y se coloca después la C que cierra la terna;
 * 3. renombrar valida el nombre compartido (demasiado largo ⇒ Guardar deshabilitado y error
 *    completo), la visibilidad, el color y el borrado con **cancelación** preservan el id, las
 *    marcas y el ángulo del **otro** fotograma;
 * 4. la acción personalizada del Canvas abre **el mismo** inspector visible.
 *
 * Límites honestos: el arnés publica PTS con un visor falso (no hay `ExoPlayer` ni `Surface`), así
 * que esto acredita la ruta de entrada, el estado del editor y los recuentos, no píxeles
 * decodificados en pantalla; y no se ejecuta sin AVD en este slice (se compila con
 * `compileDebugAndroidTestKotlin`).
 */
@RunWith(AndroidJUnit4::class)
class AngleWorkspaceInspectorInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var harness: AngleMultiFrameFlowHarness

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        harness = AngleMultiFrameFlowHarness(productionRenderer = true)
        harness.openVideo()
        harness.openMultiFrameSession()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    private fun composeWorkspace() {
        rule.setContent {
            OpenJumpTheme {
                AngleEditorWorkspace(viewModel = harness.viewModel, onCloseRequest = {})
            }
        }
    }

    private fun openInspectorFromButton() {
        rule.onNodeWithTag(ANGLE_WORKSPACE_PRECISION_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_INSPECTOR_TAG).assertExists()
        rule.onNodeWithTag(ANGLE_INSPECTOR_CLOSE_TAG).assertExists()
    }

    private fun confirmPoint() {
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performScrollTo().performClick()
    }

    private fun step(direction: String) {
        rule.onNodeWithTag(angleAccessibleStepTag(direction)).performScrollTo().performClick()
    }

    /** Estado del editor publicado AHORA (sin bloquear el hilo de la regla dentro de `waitUntil`). */
    private fun editorNow(frameId: AngleFrameId): AngleEditorState? =
        (harness.viewModel.multiFrameState.value as? AngleMultiFrameState.Ready)?.state
            ?.frame(frameId)?.editor

    private fun draftPoints(frameId: AngleFrameId): List<AnglePoint> =
        editorNow(frameId)?.draft?.points.orEmpty()

    private fun anglesNow(frameId: AngleFrameId) = editorNow(frameId)?.angles.orEmpty()

    @Test
    fun elInspectorColocaLaTernaConElCursorYNoTocaLaPersistencia() {
        val frameId = harness.captureFrame(3)
        composeWorkspace()
        rule.waitUntil(timeoutMillis = 20_000) { editorNow(frameId) != null && harness.frame(frameId).canEdit }
        openInspectorFromButton()

        // Fotograma vacío: el panel ofrece el primer A (no una lista de ángulos vacía).
        rule.onNodeWithText(context.getString(R.string.angle_accessible_place, "A"))
            .performScrollTo()
            .assertExists()

        // A → B → C con el cursor del panel y su botón de confirmar, moviendo el cursor antes de
        // cada punto para que la terna no sea degenerada.
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) { draftPoints(frameId).size == 1 }
        step("up")
        rule.onNodeWithText(context.getString(R.string.angle_accessible_place, "B"))
            .performScrollTo()
            .assertExists()
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) { draftPoints(frameId).size == 2 }
        step("right")
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) {
            val editor = editorNow(frameId)
            editor?.let { it.angles.size == 1 && it.draft.points.isEmpty() } == true
        }

        val angle = harness.frame(frameId).editor.angles.single()
        assertEquals("la terna del cursor crea UN ángulo", 1, harness.frame(frameId).editor.angles.size)
        assertNotEquals("A y B no pueden coincidir", angle.markers.a, angle.markers.b)
        assertNotEquals("A y C no pueden coincidir", angle.markers.a, angle.markers.c)
        assertNotEquals("B y C no pueden coincidir", angle.markers.b, angle.markers.c)
        assertEquals("sin fila V2 antes de guardar", 0L, harness.v2RootCount())
    }

    @Test
    fun ajustarBYDespuesASeHaceSinDescartarElDraftYPermiteColocarC() {
        val frameId = harness.captureFrame(3)
        composeWorkspace()
        rule.waitUntil(timeoutMillis = 20_000) { editorNow(frameId) != null && harness.frame(frameId).canEdit }
        openInspectorFromButton()

        // Dos puntos pendientes A y B con el cursor del panel.
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) { draftPoints(frameId).size == 1 }
        step("up")
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) { draftPoints(frameId).size == 2 }
        val pending = draftPoints(frameId)
        val aBefore = pending[0]
        val bBefore = pending[1]

        // Ajustar B: se elige la marca B explícitamente y se mueve el cursor.
        rule.onNodeWithTag(ANGLE_INSPECTOR_ADJUST_DRAFT_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.B)).performScrollTo().performClick()
        step("left")
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) {
            draftPoints(frameId).size == 2 && draftPoints(frameId)[1] != bBefore
        }
        assertEquals("ajustar B no puede mover A", aBefore, draftPoints(frameId)[0])
        assertTrue("B sí se movió", draftPoints(frameId)[1] != bBefore)

        // Ajustar A: sigue habiendo DOS puntos (el borrador nunca se descarta al ajustar).
        val bAfter = draftPoints(frameId)[1]
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.A)).performScrollTo().performClick()
        step("down")
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) {
            draftPoints(frameId).size == 2 && draftPoints(frameId)[0] != aBefore
        }
        assertEquals("ajustar A no puede mover B", bAfter, draftPoints(frameId)[1])
        assertEquals("el borrador conserva sus dos puntos", 2, draftPoints(frameId).size)

        // Y la C que cierra la terna se coloca sin descartar A/B.
        rule.onNodeWithTag(angleAccessibleMarkerTag(AngleMarker.C)).performScrollTo().performClick()
        step("right")
        confirmPoint()
        rule.waitUntil(timeoutMillis = 20_000) {
            val editor = editorNow(frameId)
            editor?.let { it.angles.size == 1 && it.draft.points.isEmpty() } == true
        }
        val angles = harness.frame(frameId).editor.angles
        assertEquals(1, angles.size)
        assertEquals("la A ajustada es la que se guarda", draftPoints(frameId).size, 0)
        assertNotEquals("las tres marcas son distintas", angles.single().markers.a, angles.single().markers.b)
        assertEquals("sin fila V2 antes de guardar", 0L, harness.v2RootCount())
    }

    @Test
    fun renombrarVisibilidadColorYBorradoCanceladoConservanIdsMarcasYOtraFoto() {
        val firstFrame = harness.captureFrame(3)
        val secondFrame = harness.captureFrame(9)
        val firstAngleId = harness.markAngle(firstFrame, AngleMultiFrameFlowHarness.FIRST_ANGLE)
        val secondAngleId = harness.markAngle(
            secondFrame,
            AngleMultiFrameFlowHarness.SECOND_ANGLE,
        )
        // La lista del inspector muestra el fotograma ACTIVO: se vuelve al primero sin tocar nada.
        harness.viewModel.selectMultiFrame(firstFrame)
        composeWorkspace()
        rule.waitUntil(timeoutMillis = 20_000) { harness.frame(firstFrame).canEdit }
        openInspectorFromButton()

        val secondBefore = harness.frame(secondFrame).editor.angles.single()

        // 1. Renombrar: un nombre por encima del límite compartido no se puede guardar y se explica
        //    completo; el nombre válido sí se aplica.
        rule.onNodeWithText(context.getString(R.string.angle_rename))
            .performScrollTo()
            .performClick()
        val tooLong = "a".repeat(AngleMarkNameContract.MAX_CODE_UNITS + 1)
        rule.onNode(hasSetTextAction()).performTextReplacement(tooLong)
        rule.onNodeWithText(
            context.getString(
                R.string.angle_rename_too_long_error,
                AngleMarkNameContract.MAX_CODE_UNITS,
            ),
        ).assertExists()
        rule.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()

        val newName = "Ángulo reformado"
        rule.onNode(hasSetTextAction()).performTextReplacement(newName)
        rule.onNodeWithText(context.getString(R.string.common_save)).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            editorNow(firstFrame)?.angles?.single()?.name == newName
        }
        assertEquals(firstAngleId, harness.frame(firstFrame).editor.angles.single().id)

        // 2. Visibilidad: ocultar y volver a mostrar con el interruptor real de la fila.
        rule.onNodeWithContentDescription(context.getString(R.string.angle_hide, newName))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            editorNow(firstFrame)?.angles?.single()?.visible == false
        }
        rule.onNodeWithContentDescription(context.getString(R.string.angle_show, newName))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            editorNow(firstFrame)?.angles?.single()?.visible == true
        }

        // 3. Color: el botón de texto «Color» SOLO existe en la fila SELECCIONADA y ocultar el
        // ángulo limpió la selección (el show no vuelve a elegirlo). Se selecciona con la fila
        // REAL (row tag → performClick) y se exige la selección efectiva antes de abrir la paleta.
        rule.onNodeWithTag(angleInspectorRowTag(firstAngleId)).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            editorNow(firstFrame)?.selectedAngleId == firstAngleId
        }
        rule.onNodeWithText(context.getString(R.string.angle_color))
            .performScrollTo()
            .performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.angle_color_option, 2))
            .performClick()
        rule.onNodeWithText(context.getString(R.string.common_apply)).performClick()
        rule.waitUntil(timeoutMillis = 20_000) {
            editorNow(firstFrame)?.angles?.single()?.colorArgb == AngleColorPalette[1]
        }

        // 4. Borrar CANCELADO: no cambia nada (ni el id, ni las marcas, ni el otro fotograma).
        val before = harness.frame(firstFrame).editor.angles.single()
        rule.onNodeWithText(context.getString(R.string.angle_delete))
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(context.getString(R.string.angle_delete_title)).assertExists()
        rule.onAllNodesWithText(context.getString(R.string.common_cancel))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.angle_delete_title)).assertDoesNotExist()

        val after = harness.frame(firstFrame).editor.angles.single()
        assertEquals("el id del ángulo se conserva", before.id, after.id)
        assertEquals("las marcas se conservan", before.markers, after.markers)
        assertEquals("el nombre aplicado se conserva", newName, after.name)
        assertEquals("sin fila V2 antes de guardar", 0L, harness.v2RootCount())

        val secondAfter = harness.frame(secondFrame).editor.angles.single()
        assertEquals("el ángulo del otro fotograma conserva su id", secondBefore.id, secondAfter.id)
        assertEquals(
            "el ángulo del otro fotograma conserva sus marcas",
            secondBefore.markers,
            secondAfter.markers,
        )
        assertEquals(
            "el otro fotograma no recibe un borrador",
            0,
            harness.frame(secondFrame).editor.draft.points.size,
        )
        // La identidad de un ángulo es (fotograma, id): los AngleId son LOCALES al fotograma, así
        // que el primer ángulo de cada uno vale 1. Se exige que cada id original se conserve y que
        // las dos identidades compuestas sean distintas; no se inventa un id global único.
        assertEquals("el id original del otro fotograma se conserva", secondAngleId, secondAfter.id)
        assertEquals("el id original del primer fotograma se conserva", firstAngleId, after.id)
        assertNotEquals(
            "dos ángulos distintos no comparten identidad (fotograma, id)",
            firstFrame to firstAngleId,
            secondFrame to secondAngleId,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun laAccionPersonalizadaDelCanvasAbreElMismoInspector() {
        val frameId = harness.captureFrame(3)
        composeWorkspace()
        rule.waitUntil(timeoutMillis = 20_000) { editorNow(frameId) != null && harness.frame(frameId).canEdit }
        // Sin ángulos ni borrador: el lienzo describe «0 de 0» y su acción personalizada es la del
        // mismo panel accesible que el botón Precisión.
        val description = context.getString(
            R.string.angle_overlay_description,
            0,
            0,
            context.getString(R.string.angle_overlay_detail_none),
        )
        rule.onNodeWithTag(ANGLE_INSPECTOR_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription(description).performCustomAccessibilityActionWithLabel(
            context.getString(R.string.angle_workspace_precision),
        )
        rule.waitForIdle()
        rule.onNodeWithTag(ANGLE_INSPECTOR_TAG).assertExists()
        rule.onNodeWithTag(ANGLE_INSPECTOR_CLOSE_TAG).assertExists()
        // Es el MISMO inspector: el cursor accesible está ya desplegado (showToggle = false).
        rule.onNodeWithTag(ANGLE_ACCESSIBLE_CONFIRM_TAG).performScrollTo().assertIsEnabled()
        rule.onNodeWithText(context.getString(R.string.angle_accessible_place, "A")).assertExists()
        assertEquals("abrir el inspector no crea fotogramas", 1, harness.sessionState().frames.size)
        assertEquals("sin fila V2 antes de guardar", 0L, harness.v2RootCount())
    }
}
